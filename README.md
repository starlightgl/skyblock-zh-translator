# SkyBlock 中文翻译（SkyBlock ZH Translator）

[![build](https://github.com/starlightgl/skyblock-zh-translator/actions/workflows/build.yml/badge.svg)](https://github.com/starlightgl/skyblock-zh-translator/actions/workflows/build.yml)
[![release](https://img.shields.io/github/v/release/starlightgl/skyblock-zh-translator?label=release)](https://github.com/starlightgl/skyblock-zh-translator/releases/latest)
[![license](https://img.shields.io/github/license/starlightgl/skyblock-zh-translator)](LICENSE)

Hypixel SkyBlock 中文翻译模组，适用于 Fabric（Minecraft 26.2 / 26.3），仅需安装在客户端。
翻译范围包括 NPC 对话、物品描述、计分板、全息文字、聊天消息以及其他模组添加的提示文本。

> A client-side Fabric mod that translates Hypixel SkyBlock into Simplified Chinese. Documentation is in Chinese.

本项目基于 KALPSIZ__ 的 [NPC & Items Translator](https://github.com/knutolof06/hypixel-skyblock-npc-and-items-translate) 开发，面向中文玩家进行了扩展：内置社区维护的固定译文与术语表，仅在必要时调用 AI 接口，并对数字、倒计时等动态内容做了缓存处理。

本项目为非官方作品，与 Hypixel Inc. 及 Mojang Studios 无关。

## 目录

- [功能](#功能)
- [安装](#安装)
- [配置](#配置)
- [快捷键与命令](#快捷键与命令)
- [自定义数据](#自定义数据)
- [更新](#更新)
- [从源码构建](#从源码构建)
- [常见问题](#常见问题)
- [许可与致谢](#许可与致谢)

## 功能

### 翻译机制

- **SkyBlock 术语表**：以 NotEnoughUpdates 物品库为基础整理并逐条校对的中文译名表，收录物品名称 7,739 条、物品描述 5,309 条、宠物 89 条、修饰词 136 条、区域 169 条、生物 255 条、附魔 157 条，并附社区常用别称。源文件为 [data/SkyBlock术语表.xlsx](data/SkyBlock术语表.xlsx)。
- **译文来源可选**：仅校对表 / 校对表 + 社区语料 / 校对表 + 社区语料 + AI。默认仅使用校对表，表中没有的内容保持英文，不调用 AI。
- **固定短语库**：内置 SkyBlockZH 社区语料约 4.4 万条（选择包含社区语料的译文来源时启用）。命中的文本直接显示译文，不调用 AI；同一行内的多个「标签: 数值」字段逐段匹配。
- **术语表**：内置 1 万余条术语，用于统一物品、属性等名称的译法，修改后即时生效。
- **附魔译名与说明**：收录 145 个附魔（含全部终极附魔）的统一译名，附魔列表在本地翻译；按住 `Shift` 时在每个附魔下方显示中文名、英文原名与功能说明。
- **AI 翻译**：译文来源包含 AI 时，未命中本地数据的文本交由 OpenAI 兼容接口翻译，结果写入本地词典，相同文本只翻译一次。
- **数字占位缓存**：译文以句子模板存储，数值、价格和倒计时变化时无需重新翻译，也不会出现闪烁。

### 物品提示框

- 物品名称支持修饰词前缀、星级后缀与宠物等级（如「英勇海伯利安 ✪✪✪✪✪」「[Lv.100] 黄金龙」）；名称下方显示英文原名与社区别称（如「Hyperion · 别称 神剑 / Hype」）。

- 按翻译键翻译当前物品；逐行替换文本，保留其他模组添加的内容（如 Skyblocker、SkyHanni 的价格信息）。
- **整页翻译**：在容器界面中一次翻译当前页面的全部物品。相同文本去重后合并为批量请求，界面左上角显示翻译进度。
- 被折成多行的句子先合并为完整句子再翻译，避免断句造成的误译。
- 外观相同的物品共用同一份译文；重新打开菜单时自动显示已有译文。
- 译文按菜单分类保存：在菜单中翻译的内容记录在该菜单名下，优先使用当前菜单的译文，并可按菜单查看或清除；AI 请求附带菜单名称作为上下文。
- 集市历史记录等固定格式的条目在本地生成译文。
- 翻译 Skyblocker 价格表格行、快捷导航按钮提示等其他模组的文本。
- 提示框底部列出可用的快捷键。

### 界面与世界

- 计分板（侧边栏）与切换物品时显示的物品名称。
- NPC 名称与副标题、全息文字、文字展示实体（不翻译真实玩家的名称）。

### 聊天

- 系统消息与 NPC 对话命中短语库时直接显示译文；已翻译过的固定格式消息再次出现时自动显示已存译文。
- 含多个可点击选项的对话按选项分段翻译，点击行为保持不变。
- 拆分为多条消息的 NPC 对话结合上下文翻译。
- 点击译文前的 `[翻译]` 查看原文，点击 `↻` 重新翻译。

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/) 及前置模组：Fabric API、YetAnotherConfigLib、ModMenu。
2. 在 [Releases](https://github.com/starlightgl/skyblock-zh-translator/releases/latest) 页面下载与游戏版本对应的 jar：

   | Minecraft 版本 | 文件名后缀 |
   |---|---|
   | 26.2 | `+mc26.2.jar` |
   | 26.3 | `+mc26.3.jar` |

3. 将 jar 放入 `mods` 目录。如已安装原版 NPC & Items Translator，请先将其移除（两者的模组 ID 相同）。

短语库、术语表和附魔数据均已内置在 jar 中，无需额外放置配置文件。

## 配置

按 `Z`、输入 `/translate` 或通过 ModMenu 打开设置界面。

### 接入 AI 接口

在「API → 自定义 API」中选择服务商并填写 API Key。选择服务商后，接口地址与推荐模型会自动填入，两者均可手动修改。

| 服务商 | 接口地址（Base URL） | 推荐模型 |
|---|---|---|
| DeepSeek | `https://api.deepseek.com/v1` | `deepseek-flash` |
| 阿里云百炼（通义千问） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` |
| 智谱 GLM | `https://open.bigmodel.cn/api/paas/v4` | `glm-5.3` |
| Kimi（月之暗面） | `https://api.moonshot.cn/v1` | `kimi-k2.6` |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `deepseek-ai/DeepSeek-V3` |
| 火山方舟（豆包） | `https://ark.cn-beijing.volces.com/api/v3` | 方舟控制台中的模型 ID |

- 模型名称以各服务商的最新文档为准。
- 其他 OpenAI 兼容接口（如中转服务）请选择「其他」并手动填写。
- 接口地址末尾会自动补全 `/chat/completions`；推理模型输出的 `<think>` 内容会被自动移除。
- 设置界面中的「如何获取 API」页面提供上述平台的 API Key 申请入口。

### 常用选项

| 选项 | 说明 | 默认 |
|---|---|---|
| 译文来源 | 仅校对表 / 校对表 + 社区语料 / 校对表 + 社区语料 + AI | 仅校对表 |
| 检查更新 | 加入服务器时检查是否有新的正式版 | 开启 |
| 固定短语库 | 优先使用内置及自定义的固定译文 | 开启 |
| 翻译侧边栏 / 悬浮文字 / 其他模组提示 | 分别控制对应区域的翻译 | 开启 |
| 记住已翻译的物品 | 重新打开菜单时自动显示已有译文 | 开启 |
| 打开菜单自动整页翻译 | 打开或切换菜单页面时在后台翻译整页物品（会产生 API 调用） | 关闭 |
| 自动显示已存的聊天译文 | 固定格式的聊天消息直接显示已存译文 | 开启 |
| 物品原名与别称 | 关闭 / 按住 `Shift` 显示 / 始终显示 | 始终显示 |
| 附魔说明 | 关闭 / 按住 `Shift` 显示 / 始终显示 | 按住 `Shift` |

## 快捷键与命令

| 按键 | 功能 |
|---|---|
| `N` | 翻译当前物品 |
| `Shift` + `N` 或 `Ctrl` + `N` | 清除该物品的已存译文，跳过短语库重新翻译 |
| `V` | 恢复物品原文，并停止自动翻译该物品 |
| `M` | 翻译当前菜单页面的全部物品 |
| `Shift` + `M` 或 `Ctrl` + `M` | 清除当前菜单的已存译文，并重新翻译整页 |
| `Z` | 打开翻译设置（容器界面中同样可用） |
| `Shift`（按住） | 显示附魔说明 |

| 命令 | 功能 |
|---|---|
| `/translate` | 打开设置界面 |
| `/translate find <关键词>` | 列出词典中包含关键词的已存译文 |
| `/translate forget <关键词>` | 删除匹配的已存译文，下次出现时重新翻译 |
| `/translate menus` | 列出各菜单已保存的译文条目数 |
| `/translate forgetmenu <菜单名>` | 清除菜单名包含该关键词的全部译文 |
| `/translate DeleteDict` | 清空翻译词典 |
| `/translate coverage` | 显示本次游戏中所见文本的翻译覆盖率，未覆盖的原文记录到 `config/npc_translator_missing.tsv`；加 `reset` 重新统计 |
| `/translate debug` | 开启或关闭调试日志，记录其他模组提示行的处理过程（输出至 `logs/latest.log`） |

按键可在游戏的「按键绑定」中修改。

## 自定义数据

在游戏目录的 `config` 文件夹中放置以下同名文件，其中的条目会覆盖内置数据：

| 文件 | 内容 |
|---|---|
| `npc_translator.json` | 模组设置（包含 API Key，请勿外传） |
| `npc_translator_dict.json` | 翻译词典，保存 AI 翻译结果 |
| `npc_translator_glossary.json` | 自定义术语，格式为 `"English": "中文"`，保存后即时生效 |
| `npc_translator_phrasebook.json` | 自定义固定译文，格式为 `{"entries": [{"en": "...", "zh": "..."}]}` |
| `npc_translator_enchants.json` | 自定义附魔译名与说明 |

术语表更新后，重新生成内置数据：

```
python tools/import_terms.py data/SkyBlock术语表.xlsx
```

使用新版 SkyBlockZH 语料重新生成内置数据：

```
python tools/export_skyblockzh.py <SkyBlockZH 目录> <输出目录>
```

## 更新

- 模组在加入服务器时检查 GitHub 上的最新正式版，有新版本时在聊天栏提供下载链接。可在设置中关闭「检查更新」。
- 升级时以新版 jar 替换 `mods` 目录中的旧版即可，设置与已存译文会保留。
- 各版本的变更见 [CHANGELOG.md](CHANGELOG.md)。版本号格式为 `<原模组版本>-zh.<序号>`；带 `-beta` 后缀的为预发布版本，不会通过游戏内更新提示推送。

## 从源码构建

需要 JDK 25。

```
gradlew build -PmcTarget=26.2
gradlew build -PmcTarget=26.3
```

构建产物位于 `build/libs/`。Windows 用户也可运行 `一键编译.bat`：脚本会在缺少 JDK 25 时下载便携版至 `jdk` 目录，并依次构建两个版本。

每次推送都会由 GitHub Actions 构建两个版本，构建产物可在对应运行的 Artifacts 中下载。版本发布流程见 [docs/RELEASING.md](docs/RELEASING.md)。

## 常见问题

**使用本模组是否违反 Hypixel 规则？**
本模组仅在客户端替换显示的文字，不发送数据包，也不执行任何自动化操作，属于显示类修改。具体规则以 Hypixel 官方说明为准。

**AI 接口的调用量大吗？**
命中短语库、术语表或本地词典的文本不会调用 AI；同一文本只翻译一次，数值变化也不会触发重新翻译。随着词典积累，调用量会明显下降。

**某一行没有被翻译怎么办？**
输入 `/translate debug` 后将鼠标停留在该物品上，日志中会记录该行的处理过程。提交问题时请附上相关日志。

## 许可与致谢

本项目以 [GPL-3.0](LICENSE) 协议发布，版权与出处说明见 [NOTICE.md](NOTICE.md)。

- **原模组**：KALPSIZ__ 的 [NPC & Items Translator](https://github.com/knutolof06/hypixel-skyblock-npc-and-items-translate)（MIT），其版权声明保留在 NOTICE.md 中。原模组的多语言说明文档存档于 [docs/upstream](docs/upstream/)。
- **内置数据**：固定短语库与术语表导出自 [SkyBlockZH](https://github.com/BingKKni/SkyBlockZH)（GPL-3.0）。
- **附魔译名**：参考 Minecraft 官方中文译名及 [Hypixel SkyBlock 中文维基](https://hypixel-skyblock.fandom.com/zh/wiki/%E9%99%84%E9%AD%94)。

Hypixel 与 Hypixel SkyBlock 是 Hypixel Inc. 的商标；Minecraft 是 Mojang Studios 的商标。
