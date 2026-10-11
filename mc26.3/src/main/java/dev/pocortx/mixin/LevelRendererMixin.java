package dev.pocortx.mixin;

import com.mojang.renderpearl.api.commands.RenderPass;
import dev.pocortx.render.RtxRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
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
