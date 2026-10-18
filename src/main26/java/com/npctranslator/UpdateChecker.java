package com.npctranslator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.npctranslator.config.ModConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 中文增强版：进服时查一次 GitHub 上的最新正式版，有新版本就在聊天栏提示（可在设置里关闭）。
 */
public final class UpdateChecker {

    public static final String REPO = "starlightgl/skyblock-zh-translator";
    public static final String MOD_ID = "hypixel-npc-and-items-translate";
    private static volatile boolean checked = false;

    private UpdateChecker() {}

    /** Version of the running jar without the "+mc26.x" build suffix, e.g. "1.5.1-zh.2". */
    public static String currentVersion() {
        return FabricLoader.getInstance().getModContainer(MOD_ID)
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse("")
                .replaceFirst("\\+.*$", "");
    }

    public static void onJoin(Minecraft client) {
        ModConfig c = ModConfig.INSTANCE;
        if (checked || c == null || !c.checkUpdates) return;
        checked = true;
        CompletableFuture.runAsync(() -> {
            try {
                String current = currentVersion();
                if (current.isEmpty()) return;
                HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/" + REPO + "/releases/latest"))
                        .header("Accept", "application/vnd.github+json")
                        .header("User-Agent", "skyblock-zh-translator")
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<String> resp = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build()
                        .send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) return;
                JsonObject o = JsonParser.parseString(resp.body()).getAsJsonObject();
                String latest = o.get("tag_name").getAsString().replaceFirst("^v", "");
                String url = o.get("html_url").getAsString();
                if (compare(latest, current) <= 0) return;
                Thread.sleep(4000); // let the server's join messages scroll by first
                client.execute(() -> {
                    if (client.player == null) return;
                    MutableComponent link = Component.literal("[打开下载页]").withStyle(s -> s
                            .withColor(ChatFormatting.AQUA).withUnderlined(true)
                            .withClickEvent(new ClickEvent.OpenUrl(URI.create(url)))
                            .withHoverEvent(new HoverEvent.ShowText(Component.literal(url))));
                    client.player.sendSystemMessage(Component.literal("[SkyBlock 中文翻译] 有新版本 " + latest
                            + "（你现在是 " + current + "） ").withStyle(ChatFormatting.GOLD).append(link));
                });
            } catch (Throwable ignored) {
                // offline / GitHub unreachable: say nothing
            }
        });
    }

    private static final Pattern NUM = Pattern.compile("\\d+");

    /** Compares "1.5.1-zh.2" style versions number by number; a "-beta"/"-pre"/"-rc" tag is older than the plain one. */
    static int compare(String a, String b) {
        List<Integer> x = nums(a), y = nums(b);
        for (int i = 0; i < Math.max(x.size(), y.size()); i++) {
            int p = i < x.size() ? x.get(i) : -1, q = i < y.size() ? y.get(i) : -1;
            if (p != q) return Integer.compare(p, q);
        }
        boolean preA = a.matches(".*-(beta|pre|rc).*"), preB = b.matches(".*-(beta|pre|rc).*");
        return preA == preB ? 0 : (preA ? -1 : 1);
    }

    private static List<Integer> nums(String v) {
        String core = v.replaceFirst("-(beta|pre|rc).*$", "");
        List<Integer> out = new ArrayList<>();
        Matcher m = NUM.matcher(core);
        while (m.find()) {
            try { out.add(Integer.parseInt(m.group())); } catch (NumberFormatException e) { out.add(0); }
        }
        return out;
    }
}
