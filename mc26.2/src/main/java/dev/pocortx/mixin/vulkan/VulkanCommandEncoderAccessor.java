package dev.pocortx.mixin.vulkan;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
    @Invoker("commandBuffer")
    VkCommandBuffer pocortx$commandBuffer();

    @Accessor("currentRenderPass")
    VulkanRenderPass pocortx$currentRenderPass();
}
