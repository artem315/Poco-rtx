package dev.pocortx.mixin;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.pocortx.rt.RtScene;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
    // The same meshes the terrain renderer uploads become the ray tracing geometry.
    @Inject(method = "compile", at = @At("RETURN"))
    private void pocortx$captureForRayTracing(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting,
            SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> cir) {
        RtScene.onSectionCompiled(sectionPos, cir.getReturnValue());
    }
}
