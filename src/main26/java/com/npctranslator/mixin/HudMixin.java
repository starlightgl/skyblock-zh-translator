package com.npctranslator.mixin;

import com.npctranslator.HudTranslator;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.Team;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 26.2+ split the HUD out of Gui into Hud. Hook points (sidebar title / sidebar rows / the item
 * name above the hotbar) were checked against the 26.2–26.3 hooks used by other SkyBlock text mods.
 * Everything is require = 0: if Mojang renames something, that piece just stays English.
 */
@Mixin(Hud.class)
public abstract class HudMixin {

    private static final String TEXT_WITH_BACKDROP =
            "Lnet/minecraft/client/gui/GuiGraphicsExtractor;textWithBackdrop(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IIII)V";

    @Redirect(
            method = "displayScoreboardSidebar",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/Objective;getDisplayName()Lnet/minecraft/network/chat/Component;"),
            require = 0)
    private Component npctranslator$sidebarTitle(Objective objective) {
        return HudTranslator.sidebarTitle(objective);
    }

    @Redirect(
            method = "lambda$displayScoreboardSidebar$1",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/scores/PlayerTeam;formatNameForTeam(Lnet/minecraft/world/scores/Team;Lnet/minecraft/network/chat/Component;)Lnet/minecraft/network/chat/MutableComponent;"),
            require = 0)
    private MutableComponent npctranslator$sidebarRow(Team team, Component name) {
        return HudTranslator.sidebarRow(team, name);
    }

    @Redirect(method = "extractSelectedItemName", at = @At(value = "INVOKE", target = TEXT_WITH_BACKDROP), require = 0)
    private void npctranslator$selectedItemName(GuiGraphicsExtractor graphics, Font font, Component text, int x, int y, int width, int color) {
        HudTranslator.selectedItemName(graphics, font, text, x, y, width, color);
    }
}
