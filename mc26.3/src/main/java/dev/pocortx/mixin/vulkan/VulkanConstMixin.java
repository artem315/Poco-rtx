package dev.pocortx.mixin.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import dev.pocortx.rt.RtTextures;
import org.lwjgl.vulkan.VK10;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanConst.class)
abstract class VulkanConstMixin {
    // Our ray tracing outputs are ordinary Minecraft textures that compute shaders also write to.
    @Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
    private static void pocortx$storageUsage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
        if (RtTextures.storageRequested()) {
            cir.setReturnValue(cir.getReturnValueI() | VK10.VK_IMAGE_USAGE_STORAGE_BIT);
        }
    }
}
