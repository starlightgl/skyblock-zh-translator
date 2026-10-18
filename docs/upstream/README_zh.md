> 以下为原模组 NPC & Items Translator 的说明文档，仅作存档，内容不适用于本项目。本项目的说明见 [README](../../README.md)。
>
> This is the documentation of the original NPC & Items Translator, kept for reference. It does not describe this fork.

[English](README_en.md) | [Türkçe](README_tr.md) | [Русский](README_ru.md) | [Deutsch](README_de.md) | [中文](README_zh.md)

---

# 🌍 Hypixel Skyblock 及更多服务器的 NPC 与物品翻译模组

语言障碍不应该成为您畅玩最喜爱的 Minecraft 服务器的阻碍。**NPC & Items Translator (NPC 与物品翻译器)** 是一款完全可自定义的、智能的客户端 Fabric 模组，能够通过 AI 强大的准确度、头顶自然浮动气泡以及逼真的语音朗读 (TTS)，将 NPC 对话、物品描述和 GUI 提示无缝翻译成您的首选语言！

该模组专为像 **Hypixel SkyBlock** 这样的大型 RPG/MMO 游戏模式而设计，完美支持复杂的颜色代码、动态数据（如 Bazaar 实时价格）、交互式聊天事件以及 17 种语言的完整 UI 本地化。

---

## ✨ 核心功能

### 🗨️ 自然浮动聊天气泡（头顶对话）
将目光集中在游戏世界中即可阅读！已翻译的消息将直接显示在 3D 世界中 NPC 的头顶上方：
- **堆叠式对话（漫画/RPG 风格）：** 当 NPC 连续说出多句话时，先前的句子不会消失！较早的气泡会平滑向上滑动堆叠，让您可以按时间顺序从容阅读完整对话。
- **动态阅读时长：** 气泡停留时间根据文字长度动态计算（`4秒 + 每字符 65毫秒`）。翻译完成后会自动延长停留时间，确保您在阅读完毕前气泡绝不提前消失。
- **现代化圆角美学：** 柔和圆角漫画气泡框，深石板蓝半透明背景、天蓝色边框、琥珀金色 `[NPC] 名字` 标题，以及指向说话角色的三角形尾巴。
- **丰富的配置选项：** 在设置菜单中可轻松切换开关、调整基础显示时间（3秒 - 15秒）、限制仅对 NPC 生效以及调整最大可见距离（6格 - 32格）。

### 💬 交互式聊天翻译
告别繁琐的手动复制到浏览器！聊天消息旁会自动出现精致的 **[翻译]** (Translate) 按钮。  
只需点击即可立即将对话翻译为您所需的语言！

- **自定义按钮位置：** 可将 `[翻译]` 按钮放置在消息的**开头**（默认）或**末尾**，也可完全隐藏。
- **自动翻译选项：** 无需手动点击，自动翻译所有接收到的聊天消息。
- **仅翻译 NPC：** 仅自动翻译 NPC 对话，保持普通玩家聊天内容不变。
- **安全且可还原：** 随时点击语言标签（如 `[ZH]`、`[TR]` 等）即可立即将文本还原为原始内容。所有交互式点击事件（例如 *“点击此处打开拍卖行”* 等）均完美保留！

### 🗣️ 内置文本转语音 (TTS) 语音朗读
以您的母语实时聆听 NPC 的对话朗读！
- **自然语音输出：** 使用高质量 Google TTS 引擎自动朗读已翻译的 NPC 对话或聊天消息。
- **完全自定义：** 可在设置菜单中轻松调节**语速** (0.25x - 2.00x) 和**音调 / 声音粗细** (0.60x - 1.40x)。
- **跨平台与完全安全（彻底移除 PowerShell）：**
  - **Windows：** 100% 内存中原生 Windows Multimedia (`winmm.dll`) C API 调用。绝不启动 PowerShell 或外部脚本，杜绝杀毒软件误报。
  - **macOS (MacBook / iMac)：** 原生调用 Apple CoreAudio (`/usr/bin/afplay`)，支持即时语速调节 (`-r`)，开箱即用，无需额外配置。
  - **Linux：** 自动适配常用原生音频管线 (`ffplay`, `mpv`, `play`)。
- **智能对话队列：** 连续对话流畅有序地依次播放，避免音频重叠。

### 📦 按需物品提示翻译
将鼠标悬停在任何物品上，按下自定义快捷键即可通过首选引擎翻译物品 Lore：
- **`G`** — 通过 **Google 翻译** 翻译（免费、即时）
- **`X`** — 通过 **Gemini AI** 翻译（智能、高质量）
- **`C`** — 通过 **Groq AI** 翻译（超快速大语言模型）
- **`[可自定义]`** — 通过 **Mistral API** 翻译 *(在“控制”设置中绑定)*
- **`[可自定义]`** — 通过 **OpenRouter API** 翻译 *(在“控制”设置中绑定)*
- **`V`** — 将翻译还原为原始物品描述

