package com.npctranslator.mixin;

import com.npctranslator.TextCollector;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.BossEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records boss bar names (shown on the client as LerpingBossEvent) for the text collector. */
@Mixin(BossEvent.class)
public abstract class BossEventMixin {

    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void npctranslator$collectCreate(CallbackInfo ci) {
        if ((Object) this instanceof LerpingBossEvent e) TextCollector.record(TextCollector.BOSS_BAR, "", e.getName());
    }

    @Inject(method = "setName", at = @At("HEAD"), require = 0)
    private void npctranslator$collectName(Component name, CallbackInfo ci) {
        if ((Object) this instanceof LerpingBossEvent) TextCollector.record(TextCollector.BOSS_BAR, "", name);
    }
}
