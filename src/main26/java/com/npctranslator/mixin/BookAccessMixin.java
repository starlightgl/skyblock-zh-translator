package com.npctranslator.mixin;

import com.npctranslator.BookText;
import net.minecraft.client.gui.screens.inventory.BookViewScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Book pages (quest log stories): collected and, where the term table has them, shown in Chinese. */
@Mixin(BookViewScreen.BookAccess.class)
public abstract class BookAccessMixin {
    @Inject(method = "getPage", at = @At("RETURN"), cancellable = true, require = 0)
    private void npctranslator$page(int index, CallbackInfoReturnable<Component> cir) {
        try {
            Component page = cir.getReturnValue();
            Component shown = BookText.page(page);
            if (shown != page) cir.setReturnValue(shown);
        } catch (Throwable ignored) {
        }
    }
}
