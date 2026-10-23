package com.npctranslator.mixin;
import com.npctranslator.HudTranslator;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Display titles only: the original menu title remains available to other mods. */
@Mixin(AbstractContainerScreen.class)
public abstract class ContainerTitleMixin {
    @Redirect(method="extractLabels", at=@At(value="FIELD",target="Lnet/minecraft/client/gui/screens/inventory/AbstractContainerScreen;title:Lnet/minecraft/network/chat/Component;",opcode=180),require=1)
    private Component npctranslator$displayTitle(AbstractContainerScreen<?> screen) {
        return HudTranslator.containerTitle(screen.getTitle());
    }
}
