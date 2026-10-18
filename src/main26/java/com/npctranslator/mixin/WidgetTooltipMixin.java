package com.npctranslator.mixin;

import com.npctranslator.HudTranslator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Hover tooltips of buttons/widgets (e.g. Skyblocker quick navigation). The split lines are cached,
 * so the text is swapped where the cache is built and the cache is dropped once a translation that
 * was missing becomes available.
 */
@Mixin(Tooltip.class)
public abstract class WidgetTooltipMixin {

    @Shadow @Final private Component message;
    @Shadow private List<FormattedCharSequence> cachedTooltip;

    @Unique private boolean npctranslator$translated;
    @Unique private int npctranslator$checkCounter;

    @Inject(method = "toCharSequence", at = @At("HEAD"), require = 0)
    private void npctranslator$refresh(Minecraft minecraft, CallbackInfoReturnable<List<FormattedCharSequence>> cir) {
        if (cachedTooltip == null || npctranslator$translated) return;
        if (++npctranslator$checkCounter < 40) return;
        npctranslator$checkCounter = 0;
        if (HudTranslator.modText(message) != null) cachedTooltip = null;
    }

    @Redirect(method = "toCharSequence",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/Tooltip;splitTooltip(Lnet/minecraft/client/Minecraft;Lnet/minecraft/network/chat/Component;)Ljava/util/List;"),
            require = 0)
    private List<FormattedCharSequence> npctranslator$split(Minecraft minecraft, Component text) {
        Component t = HudTranslator.modText(text);
        npctranslator$translated = t != null;
        return Tooltip.splitTooltip(minecraft, t != null ? t : text);
    }
}