*注意：连续按两次相同的翻译键可充当开关并还原翻译。*

### 🎨 完美保留颜色与排版代码
与破坏 Minecraft 排版的普通翻译工具不同，本模组会自动提取 `§` 颜色代码，翻译纯文本内容后精确还原原始颜色。  
您的 **史诗 (Epic)** 物品依旧是紫色，**传说 (Legendary)** 物品始终保持橙色！

### 🛡️ 智能错误处理与超额自动轮换模型
- 若未填写 API 密钥或密钥无效，物品提示或聊天中将出现明确提示。
- **速率超限自动降级：** 当某个 AI 模型达到并发或速率限制 (429) 时，模组会自动静默切换至同一提供商的**下一个可用模型**继续翻译！

---

## 🌐 17 种受支持语言（界面与翻译）

模组在 UI 本地化以及翻译目标语言中完全支持 **17 种主要语言**：

| 语言 | 代码 | 本地名称 |
| :--- | :--- | :--- |
| 🇬🇧 英语 | `en_us` | English |
| 🇹🇷 土耳其语 | `tr_tr` | Türkçe |
| 🇨🇳 中文（简体） | `zh_cn` | 简体中文 |
| 🇪🇸 西班牙语 | `es_es` | Español |
| 🇮🇳 印地语 | `hi_in` | हिन्दी |
| 🇸🇦 阿拉伯语 | `ar_sa` | العربية |
| 🇫🇷 法语 | `fr_fr` | Français |
| 🇷🇺 俄语 | `ru_ru` | Русский |
| 🇧🇷 葡萄牙语（巴西） | `pt_br` | Português (Brasil) |
| 🇮🇩 印尼语 | `id_id` | Bahasa Indonesia |
| 🇩🇪 德语 | `de_de` | Deutsch |
| 🇯🇵 日语 | `ja_jp` | 日本語 |
| 🇰🇷 韩语 | `ko_kr` | 한국어 |
| 🇻🇳 越南语 | `vi_vn` | Tiếng Việt |
| 🇮🇹 意大利语 | `it_it` | Italiano |
| 🇵🇱 波兰语 | `pl_pl` | Polski |
| 🇺🇦 乌克兰语 | `uk_ua` | Українська |

---

## ⚙️ 翻译引擎

您可以在配置菜单（通过 `/translate` 命令、ModMenu 或按 **Z** 键）中完全自由选择翻译后端：

| 引擎 | 说明 |
|---|---|
| 🤖 **Gemini AI** | 谷歌最新的 Gemini 模型（如 Gemini 2.5 Flash 等）。可在 [Google AI Studio](https://aistudio.google.com) 获取免费 API 密钥。 |
| ⚡ **Groq AI** | 超快速的开源 LLM（Llama 3.3 70B、Qwen3 等），上下文理解准确。可在 [console.groq.com](https://console.groq.com) 获取免费 API 密钥。 |
| 🌪️ **Mistral AI** | Mistral 的高性能模型（Mistral Large 等）。可在 [console.mistral.ai](https://console.mistral.ai) 获取免费 API 密钥。 |
| 🌍 **OpenRouter AI** | 通过统一的 API 访问上百款顶尖模型（Claude、Llama、Qwen、DeepSeek 等）。可在 [openrouter.ai](https://openrouter.ai) 获取密钥。 |
| 🌐 **Google 翻译** | 无需 API 密钥！免配置、完全免费且快速可用。 |

---

## 🔧 配置与命令

- **游戏内界面：** `/translate` 命令、**`Z`** 快捷键，或 `ESC → 选项 → 模组 → NPC & Items Translator`
- **动态语言：** 自动匹配您的 Minecraft 游戏语言，或指定固定的目标语言。
- **按键绑定：** `选项 → 控制 → 按键绑定 → NPC & Items Translator`
- **词典清理：** `/translate DeleteDict` 或通过游戏内菜单清空翻译缓存。

---

## 📦 兼容性与运行需求

- **加载器：** Fabric
- **支持的 Minecraft 版本：** `1.21.11`, `26.1`, `26.2`, `26.3`
- **前置模组：** 
  - [Fabric API](https://modrinth.com/mod/fabric-api)
  - [YetAnotherConfigLib (YACL)](https://modrinth.com/mod/yacl)
  - [ModMenu](https://modrinth.com/mod/modmenu)
- **运行端：** 仅客户端 (Client-side) — 在包括 Hypixel 在内的所有多人服务器上 100% 安全！

---

## 📄 开源许可

MIT 许可证
