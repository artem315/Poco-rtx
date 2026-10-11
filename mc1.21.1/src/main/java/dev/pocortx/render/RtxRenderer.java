package dev.pocortx.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.pocortx.compat.IrisCompat;
import dev.pocortx.config.RtxConfig;
import java.io.IOException;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Camera;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.render.DimensionEffects;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;
import net.minecraft.world.biome.Biome;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screen-space ray tracing on top of the vanilla pipeline.
 *
 * <p>Frame flow:
 * <ol>
 * <li>Before translucent terrain: pack the opaque depth buffer into an RGBA8 target.</li>
 * <li>End of world rendering: pack the final depth, trace lighting (AO, sun shadows,
 * light shafts) and reflections at reduced resolution, then blend the result into
 * the main framebuffer.</li>
 * </ol>
 * Every pass goes through vanilla {@link ShaderProgram} and {@link BufferRenderer},
 * the same path the game's own blit uses, so GL translators on Android see nothing new.
 */
public final class RtxRenderer {
    public static final RtxRenderer INSTANCE = new RtxRenderer();
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    private static final float SUN_PATH_TILT = (float) Math.toRadians(17.0);

    private ShaderProgram packProgram;
    private ShaderProgram lightProgram;
    private ShaderProgram blurProgram;
    private ShaderProgram reflectProgram;
    private ShaderProgram compositeProgram;

    private SimpleFramebuffer opaqueTarget;
    private SimpleFramebuffer finalTarget;
    private SimpleFramebuffer lightTarget;
    private SimpleFramebuffer blurTarget;
    private SimpleFramebuffer reflectTarget;

    private boolean failed;
    private String failureReason;
    private boolean failureReported;
    private boolean opaqueCaptured;
    private float fogEnd = 192.0f;

    private float wetness;
    private float skyExposure = 1.0f;
    private long lastFrameNanos;

    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f invProj = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f invView = new Matrix4f();

    private RtxRenderer() {
    }

    public boolean hasFailed() {
        return failed;
    }

    public String failureReason() {
        return failureReason;
    }

    /** Takes the failure message once so the client can show it in chat. */
    public String pollFailureNotice() {
        if (failed && !failureReported) {
            failureReported = true;
            return failureReason;
        }
        return null;
    }

    /** Called on resource reload: shaders are rebuilt on the next frame. */
    public void invalidate() {
        RenderSystem.assertOnRenderThread();
        closePrograms();
        failed = false;
        failureReason = null;
        failureReported = false;
    }

    private boolean shouldRun(WorldRenderContext context) {
        return RtxConfig.get().enabled && !failed && context.world() != null && !IrisCompat.isShaderPackInUse();
    }

    /** Fired right before translucent terrain is drawn. */
    public void captureOpaqueDepth(WorldRenderContext context) {
        opaqueCaptured = false;
        if (!shouldRun(context)) {
            return;
        }
        Framebuffer main = MinecraftClient.getInstance().getFramebuffer();
        SavedState state = SavedState.capture();
        try {
            fogEnd = RenderSystem.getShaderFogEnd();
            if (!ensurePrograms() || !ensureTargets(main, RtxConfig.get().quality.renderScale)) {
                return;
            }
            prepareFullscreenState();
            packDepth(main.getDepthAttachment(), opaqueTarget);
            opaqueCaptured = true;
        } catch (Throwable t) {
            fail("opaque depth capture", t);
        } finally {
            state.restore(main);
        }
    }

