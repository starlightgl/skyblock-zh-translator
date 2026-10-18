**中文增强版** | [English](README_en.md) | [Türkçe](README_tr.md) | [Русский](README_ru.md) | [Deutsch](README_de.md) | [原版中文说明](README_zh.md)

---

# SkyBlock 中文翻译（SkyBlock ZH Translator）

一个纯客户端的 Fabric 模组，把 Hypixel SkyBlock 里的 NPC 对话、物品描述、侧边栏、悬浮文字和各类提示翻译成中文。

基于 [KALPSIZ__ 的 NPC & Items Translator](https://github.com/knutolof06/hypixel-skyblock-npc-and-items-translate)（MIT 协议）修改，针对中文玩家做了大量增强：可以接入任意 OpenAI 兼容的 AI 接口，内置固定译文和术语表，能识别数字和倒计时，尽量少调 AI、少花钱。

- **支持版本**：Minecraft 26.2 / 26.3，Fabric
- **前置模组**：Fabric API、YetAnotherConfigLib、ModMenu
- 本模组与 Hypixel、Mojang 无关，非官方作品。

---

## 主要功能

### 翻译引擎
- **自定义 AI 接口**：填 Base URL、API Key、模型名即可，支持 DeepSeek、ClinePass、各类中转站等 OpenAI 兼容接口。推理模型输出的 `<think>` 会自动去掉。翻译失败时聊天栏会显示红字原因。
- **自定义提示词**：默认已写好 SkyBlock 背景说明，可以自己改。
- **数字占位缓存**：翻译前把数字换成占位符，存的是句子模板。数字、价格、倒计时变了也不用重新翻，也不会一闪一闪。

### 固定译文与术语
- **固定短语库**：内置 SkyBlockZH 社区语料约 4.4 万条，装上就能用。命中的句子直接显示中文，不调用 AI。一行里有多个「标签: 数值」的，会逐段翻译。
- **术语表**：保证物品名、属性名前后统一，修改后即时生效。
- **附魔词库与附魔说明**：145 个附魔（含全部终极附魔）都有统一译名，附魔列表直接本地翻译、不调用 AI（Skyblocker 等模组用的内部名如 Syphon 也认得）。按住 Shift 时，每个附魔下方会显示「中文名 英文原名 一句话功能」。

### 物品描述
- 按翻译键（默认 **N**）翻译鼠标指着的物品，按 **V** 恢复原文，按 **Shift+N** 或 **Ctrl+N** 丢掉旧译文重新翻译。
- 逐行替换：Skyblocker、SkyHanni 等模组加的最低价、均价这些行会原样保留。
- **整页翻译**：打开菜单后按 **M**，把这一页所有物品一次翻完（重复的句子只翻一次，合并成几次请求），之后鼠标指哪个都是秒出。设置里可以打开「打开菜单自动整页翻译」。
- Hypixel 把一句话折成好几行的，会先拼回整句再翻，不会出现「查看你的技能进度和 / 。」这种断句错误。
- 按 Shift 出现的详情会在后台自动补翻；关掉菜单再打开，翻译过的物品会自动显示译文。
- 其他模组加在物品描述里的行，以及 Skyblocker 快捷导航这类按钮的悬停提示，也会翻译。

### 世界与界面
- 侧边栏计分板、切换物品时物品栏上方的物品名。
- NPC 头顶的名字和副标题、全息文字、文字展示实体（真实玩家的名字不会动）。

### 聊天
- 系统提示、NPC 对话命中短语库时直接显示中文。
- 翻译过的固定格式消息（比如 SkyHanni 的「获得了 X 硬币」）再出现时，直接用存好的译文显示。
- 带多个可点选项的对话按选项分段翻译，点哪个就是哪个。
- NPC 一段长话被拆成几条消息时，会带上前后句一起翻，避免意思翻错。
- 点译文前面的 `[翻译]` 看原文，点后面的 `↻` 重新翻译。

---

## 安装

1. 安装 Fabric Loader 和前置模组：Fabric API、YetAnotherConfigLib、ModMenu。
2. 到 [Releases](../../releases) 下载和游戏版本一致的 jar（文件名带 `mc26.2` 或 `mc26.3`），放进 `mods` 文件夹。
3. 如果装过原版 NPC & Items Translator，要先删掉，两者的模组 ID 相同。

## 配置

游戏里输入 `/translate` 打开设置，或者在 ModMenu 里点本模组的设置按钮。

**接入 AI（以 DeepSeek 为例）**：在「API → 自定义 API」里填：

| 项目 | 示例 |
|---|---|
| Base URL | `https://api.deepseek.com/v1`（会自动补 `/chat/completions`） |
| API Key | 你的密钥 |
| 模型名 | `deepseek-chat` |

再把「聊天翻译引擎」和「自动翻译使用的引擎」都选成 **Custom API**。

**常用开关**（都在设置里）：检查更新、固定短语库、翻译侧边栏、翻译悬浮文字、翻译其他模组的提示文字、记住已翻译的物品、打开菜单自动整页翻译、自动显示已存的聊天译文、附魔副标题（关闭 / 按住 Shift / 一直显示）。

## 指令与按键

| 指令 / 按键 | 作用 |
|---|---|
| `/translate` | 打开设置 |
| `/translate find 关键词` | 查看词典里包含关键词的已存译文 |
| `/translate forget 关键词` | 删掉这些译文，下次出现时重新翻译 |
| `/translate DeleteDict` | 清空整个词典 |
| `/translate debug` | 开/关调试日志：其他模组提示行的处理过程写进 `logs/latest.log`，排查「某行没翻译」用 |
| N | 翻译鼠标指着的物品（自定义 API） |
| Shift + N 或 Ctrl + N | 丢掉这个物品的旧译文，跳过短语库重新翻译 |
| M | 整页翻译：翻译当前菜单里的所有物品 |
| V | 恢复物品原文，并且不再自动翻译这个物品 |
| Shift | 查看附魔说明 |

按键都可以在游戏的按键设置里改。

## 数据文件

短语库、术语表、附魔说明都已经内置在 jar 里，**一个 jar 就能用**，不需要手动放任何文件。

想自己改译名时，在游戏目录的 `config` 文件夹里写同名文件，里面的条目会覆盖内置的：

| 文件 | 内容 |
|---|---|
| `npc_translator.json` | 模组设置（含 API Key，不要分享给别人） |
| `npc_translator_dict.json` | 翻译词典，AI 翻译过的内容都存在这里 |
| `npc_translator_glossary.json` | 自己的术语：左英文右中文，保存后即时生效 |
| `npc_translator_phrasebook.json` | 自己的固定译文：`{"entries":[{"en":"...","zh":"..."}]}` |
| `npc_translator_enchants.json` | 自己改的附魔译名和说明 |

想用新版 SkyBlockZH 语料重新生成内置数据：

```
python tools/export_skyblockzh.py <SkyBlockZH 文件夹> <输出文件夹>
```

## 更新

- 进服时模组会到 GitHub 查一次新版本，有的话在聊天栏给出下载链接（设置里可以关掉「检查更新」）。
- 升级时用新 jar 替换 `mods` 里的旧 jar 即可，设置和已存译文都会保留。
- 每个版本改了什么见 [更新日志](CHANGELOG.md)。版本号格式是 `原模组版本-zh.序号`，带 `-beta` 的是测试版。

## 自己编译

双击 `一键编译.bat`：

- 没有 JDK 25 会自动下载一个便携版，放在 `jdk` 文件夹里，不影响系统。
- 依次编译 26.2 和 26.3 两个版本，成功后 jar 会复制到上一级文件夹。
- 只编一个版本：`gradlew.bat build -PmcTarget=26.2`

也可以不在本地编译：每次推送代码，GitHub Actions 都会自动编译两个版本（在 Actions 页面的 Artifacts 里下载）。

### 发布新版本

1. `gradle.properties` 里改 `mod_version`（例如 `1.5.1-zh.3`，测试版 `1.5.1-zh.3-beta.1`）。
2. 在 `CHANGELOG.md` 顶部加一节 `## 1.5.1-zh.3 (日期)`，写这一版改了什么。
3. 提交后推送标签 `v1.5.1-zh.3`。GitHub Actions 会检查版本号是否一致、用更新日志生成发布说明、编译 26.2 / 26.3 两个 jar 并上传，然后正式发布；测试版会标成「预发布」，游戏内的更新提示不会推送它。

## 常见问题

**会被 Hypixel 封号吗？**
这个模组只在你自己的客户端上翻译文字，不发包、不自动操作游戏，属于 Hypixel 一般允许的那类显示类模组。但规则以 Hypixel 官方为准，请自行判断。

**会花很多 API 费用吗？**
固定短语库和词典命中的内容不调用 AI。同一句话只翻一次，数字变了也不用重翻，所以玩一阵子后基本不花钱。

---

## 致谢与许可

- 本项目以 **GPL-3.0** 协议发布，见 [LICENSE](LICENSE)；版权与出处说明见 [NOTICE.md](NOTICE.md)。
- 原模组：[KALPSIZ__ / NPC & Items Translator](https://github.com/knutolof06/hypixel-skyblock-npc-and-items-translate)，原代码为 MIT 协议，其版权声明保留在 NOTICE.md 中。
- 内置的固定短语库和术语表数据来自 [SkyBlockZH](https://github.com/BingKKni/SkyBlockZH)（GPL-3.0）。
- 附魔译名参考了 Minecraft 官方中文译名和 [Hypixel 空岛生存中文维基](https://hypixel-skyblock.fandom.com/zh/wiki/%E9%99%84%E9%AD%94)。
- Hypixel、Hypixel SkyBlock 是 Hypixel Inc. 的商标；Minecraft 是 Mojang Studios 的商标。本项目与它们无关。
