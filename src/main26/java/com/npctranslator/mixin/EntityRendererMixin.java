package com.npctranslator.mixin;

import com.npctranslator.HudTranslator;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Name tags above entities. On Hypixel, NPC names, their subtitles ("CLICK", job titles) and
 * holograms are armor stands with a custom name, so translating the extracted name tag covers them.
 * Real players are skipped so player names are never touched.
 */
@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {

    @Inject(
            method = "extractNameTags(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;FDD)V",
            at = @At("TAIL"),
            require = 0)
    private void npctranslator$translateNameTag(Entity entity, EntityRenderState state, float partialTick, double a, double b, CallbackInfo ci) {
        if (state.nameTag == null || entity instanceof Player) return;
        Component translated = HudTranslator.worldText(state.nameTag);
        if (translated != null) state.nameTag = translated;
    }
}
