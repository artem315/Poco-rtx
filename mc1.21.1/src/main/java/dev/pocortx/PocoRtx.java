package dev.pocortx;

import dev.pocortx.command.RtxCommand;
import dev.pocortx.config.RtxConfig;
import dev.pocortx.gui.RtxSettingsScreen;
import dev.pocortx.render.RtxRenderer;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

public final class PocoRtx implements ClientModInitializer {
    public static final String MOD_ID = "pocortx";

    private static KeyBinding toggleKey;
    private static KeyBinding settingsKey;

    @Override
    public void onInitializeClient() {
        RtxConfig.load();

        WorldRenderEvents.BEFORE_DEBUG_RENDER.register(RtxRenderer.INSTANCE::captureOpaqueDepth);
        WorldRenderEvents.LAST.register(RtxRenderer.INSTANCE::renderEffects);

        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return Identifier.of(MOD_ID, "shaders");
            }

            @Override
            public void reload(ResourceManager manager) {
                RtxRenderer.INSTANCE.invalidate();
            }
        });

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pocortx.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, "key.categories.pocortx"));
        settingsKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.pocortx.settings", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F7, "key.categories.pocortx"));

        ClientTickEvents.END_CLIENT_TICK.register(PocoRtx::onClientTick);
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> RtxCommand.register(dispatcher));
    }

    private static void onClientTick(MinecraftClient client) {
        while (toggleKey.wasPressed()) {
            RtxConfig config = RtxConfig.get();
            config.enabled = !config.enabled;
            RtxConfig.save();
            if (client.player != null) {
                client.player.sendMessage(Text.translatable(config.enabled ? "pocortx.msg.enabled" : "pocortx.msg.disabled"), true);
            }
        }
        while (settingsKey.wasPressed()) {
            client.setScreen(new RtxSettingsScreen(client.currentScreen));
        }
        String failure = RtxRenderer.INSTANCE.pollFailureNotice();
        if (failure != null && client.player != null) {
            client.player.sendMessage(Text.translatable("pocortx.msg.failed", failure).formatted(Formatting.RED), false);
        }
    }
}
