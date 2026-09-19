# ThemeCompat Settings

面向 HyperOS 3 的 LSPosed/libxposed 主题兼容模块，同时提供：

- `.mtz` 静态修补器：补齐窗口背景别名、fallback 和少量已验证的模块专用资源。
- 运行时 Hook：清理系统应用盖在主题画布上的纯色宿主与卡片填充，并统一调节卡片透明度。
- 应用内审计页：按应用列出实际 Hook 元素、实现摘要和对应源码片段。

当前发布版本：**v1.1.0（versionCode 337）**。验证设备为 Redmi K70 Ultra / HyperOS 3。

## 静态修补

通用窗口别名覆盖设置、相机、日历、时钟、文件管理、联系人、短信、录音机、相册、应用安装器、安全中心、指南针、通知、AI 通话、翻译与垃圾清理。缺少深色画布时只按真机审计结论在同一主题内借图，不使用设置模块的全局深色图兜底。

已验证的专用修补包括：

- 设置辅助功能磁贴图标，并按包内磁贴主色重着色。
- 短信 `drak` 拼写路径、验证码页继承白底及 fallback。
- 文件管理夜间画布与 fallback。
- 主题商店无密度窗口路径及旧式中文 ZIP pathname。
- 安全中心夜间窗口别名与 fallback。
- 钱包夜间启动图；钱包主页背景不处理。

### ZIP 无损处理

修补器在业务补丁前读取 raw ZIP pathname，并依次使用严格 UTF-8、CRC 有效的 Info-ZIP `0x7075 Unicode Path`、可无损 round-trip 的 GB18030 legacy fallback 恢复 canonical 路径。

不同 raw name 恢复到同一 canonical pathname 时中止；需要业务修补的 ZIP 若含真正同名重复条目也中止，不再静默删除。只有新增或替换资源会重新压缩，未修改条目的 compressed stream、CRC、方法、extra、comment 与平台元数据保持原样。生成摘要分别报告 normalized / added / replaced / removed。

当前 NachoNeko 样本的验收结果：主题商店原始 206 条，规范化 9 个中文 pathname；业务修补后新增 13、替换 `theme_fallback.xml` 1、删除 0，其余 205 条记录保持原样。完整适配包共 67 个外层条目、46 个内层 ZIP、5734 个内层条目，全部 CRC 校验通过。

## 运行时 Hook

`HookEntry` 按包名装配专用 adapter；应用内“Hook 软件”页面是当前实现的用户可见清单。主要组件：

- `SettingsSurfaceAdapter`：具体 Activity 白名单、主题画布挂载、纯色宿主清理、模式重试。
- `BusinessCardSurfaceAdapter`、`ObfuscatedCardDecorationAdapter`、`VerifiedLightCardDecorationAdapter`、`PreferenceCardPaintAdapter`：各类 MIUIX 卡片 Drawable/Paint alpha。
- 日历、短信验证码、联系人、录音机、相册、文件管理、安装器、安全中心、相机设置页和 SystemUI 控制中心立绘等专用 adapter。
- `HookRegistry`：应用内展示的 Hook 说明及源码摘录，需与实际 adapter 同步更新。

明确限制：

- 相机拍摄页保留主题作者的全幅黑底；相机设置页首次进入有效，运行中切换明暗暂不适配。
- 钱包只做静态夜间启动图，不适配主页运行时背景。
- 哔哩哔哩、主题商店极速版及 SystemUI 插件当前只保留开关，不执行运行时改动。
- 控制中心立绘 Hook 默认关闭。

## 构建与验证

要求 JDK 17、Android SDK compileSdk 36 / buildTools 36.0.0。

```text
gradlew test assembleRelease
```

发布 APK 位于 `app/build/outputs/apk/release/app-release.apk`。Release 使用本机 debug keystore 签名，便于在同一构建机上连续升级。

`app/src/test` 中的独立审计程序覆盖 pathname 规范化、原始 ZIP 记录编辑、重复路径策略、路径穿越、非法 UTF-8、条目数上限、静态资源修补、嵌套 ZIP CRC 和原始记录差异比对。

命令行构建需在不入库的 `local.properties` 中设置 `sdk.dir`，或使用 `ANDROID_HOME`。机器相关的 `org.gradle.java.home` 应放在用户级 Gradle 配置中。
