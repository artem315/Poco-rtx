package dev.pocortx.render;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import dev.pocortx.compat.IrisCompat;
import dev.pocortx.config.RtxConfig;
import dev.pocortx.rt.RtBuffer;
import dev.pocortx.rt.RtCompute;
import dev.pocortx.rt.RtContext;
import dev.pocortx.rt.RtDeviceSupport;
import dev.pocortx.rt.RtMaterials;
import dev.pocortx.rt.RtScene;
import dev.pocortx.rt.RtTextures;
import java.nio.LongBuffer;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.LightmapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.data.AtlasIds;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.CardinalLighting;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Hardware ray tracing for Minecraft 26.2 on the Vulkan backend, plus the BSL-style post chain.
 *
 * <p>Frame flow:
 * <ol>
 * <li>Before translucent terrain: copy the opaque depth.</li>
 * <li>End of the main pass: update the acceleration structures, run the ray tracing compute
 * pass (ray queries) at trace resolution with temporal accumulation, denoise, and blend the
 * result into the main target with Minecraft pipelines.</li>
 * <li>Then the shader effects (colour grading, bloom, vignette, sharpening) on any backend.</li>
 * </ol>
 */
public final class RtxRenderer {
    public static final RtxRenderer INSTANCE = new RtxRenderer();
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    private static final float SUN_PATH_TILT = (float) Math.toRadians(17.0);
    private static final String RT_UBO = "RtxFrame";
    private static final String FX_UBO = "FxFrame";
    private static final int TRACE_UBO_SIZE = 5 * 64 + 19 * 16;
    private static final int RT_UBO_SIZE = 2 * 64 + 10 * 16;
    private static final int FX_UBO_SIZE = 4 * 16;
    private static final int RING = 3;

    private static final RenderPipeline BLUR1 = post("rt_blur", null, GpuFormat.RGBA16_FLOAT, 1, RT_UBO,
            "OpaqueDepthSampler", "SourceSampler", "NormalSampler", "InfoSampler");
    private static final RenderPipeline BLUR2 = post("rt_blur", null, GpuFormat.RGBA16_FLOAT, 2, RT_UBO,
            "OpaqueDepthSampler", "SourceSampler", "NormalSampler", "InfoSampler");
    // Reads a copy of the frame and writes the relit frame (alpha kept).
    private static final RenderPipeline COMPOSITE = post("rt_composite",
            new BlendFunction(BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ZERO, BlendFactor.ONE), GpuFormat.RGBA8_UNORM, 0, RT_UBO,
            "SceneSampler", "OpaqueDepthSampler", "FinalDepthSampler", "LightSampler", "IndirectSampler", "ReflSampler", "NormalSampler", "InfoSampler");
    private static final RenderPipeline FX_BLOOM_PREFILTER = post("fx_bloom_down", null, GpuFormat.RGBA8_UNORM, 1, FX_UBO, "SourceSampler");
    private static final RenderPipeline FX_BLOOM_DOWN = post("fx_bloom_down", null, GpuFormat.RGBA8_UNORM, 0, FX_UBO, "SourceSampler");
    private static final RenderPipeline FX_BLOOM_UP = post("fx_bloom_up",
            new BlendFunction(BlendFactor.ONE, BlendFactor.ONE, BlendFactor.ZERO, BlendFactor.ONE), GpuFormat.RGBA8_UNORM, 0, FX_UBO, "SourceSampler");
    private static final RenderPipeline FX_FINAL = post("fx_final", null, GpuFormat.RGBA8_UNORM, 0, FX_UBO, "SceneSampler", "BloomSampler");

    // ray tracing (Vulkan only)
    private RtContext ctx;
    private RtScene scene;
    private RtCompute lightKernel;
    private RtCompute indirectKernel;
    private RtCompute reflectKernel;
    private final RtTextures.Image gNormal = new RtTextures.Image("PocoRTX G-buffer normal", GpuFormat.RGBA16_FLOAT);
    private final RtTextures.Image gAlbedo = new RtTextures.Image("PocoRTX G-buffer albedo", GpuFormat.RGBA8_UNORM);
    private final RtTextures.Image gInfo = new RtTextures.Image("PocoRTX G-buffer info", GpuFormat.RGBA8_UNORM);
    private final RtMaterials materials = new RtMaterials();
    private long nearestSampler;
    private long linearSampler;
    private long atlasSampler;
    private final RtBuffer[] traceUbo = new RtBuffer[RING];
    private final RtTextures.Image[][] accum = {
            {new RtTextures.Image("PocoRTX light A", GpuFormat.RGBA16_FLOAT), new RtTextures.Image("PocoRTX indirect A", GpuFormat.RGBA16_FLOAT),
                    new RtTextures.Image("PocoRTX reflections A", GpuFormat.RGBA16_FLOAT)},
            {new RtTextures.Image("PocoRTX light B", GpuFormat.RGBA16_FLOAT), new RtTextures.Image("PocoRTX indirect B", GpuFormat.RGBA16_FLOAT),
                    new RtTextures.Image("PocoRTX reflections B", GpuFormat.RGBA16_FLOAT)}};
    private int accumIndex;
    private boolean historyValid;
    private int frameIndex;

