package dev.pocortx.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import dev.pocortx.rt.RtDeviceSupport;
import java.util.Collection;
import java.util.Set;
import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanBackend.class)
abstract class VulkanBackendMixin {
    @Inject(method = "createDevice(Ljava/util/Collection;Lcom/mojang/blaze3d/vulkan/VulkanPhysicalDevice;Ljava/util/Set;)Lorg/lwjgl/vulkan/VkDevice;",
            at = @At("HEAD"))
    private static void pocortx$enableRayTracing(Collection<String> extensions, VulkanPhysicalDevice physicalDevice,
            Set<VulkanFeature> features, CallbackInfoReturnable<VkDevice> cir) {
        RtDeviceSupport.extendDevice(extensions, physicalDevice, features);
    }
}
