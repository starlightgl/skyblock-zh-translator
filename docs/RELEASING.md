# 版本发布流程

本文档面向维护者，说明版本号规则与发布步骤。

## 版本号

格式为 `<原模组版本>-zh.<序号>`：

- `1.5.1-zh.2`：基于原模组 1.5.1 的第 2 个正式版。
- `1.5.1-zh.3-beta.1`：`1.5.1-zh.3` 的第 1 个预发布版本。

版本号定义在 `gradle.properties` 的 `mod_version` 中，构建产物文件名为 `npc_translator_skyblock_zh-<版本号>+mc<Minecraft 版本>.jar`。

## 发布步骤

1. 修改 `gradle.properties` 中的 `mod_version`。
2. 在 `CHANGELOG.md` 顶部添加对应版本的条目，标题格式为 `## [版本号] - YYYY-MM-DD`，按「新增 / 变更 / 修复 / 移除 / 构建」分类列出变更。预发布版本可直接使用对应正式版的条目。
3. 提交更改并推送到 `skyblock-zh` 分支。
4. 创建并推送标签 `v<版本号>`，例如：

   ```
   git tag -a v1.5.1-zh.3 -m "1.5.1-zh.3"
   git push origin v1.5.1-zh.3
   ```

推送标签后，GitHub Actions（`.github/workflows/build.yml`）会依次执行：

1. 校验标签与 `mod_version` 是否一致；
2. 以 `.github/release-header.md` 和 `CHANGELOG.md` 中的对应条目生成发布说明，创建草稿发布；
3. 分别构建 Minecraft 26.2 与 26.3 版本，并将 jar 上传至该发布；
4. 构建全部成功后正式发布。带 `-beta`、`-pre` 或 `-rc` 后缀的版本标记为预发布，不会成为「最新版本」，也不会通过游戏内的更新检查推送。

任一步骤失败时发布保持草稿状态。修复后可将标签移至新的提交并重新推送（`git push -f origin v<版本号>`），工作流会更新同一发布。

## 日常构建

每次推送到 `skyblock-zh` 分支或提交 Pull Request 时，工作流都会构建两个版本，构建产物可在对应运行的 Artifacts 中下载。
