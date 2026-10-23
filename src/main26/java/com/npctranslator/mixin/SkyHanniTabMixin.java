package com.npctranslator.mixin;

import com.npctranslator.HudTranslator;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/** Optional compact-Tab renderer support: never modifies TabLine/TabWidget/parser data. */
@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.misc.compacttablist.TabListRenderer", remap = false)
public abstract class SkyHanniTabMixin {
    private static Component npctranslator$display(Component original) {
        if (original == null) return null;
        // Compact rows may contain player names without a level/rank. Keep ambiguous bare names.
        String plain = original.getString().replaceAll("§.", "").trim();
        if (plain.matches("[A-Za-z0-9_]{1,16}")) return original;
        return HudTranslator.tabText(original);
    }
    @ModifyArgs(method = {"drawTabList", "drawColumns"}, at = @At(value = "INVOKE",
            target = "Lat/hannibal2/skyhanni/utils/GuiRenderUtils;drawString$default(Lat/hannibal2/skyhanni/utils/GuiRenderUtils;Lnet/minecraft/network/chat/Component;FFIZILjava/lang/Object;)V"), require = 0, remap = false)
    private void npctranslator$drawText(Args args) {
        args.set(1, npctranslator$display(args.get(1)));
    }
    @ModifyArg(method = {"drawTabList", "drawColumns"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"), index = 0, require = 0, remap = false)
    private FormattedText npctranslator$measureText(FormattedText original) {
        return original instanceof Component component ? npctranslator$display(component) : original;
    }
}
