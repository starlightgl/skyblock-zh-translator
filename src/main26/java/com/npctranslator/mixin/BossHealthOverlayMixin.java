package com.npctranslator.mixin;

import com.npctranslator.HudTranslator;
import net.minecraft.client.gui.components.BossHealthOverlay;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Shows the boss bar text translated (quest objectives, mining events). The server's text is not changed. */
@Mixin(BossHealthOverlay.class)
public abstract class BossHealthOverlayMixin {
    @Redirect(method = "extractRenderState",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/components/LerpingBossEvent;getName()Lnet/minecraft/network/chat/Component;"),
            require = 0)
    private Component npctranslator$bossName(LerpingBossEvent event) {
        return HudTranslator.bossBar(event.getName());
    }
}
