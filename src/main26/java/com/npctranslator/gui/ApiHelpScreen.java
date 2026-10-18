package com.npctranslator.gui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

@Environment(EnvType.CLIENT)
public class ApiHelpScreen extends Screen {

    private final Screen parent;

    public ApiHelpScreen(Screen parent) {
        super(Component.translatable("npctranslator.api_help.title"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        int buttonWidth = 220;
        int buttonHeight = 20;
        int x = this.width / 2 - buttonWidth / 2;
        int padding = 26;

        com.npctranslator.config.ModConfig.ApiPreset[] presets = java.util.Arrays.stream(com.npctranslator.config.ModConfig.ApiPreset.values())
                .filter(p -> !p.keyPage.isEmpty()).toArray(com.npctranslator.config.ModConfig.ApiPreset[]::new);
        int totalButtons = presets.length + 1;
        int totalHeight = totalButtons * buttonHeight + (totalButtons - 1) * (padding - buttonHeight);
        int startY = this.height / 2 - totalHeight / 2 + 20;

        // 中文增强版：国内能直连的服务商，点按钮打开它的 API Key 页面
        for (int i = 0; i < presets.length; i++) {
            com.npctranslator.config.ModConfig.ApiPreset p = presets[i];
            this.addRenderableWidget(Button.builder(Component.literal(p.displayName), button -> {
                try {
                    net.minecraft.util.Util.getPlatform().openUri(p.keyPage);
                } catch (Throwable e) {
                    try { java.awt.Desktop.getDesktop().browse(new java.net.URI(p.keyPage)); } catch (Exception ignored) {}
                }
            }).bounds(x, startY + padding * i, buttonWidth, buttonHeight).build());
        }

        // Back Button
        this.addRenderableWidget(Button.builder(Component.translatable("npctranslator.api_help.back"), button -> {
            com.npctranslator.NPCTranslatorClient.setScreen(this.minecraft, parent);
        }).bounds(x, startY + padding * presets.length + 10, buttonWidth, buttonHeight).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        context.centeredText(this.font, this.title, this.width / 2, this.height / 2 - 90, 0xFFFFFFFF);
        
        Component[] desc = {
            Component.translatable("npctranslator.api_help.desc1"),
            Component.translatable("npctranslator.api_help.desc2")
        };
        
        int y = this.height / 2 - 65;
        for (Component line : desc) {
            context.centeredText(this.font, line, this.width / 2, y, 0xFFAAAAAA);
            y += 14;
        }

        super.extractRenderState(context, mouseX, mouseY, delta);
    }
}
