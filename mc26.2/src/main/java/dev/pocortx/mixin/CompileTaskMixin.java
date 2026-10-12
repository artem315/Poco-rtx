package dev.pocortx.mixin;

import dev.pocortx.rt.RtScene;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
abstract class CompileTaskMixin {
    // Only meshes from compile tasks that vanilla keeps become ray tracing geometry.
    @Inject(method = "doTask", at = @At("HEAD"))
    private void pocortx$startTask(SectionBufferBuilderPack buffers, CallbackInfoReturnable<?> cir) {
        RtScene.discardPending();
    }

    @Inject(method = "doTask", at = @At("RETURN"))
    private void pocortx$finishTask(SectionBufferBuilderPack buffers, CallbackInfoReturnable<?> cir) {
        Object result = cir.getReturnValue();
        RtScene.onCompileTaskDone(result instanceof Enum<?> e && e.name().equals("SUCCESSFUL"));
    }
}
