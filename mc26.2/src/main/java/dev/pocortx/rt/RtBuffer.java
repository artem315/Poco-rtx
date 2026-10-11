package dev.pocortx.rt;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.util.vma.VmaAllocationInfo;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkBufferDeviceAddressInfo;

/** A VkBuffer from our allocator with its device address; optionally persistently mapped. */
public final class RtBuffer {
    public final long buffer;
    public final long allocation;
    public final long size;
    /** Device address of the usable range (aligned as requested). */
    public final long address;
    /** Where the usable range starts inside the VkBuffer; descriptors and AS creation must add it. */
    public final long offset;
    public final long mapped;
    private final RtContext ctx;
    private boolean freed;

    private RtBuffer(RtContext ctx, long buffer, long allocation, long size, long address, long offset, long mapped) {
        this.ctx = ctx;
        this.buffer = buffer;
        this.allocation = allocation;
        this.size = size;
        this.address = address;
        this.offset = offset;
        this.mapped = mapped;
    }

    public static RtBuffer create(RtContext ctx, long size, int usage, boolean hostVisible, long alignment) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size)
                    .usage(usage | VK12.VK_BUFFER_USAGE_SHADER_DEVICE_ADDRESS_BIT)
                    .sharingMode(VK12.VK_SHARING_MODE_EXCLUSIVE);
            VmaAllocationCreateInfo alloc = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_AUTO);
            if (hostVisible) {
                alloc.flags(Vma.VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT | Vma.VMA_ALLOCATION_CREATE_MAPPED_BIT);
            }
            LongBuffer pBuffer = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);
            VmaAllocationInfo allocInfo = VmaAllocationInfo.calloc(stack);
            // Room to align the start by hand: not every LWJGL version has vmaCreateBufferWithAlignment.
            if (alignment > 1) {
                info.size(size + alignment);
            }
            int result = Vma.vmaCreateBuffer(ctx.vma, info, alloc, pBuffer, pAlloc, allocInfo);
            RtContext.check(result, "vmaCreateBuffer(" + size + ")");
            long buffer = pBuffer.get(0);
            long address = VK12.vkGetBufferDeviceAddress(ctx.vk, VkBufferDeviceAddressInfo.calloc(stack).sType$Default().buffer(buffer));
            long offset = alignment > 1 ? (alignment - address % alignment) % alignment : 0L;
            long mapped = hostVisible ? allocInfo.pMappedData() + offset : 0L;
            return new RtBuffer(ctx, buffer, pAlloc.get(0), size, address + offset, offset, mapped);
        }
    }

    public ByteBuffer mappedBytes() {
        if (mapped == 0L) {
            throw new IllegalStateException("buffer is not host visible");
        }
        return MemoryUtil.memByteBuffer(mapped, (int) size);
    }

    /** Makes host writes visible to the device on memory that is not host coherent. */
    public void flush(long offset, long length) {
        Vma.vmaFlushAllocation(ctx.vma, allocation, this.offset + offset, length);
    }

    /** Frees the buffer once the frames that may still read it have finished. */
    public void freeLater() {
        if (freed) {
            return;
        }
        freed = true;
        long b = buffer;
        long a = allocation;
        long vma = ctx.vma;
        ctx.destroyLater(() -> Vma.vmaDestroyBuffer(vma, b, a));
    }
}
