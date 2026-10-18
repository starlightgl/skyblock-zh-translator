> 以下为原模组 NPC & Items Translator 的说明文档，仅作存档，内容不适用于本项目。本项目的说明见 [README](../../README.md)。
>
> This is the documentation of the original NPC & Items Translator, kept for reference. It does not describe this fork.

[English](README_en.md) | [Türkçe](README_tr.md) | [Русский](README_ru.md) | [Deutsch](README_de.md) | [中文](README_zh.md)

---

# 🌍 NPC & Items Translator for Hypixel Skyblock & More

Language barriers should never hold you back from enjoying your favorite Minecraft servers. **NPC & Items Translator** is a fully customizable, intelligent client-side Fabric mod that seamlessly translates NPC dialogues, item descriptions, and GUI tooltips into your preferred language with AI-powered accuracy, overhead speech bubbles, and realistic voice narration (TTS)!

Built especially with heavy RPG/MMO game modes like **Hypixel SkyBlock** in mind, it flawlessly handles complex color codes, dynamic lore (such as live Bazaar prices), interactive chat actions, and full multi-language UI localization.

---

## ✨ Core Features

### 🗨️ Natural Floating Chat Bubbles (Overhead Dialogue)
Read NPC dialogue where you're already looking! Translated messages appear directly above character heads in the 3D world:
- **Stacked Dialogue (Comic/RPG Style):** When an NPC speaks multiple lines rapidly, previous lines don't vanish! Earlier sentences slide upward into an elegant vertical stack so you can read everything in chronological order.
- **Dynamic Reading Duration:** Bubble display time dynamically calculates based on message length (`4s + 65ms per character`). When translation completes, extra reading time is automatically added so you never run out of time before finishing a sentence.
- **Modern Rounded Aesthetic:** Sleek rounded comic-balloon boxes with dark slate backdrop, sky blue borders, amber gold speaker titles, and a directional pointer tail pointing down to the character.
- **Full Control in Settings:** Toggle On/Off, adjust base display duration (3s - 15s), restrict to NPCs only, and adjust max visibility distance (6m - 32m).

### 💬 Interactive Chat Translation
Tired of copying text to your browser? A clean **[Translate]** button appears automatically next to chat messages.  
Click it to instantly translate dialogue into your language!

- **Customizable Button Position:** Place the `[Translate]` button at the **Beginning** (default) or **End** of chat messages, or hide it completely.
- **Auto-Translate Option:** Automatically translate all incoming chat messages without clicking.
- **Only Translate NPCs:** Only translate NPC dialogue while leaving regular player chat untouched.
- **Safe & Reversible:** Click the language tag (e.g. `[TR]`, `[EN]`) anytime to revert the text back to its original version. All interactive click events (*"Click to open Auction House"*, etc.) are completely preserved!

### 🗣️ Built-in Text-to-Speech (TTS) Voice Narration
Hear NPC dialogue spoken out loud in your language!
- **Natural Voice Output:** Automatically narrates translated NPC dialogue or chat messages using high-quality Google TTS.
- **Full Customization:** Adjust **Speech Speed** (0.25x - 2.00x) and **Voice Pitch / Tone** (0.60x - 1.40x) directly in the settings menu.
- **Cross-Platform & Safe (Zero PowerShell):**
  - **Windows:** 100% in-memory native Windows Multimedia (`winmm.dll`) playback. No PowerShell, no external command scripts, and zero antivirus false positives.
  - **macOS (MacBook / iMac):** Native Apple CoreAudio (`/usr/bin/afplay`) player with real-time speed control (`-r`). Works seamlessly out of the box with zero setup.
  - **Linux:** Automatic native audio pipeline routing (`ffplay`, `mpv`, `play`).
- **Smart Queueing:** High-speed dialogue queue ensures smooth sequential playback without stutter or overlapping audio.

### 📦 On-Demand Item Tooltip Translation
Hover over any item and press customizable hotkeys to translate item lore with your preferred engine:
- **`G`** — Translate via **Google Translate** (Free, Instant)
- **`X`** — Translate via **Gemini AI** (Smart, High quality)
- **`C`** — Translate via **Groq AI** (Ultra-fast LLM)
- **`[Configurable]`** — Translate via **Mistral AI** *(Assign key in Controls)*
- **`[Configurable]`** — Translate via **OpenRouter AI** *(Assign key in Controls)*
- **`V`** — Revert translation back to original

