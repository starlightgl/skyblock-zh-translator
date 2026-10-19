package com.npctranslator;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.npctranslator.config.ModConfig;
import com.npctranslator.mixin.ChatHudAccessor;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.KeyMapping;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.ChatFormatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class NPCTranslatorClient implements ClientModInitializer {

    private static ModConfig config;
    private static final HttpClient HTTP_CLIENT = HttpClient.newHttpClient();
    private static final Gson GSON = new Gson();
    public static KeyMapping keyTranslateGoogle;
    public static KeyMapping keyTranslateGemini;
    public static KeyMapping keyTranslateGroq;
    public static KeyMapping keyTranslateMistral;
    public static KeyMapping keyTranslateOpenRouter;
    public static KeyMapping keyTranslateCustom;
    public static KeyMapping keyRevertTranslation;
    public static KeyMapping menuKey;

    private static final Map<String, ModConfig.TranslationProvider> TRANSLATED_PROVIDER_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> TRANSLATION_MEMORY_CACHE = new ConcurrentHashMap<>();
    
    public static final Map<String, List<Component>> ITEM_CACHE = new ConcurrentHashMap<>();
    /** cacheKey -> (original formatted lore line -> replacement lines; empty list = line merged away). */
    public static final Map<String, Map<String, List<Component>>> ITEM_LINE_MAP = new ConcurrentHashMap<>();
    private static final Set<String> PENDING_ITEMS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    // WeakHashMap to track tooltips processed by ItemStackMixin without modifying them
    // identity + weak keys: other mods add lines to the list after we saw it, which must not change its key
    public static final java.util.Map<List<Component>, Boolean> PROCESSED_TOOLTIPS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
    private static final Map<String, Component> ORIGINAL_MESSAGES = new ConcurrentHashMap<>();
    private static final Set<String> REVERTED_ITEMS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final java.util.concurrent.atomic.AtomicInteger messageCounter = new java.util.concurrent.atomic.AtomicInteger(0);
    private static final Map<String, String> MESSAGE_ID_TO_BASE64 = new ConcurrentHashMap<>();
    private static final Set<String> TOGGLED_THIS_TICK = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // ── TTS Queue (prevents audio overlap) ──────────────────────────────────────
    private static final java.util.concurrent.LinkedBlockingQueue<String> TTS_QUEUE =
            new java.util.concurrent.LinkedBlockingQueue<>(50);
    private static volatile boolean TTS_RUNNER_STARTED = false;
    
    private static final Set<String> ACTIVE_TRANSLATED_ITEMS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static boolean googleJustPressed = false;
    public static boolean geminiJustPressed = false;
    public static boolean groqJustPressed = false;
    public static boolean mistralJustPressed = false;
    public static boolean openRouterJustPressed = false;
    public static boolean customJustPressed = false;
    public static boolean revertJustPressed = false;
    private static boolean lastGoogleState = false;
    private static boolean lastGeminiState = false;
    private static boolean lastGroqState = false;
    private static boolean lastMistralState = false;
    private static boolean lastOpenRouterState = false;
    private static boolean lastCustomState = false;
    private static boolean lastRevertState = false;
    private static long animationTick = 0;

    private static ChatComponent getChatComponent(Minecraft client) {
        try {
            // Try 26.1
            java.lang.reflect.Method getChat = client.gui.getClass().getMethod("getChat");
            return (ChatComponent) getChat.invoke(client.gui);
        } catch (Exception e) {
            try {
                // Try 26.2
                java.lang.reflect.Field hudField = client.gui.getClass().getField("hud");
                Object hud = hudField.get(client.gui);
                java.lang.reflect.Method getChat = hud.getClass().getMethod("getChat");
                return (ChatComponent) getChat.invoke(hud);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }
    }

    public static void setScreen(Minecraft client, Screen screen) {
        if (client == null) return;
        try {
            // Try 1.21.11 and 26.1
            java.lang.reflect.Method setScreenMethod = client.getClass().getMethod("setScreen", Screen.class);
            setScreenMethod.invoke(client, screen);
        } catch (NoSuchMethodException e) {
            try {
                // Try 26.2 and 26.3
                java.lang.reflect.Method setScreenMethod = client.gui.getClass().getMethod("setScreen", Screen.class);
                setScreenMethod.invoke(client.gui, screen);
            } catch (NoSuchMethodException ex) {
                try {
                    // Try 26.3 setScreenAndShow
                    java.lang.reflect.Method setScreenMethod = client.getClass().getMethod("setScreenAndShow", Screen.class);
                    setScreenMethod.invoke(client, screen);
                } catch (Exception ex2) {
                    ex2.printStackTrace();
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Screen getScreen(Minecraft client) {
        try {
            // Try 1.21.11 and 26.1
            java.lang.reflect.Field screenField = client.getClass().getField("screen");
            return (Screen) screenField.get(client);
        } catch (Exception e) {
            try {
                // Try 26.2
                java.lang.reflect.Method screenMethod = client.gui.getClass().getMethod("screen");
                return (Screen) screenMethod.invoke(client.gui);
            } catch (Exception ex) {
                ex.printStackTrace();
                return null;
            }
        }
    }

    private static boolean isKeyCurrentlyDown(KeyMapping keyMapping) {
        if (keyMapping == null || keyMapping.isUnbound()) return false;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return keyMapping.isDown();
        try {
            com.mojang.blaze3d.platform.InputConstants.Key key = com.mojang.blaze3d.platform.InputConstants.getKey(keyMapping.saveString());
            if (key == null || key.equals(InputConstants.UNKNOWN)) return false;
            int keyCode = key.getValue();
            if (keyCode != InputConstants.UNKNOWN.getValue() && keyCode > 0) {
                // 26.3+ SDL: isKeyDown(int)
                try {
                    java.lang.reflect.Method m = com.mojang.blaze3d.platform.InputConstants.class.getMethod("isKeyDown", int.class);
                    return (boolean) m.invoke(null, keyCode);
                } catch (NoSuchMethodException e) {
                    // 26.1 / 26.2 GLFW: isKeyDown(Window, int)
                    if (client.getWindow() != null) {
                        try {
                            java.lang.reflect.Method m = com.mojang.blaze3d.platform.InputConstants.class.getMethod("isKeyDown", com.mojang.blaze3d.platform.Window.class, int.class);
                            return (boolean) m.invoke(null, client.getWindow(), keyCode);
                        } catch (Exception ignored) {}
                    }
                }
            }
        } catch (Exception ignored) {}
        return keyMapping.isDown();
    }

    @Override
    public void onInitializeClient() {
        TranslationDictionary.load();
        ModConfig.load();
        config = ModConfig.INSTANCE;
        Glossary.ensureFile();
        dataSource = source();
        PhraseBook.loadAsync();
        HudTranslator.init();
        startTtsRunner();

        net.minecraft.client.KeyMapping.Category customCategory = net.minecraft.client.KeyMapping.Category.MISC;
        try {
            // Try all possible names for ResourceLocation/Identifier depending on mapping (Fabric vs NeoForge/Mojmap)
            String[] classNames = { "net.minecraft.resources.Identifier", "net.minecraft.resources.ResourceLocation", "net.minecraft.util.Identifier" };
            Class<?> idClass = null;
            for (String name : classNames) {
                try {
                    idClass = Class.forName(name);
                    break;
                } catch (Exception ignored) {}
            }
            
            if (idClass != null) {
                // Try parse or fromNamespaceAndPath
                Object idObj = null;
                try {
                    idObj = idClass.getMethod("parse", String.class).invoke(null, "npctranslator:keys");
                } catch (Exception e1) {
                    try {
                        idObj = idClass.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, "npctranslator", "keys");
                    } catch (Exception e2) {}
                }
                
                if (idObj != null) {
                    // Try to register (Fabric API)
                    try {
                        java.lang.reflect.Method regMethod = net.minecraft.client.KeyMapping.Category.class.getMethod("register", idClass);
                        customCategory = (net.minecraft.client.KeyMapping.Category) regMethod.invoke(null, idObj);
                    } catch (Exception e3) {
                        // Try NeoForge constructor
                        try {
                            java.lang.reflect.Constructor<?> constructor = net.minecraft.client.KeyMapping.Category.class.getConstructor(idClass);
                            customCategory = (net.minecraft.client.KeyMapping.Category) constructor.newInstance(idObj);
                        } catch (Exception e4) {
                            try {
                                java.lang.reflect.Constructor<?> constructor = net.minecraft.client.KeyMapping.Category.class.getConstructor(String.class);
                                customCategory = (net.minecraft.client.KeyMapping.Category) constructor.newInstance("key.category.npctranslator.keys");
                            } catch (Exception ignored) {}
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        final net.minecraft.client.KeyMapping.Category finalCategory = customCategory;

        // 中文增强版：Google / Gemini / Groq / Mistral / OpenRouter 在国内用不了，不再注册它们的按键（G / X / C 还给游戏和其他模组）
        keyTranslateCustom = new KeyMapping(
                "key.npctranslator.translate.custom",
                InputConstants.KEY_N,
                finalCategory
        );
        KeyMappingHelper.registerKeyMapping(keyTranslateCustom);

        keyRevertTranslation = new KeyMapping(
                "key.npctranslator.translate.revert",
                InputConstants.KEY_V,
                finalCategory
        );
        KeyMappingHelper.registerKeyMapping(keyRevertTranslation);
        
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>) {
                net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.afterExtract(screen).register((s, g, mx, my, delta) -> {
                    try { drawPageProgress(g); } catch (Throwable ignored) {}
                });
            }
        });

        keyTranslatePage = new KeyMapping(
                "key.npctranslator.translate.page",
                InputConstants.KEY_M,
                finalCategory
        );
        KeyMappingHelper.registerKeyMapping(keyTranslatePage);

        keyReport = new KeyMapping(
                "key.npctranslator.report",
                InputConstants.KEY_F8,
                finalCategory
        );
        KeyMappingHelper.registerKeyMapping(keyReport);

        menuKey = new KeyMapping(
                "key.npctranslator.menu",
                InputConstants.KEY_Z,
                finalCategory
        );
        KeyMappingHelper.registerKeyMapping(menuKey);

        ClientReceiveMessageEvents.MODIFY_GAME.register((text, overlay) -> {
            if (!overlay) TextCollector.record(TextCollector.CHAT, "", text); // action bar is recorded by the Hud hook
            if (!config.enabled) return text;
            if (overlay) return text;
            String rawText = text.getString();
            if (rawText.trim().length() < 2) return text;
            String btnText = Component.translatable("npctranslator.button").getString();
            if (rawText.contains("[Çevir]") || rawText.contains("[TR]") || rawText.contains("[Translate]") || rawText.contains("[EN]") || (!btnText.isEmpty() && rawText.contains(btnText))) {
                return text;
            }

            // 中文增强版：短语库里有固定译文的系统提示/NPC 对话直接替换，不调用 AI
            if (config.usePhrasebook && config.fixedChatTranslation) {
                try {
                    Component fixed = translateChatFixed(text);
                    if (fixed != null) return fixed;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            
            String msgId = String.valueOf(messageCounter.incrementAndGet());
            String encodedText = Base64.getEncoder().encodeToString(rawText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            ORIGINAL_MESSAGES.put(msgId, text.copy());
            rememberNpcLine(msgId, rawText);

            // 中文增强版：已经翻译过（词典里有）的消息直接显示译文，比如 SkyHanni 的“获得了 X 硬币/物品”这类固定格式消息
            if (config.autoShowStoredChat) {
                try {
                    Component stored = storedChatTranslation(text, msgId);
                    if (stored != null) return stored;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            MESSAGE_ID_TO_BASE64.put(msgId, encodedText);

            String cachedTranslation = TRANSLATION_MEMORY_CACHE.get(rawText);
            if (cachedTranslation == null && config.chatTranslationProvider != null) {
                cachedTranslation = TranslationDictionary.get(rawText, config.chatTranslationProvider.name());
            }
            com.npctranslator.chatbubble.ChatBubbleManager.onChatMessage(msgId, rawText, cachedTranslation);

            boolean shouldAutoTranslate = config.autoTranslateChat && (!config.onlyTranslateNpcChat || rawText.contains("[NPC] "));

            if (shouldAutoTranslate) {
                handleTranslation(msgId);
                return Component.empty().append(text.copy()).append(Component.literal("\u200B").withStyle(s -> s.withClickEvent(new ClickEvent.RunCommand("/translate_npc " + msgId))));
            }

            if (!shouldAutoTranslate && config.enableTts) {
                if (config.ttsMode == ModConfig.TtsMode.ALL_CHAT) {
                    speakText(rawText);
                } else if (config.ttsMode == ModConfig.TtsMode.NPC_ONLY && (rawText.contains("[NPC] ") || rawText.contains("NPC"))) {
                    speakText(rawText);
                }
            }

            if (!config.showTranslateButton) {
                return text;
            }

            MutableComponent originalMessage = text.copy();
            MutableComponent translateButton = Component.translatable("npctranslator.button")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent.RunCommand("/translate_npc " + msgId))
                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("npctranslator.hover"))));

            if (config.buttonPosition == ModConfig.ButtonPosition.END) {
                return Component.empty().append(originalMessage).append(" ").append(translateButton);
            } else {
                return Component.empty().append(translateButton).append(" ").append(originalMessage);
            }
        });

        net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean pGoogle = isKeyCurrentlyDown(keyTranslateGoogle);
            googleJustPressed = pGoogle && !lastGoogleState;
            lastGoogleState = pGoogle;

            boolean pGemini = isKeyCurrentlyDown(keyTranslateGemini);
            geminiJustPressed = pGemini && !lastGeminiState;
            lastGeminiState = pGemini;

            boolean pGroq = isKeyCurrentlyDown(keyTranslateGroq);
            groqJustPressed = pGroq && !lastGroqState;
            lastGroqState = pGroq;

            boolean pMistral = isKeyCurrentlyDown(keyTranslateMistral);
            mistralJustPressed = pMistral && !lastMistralState;
            lastMistralState = pMistral;

            boolean pOpenRouter = isKeyCurrentlyDown(keyTranslateOpenRouter);
            openRouterJustPressed = pOpenRouter && !lastOpenRouterState;
            lastOpenRouterState = pOpenRouter;

            boolean pCustom = isKeyCurrentlyDown(keyTranslateCustom);
            customJustPressed = pCustom && !lastCustomState;
            lastCustomState = pCustom;

            boolean pRevert = isKeyCurrentlyDown(keyRevertTranslation);
            revertJustPressed = pRevert && !lastRevertState;
            lastRevertState = pRevert;

            TOGGLED_THIS_TICK.clear();
            animationTick++;
            try {
                tickPageTranslation(client);
            } catch (Throwable t) {
                System.err.println("[NPCTranslator] page translation: " + t);
            }
            
            while (menuKey.consumeClick()) {
                openSettings();
            }
            boolean pMenu = isKeyCurrentlyDown(menuKey);
            boolean menuJust = pMenu && !lastMenuState;
            lastMenuState = pMenu;
            if (menuJust) {
                Screen cur = getScreen(client);
                if (cur instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                        && !(cur.getFocused() instanceof net.minecraft.client.gui.components.EditBox)) {
                    // close the menu properly (tells the server) before showing our settings
                    if (client.player != null) client.player.closeContainer();
                    openSettings();
                }
            }
        });

        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            UpdateChecker.onJoin(client);
            if (!config.hasSeenWelcomeScreen) {
            client.execute(() -> {
                    setScreen(client, new com.npctranslator.gui.WelcomeScreen());
                });
            }
        });

        // Oyun kapanınca (sunucudan ayrılınca) sözlüğü sil
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            // 中文增强版：默认不再删除词典，只有勾选了 clearDictOnDisconnect 才删
            if (config != null && config.clearDictOnDisconnect) {
                TranslationDictionary.clear();
            }
            ITEM_CACHE.clear(); ITEM_LINE_MAP.clear();
            PENDING_ITEMS.clear();
            ACTIVE_TRANSLATED_ITEMS.clear();
            REVERTED_ITEMS.clear();
        });

        net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register((com.mojang.brigadier.builder.LiteralArgumentBuilder) com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("translate")
                .executes(context -> {
                    openSettings();
                    return 1;
                })
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("find")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("keyword", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> {
                            String kw = ((String) context.getArgument("keyword", String.class)).trim();
                            List<Map.Entry<String, String>> hits = TranslationDictionary.find(kw, 9);
                            Minecraft mc = Minecraft.getInstance();
                            if (hits.isEmpty()) {
                                mc.player.sendSystemMessage(Component.translatable("npctranslator.find.none", kw).withStyle(ChatFormatting.GRAY));
                                return 1;
                            }
                            mc.player.sendSystemMessage(Component.translatable("npctranslator.find.header", kw).withStyle(ChatFormatting.YELLOW));
                            for (int i = 0; i < Math.min(8, hits.size()); i++) {
                                Map.Entry<String, String> e = hits.get(i);
                                String key = e.getKey();
                                String orig = key.substring(key.indexOf(':') + 1);
                                String menu = TranslationDictionary.menuOf(key);
                                mc.player.sendSystemMessage(Component.literal(" " + (menu != null ? "[" + menu + "] " : "")
                                        + TranslationUtil.stripColorCodes(orig) + " \u2192 " + TranslationUtil.stripColorCodes(e.getValue())).withStyle(ChatFormatting.GRAY));
                            }
                            if (hits.size() > 8) mc.player.sendSystemMessage(Component.literal(" …").withStyle(ChatFormatting.GRAY));
                            return 1;
                        })))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("forget")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("keyword", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> {
                            String kw = ((String) context.getArgument("keyword", String.class)).trim();
                            Minecraft mc = Minecraft.getInstance();
                            if (kw.length() < 2) {
                                mc.player.sendSystemMessage(Component.translatable("npctranslator.forget.too_short").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            int n = TranslationDictionary.removeMatching(kw);
                            clearRuntimeCaches();
                            mc.player.sendSystemMessage(Component.translatable("npctranslator.forget.done", n, kw).withStyle(ChatFormatting.GREEN));
                            return 1;
                        })))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("menus")
                    .executes(context -> {
                        Minecraft mc = Minecraft.getInstance();
                        List<Map.Entry<String, Integer>> menus = TranslationDictionary.menus();
                        if (menus.isEmpty()) {
                            mc.player.sendSystemMessage(Component.literal("[翻译] 还没有按菜单保存的译文").withStyle(ChatFormatting.GRAY));
                            return 1;
                        }
                        mc.player.sendSystemMessage(Component.literal("[翻译] 按菜单保存的译文（菜单名 · 条数）：").withStyle(ChatFormatting.YELLOW));
                        for (int i = 0; i < Math.min(15, menus.size()); i++) {
                            Map.Entry<String, Integer> m = menus.get(i);
                            mc.player.sendSystemMessage(Component.literal(" " + m.getKey() + " · " + m.getValue()).withStyle(ChatFormatting.GRAY));
                        }
                        if (menus.size() > 15) mc.player.sendSystemMessage(Component.literal(" …共 " + menus.size() + " 个菜单").withStyle(ChatFormatting.GRAY));
                        mc.player.sendSystemMessage(Component.literal("用 /translate forgetmenu <菜单名> 清除某个菜单的译文").withStyle(ChatFormatting.DARK_GRAY));
                        return 1;
                    }))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("forgetmenu")
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("menu", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> {
                            String kw = ((String) context.getArgument("menu", String.class)).trim();
                            Minecraft mc = Minecraft.getInstance();
                            if (kw.length() < 2) {
                                mc.player.sendSystemMessage(Component.translatable("npctranslator.forget.too_short").withStyle(ChatFormatting.RED));
                                return 0;
                            }
                            int n = TranslationDictionary.removeMenu(kw);
                            clearRuntimeCaches();
                            mc.player.sendSystemMessage(Component.literal("[翻译] 已清除菜单名含「" + kw + "」的 " + n + " 条译文，下次打开会重新翻译").withStyle(ChatFormatting.GREEN));
                            return 1;
                        })))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("collect")
                    .executes(context -> {
                        TextCollector.flush();
                        Minecraft mc = Minecraft.getInstance();
                        mc.player.sendSystemMessage(Component.literal("[原文收集] " + (config.collectMenuText ? "已开启" : "已关闭（设置 → 原文收集）")
                                + "，共 " + TextCollector.total() + " 条").withStyle(ChatFormatting.YELLOW));
                        for (Map.Entry<String, int[]> e : TextCollector.summary().entrySet()) {
                            int[] v = e.getValue();
                            mc.player.sendSystemMessage(Component.literal("  " + e.getKey() + "：" + v[0] + " 条" + (v[1] > 0 ? "（本次新增 " + v[1] + "）" : "")).withStyle(ChatFormatting.GRAY));
                        }
                        mc.player.sendSystemMessage(Component.literal("文件：config/" + TextCollector.FILE.getName() + "，玩一段时间后发给维护者整理进术语表；/translate collect reset 清空重新收集").withStyle(ChatFormatting.DARK_GRAY));
                        return 1;
                    })
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("reset")
                        .executes(context -> {
                            TextCollector.reset();
                            Minecraft.getInstance().player.sendSystemMessage(Component.literal("[原文收集] 已清空，重新开始收集").withStyle(ChatFormatting.GREEN));
                            return 1;
                        })))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("coverage")
                    .executes(context -> {
                        Minecraft mc = Minecraft.getInstance();
                        Coverage.saveNow();
                        int ok = Coverage.covered(), miss = Coverage.missing();
                        int pct = ok + miss == 0 ? 0 : Math.round(ok * 100f / (ok + miss));
                        mc.player.sendSystemMessage(Component.literal("[翻译覆盖率] 本次游戏看到的文本：已翻译 " + ok + " 条，未覆盖 " + miss + " 条（" + pct + "%）").withStyle(ChatFormatting.YELLOW));
                        for (String s : Coverage.summary(6)) {
                            mc.player.sendSystemMessage(Component.literal(" 未覆盖 · " + s).withStyle(ChatFormatting.GRAY));
                        }
                        mc.player.sendSystemMessage(Component.literal("未覆盖的原文已记录到 config/" + Coverage.FILE.getName() + "，可补进术语表").withStyle(ChatFormatting.DARK_GRAY));
                        return 1;
                    })
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("reset")
                        .executes(context -> {
                            Coverage.reset();
                            Minecraft.getInstance().player.sendSystemMessage(Component.literal("[翻译覆盖率] 已重新开始统计").withStyle(ChatFormatting.GREEN));
                            return 1;
                        })))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("report")
                    .executes(context -> {
                        report(Minecraft.getInstance(), null);
                        return 1;
                    })
                    .then(com.mojang.brigadier.builder.RequiredArgumentBuilder.argument("note", com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                        .executes(context -> {
                            report(Minecraft.getInstance(), context.getArgument("note", String.class));
                            return 1;
                        })))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("debug")
                    .executes(context -> {
                        HudTranslator.debug = !HudTranslator.debug;
                        Minecraft mc = Minecraft.getInstance();
                        mc.player.sendSystemMessage(Component.literal(HudTranslator.debug
                                ? "[翻译调试] 已开启：其他模组提示行的处理过程会写进 logs/latest.log，再输入一次关闭"
                                : "[翻译调试] 已关闭").withStyle(ChatFormatting.AQUA));
                        return 1;
                    }))
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("DeleteDict")
                    .executes(context -> {
                        int count = TranslationDictionary.size();
                        TranslationDictionary.clear();
                        HudTranslator.clearCache();
                        ITEM_CACHE.clear(); ITEM_LINE_MAP.clear();
                        Minecraft.getInstance().player.sendSystemMessage(Component.translatable("npctranslator.dict_cleared", count));
                        return 1;
                    })
                )
                .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("Delete")
                    .then(com.mojang.brigadier.builder.LiteralArgumentBuilder.literal("dictionary")
                        .executes(context -> {
                            int count = TranslationDictionary.size();
                            TranslationDictionary.clear();
                            HudTranslator.clearCache();
                            ITEM_CACHE.clear(); ITEM_LINE_MAP.clear();
                            Minecraft.getInstance().player.sendSystemMessage(Component.translatable("npctranslator.dict_cleared", count));
                            return 1;
                        })
                    )
                )
            );
        });
    }



    public static boolean isItemTranslationEnabled() {
        return config != null && config.enabled;
    }

    public static void setApiKey(String apiKey) {
        Minecraft client = Minecraft.getInstance();
        config.groqApiKey = apiKey;
        ModConfig.save();
        client.execute(() -> {
            ChatComponent chat = getChatComponent(client);
            if (chat != null) chat.addClientSystemMessage(
                    Component.translatable("npctranslator.key_saved").withStyle(ChatFormatting.GREEN));
        });
    }

    public static void openSettings() {
        Minecraft client = Minecraft.getInstance();
        if (client != null) {
            client.execute(() -> {
                Screen screen = new com.npctranslator.gui.HubScreen();
                setScreen(client, screen);
            });
        }
    }

    public static void reloadConfig() {
        config = ModConfig.INSTANCE;
    }

    private static void replaceMessageInChat(Minecraft client, String searchMarker, Component newMessage) {
        ChatComponent chatHud = getChatComponent(client);
        if (chatHud == null) return;
        ChatHudAccessor accessor = (ChatHudAccessor) chatHud;
        List<GuiMessage> messages = accessor.getMessages();
        // First try exact marker match (for unique ID commands embedded in click events)
        for (int i = 0; i < messages.size(); i++) {
            GuiMessage line = messages.get(i);
            String fullStyled = extractClickCommands(line.content());
            if (containsMarker(fullStyled, searchMarker)) {
                GuiMessage newLine = new GuiMessage(line.addedTime(), newMessage, null, null, null);
                messages.set(i, newLine);
                int scroll = accessor.getScrolledLines();
                accessor.invokeRefresh();
                accessor.setScrolledLines(scroll);
                return;
            }
        }
        // Fallback: text content match (for auto-translate)
        String searchText = searchMarker.trim();
        for (int i = 0; i < messages.size(); i++) {
            GuiMessage line = messages.get(i);
            String lineText = line.content().getString();
            if (lineText.contains(searchText) || searchText.contains(lineText.trim())) {
                GuiMessage newLine = new GuiMessage(line.addedTime(), newMessage, null, null, null);
                messages.set(i, newLine);
                int scroll = accessor.getScrolledLines();
                accessor.invokeRefresh();
                accessor.setScrolledLines(scroll);
                return;
            }
        }
    }

    /** marker followed by a non-digit, so "translate_npc 1" does not match "translate_npc 12". */
    private static boolean containsMarker(String hay, String marker) {
        int from = 0;
        while (true) {
            int at = hay.indexOf(marker, from);
            if (at < 0) return false;
            int end = at + marker.length();
            if (end >= hay.length() || !Character.isDigit(hay.charAt(end))) return true;
            from = at + 1;
        }
    }

    private static String extractClickCommands(Component text) {
        StringBuilder sb = new StringBuilder();
        if (text.getStyle() != null && text.getStyle().getClickEvent() instanceof ClickEvent.RunCommand runCmd) {
            sb.append(runCmd.command()).append('\n');
        }
        for (Component sibling : text.getSiblings()) {
            sb.append(extractClickCommands(sibling));
        }
        return sb.toString();
    }

    // Hardcoded § code -> ChatFormatting map (avoids byCode() which may not exist in all MC versions)
    private static final java.util.Map<Character, ChatFormatting> CODE_TO_FORMAT = new java.util.HashMap<>();
    // Color RGB -> § code map for textToFormattedString
    private static final java.util.Map<Integer, Character> COLOR_TO_CODE = new java.util.HashMap<>();
    static {
        CODE_TO_FORMAT.put('0', ChatFormatting.BLACK);
        CODE_TO_FORMAT.put('1', ChatFormatting.DARK_BLUE);
        CODE_TO_FORMAT.put('2', ChatFormatting.DARK_GREEN);
        CODE_TO_FORMAT.put('3', ChatFormatting.DARK_AQUA);
        CODE_TO_FORMAT.put('4', ChatFormatting.DARK_RED);
        CODE_TO_FORMAT.put('5', ChatFormatting.DARK_PURPLE);
        CODE_TO_FORMAT.put('6', ChatFormatting.GOLD);
        CODE_TO_FORMAT.put('7', ChatFormatting.GRAY);
        CODE_TO_FORMAT.put('8', ChatFormatting.DARK_GRAY);
        CODE_TO_FORMAT.put('9', ChatFormatting.BLUE);
        CODE_TO_FORMAT.put('a', ChatFormatting.GREEN);
        CODE_TO_FORMAT.put('b', ChatFormatting.AQUA);
        CODE_TO_FORMAT.put('c', ChatFormatting.RED);
        CODE_TO_FORMAT.put('d', ChatFormatting.LIGHT_PURPLE);
        CODE_TO_FORMAT.put('e', ChatFormatting.YELLOW);
        CODE_TO_FORMAT.put('f', ChatFormatting.WHITE);
        CODE_TO_FORMAT.put('k', ChatFormatting.OBFUSCATED);
        CODE_TO_FORMAT.put('l', ChatFormatting.BOLD);
        CODE_TO_FORMAT.put('m', ChatFormatting.STRIKETHROUGH);
        CODE_TO_FORMAT.put('n', ChatFormatting.UNDERLINE);
        CODE_TO_FORMAT.put('o', ChatFormatting.ITALIC);
        CODE_TO_FORMAT.put('r', ChatFormatting.RESET);
        // Build color code lookup
        int[] colorCodes = {0x000000,0x0000AA,0x00AA00,0x00AAAA,0xAA0000,0xAA00AA,0xFFAA00,0xAAAAAA,0x555555,0x5555FF,0x55FF55,0x55FFFF,0xFF5555,0xFF55FF,0xFFFF55,0xFFFFFF};
        char[] chars =    {'0','1','2','3','4','5','6','7','8','9','a','b','c','d','e','f'};
        for (int i = 0; i < colorCodes.length; i++) COLOR_TO_CODE.put(colorCodes[i], chars[i]);
    }
    // Color ChatFormatting set for quick lookup
    private static final java.util.Set<ChatFormatting> COLOR_FORMATS = new java.util.HashSet<>(java.util.Arrays.asList(
        ChatFormatting.BLACK, ChatFormatting.DARK_BLUE, ChatFormatting.DARK_GREEN, ChatFormatting.DARK_AQUA,
        ChatFormatting.DARK_RED, ChatFormatting.DARK_PURPLE, ChatFormatting.GOLD, ChatFormatting.GRAY,
        ChatFormatting.DARK_GRAY, ChatFormatting.BLUE, ChatFormatting.GREEN, ChatFormatting.AQUA,
        ChatFormatting.RED, ChatFormatting.LIGHT_PURPLE, ChatFormatting.YELLOW, ChatFormatting.WHITE
    ));

    public static String textToFormattedString(Component text) {
        StringBuilder sb = new StringBuilder();
        text.visit((style, string) -> {
            // Apply color
            if (style != null && style.getColor() != null) {
                int rgb = style.getColor().getValue() & 0xFFFFFF;
                Character c = COLOR_TO_CODE.get(rgb);
                if (c != null) sb.append('\u00A7').append(c);
            }
            // Apply decorations
            if (style != null) {
                if (style.isBold()) sb.append("\u00A7l");
                if (style.isItalic()) sb.append("\u00A7o");
                if (style.isUnderlined()) sb.append("\u00A7n");
                if (style.isStrikethrough()) sb.append("\u00A7m");
                if (style.isObfuscated()) sb.append("\u00A7k");
            }
            sb.append(string);
            sb.append("\u00A7r"); // Reset to prevent bleeding
            return java.util.Optional.empty();
        }, net.minecraft.network.chat.Style.EMPTY);
        return sb.toString();
    }

    public static MutableComponent formattedStringToText(String formatted) {
        MutableComponent result = Component.empty();
        StringBuilder current = new StringBuilder();
        ChatFormatting currentColor = null;
        boolean bold = false, italic = false, underline = false, strikethrough = false, obfuscated = false;

        for (int i = 0; i < formatted.length(); i++) {
            if (formatted.charAt(i) == '\u00A7' && i + 1 < formatted.length()) {
                // Flush current segment
                if (current.length() > 0) {
                    MutableComponent segment = Component.literal(current.toString());
                    applyStyle(segment, currentColor, bold, italic, underline, strikethrough, obfuscated);
                    result.append(segment);
                    current.setLength(0);
                }
                char code = Character.toLowerCase(formatted.charAt(i + 1));
                ChatFormatting fmt = CODE_TO_FORMAT.get(code);
                if (fmt != null) {
                    if (COLOR_FORMATS.contains(fmt)) {
                        currentColor = fmt;
                        bold = false; italic = false; underline = false; strikethrough = false; obfuscated = false;
                    } else if (fmt == ChatFormatting.RESET) {
                        currentColor = null;
                        bold = false; italic = false; underline = false; strikethrough = false; obfuscated = false;
                    } else if (fmt == ChatFormatting.BOLD) bold = true;
                    else if (fmt == ChatFormatting.ITALIC) italic = true;
                    else if (fmt == ChatFormatting.UNDERLINE) underline = true;
                    else if (fmt == ChatFormatting.STRIKETHROUGH) strikethrough = true;
                    else if (fmt == ChatFormatting.OBFUSCATED) obfuscated = true;
                }
                i++; // skip code char
            } else {
                current.append(formatted.charAt(i));
            }
        }
        // Flush remaining
        if (current.length() > 0) {
            MutableComponent segment = Component.literal(current.toString());
            applyStyle(segment, currentColor, bold, italic, underline, strikethrough, obfuscated);
            result.append(segment);
        }
        return result;
    }

    private static void applyStyle(MutableComponent text, ChatFormatting color, boolean bold, boolean italic, boolean underline, boolean strikethrough, boolean obfuscated) {
        Style s = Style.EMPTY;
        if (color != null) s = s.applyFormat(color);
        if (bold) s = s.applyFormat(ChatFormatting.BOLD);
        if (italic) s = s.applyFormat(ChatFormatting.ITALIC);
        if (underline) s = s.applyFormat(ChatFormatting.UNDERLINE);
        if (strikethrough) s = s.applyFormat(ChatFormatting.STRIKETHROUGH);
        if (obfuscated) s = s.applyFormat(ChatFormatting.OBFUSCATED);
        text.withStyle(s);
    }

    public static void handleTranslation(String msgId) {
        reloadConfig();
        Minecraft client = Minecraft.getInstance();

        String base64Message = MESSAGE_ID_TO_BASE64.get(msgId);
        if (base64Message == null) {
            // Fallback: treat msgId as raw base64 (for backwards compat)
            base64Message = msgId;
        }
        final String finalBase64 = base64Message;

        String decodedMessage;
        try {
            decodedMessage = new String(Base64.getDecoder().decode(finalBase64), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) { return; }

        if (config.chatTranslationProvider == ModConfig.TranslationProvider.GROQ && (config.groqApiKey == null || config.groqApiKey.isEmpty())) {
            client.execute(() -> { ChatComponent c = getChatComponent(client); if(c!=null) c.addClientSystemMessage(Component.translatable("npctranslator.error.config").withStyle(ChatFormatting.RED)); });
            return;
        }
        if (config.chatTranslationProvider == ModConfig.TranslationProvider.GEMINI && (config.geminiApiKey == null || config.geminiApiKey.isEmpty())) {
            client.execute(() -> { ChatComponent c = getChatComponent(client); if(c!=null) c.addClientSystemMessage(Component.translatable("npctranslator.error.config").withStyle(ChatFormatting.RED)); });
            return;
        }
        if (config.chatTranslationProvider == ModConfig.TranslationProvider.CUSTOM && !isCustomConfigured()) {
            client.execute(() -> { ChatComponent c = getChatComponent(client); if(c!=null) c.addClientSystemMessage(Component.translatable("npctranslator.error.custom_config").withStyle(ChatFormatting.RED)); });
            return;
        }

        String targetLangName;
        String displayLanguageCode;
        if (config.useGameLanguage) {
            String userLanguage = clientLanguage();
            displayLanguageCode = getEffectiveLanguageCode();
            targetLangName = "the language for locale code: " + displayLanguageCode + " (user locale: " + userLanguage + ")";
        } else {
            targetLangName = config.targetLanguage.englishName;
            displayLanguageCode = config.targetLanguage.code;
        }

        final String searchMarker = "translate_npc " + msgId;
        CompletableFuture.runAsync(() -> {
            String tempFormattedInput = decodedMessage;
            ClickEvent tempClickEvent = null;
            try {
                // Convert original message to §-coded string to preserve colors
                Component originalMsg = ORIGINAL_MESSAGES.get(msgId);
                if (originalMsg != null) {
                    tempFormattedInput = textToFormattedString(originalMsg);
                    // Extract click event
                    if (originalMsg.getStyle() != null && originalMsg.getStyle().getClickEvent() != null) {
                        tempClickEvent = originalMsg.getStyle().getClickEvent();
                    } else {
                        for (Component sibling : originalMsg.getSiblings()) {
                            if (sibling.getStyle() != null && sibling.getStyle().getClickEvent() != null) {
                                tempClickEvent = sibling.getStyle().getClickEvent();
                                break;
                            }
                        }
                    }
                }
                final String formattedInput = tempFormattedInput;
                final ClickEvent finalClickEvent = tempClickEvent;

                // Messages with several clickable options ([Yes] [No], dialogue choices) are translated
                // piece by piece so every option keeps its own click/hover action and position.
                List<ChatSegment> segments = originalMsg != null ? splitByEvents(originalMsg) : null;
                if (segments != null && countClickEvents(segments) > 1) {
                    MutableComponent body = Component.empty();
                    StringBuilder joined = new StringBuilder();
                    for (ChatSegment seg : segments) {
                        String tr = translateSegment(seg.formatted, displayLanguageCode, targetLangName, config.chatTranslationProvider);
                        joined.append(tr);
                        MutableComponent part = formattedStringToText(tr);
                        if (seg.click != null || seg.hover != null) {
                            final ClickEvent ce = seg.click;
                            final HoverEvent he = seg.hover;
                            part = Component.empty().append(part).withStyle(st -> st.withClickEvent(ce).withHoverEvent(he));
                        }
                        body.append(part);
                    }
                    com.npctranslator.chatbubble.ChatBubbleManager.onTranslationComplete(msgId, joined.toString());
                    MutableComponent prefix = Component.translatable("npctranslator.translated", displayLanguageCode.toUpperCase())
                        .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                            .withClickEvent(new ClickEvent.RunCommand("/revert_npc " + msgId))
                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("npctranslator.hover_revert"))));
                    MutableComponent translatedMessage = Component.empty().append(prefix).append(body).append(retranslateButton(msgId));
                    client.execute(() -> replaceMessageInChat(client, searchMarker, translatedMessage));
                    return;
                }

                String translatedText;
                CHAT_CONTEXT.set(npcContext(msgId));
                try {
                    translatedText = translateChatCached(formattedInput, displayLanguageCode, targetLangName, config.chatTranslationProvider);
                } finally {
                    CHAT_CONTEXT.remove();
                }
                com.npctranslator.chatbubble.ChatBubbleManager.onTranslationComplete(msgId, translatedText);

                if (config.enableTts) {
                    boolean isNpcMsg = formattedInput.contains("[NPC] ") || formattedInput.contains("NPC");
                    if (config.ttsMode == ModConfig.TtsMode.ALL_CHAT || config.ttsMode == ModConfig.TtsMode.TRANSLATED_ONLY || (config.ttsMode == ModConfig.TtsMode.NPC_ONLY && isNpcMsg)) {
                        speakText(translatedText);
                    }
                }

                MutableComponent prefix = Component.translatable("npctranslator.translated", displayLanguageCode.toUpperCase())
                    .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand("/revert_npc " + msgId))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("npctranslator.hover_revert"))));

                // Parse §-coded translated text back into colored Text
                MutableComponent translationContent = formattedStringToText(translatedText);
                if (finalClickEvent != null) {
                    translationContent.withStyle(s -> s.withClickEvent(finalClickEvent));
                }

                MutableComponent translatedMessage = Component.empty().append(prefix).append(translationContent).append(retranslateButton(msgId));
                client.execute(() -> replaceMessageInChat(client, searchMarker, translatedMessage));
            } catch (Exception e) {
                e.printStackTrace();
                notifyTranslationError(e);
            }
        });
    }

    // ── 中文增强版：用词典里已存的译文自动显示聊天消息 ─────────────────
    /** Translation built only from stored data (phrase book / dictionary), or null if anything is missing. */
    private static Component storedChatTranslation(Component text, String msgId) {
        ModConfig.TranslationProvider provider = config.chatTranslationProvider;
        if (provider == null) return null;
        String dictProvider = provider.name() + "_CHAT";
        MutableComponent body;
        List<ChatSegment> segments = splitByEvents(text);
        if (countClickEvents(segments) > 1) {
            body = Component.empty();
            boolean any = false;
            for (ChatSegment seg : segments) {
                String tr = seg.formatted;
                if (WORD.matcher(TranslationUtil.stripColorCodes(seg.formatted)).find()) {
                    tr = storedLine(seg.formatted.trim(), dictProvider);
                    if (tr == null) return null;
                    any = true;
                }
                MutableComponent part = formattedStringToText(tr);
                if (seg.click != null || seg.hover != null) {
                    final ClickEvent ce = seg.click;
                    final HoverEvent he = seg.hover;
                    part = Component.empty().append(part).withStyle(st -> st.withClickEvent(ce).withHoverEvent(he));
                }
                body.append(part);
            }
            if (!any) return null;
        } else {
            String tr = storedLine(textToFormattedString(text), dictProvider);
            if (tr == null) return null;
            body = formattedStringToText(tr);
            List<ClickEvent> clicks = new ArrayList<>();
            List<HoverEvent> hovers = new ArrayList<>();
            collectEvents(text, clicks, hovers);
            final ClickEvent ce = clicks.isEmpty() ? null : clicks.get(0);
            final HoverEvent he = hovers.isEmpty() ? null : hovers.get(0);
            if (ce != null || he != null) body = Component.empty().append(body).withStyle(st -> st.withClickEvent(ce).withHoverEvent(he));
        }
        String code = config.useGameLanguage ? getEffectiveLanguageCode() : config.targetLanguage.code;
        MutableComponent prefix = Component.translatable("npctranslator.translated", code.toUpperCase())
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent.RunCommand("/revert_npc " + msgId))
                        .withHoverEvent(new HoverEvent.ShowText(Component.translatable("npctranslator.hover_revert"))));
        return Component.empty().append(prefix).append(body).append(retranslateButton(msgId));
    }

    private static String storedLine(String formatted, String dictProvider) {
        TranslationUtil.Template t = config.normalizeNumbers ? TranslationUtil.normalize(formatted) : TranslationUtil.Template.raw(formatted);
        String tr = TranslationDictionary.get(t.text, dictProvider);
        if (tr != null && !tr.isEmpty() && TranslationUtil.placeholdersMatch(t.text, tr)) return TranslationUtil.restore(tr, t.numbers);
        return null;
    }

    // ── 中文增强版：指定清除译文并重新翻译 ─────────────────────────────
    private static MutableComponent retranslateButton(String msgId) {
        return Component.literal(" \u21bb").withStyle(st -> st.withColor(ChatFormatting.DARK_GRAY)
                .withClickEvent(new ClickEvent.RunCommand("/retranslate_npc " + msgId))
                .withHoverEvent(new HoverEvent.ShowText(Component.translatable("npctranslator.retranslate.hover"))));
    }

    /** Drop the stored translation of one chat message and translate it again. */
    public static void handleRetranslate(String msgId) {
        try {
            Component originalMsg = ORIGINAL_MESSAGES.get(msgId);
            if (originalMsg != null && config.chatTranslationProvider != null) {
                String dictProvider = config.chatTranslationProvider.name() + "_CHAT";
                forgetChatText(textToFormattedString(originalMsg), dictProvider);
                for (ChatSegment seg : splitByEvents(originalMsg)) forgetChatText(seg.formatted.trim(), dictProvider);
                TRANSLATION_MEMORY_CACHE.remove(originalMsg.getString());
                TranslationDictionary.save();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        handleTranslation(msgId);
    }

    private static void forgetChatText(String formatted, String dictProvider) {
        TranslationUtil.Template t = config.normalizeNumbers ? TranslationUtil.normalize(formatted) : TranslationUtil.Template.raw(formatted);
        TranslationDictionary.remove(t.text, dictProvider);
        TranslationDictionary.remove(formatted, dictProvider);
    }

    // ── 中文增强版：记住翻译过的物品（按物品名，不按物品数据哈希） ─────────
    private static String itemNameKey(String firstLine) {
        if (firstLine == null) return null;
        String f = firstLine.trim();
        if (f.isEmpty()) return null;
        return config.normalizeNumbers ? TranslationUtil.normalize(f).text : f;
    }

    private static void rememberItem(String firstLine, ModConfig.TranslationProvider provider) {
        String key = itemNameKey(firstLine);
        if (key == null || provider == null) return;
        if (!provider.name().equals(TranslationDictionary.get(key, "ITEM_SEEN"))) {
            TranslationDictionary.put(key, provider.name(), "ITEM_SEEN");
            TranslationDictionary.save();
        }
    }

    private static void unrememberItem(List<Component> tooltip) {
        if (tooltip == null || tooltip.isEmpty()) return;
        String key = itemNameKey(toFormattedString(tooltip.get(0)));
        if (key != null && TranslationDictionary.has(key, "ITEM_SEEN")) {
            TranslationDictionary.remove(key, "ITEM_SEEN");
            TranslationDictionary.save();
        }
    }

    private static ModConfig.TranslationProvider rememberedProvider(List<Component> tooltip) {
        if (tooltip == null || tooltip.isEmpty()) return null;
        String key = itemNameKey(toFormattedString(tooltip.get(0)));
        String v = key == null ? null : TranslationDictionary.get(key, "ITEM_SEEN");
        if (v == null) return null;
        try {
            return ModConfig.TranslationProvider.valueOf(v);
        } catch (Exception e) {
            return null;
        }
    }

    /** Items re-translated with Ctrl: the phrase book is skipped so the AI result replaces it. */
    private static final Set<String> FORCE_AI_ITEMS = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static void forgetTooltipLines(List<Component> tooltip, ModConfig.TranslationProvider provider) {
        for (Component line : tooltip) {
            String f = toFormattedString(line).trim();
            if (f.isEmpty()) continue;
            TranslationUtil.Template t = config.normalizeNumbers ? TranslationUtil.normalize(f) : TranslationUtil.Template.raw(f);
            TranslationDictionary.remove(t.text, provider.name());
        }
        TranslationDictionary.save();
        HudTranslator.clearCache();
    }

    private static boolean isCtrlDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return false;
        for (int code : new int[]{341, 345}) { // GLFW left / right control
            try {
                try {
                    java.lang.reflect.Method m = InputConstants.class.getMethod("isKeyDown", int.class);
                    if ((boolean) m.invoke(null, code)) return true;
                } catch (NoSuchMethodException e) {
                    java.lang.reflect.Method m = InputConstants.class.getMethod("isKeyDown", com.mojang.blaze3d.platform.Window.class, int.class);
                    if (client.getWindow() != null && (boolean) m.invoke(null, client.getWindow(), code)) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    /** Clears every in-memory translation cache (after dictionary entries were removed). */
    private static void clearRuntimeCaches() {
        HudTranslator.clearCache();
        ITEM_CACHE.clear(); ITEM_LINE_MAP.clear();
        ACTIVE_TRANSLATED_ITEMS.clear();
        TRANSLATION_MEMORY_CACHE.clear();
    }

    // ── 中文增强版：NPC 长对话被拆成多条消息时，带上前后文 ─────────────
    private record NpcLine(String id, long time, String speaker, String text) {}
    private static final Deque<NpcLine> RECENT_NPC_LINES = new ArrayDeque<>();
    private static final ThreadLocal<String> CHAT_CONTEXT = new ThreadLocal<>();

    private static void rememberNpcLine(String msgId, String rawText) {
        String plain = TranslationUtil.stripColorCodes(rawText).trim();
        int npc = plain.indexOf("[NPC] ");
        if (npc < 0) return;
        int colon = plain.indexOf(": ", npc);
        if (colon < 0) return;
        String speaker = plain.substring(npc + 6, colon).trim();
        String text = plain.substring(colon + 2).trim();
        if (text.isEmpty()) return;
        synchronized (RECENT_NPC_LINES) {
            RECENT_NPC_LINES.addLast(new NpcLine(msgId, System.currentTimeMillis(), speaker, text));
            while (RECENT_NPC_LINES.size() > 60) RECENT_NPC_LINES.removeFirst();
        }
    }

    /** Up to two lines before and after this one from the same NPC within 20 s, or null. */
    private static String npcContext(String msgId) {
        List<NpcLine> lines;
        synchronized (RECENT_NPC_LINES) {
            lines = new ArrayList<>(RECENT_NPC_LINES);
        }
        int idx = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).id.equals(msgId)) { idx = i; break; }
        }
        if (idx < 0) return null;
        NpcLine self = lines.get(idx);
        List<String> before = new ArrayList<>();
        for (int i = idx - 1; i >= 0 && before.size() < 2; i--) {
            NpcLine l = lines.get(i);
            if (!l.speaker.equals(self.speaker) || self.time - l.time > 20_000L) break;
            before.add(0, l.text);
        }
        List<String> after = new ArrayList<>();
        for (int i = idx + 1; i < lines.size() && after.size() < 2; i++) {
            NpcLine l = lines.get(i);
            if (!l.speaker.equals(self.speaker) || l.time - self.time > 20_000L) break;
            after.add(l.text);
        }
        if (before.isEmpty() && after.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (String b : before) sb.append("Previous line: ").append(b).append('\n');
        sb.append("Line to translate: ").append(self.text).append('\n');
        for (String a : after) sb.append("Next line: ").append(a).append('\n');
        return sb.toString().trim();
    }

    // ── 中文增强版：带多个点击选项的聊天消息按片段翻译 ─────────────────
    private record ChatSegment(String formatted, ClickEvent click, HoverEvent hover) {}

    /** Consecutive text pieces with the same click/hover action form one segment. */
    private static List<ChatSegment> splitByEvents(Component msg) {
        List<ChatSegment> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        ClickEvent[] click = {null};
        HoverEvent[] hover = {null};
        boolean[] started = {false};
        msg.visit((style, string) -> {
            if (string.isEmpty()) return java.util.Optional.empty();
            ClickEvent ce = style.getClickEvent();
            HoverEvent he = style.getHoverEvent();
            if (started[0] && (!Objects.equals(ce, click[0]) || !Objects.equals(he, hover[0]))) {
                out.add(new ChatSegment(cur.toString(), click[0], hover[0]));
                cur.setLength(0);
            }
            started[0] = true;
            click[0] = ce;
            hover[0] = he;
            appendFormatted(cur, style, string);
            return java.util.Optional.empty();
        }, Style.EMPTY);
        if (started[0]) out.add(new ChatSegment(cur.toString(), click[0], hover[0]));
        return out;
    }

    private static int countClickEvents(List<ChatSegment> segments) {
        List<ClickEvent> seen = new ArrayList<>();
        for (ChatSegment seg : segments) {
            if (seg.click != null && !seen.contains(seg.click)) seen.add(seg.click);
        }
        return seen.size();
    }

    /** Phrase book first, then the chat cache / AI. Keeps leading and trailing spaces. */
    private static String translateSegment(String formatted, String langCode, String langName, ModConfig.TranslationProvider provider) {
        String plain = TranslationUtil.stripColorCodes(formatted);
        if (!WORD.matcher(plain).find()) return formatted;
        String tr = null;
        try {
            if (config.usePhrasebook) tr = PhraseBook.translate(formatted.trim());
            if (tr == null) tr = translateChatCached(formatted.trim(), langCode, langName, provider);
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (tr == null || tr.isEmpty()) return formatted;
        String trPlain = TranslationUtil.stripColorCodes(tr);
        int lead = 0;
        while (lead < plain.length() && plain.charAt(lead) == ' ') lead++;
        int trail = 0;
        while (trail < plain.length() - lead && plain.charAt(plain.length() - 1 - trail) == ' ') trail++;
        if (lead > 0 && !trPlain.startsWith(" ")) tr = " ".repeat(lead) + tr;
        if (trail > 0 && !trPlain.endsWith(" ")) tr = tr + " ".repeat(trail);
        return tr;
    }

    private static void appendFormatted(StringBuilder sb, Style style, String string) {
        if (style != null && style.getColor() != null) {
            Character c = COLOR_TO_CODE.get(style.getColor().getValue() & 0xFFFFFF);
            if (c != null) sb.append('\u00A7').append(c);
        }
        if (style != null) {
            if (style.isBold()) sb.append("\u00A7l");
            if (style.isItalic()) sb.append("\u00A7o");
            if (style.isUnderlined()) sb.append("\u00A7n");
            if (style.isStrikethrough()) sb.append("\u00A7m");
            if (style.isObfuscated()) sb.append("\u00A7k");
        }
        sb.append(string);
        sb.append("\u00A7r");
    }

    // ── 中文增强版：辅助方法 ─────────────────────────────────────────
    /**
     * Reads choices[0].message.content from an OpenAI-style response. Some gateways (e.g. ClinePass)
     * wrap the whole object in {"data": {...}}, so unwrap that first.
     */
    static String extractChatContent(String rawBody) throws Exception {
        JsonObject root;
        try {
            root = GSON.fromJson(rawBody, JsonObject.class);
        } catch (Exception e) {
            throw new Exception("Custom API returned non-JSON: " + shorten(rawBody, 120));
        }
        if (root != null && !root.has("choices") && root.has("data") && root.get("data").isJsonObject()) {
            root = root.getAsJsonObject("data");
        }
        if (root == null || !root.has("choices") || !root.get("choices").isJsonArray() || root.getAsJsonArray("choices").size() == 0) {
            System.err.println("[NPCTranslator] Custom API unexpected response: " + shorten(rawBody, 300));
            String apiMsg = apiErrorMessage(rawBody);
            throw new Exception("Custom API returned no choices" + (apiMsg != null ? " (" + apiMsg + ")" : ""));
        }
        JsonObject first = root.getAsJsonArray("choices").get(0).getAsJsonObject();
        JsonObject message = first.has("message") && first.get("message").isJsonObject() ? first.getAsJsonObject("message") : null;
        JsonElement contentEl = message == null ? null : message.get("content");
        if (contentEl == null || contentEl.isJsonNull()) {
            throw new Exception("Custom API returned empty content");
        }
        return contentEl.getAsString();
    }

    /** error.message / message / error from an error body, or null. */
    static String apiErrorMessage(String rawBody) {
        try {
            JsonObject o = GSON.fromJson(rawBody, JsonObject.class);
            if (o == null) return null;
            if (o.has("data") && o.get("data").isJsonObject() && !o.has("error")) o = o.getAsJsonObject("data");
            JsonElement err = o.get("error");
            if (err != null && err.isJsonObject() && err.getAsJsonObject().has("message")) {
                return shorten(err.getAsJsonObject().get("message").getAsString(), 120);
            }
            if (err != null && err.isJsonPrimitive()) return shorten(err.getAsString(), 120);
            if (o.has("message") && o.get("message").isJsonPrimitive()) return shorten(o.get("message").getAsString(), 120);
        } catch (Exception ignored) {
        }
        return null;
    }

    private static String shorten(String s, int max) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > max ? s.substring(0, max) + "…" : s;
    }

    private static volatile long lastErrorNotice = 0;

    /** Red chat line when a translation fails, at most once every 10 s. */
    static void notifyTranslationError(Exception e) {
        long now = System.currentTimeMillis();
        if (now - lastErrorNotice < 10_000L) return;
        lastErrorNotice = now;
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        Minecraft client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.player != null) {
                client.player.sendSystemMessage(Component.literal("[翻译失败] " + shorten(msg, 160)).withStyle(ChatFormatting.RED));
            }
        });
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    /** Whether a provider has what it needs (API key / endpoint) to be called. */
    public static boolean isProviderUsable(ModConfig.TranslationProvider p) {
        ModConfig c = ModConfig.INSTANCE;
        if (p == null || c == null) return false;
        switch (p) {
            case GOOGLE: return true;
            case GEMINI: return !isBlank(c.geminiApiKey);
            case GROQ: return !isBlank(c.groqApiKey);
            case MISTRAL: return !isBlank(c.mistralApiKey);
            case OPENROUTER: return !isBlank(c.openRouterApiKey);
            case CUSTOM: return !isBlank(c.customBaseUrl) && !isBlank(c.customModel);
            default: return false;
        }
    }

    /** Same target-language wording the item translator uses. */
    public static String targetLanguageName(String langCode) {
        ModConfig c = ModConfig.INSTANCE;
        return c.useGameLanguage ? ("the language for locale code: " + langCode) : c.targetLanguage.englishName;
    }

    /**
     * Fixed (phrase-book) translation of a whole chat message, or null.
     * "[NPC] Name: text" keeps the speaker and translates the text after ": ".
     * Messages with more than one click action (e.g. [YES] [NO]) are left alone.
     */
    private static Component translateChatFixed(Component text) {
        if (!PhraseBook.isReady()) return null;
        List<ClickEvent> clicks = new ArrayList<>();
        List<HoverEvent> hovers = new ArrayList<>();
        collectEvents(text, clicks, hovers);
        if (clicks.size() > 1) return null;
        String formatted = textToFormattedString(text);
        String plain = TranslationUtil.stripColorCodes(formatted).trim();
        String result = null;
        if (plain.startsWith("[NPC] ")) {
            int idx = formatted.indexOf(": ");
            if (idx > 0) {
                String tb = PhraseBook.translate(formatted.substring(idx + 2));
                if (tb != null) result = formatted.substring(0, idx + 2) + tb;
            }
        } else {
            result = PhraseBook.translate(formatted);
        }
        Coverage.record(formatted, result != null, "聊天");
        if (result == null) return null;
        MutableComponent comp = formattedStringToText(result);
        final ClickEvent ce = clicks.isEmpty() ? null : clicks.get(0);
        final HoverEvent he = hovers.isEmpty() ? null : hovers.get(0);
        if (ce != null || he != null) {
            comp = Component.empty().append(comp).withStyle(st -> st.withClickEvent(ce).withHoverEvent(he));
        }
        return comp;
    }

    private static void collectEvents(Component c, List<ClickEvent> clicks, List<HoverEvent> hovers) {
        Style st = c.getStyle();
        if (st != null) {
            if (st.getClickEvent() != null && !clicks.contains(st.getClickEvent())) clicks.add(st.getClickEvent());
            if (st.getHoverEvent() != null && hovers.isEmpty()) hovers.add(st.getHoverEvent());
        }
        for (Component sib : c.getSiblings()) collectEvents(sib, clicks, hovers);
    }

    public static boolean isCustomConfigured() {
        return config != null && !isBlank(config.customBaseUrl) && !isBlank(config.customModel);
    }

    private static String buildSystemPrompt(boolean isTooltip, String langName, String content) {
        Map<String, String> hits = config.useGlossary
                ? Glossary.index().hits(content, 80)
                : Collections.emptyMap();
        String prompt = TranslationUtil.buildSystemPrompt(isTooltip, langName, config.customPrompt, hits);
        String menu = isTooltip ? MENU_CONTEXT.get() : null;
        if (menu != null) {
            prompt += "\nMENU:\nThese lines come from the in-game menu \"" + menu + "\". Use it to pick the right meaning.\n";
            if (menu.toLowerCase(Locale.ROOT).contains("bazaar")) {
                prompt += "In Bazaar menus use: Bazaar = 集市, Buy Order = 挂单收购, Sell Offer = 挂单出售, "
                        + "Instant Buy = 立即买入, Instant Sell = 立即卖出, products = 种商品.\n";
            }
        }
        String ctx = isTooltip ? null : CHAT_CONTEXT.get();
        if (ctx != null) {
            prompt += "\nSPLIT DIALOGUE:\nThis NPC line is one part of a longer speech that Hypixel split over several chat lines. "
                    + "The neighbouring lines are given ONLY so you understand the meaning; do NOT translate or output them. "
                    + "Translate only the given line, so that it reads naturally as that part of the whole sentence.\n"
                    + ctx + "\n";
        }
        return prompt;
    }

    /** Chat translation with number-normalised dictionary cache. */
    private static String translateChatCached(String formattedInput, String langCode, String langName, ModConfig.TranslationProvider provider) throws Exception {
        String dictProvider = provider.name() + "_CHAT";
        TranslationUtil.Template t = config.normalizeNumbers ? TranslationUtil.normalize(formattedInput) : TranslationUtil.Template.raw(formattedInput);
        String cached = TranslationDictionary.get(t.text, dictProvider);
        if (cached != null && !cached.isEmpty()) {
            return TranslationUtil.restore(cached, t.numbers);
        }
        String tr = getTranslatedText(t.text, langCode, langName, false, provider);
        if (tr != null && !tr.isEmpty() && TranslationUtil.placeholdersMatch(t.text, tr)) {
            if (!tr.equals(t.text)) {
                TranslationDictionary.put(t.text, tr, dictProvider);
                TranslationDictionary.save();
            }
            return TranslationUtil.restore(tr, t.numbers);
        }
        // The model mangled the {n} placeholders: translate the raw text instead (not cached).
        if (t.hasNumbers()) {
            return getTranslatedText(formattedInput, langCode, langName, false, provider);
        }
        return tr;
    }

    /** Translate a list of lines in one request ("ID|text" batch format). */
    public static Map<Integer, String> translateBatch(List<String> lines, String langCode, String targetLang, ModConfig.TranslationProvider provider) throws Exception {
        if (lines.isEmpty()) return new HashMap<>();
        String translatedContent = getTranslatedText(TranslationUtil.buildBatch(lines), langCode, targetLang, true, provider);
        return TranslationUtil.parseBatch(translatedContent, lines.size());
    }

    private static String getTranslatedText(String content, String langCode, String langName, boolean isTooltip, ModConfig.TranslationProvider provider) throws Exception {
        String result = getTranslatedTextRaw(content, langCode, langName, isTooltip, provider);
        if (provider != ModConfig.TranslationProvider.GOOGLE) {
            result = TranslationUtil.stripThink(result);
        }
        return result;
    }

    private static String getTranslatedTextRaw(String content, String langCode, String langName, boolean isTooltip, ModConfig.TranslationProvider provider) throws Exception {
        if (!aiAllowed()) {
            throw new IllegalStateException("当前译文来源不含 AI（设置 → 译文来源），未调用 AI");
        }
        if (provider == ModConfig.TranslationProvider.GOOGLE) {
            String cacheKey = langCode + ":" + content;
            String cached = TRANSLATION_MEMORY_CACHE.get(cacheKey);
            if (cached != null) return cached;

            String encoded = java.net.URLEncoder.encode(content, "UTF-8");
            String userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36";

            String[] urls = new String[] {
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=" + langCode + "&dt=t&q=" + encoded,
                "https://translate.googleapis.com/translate_a/single?client=dict-chrome-ex&sl=auto&tl=" + langCode + "&dt=t&q=" + encoded,
                "https://clients5.google.com/translate_a/t?client=dict-chrome-ex&sl=auto&tl=" + langCode + "&q=" + encoded
            };

            for (String url : urls) {
                try {
                    HttpRequest request = HttpRequest.newBuilder()
                            .uri(URI.create(url))
                            .header("User-Agent", userAgent)
                            .header("Accept", "*/*")
                            .timeout(java.time.Duration.ofSeconds(4))
                            .GET()
                            .build();

                    HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() == 200) {
                        String body = response.body();
                        if (url.contains("clients5.google.com")) {
                            try {
                                JsonElement el = GSON.fromJson(body, JsonElement.class);
                                if (el.isJsonArray()) {
                                    String res = el.getAsJsonArray().get(0).getAsString();
                                    TRANSLATION_MEMORY_CACHE.put(cacheKey, res);
                                    return res;
                                } else if (el.isJsonPrimitive()) {
                                    String res = el.getAsString();
                                    TRANSLATION_MEMORY_CACHE.put(cacheKey, res);
                                    return res;
                                }
                            } catch (Exception ignored) {}
                        } else {
                            JsonArray jsonArray = GSON.fromJson(body, JsonArray.class);
                            JsonArray sentences = jsonArray.get(0).getAsJsonArray();
                            StringBuilder sb = new StringBuilder();
                            for (int i = 0; i < sentences.size(); i++) {
                                sb.append(sentences.get(i).getAsJsonArray().get(0).getAsString());
                            }
                            String res = sb.toString();
                            TRANSLATION_MEMORY_CACHE.put(cacheKey, res);
                            return res;
                        }
                    }
                } catch (Exception ignored) {}
            }

            // Fallback 4: MyMemory free translation API
            try {
                String myMemoryUrl = "https://api.mymemory.translated.net/get?q=" + encoded + "&langpair=auto|" + langCode;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(myMemoryUrl))
                        .header("User-Agent", userAgent)
                        .timeout(java.time.Duration.ofSeconds(4))
                        .GET()
                        .build();
                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject obj = GSON.fromJson(response.body(), JsonObject.class);
                    if (obj.has("responseData")) {
                        String res = obj.getAsJsonObject("responseData").get("translatedText").getAsString();
                        if (res != null && !res.isEmpty()) {
                            TRANSLATION_MEMORY_CACHE.put(cacheKey, res);
                            return res;
                        }
                    }
                }
            } catch (Exception ignored) {}

            throw new Exception("Google Translate failed. (All mirror endpoints rate-limited)");
        } else if (provider == ModConfig.TranslationProvider.GROQ) {
            ModConfig.GroqModel[] allModels = ModConfig.GroqModel.values();
            int startIndex = config.groqModel.ordinal();
            int attempts = config.autoFallbackOnLimit ? allModels.length : 1;
            
            for (int i = 0; i < attempts; i++) {
                ModConfig.GroqModel currentModel = allModels[(startIndex + i) % allModels.length];
                JsonObject jsonBody = new JsonObject();
                jsonBody.addProperty("model", currentModel.modelId);
                JsonArray messages = new JsonArray();
                JsonObject systemMessage = new JsonObject();
                systemMessage.addProperty("role", "system");
                systemMessage.addProperty("content", buildSystemPrompt(isTooltip, langName, content));
                messages.add(systemMessage);
                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", content);
                messages.add(userMessage);
                jsonBody.add("messages", messages);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.groq.com/openai/v1/chat/completions"))
                        .header("Authorization", "Bearer " + config.groqApiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonBody)))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject responseJson = GSON.fromJson(response.body(), JsonObject.class);
                    return responseJson.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
                } else if (response.statusCode() == 429 && i < attempts - 1) {
                    continue; // Limit aşıldı, sıradaki modele geç
                }
                
                if (i == attempts - 1) {
                    throw new Exception("Groq API failed. Status: " + response.statusCode());
                }
            }
        } else if (provider == ModConfig.TranslationProvider.GEMINI) {
            JsonObject jsonBody = new JsonObject();
            
            JsonObject systemInstruction = new JsonObject();
            JsonObject partsObjSys = new JsonObject();
            partsObjSys.addProperty("text", buildSystemPrompt(isTooltip, langName, content));
            JsonArray sysParts = new JsonArray();
            sysParts.add(partsObjSys);
            systemInstruction.add("parts", sysParts);
            jsonBody.add("systemInstruction", systemInstruction);

            JsonArray contents = new JsonArray();
            JsonObject contentObj = new JsonObject();
            contentObj.addProperty("role", "user");
            JsonArray parts = new JsonArray();
            JsonObject textPart = new JsonObject();
            textPart.addProperty("text", content);
            parts.add(textPart);
            contentObj.add("parts", parts);
            contents.add(contentObj);
            
            jsonBody.add("contents", contents);

            JsonArray safetySettings = new JsonArray();
            String[] categories = {"HARM_CATEGORY_HARASSMENT", "HARM_CATEGORY_HATE_SPEECH", "HARM_CATEGORY_SEXUALLY_EXPLICIT", "HARM_CATEGORY_DANGEROUS_CONTENT"};
            for (String cat : categories) {
                JsonObject setting = new JsonObject();
                setting.addProperty("category", cat);
                setting.addProperty("threshold", "BLOCK_NONE");
                safetySettings.add(setting);
            }
            jsonBody.add("safetySettings", safetySettings);

            ModConfig.GeminiModel[] allModels = ModConfig.GeminiModel.values();
            int startIndex = config.geminiModel.ordinal();
            int attempts = config.autoFallbackOnLimit ? allModels.length : 1;

            for (int i = 0; i < attempts; i++) {
                ModConfig.GeminiModel currentModel = allModels[(startIndex + i) % allModels.length];
                String url = "https://generativelanguage.googleapis.com/v1beta/models/" + currentModel.modelId + ":generateContent?key=" + config.geminiApiKey;
                
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonBody)))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject responseJson = GSON.fromJson(response.body(), JsonObject.class);
                    return responseJson.getAsJsonArray("candidates").get(0).getAsJsonObject().getAsJsonObject("content").getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
                } else if (response.statusCode() == 429 && i < attempts - 1) {
                    continue; // Limit aşıldı, sıradaki modele geç
                }

                if (i == attempts - 1) {
                    throw new Exception("Gemini API failed. Status: " + response.statusCode());
                }
            }
        } else if (provider == ModConfig.TranslationProvider.MISTRAL) {
            ModConfig.MistralModel[] allModels = ModConfig.MistralModel.values();
            int startIndex = config.mistralModel.ordinal();
            int attempts = config.autoFallbackOnLimit ? allModels.length : 1;

            for (int i = 0; i < attempts; i++) {
                ModConfig.MistralModel currentModel = allModels[(startIndex + i) % allModels.length];
                JsonObject jsonBody = new JsonObject();
                jsonBody.addProperty("model", currentModel.modelId);
                JsonArray messages = new JsonArray();
                JsonObject systemMessage = new JsonObject();
                systemMessage.addProperty("role", "system");
                systemMessage.addProperty("content", buildSystemPrompt(isTooltip, langName, content));
                messages.add(systemMessage);
                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", content);
                messages.add(userMessage);
                jsonBody.add("messages", messages);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://api.mistral.ai/v1/chat/completions"))
                        .header("Authorization", "Bearer " + config.mistralApiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonBody)))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject responseJson = GSON.fromJson(response.body(), JsonObject.class);
                    return responseJson.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
                } else if (response.statusCode() == 429 && i < attempts - 1) {
                    continue; // Limit aşıldı, sıradaki modele geç
                }
                
                if (i == attempts - 1) {
                    throw new Exception("Mistral API failed. Status: " + response.statusCode());
                }
            }
        } else if (provider == ModConfig.TranslationProvider.OPENROUTER) {
            ModConfig.OpenRouterModel[] allModels = ModConfig.OpenRouterModel.values();
            int startIndex = config.openRouterModel.ordinal();
            int attempts = config.autoFallbackOnLimit ? allModels.length : 1;

            for (int i = 0; i < attempts; i++) {
                ModConfig.OpenRouterModel currentModel = allModels[(startIndex + i) % allModels.length];
                JsonObject jsonBody = new JsonObject();
                jsonBody.addProperty("model", currentModel.modelId);
                
                // Extra setting recommended by OpenRouter documentation for routing
                JsonObject httpReferer = new JsonObject();
                httpReferer.addProperty("HTTP-Referer", "https://github.com/knutolof06/hypixel-skyblock-npc-and-items-translate");
                httpReferer.addProperty("X-Title", "Minecraft NPC Translator Mod");
                
                JsonArray messages = new JsonArray();
                JsonObject systemMessage = new JsonObject();
                systemMessage.addProperty("role", "system");
                systemMessage.addProperty("content", buildSystemPrompt(isTooltip, langName, content));
                messages.add(systemMessage);
                JsonObject userMessage = new JsonObject();
                userMessage.addProperty("role", "user");
                userMessage.addProperty("content", content);
                messages.add(userMessage);
                jsonBody.add("messages", messages);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create("https://openrouter.ai/api/v1/chat/completions"))
                        .header("Authorization", "Bearer " + config.openRouterApiKey)
                        .header("Content-Type", "application/json")
                        .header("HTTP-Referer", "https://github.com/knutolof06/hypixel-skyblock-npc-and-items-translate")
                        .header("X-Title", "Minecraft NPC Translator Mod")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonBody)))
                        .build();

                HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    JsonObject responseJson = GSON.fromJson(response.body(), JsonObject.class);
                    return responseJson.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
                } else if (response.statusCode() == 429 && i < attempts - 1) {
                    continue; // Limit aşıldı, sıradaki modele geç
                }
                
                if (i == attempts - 1) {
                    throw new Exception("OpenRouter API failed. Status: " + response.statusCode());
                }
            }
        } else if (provider == ModConfig.TranslationProvider.CUSTOM) {
            if (!isCustomConfigured()) {
                throw new Exception("Custom API not configured. Status: 401");
            }
            JsonObject jsonBody = new JsonObject();
            jsonBody.addProperty("model", config.customModel.trim());
            if (config.customTemperature >= 0) {
                jsonBody.addProperty("temperature", config.customTemperature);
            }
            jsonBody.addProperty("stream", false);
            JsonArray messages = new JsonArray();
            JsonObject systemMessage = new JsonObject();
            systemMessage.addProperty("role", "system");
            systemMessage.addProperty("content", buildSystemPrompt(isTooltip, langName, content));
            messages.add(systemMessage);
            JsonObject userMessage = new JsonObject();
            userMessage.addProperty("role", "user");
            userMessage.addProperty("content", content);
            messages.add(userMessage);
            jsonBody.add("messages", messages);

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(TranslationUtil.chatCompletionsUrl(config.customBaseUrl)))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(Math.max(5, config.customTimeoutSeconds)))
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(jsonBody)));
            if (!isBlank(config.customApiKey)) {
                builder.header("Authorization", "Bearer " + config.customApiKey.trim());
            }

            HttpResponse<String> response = HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            String rawBody = response.body() == null ? "" : response.body();
            if (response.statusCode() == 200) {
                return extractChatContent(rawBody);
            }
            String body = rawBody.length() > 300 ? rawBody.substring(0, 300) : rawBody;
            System.err.println("[NPCTranslator] Custom API error " + response.statusCode() + ": " + body);
            String apiMsg = apiErrorMessage(rawBody);
            throw new Exception("Custom API failed. Status: " + response.statusCode() + (apiMsg != null ? " (" + apiMsg + ")" : ""));
        }
        return content;
    }

    public static void handleRevert(String msgId, String unused) {
        Minecraft client = Minecraft.getInstance();
        try {
            Component originalMessage = ORIGINAL_MESSAGES.get(msgId);
            if (originalMessage == null) {
                // Fallback
                String base64 = MESSAGE_ID_TO_BASE64.get(msgId);
                if (base64 != null) {
                    String originalText = new String(Base64.getDecoder().decode(base64), java.nio.charset.StandardCharsets.UTF_8);
                    originalMessage = Component.literal(originalText);
                } else {
                    return;
                }
            }

            MutableComponent translateButton = Component.translatable("npctranslator.button")
                    .withStyle(style -> style.withColor(ChatFormatting.AQUA)
                            .withClickEvent(new ClickEvent.RunCommand("/translate_npc " + msgId))
                            .withHoverEvent(new HoverEvent.ShowText(Component.translatable("npctranslator.hover"))));

            Component finalMessage = originalMessage;
            MutableComponent fullOriginal;
            if (!config.showTranslateButton) {
                fullOriginal = Component.empty().append(finalMessage.copy());
            } else if (config.buttonPosition == ModConfig.ButtonPosition.END) {
                fullOriginal = Component.empty().append(finalMessage.copy()).append(" ").append(translateButton);
            } else {
                fullOriginal = Component.empty().append(translateButton).append(" ").append(finalMessage.copy());
            }
            String searchMarker = "revert_npc " + msgId;
            client.execute(() -> replaceMessageInChat(client, searchMarker, fullOriginal));

        } catch (Exception e) {}
    }

    public static void handleItemTooltip(ItemStack stack, List<Component> tooltip) {
        if (!config.enabled) return;
        Minecraft client = Minecraft.getInstance();
        if (client == null) return;
        
        String cacheKey = itemCacheKey(stack);

        if (config.translateModTooltips) translateForeignLines(stack, tooltip);
        handleTooltipStateAndRender(tooltip, cacheKey);
        appendNameSubtitle(stack, tooltip);
        appendEnchantHints(tooltip);

        Set<String> known = new HashSet<>();
        for (Component c : tooltip) known.add(c.getString());
        lastItemLines = known;
        lastItemLinesAt = System.currentTimeMillis();
    }

    /**
     * Cache key of an item = what the player sees (item type, name, lore), not its hidden data:
     * three identical items with different internal ids share one translation.
     */
    static String itemCacheKey(ItemStack stack) {
        int h;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append(toFormattedString(stack.getHoverName()));
            net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (lore != null) for (Component l : lore.lines()) sb.append('\n').append(toFormattedString(l));
            // numbers left out: Bazaar prices and countdowns change all the time, the item stays the same
            h = TranslationUtil.normalize(sb.toString()).text.hashCode();
        } catch (Exception e) {
            h = stack.getComponents() != null ? stack.getComponents().hashCode() : 0;
        }
        String menu = currentMenu();
        return stack.getItem().toString() + "_" + Integer.toHexString(h) + "_" + getEffectiveLanguageCode()
                + (menu == null ? "" : "@" + menu);
    }

    // ── 中文增强版：译文来源 ───────────────────────────────────────────
    private static volatile ModConfig.TranslationSource dataSource = null;

    public static ModConfig.TranslationSource source() {
        ModConfig c = ModConfig.INSTANCE;
        return c == null || c.translationSource == null ? ModConfig.TranslationSource.TABLE_ONLY : c.translationSource;
    }

    /** AI translation (and its saved results) is used only with the "table + corpus + AI" source. */
    public static boolean aiAllowed() {
        return source() == ModConfig.TranslationSource.FULL;
    }

    /** SkyBlockZH community corpus and the extra local rules. */
    public static boolean corpusAllowed() {
        return source() != ModConfig.TranslationSource.TABLE_ONLY;
    }

    /** Source the built-in data was last loaded for. */
    public static ModConfig.TranslationSource loadedSource() {
        return dataSource != null ? dataSource : source();
    }

    /** Rebuild glossary and phrase book after the translation source changed. */
    public static void reloadData() {
        dataSource = source();
        Glossary.invalidate();
        PhraseBook.loadAsync();
        clearRuntimeCaches();
        Coverage.reset();
    }

    // ── 中文增强版：按菜单分类保存译文 ──────────────────────────────────
    /** Menu given to the AI as context while a batch from that menu is translated. */
    public static final ThreadLocal<String> MENU_CONTEXT = new ThreadLocal<>();

    /** Title of the open container menu ("Bazaar ➜ Mining"), numbers folded; null outside menus. */
    /** Whether game text is being recorded (setting "收集游戏原文"). */
    public static boolean collectEnabled() {
        return config == null || config.collectMenuText;
    }

    public static String currentMenu() {
        try {
            return menuKey(getScreen(Minecraft.getInstance()));
        } catch (Throwable t) {
            return null;
        }
    }

    static String menuKey(Screen s) {
        if (!(s instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>)
                || s instanceof net.minecraft.client.gui.screens.inventory.InventoryScreen
                || s instanceof net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen) return null;
        String t = TranslationUtil.stripColorCodes(s.getTitle().getString())
                .replaceAll("\\d+", "#").replace(':', '：').replace('@', '＠').replaceAll("\\s+", " ").trim();
        if (t.isEmpty()) return null;
        return t.length() > 60 ? t.substring(0, 60) : t;
    }

    /** Dictionary namespace: per menu when inside one ("CUSTOM@Bazaar ➜ Mining"), else the provider. */
    public static String dictNs(ModConfig.TranslationProvider provider, String menu) {
        return menu == null ? provider.name() : provider.name() + "@" + menu;
    }

    /** This menu's translation first, then the one shared by all menus. */
    public static String dictGet(String template, ModConfig.TranslationProvider provider, String menu) {
        if (menu != null) {
            String s = TranslationDictionary.get(template, dictNs(provider, menu));
            if (s != null && !s.isEmpty()) return s;
        }
        return TranslationDictionary.get(template, provider.name());
    }

    // ── 中文增强版：整页翻译 ─────────────────────────────────────────
    /** Set while we read tooltips ourselves (page translation): the item hook must not touch them. */
    public static final ThreadLocal<Boolean> COLLECTING = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static KeyMapping keyTranslatePage;
    private static KeyMapping keyReport;
    private static boolean lastReportState = false;

    private static void report(Minecraft client, String note) {
        if (client.player == null) return;
        String name = TranslationReport.write(note);
        if (name == null) {
            client.player.sendSystemMessage(Component.literal("[翻译反馈] 还没有可记录的物品：先把鼠标停在有问题的物品上，再按 F8 或输入 /translate report").withStyle(ChatFormatting.RED));
        } else {
            client.player.sendSystemMessage(Component.literal("[翻译反馈] 已记录「" + name + "」的原文与显示内容 → config/" + TranslationReport.FILE.getName()).withStyle(ChatFormatting.GREEN));
        }
    }
    private static boolean lastPageState = false;
    private static boolean lastMenuState = false;
    private static volatile boolean pageRunning = false;
    private static int pageScanTick = 0;
    private static int lastPageSignature = 0;
    private static final Set<String> PAGE_EMPTY = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 整页翻译进度：画在菜单左上角，翻完前一直显示（关掉菜单再打开也还在）
    private static volatile int pageItems = 0;
    private static final java.util.concurrent.atomic.AtomicInteger pageLinesDone = new java.util.concurrent.atomic.AtomicInteger();
    private static volatile int pageLinesTotal = 0;
    private static volatile String pageResult = null;
    private static volatile int pageResultColor = 0xFF55FF55;
    private static volatile long pageResultUntil = 0;

    private static void pageFinished(String text, int color, long millis) {
        pageResult = text;
        pageResultColor = color;
        pageResultUntil = System.currentTimeMillis() + millis;
    }

    /** Progress box for the page translation, drawn over container screens. */
    private static void drawPageProgress(net.minecraft.client.gui.GuiGraphicsExtractor g) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.font == null) return;
        String text;
        int color;
        float frac = -1f;
        if (pageRunning) {
            int total = pageLinesTotal, done = Math.min(pageLinesDone.get(), total);
            if (total > 0) {
                text = "整页翻译中 · " + pageItems + " 个物品 · " + done + "/" + total + " 句";
                frac = done / (float) total;
            } else {
                text = "整页翻译中 · " + pageItems + " 个物品";
            }
            color = 0xFFFFFF55;
        } else if (pageResult != null && System.currentTimeMillis() < pageResultUntil) {
            text = pageResult;
            color = pageResultColor;
        } else {
            return;
        }
        int w = mc.font.width(text);
        int x = 4, y = 4;
        g.fill(x - 3, y - 3, x + w + 3, y + 9 + (frac >= 0 ? 5 : 2), 0xC0000000);
        g.text(mc.font, text, x, y, color, true);
        if (frac >= 0) {
            g.fill(x, y + 10, x + w, y + 12, 0xFF404040);
            g.fill(x, y + 10, x + Math.round(w * frac), y + 12, 0xFF55FF55);
        }
    }

    private static ModConfig.TranslationProvider pageProvider() {
        if (config.enableCustomItem && isCustomConfigured()) return ModConfig.TranslationProvider.CUSTOM;
        ModConfig.TranslationProvider p = config.autoItemProvider != null ? config.autoItemProvider : ModConfig.TranslationProvider.CUSTOM;
        return isProviderUsable(p) ? p : null;
    }

    private static void tickPageTranslation(Minecraft client) {
        boolean reportDown = keyReport != null && isKeyCurrentlyDown(keyReport);
        if (reportDown && !lastReportState) report(client, null);
        lastReportState = reportDown;
        boolean down = isKeyCurrentlyDown(keyTranslatePage);
        boolean pressed = down && !lastPageState;
        lastPageState = down;
        // text collection does not depend on any translation setting
        if (config != null && collectEnabled() && ++collectTick % 20 == 0) {
            try {
                if (getScreen(client) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> cs) collectMenu(client, cs);
            } catch (Throwable t) {
                HudTranslator.debugLog("menu collect: " + t);
            }
        }
        if (config == null || !config.enabled) return;
        boolean scan = config.autoTranslatePage && ++pageScanTick % 10 == 0;
        if (!pressed && !scan) return;
        if (!(getScreen(client) instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen)
                || client.player == null || client.level == null) {
            lastPageSignature = 0;
            return;
        }
        if (pressed && !(screen.getFocused() instanceof net.minecraft.client.gui.components.EditBox)) {
            if ((isShiftDown() || isCtrlDown()) && !pageRunning) {
                // Shift/Ctrl + M: drop this menu's saved translations and translate the page again
                String menu = menuKey(screen);
                int n = menu == null ? 0 : TranslationDictionary.removeMenuExact(menu);
                String suffix = menu == null ? "" : "@" + menu;
                for (String key : new ArrayList<>(ITEM_CACHE.keySet())) {
                    if (menu != null && key.endsWith(suffix)) {
                        ITEM_CACHE.remove(key); ITEM_LINE_MAP.remove(key);
                        ACTIVE_TRANSLATED_ITEMS.remove(key); TRANSLATED_PROVIDER_CACHE.remove(key);
                    }
                }
                PAGE_EMPTY.clear();
                HudTranslator.debugLog("page re-translate: " + menu + ", removed " + n);
            }
            translatePage(client, screen, true);
            return;
        }
        if (scan) translatePage(client, screen, false);
    }

    /**
     * Hovered items are recorded whether or not they get translated (item translation by key only,
     * shown in English with Z, ...). Already-seen lines cost one set lookup.
     */
    public static void collectTooltip(List<Component> tooltip) {
        if (!collectEnabled() || tooltip == null || tooltip.isEmpty()) return;
        try {
            Set<String> seen = new HashSet<>();
            for (Component c : tooltip) seen.add(c.getString());
            lastCollectedItemLines = seen;
            lastCollectedItemAt = System.currentTimeMillis();
            String menu = currentMenu();
            String where = menu != null ? menu : "背包";
            for (int i = 0; i < tooltip.size(); i++) {
                String f = toFormattedString(tooltip.get(i)).trim();
                if (!f.isEmpty()) TextCollector.record(i == 0 ? TextCollector.ITEM_NAME : TextCollector.ITEM_LINE, where, f, null);
            }
        } catch (Throwable ignored) {
        }
    }

    private static volatile Set<String> lastCollectedItemLines = Collections.emptySet();
    private static volatile long lastCollectedItemAt = 0;

    /** Tooltip lines drawn on screen that are not the hovered item's own lines (other mods' text). */
    private static void collectRenderedTooltip(List<Component> components) {
        if (components == null || components.isEmpty() || !collectEnabled()) return;
        try {
            Set<String> item = System.currentTimeMillis() - lastCollectedItemAt < 250 ? lastCollectedItemLines : Collections.emptySet();
            for (Component line : components) {
                if (MOD_TRANSLATED.contains(line)) continue;
                if (!HudTranslator.isGrid(line) && item.contains(line.getString())) continue;
                HudTranslator.collectModText(line);
            }
        } catch (Throwable ignored) {
        }
    }

    private static int collectTick = 0;
    private static int lastCollectSignature = 0;

    /** Writes the names and descriptions of the items in an open menu to the menu text file. */
    private static void collectMenu(Minecraft client, net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen) {
        String menu = menuKey(screen);
        if (menu == null || client.player == null || client.level == null) return;
        int signature = menu.hashCode();
        List<net.minecraft.world.inventory.Slot> slots = new ArrayList<>();
        for (net.minecraft.world.inventory.Slot slot : screen.getMenu().slots) {
            if (!slot.hasItem() || slot.container instanceof net.minecraft.world.entity.player.Inventory) continue;
            slots.add(slot);
            signature = signature * 31 + itemCacheKey(slot.getItem()).hashCode();
        }
        if (slots.isEmpty() || signature == lastCollectSignature) return;
        lastCollectSignature = signature;
        TextCollector.record(TextCollector.MENU_TITLE, menu, screen.getTitle());
        net.minecraft.world.item.Item.TooltipContext ctx = net.minecraft.world.item.Item.TooltipContext.of(client.level);
        for (net.minecraft.world.inventory.Slot slot : slots) {
            List<Component> tooltip;
            COLLECTING.set(Boolean.TRUE);
            try {
                tooltip = slot.getItem().getTooltipLines(ctx, client.player, net.minecraft.world.item.TooltipFlag.NORMAL);
            } catch (Throwable t) {
                continue;
            } finally {
                COLLECTING.set(Boolean.FALSE);
            }
            for (int i = 0; i < tooltip.size(); i++) {
                String f = toFormattedString(tooltip.get(i)).trim();
                if (f.isEmpty()) continue;
                String current = i == 0 ? TableNames.translateName(f) : null;
                if (current == null) current = PhraseBook.translate(f);
                TextCollector.record(i == 0 ? TextCollector.ITEM_NAME : TextCollector.ITEM_LINE, menu, f, current);
            }
        }
    }

    private static void pageMessage(Minecraft client, String text, ChatFormatting color) {
        if (client.player == null) return;
        Component c = Component.literal(text).withStyle(color);
        try {
            client.player.sendOverlayMessage(c);
        } catch (Throwable t) {
            client.player.sendSystemMessage(c);
        }
    }

    /** Collect every item of the open menu and translate all of them with a few batched requests. */
    private static void translatePage(Minecraft client, net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?> screen, boolean manual) {
        final String menu = menuKey(screen);
        if (pageRunning) {
            if (manual) pageMessage(client, "[整页翻译] 上一页还在翻译中…", ChatFormatting.YELLOW);
            return;
        }
        ModConfig.TranslationProvider provider = pageProvider();
        if (provider == null) {
            if (manual) pageFinished("整页翻译：没有可用的翻译接口，请先在设置里填自定义 API", 0xFFFF5555, 5000);
            return;
        }
        List<net.minecraft.world.inventory.Slot> slots = new ArrayList<>();
        for (net.minecraft.world.inventory.Slot slot : screen.getMenu().slots) {
            if (slot.hasItem() && !(slot.container instanceof net.minecraft.world.entity.player.Inventory)) slots.add(slot);
        }
        if (slots.isEmpty()) {
            // the player's own inventory screen: translate the inventory instead
            for (net.minecraft.world.inventory.Slot slot : screen.getMenu().slots) if (slot.hasItem()) slots.add(slot);
        }
        Map<String, List<String>> items = new LinkedHashMap<>();
        int signature = 1;
        for (net.minecraft.world.inventory.Slot slot : slots) {
            ItemStack stack = slot.getItem();
            String key = itemCacheKey(stack);
            signature = signature * 31 + key.hashCode();
            if (items.containsKey(key) || REVERTED_ITEMS.contains(key) || PENDING_ITEMS.contains(key) || PAGE_EMPTY.contains(key)) continue;
            if (ACTIVE_TRANSLATED_ITEMS.contains(key) && ITEM_CACHE.containsKey(key)) continue;
            items.put(key, null);
        }
        if (!manual && signature == lastPageSignature) return;
        lastPageSignature = signature;
        if (items.isEmpty()) {
            if (manual) pageFinished("整页翻译：这一页都翻译过了", 0xFF55FF55, 2500);
            return;
        }
        net.minecraft.world.item.Item.TooltipContext ctx = net.minecraft.world.item.Item.TooltipContext.of(client.level);
        net.minecraft.world.item.TooltipFlag flag = client.options.advancedItemTooltips
                ? net.minecraft.world.item.TooltipFlag.ADVANCED : net.minecraft.world.item.TooltipFlag.NORMAL;
        for (net.minecraft.world.inventory.Slot slot : slots) {
            ItemStack stack = slot.getItem();
            String key = itemCacheKey(stack);
            if (!items.containsKey(key) || items.get(key) != null) continue;
            List<Component> tooltip;
            COLLECTING.set(Boolean.TRUE);
            try {
                tooltip = stack.getTooltipLines(ctx, client.player, flag);
            } catch (Throwable t) {
                tooltip = List.of();
            } finally {
                COLLECTING.set(Boolean.FALSE);
            }
            List<String> lines = new ArrayList<>(tooltip.size());
            boolean any = false;
            for (Component c : tooltip) {
                String f = toFormattedString(c).trim();
                lines.add(f);
                if (!any && !f.isEmpty() && TranslationUtil.needsTranslation(f)) any = true;
            }
            if (any) items.put(key, lines);
        }
        items.values().removeIf(java.util.Objects::isNull);
        if (items.isEmpty()) return;

        pageItems = items.size();
        pageLinesTotal = 0;
        pageLinesDone.set(0);
        pageResult = null;
        for (String key : items.keySet()) {
            PENDING_ITEMS.add(key);
            ACTIVE_TRANSLATED_ITEMS.add(key);
            TRANSLATED_PROVIDER_CACHE.put(key, provider);
            REVERTED_ITEMS.remove(key);
        }
        pageRunning = true;
        CompletableFuture.runAsync(() -> {
            int done = 0;
            try {
                String providerName = provider.name();
                Map<String, ItemLines.Plan> plans = new LinkedHashMap<>();
                LinkedHashSet<String> missing = new LinkedHashSet<>();
                for (Map.Entry<String, List<String>> e : items.entrySet()) {
                    ItemLines.Plan plan = ItemLines.plan(e.getValue(), config.usePhrasebook);
                    plans.put(e.getKey(), plan);
                    ItemLines.collectMissing(plan, provider, menu, missing);
                }
                pageLinesTotal = missing.size();
                if (!missing.isEmpty()) ItemLines.translateMissing(missing, provider, 25, 3, pageLinesDone::addAndGet, menu);
                for (Map.Entry<String, ItemLines.Plan> e : plans.entrySet()) {
                    String key = e.getKey();
                    ItemLines.Result r = ItemLines.assemble(e.getValue(), provider, null, menu);
                    if (r.changed() && !r.tooltip().isEmpty()) {
                        ITEM_LINE_MAP.put(key, r.lineMap());
                        ITEM_CACHE.put(key, r.tooltip());
                        rememberItem(e.getValue().lines.get(0), provider);
                        done++;
                    } else {
                        ACTIVE_TRANSLATED_ITEMS.remove(key);
                        TRANSLATED_PROVIDER_CACHE.remove(key);
                        if (PAGE_EMPTY.size() > 5000) PAGE_EMPTY.clear();
                        PAGE_EMPTY.add(key);
                    }
                }
                final int finished = done;
                if (manual || !missing.isEmpty()) {
                    pageFinished("整页翻译完成 · " + finished + " 个物品" + (missing.isEmpty() ? "（全部来自本地词库）" : ""), 0xFF55FF55, 3000);
                }
            } catch (Throwable t) {
                for (String key : items.keySet()) {
                    if (!ITEM_CACHE.containsKey(key)) {
                        ACTIVE_TRANSLATED_ITEMS.remove(key);
                        TRANSLATED_PROVIDER_CACHE.remove(key);
                    }
                }
                notifyTranslationError(t instanceof Exception ex ? ex : new Exception(t));
                pageFinished("整页翻译失败：" + shorten(String.valueOf(t.getMessage()), 60), 0xFFFF5555, 6000);
            } finally {
                for (String key : items.keySet()) PENDING_ITEMS.remove(key);
                pageRunning = false;
            }
        });
    }

    /** "Hyperion · 别称 神剑 / Hype" under a translated item name. */
    private static void appendNameSubtitle(ItemStack stack, List<Component> tooltip) {
        ModConfig.EnchantHintMode mode = config.nameSubtitleMode != null ? config.nameSubtitleMode : ModConfig.EnchantHintMode.ALWAYS;
        if (mode == ModConfig.EnchantHintMode.OFF || tooltip.isEmpty()) return;
        if (mode == ModConfig.EnchantHintMode.SHIFT && !isShiftDown()) return;
        try {
            String en = TranslationUtil.stripColorCodes(toFormattedString(stack.getHoverName())).trim();
            String base = TableNames.baseName(en);
            if (base == null) return;
            if (tooltip.get(0).getString().trim().equals(en)) return; // the name is still in English
            MutableComponent line = Component.literal(en).withStyle(ChatFormatting.DARK_GRAY);
            List<String> aliases = TableNames.aliases(base);
            if (!aliases.isEmpty()) {
                line.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.translatable("npctranslator.tooltip.subtitle.aliases").withStyle(ChatFormatting.DARK_GRAY))
                    .append(Component.literal(" " + String.join(" / ", aliases)).withStyle(ChatFormatting.GRAY));
            }
            MOD_TRANSLATED.add(line); // our own line: not other mods' text, not to be translated or collected
            tooltip.add(1, line);
        } catch (Exception ignored) {}
    }

    // ── 中文增强版：附魔副标题 ─────────────────────────────────────────
    /** Puts each enchantment's note right under the line where that enchantment appears. */
    private static void appendEnchantHints(List<Component> tooltip) {
        ModConfig.EnchantHintMode mode = config.enchantHintMode != null ? config.enchantHintMode : ModConfig.EnchantHintMode.SHIFT;
        if (mode == ModConfig.EnchantHintMode.OFF) return;
        boolean show = mode == ModConfig.EnchantHintMode.ALWAYS || isShiftDown();
        List<EnchantHints.Entry> seen = new ArrayList<>();
        int lastEnchantLine = -1;
        for (int i = 0; i < tooltip.size(); i++) {
            List<EnchantHints.Entry> here = EnchantHints.find(List.of(tooltip.get(i)));
            here.removeAll(seen);
            if (here.isEmpty()) continue;
            seen.addAll(here);
            lastEnchantLine = i;
            if (!show) continue;
            int at = i + 1;
            for (EnchantHints.Entry e : here) {
                tooltip.add(at++, EnchantHints.line(e));
            }
            i = at - 1;
            lastEnchantLine = i;
            if (seen.size() > 30) break;
        }
        if (!show && lastEnchantLine >= 0) {
            // after the enchantment paragraph (its descriptions), not between a name and its description
            int at = lastEnchantLine + 1;
            for (int k = at; k < tooltip.size() && k <= lastEnchantLine + 8; k++) {
                if (tooltip.get(k).getString().isBlank()) { at = k; break; }
                if (k == tooltip.size() - 1) at = k + 1;
            }
            tooltip.add(at, Component.translatable("npctranslator.enchant_hint.press_shift").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    private static boolean isShiftDown() {
        Minecraft client = Minecraft.getInstance();
        if (client == null) return false;
        for (int code : new int[]{340, 344}) { // GLFW left / right shift
            try {
                try {
                    java.lang.reflect.Method m = InputConstants.class.getMethod("isKeyDown", int.class);
                    if ((boolean) m.invoke(null, code)) return true;
                } catch (NoSuchMethodException e) {
                    java.lang.reflect.Method m = InputConstants.class.getMethod("isKeyDown", com.mojang.blaze3d.platform.Window.class, int.class);
                    if (client.getWindow() != null && (boolean) m.invoke(null, client.getWindow(), code)) return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }

    // ── 中文增强版：其他模组加进提示框的文字（价格行、按钮提示等） ──────────
    private static volatile Set<String> lastItemLines = Collections.emptySet();
    private static volatile long lastItemLinesAt = 0;
    /** Mod lines we already replaced; never sent again to the item's extra-line translation. */
    private static final Set<Component> MOD_TRANSLATED = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    /** Lines that are neither the item name nor its lore were added by other mods: translate them. */
    private static void translateForeignLines(ItemStack stack, List<Component> tooltip) {
        Set<String> own = new HashSet<>();
        try {
            own.add(stack.getHoverName().getString());
            net.minecraft.world.item.component.ItemLore lore = stack.get(net.minecraft.core.component.DataComponents.LORE);
            if (lore != null) for (Component l : lore.lines()) own.add(l.getString());
        } catch (Exception ignored) {}
        if (own.size() <= 1) return; // no lore: probably not a SkyBlock item, leave it alone
        for (int i = 0; i < tooltip.size(); i++) {
            Component line = tooltip.get(i);
            String plain = line.getString();
            boolean grid = HudTranslator.isGrid(line); // Skyblocker price table rows: text lives in the cells
            if (!grid && (plain.isBlank() || own.contains(plain))) continue;
            if (HudTranslator.debug) HudTranslator.debugLog("item hook, mod line: " + toFormattedString(line));
            Component t = HudTranslator.modText(line);
            if (t != null) {
                tooltip.set(i, t);
                MOD_TRANSLATED.add(t);
            }
        }
    }

    /**
     * Called for every tooltip about to be drawn. Lines that appeared after our item hook (added
     * by mods that run later) or tooltips that are not item tooltips at all are translated here.
     */
    public static List<Component> processRenderedTooltip(List<Component> components) {
        List<Component> out = processRenderedTooltip0(components);
        TranslationReport.shown(out);
        return out;
    }

    private static List<Component> processRenderedTooltip0(List<Component> components) {
        collectRenderedTooltip(components);
        if (config == null || !config.enabled || components == null || components.isEmpty()) return components;
        boolean processed = PROCESSED_TOOLTIPS.containsKey(components);
        List<Component> out = components;
        if (config.translateModTooltips) {
            Set<String> known = System.currentTimeMillis() - lastItemLinesAt < 250 ? lastItemLines : Collections.emptySet();
            if (HudTranslator.debug) {
                StringBuilder sb = new StringBuilder("rendered tooltip (" + out.size() + " lines, item lines known: " + known.size() + "):");
                for (Component line : out) {
                    sb.append("\n    ").append(MOD_TRANSLATED.contains(line) ? "[done] " : HudTranslator.isGrid(line) ? "[grid] "
                            : known.contains(line.getString()) ? "[item] " : "[mod]  ")
                      .append(toFormattedString(line));
                }
                HudTranslator.debugLog(sb.toString());
            }
            for (int i = 0; i < out.size(); i++) {
                Component line = out.get(i);
                if (MOD_TRANSLATED.contains(line)) continue;
                if (!HudTranslator.isGrid(line) && known.contains(line.getString())) continue;
                Component t = HudTranslator.modText(line);
                if (t == null) continue;
                if (out == components) out = new ArrayList<>(components);
                out.set(i, t);
                MOD_TRANSLATED.add(t);
            }
        }
        if (!processed && isItemTranslationEnabled()) {
            try {
                if (out == components) out = new ArrayList<>(components);
                handleGeneralTooltip(out);
            } catch (Exception ignored) {}
        }
        if (out != components && processed) PROCESSED_TOOLTIPS.put(out, true);
        return out;
    }

    public static void handleGeneralTooltip(List<Component> tooltip) {
        if (!config.enabled) return;
        if (tooltip.isEmpty()) return;

        // Skip if already handled by ItemStack tooltip mapping (tracked in memory)
        if (PROCESSED_TOOLTIPS.containsKey(tooltip)) return;

        for (Component t : tooltip) {
            String str = t.getString();
            if (str.contains("✅") || str.contains("evirmek") || str.contains("Google ile") || str.contains("Yapay zeka")
                || str.contains("Translated by") || str.contains("to translate with") || str.contains("Press [")) return;
        }

        // Create a cache key from the tooltip content itself since we don't have an ItemStack
        StringBuilder content = new StringBuilder();
        for (Component t : tooltip) content.append(t.getString());
        if (content.length() < 10) return; // Too short to be an item lore usually

        String lang = getEffectiveLanguageCode();
        String cacheKey = "gen_" + Integer.toHexString(content.toString().hashCode()) + "_" + lang;

        handleTooltipStateAndRender(tooltip, cacheKey);
    }

    private static void handleTooltipStateAndRender(List<Component> tooltip, String cacheKey) {
        ModConfig.TranslationProvider currentProvider = TRANSLATED_PROVIDER_CACHE.get(cacheKey);
        boolean isAlreadyTranslated = ACTIVE_TRANSLATED_ITEMS.contains(cacheKey) && ITEM_CACHE.containsKey(cacheKey);

        // 中文增强版：Ctrl/Shift + 翻译键 = 清掉这个物品已存的译文，跳过短语库，用该引擎重新翻译
        if (isCtrlDown() || isShiftDown()) {
            ModConfig.TranslationProvider rp = null;
            String suffix = null;
            if (customJustPressed && config.enableCustomItem) { rp = ModConfig.TranslationProvider.CUSTOM; suffix = "_U"; }
            else if (googleJustPressed && config.enableGoogleItem) { rp = ModConfig.TranslationProvider.GOOGLE; suffix = "_G"; }
            else if (geminiJustPressed && config.enableGeminiItem) { rp = ModConfig.TranslationProvider.GEMINI; suffix = "_X"; }
            else if (groqJustPressed && config.enableGroqItem) { rp = ModConfig.TranslationProvider.GROQ; suffix = "_C"; }
            else if (mistralJustPressed && config.enableMistralItem) { rp = ModConfig.TranslationProvider.MISTRAL; suffix = "_M"; }
            else if (openRouterJustPressed && config.enableOpenRouterItem) { rp = ModConfig.TranslationProvider.OPENROUTER; suffix = "_O"; }
            if (rp != null && !TOGGLED_THIS_TICK.contains(cacheKey + suffix)) {
                TOGGLED_THIS_TICK.add(cacheKey + suffix);
                forgetTooltipLines(tooltip, rp);
                ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey); PENDING_ITEMS.remove(cacheKey); REVERTED_ITEMS.remove(cacheKey);
                ACTIVE_TRANSLATED_ITEMS.add(cacheKey); TRANSLATED_PROVIDER_CACHE.put(cacheKey, rp);
                FORCE_AI_ITEMS.add(cacheKey);
                triggerTranslation(tooltip, cacheKey, rp);
            }
        }

        // Toggle off if same key pressed while already translated with that provider
        if (googleJustPressed && config.enableGoogleItem && !TOGGLED_THIS_TICK.contains(cacheKey + "_G")) {
            TOGGLED_THIS_TICK.add(cacheKey + "_G");
            if (isAlreadyTranslated && currentProvider == ModConfig.TranslationProvider.GOOGLE) {
                // Same key pressed → revert
                ACTIVE_TRANSLATED_ITEMS.remove(cacheKey); REVERTED_ITEMS.add(cacheKey); unrememberItem(tooltip);
                TRANSLATED_PROVIDER_CACHE.remove(cacheKey); ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey);
            } else {
                ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey); PENDING_ITEMS.remove(cacheKey); REVERTED_ITEMS.remove(cacheKey);
                ACTIVE_TRANSLATED_ITEMS.add(cacheKey); TRANSLATED_PROVIDER_CACHE.put(cacheKey, ModConfig.TranslationProvider.GOOGLE);
                triggerTranslation(tooltip, cacheKey, ModConfig.TranslationProvider.GOOGLE);
            }
        }
        if (geminiJustPressed && config.enableGeminiItem && !TOGGLED_THIS_TICK.contains(cacheKey + "_X")) {
            TOGGLED_THIS_TICK.add(cacheKey + "_X");
            if (isAlreadyTranslated && currentProvider == ModConfig.TranslationProvider.GEMINI) {
                ACTIVE_TRANSLATED_ITEMS.remove(cacheKey); REVERTED_ITEMS.add(cacheKey); unrememberItem(tooltip);
                TRANSLATED_PROVIDER_CACHE.remove(cacheKey); ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey);
            } else {
                ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey); PENDING_ITEMS.remove(cacheKey); REVERTED_ITEMS.remove(cacheKey);
                ACTIVE_TRANSLATED_ITEMS.add(cacheKey); TRANSLATED_PROVIDER_CACHE.put(cacheKey, ModConfig.TranslationProvider.GEMINI);
                triggerTranslation(tooltip, cacheKey, ModConfig.TranslationProvider.GEMINI);
            }
        }
        if (groqJustPressed && config.enableGroqItem && !TOGGLED_THIS_TICK.contains(cacheKey + "_C")) {
            TOGGLED_THIS_TICK.add(cacheKey + "_C");
            if (isAlreadyTranslated && currentProvider == ModConfig.TranslationProvider.GROQ) {
                ACTIVE_TRANSLATED_ITEMS.remove(cacheKey); REVERTED_ITEMS.add(cacheKey); unrememberItem(tooltip);
                TRANSLATED_PROVIDER_CACHE.remove(cacheKey); ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey);
            } else {
                ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey); PENDING_ITEMS.remove(cacheKey); REVERTED_ITEMS.remove(cacheKey);
                ACTIVE_TRANSLATED_ITEMS.add(cacheKey); TRANSLATED_PROVIDER_CACHE.put(cacheKey, ModConfig.TranslationProvider.GROQ);
                triggerTranslation(tooltip, cacheKey, ModConfig.TranslationProvider.GROQ);
            }
        }
        if (mistralJustPressed && config.enableMistralItem && !TOGGLED_THIS_TICK.contains(cacheKey + "_M")) {
            TOGGLED_THIS_TICK.add(cacheKey + "_M");
            if (isAlreadyTranslated && currentProvider == ModConfig.TranslationProvider.MISTRAL) {
                ACTIVE_TRANSLATED_ITEMS.remove(cacheKey); REVERTED_ITEMS.add(cacheKey); unrememberItem(tooltip);
                TRANSLATED_PROVIDER_CACHE.remove(cacheKey); ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey);
            } else {
                ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey); PENDING_ITEMS.remove(cacheKey); REVERTED_ITEMS.remove(cacheKey);
                ACTIVE_TRANSLATED_ITEMS.add(cacheKey); TRANSLATED_PROVIDER_CACHE.put(cacheKey, ModConfig.TranslationProvider.MISTRAL);
                triggerTranslation(tooltip, cacheKey, ModConfig.TranslationProvider.MISTRAL);
            }
        }
        if (openRouterJustPressed && config.enableOpenRouterItem && !TOGGLED_THIS_TICK.contains(cacheKey + "_O")) {
            TOGGLED_THIS_TICK.add(cacheKey + "_O");
            if (isAlreadyTranslated && currentProvider == ModConfig.TranslationProvider.OPENROUTER) {
                ACTIVE_TRANSLATED_ITEMS.remove(cacheKey); REVERTED_ITEMS.add(cacheKey); unrememberItem(tooltip);
                TRANSLATED_PROVIDER_CACHE.remove(cacheKey); ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey);
            } else {
                ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey); PENDING_ITEMS.remove(cacheKey); REVERTED_ITEMS.remove(cacheKey);
                ACTIVE_TRANSLATED_ITEMS.add(cacheKey); TRANSLATED_PROVIDER_CACHE.put(cacheKey, ModConfig.TranslationProvider.OPENROUTER);
                triggerTranslation(tooltip, cacheKey, ModConfig.TranslationProvider.OPENROUTER);
            }
        }
        if (customJustPressed && config.enableCustomItem && !TOGGLED_THIS_TICK.contains(cacheKey + "_U")) {
            TOGGLED_THIS_TICK.add(cacheKey + "_U");
            if (isAlreadyTranslated && currentProvider == ModConfig.TranslationProvider.CUSTOM) {
                ACTIVE_TRANSLATED_ITEMS.remove(cacheKey); REVERTED_ITEMS.add(cacheKey); unrememberItem(tooltip);
                TRANSLATED_PROVIDER_CACHE.remove(cacheKey); ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey);
            } else {
                ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey); PENDING_ITEMS.remove(cacheKey); REVERTED_ITEMS.remove(cacheKey);
                ACTIVE_TRANSLATED_ITEMS.add(cacheKey); TRANSLATED_PROVIDER_CACHE.put(cacheKey, ModConfig.TranslationProvider.CUSTOM);
                triggerTranslation(tooltip, cacheKey, ModConfig.TranslationProvider.CUSTOM);
            }
        }
        if (revertJustPressed && !TOGGLED_THIS_TICK.contains(cacheKey + "_R")) {
            TOGGLED_THIS_TICK.add(cacheKey + "_R");
            ACTIVE_TRANSLATED_ITEMS.remove(cacheKey); REVERTED_ITEMS.add(cacheKey); unrememberItem(tooltip);
            TRANSLATED_PROVIDER_CACHE.remove(cacheKey); ITEM_CACHE.remove(cacheKey); ITEM_LINE_MAP.remove(cacheKey);
        }

        boolean isTranslated = ACTIVE_TRANSLATED_ITEMS.contains(cacheKey) && ITEM_CACHE.containsKey(cacheKey);
        // Check if there's an API error message stored
        boolean hasError = !ACTIVE_TRANSLATED_ITEMS.contains(cacheKey) && ITEM_CACHE.containsKey(cacheKey) 
                           && ITEM_CACHE.get(cacheKey).stream().anyMatch(t -> t.getString().startsWith("⚠"));
        
        if (isTranslated) {
            ModConfig.TranslationProvider provider = TRANSLATED_PROVIDER_CACHE.getOrDefault(cacheKey, ModConfig.TranslationProvider.CUSTOM);
            
            // "tikli olmayan item altında yazısı çıkmasın google tranlate dahil"
            boolean providerEnabled = false;
            switch(provider) {
                case GOOGLE: providerEnabled = config.enableGoogleItem; break;
                case GEMINI: providerEnabled = config.enableGeminiItem; break;
                case GROQ: providerEnabled = config.enableGroqItem; break;
                case MISTRAL: providerEnabled = config.enableMistralItem; break;
                case OPENROUTER: providerEnabled = config.enableOpenRouterItem; break;
                case CUSTOM: providerEnabled = config.enableCustomItem; break;
            }

            if (providerEnabled) {
                List<Component> translated = ITEM_CACHE.get(cacheKey);
                Map<String, List<Component>> lineMap = ITEM_LINE_MAP.get(cacheKey);
                try {
                    if (lineMap != null) {
                        // Replace only the lines we translated. Lines added by other mods (prices, etc.)
                        // or lines that changed since the translation stay exactly as they are.
                        List<Component> rebuilt = new ArrayList<>(tooltip.size());
                        List<String> unmatched = null;
                        for (Component line : tooltip) {
                            String key = toFormattedString(line).trim();
                            List<Component> repl = lineMap.get(key);
                            if (repl == null) {
                                // Countdowns / changing numbers: the line text is new every second, but its
                                // number-free template is already in the dictionary -> use it right away.
                                String byTemplate = templateTranslation(key, provider);
                                if (byTemplate != null) {
                                    List<Component> c = List.of(parseColorCodes(byTemplate));
                                    if (lineMap.size() < 400) lineMap.put(key, c);
                                    rebuilt.addAll(c);
                                    continue;
                                }
                                rebuilt.add(line);
                                if (MOD_TRANSLATED.contains(line)) continue;
                                // e.g. the extra lines shown while holding Shift, or price lines from other mods
                                if (TranslationUtil.needsTranslation(key)) {
                                    if (unmatched == null) unmatched = new ArrayList<>();
                                    unmatched.add(key);
                                }
                            } else {
                                rebuilt.addAll(repl);
                            }
                        }
                        if (unmatched != null) translateExtraLines(cacheKey, unmatched, provider);
                        tooltip.clear();
                        tooltip.addAll(rebuilt);
                    } else {
                        tooltip.clear();
                        tooltip.addAll(translated);
                    }
                    if (aiAllowed()) tooltip.add(Component.empty());
                    if (aiAllowed()) tooltip.add(Component.translatable(
                        provider == ModConfig.TranslationProvider.GOOGLE ? "npctranslator.tooltip.translated.google" :
                        provider == ModConfig.TranslationProvider.GEMINI ? "npctranslator.tooltip.translated.gemini" :
                        provider == ModConfig.TranslationProvider.MISTRAL ? "npctranslator.tooltip.translated.mistral" :
                        provider == ModConfig.TranslationProvider.OPENROUTER ? "npctranslator.tooltip.translated.openrouter" :
                        provider == ModConfig.TranslationProvider.CUSTOM ? "npctranslator.tooltip.translated.custom" :
                        "npctranslator.tooltip.translated.groq"
                    ).withStyle(ChatFormatting.GRAY, ChatFormatting.ITALIC));
                } catch (Exception ignored) {}
            }
        } else if (PENDING_ITEMS.contains(cacheKey)) {
            try {
                // Animated spinner: cycles through frames every ~10 ticks
                String[] spinnerFrames = {"⏳", "⌛"};
                String[] dotFrames = {"", ".", "..", "..."};
                String spinner = spinnerFrames[(int)((animationTick / 10) % spinnerFrames.length)];
                String dots = dotFrames[(int)((animationTick / 8) % dotFrames.length)];
                tooltip.add(Component.empty());
                tooltip.add(Component.literal(spinner + " ")
                    .append(Component.translatable("npctranslator.translating.item"))
                    .append(dots)
                    .withStyle(ChatFormatting.YELLOW, ChatFormatting.ITALIC));
            } catch (Exception ignored) {}
        } else if (hasError) {
            // Show the error message stored in cache
            try {
                tooltip.add(Component.empty());
                ITEM_CACHE.get(cacheKey).forEach(tooltip::add);
            } catch (Exception ignored) {}
        } else if (config.rememberTranslatedItems && !REVERTED_ITEMS.contains(cacheKey) && !ACTIVE_TRANSLATED_ITEMS.contains(cacheKey)
                && rememberedProvider(tooltip) != null) {
            // 中文增强版：翻译过的物品（比如重新打开菜单后 key 变了）自动重新显示译文，结果主要来自词典
            ModConfig.TranslationProvider remembered = rememberedProvider(tooltip);
            ACTIVE_TRANSLATED_ITEMS.add(cacheKey);
            TRANSLATED_PROVIDER_CACHE.put(cacheKey, remembered);
            triggerTranslation(tooltip, cacheKey, remembered);
        } else if ((config.autoTranslateItems || !aiAllowed()) && !REVERTED_ITEMS.contains(cacheKey) && !ACTIVE_TRANSLATED_ITEMS.contains(cacheKey)) {
            // without AI the translation is local and free: every item is translated automatically
            // Auto translate items logic
            ModConfig.TranslationProvider autoProvider = config.autoItemProvider != null ? config.autoItemProvider : ModConfig.TranslationProvider.CUSTOM;
            ACTIVE_TRANSLATED_ITEMS.add(cacheKey);
            TRANSLATED_PROVIDER_CACHE.put(cacheKey, autoProvider);
            triggerTranslation(tooltip, cacheKey, autoProvider);
        }

        renderShortcutsFooter(tooltip, isTranslated, PENDING_ITEMS.contains(cacheKey), cacheKey);
    }

    private static boolean isPendingTooltip(String cacheKey) {
        return PENDING_ITEMS.contains(cacheKey);
    }

    /** Shortcut hints at the very bottom of item tooltips. */
    private static void renderShortcutsFooter(List<Component> tooltip, boolean isTranslated, boolean isPending, String cacheKey) {
        try {
            if (isPending) return;
            String tk = getKeyName(keyTranslateCustom, "N");
            if (!aiAllowed()) {
                tooltip.add(Component.empty());
                tooltip.add(Component.translatable(isTranslated ? "npctranslator.tooltip.footer.local" : "npctranslator.tooltip.footer.local_translate",
                        isTranslated ? getKeyName(keyRevertTranslation, "V") : tk, getKeyName(menuKey, "Z"), getKeyName(keyReport, "F8")).withStyle(ChatFormatting.DARK_GRAY));
                return;
            }
            tooltip.add(Component.empty());
            if (isTranslated) {
                tooltip.add(Component.translatable("npctranslator.tooltip.footer.translated",
                        getKeyName(keyRevertTranslation, "V"), tk).withStyle(ChatFormatting.DARK_GRAY));
            } else if (isCustomConfigured()) {
                tooltip.add(Component.translatable("npctranslator.tooltip.footer.translate", tk, tk).withStyle(ChatFormatting.DARK_GRAY));
            } else {
                tooltip.add(Component.translatable("npctranslator.tooltip.footer.setup", getKeyName(menuKey, "Z")).withStyle(ChatFormatting.DARK_GRAY));
            }
            tooltip.add(Component.translatable("npctranslator.tooltip.footer.more",
                    getKeyName(keyTranslatePage, "M"), getKeyName(menuKey, "Z"), getKeyName(keyReport, "F8")).withStyle(ChatFormatting.DARK_GRAY));
        } catch (Exception ignored) {}
    }

    private static String getKeyName(KeyMapping bind, String def) {
        try {
            String n = bind.getTranslatedKeyMessage().getString().toUpperCase();
            if (n.isEmpty() || n.equals("-") || n.equals("NONE")) return def;
            return n;
        } catch (Exception e) { return def; }
    }

    private static final java.util.regex.Pattern WORD = java.util.regex.Pattern.compile("[A-Za-z]{2,}");

    /** Dictionary translation of a line via its number-free template, or null. */
    private static String templateTranslation(String formatted, ModConfig.TranslationProvider provider) {
        if (formatted.isEmpty() || provider == null || !config.normalizeNumbers) return null;
        TranslationUtil.Template t = TranslationUtil.normalize(formatted);
        if (!t.hasNumbers()) return null;
        String tr = dictGet(t.text, provider, currentMenu());
        if (tr == null || tr.isEmpty() || !TranslationUtil.placeholdersMatch(t.text, tr)) return null;
        return TranslationUtil.restore(tr, t.numbers);
    }
    private static final Set<String> EXTRA_PENDING = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, Long> EXTRA_LAST = new ConcurrentHashMap<>();

    /**
     * Lines that show up after an item was translated (Shift details, other mods' price lines,
     * changed lore) are translated in the background and added to that item's line map.
     */
    private static void translateExtraLines(String cacheKey, List<String> lines, ModConfig.TranslationProvider provider) {
        Map<String, List<Component>> lineMap = ITEM_LINE_MAP.get(cacheKey);
        if (lineMap == null || lineMap.size() > 400) return;
        long now = System.currentTimeMillis();
        Long last = EXTRA_LAST.get(cacheKey);
        if (last != null && now - last < 1500L) return;
        if (!EXTRA_PENDING.add(cacheKey)) return;
        EXTRA_LAST.put(cacheKey, now);
        if (EXTRA_LAST.size() > 2000) EXTRA_LAST.clear();
        List<String> todo = new ArrayList<>(new LinkedHashSet<>(lines));
        final String menu = currentMenu();
        CompletableFuture.runAsync(() -> {
            try {
                String providerName = dictNs(provider, menu);
                MENU_CONTEXT.set(menu);
                String langCode = getEffectiveLanguageCode();
                String targetLang = config.useGameLanguage ? ("the language for locale code: " + langCode) : config.targetLanguage.englishName;
                Map<String, String> done = new HashMap<>();
                List<String> missing = new ArrayList<>();
                for (String line : todo) {
                    String fixedTr = config.usePhrasebook ? PhraseBook.translate(line) : null;
                    if (fixedTr == null) fixedTr = EnchantHints.translateList(line);
                    if (fixedTr != null) { done.put(line, fixedTr); continue; }
                    TranslationUtil.Template t = config.normalizeNumbers ? TranslationUtil.normalize(line) : TranslationUtil.Template.raw(line);
                    String tr = dictGet(t.text, provider, menu);
                    if (tr != null && !tr.isEmpty()) done.put(line, TranslationUtil.restore(tr, t.numbers));
                    else if (!missing.contains(t.text)) missing.add(t.text);
                }
                if (!missing.isEmpty() && aiAllowed()) {
                    Map<Integer, String> result = translateBatch(missing, langCode, targetLang, provider);
                    boolean changed = false;
                    for (Map.Entry<Integer, String> e : result.entrySet()) {
                        String template = missing.get(e.getKey());
                        if (TranslationUtil.placeholdersMatch(template, e.getValue())) {
                            TranslationDictionary.put(template, e.getValue(), providerName);
                            changed = true;
                        }
                    }
                    if (changed) TranslationDictionary.save();
                    for (String line : todo) {
                        if (done.containsKey(line)) continue;
                        TranslationUtil.Template t = config.normalizeNumbers ? TranslationUtil.normalize(line) : TranslationUtil.Template.raw(line);
                        String tr = dictGet(t.text, provider, menu);
                        if (tr != null && !tr.isEmpty()) done.put(line, TranslationUtil.restore(tr, t.numbers));
                    }
                }
                Map<String, List<Component>> map = ITEM_LINE_MAP.get(cacheKey);
                if (map != null) {
                    for (Map.Entry<String, String> e : done.entrySet()) {
                        List<Component> out = new ArrayList<>();
                        for (String wrapped : PhraseMatcher.wrap(e.getValue(), 64)) out.add(parseColorCodes(wrapped));
                        map.put(e.getKey(), out);
                    }
                    // Lines that could not be translated are remembered as-is so they are not retried every frame.
                    for (String line : todo) map.putIfAbsent(line, List.of(parseColorCodes(line)));
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                MENU_CONTEXT.remove();
                EXTRA_PENDING.remove(cacheKey);
            }
        });
    }

    private static void triggerTranslation(List<Component> tooltip, String cacheKey, ModConfig.TranslationProvider provider) {
        if (PENDING_ITEMS.contains(cacheKey)) return;
        if (provider == ModConfig.TranslationProvider.GROQ && (config.groqApiKey == null || config.groqApiKey.isEmpty())) {
            ACTIVE_TRANSLATED_ITEMS.remove(cacheKey);
            ITEM_CACHE.put(cacheKey, List.of(Component.translatable("npctranslator.error.config").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            return;
        }
        if (provider == ModConfig.TranslationProvider.GEMINI && (config.geminiApiKey == null || config.geminiApiKey.isEmpty())) {
            ACTIVE_TRANSLATED_ITEMS.remove(cacheKey);
            ITEM_CACHE.put(cacheKey, List.of(Component.translatable("npctranslator.error.config").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            return;
        }
        if (provider == ModConfig.TranslationProvider.CUSTOM && !isCustomConfigured() && aiAllowed()) {
            ACTIVE_TRANSLATED_ITEMS.remove(cacheKey);
            ITEM_CACHE.put(cacheKey, List.of(Component.translatable("npctranslator.error.custom_config").withStyle(ChatFormatting.RED, ChatFormatting.BOLD)));
            return;
        }

        
        PENDING_ITEMS.add(cacheKey);

        String langCode = getEffectiveLanguageCode();
        String targetLang = config.useGameLanguage ? ("the language for locale code: " + langCode) : config.targetLanguage.englishName;

        // Create a snapshot of the tooltip on the main thread to avoid ConcurrentModificationException
        // since the main thread might modify the tooltip (e.g. adding shortcuts) while we iterate async.
        List<Component> tooltipSnapshot = new ArrayList<>(tooltip);
        final String menu = currentMenu();

        Runnable task = () -> {
            try {
                String providerName = provider.name(); // e.g. "GOOGLE", "GEMINI", "CUSTOM"
                List<String> allLinesOriginal = new ArrayList<>();
                for (Component line : tooltipSnapshot) {
                    allLinesOriginal.add(toFormattedString(line).trim());
                }
                int n = allLinesOriginal.size();

                // 0) Phrase book / enchant lists; wrapped lore sentences are joined before going to the AI.
                boolean forceAi = FORCE_AI_ITEMS.remove(cacheKey);
                ItemLines.Plan plan = ItemLines.plan(allLinesOriginal, config.usePhrasebook && !forceAi);

                // 1) Translate only the templates we have never seen (numbers already replaced by {n}).
                LinkedHashSet<String> missingSet = new LinkedHashSet<>();
                ItemLines.collectMissing(plan, provider, menu, missingSet);
                if (!missingSet.isEmpty()) {
                    ItemLines.translateMissing(missingSet, provider, 25, 2, menu);
                }

                // 2) Lines with numbers whose template failed get one raw (uncached) retry.
                String[] raw = new String[n];
                List<String> retryLines = new ArrayList<>();
                List<Integer> retryIdx = new ArrayList<>();
                for (int i = 0; i < n; i++) {
                    TranslationUtil.Template t = plan.templates[i];
                    if (t == null || !t.hasNumbers()) continue;
                    String tr = dictGet(t.text, provider, menu);
                    if (tr == null || tr.isEmpty()) {
                        retryLines.add(TranslationUtil.restore(t.text, t.numbers));
                        retryIdx.add(i);
                    }
                }
                if (!retryLines.isEmpty() && aiAllowed()) {
                    try {
                        Map<Integer, String> retry = translateBatch(retryLines, langCode, targetLang, provider);
                        for (Map.Entry<Integer, String> e : retry.entrySet()) {
                            raw[retryIdx.get(e.getKey())] = e.getValue();
                        }
                    } catch (Exception retryError) {
                        retryError.printStackTrace();
                    }
                }

                ItemLines.Result result = ItemLines.assemble(plan, provider, raw, menu);
                List<Component> translatedTooltip = result.tooltip();
                Map<String, List<Component>> lineMap = result.lineMap();

                if (!translatedTooltip.isEmpty()) {
                    ITEM_LINE_MAP.put(cacheKey, lineMap);
                    ITEM_CACHE.put(cacheKey, translatedTooltip);
                    if (n > 0) rememberItem(allLinesOriginal.get(0), provider);
                }
            } catch (Exception e) {
                e.printStackTrace();
                String providerName = provider.toString();
                boolean isRateLimit = e.getMessage() != null && e.getMessage().contains("429");
                String errorMsgKey = e.getMessage() != null && e.getMessage().contains("401") ? "npctranslator.error.api_key_invalid" :
                                  isRateLimit ? "npctranslator.error.api_limit_exceeded" :
                                  "npctranslator.error.api_general";
                ITEM_CACHE.put(cacheKey, List.of(Component.translatable(errorMsgKey, providerName).withStyle(Style.EMPTY.withColor(ChatFormatting.RED).withBold(true))));
                ACTIVE_TRANSLATED_ITEMS.remove(cacheKey);
            } finally {
                PENDING_ITEMS.remove(cacheKey);
            }
        };
        // local data only (no AI): fast enough to do right away, so no "translating…" flash
        if (!aiAllowed() && PhraseBook.isReady()) task.run();
        else CompletableFuture.runAsync(task);
    }

    public static String toFormattedString(Component text) {
        StringBuilder sb = new StringBuilder();
        text.visit((style, string) -> {
            net.minecraft.network.chat.TextColor color = style.getColor();
            if (color != null) {
                // Use our reverse map: rgb -> §code
                int rgb = color.getValue() & 0xFFFFFF;
                Character code = COLOR_TO_CODE.get(rgb);
                if (code != null) {
                    sb.append('\u00A7').append(code);
                }
            }
            if (style.isBold()) sb.append("\u00A7l");
            if (style.isItalic()) sb.append("\u00A7o");
            if (style.isUnderlined()) sb.append("\u00A7n");
            if (style.isStrikethrough()) sb.append("\u00A7m");
            if (style.isObfuscated()) sb.append("\u00A7k");
            sb.append(string);
            return Optional.empty();
        }, Style.EMPTY);
        return sb.toString();
    }

    public static Component parseColorCodes(String text) {
        MutableComponent root = Component.empty();
        String[] parts = text.split("\u00A7");
        if (parts.length == 0) return Component.literal(text);
        root.append(Component.literal(parts[0]));
        List<ChatFormatting> activeFormats = new ArrayList<>();
        for (int i = 1; i < parts.length; i++) {
            String part = parts[i];
            if (part.isEmpty()) continue;
            char code = Character.toLowerCase(part.charAt(0));
            ChatFormatting format = CODE_TO_FORMAT.get(code);
            if (format != null) {
                if (COLOR_FORMATS.contains(format) || format == ChatFormatting.RESET) activeFormats.clear();
                if (format != ChatFormatting.RESET) activeFormats.add(format);
                if (part.length() > 1) {
                    MutableComponent t = Component.literal(part.substring(1));
                    for (ChatFormatting f : activeFormats) t.withStyle(f);
                    root.append(t);
                }
            } else {
                root.append(Component.literal("\u00A7" + part));
            }
        }
        return root;
    }
    
    private static String clientLanguage() {
        Minecraft client = Minecraft.getInstance();
        return (client != null && client.options != null) ? client.options.languageCode : "en_us";
    }

    public static String getEffectiveLanguageCode() {
        ModConfig config = ModConfig.INSTANCE;
        if (!config.useGameLanguage) {
            return config.targetLanguage.code;
        }
        String userLang = clientLanguage();
        if (userLang == null || userLang.trim().isEmpty()) return "tr";
        userLang = userLang.toLowerCase().trim();

        switch (userLang) {
            case "zh_cn":
            case "zh_hans":
                return "zh-CN";
            case "zh_tw":
            case "zh_hk":
            case "zh_hant":
                return "zh-TW";
            case "pt_br":
                return "pt-BR";
            case "pt_pt":
                return "pt";
            case "he_il":
                return "he";
            case "fil_ph":
            case "tl_ph":
                return "tl";
            case "brb": // Brabantian -> Dutch
            case "vls_be":
            case "zea_nl":
            case "li_li":
                return "nl";
            case "bar_at":
            case "gsw_ch":
            case "nds_de":
            case "ksh_de":
            case "pfl_de":
                return "de";
            case "szl_pl":
            case "csb_pl":
                return "pl";
            case "vec_it":
            case "lmo_it":
            case "scn_it":
            case "nap_it":
            case "fur_it":
            case "pms_it":
                return "it";
            case "ast_es":
            case "an_es":
            case "ext_es":
                return "es";
            case "val_es":
                return "ca";
            case "sr_sp":
                return "sr";
            case "az_az":
                return "az";
            case "mt_mt":
                return "mt";
            case "nb_no":
            case "nn_no":
                return "no";
            default:
                if (userLang.contains("_")) {
                    return userLang.split("_")[0];
                }
                return userLang;
        }
    }

    private static String getTargetLanguageCode() {
        return getEffectiveLanguageCode();
    }

    // ── Start TTS queue runner (called once at mod init) ────────────────────────
    private static void startTtsRunner() {
        if (TTS_RUNNER_STARTED) return;
        TTS_RUNNER_STARTED = true;
        Thread t = new Thread(() -> {
            while (true) {
                try {
                    String text = TTS_QUEUE.take(); // blocks until a message arrives
                    playNow(text);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }, "TTS-Queue-Runner");
        t.setDaemon(true);
        t.start();
    }

    // ── Add text to queue (called by MODIFY_GAME / handleTranslation) ───────────
    public static void speakText(String text) {
        if (text == null || text.trim().isEmpty()) return;
        ModConfig cfg = ModConfig.INSTANCE;
        if (!cfg.enableTts) return;

        startTtsRunner();

        String clean = text.replaceAll("§[0-9a-fk-rA-FK-R]", "").trim();
        clean = clean.replaceAll("^\\[.*?\\]", "").trim();
        if (clean.isEmpty()) return;

        TTS_QUEUE.offer(clean); // if queue full (50 items), silently drop
    }

    // ── Actually play audio — called by queue runner (blocking) ─────────────────
    private static void playNow(String text) {
        ModConfig cfg = ModConfig.INSTANCE;
        playGoogleTts(text, getTargetLanguageCode(), cfg.ttsSpeed, cfg.ttsPitch);
    }

    // ─── Google TTS engine with multi-endpoint and language fallback ────────────
    private static void playGoogleTts(String text, String langCode, float speed, float pitch) {
        java.io.File tempMp3 = null;
        try {
            String paddedText = " , , " + text;
            String encodedText = java.net.URLEncoder.encode(paddedText, "UTF-8");

            List<String> langCandidates = new ArrayList<>();
            if (langCode != null && !langCode.trim().isEmpty()) {
                langCandidates.add(langCode.trim());
            }
            if ("he".equalsIgnoreCase(langCode)) langCandidates.add(0, "iw");
            if ("jv".equalsIgnoreCase(langCode)) langCandidates.add(0, "jw");
            if ("mt".equalsIgnoreCase(langCode)) { langCandidates.add("it"); langCandidates.add("en"); }
            if (!langCandidates.contains("tr")) langCandidates.add("tr");
            if (!langCandidates.contains("en")) langCandidates.add("en");

            byte[] audioBytes = null;

            for (String tryLang : langCandidates) {
                String[] endpoints = new String[]{
                    "https://translate.google.com/translate_tts?ie=UTF-8&tl=" + tryLang + "&client=tw-ob&q=" + encodedText,
                    "https://clients5.google.com/translate_tts?ie=UTF-8&tl=" + tryLang + "&client=dict-chrome-ex&q=" + encodedText,
                    "https://translate.google.com/translate_tts?ie=UTF-8&tl=" + tryLang + "&client=gtx&q=" + encodedText
                };

                for (String urlStr : endpoints) {
                    try {
                        java.net.URL url = new java.net.URL(urlStr);
                        java.net.HttpURLConnection conn = (java.net.HttpURLConnection) url.openConnection();
                        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
                        conn.setRequestProperty("Accept", "audio/mpeg, audio/*");
                        conn.setConnectTimeout(4000);
                        conn.setReadTimeout(4000);
                        conn.connect();
                        if (conn.getResponseCode() == 200) {
                            try (java.io.InputStream in = conn.getInputStream();
                                 java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                                byte[] buf = new byte[8192];
                                int n;
                                while ((n = in.read(buf)) != -1) baos.write(buf, 0, n);
                                if (baos.size() > 500) {
                                    audioBytes = baos.toByteArray();
                                    break;
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
                if (audioBytes != null) break;
            }

            if (audioBytes == null || audioBytes.length == 0) return;

            tempMp3 = java.io.File.createTempFile("npctts_", ".mp3");
            tempMp3.deleteOnExit();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempMp3)) {
                fos.write(audioBytes);
            }

            float effectiveRate = Math.max(0.2f, Math.min(3.0f, speed * pitch));
            String filePath = tempMp3.getAbsolutePath().replace("\\", "/");
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);

            if (os.contains("win")) {
                playWindowsMci(filePath, effectiveRate);
            } else if (os.contains("mac")) {
                playMacAfplay(tempMp3, effectiveRate);
            } else {
                playLinuxAudio(tempMp3);
            }
        } catch (Exception e) {
            System.err.println("[NPCTranslator] TTS playback error: " + e.getMessage());
        } finally {
            if (tempMp3 != null) try { tempMp3.delete(); } catch (Exception ignored) {}
        }
    }

    private static void playWindowsMci(String filePath, float effectiveRate) {
        int mciSpeed = Math.round(effectiveRate * 1000.0f);
        mciSend("close all");
        int rOpen = mciSend("open \"" + filePath + "\" type mpegvideo alias npctts");
        if (rOpen != 0) {
            System.err.println("[NPCTranslator] MCI open failed (code " + rOpen + ") for " + filePath);
        }
        mciSend("set npctts speed " + mciSpeed);
        int rPlay = mciSend("play npctts wait");
        if (rPlay != 0) {
            System.err.println("[NPCTranslator] MCI play failed (code " + rPlay + ")");
        }
        mciSend("close npctts");
    }

    private static void playMacAfplay(java.io.File file, float rate) {
        try {
            float clampedRate = Math.max(0.5f, Math.min(2.0f, rate));
            ProcessBuilder pb = new ProcessBuilder("/usr/bin/afplay", "-r", String.format(java.util.Locale.ROOT, "%.2f", clampedRate), file.getAbsolutePath());
            Process p = pb.start();
            p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
            if (p.isAlive()) p.destroyForcibly();
        } catch (Exception e) {
            System.err.println("[NPCTranslator] afplay failed: " + e.getMessage());
        }
    }

    private static void playLinuxAudio(java.io.File file) {
        String[] players = new String[]{"ffplay -nodisp -autoexit -loglevel quiet", "mpv --no-video --really-quiet", "play -q"};
        for (String pcmd : players) {
            try {
                String[] parts = pcmd.split(" ");
                java.util.List<String> cmd = new java.util.ArrayList<>(java.util.Arrays.asList(parts));
                cmd.add(file.getAbsolutePath());
                Process p = new ProcessBuilder(cmd).start();
                p.waitFor(90, java.util.concurrent.TimeUnit.SECONDS);
                if (p.isAlive()) p.destroyForcibly();
                if (p.exitValue() == 0) return;
            } catch (Exception ignored) {}
        }
    }

    private static Object mciWFunc = null;
    private static java.lang.reflect.Method mciInvokeInt = null;
    private static java.lang.reflect.Constructor<?> mciWStringCons = null;
    private static Object mciAFunc = null;
    private static boolean mciInitialized = false;

    private static synchronized void initMci() {
        if (mciInitialized) return;
        mciInitialized = true;
        try {
            Class<?> funcClass = Class.forName("com.sun.jna.Function");
            java.lang.reflect.Method getFunc = funcClass.getMethod("getFunction", String.class, String.class);
            mciInvokeInt = funcClass.getMethod("invokeInt", Object[].class);

            try {
                Class<?> wstrClass = Class.forName("com.sun.jna.WString");
                mciWStringCons = wstrClass.getConstructor(String.class);
                mciWFunc = getFunc.invoke(null, "winmm", "mciSendStringW");
            } catch (Throwable ignored) {}

            try {
                mciAFunc = getFunc.invoke(null, "winmm", "mciSendStringA");
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            System.err.println("[NPCTranslator] JNA MCI bridge init failed: " + t);
        }
    }

    private static int mciSend(String command) {
        initMci();
        if (mciWFunc != null && mciWStringCons != null && mciInvokeInt != null) {
            try {
                Object wstr = mciWStringCons.newInstance(command);
                Object res = mciInvokeInt.invoke(mciWFunc, new Object[]{ new Object[]{ wstr, null, 0, 0 } });
                return (Integer) res;
            } catch (Throwable ignored) {}
        }
        if (mciAFunc != null && mciInvokeInt != null) {
            try {
                Object res = mciInvokeInt.invoke(mciAFunc, new Object[]{ new Object[]{ command, null, 0, 0 } });
                return (Integer) res;
            } catch (Throwable ignored) {}
        }
        return -1;
    }
}
