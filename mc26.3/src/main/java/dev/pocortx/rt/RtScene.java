package dev.pocortx.rt;

import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.KHRAccelerationStructure;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkAccelerationStructureBuildGeometryInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildRangeInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureBuildSizesInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureCreateInfoKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryKHR;
import org.lwjgl.vulkan.VkAccelerationStructureGeometryTrianglesDataKHR;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * Ray tracing geometry: one bottom level acceleration structure per chunk section, built from
 * the same meshes Minecraft compiles for rasterisation, and a top level structure rebuilt every
 * frame from the sections around the camera.
 *
 * <p>Section vertices use Minecraft's block format (28 bytes: float3 position relative to the
 * section origin, RGBA8 colour, float2 atlas UV, short2 lightmap) and are kept as is, so the
 * shaders read UVs, tint and light at hit points straight from the BLAS input buffers.
 */
public final class RtScene {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    public static final int VERTEX_SIZE = 28;
    private static final int MAX_QUADS = 1 << 17;
    private static final int RING = 3;
    private static final int ENTRY_SIZE = 32;
    private static final int INSTANCE_SIZE = 64;
    public static final int MASK_OPAQUE = 0x01;
    public static final int MASK_TRANSLUCENT = 0x02;
    public static final int MASK_ENTITY = 0x04;
    private static final int FLAG_TRANSLUCENT = 4;
    private static final int FLAG_ENTITY = 8;
    private static final int ENTITY_VERTEX_SIZE = 36;
    private static final int BUILDS_PER_FRAME = 48;
    private static final long SCRATCH_BUDGET = 48L << 20;
    private static final int RECOMPILE_REQUESTS_PER_FRAME = 24;

    // ------------------------------------------------------------------ capture (worker threads)

    private record CapturedLayer(int layer, long data, int vertexCount) {
    }

    private record Captured(long sectionNode, CapturedLayer[] layers) {
    }

    private static final ConcurrentLinkedQueue<Captured> CAPTURED = new ConcurrentLinkedQueue<>();
    private static volatile boolean capturing;
    private static volatile int captureX, captureY, captureZ, captureRadius;

    /** Called on chunk compile threads with the freshly built meshes of one section. */
    public static void onSectionCompiled(SectionPos pos, SectionCompiler.Results results) {
        if (!capturing) {
            return;
        }
        int r = captureRadius + 1;
        if (Math.abs(pos.x() - captureX) > r || Math.abs(pos.y() - captureY) > r || Math.abs(pos.z() - captureZ) > r) {
            return;
        }
        List<CapturedLayer> layers = new ArrayList<>(3);
        for (Map.Entry<ChunkSectionLayer, MeshData> entry : results.renderedLayers.entrySet()) {
            MeshData mesh = entry.getValue();
            MeshData.DrawState state = mesh.drawState();
            if (state.format() != DefaultVertexFormat.BLOCK || state.primitiveTopology() != PrimitiveTopology.QUADS
                    || state.vertexCount() < 4 || state.vertexCount() / 4 > MAX_QUADS) {
                continue;
            }
            ByteBuffer src = mesh.vertexBuffer();
            int bytes = state.vertexCount() * VERTEX_SIZE;
            if (src.remaining() < bytes) {
                continue;
            }
            long copy = MemoryUtil.nmemAlloc(bytes);
            MemoryUtil.memCopy(MemoryUtil.memAddress(src), copy, bytes);
            layers.add(new CapturedLayer(entry.getKey().ordinal(), copy, state.vertexCount()));
        }
        CAPTURED.add(new Captured(pos.asLong(), layers.toArray(new CapturedLayer[0])));
    }

    // ------------------------------------------------------------------ entity capture (render thread)

    private static boolean entityCapture;
    private static long entityData;
    private static long entityCapacity;
    private static int entityVertices;

    /** Around the preparation of the level's features (entities, block entities, items). */
    public static void beginEntityCapture() {
        entityCapture = capturing;
        entityVertices = 0;
    }