    /** Fired after the world (sky, terrain, entities, water, particles, weather) is drawn, before the hand. */
    public void renderEffects(WorldRenderContext context) {
        if (!opaqueCaptured || !shouldRun(context)) {
            opaqueCaptured = false;
            return;
        }
        opaqueCaptured = false;
        MinecraftClient client = MinecraftClient.getInstance();
        Framebuffer main = client.getFramebuffer();
        SavedState state = SavedState.capture();
        try {
            if (!ensureTargets(main, RtxConfig.get().quality.renderScale)) {
                return;
            }
            prepareFullscreenState();

            int finalDepth = main.getDepthAttachment();
            Framebuffer translucent = context.worldRenderer().getTranslucentFramebuffer();
            if (MinecraftClient.isFabulousGraphicsOrBetter() && translucent != null) {
                // Fabulous keeps water and glass depth in its own target.
                finalDepth = translucent.getDepthAttachment();
            }
            packDepth(finalDepth, finalTarget);

            FrameParams frame = computeFrame(context);
            runLightPass(frame);
            runBlurPass();
            runReflectionPass(frame, main);
            runComposite(frame, main);
        } catch (Throwable t) {
            fail("effects pass", t);
        } finally {
            state.restore(main);
        }
    }

    // ------------------------------------------------------------------
    // Frame parameters

    private static final class FrameParams {
        final Vector3f lightDirView = new Vector3f();
        final Vector3f lightColor = new Vector3f();
        float lightStrength;
        float sunStrength;
        boolean submerged;
        Vec3d skyColor;
        float[] fogColor;
        float camX;
        float camY;
        float camZ;
        float cloudHeight;
        float time;
        float fadeDistance;
    }

    private FrameParams computeFrame(WorldRenderContext context) {
        ClientWorld world = context.world();
        Camera camera = context.camera();
        float tickDelta = context.tickCounter().getTickDelta(true);
        FrameParams frame = new FrameParams();

        proj.set(context.projectionMatrix());
        proj.invert(invProj);
        view.set(context.positionMatrix());
        view.invert(invView);

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.0f : Math.min((now - lastFrameNanos) / 1.0e9f, 0.25f);
        lastFrameNanos = now;

        Vec3d camPos = camera.getPos();
        BlockPos eye = BlockPos.ofFloored(camPos);

        // Sky exposure at the camera: fades sky reflections and shadows out underground.
        float exposureTarget = world.getLightLevel(LightType.SKY, eye) / 15.0f;
        skyExposure = approach(skyExposure, exposureTarget, dt * 1.5f);

        boolean normalSky = world.getDimensionEffects().getSkyType() == DimensionEffects.SkyType.NORMAL;
        float rain = world.getRainGradient(tickDelta);
        float thunder = world.getThunderGradient(tickDelta);

        // Same rotation the sky renderer applies to the sun quad, with the path
        // tilted slightly south so north/south faces are not all edge-on to the sun.
        float skyAngle = world.getSkyAngle(tickDelta);
        Vector3f sun = new Vector3f(0.0f, 1.0f, 0.0f)
                .rotateX(skyAngle * MathHelper.TAU)
                .rotateY(-MathHelper.HALF_PI)
                .rotateX(SUN_PATH_TILT);
        Vector3f lightWorld;
        if (sun.y >= 0.0f) {
            lightWorld = sun;
            frame.lightStrength = smoothstep(-0.02f, 0.12f, sun.y);
            float warm = smoothstep(0.0f, 0.35f, sun.y);
            frame.lightColor.set(1.0f, MathHelper.lerp(warm, 0.55f, 0.95f), MathHelper.lerp(warm, 0.28f, 0.85f));
        } else {
            lightWorld = new Vector3f(sun).negate();
            frame.lightStrength = 0.35f * smoothstep(-0.02f, 0.12f, -sun.y);
            frame.lightColor.set(0.55f, 0.65f, 0.95f);
        }
        frame.lightStrength *= (1.0f - 0.8f * rain) * (1.0f - 0.5f * thunder);
        if (!normalSky) {
            frame.lightStrength = 0.0f;
        }
        frame.sunStrength = frame.lightStrength;
        frame.lightDirView.set(lightWorld).mulDirection(view).normalize();

        frame.submerged = camera.getSubmersionType() != CameraSubmersionType.NONE;

        float wetTarget = 0.0f;
        if (RtxConfig.get().rainPuddles && normalSky && rain > 0.0f
                && world.getBiome(eye).value().getPrecipitation(eye) == Biome.Precipitation.RAIN) {
            wetTarget = rain;
        }
        // Wets quickly, dries slowly.
        wetness = approach(wetness, wetTarget, dt * (wetTarget > wetness ? 0.25f : 0.04f));

        frame.skyColor = world.getSkyColor(camPos, tickDelta);
        frame.fogColor = RenderSystem.getShaderFogColor().clone();
        frame.camX = (float) (camPos.x - Math.floor(camPos.x / 2048.0) * 2048.0);
        frame.camY = (float) camPos.y;
        frame.camZ = (float) (camPos.z - Math.floor(camPos.z / 2048.0) * 2048.0);
        float clouds = world.getDimensionEffects().getCloudsHeight();
        frame.cloudHeight = Float.isNaN(clouds) ? -10000.0f : clouds;
        frame.time = ((world.getTime() % 72000L) + tickDelta) / 20.0f;

        float viewDistance = context.gameRenderer().getViewDistance();
        frame.fadeDistance = fogEnd > 1.0f ? Math.min(viewDistance, fogEnd) : viewDistance;
        return frame;
    }

