package dev.pocortx.mixin;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.pocortx.rt.RtScene;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.client.renderer.StagedVertexBuffer$Draw")
abstract class StagedVertexBufferDrawMixin {
    @Shadow
    @Final
    private VertexFormat format;

    @Shadow
    @Final
    private PrimitiveTopology primitiveTopology;

    // Entities, block entities and dropped items, captured while the level's features are prepared.
    @Inject(method = "append", at = @At("HEAD"))
    private void pocortx$captureEntityMesh(MeshData mesh, CallbackInfo ci) {
        RtScene.onFeatureMesh(format, primitiveTopology, mesh);
    }
}
