package dev.pocortx.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanFeatureSets;
import com.mojang.renderpearl.backend.vulkan.init.FeatureSet;
import dev.pocortx.rt.RtDeviceSupport;
import java.util.Set;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(VulkanFeatureSets.class)
abstract class VulkanFeatureSetsMixin {
    // Enabled by Minecraft only when the device supports every extension and feature in it.
    @Inject(method = "optionalFeatureSets", at = @At("RETURN"))
    private static void pocortx$offerRayTracing(CallbackInfoReturnable<Set<FeatureSet>> cir) {
        cir.getReturnValue().add(RtDeviceSupport.FEATURE_SET);
    }
}