    // ------------------------------------------------------------------
    // Passes

    private void runLightPass(FrameParams frame) {
        RtxConfig config = RtxConfig.get();
        RtxConfig.Quality q = config.quality;
        ShaderProgram p = lightProgram;
        setCommon(p);
        p.addSampler("OpaqueSampler", opaqueTarget.getColorAttachment());
        p.addSampler("FinalSampler", finalTarget.getColorAttachment());
        set(p, "LightDir", frame.lightDirView.x, frame.lightDirView.y, frame.lightDirView.z);
        set(p, "LightParams", frame.lightStrength, 1.1f, 14.0f, 0.0f);
        set(p, "Counts", q.aoSamples, q.shadowSteps, q.lightShaftSteps, 0.0f);
        boolean sunEffects = !frame.submerged;
        set(p, "Toggles",
                config.ambientOcclusion ? 1.0f : 0.0f,
                config.shadows && sunEffects ? 1.0f : 0.0f,
                config.lightShafts && sunEffects ? 1.0f : 0.0f,
                0.0f);
        drawFullscreen(p, lightTarget);
    }

    private void runBlurPass() {
        ShaderProgram p = blurProgram;
        set(p, "RtxInvProj", invProj);
        set(p, "TraceSize", lightTarget.textureWidth, lightTarget.textureHeight);
        p.addSampler("OpaqueSampler", opaqueTarget.getColorAttachment());
        p.addSampler("LightSampler", lightTarget.getColorAttachment());
        drawFullscreen(p, blurTarget);
    }

    private void runReflectionPass(FrameParams frame, Framebuffer main) {
        RtxConfig config = RtxConfig.get();
        if (!config.reflections || frame.submerged) {
            reflectTarget.setClearColor(0.0f, 0.0f, 0.0f, 0.0f);
            reflectTarget.clear(MinecraftClient.IS_SYSTEM_MAC);
            return;
        }
        ShaderProgram p = reflectProgram;
        setCommon(p);
        p.addSampler("OpaqueSampler", opaqueTarget.getColorAttachment());
        p.addSampler("FinalSampler", finalTarget.getColorAttachment());
        p.addSampler("SceneSampler", main.getColorAttachment());
        set(p, "LightDir", frame.lightDirView.x, frame.lightDirView.y, frame.lightDirView.z);
        float s = frame.sunStrength * skyExposure;
        set(p, "SunColor", frame.lightColor.x * s, frame.lightColor.y * s, frame.lightColor.z * s);
        set(p, "SkyColor", (float) frame.skyColor.x, (float) frame.skyColor.y, (float) frame.skyColor.z);
        set(p, "FogColor", frame.fogColor[0], frame.fogColor[1], frame.fogColor[2]);
        set(p, "CameraPos", frame.camX, frame.camY, frame.camZ);
        set(p, "ReflParams", config.quality.reflectionSteps, 64.0f, wetness, skyExposure);
        set(p, "ReflParams2", frame.time, frame.cloudHeight, frame.fadeDistance, config.waterWaves ? 1.0f : 0.0f);
        set(p, "ReflToggles", 0.9f, 0.6f, config.rainPuddles ? 1.0f : 0.0f, 0.0f);
        drawFullscreen(p, reflectTarget);
    }

