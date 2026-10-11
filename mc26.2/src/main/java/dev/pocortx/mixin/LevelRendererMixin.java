package dev.pocortx.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.pocortx.render.RtxRenderer;
import dev.pocortx.rt.RtScene;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
    @Shadow
    @Final
    private LevelTargetBundle targets;

    // Only the level's own features (not the first-person hand or GUI items) become ray tracing geometry.
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;"))
    private void pocortx$beginEntityCapture(CallbackInfo ci) {
        RtScene.beginEntityCapture();
    }

    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher;prepareFrame(Lnet/minecraft/client/renderer/SubmitNodeStorage;)Lnet/minecraft/client/renderer/feature/FeatureRenderDispatcher$PreparedFrame;",
            shift = At.Shift.AFTER))
    private void pocortx$endEntityCapture(CallbackInfo ci) {
        RtScene.endEntityCapture();
    }

    // Improved transparency draws water into its own target and merges it in a later
    // post chain; the effects have to run after that merge to sit on top of the water.
    @Inject(method = "render", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/PostChain;addToFrame(Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder;IILnet/minecraft/client/renderer/PostChain$TargetBundle;)V",
            ordinal = 1, shift = At.Shift.AFTER))
    private void pocortx$afterTransparencyChain(CallbackInfo ci, @Local FrameGraphBuilder frame) {
        RtxRenderer.INSTANCE.addAfterTransparencyPass(frame, this.targets);
    }
}
