package dev.pocortx.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.util.Mth;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtxConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("pocortx.json");
    private static final int VERSION = 2;

    private static RtxConfig instance = new RtxConfig();

    /** Only hardware ray tracing is offered; screen-space tracing was removed. */
    public enum Mode {
        HARDWARE
    }

    public enum Quality {
        // scale, AO rays, GI rays, volumetric steps, shadow rays at GI hits, shadow rays at reflection hits, diffuse atlas LOD
        LOW(0.4f, 1, 1, 3, false, false, 3),
        MEDIUM(0.5f, 2, 1, 6, false, true, 3),
        HIGH(0.75f, 2, 2, 8, true, true, 2),
        ULTRA(1.0f, 4, 2, 12, true, true, 2);

        public final float renderScale;
        public final int aoRays;
        public final int giRays;
        public final int volumetricSteps;
        public final boolean giShadowRays;
        public final boolean reflectionShadowRays;
        public final int diffuseLod;

        Quality(float renderScale, int aoRays, int giRays, int volumetricSteps, boolean giShadowRays, boolean reflectionShadowRays, int diffuseLod) {
            this.renderScale = renderScale;
            this.aoRays = aoRays;
            this.giRays = giRays;
            this.volumetricSteps = volumetricSteps;
            this.giShadowRays = giShadowRays;
            this.reflectionShadowRays = reflectionShadowRays;
            this.diffuseLod = diffuseLod;
        }

        public Quality next() {
            Quality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum DebugView {
        OFF, AO, SHADOWS, GI, REFLECTIONS, VOLUMETRIC;

        public DebugView next() {
            DebugView[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    /** BSL-style look: colour grading, bloom and friends, written from scratch (no BSL code). */
    public static final class ShaderSettings {
        public boolean enabled = true;
        public boolean tonemap = true;
        public float exposure = 1.0f;
        public float saturation = 1.12f;
        public float vibrance = 0.15f;
        public float contrast = 1.05f;
        public float temperature = 0.12f;
        public boolean bloom = true;
        public float bloomStrength = 0.35f;
        public float vignette = 0.3f;
        public float sharpen = 0.25f;

        void sanitize() {
            exposure = Mth.clamp(exposure, 0.25f, 3.0f);
            saturation = Mth.clamp(saturation, 0.0f, 2.0f);
            vibrance = Mth.clamp(vibrance, -1.0f, 1.0f);
            contrast = Mth.clamp(contrast, 0.5f, 1.5f);
            temperature = Mth.clamp(temperature, -1.0f, 1.0f);
            bloomStrength = Mth.clamp(bloomStrength, 0.0f, 1.0f);
            vignette = Mth.clamp(vignette, 0.0f, 1.0f);
            sharpen = Mth.clamp(sharpen, 0.0f, 1.0f);
        }
    }

    // Stays 0 for files written by older versions (Gson keeps the field initializer when the key is missing).
    public int version;
    public boolean enabled = true;
    public Mode mode = Mode.HARDWARE;
    // MEDIUM traces at half resolution; tuned for Immortalis-G925 class GPUs (POCO X8 Pro Max).
    public Quality quality = Quality.MEDIUM;
    /** Ray traced radius in chunk sections (16 blocks) around the camera. */
    public int rtDistance = 4;
    public boolean shadows = true;
    public boolean ambientOcclusion = true;
    public boolean globalIllumination = true;
    public boolean reflections = true;
    public boolean specular = true;
    public boolean volumetricLight = true;
    public boolean rainPuddles = true;
    public boolean waterWaves = true;
    public boolean temporalDenoise = true;
    public boolean alphaTestedShadows = true;
    public float aoStrength = 0.8f;
    public float shadowStrength = 0.5f;
    public float giStrength = 1.0f;
    public float reflectionStrength = 1.0f;
    public float specularStrength = 1.0f;
    public float volumetricStrength = 0.6f;
    public float emissiveStrength = 1.0f;
    public DebugView debugView = DebugView.OFF;
    public ShaderSettings shader = new ShaderSettings();

    public static RtxConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                RtxConfig loaded = GSON.fromJson(reader, RtxConfig.class);
                if (loaded != null && loaded.version == VERSION) {
                    instance = loaded;
                } else {
                    LOGGER.info("PocoRTX: config from an older version, starting from defaults");
                    instance = new RtxConfig();
                }
            } catch (IOException | JsonParseException e) {
                LOGGER.warn("Could not read {}, using defaults", PATH, e);
                instance = new RtxConfig();
            }
        }
        instance.sanitize();
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(PATH)) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            LOGGER.warn("Could not write {}", PATH, e);
        }
    }

    public static void reset() {
        instance = new RtxConfig();
        instance.sanitize();
        save();
    }

    public static void resetShader() {
        instance.shader = new ShaderSettings();
        save();
    }

    private void sanitize() {
        version = VERSION;
        if (mode == null) {
            mode = Mode.HARDWARE;
        }
        if (quality == null) {
            quality = Quality.MEDIUM;
        }
        if (debugView == null) {
            debugView = DebugView.OFF;
        }
        if (shader == null) {
            shader = new ShaderSettings();
        }
        shader.sanitize();
        rtDistance = Mth.clamp(rtDistance, 2, 8);
        aoStrength = Mth.clamp(aoStrength, 0.0f, 1.0f);
        shadowStrength = Mth.clamp(shadowStrength, 0.0f, 1.0f);
        giStrength = Mth.clamp(giStrength, 0.0f, 2.0f);
        reflectionStrength = Mth.clamp(reflectionStrength, 0.0f, 1.0f);
        specularStrength = Mth.clamp(specularStrength, 0.0f, 2.0f);
        volumetricStrength = Mth.clamp(volumetricStrength, 0.0f, 1.0f);
        emissiveStrength = Mth.clamp(emissiveStrength, 0.0f, 2.0f);
    }
}
