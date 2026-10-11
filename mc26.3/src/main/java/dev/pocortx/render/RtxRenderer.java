package dev.pocortx.render;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import dev.pocortx.compat.IrisCompat;
import dev.pocortx.config.RtxConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Screen-space ray tracing on top of the renderpearl pipeline (Minecraft 26.x).
 *
 * <p>Frame flow:
 * <ol>
 * <li>Right before translucent geometry (water, glass, clouds): copy the opaque depth.
 * In classic transparency the main render pass is briefly closed for this and then
 * resumed, because no other commands may run while a render pass is open.</li>
 * <li>End of the main pass: trace lighting (AO, sun shadows, light shafts) and
 * reflections at reduced resolution and blend the result into the main target.</li>
 * </ol>
 * Everything goes through vanilla's {@link RenderPipeline}s, so it runs on both the
 * OpenGL and the Vulkan backend.
 */
public final class RtxRenderer {
    public static final RtxRenderer INSTANCE = new RtxRenderer();
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    private static final float SUN_PATH_TILT = (float) Math.toRadians(17.0);
    private static final String FRAME_UBO = "RtxFrame";
    private static final int FRAME_UBO_SIZE = 4 * 64 + 11 * 16;

    private static final String PRECISION_HINT_SAMPLER = "PrecisionHintSampler";

    // The OpenGL backend gets its own variants with a never-sampled shadow sampler that keeps
    // LTW from turning the depth samplers lowp (see POCORTX_PRECISION_HINT in rtx_common.glsl).
    private static final Passes DEFAULT_PASSES = Passes.create(false);
    private static final Passes GL_PASSES = Passes.create(true);

    private record Passes(RenderPipeline light, RenderPipeline blur, RenderPipeline reflect, RenderPipeline composite) {
        static Passes create(boolean precisionHint) {
            // Every pipeline lists both depth samplers: the shared include declares them, and the
            // pipeline builder rejects shader uniforms missing from the layout.
            return new Passes(
                    post("rtx_light", null, precisionHint, "OpaqueDepthSampler", "FinalDepthSampler"),
                    post("rtx_blur", null, precisionHint, "OpaqueDepthSampler", "FinalDepthSampler", "LightSampler"),
                    post("rtx_reflect", null, precisionHint, "OpaqueDepthSampler", "FinalDepthSampler", "SceneSampler"),
                    // result = src.rgb + dst.rgb * src.a, destination alpha untouched
                    post("rtx_composite", new BlendFunction(BlendFactor.ONE, BlendFactor.SRC_ALPHA, BlendFactor.ZERO, BlendFactor.ONE),
                            precisionHint, "OpaqueDepthSampler", "FinalDepthSampler", "LightSampler", "ReflSampler"));
        }

        RenderPipeline[] all() {
            return new RenderPipeline[] {light, blur, reflect, composite};
        }
    }

    private TextureTarget opaqueDepth;
    private TextureTarget lightTarget;
    private TextureTarget blurTarget;
    private TextureTarget reflectTarget;
    private MappableRingBuffer frameUbo;

    private RenderPass resumedPass;
    private boolean opaqueCaptured;
    private boolean pipelinesChecked;
    private boolean failed;
    private String failureReason;
    private boolean failureReported;
    private int framesWithoutCapture;

    private final Matrix4f levelProjection = new Matrix4f();
    private boolean haveLevelProjection;
    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f invProj = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private final Matrix4f invView = new Matrix4f();

    private float wetness;
    private float skyExposure = 1.0f;
    private long lastFrameNanos;

    private RtxRenderer() {
    }

