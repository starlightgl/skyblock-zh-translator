# NPC & Items Translator · SkyBlock 中文增强版

基于 KALPSIZ__ 的 hypixel-skyblock-npc-and-items-translate（MIT 协议），只适配 Minecraft 26.3。

## 编译

双击 `一键编译.bat`：

- 会自动查找 JDK 25；找不到就下载一个便携版放到 `jdk` 文件夹，不影响系统。
- 第一次编译要下载 Gradle、Minecraft 和 Fabric 依赖。如果开了 Clash 之类的“系统代理”，脚本会自动让 Gradle 走代理。
- 成功后，jar 会复制到上一级文件夹（`npc-translator-zh`）。

安装：把 jar 放进 26.3 的 mods 文件夹，并**删掉原版的 NPC Translator jar**（两者 Mod ID 相同）。依赖跟原版一样：Fabric API、YetAnotherConfigLib、ModMenu。

## 新增功能

1. **自定义 API（OpenAI 兼容）**
   在 `/translate → 设置 → API → 自定义 API` 里填写 Base URL、API Key、模型名。
   - Base URL 示例：`https://api.deepseek.com/v1`，模型名填 `deepseek-chat`。程序会自动补上 `/chat/completions`。
   - 物品翻译的默认快捷键是 **N**，可以在按键设置里改。
   - 想让聊天也用它：在“聊天翻译”里把翻译引擎选成 `Custom API`。
   - 想悬停自动翻译：勾选“自动翻译物品”，再把“自动翻译使用的引擎”选成 `Custom API`。
   - 推理模型输出的 `<think>…</think>` 会被自动去掉。

2. **自定义提示词**
   默认已经写好了 Hypixel SkyBlock 的背景说明，可以在“提示词 / 术语表 / 缓存”里修改。所有 AI 引擎都会带上它（Google 翻译不支持提示词）。

3. **术语表** `config/npc_translator_glossary.json`
   ```json
   { "Hyperion": "Hyperion", "Skyblock Level": "空岛等级", "Crit Damage": "暴击伤害" }
   ```
   当前文本里出现了哪些词条，就把这些词条附进提示词，让 AI 统一译名。文件保存后即时生效，以 `_` 开头的键是注释。

4. **数字占位缓存**
   翻译前把数字换成 `{0}` `{1}`，缓存的是句子模板，显示时再把真实数字填回去。数字变了不用重新翻译。万一 AI 弄丢了占位符，这一行会自动改用原文重新翻译一次，这次结果不进缓存。

5. **词典不再在退出服务器时被删除**
   原版每次断开连接都会清空词典。现在默认保留，想清空就用 `/translate DeleteDict`，或者勾选“退出服务器时清空词典”。

修改了术语表或提示词后，已经缓存的旧译文不会自动更新，需要执行一次 `/translate DeleteDict` 让它重新翻译。
