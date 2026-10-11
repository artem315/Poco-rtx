package dev.pocortx;

import dev.pocortx.command.RtxCommand;
import dev.pocortx.config.RtxConfig;
import dev.pocortx.gui.RtxSettingsScreen;
import dev.pocortx.render.RtxRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;

public final class PocoRtx implements ClientModInitializer {
    public static final String MOD_ID = "pocortx";

    private static KeyMapping toggleKey;
    private static KeyMapping settingsKey;

    @Override
    public void onInitializeClient() {
        RtxConfig.load();

        LevelRenderEvents.END_MAIN.register(RtxRenderer.INSTANCE::renderEffects);

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.pocortx.toggle", InputConstants.Type.KEYBOARD, InputConstants.KEY_F6, category));
        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.pocortx.settings", InputConstants.Type.KEYBOARD, InputConstants.KEY_F7, category));

        ClientTickEvents.END_CLIENT_TICK.register(PocoRtx::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, context) -> RtxCommand.register(dispatcher));
    }

    private static void onClientTick(Minecraft client) {
        while (toggleKey.consumeClick()) {
            RtxConfig config = RtxConfig.get();
            config.enabled = !config.enabled;
            if (config.enabled) {
                RtxRenderer.INSTANCE.resetFailure();
            }
            RtxConfig.save();
            if (client.player != null) {
                client.player.sendOverlayMessage(Component.translatable(config.enabled ? "pocortx.msg.enabled" : "pocortx.msg.disabled"));
            }
        }
        while (settingsKey.consumeClick()) {
            client.gui.setScreen(new RtxSettingsScreen(client.gui.screen()));
        }
        String failure = RtxRenderer.INSTANCE.pollFailureNotice();
        if (failure != null && client.player != null) {
            client.player.sendSystemMessage(Component.translatable("pocortx.msg.failed", failure).withStyle(ChatFormatting.RED));
        }
    }
}
