package dev.pocortx.gui;

import dev.pocortx.config.RtxConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/** "Shader settings": the BSL-style look (own implementation, works on every backend). */
public final class ShaderSettingsScreen extends OptionsSubScreen {
    public ShaderSettingsScreen(Screen parent) {
        super(parent, Minecraft.getInstance().options, Component.translatable("pocortx.shader.title"));
    }

    @Override
    protected void addOptions() {
        RtxConfig.ShaderSettings s = RtxConfig.get().shader;
        this.list.addSmall(
                Options2.toggle("pocortx.shader.enabled", s.enabled, v -> s.enabled = v),
                Options2.toggle("pocortx.shader.tonemap", s.tonemap, v -> s.tonemap = v));
        this.list.addHeader(Component.translatable("pocortx.header.color"));
        this.list.addSmall(
                Options2.slider("pocortx.shader.exposure", 0.25, 3.0, s.exposure, 1.0, v -> s.exposure = (float) v),
                Options2.slider("pocortx.shader.contrast", 0.5, 1.5, s.contrast, 1.0, v -> s.contrast = (float) v));
        this.list.addSmall(
                Options2.slider("pocortx.shader.saturation", 0.0, 2.0, s.saturation, 1.0, v -> s.saturation = (float) v),
                Options2.signed("pocortx.shader.vibrance", s.vibrance, "pocortx.value.less", "pocortx.value.more", v -> s.vibrance = (float) v));
        this.list.addSmall(
                Options2.signed("pocortx.shader.temperature", s.temperature, "pocortx.value.cool", "pocortx.value.warm", v -> s.temperature = (float) v),
                Options2.slider("pocortx.shader.vignette", 0.0, 1.0, s.vignette, 1.0, v -> s.vignette = (float) v));
        this.list.addHeader(Component.translatable("pocortx.header.post"));
        this.list.addSmall(
                Options2.toggle("pocortx.shader.bloom", s.bloom, v -> s.bloom = v),
                Options2.slider("pocortx.shader.bloom_strength", 0.0, 1.0, s.bloomStrength, 1.0, v -> s.bloomStrength = (float) v));
        this.list.addSmall(Options2.slider("pocortx.shader.sharpen", 0.0, 1.0, s.sharpen, 1.0, v -> s.sharpen = (float) v));
        this.list.addBig(Button.builder(Component.translatable("pocortx.option.reset"), b -> {
            RtxConfig.resetShader();
            this.minecraft.gui.setScreen(new ShaderSettingsScreen(this.lastScreen));
        }).width(310).build());
    }

    @Override
    public void removed() {
        RtxConfig.save();
        super.removed();
    }
}