    public static void endEntityCapture() {
        entityCapture = false;
    }

    public static void onFeatureMesh(VertexFormat format, PrimitiveTopology topology, MeshData mesh) {
        if (!entityCapture || format != DefaultVertexFormat.ENTITY || topology != PrimitiveTopology.QUADS
                || format.getVertexSize() != ENTITY_VERTEX_SIZE) {
            return;
        }
        int count = mesh.drawState().vertexCount() & ~3;
        if (count < 4 || (entityVertices + count) / 4 > MAX_QUADS) {
            return;
        }
        ByteBuffer src = mesh.vertexBuffer();
        long bytes = (long) count * ENTITY_VERTEX_SIZE;
        if (src.remaining() < bytes) {
            return;
        }
        long needed = (long) (entityVertices + count) * ENTITY_VERTEX_SIZE;
        if (needed > entityCapacity) {
            long capacity = Math.max(needed, Math.max(1L << 20, entityCapacity * 2));
            entityData = MemoryUtil.nmemRealloc(entityData, capacity);
            entityCapacity = capacity;
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(src), entityData + (long) entityVertices * ENTITY_VERTEX_SIZE, bytes);
        entityVertices += count;
    }

    // ------------------------------------------------------------------ render thread state

    private static final class Section {
        final long node;
        final int x, y, z;
        final RtBuffer[] vertices = new RtBuffer[3];
        final int[] quads = new int[3];
        long blasOpaque, blasTranslucent;
        RtBuffer blasOpaqueBuffer, blasTranslucentBuffer;
        long blasOpaqueAddress, blasTranslucentAddress;
        int opaqueFlags;
        long opaqueAddr0, opaqueAddr1;
        boolean built;

        Section(long node) {
            this.node = node;
            this.x = SectionPos.x(node);
            this.y = SectionPos.y(node);
            this.z = SectionPos.z(node);
        }

        boolean empty() {
            return vertices[0] == null && vertices[1] == null && vertices[2] == null;
        }
    }

    private final RtContext ctx;
    private final Long2ObjectOpenHashMap<Section> sections = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap requested = new Long2IntOpenHashMap();
    private final ObjectArrayList<Section> pendingBuilds = new ObjectArrayList<>();
    private final RtBuffer quadIndices;
    private RtBuffer scratch;
    // AS build descriptions for a whole batch do not fit the default per-thread stack.
    private final MemoryStack buildStack = MemoryStack.create(1 << 20);

    private final RtBuffer[] instanceRing = new RtBuffer[RING];
    private final RtBuffer[] tableRing = new RtBuffer[RING];
    private int instanceCapacity;
    private long tlas;
    private RtBuffer tlasBuffer;
    private RtBuffer tlasScratch;
    private int tlasCapacity;
    private int frame;
    private ClientLevel level;
    private int instanceCount;

    // Entities: one BLAS rebuilt every frame from camera-relative vertices.
    private final RtBuffer[] entityRing = new RtBuffer[RING];
    private long entityRingCapacity;
    private long entityBlas;
    private RtBuffer entityBlasBuffer;
    private RtBuffer entityScratch;
    private long entityBlasAddress;
    private int entityBlasQuads;
    private boolean entityActive;
    private long entityVertexAddress;

    public RtScene(RtContext ctx) {
        this.ctx = ctx;
        quadIndices = RtBuffer.create(ctx, (long) MAX_QUADS * 6 * 4,
                VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, 0);
        ByteBuffer idx = quadIndices.mappedBytes();
        for (int q = 0; q < MAX_QUADS; q++) {
            int v = q * 4;
            idx.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
        }
        quadIndices.flush(0, quadIndices.size);
    }

    public long tlas() {
        return tlas;
    }

    public RtBuffer table() {
        return tableRing[frame % RING];
    }

    public int instanceCount() {
        return instanceCount;
    }

    public int sectionCount() {
        return sections.size();
    }