    // Minecraft pipelines
    private TextureTarget opaqueDepth;
    private TextureTarget blurLight;
    private TextureTarget blurLight2;
    private TextureTarget blurIndirect;
    private TextureTarget blurIndirect2;
    private TextureTarget fxScene;
    private final TextureTarget[] bloom = new TextureTarget[4];
    private MappableRingBuffer rtUbo;
    private MappableRingBuffer fxUbo;

    private boolean opaqueCaptured;
    private RenderPass resumedPass;
    private boolean failed;
    private String failureReason;
    private boolean failureReported;
    private int framesWithoutCapture;

    private final Matrix4f levelProjection = new Matrix4f();
    private boolean haveLevelProjection;
    private final Matrix4f proj = new Matrix4f();
    private final Matrix4f invProj = new Matrix4f();
    private final Matrix4f viewRot = new Matrix4f();
    private final Matrix4f invViewRot = new Matrix4f();
    private final Matrix4f prevProj = new Matrix4f();
    private final Matrix4f prevViewRot = new Matrix4f();
    private Vec3 prevCamera;

    private float wetness;
    private long lastFrameNanos;

    private RtxRenderer() {
    }

    private static RenderPipeline post(String name, BlendFunction blend, GpuFormat format, int variant, String ubo, String... samplers) {
        BindGroupLayout.Builder layout = BindGroupLayout.builder();
        for (String sampler : samplers) {
            layout.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
        }
        layout.withUniform(ubo, UniformType.UNIFORM_BUFFER);
        RenderPipeline.Builder builder = RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
                .withLocation(Identifier.fromNamespaceAndPath("pocortx", "pipeline/" + name + "_" + variant))
                .withVertexShader(Identifier.withDefaultNamespace("core/screenquad"))
                .withFragmentShader(Identifier.fromNamespaceAndPath("pocortx", "post/" + name))
                .withBindGroupLayout(layout.build())
                .withColorTargetState(new ColorTargetState(Optional.ofNullable(blend), format, ColorTargetState.WRITE_ALL));
        if (variant != 0) {
            builder.withShaderDefine("VARIANT", variant);
        }
        return builder.build();
    }

    // ------------------------------------------------------------------ state

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

    public void resetFailure() {
        failed = false;
        failureReason = null;
        failureReported = false;
    }

    /** True when hardware ray tracing is running on this device right now. */
    public boolean hardwareRtActive() {
        return ctx != null && !failed && RtxConfig.get().enabled;
    }

    public int traceInstances() {
        return scene == null ? 0 : scene.instanceCount();
    }

    public int traceSections() {
        return scene == null ? 0 : scene.sectionCount();
    }

    public void setLevelProjection(Matrix4f projection) {
        levelProjection.set(projection);
        haveLevelProjection = true;
    }

    private boolean rtWanted() {
        return RtxConfig.get().enabled && !failed && Minecraft.getInstance().level != null && !IrisCompat.isShaderPackInUse()
                && RtDeviceSupport.enabledOnDevice();
    }

    private static boolean fxWanted() {
        RtxConfig config = RtxConfig.get();
        // Debug views show the raw ray traced data, without colour grading.
        boolean debugShown = config.enabled && config.debugView != RtxConfig.DebugView.OFF && INSTANCE.hardwareRtActive();
        return config.shader.enabled && !debugShown && Minecraft.getInstance().level != null && !IrisCompat.isShaderPackInUse();
    }

    // ------------------------------------------------------------------ hooks

