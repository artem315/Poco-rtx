package dev.pocortx.rt;

import com.mojang.blaze3d.vulkan.VulkanBackend;
import com.mojang.blaze3d.vulkan.VulkanPhysicalDevice;
import com.mojang.blaze3d.vulkan.init.VulkanFeature;
import com.mojang.blaze3d.vulkan.init.VulkanPNextStruct;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.KHRRayQuery;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkPhysicalDeviceAccelerationStructureFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceRayQueryFeaturesKHR;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan12Features;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Turns on hardware ray tracing (ray queries against acceleration structures) when Minecraft
 * creates its Vulkan device. Nothing is enabled unless the driver supports all of it, so the
 * game itself starts exactly as before on GPUs without ray tracing.
 */
public final class RtDeviceSupport {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    public static final List<String> EXTENSIONS = List.of(
            "VK_KHR_acceleration_structure", "VK_KHR_ray_query", "VK_KHR_deferred_host_operations");

    private static final VulkanPNextStruct AS_FEATURES = new VulkanPNextStruct(
            KHRAccelerationStructure.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR,
            VkPhysicalDeviceAccelerationStructureFeaturesKHR.SIZEOF);
    private static final VulkanPNextStruct RAY_QUERY_FEATURES = new VulkanPNextStruct(
            KHRRayQuery.VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR,
            VkPhysicalDeviceRayQueryFeaturesKHR.SIZEOF);
    private static final VulkanFeature ACCELERATION_STRUCTURE = new VulkanFeature(
            AS_FEATURES, "accelerationStructure", VkPhysicalDeviceAccelerationStructureFeaturesKHR.ACCELERATIONSTRUCTURE);
    private static final VulkanFeature RAY_QUERY = new VulkanFeature(
            RAY_QUERY_FEATURES, "rayQuery", VkPhysicalDeviceRayQueryFeaturesKHR.RAYQUERY);
    private static final VulkanFeature BUFFER_DEVICE_ADDRESS = new VulkanFeature(
            VulkanBackend.VK12_FEATURES_STRUCT, "bufferDeviceAddress", VkPhysicalDeviceVulkan12Features.BUFFERDEVICEADDRESS);

    private static volatile boolean enabledOnDevice;
    private static volatile String unavailableReason = "pocortx.rt.status.not_vulkan";
    private static volatile String deviceName = "";

    private RtDeviceSupport() {
    }

    /** Called from the device creation mixin with the extension and feature sets Minecraft is about to enable. */
    public static void extendDevice(Collection<String> extensions, VulkanPhysicalDevice physicalDevice, Set<VulkanFeature> features) {
        enabledOnDevice = false;
        try {
            deviceName = physicalDevice.deviceName();
            for (String extension : EXTENSIONS) {
                if (!physicalDevice.hasDeviceExtension(extension)) {
                    unavailableReason = "pocortx.rt.status.no_extension";
                    LOGGER.info("PocoRTX: hardware ray tracing unavailable on '{}': missing {}", deviceName, extension);
                    return;
                }
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceFeatures2 query = VkPhysicalDeviceFeatures2.calloc(stack).sType$Default();
                AS_FEATURES.findOrCreateStructInPNextChain(query, stack);
                RAY_QUERY_FEATURES.findOrCreateStructInPNextChain(query, stack);
                VulkanBackend.VK12_FEATURES_STRUCT.findOrCreateStructInPNextChain(query, stack);
                VK12.vkGetPhysicalDeviceFeatures2(physicalDevice.vkPhysicalDevice(), query);
                for (VulkanFeature feature : List.of(ACCELERATION_STRUCTURE, RAY_QUERY, BUFFER_DEVICE_ADDRESS)) {
                    if (!feature.get(query)) {
                        unavailableReason = "pocortx.rt.status.no_feature";
                        LOGGER.info("PocoRTX: hardware ray tracing unavailable on '{}': missing feature {}", deviceName, feature.name());
                        return;
                    }
                }
            }
            extensions.addAll(EXTENSIONS);
            features.add(ACCELERATION_STRUCTURE);
            features.add(RAY_QUERY);
            features.add(BUFFER_DEVICE_ADDRESS);
            enabledOnDevice = true;
            unavailableReason = null;
            LOGGER.info("PocoRTX: enabling hardware ray tracing (ray query) on '{}'", deviceName);
        } catch (Throwable t) {
            unavailableReason = "pocortx.rt.status.error";
            LOGGER.warn("PocoRTX: could not check ray tracing support", t);
        }
    }

    public static boolean enabledOnDevice() {
        return enabledOnDevice;
    }

    /** Translation key explaining why hardware ray tracing is off, or null when it is available. */
    public static String unavailableReason() {
        return enabledOnDevice ? null : unavailableReason;
    }

    public static String deviceName() {
        return deviceName;
    }
}