    /**
     * Brings the acceleration structures up to date for this frame and records the builds into
     * {@code cb}. Ends with a barrier, so the TLAS is ready for ray queries afterwards.
     */
    public void update(VkCommandBuffer cb, Vec3 camera, int radius) {
        frame++;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != level) {
            clear();
            level = mc.level;
        }
        int cx = SectionPos.blockToSectionCoord(camera.x);
        int cy = SectionPos.blockToSectionCoord(camera.y);
        int cz = SectionPos.blockToSectionCoord(camera.z);
        captureX = cx;
        captureY = cy;
        captureZ = cz;
        captureRadius = radius;
        capturing = true;

        drainCaptured(cx, cy, cz, radius);
        evictFarSections(cx, cy, cz, radius);
        requestMissing(cx, cy, cz, radius);

        ctx.barrier(cb);
        buildPendingBlas(cb);
        buildEntityBlas(cb);
        // The TLAS build reads the BLASes built just above.
        ctx.barrier(cb);
        buildTlas(cb, camera, cx, cy, cz, radius);
        ctx.barrier(cb);
    }

    private void drainCaptured(int cx, int cy, int cz, int radius) {
        Captured captured;
        while ((captured = CAPTURED.poll()) != null) {
            long node = captured.sectionNode();
            int sx = SectionPos.x(node), sy = SectionPos.y(node), sz = SectionPos.z(node);
            boolean inRange = Math.abs(sx - cx) <= radius + 1 && Math.abs(sy - cy) <= radius + 1 && Math.abs(sz - cz) <= radius + 1;
            Section section = inRange ? new Section(node) : null;
            for (CapturedLayer layer : captured.layers()) {
                if (section != null) {
                    long bytes = (long) layer.vertexCount() * VERTEX_SIZE;
                    RtBuffer buffer = RtBuffer.create(ctx, bytes,
                            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                            true, 0);
                    MemoryUtil.memCopy(layer.data(), buffer.mapped, bytes);
                    buffer.flush(0, bytes);
                    section.vertices[layer.layer()] = buffer;
                    section.quads[layer.layer()] = layer.vertexCount() / 4;
                }
                MemoryUtil.nmemFree(layer.data());
            }
            if (section == null) {
                continue;
            }
            requested.remove(node);
            Section old = sections.put(node, section);
            if (old != null) {
                free(old);
                pendingBuilds.remove(old);
            }
            if (!section.empty()) {
                pendingBuilds.add(section);
            }
        }
    }

    private void evictFarSections(int cx, int cy, int cz, int radius) {
        if (frame % 20 != 0) {
            return;
        }
        int keep = radius + 2;
        ClientLevel lvl = level;
        sections.values().removeIf(s -> {
            boolean far = Math.abs(s.x - cx) > keep || Math.abs(s.y - cy) > keep || Math.abs(s.z - cz) > keep;
            boolean unloaded = lvl != null && !lvl.hasChunk(s.x, s.z);
            if (far || unloaded) {
                free(s);
                pendingBuilds.remove(s);
                return true;
            }
            return false;
        });
        requested.long2IntEntrySet().removeIf(e -> frame - e.getIntValue() > 400);
    }

    /** Sections near the camera that were compiled before we started listening get recompiled once. */
    private void requestMissing(int cx, int cy, int cz, int radius) {
        ClientLevel lvl = level;
        if (lvl == null) {
            return;
        }
        int budget = RECOMPILE_REQUESTS_PER_FRAME;
        int minY = lvl.getMinSectionY();
        int maxY = lvl.getMaxSectionY();
        // Nearest first: walk shells of growing radius.
        for (int r = 0; r <= radius && budget > 0; r++) {
            for (int dx = -r; dx <= r && budget > 0; dx++) {
                for (int dz = -r; dz <= r && budget > 0; dz++) {
                    for (int dy = -r; dy <= r && budget > 0; dy++) {
                        if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != r) {
                            continue;
                        }
                        int sx = cx + dx, sy = cy + dy, sz = cz + dz;
                        if (sy < minY || sy > maxY || !lvl.hasChunk(sx, sz)) {
                            continue;
                        }
                        long node = SectionPos.asLong(sx, sy, sz);
                        if (sections.containsKey(node) || requested.containsKey(node)) {
                            continue;
                        }
                        LevelChunk chunk = lvl.getChunk(sx, sz);
                        LevelChunkSection chunkSection = chunk.getSection(lvl.getSectionIndexFromSectionY(sy));
                        if (chunkSection.hasOnlyAir()) {
                            sections.put(node, new Section(node));
                            continue;
                        }
                        lvl.setSectionRangeDirty(sx, sy, sz, sx, sy, sz);
                        requested.put(node, frame);
                        budget--;
                    }
                }
            }
        }
    }

    private void buildPendingBlas(VkCommandBuffer cb) {
        if (pendingBuilds.isEmpty()) {
            return;
        }
        try (MemoryStack stack = buildStack.push()) {
            List<VkAccelerationStructureBuildGeometryInfoKHR> infos = new ArrayList<>();
            List<VkAccelerationStructureBuildRangeInfoKHR.Buffer> ranges = new ArrayList<>();
            List<Long> scratchOffsets = new ArrayList<>();
            long scratchUsed = 0;
            int count = 0;
            while (!pendingBuilds.isEmpty() && count < BUILDS_PER_FRAME) {
                Section s = pendingBuilds.get(0);
                BlasRequest opaque = describe(stack, s, false);
                BlasRequest translucent = describe(stack, s, true);
                long opaqueScratch = opaque == null ? 0 : align(opaque.sizes.buildScratchSize(), ctx.scratchAlignment);
                long translucentScratch = translucent == null ? 0 : align(translucent.sizes.buildScratchSize(), ctx.scratchAlignment);
                if (count > 0 && scratchUsed + opaqueScratch + translucentScratch > SCRATCH_BUDGET) {
                    break;
                }
                pendingBuilds.remove(0);
                if (opaque != null) {
                    RtBuffer storage = RtBuffer.create(ctx, opaque.sizes.accelerationStructureSize(),
                            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, 0);
                    s.blasOpaque = createAs(stack, storage, opaque.sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                    s.blasOpaqueBuffer = storage;
                    s.blasOpaqueAddress = ctx.asDeviceAddress(s.blasOpaque);
                    opaque.info.dstAccelerationStructure(s.blasOpaque);
                    infos.add(opaque.info);
                    ranges.add(opaque.ranges);
                    scratchOffsets.add(scratchUsed);
                }
                if (translucent != null) {
                    RtBuffer storage = RtBuffer.create(ctx, translucent.sizes.accelerationStructureSize(),
                            VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, 0);
                    s.blasTranslucent = createAs(stack, storage, translucent.sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                    s.blasTranslucentBuffer = storage;
                    s.blasTranslucentAddress = ctx.asDeviceAddress(s.blasTranslucent);
                    translucent.info.dstAccelerationStructure(s.blasTranslucent);
                    infos.add(translucent.info);
                    ranges.add(translucent.ranges);
                    scratchOffsets.add(scratchUsed + opaqueScratch);
                }
                scratchUsed += opaqueScratch + translucentScratch;
                s.built = true;
                count++;
            }
            if (infos.isEmpty()) {
                return;
            }
            ensureScratch(scratchUsed);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer infoBuffer = VkAccelerationStructureBuildGeometryInfoKHR.calloc(infos.size(), stack);
            PointerBuffer rangePointers = stack.mallocPointer(infos.size());
            for (int i = 0; i < infos.size(); i++) {
                VkAccelerationStructureBuildGeometryInfoKHR info = infos.get(i);
                info.scratchData().deviceAddress(scratch.address + scratchOffsets.get(i));
                MemoryUtil.memCopy(info.address(), infoBuffer.get(i).address(), VkAccelerationStructureBuildGeometryInfoKHR.SIZEOF);
                rangePointers.put(i, ranges.get(i).address());
            }
            vkCmdBuildAccelerationStructuresKHR(cb, infoBuffer, rangePointers);
        }
    }

    private void buildEntityBlas(VkCommandBuffer cb) {
        int quads = entityVertices / 4;
        entityActive = false;
        if (quads == 0) {
            return;
        }
        long bytes = (long) quads * 4 * ENTITY_VERTEX_SIZE;
        int slot = frame % RING;
        if (entityRing[slot] == null || entityRingCapacity < bytes) {
            long capacity = Math.max(bytes, Math.max(1L << 20, entityRingCapacity * 2));
            for (int i = 0; i < RING; i++) {
                if (entityRing[i] != null) {
                    entityRing[i].freeLater();
                }
                entityRing[i] = RtBuffer.create(ctx, capacity,
                        VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR | VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, 0);
            }
            entityRingCapacity = capacity;
        }
        RtBuffer vertices = entityRing[slot];
        MemoryUtil.memCopy(entityData, vertices.mapped, bytes);
        vertices.flush(0, bytes);

        try (MemoryStack stack = buildStack.push()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.sType$Default().geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
            VkAccelerationStructureGeometryTrianglesDataKHR tri = geometry.geometry().triangles();
            tri.sType$Default()
                    .vertexFormat(VK12.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(ENTITY_VERTEX_SIZE)
                    .maxVertex(quads * 4 - 1)
                    .indexType(VK12.VK_INDEX_TYPE_UINT32);
            tri.vertexData().deviceAddress(vertices.address);
            tri.indexData().deviceAddress(quadIndices.address);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry);
            info.geometryCount(1);
            if (entityBlas == 0L || entityBlasQuads < quads) {
                int capacityQuads = Math.min(MAX_QUADS, Math.max(quads, Math.max(4096, entityBlasQuads * 2)));
                VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0),
                        stack.ints(capacityQuads * 2), sizes);
                destroyAs(entityBlas, entityBlasBuffer);
                if (entityScratch != null) {
                    entityScratch.freeLater();
                }
                entityBlasBuffer = RtBuffer.create(ctx, sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, 0);
                entityBlas = createAs(stack, entityBlasBuffer, sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
                entityBlasAddress = ctx.asDeviceAddress(entityBlas);
                entityScratch = RtBuffer.create(ctx, sizes.buildScratchSize() + ctx.scratchAlignment, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                        ctx.scratchAlignment);
                entityBlasQuads = capacityQuads;
            }
            info.dstAccelerationStructure(entityBlas);
            info.scratchData().deviceAddress(entityScratch.address);
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack).primitiveCount(quads * 2);
            vkCmdBuildAccelerationStructuresKHR(cb, info, stack.pointers(range.address()));
        }
        entityActive = true;
        entityVertexAddress = vertices.address;
    }

    private record BlasRequest(VkAccelerationStructureBuildGeometryInfoKHR info,
            VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges, VkAccelerationStructureBuildSizesInfoKHR sizes) {
    }

    /** Geometry description for the opaque (solid + cutout) or translucent BLAS of a section. */
    private BlasRequest describe(MemoryStack stack, Section s, boolean translucent) {
        int[] layers = translucent
                ? new int[] {ChunkSectionLayer.TRANSLUCENT.ordinal()}
                : new int[] {ChunkSectionLayer.SOLID.ordinal(), ChunkSectionLayer.CUTOUT.ordinal()};
        int n = 0;
        for (int layer : layers) {
            if (s.vertices[layer] != null) {
                n++;
            }
        }
        if (n == 0) {
            return null;
        }
        VkAccelerationStructureGeometryKHR.Buffer geometries = VkAccelerationStructureGeometryKHR.calloc(n, stack);
        VkAccelerationStructureBuildRangeInfoKHR.Buffer ranges = VkAccelerationStructureBuildRangeInfoKHR.calloc(n, stack);
        var maxPrimitives = stack.mallocInt(n);
        int g = 0;
        int flags = 0;
        long addr0 = 0, addr1 = 0;
        for (int layer : layers) {
            RtBuffer vertices = s.vertices[layer];
            if (vertices == null) {
                continue;
            }
            boolean cutout = layer == ChunkSectionLayer.CUTOUT.ordinal();
            VkAccelerationStructureGeometryKHR geometry = geometries.get(g);
            geometry.sType$Default()
                    .geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR)
                    .flags(layer == ChunkSectionLayer.SOLID.ordinal() ? VK_GEOMETRY_OPAQUE_BIT_KHR : VK_GEOMETRY_NO_DUPLICATE_ANY_HIT_INVOCATION_BIT_KHR);
            VkAccelerationStructureGeometryTrianglesDataKHR tri = geometry.geometry().triangles();
            tri.sType$Default()
                    .vertexFormat(VK12.VK_FORMAT_R32G32B32_SFLOAT)
                    .vertexStride(VERTEX_SIZE)
                    .maxVertex(s.quads[layer] * 4 - 1)
                    .indexType(VK12.VK_INDEX_TYPE_UINT32);
            tri.vertexData().deviceAddress(vertices.address);
            tri.indexData().deviceAddress(quadIndices.address);
            ranges.get(g).primitiveCount(s.quads[layer] * 2).primitiveOffset(0).firstVertex(0).transformOffset(0);
            maxPrimitives.put(g, s.quads[layer] * 2);
            if (cutout) {
                flags |= 1 << g;
            }
            if (g == 0) {
                addr0 = vertices.address;
            } else {
                addr1 = vertices.address;
            }
            g++;
        }
        if (!translucent) {
            s.opaqueFlags = flags;
            s.opaqueAddr0 = addr0;
            s.opaqueAddr1 = addr1;
        }
        VkAccelerationStructureBuildGeometryInfoKHR info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default()
                .type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR)
                .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR)
                .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                .pGeometries(geometries);
        info.geometryCount(n);
        VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
        vkGetAccelerationStructureBuildSizesKHR(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info, maxPrimitives, sizes);
        return new BlasRequest(info, ranges, sizes);
    }

    private void buildTlas(VkCommandBuffer cb, Vec3 camera, int cx, int cy, int cz, int radius) {
        int slot = frame % RING;
        int wanted = 0;
        for (Section s : sections.values()) {
            if (s.built) {
                wanted += 2;
            }
        }
        wanted += 1;
        ensureInstanceCapacity(Math.max(64, wanted));
        RtBuffer instances = instanceRing[slot];
        RtBuffer table = tableRing[slot];
        long ip = instances.mapped;
        long tp = table.mapped;
        int n = 0;
        for (Section s : sections.values()) {
            if (!s.built) {
                continue;
            }
            if (Math.abs(s.x - cx) > radius || Math.abs(s.y - cy) > radius || Math.abs(s.z - cz) > radius) {
                continue;
            }
            float tx = (float) (SectionPos.sectionToBlockCoord(s.x) - camera.x);
            float ty = (float) (SectionPos.sectionToBlockCoord(s.y) - camera.y);
            float tz = (float) (SectionPos.sectionToBlockCoord(s.z) - camera.z);
            if (s.blasOpaque != 0L) {
                writeInstance(ip + (long) n * INSTANCE_SIZE, tx, ty, tz, n, MASK_OPAQUE, s.blasOpaqueAddress);
                writeEntry(tp + (long) n * ENTRY_SIZE, s.opaqueAddr0, s.opaqueAddr1, s.opaqueFlags);
                n++;
            }
            if (s.blasTranslucent != 0L) {
                RtBuffer v = s.vertices[ChunkSectionLayer.TRANSLUCENT.ordinal()];
                writeInstance(ip + (long) n * INSTANCE_SIZE, tx, ty, tz, n, MASK_TRANSLUCENT, s.blasTranslucentAddress);
                writeEntry(tp + (long) n * ENTRY_SIZE, v.address, 0L, FLAG_TRANSLUCENT);
                n++;
            }
        }
        if (entityActive) {
            // Entity vertices are already relative to this frame's camera.
            writeInstance(ip + (long) n * INSTANCE_SIZE, 0.0f, 0.0f, 0.0f, n, MASK_ENTITY, entityBlasAddress);
            writeEntry(tp + (long) n * ENTRY_SIZE, entityVertexAddress, 0L, FLAG_ENTITY);
            n++;
        }
        instances.flush(0, (long) Math.max(n, 1) * INSTANCE_SIZE);
        table.flush(0, (long) Math.max(n, 1) * ENTRY_SIZE);
        instanceCount = n;
        int primitives = n;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAccelerationStructureGeometryKHR.calloc(1, stack);
            geometry.sType$Default().geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
            geometry.geometry().instances().sType$Default().arrayOfPointers(false).data().deviceAddress(instances.address);
            VkAccelerationStructureBuildGeometryInfoKHR.Buffer info = VkAccelerationStructureBuildGeometryInfoKHR.calloc(1, stack).sType$Default()
                    .type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR)
                    .flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_BUILD_BIT_KHR)
                    .mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR)
                    .pGeometries(geometry);
            info.geometryCount(1);
            if (tlas == 0L || tlasCapacity < instanceCapacity) {
                VkAccelerationStructureBuildSizesInfoKHR sizes = VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
                vkGetAccelerationStructureBuildSizesKHR(ctx.vk, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, info.get(0),
                        stack.ints(instanceCapacity), sizes);
                freeTlas();
                tlasBuffer = RtBuffer.create(ctx, sizes.accelerationStructureSize(), VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_STORAGE_BIT_KHR, false, 0);
                tlas = createAs(stack, tlasBuffer, sizes.accelerationStructureSize(), VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
                tlasScratch = RtBuffer.create(ctx, sizes.buildScratchSize() + ctx.scratchAlignment, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false,
                        ctx.scratchAlignment);
                tlasCapacity = instanceCapacity;
            }
            info.dstAccelerationStructure(tlas);
            info.scratchData().deviceAddress(tlasScratch.address);
            // Zero instances is a valid, empty scene: every ray misses.
            VkAccelerationStructureBuildRangeInfoKHR.Buffer range = VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack)
                    .primitiveCount(primitives);
            vkCmdBuildAccelerationStructuresKHR(cb, info, stack.pointers(range.address()));
        }
    }

    private static void writeInstance(long p, float tx, float ty, float tz, int customIndex, int mask, long blasAddress) {
        // VkTransformMatrixKHR: 3x4 row-major, rotation is identity.
        MemoryUtil.memPutFloat(p, 1.0f);
        MemoryUtil.memPutFloat(p + 4, 0.0f);
        MemoryUtil.memPutFloat(p + 8, 0.0f);
        MemoryUtil.memPutFloat(p + 12, tx);
        MemoryUtil.memPutFloat(p + 16, 0.0f);
        MemoryUtil.memPutFloat(p + 20, 1.0f);
        MemoryUtil.memPutFloat(p + 24, 0.0f);
        MemoryUtil.memPutFloat(p + 28, ty);
        MemoryUtil.memPutFloat(p + 32, 0.0f);
        MemoryUtil.memPutFloat(p + 36, 0.0f);
        MemoryUtil.memPutFloat(p + 40, 1.0f);
        MemoryUtil.memPutFloat(p + 44, tz);
        MemoryUtil.memPutInt(p + 48, (customIndex & 0xFFFFFF) | (mask << 24));
        MemoryUtil.memPutInt(p + 52, VK_GEOMETRY_INSTANCE_TRIANGLE_FACING_CULL_DISABLE_BIT_KHR << 24);
        MemoryUtil.memPutLong(p + 56, blasAddress);
    }

    private static void writeEntry(long p, long addr0, long addr1, int flags) {
        MemoryUtil.memPutLong(p, addr0);
        MemoryUtil.memPutLong(p + 8, addr1);
        MemoryUtil.memPutInt(p + 16, flags);
        MemoryUtil.memPutInt(p + 20, 0);
        MemoryUtil.memPutLong(p + 24, 0L);
    }

    private long createAs(MemoryStack stack, RtBuffer storage, long size, int type) {
        LongBuffer handle = stack.mallocLong(1);
        RtContext.check(vkCreateAccelerationStructureKHR(ctx.vk, VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default()
                .buffer(storage.buffer).offset(storage.offset).size(size).type(type), null, handle), "vkCreateAccelerationStructureKHR");
        return handle.get(0);
    }

    private void ensureScratch(long size) {
        if (scratch != null && scratch.size >= size) {
            return;
        }
        if (scratch != null) {
            scratch.freeLater();
        }
        scratch = RtBuffer.create(ctx, Math.max(size, 4L << 20), VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, false, ctx.scratchAlignment);
    }

    private void ensureInstanceCapacity(int count) {
        if (instanceRing[0] != null && instanceCapacity >= count) {
            return;
        }
        int capacity = Math.max(count, instanceCapacity * 2);
        for (int i = 0; i < RING; i++) {
            if (instanceRing[i] != null) {
                instanceRing[i].freeLater();
                tableRing[i].freeLater();
            }
            instanceRing[i] = RtBuffer.create(ctx, (long) capacity * INSTANCE_SIZE,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR, true, 0);
            tableRing[i] = RtBuffer.create(ctx, (long) capacity * ENTRY_SIZE, VK12.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true, 0);
        }
        instanceCapacity = capacity;
    }

    private static long align(long value, long alignment) {
        return (value + alignment - 1) / alignment * alignment;
    }

    private void free(Section s) {
        for (int i = 0; i < 3; i++) {
            if (s.vertices[i] != null) {
                s.vertices[i].freeLater();
                s.vertices[i] = null;
            }
        }
        destroyAs(s.blasOpaque, s.blasOpaqueBuffer);
        destroyAs(s.blasTranslucent, s.blasTranslucentBuffer);
        s.blasOpaque = 0L;
        s.blasTranslucent = 0L;
        s.built = false;
    }

    private void destroyAs(long as, RtBuffer buffer) {
        if (as != 0L) {
            var vk = ctx.vk;
            ctx.destroyLater(() -> vkDestroyAccelerationStructureKHR(vk, as, null));
        }
        if (buffer != null) {
            buffer.freeLater();
        }
    }

    private void freeTlas() {
        destroyAs(tlas, tlasBuffer);
        if (tlasScratch != null) {
            tlasScratch.freeLater();
        }
        tlas = 0L;
        tlasBuffer = null;
        tlasScratch = null;
        tlasCapacity = 0;
    }

    /** Drops all geometry, e.g. on world or dimension change. */
    public void clear() {
        for (Section s : sections.values()) {
            free(s);
        }
        sections.clear();
        pendingBuilds.clear();
        requested.clear();
        Captured c;
        while ((c = CAPTURED.poll()) != null) {
            for (CapturedLayer layer : c.layers()) {
                MemoryUtil.nmemFree(layer.data());
            }
        }
    }

    public static void stopCapturing() {
        capturing = false;
    }

    public void close() {
        stopCapturing();
        clear();
        freeTlas();
        destroyAs(entityBlas, entityBlasBuffer);
        entityBlas = 0L;
        if (entityScratch != null) {
            entityScratch.freeLater();
        }
        for (int i = 0; i < RING; i++) {
            if (entityRing[i] != null) {
                entityRing[i].freeLater();
            }
        }
        if (scratch != null) {
            scratch.freeLater();
        }
        for (int i = 0; i < RING; i++) {
            if (instanceRing[i] != null) {
                instanceRing[i].freeLater();
                tableRing[i].freeLater();
            }
        }
        quadIndices.freeLater();
        LOGGER.info("PocoRTX: ray tracing scene released");
    }
}