    /**
     * Classic transparency draws translucent terrain inside the main render pass. Close
     * that pass, copy the opaque depth, and hand back a resumed pass on the same targets.
     */
    public RenderPass beforeClassicTranslucency(RenderPass mainPass) {
        opaqueCaptured = false;
        if (!rtWanted()) {
            if (!RtxConfig.get().enabled) {
                RtScene.stopCapturing();
            }
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
        if (!rtWanted()) {
            return;
        }
        try {
            captureOpaqueDepth(Minecraft.getInstance().gameRenderer.mainRenderTarget());
        } catch (Throwable t) {
            fail("opaque depth capture", t);
        }
    }

    private void captureOpaqueDepth(RenderTarget main) {
        opaqueDepth = ensure(opaqueDepth, "PocoRTX opaque depth", main.width, main.height, true, GpuFormat.D32_FLOAT);
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

    /** Fired at the end of the main pass (after improved transparency, if on), before the hand and GUI. */
    public void renderEffects(LevelRenderContext context) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        renderFrame(context.levelState(), main);
    }

    private void renderFrame(LevelRenderState levelState, RenderTarget finalDepthTarget) {
        if (rtWanted()) {
            if (opaqueCaptured) {
                framesWithoutCapture = 0;
                renderRayTracing(levelState, finalDepthTarget);
            } else if (++framesWithoutCapture == 200) {
                LOGGER.warn("PocoRTX: opaque depth was never captured; another mod may replace the translucent pass");
            }
        } else {
            historyValid = false;
        }
        opaqueCaptured = false;
        if (fxWanted()) {
            try {
                renderShaderEffects();
            } catch (Throwable t) {
                RtxConfig.get().shader.enabled = false;
                fail("shader effects", t);
            }
        }
    }

    // ------------------------------------------------------------------ ray tracing

    private boolean ensureRt() {
        RtContext current = RtContext.get();
        if (current == null) {
            return false;
        }
        if (current != ctx) {
            releaseRt();
            ctx = current;
            scene = new RtScene(ctx);
            lightKernel = new RtCompute(ctx, "/assets/pocortx/shaders/rt/rt_light.spv", RtCompute.kernelLayout(true));
            indirectKernel = new RtCompute(ctx, "/assets/pocortx/shaders/rt/rt_indirect.spv", RtCompute.kernelLayout(false));
            reflectKernel = new RtCompute(ctx, "/assets/pocortx/shaders/rt/rt_reflect.spv", RtCompute.kernelLayout(false));
            nearestSampler = sampler(VK12.VK_FILTER_NEAREST, VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST, 0.0f);
            linearSampler = sampler(VK12.VK_FILTER_LINEAR, VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST, 0.0f);
            atlasSampler = sampler(VK12.VK_FILTER_NEAREST, VK12.VK_SAMPLER_MIPMAP_MODE_NEAREST, 16.0f);
            for (int i = 0; i < RING; i++) {
                traceUbo[i] = RtBuffer.create(ctx, TRACE_UBO_SIZE, VK12.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, true, 0);
            }
            historyValid = false;
            LOGGER.info("PocoRTX: hardware ray tracing pipeline ready on '{}'", RtDeviceSupport.deviceName());
        }
        return true;
    }

    private long sampler(int filter, int mipmapMode, float maxLod) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            RtContext.check(VK12.vkCreateSampler(ctx.vk, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(filter).minFilter(filter).mipmapMode(mipmapMode)
                    .addressModeU(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK12.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .minLod(0.0f).maxLod(maxLod), null, handle), "vkCreateSampler");
            return handle.get(0);
        }
    }

    private void releaseRt() {
        if (ctx == null) {
            return;
        }
        if (scene != null) {
            scene.close();
            scene = null;
        }
        for (RtCompute kernel : new RtCompute[] {lightKernel, indirectKernel, reflectKernel}) {
            if (kernel != null) {
                kernel.destroyLater();
            }
        }
        lightKernel = null;
        indirectKernel = null;
        reflectKernel = null;
        var vk = ctx.vk;
        long a = nearestSampler, b = linearSampler, c = atlasSampler;
        ctx.destroyLater(() -> {
            VK12.vkDestroySampler(vk, a, null);
            VK12.vkDestroySampler(vk, b, null);
            VK12.vkDestroySampler(vk, c, null);
        });
        for (int i = 0; i < RING; i++) {
            if (traceUbo[i] != null) {
                traceUbo[i].freeLater();
                traceUbo[i] = null;
            }
        }
        ctx = null;
    }

