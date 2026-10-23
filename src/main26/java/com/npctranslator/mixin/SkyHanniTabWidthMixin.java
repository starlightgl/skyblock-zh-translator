package com.npctranslator.mixin;
import com.npctranslator.HudTranslator;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Column layout measures the same display-only text; raw components and names are unchanged. */
@Pseudo
@Mixin(targets = "at.hannibal2.skyhanni.features.misc.compacttablist.TabLine", remap = false)
public abstract class SkyHanniTabWidthMixin {
    @ModifyArg(method = "getWidth", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/Font;width(Lnet/minecraft/network/chat/FormattedText;)I"), index = 0, require = 0, remap = false)
    private FormattedText npctranslator$measureText(FormattedText original) {
        if (!(original instanceof Component component)) return original;
        String plain = component.getString().replaceAll("§.", "").trim();
        if (plain.matches("[A-Za-z0-9_]{1,16}")) return original;
        return HudTranslator.tabText(component);
    }
}