    private static RenderPipeline post(String name, BlendFunction blend, boolean precisionHint, String... samplers) {
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (String sampler : samplers) {
            layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        if (precisionHint) {
            layout.withUniform(PRECISION_HINT_SAMPLER, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        layout.withUniform(FRAME_UBO, UniformType.UNIFORM_BUFFER);
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("pocortx", "pipeline/" + name + (precisionHint ? "_gl" : "")))
                .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("pocortx", "post/" + name))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(blend == null ? ColorTargetState.DEFAULT : new ColorTargetState(blend));
        if (precisionHint) {
            builder.withShaderDefine("POCORTX_PRECISION_HINT");
        }
        return builder.build();
    }

    private static boolean openGlBackend() {
        return "OpenGL".equals(RenderSystem.getDevice().getDeviceInfo().backendName());
    }

    private static Passes passes() {
        return openGlBackend() ? GL_PASSES : DEFAULT_PASSES;
    }

    public boolean hasFailed() {
        return failed;
    }

    public String failureReason() {
        return failureReason;
    }

    public String pollFailureNotice() {
        if (failed && !failureReported) {
            failureReported = true;
            return failureReason;
        }
        return null;
    }

    /** Gives a failed renderer another try, e.g. after the player switched it back on. */
    public void resetFailure() {
        failed = false;
        failureReason = null;
        failureReported = false;
        pipelinesChecked = false;
    }

    /** Captured from GameRenderer: the level projection including view bobbing and nausea. */
    public void setLevelProjection(Matrix4f projection) {
        levelProjection.set(projection);
        haveLevelProjection = true;
    }

    private boolean active() {
        return RtxConfig.get().enabled && !failed && Minecraft.getInstance().level != null && !IrisCompat.isShaderPackInUse();
    }

    // ------------------------------------------------------------------
    // Opaque depth capture

    /**
     * Classic transparency draws translucent terrain inside the main render pass. Close
     * that pass, copy the opaque depth, and hand back a resumed pass on the same targets.
     */
    public RenderPass beforeClassicTranslucency(RenderPass mainPass) {
        opaqueCaptured = false;
        if (!active() || !checkPipelines()) {
            return mainPass;
        }
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        mainPass.close();
        try {
            captureOpaqueDepth(main);
        } catch (Throwable t) {
            fail("opaque depth capture", t);
        }
        resumedPass = encoder().createRenderPass(() -> "PocoRTX resumed main",
                main.getColorTextureView(), Optional.empty(), main.getDepthTextureView(), OptionalDouble.empty());
        RenderSystem.bindDefaultUniforms(resumedPass);
        return resumedPass;
    }

    public void afterClassicTranslucency() {
        if (resumedPass != null) {
            resumedPass.close();
            resumedPass = null;
        }
    }

    /** Improved transparency (OIT) runs with no render pass open, and composites into main afterwards. */
    public void beforeOit() {
        opaqueCaptured = false;
        if (!active() || !checkPipelines()) {
            return;
        }
        try {
            captureOpaqueDepth(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        } catch (Throwable t) {
            fail("opaque depth capture", t);
        }
    }

    private void captureOpaqueDepth(RenderTarget main) {
        ensureTargets(main);
        RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "PocoRTX opaque depth")
                .withDepthAttachment(opaqueDepth.getDepthTextureView())
                .build();
        try (RenderPass pass = encoder().createRenderPass(descriptor)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.BLIT_DEPTH));
            pass.setUniform("InSampler", main.getDepthTextureView(), nearest());
            pass.draw(3, 1, 0, 0);
        }
        opaqueCaptured = true;
    }

    // ------------------------------------------------------------------
    // Effects

    /** Fired at the end of the main pass, before the hand and GUI. */
    public void renderEffects(LevelRenderContext context) {
        if (!active()) {
            opaqueCaptured = false;
            return;
        }
        if (!opaqueCaptured) {
            if (++framesWithoutCapture == 200) {
                LOGGER.warn("PocoRTX: opaque depth was never captured; another mod may replace the translucent pass");
            }
            return;
        }
        framesWithoutCapture = 0;
        opaqueCaptured = false;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            ensureTargets(main);
            writeFrameUniforms(context.levelState(), main);
            RtxConfig config = RtxConfig.get();
            GpuSampler nearest = nearest();
            Passes passes = passes();

            draw("PocoRTX lighting", passes.light(), lightTarget, pass -> {
                pass.setUniform("OpaqueDepthSampler", opaqueDepth.getDepthTextureView(), nearest);
                pass.setUniform("FinalDepthSampler", main.getDepthTextureView(), nearest);
            });
            draw("PocoRTX denoise", passes.blur(), blurTarget, pass -> {
                pass.setUniform("OpaqueDepthSampler", opaqueDepth.getDepthTextureView(), nearest);
                pass.setUniform("FinalDepthSampler", main.getDepthTextureView(), nearest);
                pass.setUniform("LightSampler", lightTarget.getColorTextureView(), nearest);
            });
            if (config.reflections && !submerged(context.levelState().cameraRenderState)) {
                draw("PocoRTX reflections", passes.reflect(), reflectTarget, pass -> {
                    pass.setUniform("OpaqueDepthSampler", opaqueDepth.getDepthTextureView(), nearest);
                    pass.setUniform("FinalDepthSampler", main.getDepthTextureView(), nearest);
                    pass.setUniform("SceneSampler", main.getColorTextureView(), nearest);
                });
            } else {
                encoder().clearColorTexture(reflectTarget.getColorTexture(), new Vector4f(0.0f));
            }
            drawInto("PocoRTX composite", passes.composite(), main, pass -> {
                pass.setUniform("OpaqueDepthSampler", opaqueDepth.getDepthTextureView(), nearest);
                pass.setUniform("FinalDepthSampler", main.getDepthTextureView(), nearest);
                pass.setUniform("LightSampler", blurTarget.getColorTextureView(), nearest);
                pass.setUniform("ReflSampler", reflectTarget.getColorTextureView(), nearest);
            });
            frameUbo.rotate();
        } catch (Throwable t) {
            fail("effects pass", t);
        }
    }

    private void draw(String label, RenderPipeline pipeline, RenderTarget target, Consumer<RenderPass> bind) {
        drawInto(label, pipeline, target, bind);
    }

    private void drawInto(String label, RenderPipeline pipeline, RenderTarget target, Consumer<RenderPass> bind) {
        CompiledRenderPipeline compiled = RenderSystem.getCompiledPipeline(pipeline);
        try (RenderPass pass = encoder().createRenderPass(() -> label, target.getColorTextureView(), Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(compiled);
            pass.setUniform(FRAME_UBO, frameUbo.currentBuffer());
            if (openGlBackend()) {
                pass.setUniform(PRECISION_HINT_SAMPLER, opaqueDepth.getDepthTextureView(), nearest());
            }
            bind.accept(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static boolean submerged(CameraRenderState camera) {
        return camera.fogType == FogType.WATER || camera.fogType == FogType.LAVA || camera.fogType == FogType.POWDER_SNOW;
    }

    // ------------------------------------------------------------------
    // Per-frame uniforms

    private void writeFrameUniforms(LevelRenderState state, RenderTarget main) {
        RtxConfig config = RtxConfig.get();
        RtxConfig.Quality q = config.quality;
        CameraRenderState camera = state.cameraRenderState;
        SkyRenderState sky = state.skyRenderState;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float partial = state.worldPartialTicks;

        proj.set(haveLevelProjection ? levelProjection : camera.projectionMatrix);
        proj.invert(invProj);
        view.set(camera.viewRotationMatrix);
        view.invert(invView);

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.0f : Math.min((now - lastFrameNanos) / 1.0e9f, 0.25f);
        lastFrameNanos = now;

        BlockPos eye = camera.blockPos;
        float exposureTarget = level.getBrightness(LightLayer.SKY, eye) / 15.0f;
        skyExposure = approach(skyExposure, exposureTarget, dt * 1.5f);

        boolean overworldSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
        float rain = level.getRainLevel(partial);
        float thunder = level.getThunderLevel(partial);

        // Same rotations the sky renderer applies to the sun and moon quads, with the
        // path tilted slightly south so north/south faces are not edge-on to the sun.
        Vector3f sun = new Vector3f(0.0f, 1.0f, 0.0f).rotateX(sky.sunAngle).rotateY(-Mth.HALF_PI).rotateX(SUN_PATH_TILT);
        Vector3f moon = new Vector3f(0.0f, 1.0f, 0.0f).rotateX(sky.moonAngle).rotateY(-Mth.HALF_PI).rotateX(SUN_PATH_TILT);
        Vector3f lightWorld;
        float lightStrength;
        Vector3f lightColor = new Vector3f();
        if (sun.y >= 0.0f || moon.y < 0.0f) {
            lightWorld = sun;
            lightStrength = smoothstep(-0.02f, 0.12f, sun.y);
            float warm = smoothstep(0.0f, 0.35f, sun.y);
            lightColor.set(1.0f, Mth.lerp(warm, 0.55f, 0.95f), Mth.lerp(warm, 0.28f, 0.85f));
        } else {
            lightWorld = moon;
            lightStrength = 0.35f * smoothstep(-0.02f, 0.12f, moon.y);
            lightColor.set(0.55f, 0.65f, 0.95f);
        }
        lightStrength *= (1.0f - 0.8f * rain) * (1.0f - 0.5f * thunder);
        if (!overworldSky) {
            lightStrength = 0.0f;
        }
        Vector3f lightView = new Vector3f(lightWorld).mulDirection(view).normalize();

        boolean submerged = submerged(camera);
        float wetTarget = 0.0f;
        if (config.rainPuddles && overworldSky && rain > 0.0f
                && level.getBiome(eye).value().getPrecipitationAt(eye, level.getSeaLevel()) == Biome.Precipitation.RAIN) {
            wetTarget = rain;
        }
        wetness = approach(wetness, wetTarget, dt * (wetTarget > wetness ? 0.25f : 0.04f));

        Vec3 pos = camera.pos;
        float camX = (float) (pos.x - Math.floor(pos.x / 2048.0) * 2048.0);
        float camZ = (float) (pos.z - Math.floor(pos.z / 2048.0) * 2048.0);
        float time = ((state.gameTime % 72000L) + partial) / 20.0f;
        float fade = Math.max(16.0f, Math.min(camera.fogData.environmentalEnd, camera.fogData.renderDistanceEnd));
        float cloudHeight = Float.isNaN(state.cloudHeight) ? -10000.0f : state.cloudHeight;
        Vector3fc skyColor = sky.skyColor != null ? sky.skyColor : new Vector3f(0.5f, 0.7f, 1.0f);

        boolean sunEffects = !submerged;
        float shaftStrength = lightStrength * config.lightShaftStrength * 0.6f;
        if (frameUbo == null) {
            frameUbo = new MappableRingBuffer(() -> "PocoRTX frame", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FRAME_UBO_SIZE);
        }
        try (GpuBufferSlice.MappedView mapped = frameUbo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(mapped.data())
                    .putMat4f(proj)
                    .putMat4f(invProj)
                    .putMat4f(view)
                    .putMat4f(invView)
                    .putVec4(main.width, main.height, lightTarget.width, lightTarget.height)
                    .putVec4(lightView.x, lightView.y, lightView.z, lightStrength)
                    .putVec4(lightColor.x, lightColor.y, lightColor.z, lightStrength * skyExposure)
                    .putVec4(skyColor.x(), skyColor.y(), skyColor.z(), skyExposure)
                    .putVec4(camera.fogData.color.x, camera.fogData.color.y, camera.fogData.color.z, fade)
                    .putVec4(camX, (float) pos.y, camZ, time)
                    .putVec4(q.aoSamples, q.shadowSteps, q.lightShaftSteps, q.reflectionSteps)
                    .putVec4(config.ambientOcclusion ? 1.0f : 0.0f,
                            config.shadows && sunEffects ? 1.0f : 0.0f,
                            config.lightShafts && sunEffects ? 1.0f : 0.0f,
                            config.reflections ? 1.0f : 0.0f)
                    .putVec4(config.ambientOcclusion ? config.aoStrength : 0.0f,
                            config.shadows ? config.shadowStrength * lightStrength * skyExposure : 0.0f,
                            config.reflections ? config.reflectionStrength : 0.0f,
                            config.lightShafts ? shaftStrength : 0.0f)
                    .putVec4(1.1f, 14.0f, 64.0f, wetness)
                    .putVec4(cloudHeight, config.waterWaves ? 1.0f : 0.0f, config.debugView.ordinal(), config.rainPuddles ? 1.0f : 0.0f);
        }
    }

    // ------------------------------------------------------------------
    // Resources

    private boolean checkPipelines() {
        if (pipelinesChecked) {
            return true;
        }
        List<RenderPipeline> pipelines = new ArrayList<>(List.of(passes().all()));
        pipelines.add(RenderPipelines.BLIT_DEPTH);
        for (RenderPipeline pipeline : pipelines) {
            if (RenderSystem.getCompiledPipelineNullable(pipeline) == null) {
                fail("shader compilation", new IllegalStateException("pipeline " + pipeline.getLocation() + " did not compile"));
                return false;
            }
        }
        pipelinesChecked = true;
        LOGGER.info("PocoRTX pipelines compiled ({})", deviceName());
        return true;
    }

    private void ensureTargets(RenderTarget main) {
        RtxConfig.Quality q = RtxConfig.get().quality;
        int w = main.width;
        int h = main.height;
        int tw = Math.max(1, Math.round(w * q.renderScale));
        int th = Math.max(1, Math.round(h * q.renderScale));
        opaqueDepth = ensure(opaqueDepth, "PocoRTX opaque depth", w, h, null, GpuFormat.D32_FLOAT);
        lightTarget = ensure(lightTarget, "PocoRTX lighting", tw, th, GpuFormat.RGBA8_UNORM, null);
        blurTarget = ensure(blurTarget, "PocoRTX denoised lighting", tw, th, GpuFormat.RGBA8_UNORM, null);
        reflectTarget = ensure(reflectTarget, "PocoRTX reflections", tw, th, GpuFormat.RGBA8_UNORM, null);
    }

    private static TextureTarget ensure(TextureTarget target, String label, int w, int h, GpuFormat color, GpuFormat depth) {
        if (target == null) {
            return new TextureTarget(label, w, h, color, depth);
        }
        if (target.width != w || target.height != h) {
            target.resize(w, h);
        }
        return target;
    }

    private static CommandEncoder encoder() {
        return RenderSystem.getDevice().createCommandEncoder();
    }

    private static GpuSampler nearest() {
        return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
    }

    private void fail(String stage, Throwable t) {
        failed = true;
        failureReason = t.getClass().getSimpleName() + ": " + t.getMessage();
        LOGGER.error("PocoRTX disabled after failure in {} on '{}'", stage, deviceName(), t);
    }

    private static String deviceName() {
        try {
            return RenderSystem.getDevice().getDeviceInfo().toString();
        } catch (Throwable ignored) {
            return "unknown device";
        }
    }

    private static float smoothstep(float edge0, float edge1, float x) {
        float t = Mth.clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
        return t * t * (3.0f - 2.0f * t);
    }

    private static float approach(float current, float target, float maxStep) {
        if (current < target) {
            return Math.min(current + maxStep, target);
        }
        return Math.max(current - maxStep, target);
    }
}
