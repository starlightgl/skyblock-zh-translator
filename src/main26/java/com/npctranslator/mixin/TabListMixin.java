package com.npctranslator.mixin;

import com.npctranslator.TextCollector;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/** Records the Tab list (header, footer and the info columns) for the text collector. */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListMixin {

    @Inject(method = "setHeader", at = @At("HEAD"), require = 0)
    private void npctranslator$collectHeader(Component header, CallbackInfo ci) {
        TextCollector.record(TextCollector.TAB, "页眉", header);
    }

    @Inject(method = "setFooter", at = @At("HEAD"), require = 0)
    private void npctranslator$collectFooter(Component footer, CallbackInfo ci) {
        TextCollector.record(TextCollector.TAB, "页脚", footer);
    }

    @Inject(method = "getNameForDisplay", at = @At("RETURN"), require = 0)
    private void npctranslator$collectEntry(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        try {
            // real players have version-4 UUIDs; the server's info columns use fake profiles
            UUID id = info.getProfile().id();
            if (id != null && id.version() == 4) return;
        } catch (Throwable ignored) {
        }
        TextCollector.record(TextCollector.TAB, "列表", cir.getReturnValue());
    }
}
