package dev.pocortx.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.pocortx.render.RtxRenderer;
import dev.pocortx.rt.RtScene;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
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

    @ModifyVariable(method = "executeClassicTransparency", at = @At("HEAD"), argsOnly = true)
    private RenderPass pocortx$splitBeforeTranslucent(RenderPass mainPass) {
        return RtxRenderer.INSTANCE.beforeClassicTranslucency(mainPass);
    }

    @Inject(method = "executeClassicTransparency", at = @At("RETURN"))
    private void pocortx$closeResumedPass(CallbackInfo ci) {
        RtxRenderer.INSTANCE.afterClassicTranslucency();
    }

    @Inject(method = "executeOit", at = @At("HEAD"))
    private void pocortx$beforeOit(CallbackInfo ci) {
        RtxRenderer.INSTANCE.beforeOit();
    }
}
