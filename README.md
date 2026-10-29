# 小说连载工坊 (Novel Studio) — 安卓版

把桌面版 `novel-studio`（Python/tkinter）的「AI 写短篇连载」八步流程完整移植到安卓，功能一一对应。
默认对接局域网大模型 **`http://100.107.217.62:1234/v1`**（模型 `qwen3.8-27b`），可在设置页修改。

已针对 **vivo X Fold3 Pro 折叠屏**适配：展开大屏（values-w900dp / values-w1240dp 双断点）提供更
宽的左右分栏、更大的触控目标；折叠态为上下紧凑布局。展开/折叠切换不重启应用（configChanges 已处理），
页面用常驻 Fragment 切换，**切页不中断长任务、流式输出不丢**。

## 安装

```bash
adb install -r app/build/outputs/apk/release/app-release.apk   # 正式版（已签名）
# 或调试版：app/build/outputs/apk/debug/app-debug.apk
```

正式版签名密钥为本项目自带的 `release.keystore`（alias `novelstudio`，密码在
`release-signing.properties`，**升级必须用同一个 keystore，请连同 properties 一起备份**）。

## 八步流程（与顶部标签页一一对应）

| 步骤 | 页面 | 说明 |
| --- | --- | --- |
| 1 | 设置与项目 | API 地址/模型/温度/max_tokens →「测试连接」→ 新建/打开项目 |
| 2 | 范文提炼文风 | 导入范文 txt（系统文件选择器）→ AI 提炼文风 → 人工修改保存 |
| 2.5 | 范文拆解仿写 | AI 分段通读范文 → 拆成完整项目（文风/人物/关系/大纲 + 逐章摘要 + 原文按章归档正文）；可填仿写设定改写实例 → 应用后到第 7 页逐章生成本文即得仿写小说 |
| 3 | 人物档案 | 14 字段逐个录入，存 `characters.json` |
| 4 | 人物关系 | 7 字段表格式录入，存 `relationships.json` |
| 5 | 剧情大纲 | 9 字段逐章录入，保存自动排序重编号 |
| 6 | 章节摘要 | 提示词透明化：选章自动填充「从剧情大纲生成本章摘要」的提示词（含生成方法说明+按本章大纲裁剪的文风/人物/关系），可编辑后一键生成 → 修订 → 保存 |
| 7 | 章节正文 | **流式生成**（边生成边显示，可随时「中止」，已生成部分保留）→ 修订 → 保存 → 导出全文 md。默认只带本章出场人物（按摘要命中裁剪档案与关系） |

## 项目文件存储

**`内部存储/Download/NovelStudio/projects/<项目名>/`** —— 手机文件管理器和电脑 USB 都能直接
查看编辑，全部为可读的 md/json 文本（project.json / samples / materials / chapters/summaries /
chapters/drafts / 全文.md），与桌面版项目目录互拷即可互通。

首次启动会引导授予「所有文件访问」权限（Android 11+ 必需，未授权则回退到应用专属目录
Android/data/ 下）。旧位置已有的项目会自动迁移到 Download 下。

## 构建

复用 nas-browser 的离线工具链（JDK 17 + Gradle 8.9 + SDK）：

```bash
cd novel-studio-app
set JAVA_HOME=C:\Users\excel\WorkBuddy\Claw\nas-browser\toolchain\jdk\jdk-17.0.20+8
C:\Users\excel\WorkBuddy\Claw\nas-browser\toolchain\gradle-8.9\bin\gradle.bat --offline assembleDebug
```

`local.properties` 已指向 `nas-browser/toolchain/sdk`；依赖走本机 Gradle 缓存，离线可构建。

## 权限

仅 `INTERNET` / `ACCESS_NETWORK_STATE`（访问局域网大模型）。已开启 `usesCleartextTraffic`
以支持 http 明文 API。
