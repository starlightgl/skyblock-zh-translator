package com.npctranslator.mixin;

import com.npctranslator.HudTranslator;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Text display entities (newer floating text). The split lines are cached by vanilla, so the
 * text is swapped where the cache is built, and the cache is dropped once a translation that
 * was missing becomes available.
 */
@Mixin(Display.TextDisplay.class)
public abstract class TextDisplayMixin {

    @Shadow private Display.TextDisplay.CachedInfo clientDisplayCache;
    @Shadow private Display.TextDisplay.TextRenderState textRenderState;

    @Unique private boolean npctranslator$cachedTranslated;
    @Unique private int npctranslator$checkCounter;

    @Inject(method = "cacheDisplay", at = @At("HEAD"), require = 0)
    private void npctranslator$refreshWhenTranslated(Display.TextDisplay.LineSplitter splitter, CallbackInfoReturnable<Display.TextDisplay.CachedInfo> cir) {
        if (clientDisplayCache == null || npctranslator$cachedTranslated || textRenderState == null) return;
        if (++npctranslator$checkCounter < 40) return; // about every 2 s at 20 fps per entity
        npctranslator$checkCounter = 0;
        if (HudTranslator.hasWorldText(textRenderState.text())) {
            clientDisplayCache = null;
        }
    }

    @Redirect(
            method = "cacheDisplay",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Display$TextDisplay$TextRenderState;text()Lnet/minecraft/network/chat/Component;"),
            require = 0)
    private Component npctranslator$translateText(Display.TextDisplay.TextRenderState state) {
        Component original = state.text();
        Component translated = HudTranslator.worldText(original);
        npctranslator$cachedTranslated = translated != null;
        return translated != null ? translated : original;
    }
}
