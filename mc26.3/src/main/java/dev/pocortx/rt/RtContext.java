package dev.pocortx.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import dev.pocortx.mixin.vulkan.GpuDeviceAccessor;
import dev.pocortx.mixin.vulkan.VulkanCommandEncoderAccessor;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocatorCreateInfo;
import org.lwjgl.util.vma.VmaVulkanFunctions;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructurePropertiesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything the ray tracer needs from Minecraft's Vulkan device: the VkDevice, the command
 * buffer Minecraft is currently recording, its deferred destruction queue, and our own memory
 * allocator (Minecraft's allocator is created without buffer device addresses).
 */
public final class RtContext implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    private static RtContext instance;
    private static Object instanceDevice;

    public final VulkanDevice device;
    public final VkDevice vk;
    public final long vma;
    public final int scratchAlignment;

    private RtContext(VulkanDevice device) {
        this.device = device;
        this.vk = device.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VmaVulkanFunctions functions = VmaVulkanFunctions.calloc(stack).set(vk.getPhysicalDevice().getInstance(), vk);
            VmaAllocatorCreateInfo createInfo = VmaAllocatorCreateInfo.calloc(stack)
                    .flags(Vma.VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT)
                    .instance(vk.getPhysicalDevice().getInstance())
                    .vulkanApiVersion(VK12.VK_API_VERSION_1_2)
                    .device(vk)
                    .physicalDevice(vk.getPhysicalDevice())
                    .pVulkanFunctions(functions);
            PointerBuffer pointer = stack.callocPointer(1);
            check(Vma.vmaCreateAllocator(createInfo, pointer), "vmaCreateAllocator");
            this.vma = pointer.get(0);

            VkPhysicalDeviceAccelerationStructurePropertiesKHR asProps = VkPhysicalDeviceAccelerationStructurePropertiesKHR.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(asProps.address());
            VK12.vkGetPhysicalDeviceProperties2(vk.getPhysicalDevice(), props);
            this.scratchAlignment = Math.max(1, asProps.minAccelerationStructureScratchOffsetAlignment());
        }
    }

    /** The ray tracing context, or null when the Vulkan backend is not running with ray queries enabled. */
    public static RtContext get() {
        if (!RtDeviceSupport.enabledOnDevice()) {
            return null;
        }
        Object backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).pocortx$backend();
        if (!(backend instanceof VulkanDevice vulkanDevice)) {
            return null;
        }
        if (instance == null || instanceDevice != vulkanDevice) {
            if (instance != null) {
                instance.close();
            }
            instance = new RtContext(vulkanDevice);
            instanceDevice = vulkanDevice;
            LOGGER.info("PocoRTX: hardware ray tracing context ready (scratch alignment {})", instance.scratchAlignment);
        }
        return instance;
    }

    public VulkanCommandEncoder encoder() {
        return device.createCommandEncoder();
    }

    /** The command buffer Minecraft is recording right now. Must not be called inside a render pass. */
    public VkCommandBuffer commandBuffer() {
        VulkanCommandEncoderAccessor accessor = (VulkanCommandEncoderAccessor) (Object) encoder();
        if (accessor.pocortx$currentRenderPass() != null) {
            throw new IllegalStateException("ray tracing commands recorded inside a render pass");
        }
        return accessor.pocortx$commandBuffer();
    }

    /** Full memory barrier, the same one Minecraft places after every pass. */
    public void barrier(VkCommandBuffer cb) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VulkanCommandEncoder.memoryBarrier(cb, stack);
        }
    }

    /** Runs {@code action} once the GPU can no longer be using what it frees. */
    public void destroyLater(Runnable action) {
        encoder().queueForDestroy(action::run);
    }

    public long asDeviceAddress(long accelerationStructure) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            return KHRAccelerationStructure.vkGetAccelerationStructureDeviceAddressKHR(vk,
                    org.lwjgl.vulkan.VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default()
                            .accelerationStructure(accelerationStructure));
        }
    }

    public static void check(int result, String what) {
        if (result != VK12.VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + result);
        }
    }

    @Override
    public void close() {
        // Called only when the device itself goes away; everything allocated from it is gone too.
        Vma.vmaDestroyAllocator(vma);
    }
}
