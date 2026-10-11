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

    private static RtxConfig instance = new RtxConfig();

    public enum Quality {
        LOW(0.5f, 4, 8, 16, 12),
        MEDIUM(0.5f, 6, 12, 24, 16),
        HIGH(0.75f, 8, 16, 32, 24),
        ULTRA(1.0f, 12, 24, 48, 32);

        public final float renderScale;
        public final int aoSamples;
        public final int shadowSteps;
        public final int reflectionSteps;
        public final int lightShaftSteps;

        Quality(float renderScale, int aoSamples, int shadowSteps, int reflectionSteps, int lightShaftSteps) {
            this.renderScale = renderScale;
            this.aoSamples = aoSamples;
            this.shadowSteps = shadowSteps;
            this.reflectionSteps = reflectionSteps;
            this.lightShaftSteps = lightShaftSteps;
        }

        public Quality next() {
            Quality[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public enum DebugView {
        OFF, AO, SHADOWS, REFLECTIONS, LIGHT_SHAFTS;

        public DebugView next() {
            DebugView[] values = values();
            return values[(ordinal() + 1) % values.length];
        }
    }

    public boolean enabled = true;
    // MEDIUM traces at half resolution; tuned for Immortalis-G925 class GPUs
    // (POCO X8 Pro Max) running through a GL translation layer.
    public Quality quality = Quality.MEDIUM;
    public boolean ambientOcclusion = true;
    public boolean shadows = true;
    public boolean reflections = true;
    public boolean lightShafts = true;
    public boolean rainPuddles = true;
    public boolean waterWaves = true;
    public float aoStrength = 0.8f;
    public float shadowStrength = 0.45f;
    public float reflectionStrength = 1.0f;
    public float lightShaftStrength = 0.5f;
    public DebugView debugView = DebugView.OFF;

    public static RtxConfig get() {
        return instance;
    }

    public static void load() {
        if (Files.exists(PATH)) {
            try (Reader reader = Files.newBufferedReader(PATH)) {
                RtxConfig loaded = GSON.fromJson(reader, RtxConfig.class);
                if (loaded != null) {
                    instance = loaded;
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
        save();
    }

    private void sanitize() {
        if (quality == null) {
            quality = Quality.MEDIUM;
        }
        if (debugView == null) {
            debugView = DebugView.OFF;
        }
        aoStrength = Mth.clamp(aoStrength, 0.0f, 1.0f);
        shadowStrength = Mth.clamp(shadowStrength, 0.0f, 1.0f);
        reflectionStrength = Mth.clamp(reflectionStrength, 0.0f, 1.0f);
        lightShaftStrength = Mth.clamp(lightShaftStrength, 0.0f, 1.0f);
    }
}
