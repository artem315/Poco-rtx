package dev.pocortx.gui;

import com.mojang.serialization.Codec;
import dev.pocortx.config.RtxConfig;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.network.chat.Component;

/** Small factories for option rows that write straight into {@link RtxConfig}. */
final class Options2 {
    private Options2() {
    }

    static OptionInstance<Boolean> toggle(String key, boolean value, Consumer<Boolean> setter) {
        return OptionInstance.createBoolean(key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")), value, v -> {
            setter.accept(v);
            RtxConfig.save();
        });
    }

    /** A slider over [min, max], shown as a percentage of {@code unit}. */
    static OptionInstance<Double> slider(String key, double min, double max, double value, double unit, DoubleConsumer setter) {
        OptionInstance.SliderableValueSet<Double> values = OptionInstance.UnitDouble.INSTANCE.xmap(u -> min + u * (max - min), d -> (d - min) / (max - min));
        return new OptionInstance<>(key, OptionInstance.noTooltip(),
                (caption, v) -> Options.genericValueLabel(caption, Component.literal(String.format(Locale.ROOT, "%d%%", Math.round(v / unit * 100.0)))),
                values, Codec.doubleRange(min, max), value, v -> {
                    setter.accept(v);
                    RtxConfig.save();
                });
    }

    /** A signed slider over [-1, 1] with custom labels for the ends. */
    static OptionInstance<Double> signed(String key, double value, String negativeKey, String positiveKey, DoubleConsumer setter) {
        OptionInstance.SliderableValueSet<Double> values = OptionInstance.UnitDouble.INSTANCE.xmap(u -> u * 2.0 - 1.0, d -> (d + 1.0) / 2.0);
        return new OptionInstance<>(key, OptionInstance.noTooltip(), (caption, v) -> {
            int amount = (int) Math.round(Math.abs(v) * 100.0);
            Component label = amount == 0 ? Component.literal("0")
                    : Component.translatable(v < 0 ? negativeKey : positiveKey, amount);
            return Options.genericValueLabel(caption, label);
        }, values, Codec.doubleRange(-1.0, 1.0), value, v -> {
            setter.accept(v);
            RtxConfig.save();
        });
    }

    static OptionInstance<Integer> range(String key, int min, int max, int value, String valueKey, IntConsumer setter) {
        return new OptionInstance<>(key, OptionInstance.noTooltip(),
                (caption, v) -> Options.genericValueLabel(caption, Component.translatable(valueKey, v)),
                new OptionInstance.IntRange(min, max), value, v -> {
                    setter.accept(v);
                    RtxConfig.save();
                });
    }

    static <E extends Enum<E>> OptionInstance<E> cycle(String key, E[] all, E value, Function<E, Component> name, Consumer<E> setter) {
        Codec<E> codec = Codec.INT.xmap(i -> all[Math.max(0, Math.min(all.length - 1, i))], Enum::ordinal);
        return new OptionInstance<>(key, OptionInstance.cachedConstantTooltip(Component.translatable(key + ".tooltip")),
                // The cycle button adds the caption itself.
                (caption, v) -> name.apply(v),
                new OptionInstance.Enum<>(List.of(all), codec), value, v -> {
                    setter.accept(v);
                    RtxConfig.save();
                });
    }

    static Component enumName(String prefix, Enum<?> value) {
        return Component.translatable(prefix + value.name().toLowerCase(Locale.ROOT));
    }
}
