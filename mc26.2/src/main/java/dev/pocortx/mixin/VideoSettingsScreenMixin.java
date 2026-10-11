package dev.pocortx.mixin;

import dev.pocortx.gui.RtxSettingsScreen;
import dev.pocortx.gui.ShaderSettingsScreen;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.VideoSettingsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VideoSettingsScreen.class)
abstract class VideoSettingsScreenMixin extends OptionsSubScreen {
    private VideoSettingsScreenMixin(Screen lastScreen, Options options, Component title) {
        super(lastScreen, options, title);
    }

    // "RTX settings" and "Shader settings" at the top of Video Settings.
    @Inject(method = "addOptions", at = @At("HEAD"))
    private void pocortx$addButtons(CallbackInfo ci) {
        if (this.list == null) {
            return;
        }
        Screen self = this;
        this.list.addHeader(Component.translatable("pocortx.header.video"));
        this.list.addSmall(
                Button.builder(Component.translatable("pocortx.video.rtx"), b -> this.minecraft.gui.setScreen(new RtxSettingsScreen(self))).build(),
                Button.builder(Component.translatable("pocortx.video.shader"), b -> this.minecraft.gui.setScreen(new ShaderSettingsScreen(self))).build());
    }
}