    private void renderRayTracing(LevelRenderState levelState, RenderTarget finalTarget) {
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        try {
            if (!ensureRt()) {
                return;
            }
            RtxConfig config = RtxConfig.get();
            materials.ensure();
            int tw = Math.max(1, Math.round(main.width * config.quality.renderScale));
            int th = Math.max(1, Math.round(main.height * config.quality.renderScale));
            for (RtTextures.Image[] set : accum) {
                for (RtTextures.Image image : set) {
                    if (image.texture() == null || image.texture().getWidth(0) != tw || image.texture().getHeight(0) != th) {
                        historyValid = false;
                    }
                    image.ensure(tw, th);
                }
            }
            gNormal.ensure(tw, th);
            gAlbedo.ensure(tw, th);
            gInfo.ensure(tw, th);
            blurLight = ensure(blurLight, "PocoRTX denoised light", tw, th, false, GpuFormat.RGBA16_FLOAT);
            blurLight2 = ensure(blurLight2, "PocoRTX denoised light 2", tw, th, false, GpuFormat.RGBA16_FLOAT);
            blurIndirect = ensure(blurIndirect, "PocoRTX denoised indirect", tw, th, false, GpuFormat.RGBA16_FLOAT);
            blurIndirect2 = ensure(blurIndirect2, "PocoRTX denoised indirect 2", tw, th, false, GpuFormat.RGBA16_FLOAT);

            CameraRenderState camera = levelState.cameraRenderState;
            int slot = frameIndex % RING;
            writeTraceUniforms(levelState, main, tw, th, traceUbo[slot]);

            VkCommandBuffer cb = ctx.commandBuffer();
            scene.update(cb, camera.pos, config.rtDistance);

            RtTextures.Image[] history = accum[accumIndex];
            RtTextures.Image[] out = accum[1 - accumIndex];
            TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
            int gx = (tw + 7) / 8;
            int gy = (th + 7) / 8;
            long opaque = vkView(opaqueDepth.getDepthTextureView());
            long finalDepth = vkView(finalTarget.getDepthTextureView());
            long atlasView = vkView(atlas.getTextureView());
            long materialView = vkView(materials.view());
            long histLight = vkView(history[0].view());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                bindCommon(lightKernel.bindings(stack), slot, opaque, finalDepth, atlasView, materialView, histLight)
                        .sampled(7, histLight, linearSampler)
                        .storage(8, vkView(out[0].view()))
                        .storage(9, vkView(gNormal.view()))
                        .storage(10, vkView(gAlbedo.view()))
                        .storage(11, vkView(gInfo.view()))
                        .sampled(12, histLight, nearestSampler)
                        .dispatch(cb, gx, gy);
            }
            // The other kernels read the G-buffer and the light output written above.
            ctx.barrier(cb);
            long currentLight = vkView(out[0].view());
            try (MemoryStack stack = MemoryStack.stackPush()) {
                bindCommon(indirectKernel.bindings(stack), slot, opaque, finalDepth, atlasView, materialView, histLight)
                        .sampled(7, vkView(history[1].view()), linearSampler)
                        .storage(8, vkView(out[1].view()))
                        .sampled(9, vkView(gNormal.view()), nearestSampler)
                        .sampled(10, vkView(gAlbedo.view()), nearestSampler)
                        .sampled(11, vkView(gInfo.view()), nearestSampler)
                        .sampled(12, currentLight, nearestSampler)
                        .dispatch(cb, gx, gy);
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                bindCommon(reflectKernel.bindings(stack), slot, opaque, finalDepth, atlasView, materialView, histLight)
                        .sampled(7, vkView(history[2].view()), linearSampler)
                        .storage(8, vkView(out[2].view()))
                        .sampled(9, vkView(gNormal.view()), nearestSampler)
                        .sampled(10, vkView(gAlbedo.view()), nearestSampler)
                        .sampled(11, vkView(gInfo.view()), nearestSampler)
                        .sampled(12, currentLight, nearestSampler)
                        .dispatch(cb, gx, gy);
            }
            ctx.barrier(cb);
            accumIndex = 1 - accumIndex;
            historyValid = true;
            frameIndex++;

            writeRtUniforms(levelState, main, tw, th);
            GpuSampler nearest = nearest();
            GpuTextureView lightView = out[0].view();
            GpuTextureView indirectView = out[1].view();
            if (config.temporalDenoise || config.quality.renderScale < 1.0f) {
                blur("PocoRTX denoise light", BLUR1, blurLight, out[0].view());
                blur("PocoRTX denoise light 2", BLUR2, blurLight2, blurLight.getColorTextureView());
                blur("PocoRTX denoise indirect", BLUR1, blurIndirect, out[1].view());
                blur("PocoRTX denoise indirect 2", BLUR2, blurIndirect2, blurIndirect.getColorTextureView());
                lightView = blurLight2.getColorTextureView();
                indirectView = blurIndirect2.getColorTextureView();
            }
            GpuTextureView finalLight = lightView;
            GpuTextureView finalIndirect = indirectView;
            GpuTextureView reflView = out[2].view();
            // The frame is read from a copy (shared with the shader effects, which copy it again later).
            fxScene = ensure(fxScene, "PocoRTX shader scene", main.width, main.height, false, GpuFormat.RGBA8_UNORM);
            encoder().copyTextureToTexture(main.getColorTexture(), fxScene.getColorTexture(), 0, 0, 0, 0, 0, main.width, main.height);
            drawRt("PocoRTX composite", COMPOSITE, main, pass -> {
                pass.setUniform("SceneSampler", fxScene.getColorTextureView(), nearest);
                pass.setUniform("OpaqueDepthSampler", opaqueDepth.getDepthTextureView(), nearest);
                pass.setUniform("FinalDepthSampler", finalTarget.getDepthTextureView(), nearest);
                pass.setUniform("LightSampler", finalLight, nearest);
                pass.setUniform("IndirectSampler", finalIndirect, nearest);
                pass.setUniform("ReflSampler", reflView, nearest);
                pass.setUniform("NormalSampler", gNormal.view(), nearest);
                pass.setUniform("InfoSampler", gInfo.view(), nearest);
            });
            rtUbo.rotate();
        } catch (Throwable t) {
            fail("ray tracing", t);
        }
    }

    private RtCompute.Bindings bindCommon(RtCompute.Bindings b, int slot, long opaque, long finalDepth, long atlasView,
            long materialView, long histLight) {
        return b.accelerationStructure(0, scene.tlas())
                .buffer(1, traceUbo[slot], 0, TRACE_UBO_SIZE)
                .buffer(2, scene.table(), 0, VK12.VK_WHOLE_SIZE)
                .sampled(3, opaque, nearestSampler)
                .sampled(4, finalDepth, nearestSampler)
                .sampled(5, atlasView, atlasSampler)
                .sampled(6, materialView, nearestSampler)
                .sampled(13, histLight, linearSampler);
    }

    private void blur(String label, RenderPipeline pipeline, RenderTarget target, GpuTextureView source) {
        GpuSampler nearest = nearest();
        drawRt(label, pipeline, target, pass -> {
            pass.setUniform("OpaqueDepthSampler", opaqueDepth.getDepthTextureView(), nearest);
            pass.setUniform("SourceSampler", source, nearest);
            pass.setUniform("NormalSampler", gNormal.view(), nearest);
            pass.setUniform("InfoSampler", gInfo.view(), nearest);
        });
    }

    private static long vkView(GpuTextureView view) {
        return ((VulkanGpuTextureView) view).vkImageView();
    }

    private void drawRt(String label, RenderPipeline pipeline, RenderTarget target, Consumer<RenderPass> bind) {
        try (RenderPass pass = encoder().createRenderPass(() -> label, target.getColorTextureView(), Optional.empty())) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform(RT_UBO, rtUbo.currentBuffer());
            bind.accept(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    private static boolean submerged(CameraRenderState camera) {
        return camera.fogType == FogType.WATER || camera.fogType == FogType.LAVA || camera.fogType == FogType.POWDER_SNOW;
    }

    // ------------------------------------------------------------------ uniforms

    private final Vector3f lightDir = new Vector3f();
    private final Vector3f lightColor = new Vector3f();
    private float lightStrength;
    private float daylight;
    private float directShare;
    // Vanilla lightmap inputs (core/lightmap.fsh), so ray traced lighting can replace it exactly.
    private final float[] lmSky = new float[4];
    private final float[] lmBlock = new float[4];
    private final float[] lmAmbient = new float[4];
    private final float[] faceShade = new float[4];

    private void computeLight(LevelRenderState state) {
        SkyRenderState sky = state.skyRenderState;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        boolean overworldSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
        float rain = level.getRainLevel(partial);
        float thunder = level.getThunderLevel(partial);
        // Same rotations the sky renderer applies to the sun and moon quads, with the
        // path tilted slightly south so north/south faces are not edge-on to the sun.
        Vector3f sun = new Vector3f(0.0f, 1.0f, 0.0f).rotateX(sky.sunAngle).rotateY(-Mth.HALF_PI).rotateX(SUN_PATH_TILT);
        Vector3f moon = new Vector3f(0.0f, 1.0f, 0.0f).rotateX(sky.moonAngle).rotateY(-Mth.HALF_PI).rotateX(SUN_PATH_TILT);
        if (sun.y >= 0.0f || moon.y < 0.0f) {
            lightDir.set(sun);
            lightStrength = smoothstep(-0.02f, 0.12f, sun.y);
            directShare = lightStrength;
            float warm = smoothstep(0.0f, 0.35f, sun.y);
            lightColor.set(1.0f, Mth.lerp(warm, 0.55f, 0.95f), Mth.lerp(warm, 0.28f, 0.85f));
        } else {
            lightDir.set(moon);
            lightStrength = 0.35f * smoothstep(-0.02f, 0.12f, moon.y);
            directShare = 0.75f * smoothstep(-0.02f, 0.12f, moon.y);
            lightColor.set(0.55f, 0.65f, 0.95f);
        }
        float weather = (1.0f - 0.8f * rain) * (1.0f - 0.5f * thunder);
        lightStrength *= weather;
        directShare *= weather;
        daylight = Mth.lerp(smoothstep(-0.12f, 0.2f, sun.y), 0.16f, 1.0f) * (1.0f - 0.35f * rain);
        if (!overworldSky) {
            lightStrength = 0.0f;
            daylight = 0.0f;
            directShare = 0.0f;
        }

        LightmapRenderState lm = mc.gameRenderer.gameRenderState().lightmapRenderState;
        lmSky[0] = lm.skyLightColor.x() * lm.skyFactor;
        lmSky[1] = lm.skyLightColor.y() * lm.skyFactor;
        lmSky[2] = lm.skyLightColor.z() * lm.skyFactor;
        lmSky[3] = lm.blockFactor;
        lmBlock[0] = lm.blockLightTint.x();
        lmBlock[1] = lm.blockLightTint.y();
        lmBlock[2] = lm.blockLightTint.z();
        lmBlock[3] = lm.brightness;
        lmAmbient[0] = Math.max(lm.ambientColor.x(), lm.nightVisionColor.x() * lm.nightVisionEffectIntensity);
        lmAmbient[1] = Math.max(lm.ambientColor.y(), lm.nightVisionColor.y() * lm.nightVisionEffectIntensity);
        lmAmbient[2] = Math.max(lm.ambientColor.z(), lm.nightVisionColor.z() * lm.nightVisionEffectIntensity);
        lmAmbient[3] = lm.darknessEffectScale;
        CardinalLighting cardinal = level.cardinalLighting();
        faceShade[0] = cardinal.down();
        faceShade[1] = cardinal.up();
        faceShade[2] = (cardinal.north() + cardinal.south()) * 0.5f;
        faceShade[3] = (cardinal.west() + cardinal.east()) * 0.5f;
    }

    private void writeTraceUniforms(LevelRenderState state, RenderTarget main, int tw, int th, RtBuffer ubo) {
        RtxConfig config = RtxConfig.get();
        RtxConfig.Quality q = config.quality;
        CameraRenderState camera = state.cameraRenderState;
        SkyRenderState sky = state.skyRenderState;
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);

        proj.set(haveLevelProjection ? levelProjection : camera.projectionMatrix);
        proj.invert(invProj);
        viewRot.set(camera.viewRotationMatrix);
        viewRot.invert(invViewRot);
        computeLight(state);

        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.0f : Math.min((now - lastFrameNanos) / 1.0e9f, 0.25f);
        lastFrameNanos = now;
        BlockPos eye = camera.blockPos;
        boolean overworldSky = sky.skybox == DimensionType.Skybox.OVERWORLD;
        float rain = level.getRainLevel(partial);
        float wetTarget = 0.0f;
        if (config.rainPuddles && overworldSky && rain > 0.0f
                && level.getBiome(eye).value().getPrecipitationAt(eye, level.getSeaLevel()) == Biome.Precipitation.RAIN) {
            wetTarget = rain;
        }
        wetness = approach(wetness, wetTarget, dt * (wetTarget > wetness ? 0.25f : 0.04f));

        Vec3 pos = camera.pos;
        boolean history = historyValid && config.temporalDenoise && prevCamera != null && prevCamera.distanceToSqr(pos) < 64.0;
        Matrix4f reproj = new Matrix4f(prevProj).mul(prevViewRot);
        if (prevCamera != null) {
            reproj.translate((float) (pos.x - prevCamera.x), (float) (pos.y - prevCamera.y), (float) (pos.z - prevCamera.z));
        }
        prevProj.set(proj);
        prevViewRot.set(viewRot);
        prevCamera = pos;

        float camX = (float) (pos.x - Math.floor(pos.x / 2048.0) * 2048.0);
        float camZ = (float) (pos.z - Math.floor(pos.z / 2048.0) * 2048.0);
        float time = ((state.gameTime % 72000L) + partial) / 20.0f;
        float cloudHeight = Float.isNaN(state.cloudHeight) ? -10000.0f : state.cloudHeight;
        Vector3f skyColor = sky.skyColor != null ? new Vector3f(sky.skyColor) : new Vector3f(0.5f, 0.7f, 1.0f);
        boolean submerged = submerged(camera);

        Std140Builder.intoBuffer(ubo.mappedBytes())
                .putMat4f(invProj)
                .putMat4f(proj)
                .putMat4f(invViewRot)
                .putMat4f(viewRot)
                .putMat4f(reproj)
                .putVec4(main.width, main.height, tw, th)
                .putVec4(lightDir.x, lightDir.y, lightDir.z, lightStrength)
                .putVec4(lightColor.x, lightColor.y, lightColor.z, daylight)
                .putVec4(skyColor.x, skyColor.y, skyColor.z, 1.0f)
                .putVec4(camera.fogData.color.x, camera.fogData.color.y, camera.fogData.color.z, camera.fogData.renderDistanceEnd)
                .putVec4(camX, (float) pos.y, camZ, time)
                .putVec4(q.aoRays, q.giRays, q.volumetricSteps, frameIndex & 0xFFFF)
                .putVec4(on(config.ambientOcclusion), on(config.shadows), on(config.globalIllumination), on(config.reflections))
                .putVec4(on(config.specular), on(config.volumetricLight), on(config.rainPuddles), on(config.waterWaves))
                .putVec4(config.aoStrength, config.shadowStrength, config.giStrength, config.reflectionStrength)
                .putVec4(config.rtDistance * 16.0f, 0.03f, wetness, on(config.alphaTestedShadows))
                .putVec4(config.temporalDenoise ? 0.15f : 1.0f, config.debugView.ordinal(), config.emissiveStrength, history ? 1.0f : 0.0f)
                .putVec4(cloudHeight, config.volumetricStrength, config.specularStrength, submerged ? 1.0f : 0.0f)
                .putVec4(on(q.giShadowRays), on(q.reflectionShadowRays), q.diffuseLod, 0.0f)
                .putVec4(lmSky[0], lmSky[1], lmSky[2], lmSky[3])
                .putVec4(lmBlock[0], lmBlock[1], lmBlock[2], lmBlock[3])
                .putVec4(lmAmbient[0], lmAmbient[1], lmAmbient[2], lmAmbient[3])
                .putVec4(faceShade[0], faceShade[1], faceShade[2], faceShade[3])
                .putVec4(directShare, 1.0f, 0.0f, 0.0f);
        ubo.flush(0, TRACE_UBO_SIZE);
    }

    private void writeRtUniforms(LevelRenderState state, RenderTarget main, int tw, int th) {
        RtxConfig config = RtxConfig.get();
        if (rtUbo == null) {
            rtUbo = new MappableRingBuffer(() -> "PocoRTX composite", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, RT_UBO_SIZE);
        }
        try (GpuBufferSlice.MappedView mapped = rtUbo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(mapped.data())
                    .putMat4f(invProj)
                    .putMat4f(viewRot)
                    .putVec4(main.width, main.height, tw, th)
                    .putVec4(lightColor.x, lightColor.y, lightColor.z, lightStrength)
                    .putVec4(lightDir.x, lightDir.y, lightDir.z, directShare)
                    .putVec4(config.ambientOcclusion ? config.aoStrength : 0.0f, config.shadowStrength,
                            config.volumetricLight && !submerged(state.cameraRenderState) ? config.volumetricStrength : 0.0f,
                            config.reflections ? 1.0f : 0.0f)
                    .putVec4(config.debugView.ordinal(), config.rtDistance * 16.0f,
                            config.globalIllumination ? config.giStrength : 0.0f, 1.0f)
                    .putVec4(RenderSystem.getDevice().getDeviceInfo().isZZeroToOne() ? 1.0f : 0.0f, 1.0f, 0.0f, 0.0f)
                    .putVec4(lmSky[0], lmSky[1], lmSky[2], lmSky[3])
                    .putVec4(lmBlock[0], lmBlock[1], lmBlock[2], lmBlock[3])
                    .putVec4(lmAmbient[0], lmAmbient[1], lmAmbient[2], lmAmbient[3])
                    .putVec4(faceShade[0], faceShade[1], faceShade[2], faceShade[3]);
        }
    }

    private static float on(boolean value) {
        return value ? 1.0f : 0.0f;
    }

    // ------------------------------------------------------------------ shader effects

    private void renderShaderEffects() {
        RtxConfig.ShaderSettings s = RtxConfig.get().shader;
        RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
        int w = main.width;
        int h = main.height;
        fxScene = ensure(fxScene, "PocoRTX shader scene", w, h, false, GpuFormat.RGBA8_UNORM);
        for (int i = 0; i < bloom.length; i++) {
            bloom[i] = ensure(bloom[i], "PocoRTX bloom " + i, Math.max(1, w >> (i + 1)), Math.max(1, h >> (i + 1)), false, GpuFormat.RGBA8_UNORM);
        }
        if (fxUbo == null) {
            fxUbo = new MappableRingBuffer(() -> "PocoRTX shader effects", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_MAP_WRITE, FX_UBO_SIZE);
        }
        try (GpuBufferSlice.MappedView mapped = fxUbo.currentBuffer().map(false, true)) {
            Std140Builder.intoBuffer(mapped.data())
                    .putVec4(s.exposure, s.saturation, s.vibrance, s.contrast)
                    .putVec4(s.temperature, s.vignette, s.sharpen, s.tonemap ? 1.0f : 0.0f)
                    .putVec4(s.bloom ? s.bloomStrength : 0.0f, 0.72f, 0.0f, 0.0f)
                    .putVec4(w, h, 1.0f / w, 1.0f / h);
        }
        encoder().copyTextureToTexture(main.getColorTexture(), fxScene.getColorTexture(), 0, 0, 0, 0, 0, w, h);
        GpuSampler linear = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
        GpuSampler nearest = nearest();
        if (s.bloom && s.bloomStrength > 0.0f) {
            drawFx("PocoRTX bloom prefilter", FX_BLOOM_PREFILTER, bloom[0], true, pass -> pass.setUniform("SourceSampler", fxScene.getColorTextureView(), linear));
            for (int i = 1; i < bloom.length; i++) {
                TextureTarget src = bloom[i - 1];
                drawFx("PocoRTX bloom down", FX_BLOOM_DOWN, bloom[i], true, pass -> pass.setUniform("SourceSampler", src.getColorTextureView(), linear));
            }
            for (int i = bloom.length - 1; i > 0; i--) {
                TextureTarget src = bloom[i];
                drawFx("PocoRTX bloom up", FX_BLOOM_UP, bloom[i - 1], false, pass -> pass.setUniform("SourceSampler", src.getColorTextureView(), linear));
            }
        } else {
            encoder().clearColorTexture(bloom[0].getColorTexture(), new org.joml.Vector4f(0.0f));
        }
        drawFx("PocoRTX shader final", FX_FINAL, main, false, pass -> {
            pass.setUniform("SceneSampler", fxScene.getColorTextureView(), nearest);
            pass.setUniform("BloomSampler", bloom[0].getColorTextureView(), linear);
        });
        fxUbo.rotate();
    }

    private void drawFx(String label, RenderPipeline pipeline, RenderTarget target, boolean clear, Consumer<RenderPass> bind) {
        Optional<org.joml.Vector4fc> clearColor = clear ? Optional.of(new org.joml.Vector4f(0.0f)) : Optional.empty();
        try (RenderPass pass = encoder().createRenderPass(() -> label, target.getColorTextureView(), clearColor)) {
            RenderSystem.bindDefaultUniforms(pass);
            pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
            pass.setUniform(FX_UBO, fxUbo.currentBuffer());
            bind.accept(pass);
            pass.draw(3, 1, 0, 0);
        }
    }

    // ------------------------------------------------------------------ resources

    private static TextureTarget ensure(TextureTarget target, String label, int w, int h, boolean depth, GpuFormat format) {
        if (target == null) {
            return depth ? new TextureTarget(label, w, h, null, GpuFormat.D32_FLOAT) : new TextureTarget(label, w, h, format, null);
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
        historyValid = false;
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

    private static float approach(float current, float target, float maxDelta) {
        return current < target ? Math.min(target, current + maxDelta) : Math.max(target, current - maxDelta);
    }
}
