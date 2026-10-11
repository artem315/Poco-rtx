package dev.pocortx.command;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import dev.pocortx.config.RtxConfig;
import dev.pocortx.gui.RtxSettingsScreen;
import dev.pocortx.gui.ShaderSettingsScreen;
import dev.pocortx.rt.RtDeviceSupport;
import dev.pocortx.render.RtxRenderer;
import java.util.Locale;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * /rtx — typing a command is easier than binding keys on a phone.
 */
public final class RtxCommand {
    private RtxCommand() {
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        LiteralArgumentBuilder<FabricClientCommandSource> root = literal("rtx")
                .executes(RtxCommand::status)
                .then(literal("on").executes(ctx -> setEnabled(ctx, true)))
                .then(literal("off").executes(ctx -> setEnabled(ctx, false)))
                .then(literal("menu").executes(RtxCommand::openMenu))
                .then(literal("reset").executes(ctx -> {
                    RtxConfig.reset();
                    ctx.getSource().sendFeedback(Component.translatable("pocortx.msg.reset"));
                    return 1;
                }));

        LiteralArgumentBuilder<FabricClientCommandSource> quality = literal("quality");
        for (RtxConfig.Quality q : RtxConfig.Quality.values()) {
            quality.then(literal(q.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
                RtxConfig.get().quality = q;
                RtxConfig.save();
                ctx.getSource().sendFeedback(Component.translatable("pocortx.msg.quality", qualityName(q)));
                return 1;
            }));
        }
        root.then(quality);

        LiteralArgumentBuilder<FabricClientCommandSource> debug = literal("debug");
        for (RtxConfig.DebugView view : RtxConfig.DebugView.values()) {
            debug.then(literal(view.name().toLowerCase(Locale.ROOT)).executes(ctx -> {
                RtxConfig.get().debugView = view;
                RtxConfig.save();
                ctx.getSource().sendFeedback(Component.translatable("pocortx.msg.debug", view.name().toLowerCase(Locale.ROOT)));
                return 1;
            }));
        }
        root.then(debug);

        root.then(toggle("ao", "pocortx.option.ao", (c, v) -> c.ambientOcclusion = v));
        root.then(toggle("shadows", "pocortx.option.shadows", (c, v) -> c.shadows = v));
        root.then(toggle("gi", "pocortx.option.gi", (c, v) -> c.globalIllumination = v));
        root.then(toggle("reflections", "pocortx.option.reflections", (c, v) -> c.reflections = v));
        root.then(toggle("specular", "pocortx.option.specular", (c, v) -> c.specular = v));
        root.then(toggle("volumetric", "pocortx.option.volumetric", (c, v) -> c.volumetricLight = v));
        root.then(toggle("puddles", "pocortx.option.puddles", (c, v) -> c.rainPuddles = v));
        root.then(toggle("waves", "pocortx.option.waves", (c, v) -> c.waterWaves = v));
        root.then(toggle("shaders", "pocortx.shader.enabled", (c, v) -> c.shader.enabled = v));
        root.then(literal("shadermenu").executes(ctx -> {
            Minecraft client = ctx.getSource().getClient();
            client.execute(() -> client.gui.setScreen(new ShaderSettingsScreen(null)));
            return 1;
        }));
        root.then(literal("distance").then(argument("chunks", IntegerArgumentType.integer(2, 8)).executes(ctx -> {
            RtxConfig.get().rtDistance = IntegerArgumentType.getInteger(ctx, "chunks");
            RtxConfig.save();
            ctx.getSource().sendFeedback(Component.translatable("pocortx.msg.option", Component.translatable("pocortx.option.rt_distance"),
                    Component.translatable("pocortx.value.chunks", RtxConfig.get().rtDistance)));
            return 1;
        })));

        dispatcher.register(root);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, String key, BiConsumer<RtxConfig, Boolean> setter) {
        return literal(name).then(argument("value", BoolArgumentType.bool()).executes(ctx -> {
            boolean value = BoolArgumentType.getBool(ctx, "value");
            setter.accept(RtxConfig.get(), value);
            RtxConfig.save();
            ctx.getSource().sendFeedback(Component.translatable("pocortx.msg.option", Component.translatable(key), onOff(value)));
            return 1;
        }));
    }

    private static int setEnabled(CommandContext<FabricClientCommandSource> ctx, boolean enabled) {
        RtxConfig.get().enabled = enabled;
        RtxConfig.save();
        if (enabled) {
            RtxRenderer.INSTANCE.resetFailure();
        }
        ctx.getSource().sendFeedback(Component.translatable(enabled ? "pocortx.msg.enabled" : "pocortx.msg.disabled"));
        return 1;
    }

    private static int openMenu(CommandContext<FabricClientCommandSource> ctx) {
        Minecraft client = ctx.getSource().getClient();
        // The chat screen closes after the command runs; open the menu on the next frame.
        client.execute(() -> client.gui.setScreen(new RtxSettingsScreen(null)));
        return 1;
    }

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        RtxConfig c = RtxConfig.get();
        FabricClientCommandSource source = ctx.getSource();
        String reason = RtDeviceSupport.unavailableReason();
        source.sendFeedback(reason == null
                ? Component.translatable("pocortx.rt.status.ok", RtDeviceSupport.deviceName())
                : Component.translatable("pocortx.rt.status.off", Component.translatable(reason)));
        source.sendFeedback(Component.translatable("pocortx.msg.status",
                onOff(c.enabled), qualityName(c.quality), c.rtDistance,
                onOff(c.shadows), onOff(c.ambientOcclusion), onOff(c.globalIllumination), onOff(c.reflections),
                onOff(c.specular), onOff(c.volumetricLight), onOff(c.shader.enabled),
                RtxRenderer.INSTANCE.traceSections(), RtxRenderer.INSTANCE.traceInstances()));
        if (RtxRenderer.INSTANCE.hasFailed()) {
            source.sendError(Component.translatable("pocortx.msg.failed", RtxRenderer.INSTANCE.failureReason()));
        }
        source.sendFeedback(Component.translatable("pocortx.msg.help"));
        return 1;
    }

    private static Component onOff(boolean value) {
        return Component.translatable(value ? "options.on" : "options.off");
    }

    public static Component qualityName(RtxConfig.Quality q) {
        return Component.translatable("pocortx.quality." + q.name().toLowerCase(Locale.ROOT));
    }
}
