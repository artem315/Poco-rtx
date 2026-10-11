package dev.pocortx.rt;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.data.AtlasIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-sprite surface properties for the block atlas, looked up by atlas UV in the shaders.
 * One texel covers 4x4 atlas pixels: R roughness, G reflectance (F0; 1 = metal),
 * B emission, A material id (see rt_common.glsl).
 */
public final class RtMaterials implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    private static final int CELL = 4;

    private GpuTexture texture;
    private GpuTextureView view;
    private Object builtFor;
    private int atlasWidth;
    private int atlasHeight;

    public GpuTextureView view() {
        return view;
    }

    /** Rebuilds the table when the block atlas changed (resource reload). */
    public void ensure() {
        TextureAtlas atlas = Minecraft.getInstance().getAtlasManager().getAtlasOrThrow(AtlasIds.BLOCKS);
        GpuTexture atlasTexture = atlas.getTexture();
        if (builtFor == atlasTexture && view != null) {
            return;
        }
        close();
        atlasWidth = atlasTexture.getWidth(0);
        atlasHeight = atlasTexture.getHeight(0);
        int w = Math.max(1, atlasWidth / CELL);
        int h = Math.max(1, atlasHeight / CELL);
        try (NativeImage image = new NativeImage(NativeImage.Format.RGBA, w, h, false)) {
            int fallback = pack(0.9f, 0.04f, 0.0f, 0);
            image.fillRect(0, 0, w, h, fallback);
            int classified = 0;
            for (TextureAtlasSprite sprite : ((AtlasSpriteAccess) atlas).pocortx$sprites()) {
                String name = sprite.contents().name().getPath().toLowerCase(Locale.ROOT);
                int value = classify(name);
                if (value == fallback) {
                    continue;
                }
                classified++;
                int x0 = (int) Math.floor(sprite.getU0() * w);
                int x1 = (int) Math.ceil(sprite.getU1() * w);
                int y0 = (int) Math.floor(sprite.getV0() * h);
                int y1 = (int) Math.ceil(sprite.getV1() * h);
                for (int y = Math.max(0, y0); y < Math.min(h, y1); y++) {
                    for (int x = Math.max(0, x0); x < Math.min(w, x1); x++) {
                        image.setPixel(x, y, value);
                    }
                }
            }
            texture = RenderSystem.getDevice().createTexture("PocoRTX materials",
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
            RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image);
            view = RenderSystem.getDevice().createTextureView(texture);
            builtFor = atlasTexture;
            LOGGER.info("PocoRTX: material table {}x{} for {} special sprites", w, h, classified);
        }
    }

    /** ARGB as NativeImage.setPixel expects (it stores ABGR in memory, i.e. RGBA bytes). */
    private static int pack(float roughness, float f0, float emissive, int id) {
        int r = Math.round(roughness * 255.0f);
        int g = Math.round(f0 * 255.0f);
        int b = Math.round(emissive * 255.0f);
        return (id << 24) | (r << 16) | (g << 8) | b;
    }

    static int classify(String n) {
        if (n.contains("water")) {
            return pack(0.02f, 0.02f, 0.0f, 1);
        }
        if (n.contains("glass")) {
            return pack(0.0f, 0.04f, 0.0f, 2);
        }
        if (n.endsWith("ice") || n.contains("packed_ice") || n.contains("blue_ice") || n.contains("frosted_ice")) {
            return pack(0.04f, 0.04f, 0.0f, 3);
        }
        if (n.contains("lava") || n.contains("magma") || n.contains("glowstone") || n.contains("sea_lantern")
                || n.contains("shroomlight") || n.contains("froglight") || n.endsWith("_lamp_on") || n.contains("redstone_lamp_on")
                || n.contains("beacon") || n.contains("end_rod")) {
            return pack(0.7f, 0.04f, 1.0f, 0);
        }
        if (n.contains("torch") || n.contains("lantern") || n.contains("fire") || n.contains("campfire") || n.contains("jack_o_lantern")
                || n.contains("glow_lichen") || n.contains("candle") || n.contains("crying_obsidian") || n.contains("amethyst_cluster")
                || n.contains("respawn_anchor") || n.contains("furnace_front_on") || n.contains("smoker_front_on")) {
            return pack(0.6f, 0.04f, 1.0f, 6);
        }
        if (n.contains("iron_block") || n.contains("gold_block") || n.contains("netherite_block") || n.contains("copper")
                || n.contains("iron_bars") || n.contains("iron_door") || n.contains("iron_trapdoor") || n.contains("anvil")
                || n.contains("chain") || n.contains("cauldron") || n.contains("hopper") || n.contains("bell")
                || n.contains("lightning_rod") || n.contains("heavy_core")) {
            return pack(n.contains("oxidized") || n.contains("weathered") ? 0.55f : 0.25f, 1.0f, 0.0f, 4);
        }
        if (n.contains("diamond_block") || n.contains("emerald_block") || n.contains("amethyst_block") || n.contains("lapis_block")
                || n.contains("quartz") || n.contains("obsidian") || n.contains("prismarine")) {
            return pack(0.18f, 0.08f, 0.0f, 5);
        }
        if (n.contains("polished") || n.contains("smooth_") || n.contains("glazed_terracotta") || n.contains("_concrete")
                && !n.contains("powder") || n.contains("purpur") || n.contains("calcite")) {
            return pack(0.4f, 0.04f, 0.0f, 0);
        }
        return pack(0.9f, 0.04f, 0.0f, 0);
    }

    @Override
    public void close() {
        if (view != null) {
            view.close();
            view = null;
        }
        if (texture != null) {
            texture.close();
            texture = null;
        }
        builtFor = null;
    }

    /** Implemented by a mixin on TextureAtlas. */
    public interface AtlasSpriteAccess {
        Iterable<TextureAtlasSprite> pocortx$sprites();
    }
}
