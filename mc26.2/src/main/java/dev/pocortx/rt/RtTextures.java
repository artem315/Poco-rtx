package dev.pocortx.rt;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

/** Minecraft textures that compute shaders can also write to (storage images). */
public final class RtTextures {
    private static final ThreadLocal<Boolean> STORAGE = ThreadLocal.withInitial(() -> false);

    private RtTextures() {
    }

    public static boolean storageRequested() {
        return STORAGE.get();
    }

    public static GpuTexture createStorage(String label, GpuFormat format, int width, int height) {
        STORAGE.set(true);
        try {
            return RenderSystem.getDevice().createTexture(label,
                    GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
                    format, width, height, 1, 1);
        } finally {
            STORAGE.set(false);
        }
    }

    /** A storage texture plus its full view, recreated when the size changes. */
    public static final class Image implements AutoCloseable {
        private final String label;
        private final GpuFormat format;
        private GpuTexture texture;
        private GpuTextureView view;

        public Image(String label, GpuFormat format) {
            this.label = label;
            this.format = format;
        }

        public void ensure(int width, int height) {
            if (texture != null && texture.getWidth(0) == width && texture.getHeight(0) == height) {
                return;
            }
            close();
            texture = createStorage(label, format, width, height);
            view = RenderSystem.getDevice().createTextureView(texture);
        }

        public GpuTexture texture() {
            return texture;
        }

        public GpuTextureView view() {
            return view;
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
        }
    }
}
