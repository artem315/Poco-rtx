package dev.pocortx.gui;

import dev.pocortx.command.RtxCommand;
import dev.pocortx.config.RtxConfig;
import dev.pocortx.render.RtxRenderer;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

public final class RtxSettingsScreen extends Screen {
    private static final int ROW = 24;

    private final Screen parent;

    public RtxSettingsScreen(Screen parent) {
        super(Component.translatable("pocortx.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        RtxConfig c = RtxConfig.get();
        int colWidth = Math.min(150, (this.width - 30) / 2);
        int left = this.width / 2 - colWidth - 5;
        int right = this.width / 2 + 5;
        int y = Math.max(28, this.height / 2 - (ROW * 8) / 2);

        addRenderableWidget(CycleButton.onOffBuilder(c.enabled)
                .create(left, y, colWidth, 20, Component.translatable("pocortx.option.enabled"), (b, v) -> {
                    c.enabled = v;
                    if (v) {
                        RtxRenderer.INSTANCE.resetFailure();
                    }
                    RtxConfig.save();
                }));
        addRenderableWidget(CycleButton.builder(RtxCommand::qualityName, c.quality)
                .withValues(Arrays.asList(RtxConfig.Quality.values()))
                .create(right, y, colWidth, 20, Component.translatable("pocortx.option.quality"), (b, v) -> {
                    c.quality = v;
                    RtxConfig.save();
                }));
        y += ROW;

        y = effectRow(left, right, colWidth, y, "pocortx.option.ao", c.ambientOcclusion, v -> c.ambientOcclusion = v,
                "pocortx.option.ao_strength", () -> c.aoStrength, v -> c.aoStrength = v);
        y = effectRow(left, right, colWidth, y, "pocortx.option.shadows", c.shadows, v -> c.shadows = v,
                "pocortx.option.shadow_strength", () -> c.shadowStrength, v -> c.shadowStrength = v);
        y = effectRow(left, right, colWidth, y, "pocortx.option.reflections", c.reflections, v -> c.reflections = v,
                "pocortx.option.reflection_strength", () -> c.reflectionStrength, v -> c.reflectionStrength = v);
        y = effectRow(left, right, colWidth, y, "pocortx.option.shafts", c.lightShafts, v -> c.lightShafts = v,
                "pocortx.option.shaft_strength", () -> c.lightShaftStrength, v -> c.lightShaftStrength = v);

        addRenderableWidget(toggle(left, y, colWidth, "pocortx.option.puddles", c.rainPuddles, v -> c.rainPuddles = v));
        addRenderableWidget(toggle(right, y, colWidth, "pocortx.option.waves", c.waterWaves, v -> c.waterWaves = v));
        y += ROW;

        addRenderableWidget(CycleButton.<RtxConfig.DebugView>builder(
                        v -> Component.translatable("pocortx.debug." + v.name().toLowerCase(Locale.ROOT)), c.debugView)
                .withValues(Arrays.asList(RtxConfig.DebugView.values()))
                .create(left, y, colWidth, 20, Component.translatable("pocortx.option.debug"), (b, v) -> {
                    c.debugView = v;
                    RtxConfig.save();
                }));
        addRenderableWidget(Button.builder(Component.translatable("pocortx.option.reset"), b -> {
            RtxConfig.reset();
            this.rebuildWidgets();
        }).bounds(right, y, colWidth, 20).build());
        y += ROW + 4;

        addRenderableWidget(Button.builder(CommonComponents.GUI_DONE, b -> onClose())
                .bounds(this.width / 2 - 100, Math.min(y, this.height - 24), 200, 20).build());
    }

    private int effectRow(int left, int right, int width, int y, String toggleKey, boolean value, Consumer<Boolean> setter,
                          String sliderKey, Supplier<Float> getter, Consumer<Float> sliderSetter) {
        addRenderableWidget(toggle(left, y, width, toggleKey, value, setter));
        addRenderableWidget(new StrengthSlider(right, y, width, sliderKey, getter.get(), sliderSetter));
        return y + ROW;
    }

    private static CycleButton<Boolean> toggle(int x, int y, int width, String key, boolean value, Consumer<Boolean> setter) {
        return CycleButton.onOffBuilder(value).create(x, y, width, 20, Component.translatable(key), (b, v) -> {
            setter.accept(v);
            RtxConfig.save();
        });
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractRenderState(graphics, mouseX, mouseY, a);
        graphics.centeredText(this.font, this.title, this.width / 2, 12, 0xFFFFFFFF);
    }

    @Override
    public void onClose() {
        RtxConfig.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    private static final class StrengthSlider extends AbstractSliderButton {
        private final String key;
        private final Consumer<Float> setter;

        StrengthSlider(int x, int y, int width, String key, float value, Consumer<Float> setter) {
            super(x, y, width, 20, Component.empty(), value);
            this.key = key;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable(key, Math.round(this.value * 100.0) + "%"));
        }

        @Override
        protected void applyValue() {
            setter.accept((float) this.value);
            RtxConfig.save();
        }
    }
}
