package dev.pocortx.autotest;

import dev.pocortx.config.RtxConfig;
import dev.pocortx.render.RtxRenderer;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.server.IntegratedServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds a small test scene in a flat world and saves screenshots with the
 * effects on, off and in each debug view. Only active with -Dpocortx.autotest=true.
 */
public final class AutotestClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("PocoRTX-Autotest");

    private record Step(int waitTicks, String name, Consumer<Minecraft> action) {
    }

    private final List<Step> steps = new ArrayList<>();
    private int index = -1;
    private int countdown;
    private boolean started;

    @Override
    public void onInitializeClient() {
        if (!Boolean.getBoolean("pocortx.autotest")) {
            return;
        }
        buildPlan();
        // -Dpocortx.autotest.only=steps_,day_ keeps only the shots with these name prefixes.
        String only = System.getProperty("pocortx.autotest.only", "");
        if (!only.isEmpty()) {
            List<String> prefixes = List.of(only.split(","));
            steps.removeIf(step -> step.name() != null && prefixes.stream().noneMatch(step.name()::startsWith)
                    && !step.name().startsWith("ui_"));
        }
        ClientTickEvents.END_CLIENT_TICK.register(this::tick);
    }

    private void buildPlan() {
        String dayView = "tp @a 0 -57 -14 0 25";
        String sunsetView = "tp @a 14 -58 3 90 -3";
        String waterView = "tp @a 0 -58 -3 0 35";

        steps.add(new Step(160, null, c -> {
            if (!c.gui.hud.isHidden()) {
                c.gui.hud.toggle();
            }
            c.options.renderDistance().set(8);
            if (Boolean.getBoolean("pocortx.autotest.oit")) {
                c.options.improvedTransparency().set(true);
            }
            run(c, "gamerule doDaylightCycle false");
            run(c, "gamerule doWeatherCycle false");
            run(c, "gamerule doMobSpawning false");
            run(c, "gamemode spectator @a");
            run(c, "weather clear");
            run(c, "time set 2500");
            run(c, "fill -24 -60 -24 24 -40 24 air");
            run(c, "fill -7 -62 2 7 -62 12 stone");
            run(c, "fill -7 -61 2 7 -61 12 water");
            run(c, "fill -4 -60 15 -2 -52 17 stone_bricks");
            run(c, "fill 2 -60 15 4 -52 17 oak_planks");
            run(c, "fill -12 -60 -2 -12 -56 -2 cobblestone");
            run(c, "fill -12 -60 4 -12 -56 4 cobblestone");
            run(c, "fill -14 -55 -4 -8 -55 6 stone");
            run(c, "fill 9 -60 -2 9 -56 6 light_blue_stained_glass");
            run(c, "place feature minecraft:oak 12 -60 10");
            run(c, "place feature minecraft:birch -10 -60 12");
            run(c, "place feature minecraft:oak -18 -60 0");
            run(c, "summon minecraft:cow 3 -60 -6 {NoAI:1b,Rotation:[30f,0f]}");
            run(c, dayView);
            setRtx(true, RtxConfig.DebugView.OFF);
        }));

        steps.add(new Step(30, "day_on", c -> setRtx(true, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "day_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "day_debug_ao", c -> setRtx(true, RtxConfig.DebugView.AO)));
        steps.add(new Step(30, "day_debug_shadows", c -> setRtx(true, RtxConfig.DebugView.SHADOWS)));
        steps.add(new Step(30, "day_debug_reflections", c -> setRtx(true, RtxConfig.DebugView.REFLECTIONS)));
        steps.add(new Step(30, "day_debug_gi", c -> setRtx(true, RtxConfig.DebugView.GI)));
        steps.add(new Step(30, "day_fx_only", c -> {
            setRtx(false, RtxConfig.DebugView.OFF);
            RtxConfig.get().shader.enabled = true;
        }));

        steps.add(new Step(30, "water_on", c -> {
            run(c, waterView);
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(30, "water_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "water_debug_reflections", c -> setRtx(true, RtxConfig.DebugView.REFLECTIONS)));
        steps.add(new Step(30, "water_debug_lighting", c -> setRtx(true, RtxConfig.DebugView.LIGHTING)));

        steps.add(new Step(30, "sunset_on", c -> {
            run(c, "time set 11900");
            run(c, sunsetView);
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(30, "sunset_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "sunset_debug_shafts", c -> setRtx(true, RtxConfig.DebugView.VOLUMETRIC)));

        steps.add(new Step(200, "rain_on", c -> {
            run(c, "time set 6000");
            run(c, "weather rain");
            run(c, dayView);
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(30, "rain_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "rain_debug_reflections", c -> setRtx(true, RtxConfig.DebugView.REFLECTIONS)));

        steps.add(new Step(40, "night_on", c -> {
            run(c, "weather clear");
            run(c, "time set 18000");
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        // Night shoreline like the one reported from the phone: blocks half in water, moonlight.
        steps.add(new Step(60, "shore_night_on", c -> {
            run(c, "weather clear");
            run(c, "time set 18000");
            run(c, "fill -6 -61 4 2 -60 4 andesite");
            run(c, "fill -6 -60 3 2 -60 3 smooth_stone_slab");
            run(c, "fill 3 -61 4 5 -60 4 diorite");
            run(c, "tp @a -1 -59.4 11 180 18");
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(30, "shore_night_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "shore_night_debug_reflections", c -> setRtx(true, RtxConfig.DebugView.REFLECTIONS)));
        steps.add(new Step(30, "shore_night_debug_gi", c -> setRtx(true, RtxConfig.DebugView.GI)));
        steps.add(new Step(30, "shore_night_debug_shadows", c -> setRtx(true, RtxConfig.DebugView.SHADOWS)));
        steps.add(new Step(40, "shore_day_on", c -> {
            run(c, "time set 4000");
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(30, "shore_day_debug_reflections", c -> setRtx(true, RtxConfig.DebugView.REFLECTIONS)));
        steps.add(new Step(30, "shore_day_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "shore_day_debug_lighting", c -> setRtx(true, RtxConfig.DebugView.LIGHTING)));
        steps.add(new Step(30, "shore_day_debug_ao", c -> setRtx(true, RtxConfig.DebugView.AO)));
        steps.add(new Step(30, "shore_day_debug_gi", c -> setRtx(true, RtxConfig.DebugView.GI)));
        steps.add(new Step(30, "shore_day_debug_shadows", c -> setRtx(true, RtxConfig.DebugView.SHADOWS)));

        // The reported scene in detail: two blocks on a slab ledge at the waterline, a sandy
        // shore stepping down under the water, seen from above the water at night.
        String stepsView = "tp @a 33.5 -57.6 1.5 -20 28";
        steps.add(new Step(80, "steps_night_on", c -> {
            run(c, "fill 26 -64 -6 48 -50 18 air");
            run(c, "fill 26 -64 -6 48 -64 18 stone");
            run(c, "fill 26 -63 -6 48 -61 18 water");
            run(c, "fill 26 -63 7 48 -62 18 sandstone");
            run(c, "fill 26 -61 7 48 -61 18 sand");
            run(c, "fill 29 -63 6 39 -62 6 sandstone");
            run(c, "fill 31 -63 5 37 -63 5 sandstone");
            run(c, "fill 33 -63 4 35 -63 4 stone");
            run(c, "fill 30 -61 6 38 -61 6 smooth_stone");
            run(c, "fill 30 -60 6 37 -60 6 smooth_stone_slab");
            run(c, "fill 34 -60 7 35 -60 7 andesite");
            run(c, "fill 38 -60 8 41 -60 8 stone");
            run(c, "time set 18000");
            run(c, "weather clear");
            run(c, stepsView);
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(30, "steps_night_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "steps_night_debug_lighting", c -> setRtx(true, RtxConfig.DebugView.LIGHTING)));
        steps.add(new Step(30, "steps_night_debug_shadows", c -> setRtx(true, RtxConfig.DebugView.SHADOWS)));
        steps.add(new Step(30, "steps_night_debug_reflections", c -> setRtx(true, RtxConfig.DebugView.REFLECTIONS)));
        steps.add(new Step(30, "steps_night_debug_ao", c -> setRtx(true, RtxConfig.DebugView.AO)));
        steps.add(new Step(30, "steps_night_debug_gi", c -> setRtx(true, RtxConfig.DebugView.GI)));
        steps.add(new Step(40, "steps_moonrise_on", c -> {
            run(c, "time set 14000");
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(40, "steps_day_on", c -> {
            run(c, "time set 3000");
            setRtx(true, RtxConfig.DebugView.OFF);
        }));
        steps.add(new Step(30, "steps_day_off", c -> setRtx(false, RtxConfig.DebugView.OFF)));
        steps.add(new Step(30, "steps_day_debug_lighting", c -> setRtx(true, RtxConfig.DebugView.LIGHTING)));
        steps.add(new Step(30, "steps_day_debug_gi", c -> setRtx(true, RtxConfig.DebugView.GI)));

        // The new screens: Video Settings with the two buttons, then RTX and shader settings.
        steps.add(new Step(20, "ui_video", c -> c.gui.setScreen(
                new net.minecraft.client.gui.screens.options.VideoSettingsScreen(null, c, c.options))));
        steps.add(new Step(20, "ui_rtx", c -> c.gui.setScreen(new dev.pocortx.gui.RtxSettingsScreen(null))));
        steps.add(new Step(20, "ui_shader", c -> c.gui.setScreen(new dev.pocortx.gui.ShaderSettingsScreen(null))));
        steps.add(new Step(1000, null, c -> {
            c.gui.setScreen(null);
            setRtx(true, RtxConfig.DebugView.OFF);
            LOGGER.info("AUTOTEST DONE failed={} reason={} oit={} backend={}", RtxRenderer.INSTANCE.hasFailed(), RtxRenderer.INSTANCE.failureReason(),
                    c.gameRenderer.useImprovedTransparency(), com.mojang.blaze3d.systems.RenderSystem.getDevice().getDeviceInfo().backendName());
            c.stop();
        }));
    }

    private void tick(Minecraft client) {
        if (client.level == null || client.player == null || client.getSingleplayerServer() == null) {
            return;
        }
        if (!started) {
            started = true;
            index = 0;
            steps.get(0).action().accept(client);
            countdown = steps.get(0).waitTicks();
            return;
        }
        if (index >= steps.size() || --countdown > 0) {
            return;
        }
        // Each step applies its state, waits for it to render, then is captured.
        Step done = steps.get(index);
        if (done.name() != null) {
            String file = done.name() + ".png";
            LOGGER.info("AUTOTEST shot {} fps={} failed={} rt={} sections={} instances={}", file, client.getFps(), RtxRenderer.INSTANCE.hasFailed(),
                    RtxRenderer.INSTANCE.hardwareRtActive(), RtxRenderer.INSTANCE.traceSections(), RtxRenderer.INSTANCE.traceInstances());
            Screenshot.grab(client.gameDirectory, file, client.gameRenderer.mainRenderTarget(), 1, msg -> { });
            var lm = client.gameRenderer.gameRenderState().lightmapRenderState;
            LOGGER.info("AUTOTEST lightmap sky={} {} block={} tint={} ambient={} brightness={} darkness={}", lm.skyFactor, lm.skyLightColor,
                    lm.blockFactor, lm.blockLightTint, lm.ambientColor, lm.brightness, lm.darknessEffectScale);
        }
        index++;
        if (index < steps.size()) {
            steps.get(index).action().accept(client);
            countdown = steps.get(index).waitTicks();
        }
    }

    /** "Off" shots are plain vanilla: ray tracing and the shader effects both off. */
    private static void setRtx(boolean enabled, RtxConfig.DebugView view) {
        RtxConfig config = RtxConfig.get();
        config.enabled = enabled;
        config.shader.enabled = enabled;
        config.debugView = view;
    }

    private static void run(Minecraft client, String command) {
        IntegratedServer server = client.getSingleplayerServer();
        server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command));
    }
}
