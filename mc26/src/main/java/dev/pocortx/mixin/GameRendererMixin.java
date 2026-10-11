package dev.pocortx.mixin;

import dev.pocortx.render.RtxRenderer;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
    // The level projection with view bobbing and nausea applied, exactly as the terrain sees it.
    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private Matrix4f pocortx$captureLevelProjection(Matrix4f projection) {
        RtxRenderer.INSTANCE.setLevelProjection(projection);
        return projection;
    }
}
