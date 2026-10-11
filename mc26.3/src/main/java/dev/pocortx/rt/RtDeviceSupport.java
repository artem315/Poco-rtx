package dev.pocortx.rt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import com.mojang.renderpearl.backend.vulkan.init.VulkanFeature;
import com.mojang.renderpearl.backend.vulkan.init.VulkanPNextStruct;
import dev.pocortx.mixin.vulkan.GpuDeviceAccessor;
import java.util.Set;
import org.lwjgl.vulkan.VKCapabilitiesDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;

/**
 * Hardware ray tracing (ray queries against acceleration structures) is offered to Minecraft as
 * an optional Vulkan feature set: the game enables it only when the driver supports all of it,
 * so devices without ray tracing start exactly as before.
 */
public final class RtDeviceSupport {
    public static final FeatureSet FEATURE_SET = new FeatureSet("PocoRTX hardware ray tracing",
            Set.of("VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations"),
            Set.of(new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceAccelerationStructureFeaturesKHR.class), "accelerationStructure"),
                    new VulkanFeature(new VulkanPNextStruct(VkPhysicalDeviceRayQueryFeaturesKHR.class), "rayQuery"),
                    new VulkanFeature(VulkanFeatureSets.VK12_FEATURES_STRUCT, "bufferDeviceAddress")));

    private static Object checkedDevice;
    private static boolean enabled;
    private static String reason = "pocortx.rt.status.not_vulkan";
    private static String deviceName = "";

    private RtDeviceSupport() {
    }

    private static void check() {
        Object backend;
        try {
            backend = ((GpuDeviceAccessor) RenderSystem.getDevice()).pocortx$backend();
        } catch (Throwable t) {
            return;
        }
        if (backend == checkedDevice) {
            return;
        }
        checkedDevice = backend;
        enabled = false;
        if (!(backend instanceof VulkanDevice device)) {
            reason = "pocortx.rt.status.not_vulkan";
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            VK12.vkGetPhysicalDeviceProperties(device.vkDevice().getPhysicalDevice(), props);
            deviceName = props.deviceNameString();
        }
        VKCapabilitiesDevice caps = device.vkDevice().getCapabilities();
        enabled = caps.VK_KHR_ray_query && caps.VK_KHR_acceleration_structure;
        reason = enabled ? null : "pocortx.rt.status.no_extension";
    }

    public static boolean enabledOnDevice() {
        check();
        return enabled;
    }

    /** Translation key explaining why hardware ray tracing is off, or null when it is available. */
    public static String unavailableReason() {
        check();
        return enabled ? null : reason;
    }

    public static String deviceName() {
        check();
        return deviceName;
    }
}
