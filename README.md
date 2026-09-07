# ThemeCompat Settings

HyperOS 3 的 LSPosed/libxposed 兼容模块：把适配 .mtz 主题的壁纸/窗口画布接管到系统设置类应用的浅色页面上，并把 miuix 白卡片按用户设定的透明度（浅色/深色两档）重着色，使页面在主题壁纸上呈现半透明卡片质感。

## 结构

- `HookEntry` — 入口，按包名装配修补器（33 个包）。
- `SettingsSurfaceAdapter` — 页面背景接管核心：具体活动白名单挂主题画布、清理纯色宿主、日夜间模式重试；含主题商店 PersonalizeActivity 在线主题推荐区的卡片透明处理。
- `BusinessCardSurfaceAdapter` / `ObfuscatedCardDecorationAdapter` / `VerifiedLightCardDecorationAdapter` / `PreferenceCardPaintAdapter` — 各应用 preference 白卡（含混淆 ItemDecoration）的 Paint/Drawable alpha 处理。
- 各应用专属 adapter（日历、相机、短信、联系人、录音机、相册、时钟、文件管理、安装器、SystemUI 控制中心立绘等）。
- `HookRegistry` — 模块 UI 的 Hook 清单（应用 → 条目 → 源码摘要），供逐项审计。
- `MtzCompatibilityPatcher` — 适配 .mtz 的资源侧补丁。

## 构建

要求：JDK 17、Android SDK（compileSdk 36 / buildTools 36.0.0）。

```
gradlew assembleDebug      # 调试包
gradlew assembleRelease    # 发布包（debug keystore 签名，可直接安装）
```

命令行构建需在 `local.properties` 写入 `sdk.dir=<SDK 路径>`，或设置 `ANDROID_HOME` 环境变量（该文件不入库）。

`org.gradle.java.home` 这类机器相关配置请放在用户级 `~/.gradle/gradle.properties`，不要提交到仓库。

产物：`app/build/outputs/apk/{debug,release}/`，在 LSPosed 中启用并勾选作用域后重启对应应用生效。