    private void runComposite(FrameParams frame, Framebuffer main) {
        RtxConfig config = RtxConfig.get();
        ShaderProgram p = compositeProgram;
        set(p, "RtxInvProj", invProj);
        p.addSampler("OpaqueSampler", opaqueTarget.getColorAttachment());
        p.addSampler("FinalSampler", finalTarget.getColorAttachment());
        p.addSampler("LightSampler", blurTarget.getColorAttachment());
        p.addSampler("ReflSampler", reflectTarget.getColorAttachment());
        set(p, "TraceSize", lightTarget.textureWidth, lightTarget.textureHeight);
        float shaft = frame.sunStrength * config.lightShaftStrength * 0.6f;
        set(p, "SunColor", frame.lightColor.x, frame.lightColor.y, frame.lightColor.z);
        set(p, "Strength",
                config.ambientOcclusion ? config.aoStrength : 0.0f,
                config.shadows ? config.shadowStrength * frame.lightStrength * skyExposure : 0.0f,
                config.reflections ? config.reflectionStrength : 0.0f,
                config.lightShafts ? shaft : 0.0f);
        set(p, "Misc", config.debugView.ordinal(), frame.fadeDistance, 0.0f, 0.0f);

        RenderSystem.enableBlend();
        // result = src.rgb + dst.rgb * src.a ; destination alpha untouched
        RenderSystem.blendFuncSeparate(GL11.GL_ONE, GL11.GL_SRC_ALPHA, GL11.GL_ZERO, GL11.GL_ONE);
        drawFullscreen(p, main);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
    }

    private void packDepth(int depthTexture, Framebuffer target) {
        packProgram.addSampler("DepthSampler", depthTexture);
        drawFullscreen(packProgram, target);
    }

    private void setCommon(ShaderProgram p) {
        set(p, "RtxProj", proj);
        set(p, "RtxInvProj", invProj);
        set(p, "RtxView", view);
        set(p, "RtxInvView", invView);
        set(p, "RtxScreen", opaqueTarget.textureWidth, opaqueTarget.textureHeight);
    }

    private static void prepareFullscreenState() {
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableBlend();
    }

    private static void drawFullscreen(ShaderProgram program, Framebuffer target) {
        target.beginWrite(true);
        program.bind();
        try {
            BufferBuilder buffer = RenderSystem.renderThreadTesselator().begin(VertexFormat.DrawMode.QUADS, VertexFormats.BLIT_SCREEN);
            buffer.vertex(0.0f, 0.0f, 0.0f);
            buffer.vertex(1.0f, 0.0f, 0.0f);
            buffer.vertex(1.0f, 1.0f, 0.0f);
            buffer.vertex(0.0f, 1.0f, 0.0f);
            BufferRenderer.draw(buffer.end());
        } finally {
            program.unbind();
        }
    }

    // ------------------------------------------------------------------
    // Resources

    private boolean ensurePrograms() {
        if (compositeProgram != null) {
            return true;
        }
        if (failed) {
            return false;
        }
        ResourceFactory factory = MinecraftClient.getInstance().getResourceManager();
        try {
            packProgram = loadProgram(factory, "pocortx_pack");
            lightProgram = loadProgram(factory, "pocortx_light");
            blurProgram = loadProgram(factory, "pocortx_blur");
            reflectProgram = loadProgram(factory, "pocortx_reflect");
            compositeProgram = loadProgram(factory, "pocortx_composite");
            LOGGER.info("PocoRTX shaders compiled ({})", GlStateManager._getString(GL11.GL_RENDERER));
            return true;
        } catch (Throwable t) {
            closePrograms();
            fail("shader compilation", t);
            return false;
        }
    }

