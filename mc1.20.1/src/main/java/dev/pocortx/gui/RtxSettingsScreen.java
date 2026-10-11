package dev.pocortx.gui;

import dev.pocortx.command.RtxCommand;
import dev.pocortx.config.RtxConfig;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;

public final class RtxSettingsScreen extends Screen {
    private static final int ROW = 24;

    private final Screen parent;

    public RtxSettingsScreen(Screen parent) {
        super(Text.translatable("pocortx.settings.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        RtxConfig c = RtxConfig.get();
        int colWidth = Math.min(150, (this.width - 30) / 2);
        int left = this.width / 2 - colWidth - 5;
        int right = this.width / 2 + 5;
        int y = Math.max(28, this.height / 2 - (ROW * 8) / 2);

        addDrawableChild(CyclingButtonWidget.onOffBuilder(c.enabled)
                .build(left, y, colWidth, 20, Text.translatable("pocortx.option.enabled"), (b, v) -> {
                    c.enabled = v;
                    RtxConfig.save();
                }));
        addDrawableChild(CyclingButtonWidget.<RtxConfig.Quality>builder(RtxCommand::qualityName)
                .values(Arrays.asList(RtxConfig.Quality.values()))
                .initially(c.quality)
                .build(right, y, colWidth, 20, Text.translatable("pocortx.option.quality"), (b, v) -> {
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

        addDrawableChild(toggle(left, y, colWidth, "pocortx.option.puddles", c.rainPuddles, v -> c.rainPuddles = v));
        addDrawableChild(toggle(right, y, colWidth, "pocortx.option.waves", c.waterWaves, v -> c.waterWaves = v));
        y += ROW;

        addDrawableChild(CyclingButtonWidget.<RtxConfig.DebugView>builder(
                        v -> Text.translatable("pocortx.debug." + v.name().toLowerCase(Locale.ROOT)))
                .values(Arrays.asList(RtxConfig.DebugView.values()))
                .initially(c.debugView)
                .build(left, y, colWidth, 20, Text.translatable("pocortx.option.debug"), (b, v) -> {
                    c.debugView = v;
                    RtxConfig.save();
                }));
        addDrawableChild(ButtonWidget.builder(Text.translatable("pocortx.option.reset"), b -> {
            RtxConfig.reset();
            this.clearAndInit();
        }).dimensions(right, y, colWidth, 20).build());
        y += ROW + 4;

        addDrawableChild(ButtonWidget.builder(ScreenTexts.DONE, b -> close())
                .dimensions(this.width / 2 - 100, Math.min(y, this.height - 24), 200, 20).build());
    }

    private int effectRow(int left, int right, int width, int y, String toggleKey, boolean value, Consumer<Boolean> setter,
                          String sliderKey, Supplier<Float> getter, Consumer<Float> sliderSetter) {
        addDrawableChild(toggle(left, y, width, toggleKey, value, setter));
        addDrawableChild(new StrengthSlider(right, y, width, sliderKey, getter.get(), sliderSetter));
        return y + ROW;
    }

    private static CyclingButtonWidget<Boolean> toggle(int x, int y, int width, String key, boolean value, Consumer<Boolean> setter) {
        return CyclingButtonWidget.onOffBuilder(value).build(x, y, width, 20, Text.translatable(key), (b, v) -> {
            setter.accept(v);
            RtxConfig.save();
        });
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 12, 0xFFFFFF);
    }

    @Override
    public void close() {
        RtxConfig.save();
        if (this.client != null) {
            this.client.setScreen(this.parent);
        }
    }

    private static final class StrengthSlider extends SliderWidget {
        private final String key;
        private final Consumer<Float> setter;

        StrengthSlider(int x, int y, int width, String key, float value, Consumer<Float> setter) {
            super(x, y, width, 20, Text.empty(), value);
            this.key = key;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Text.translatable(key, Math.round(this.value * 100.0) + "%"));
        }

        @Override
        protected void applyValue() {
            setter.accept((float) this.value);
            RtxConfig.save();
        }
    }
}
