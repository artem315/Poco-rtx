package dev.pocortx.rt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;
import org.lwjgl.vulkan.VkWriteDescriptorSetAccelerationStructureKHR;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
import static org.lwjgl.vulkan.VK12.*;

/** A compute pipeline whose single descriptor set is pushed with VK_KHR_push_descriptor. */
public final class RtCompute {
    private final RtContext ctx;
    private final int[] types;
    public final long setLayout;
    public final long pipelineLayout;
    public final long pipeline;

    public RtCompute(RtContext ctx, String spirvResource, int... descriptorTypes) {
        this.ctx = ctx;
        this.types = descriptorTypes.clone();
        ByteBuffer code = load(spirvResource);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                bindings.get(i).binding(i).descriptorType(types[i]).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            }
            LongBuffer handle = stack.mallocLong(1);
            RtContext.check(vkCreateDescriptorSetLayout(ctx.vk, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
                    .pBindings(bindings), null, handle), "vkCreateDescriptorSetLayout");
            setLayout = handle.get(0);
            RtContext.check(vkCreatePipelineLayout(ctx.vk, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout)), null, handle), "vkCreatePipelineLayout");
            pipelineLayout = handle.get(0);
            RtContext.check(vkCreateShaderModule(ctx.vk, VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code), null, handle),
                    "vkCreateShaderModule(" + spirvResource + ")");
            long module = handle.get(0);
            VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
            info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
            int result = vkCreateComputePipelines(ctx.vk, VK_NULL_HANDLE, info, null, handle);
            vkDestroyShaderModule(ctx.vk, module, null);
            RtContext.check(result, "vkCreateComputePipelines(" + spirvResource + ")");
            pipeline = handle.get(0);
        } finally {
            MemoryUtil.memFree(code);
        }
    }

    private static ByteBuffer load(String resource) {
        try (InputStream in = RtCompute.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing shader " + resource);
            }
            byte[] bytes = in.readAllBytes();
            ByteBuffer buffer = MemoryUtil.memAlloc(bytes.length);
            buffer.put(bytes).flip();
            return buffer;
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + resource, e);
        }
    }

    /** Collects the descriptors for one dispatch. */
    public final class Bindings {
        private final MemoryStack stack;
        private final VkWriteDescriptorSet.Buffer writes;

        Bindings(MemoryStack stack) {
            this.stack = stack;
            this.writes = VkWriteDescriptorSet.calloc(types.length, stack);
            for (int i = 0; i < types.length; i++) {
                writes.get(i).sType$Default().dstBinding(i).descriptorType(types[i]).descriptorCount(1);
            }
        }

        public Bindings accelerationStructure(int binding, long as) {
            VkWriteDescriptorSetAccelerationStructureKHR asInfo = VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default()
                    .pAccelerationStructures(stack.longs(as));
            writes.get(binding).pNext(asInfo.address()).descriptorCount(1);
            return this;
        }

        public Bindings buffer(int binding, RtBuffer buffer, long offset, long range) {
            writes.get(binding).pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(buffer.buffer).offset(buffer.offset + offset).range(range));
            return this;
        }

        public Bindings sampled(int binding, long imageView, long sampler) {
            writes.get(binding).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(imageView).sampler(sampler).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            return this;
        }

        public Bindings storage(int binding, long imageView) {
            writes.get(binding).pImageInfo(VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(imageView).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
            return this;
        }

        /** Binds the pipeline, pushes the descriptors and dispatches. */
        public void dispatch(VkCommandBuffer cb, int groupsX, int groupsY) {
            vkCmdBindPipeline(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
            KHRPushDescriptor.vkCmdPushDescriptorSetKHR(cb, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, writes);
            vkCmdDispatch(cb, groupsX, groupsY, 1);
        }
    }

    public Bindings bindings(MemoryStack stack) {
        return new Bindings(stack);
    }

    /** Bindings shared by the three kernels; 9-11 are the G-buffer (written by the light kernel). */
    public static int[] kernelLayout(boolean writesGBuffer) {
        int gbuffer = writesGBuffer ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        return new int[] {
                VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR,  // 0 scene
                VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER,              // 1 frame
                VK_DESCRIPTOR_TYPE_STORAGE_BUFFER,              // 2 instances
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,      // 3 opaque depth
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,      // 4 final depth
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,      // 5 block atlas
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,      // 6 materials
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,      // 7 history of this kernel
                VK_DESCRIPTOR_TYPE_STORAGE_IMAGE,               // 8 output
                gbuffer,                                        // 9 normal + roughness
                gbuffer,                                        // 10 albedo + F0
                gbuffer,                                        // 11 light + emission + id
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER,      // 12 this frame's light output
                VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER       // 13 previous light output (depth for reprojection)
        };
    }

    public void destroyLater() {
        var vk = ctx.vk;
        long p = pipeline, pl = pipelineLayout, sl = setLayout;
        ctx.destroyLater(() -> {
            vkDestroyPipeline(vk, p, null);
            vkDestroyPipelineLayout(vk, pl, null);
            vkDestroyDescriptorSetLayout(vk, sl, null);
        });
    }
}