    private static ShaderProgram loadProgram(ResourceFactory factory, String name) throws IOException {
        ShaderProgram program = new ShaderProgram(factory, name, VertexFormats.BLIT_SCREEN);
        // Vanilla only logs link failures; a broken program must not be used.
        if (GlStateManager.glGetProgrami(program.getGlRef(), 0x8B82 /* GL_LINK_STATUS */) == 0) {
            String log = GlStateManager.glGetProgramInfoLog(program.getGlRef(), 32768);
            program.close();
            throw new IOException("Linking " + name + " failed: " + log);
        }
        return program;
    }

    private void closePrograms() {
        for (ShaderProgram program : new ShaderProgram[] {packProgram, lightProgram, blurProgram, reflectProgram, compositeProgram}) {
            if (program != null) {
                program.close();
            }
        }
        packProgram = null;
        lightProgram = null;
        blurProgram = null;
        reflectProgram = null;
        compositeProgram = null;
    }

    private boolean ensureTargets(Framebuffer main, float scale) {
        int w = main.textureWidth;
        int h = main.textureHeight;
        if (w <= 0 || h <= 0) {
            return false;
        }
        int tw = Math.max(1, Math.round(w * scale));
        int th = Math.max(1, Math.round(h * scale));
        opaqueTarget = ensureTarget(opaqueTarget, w, h);
        finalTarget = ensureTarget(finalTarget, w, h);
        lightTarget = ensureTarget(lightTarget, tw, th);
        blurTarget = ensureTarget(blurTarget, tw, th);
        reflectTarget = ensureTarget(reflectTarget, tw, th);
        return true;
    }

    private static SimpleFramebuffer ensureTarget(SimpleFramebuffer target, int w, int h) {
        if (target == null) {
            return new SimpleFramebuffer(w, h, false, MinecraftClient.IS_SYSTEM_MAC);
        }
        if (target.textureWidth != w || target.textureHeight != h) {
            target.resize(w, h, MinecraftClient.IS_SYSTEM_MAC);
        }
        return target;
    }

    private void fail(String stage, Throwable t) {
        failed = true;
        failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
        LOGGER.error("PocoRTX disabled after failure in {} on renderer '{}'", stage, safeRendererName(), t);
    }

    private static String safeRendererName() {
        try {
            return GlStateManager._getString(GL11.GL_RENDERER);
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    // ------------------------------------------------------------------
    // Helpers

    private static void set(ShaderProgram p, String name, Matrix4f m) {
        GlUniform u = p.getUniform(name);
        if (u != null) {
            u.set(m);
        }
    }

    private static void set(ShaderProgram p, String name, float x, float y) {
        GlUniform u = p.getUniform(name);
        if (u != null) {
            u.set(x, y);
        }
    }

    private static void set(ShaderProgram p, String name, float x, float y, float z) {
        GlUniform u = p.getUniform(name);
        if (u != null) {
            u.set(x, y, z);
        }
    }

    private static void set(ShaderProgram p, String name, float x, float y, float z, float w) {
        GlUniform u = p.getUniform(name);
        if (u != null) {
            u.set(x, y, z, w);
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = MathHelper.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float approach(float current, float target, float maxStep) {
        if (current < target) {
            return Math.min(current + maxStep, target);
        }
        return Math.max(current - maxStep, target);
    }

    /** GL state the passes touch, restored so vanilla's state cache stays truthful. */
    private record SavedState(boolean depthTest, boolean depthMask, boolean blend) {
        static SavedState capture() {
            return new SavedState(
                    GL11.glIsEnabled(GL11.GL_DEPTH_TEST),
                    GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK),
                    GL11.glIsEnabled(GL11.GL_BLEND));
        }

        void restore(Framebuffer main) {
            if (depthTest) {
                RenderSystem.enableDepthTest();
            } else {
                RenderSystem.disableDepthTest();
            }
            RenderSystem.depthMask(depthMask);
            RenderSystem.defaultBlendFunc();
            if (blend) {
                RenderSystem.enableBlend();
            } else {
                RenderSystem.disableBlend();
            }
            main.beginWrite(true);
        }
    }
}
