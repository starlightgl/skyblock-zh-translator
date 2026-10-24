package com.npctranslator.mixin;

import com.npctranslator.HoverText;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** "Show text" hover events (chat): the text is split into tooltip lines here; translate it first. */
@Mixin(GuiGraphicsExtractor.class)
public abstract class HoverTextMixin {
    @ModifyArg(method = "componentHoverEffect",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Font;split(Lnet/minecraft/network/chat/FormattedText;I)Ljava/util/List;"),
            index = 0, require = 0)
    private FormattedText npctranslator$hoverText(FormattedText text) {
        try {
            return HoverText.text(text);
        } catch (Throwable t) {
            return text;
        }
    }
}
