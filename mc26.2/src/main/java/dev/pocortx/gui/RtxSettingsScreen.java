package dev.pocortx.gui;

import dev.pocortx.config.RtxConfig;
import dev.pocortx.render.RtxRenderer;
import dev.pocortx.rt.RtDeviceSupport;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** "RTX settings": hardware ray tracing and every effect it drives. */
public final class RtxSettingsScreen extends OptionsSubScreen {
    public RtxSettingsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("pocortx.settings.title"));
    }

    @Override
    protected void addOptions() {
        RtxConfig c = RtxConfig.get();
        Button status = Button.builder(statusText(), b -> {
        }).width(310).build();
        status.active = false;
        this.list.addBig(status);
        this.list.addSmall(
                Options2.toggle("pocortx.option.enabled", c.enabled, v -> {
                    c.enabled = v;
                    if (v) {
                        RtxRenderer.INSTANCE.resetFailure();
                    }
                }),
                Options2.cycle("pocortx.option.mode", RtxConfig.Mode.values(), c.mode, m -> Options2.enumName("pocortx.mode.", m), v -> c.mode = v));
        this.list.addSmall(
                Options2.cycle("pocortx.option.quality", RtxConfig.Quality.values(), c.quality, q -> Options2.enumName("pocortx.quality.", q),
                        v -> c.quality = v),
                Options2.range("pocortx.option.rt_distance", 2, 8, c.rtDistance, "pocortx.value.chunks", v -> c.rtDistance = v));

        this.list.addHeader(Component.translatable("pocortx.header.effects"));
        this.list.addSmall(
                Options2.toggle("pocortx.option.shadows", c.shadows, v -> c.shadows = v),
                Options2.slider("pocortx.option.shadow_strength", 0.0, 1.0, c.shadowStrength, 1.0, v -> c.shadowStrength = (float) v));
        this.list.addSmall(
                Options2.toggle("pocortx.option.ao", c.ambientOcclusion, v -> c.ambientOcclusion = v),
                Options2.slider("pocortx.option.ao_strength", 0.0, 1.0, c.aoStrength, 1.0, v -> c.aoStrength = (float) v));
        this.list.addSmall(
                Options2.toggle("pocortx.option.gi", c.globalIllumination, v -> c.globalIllumination = v),
                Options2.slider("pocortx.option.gi_strength", 0.0, 2.0, c.giStrength, 1.0, v -> c.giStrength = (float) v));
        this.list.addSmall(
                Options2.toggle("pocortx.option.reflections", c.reflections, v -> c.reflections = v),
                Options2.slider("pocortx.option.reflection_strength", 0.0, 1.0, c.reflectionStrength, 1.0, v -> c.reflectionStrength = (float) v));
        this.list.addSmall(
                Options2.toggle("pocortx.option.specular", c.specular, v -> c.specular = v),
                Options2.slider("pocortx.option.specular_strength", 0.0, 2.0, c.specularStrength, 1.0, v -> c.specularStrength = (float) v));
        this.list.addSmall(
                Options2.toggle("pocortx.option.volumetric", c.volumetricLight, v -> c.volumetricLight = v),
                Options2.slider("pocortx.option.volumetric_strength", 0.0, 1.0, c.volumetricStrength, 1.0, v -> c.volumetricStrength = (float) v));
        this.list.addSmall(
                Options2.slider("pocortx.option.emissive_strength", 0.0, 2.0, c.emissiveStrength, 1.0, v -> c.emissiveStrength = (float) v),
                Options2.toggle("pocortx.option.alpha_shadows", c.alphaTestedShadows, v -> c.alphaTestedShadows = v));
        this.list.addSmall(
                Options2.toggle("pocortx.option.puddles", c.rainPuddles, v -> c.rainPuddles = v),
                Options2.toggle("pocortx.option.waves", c.waterWaves, v -> c.waterWaves = v));
        this.list.addSmall(
                Options2.toggle("pocortx.option.denoise", c.temporalDenoise, v -> c.temporalDenoise = v),
                Options2.cycle("pocortx.option.debug", RtxConfig.DebugView.values(), c.debugView, d -> Options2.enumName("pocortx.debug.", d),
                        v -> c.debugView = v));
        this.list.addBig(Button.builder(Component.translatable("pocortx.option.reset"), b -> {
            RtxConfig.reset();
            this.minecraft.gui.setScreen(new RtxSettingsScreen(this.lastScreen));
        }).width(310).build());
    }

    private static Component statusText() {
        String reason = RtDeviceSupport.unavailableReason();
        if (reason == null) {
            return Component.translatable("pocortx.rt.status.ok", RtDeviceSupport.deviceName()).withStyle(ChatFormatting.GREEN);
        }
        return Component.translatable("pocortx.rt.status.off", Component.translatable(reason)).withStyle(ChatFormatting.GOLD);
    }

    @Override
    public void removed() {
        RtxConfig.save();
        super.removed();
    }
}
