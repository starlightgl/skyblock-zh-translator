package com.npctranslator.mixin;

import com.npctranslator.TextCollector;
import com.npctranslator.HudTranslator;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.UUID;

/** Raw getters/setters stay English for SkyHanni; only this screen's render inputs are translated. */
@Mixin(PlayerTabOverlay.class)
public abstract class TabListMixin {
    @Shadow private Component header;
    @Shadow private Component footer;

    @Inject(method = "setHeader", at = @At("HEAD"), require = 1)
    private void npctranslator$collectHeader(Component original, CallbackInfo ci) {
        TextCollector.record(TextCollector.TAB, "页眉", original);
    }
    @Inject(method = "setFooter", at = @At("HEAD"), require = 1)
    private void npctranslator$collectFooter(Component original, CallbackInfo ci) {
        TextCollector.record(TextCollector.TAB, "页脚", original);
    }
    @Inject(method = "getNameForDisplay", at = @At("RETURN"), require = 1)
    private void npctranslator$collectEntry(PlayerInfo info, CallbackInfoReturnable<Component> cir) {
        if (npctranslator$isInfo(info)) TextCollector.record(TextCollector.TAB, "列表", cir.getReturnValue());
    }
    private static boolean npctranslator$isInfo(PlayerInfo info) {
        try {
            if (info == null || info.getTabListDisplayName() == null) return false;
            UUID id = info.getProfile().id();
            return id != null && id.version() != 4;
        } catch (Throwable ignored) { return false; }
    }
    @Redirect(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;getNameForDisplay(Lnet/minecraft/client/multiplayer/PlayerInfo;)Lnet/minecraft/network/chat/Component;"), require = 1)
    private Component npctranslator$displayEntry(PlayerTabOverlay overlay, PlayerInfo info) {
        Component original = overlay.getNameForDisplay(info);
        return npctranslator$isInfo(info) ? HudTranslator.tabText(original) : original;
    }
    @Redirect(method = "extractRenderState", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;header:Lnet/minecraft/network/chat/Component;", opcode = 180), require = 1)
    private Component npctranslator$displayHeader(PlayerTabOverlay overlay) {
        return HudTranslator.tabText(header);
    }
    @Redirect(method = "extractRenderState", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/gui/components/PlayerTabOverlay;footer:Lnet/minecraft/network/chat/Component;", opcode = 180), require = 1)
    private Component npctranslator$displayFooter(PlayerTabOverlay overlay) {
        return HudTranslator.tabText(footer);
    }
}