*Note: Pressing the same translation key twice acts as a toggle to revert.*

### 🎨 Flawless Color Code Preservation
Unlike basic translators that break Minecraft formatting, this mod extracts `§` color codes, translates the raw meaning, and accurately reapplies the original colors.  
Your **Epic** items stay purple, and your **Legendary** items remain orange!

### 🛡️ Smart Error Handling & Automatic Model Fallback
- If an API key is missing or invalid, an in-game warning will appear directly in the tooltip or chat.
- **Auto-Fallback on Rate Limit:** If an AI model hits rate limits (429), the mod silently and automatically retries using the next available model from the same provider!

---

## 🌐 17 Supported Languages (UI & Translation)

The mod includes complete UI localization and translation support for **17 major languages**:

| Language | Code | Native Name |
| :--- | :--- | :--- |
| 🇬🇧 English | `en_us` | English |
| 🇹🇷 Turkish | `tr_tr` | Türkçe |
| 🇨🇳 Chinese (Simplified) | `zh_cn` | 简体中文 |
| 🇪🇸 Spanish | `es_es` | Español |
| 🇮🇳 Hindi | `hi_in` | हिन्दी |
| 🇸🇦 Arabic | `ar_sa` | العربية |
| 🇫🇷 French | `fr_fr` | Français |
| 🇷🇺 Russian | `ru_ru` | Русский |
| 🇧🇷 Portuguese (Brazil) | `pt_br` | Português (Brasil) |
| 🇮🇩 Indonesian | `id_id` | Bahasa Indonesia |
| 🇩🇪 German | `de_de` | Deutsch |
| 🇯🇵 Japanese | `ja_jp` | 日本語 |
| 🇰🇷 Korean | `ko_kr` | 한국어 |
| 🇻🇳 Vietnamese | `vi_vn` | Tiếng Việt |
| 🇮🇹 Italian | `it_it` | Italiano |
| 🇵🇱 Polish | `pl_pl` | Polski |
| 🇺🇦 Ukrainian | `uk_ua` | Українська |

---

## ⚙️ Translation Engines

You have full control over the translation backend through the configuration menu (`/translate` or via ModMenu, or by pressing **Z**).

| Engine | Description |
|---|---|
| 🤖 **Gemini AI** | Google's latest Gemini models (e.g. Gemini 2.5 Flash, Flash-Lite). Excellent contextual understanding and formatting retention. Get a free API key at [Google AI Studio](https://aistudio.google.com). |
| ⚡ **Groq AI** | Ultra-fast LLMs (Llama 3.3 70B, Qwen3) to contextually understand Minecraft and MMO terminology. Get a free API key at [console.groq.com](https://console.groq.com). |
| 🌪️ **Mistral AI** | Mistral's powerful models (Mistral Large, Ministral). Reliable and high quality. Get a free API key at [console.mistral.ai](https://console.mistral.ai). |
| 🌍 **OpenRouter AI** | Access a wide variety of LLMs (Claude, Llama, Qwen, DeepSeek, etc.) through a single unified API. Get a key at [openrouter.ai](https://openrouter.ai). |
| 🌐 **Google Translate** | No API key required! Enjoy unlimited, fast, and completely free translations with zero setup. |

---

## 🔧 Configuration & Commands

- **In-game GUI:** `/translate` command, **`Z`** keybind, or `ESC → Mods → NPC & Items Translator`
- **Dynamic Language:** Translate to your Minecraft Client Language automatically, or choose a specific Target Language.
- **Keybinds:** `Options → Controls → Key Binds → NPC & Items Translator`
- **Dictionary Reset:** `/translate DeleteDict` or via the in-game Hub menu.

---

## 📦 Compatibility & Requirements

- **Loader:** Fabric
- **Supported Minecraft Versions:** `1.21.11`, `26.1`, `26.2`, `26.3`
- **Dependencies:** 
  - [Fabric API](https://modrinth.com/mod/fabric-api)
  - [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl)
  - [ModMenu](https://modrinth.com/mod/modmenu)
- **Side:** Client-side ONLY — 100% safe to use on multiplayer servers including Hypixel!

---

## 📄 License

MIT License
