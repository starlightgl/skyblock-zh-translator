package com.npctranslator.mixin;

import com.npctranslator.HudTranslator;
import com.npctranslator.NPCTranslatorClient;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.List;

@Mixin(GuiGraphicsExtractor.class)
public class GuiGraphicsMixin {

    @ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> npctranslator$tooltipList(List<Component> components) {
        return NPCTranslatorClient.processRenderedTooltip(components);
    }

    @ModifyVariable(method = "setComponentTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Ljava/util/List;IILnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private List<Component> npctranslator$componentTooltipList(List<Component> components) {
        return NPCTranslatorClient.processRenderedTooltip(components);
    }

    /** Single-line tooltips drawn directly by mods. */
    @ModifyVariable(method = "setTooltipForNextFrame(Lnet/minecraft/client/gui/Font;Lnet/minecraft/network/chat/Component;IILnet/minecraft/resources/Identifier;)V",
            at = @At("HEAD"), argsOnly = true, require = 0)
    private Component npctranslator$singleTooltip(Component text) {
        Component t = HudTranslator.modText(text);
        return t != null ? t : text;
    }
}
