package com.muy257.themecompat.settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Curated inventory of the runtime hook layer: which app gets which
 * adapter, what each adapter edits, and the key excerpt of its source so
 * the owner can audit every visible change from the module UI.
 */
final class HookRegistry {
    static final class HookItem {
        final String title;
        final String summary;
        final String source;

        HookItem(String title, String summary, String source) {
            this.title = title;
            this.summary = summary;
            this.source = source;
        }
    }

    static final class HookApp {
        final String name;
        final String packageName;
        final List<HookItem> items;

        HookApp(String name, String packageName, List<HookItem> items) {
            this.name = name;
            this.packageName = packageName;
            this.items = items;
        }
    }

    private HookRegistry() { }

    static List<HookApp> apps() {
        List<HookApp> apps = new ArrayList<>();
        apps.add(new HookApp("系统界面", "com.android.systemui", listOf(
                new HookItem("控制中心立绘",
                        "SystemUiShadeArtAdapter：控制中心落定后 200ms 淡入主题立绘；"
                                + "下拉/收起秒消，横滑分页与锁屏不显示。此 Hook 默认关闭。",
                        SRC_CC_ART))));
        apps.add(new HookApp("日历", "com.android.calendar", listOf(
                new HookItem("生命周期 + 每帧绘制守卫",
                        "Instrumentation/Activity 生命周期挂载，View.draw 每帧把日程卡重新着色",
                        SRC_CALENDAR_LIFECYCLE),
                new HookItem("月视图卡片磨砂",
                        "root_list/root 行背景替换为统一圆角半透明白（浅 0x48 / 深 0x3D）",
                        SRC_CALENDAR_FROST),
                new HookItem("日程卡内部填充剥离",
                        "纯色/分层填充按层置零，宣忌徽章等彩色件保留",
                        SRC_CALENDAR_INTERIOR),
                new HookItem("拖动指示条与底部导航",
                        "indicator 两层不透明背景与深色底栏填充清除",
                        SRC_CALENDAR_STRIP),
                new HookItem("浅色遮罩清理与还原",
                        "home_root 遮罩、miuix 面板底、灰色蒙层按模式清理与还原",
                        SRC_CALENDAR_MASK))));
        apps.add(new HookApp("AI通话", "com.xiaomi.aiasst.service", listOf(
                new HookItem("页面背景接管",
                        "SettingsSurfaceAdapter：挂主题背景并清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("设置页卡片",
                        "每帧绘制守卫：HyperCellLayout NinePatch 与白卡强制半透明",
                        SRC_AIASST_CARDS),
                new HookItem("偏好卡片装饰",
                        "VerifiedLightCardDecorationAdapter：miuix CardItemDecoration 与 "
                                + "FrameDecoration 白卡浅色 alpha 化",
                        SRC_VERIFIED_DECORATION))));
        apps.add(new HookApp("翻译", "com.xiaomi.aiasst.vision", listOf(
                new HookItem("页面背景接管",
                        "SettingsSurfaceAdapter：翻译主页挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("功能磁贴卡片",
                        "btn_group1 磁贴 CardView 背景色强制浅色透明度",
                        SRC_TRANSLATE_TILES))));
        apps.add(new HookApp("小米妙播", "com.milink.service", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("偏好卡片绘制",
                        "MIUIX FrameDecoration 白组卡片 alpha 处理",
                        SRC_PREFERENCE_PAINT))));
        apps.add(new HookApp("传送门", "com.miui.contentextension", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("偏好卡片装饰",
                        "VerifiedLightCardDecorationAdapter：PreferenceFragment$FrameDecoration "
                                + "白卡浅色 alpha 化",
                        SRC_VERIFIED_DECORATION))));
        apps.add(new HookApp("短信（含验证码页）", "com.android.mms", listOf(
                new HookItem("验证码页主题画布",
                        "会话艺术挂载 DecorView/ActionBarOverlayLayout，ActionBar 底清空",
                        SRC_MMS_THEME),
                new HookItem("验证码卡片透明",
                        "每帧 alpha 强制，列表重绑后仍保持",
                        SRC_MMS_CARDS))));
        apps.add(new HookApp("联系人", "com.android.contacts", listOf(
                new HookItem("页面与卡片",
                        "背景接管 + 白卡 alpha 处理",
                        SRC_CONTACTS_CARDS))));
        apps.add(new HookApp("互传", "com.miui.mishare.connectivity", listOf(
                new HookItem("页面背景接管",
                        "SettingsSurfaceAdapter：MiShareSettingsActivity 挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("小卡片背景",
                        "BusinessCardSurfaceAdapter：ServiceSmallCardView 背景浅色 alpha 化",
                        SRC_BUSINESS_CARDS),
                new HookItem("混淆卡片绘制器",
                        "hook 混淆 ItemDecoration 基类 g7.a 的 onDraw，卡片 Paint 走透明度",
                        SRC_OBFUSCATED_DECORATION))));
        apps.add(new HookApp("云服务", "com.miui.cloudservice", listOf(
                new HookItem("页面背景接管",
                        "SettingsSurfaceAdapter：MiCloudMainActivity 挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("小卡片背景",
                        "BusinessCardSurfaceAdapter：ServiceSmallCardView 背景浅色 alpha 化",
                        SRC_BUSINESS_CARDS),
                new HookItem("混淆卡片绘制器",
                        "hook 混淆 ItemDecoration 基类 o6.a 的 onDraw，卡片 Paint 走透明度",
                        SRC_OBFUSCATED_DECORATION))));
        apps.add(new HookApp("小米账号", "com.xiaomi.account", listOf(
                new HookItem("小卡片背景",
                        "BusinessCardSurfaceAdapter：两处 ServiceSmallCardView 背景 alpha 化，"
                                + "特意区分的大服务卡保留原样",
                        SRC_BUSINESS_CARDS),
                new HookItem("混淆卡片绘制器",
                        "hook 混淆装饰器基类 wb.a 的 onDraw，卡片 Paint 走透明度",
                        SRC_OBFUSCATED_DECORATION),
                new HookItem("页面背景接管",
                        "SettingsSurfaceAdapter：账号设置页白名单挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("电话/移动网络", "com.android.phone", listOf(
                new HookItem("页面背景接管",
                        "SettingsSurfaceAdapter：电话设置页白名单挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("MIUIX 组卡片绘制",
                        "FrameDecoration 白组 drawable + Canvas Paint 联合处理",
                        SRC_PREFERENCE_PAINT),
                new HookItem("SIM 卡片",
                        "移动网络 SIM 页卡片 alpha 化，夜间保留原生",
                        SRC_SIM_CARDS))));
        apps.add(new HookApp("设置", "com.android.settings", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主、模式重试",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("文件管理", "com.android.fileexplorer", listOf(
                new HookItem("页面背景接管",
                        "夜间 window_bg_dark 修补后接管页面画布",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("录音机", "com.android.soundrecorder", listOf(
                new HookItem("设置页与列表卡片",
                        "设置页背景 + 列表卡片 alpha 处理",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("相册", "com.miui.gallery", listOf(
                new HookItem("主题图层背景",
                        "GalleryThemeLayerAdapter 挂深色画布（借主题商店深色图）",
                        SRC_SETTINGS_SURFACE),
                new HookItem("页面背景接管",
                        "MessagingSurfaceAdapter（GallerySurface）：保留主题窗口画布，"
                                + "清理其上的回落纯色底",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("时钟", "com.android.deskclock", listOf(
                new HookItem("页面背景接管",
                        "时钟页面画布与遮罩处理",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("系统更新", "com.android.updater", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("下载管理", "com.android.providers.downloads.ui", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("偏好卡片装饰",
                        "VerifiedLightCardDecorationAdapter：XLCardItemDecoration 白卡浅色 alpha 化",
                        SRC_VERIFIED_DECORATION))));
        apps.add(new HookApp("相机", "com.android.camera", listOf(
                new HookItem("拍摄界面保持",
                        "拍摄页自绘全幅黑底画布为原作者素材；按机主决定不强改",
                        SRC_CAMERA_KEEP),
                new HookItem("相机设置页清理",
                        "CameraSurfaceAdapter：CameraPreferenceActivity 保留主题窗口画布，"
                                + "清理其上不透明 preference 宿主",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("应用安装器", "com.miui.packageinstaller", listOf(
                new HookItem("页面背景接管",
                        "安装器页面画布与遮罩处理",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("垃圾清理", "com.miui.cleanmaster", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("主题商店", "com.android.thememanager", listOf(
                new HookItem("页面背景接管",
                        "主题商店详情页与主页画布处理",
                        SRC_SETTINGS_SURFACE),
                new HookItem("系统个性化页背景",
                        "PersonalizeActivity 挂主题背景，底部入口卡片与"
                                + "在线主题推荐区（栏头、网格、标签底板）半透明",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("主题商店极速版", "com.miui.themestore", listOf(
                new HookItem("预留开关",
                        "当前无运行时改动，仅保留启用/禁用",
                        SRC_RESERVED))));
        apps.add(new HookApp("未成年人守护", "com.miui.greenguard", listOf(
                new HookItem("未成年人模式页背景",
                        "设置内未成年人模式页（GuideHomePageActivity）挂主题背景；"
                                + "列表卡片由 VerifiedLightCardDecorationAdapter 半透明；"
                                + "应用自身首页有登录墙不处理",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("安全中心", "com.miui.securitycenter", listOf(
                new HookItem("页面背景接管",
                        "SettingsSurfaceAdapter：设置/管家类页面白名单挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("卡片表面",
                        "SecurityCenterCardSurfaceAdapter 页面与卡片处理",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("省电策略", "com.miui.powerkeeper", listOf(
                new HookItem("页面背景接管",
                        "全部活动页挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE))));
        apps.add(new HookApp("我的设置", "com.xiaomi.misettings", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("偏好卡片装饰",
                        "VerifiedLightCardDecorationAdapter：混淆装饰器 xk.i 白卡浅色 alpha 化",
                        SRC_VERIFIED_DECORATION))));
        apps.add(new HookApp("系统桌面", "com.miui.home", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("偏好卡片绘制",
                        "MIUIX FrameDecoration 白组卡片 alpha 处理",
                        SRC_PREFERENCE_PAINT))));
        apps.add(new HookApp("音质音效", "com.miui.misound", listOf(
                new HookItem("页面背景接管",
                        "具体活动白名单：挂主题背景、清理纯色宿主",
                        SRC_SETTINGS_SURFACE),
                new HookItem("偏好卡片装饰",
                        "VerifiedLightCardDecorationAdapter：混淆装饰器 h1.l$c 白卡浅色 alpha 化",
                        SRC_VERIFIED_DECORATION))));
        apps.add(new HookApp("哔哩哔哩", "tv.danmaku.bili", listOf(
                new HookItem("预留开关",
                        "页面保持原生，当前无运行时改动",
                        SRC_RESERVED))));
        apps.add(new HookApp("系统界面插件", "miui.systemui.plugin", listOf(
                new HookItem("预留开关",
                        "控制中心插件进程，当前无运行时改动",
                        SRC_RESERVED))));
        apps.add(new HookApp("指南针", "com.miui.compass", listOf(
                new HookItem("页面画布接管",
                        "SettingsSurfaceAdapter：全部活动挂适配主题自带画布；"
                                + "夜间优先指南针自己的深色画布，官方全黑设计不受影响",
                        SRC_SETTINGS_SURFACE))));
        return apps;
    }

    private static List<HookItem> listOf(HookItem... items) {
        List<HookItem> list = new ArrayList<>();
        java.util.Collections.addAll(list, items);
        return list;
    }

    private static final String INDENT = "        ";

    private static final String SRC_CC_ART = join(
            "// 立绘插在 ControlCenter 的 content_container 首位，随面板原生运动。",
            "// 展开落定（EXPANDED）后 200ms 淡入；拖动/收起（EXPANDING/COLLAPSED）秒消：",
            "boolean shown = statusBarState != KEYGUARD_STATE",
            INDENT + "&& ccStateExpanded && onCcPage;",
            "if (!shown) { art.animate().cancel(); art.setVisibility(View.GONE); return; }",
            "if (art.getVisibility() == View.VISIBLE) return;   // 淡入进行中不打断",
            "art.setAlpha(0f);",
            "art.setVisibility(View.VISIBLE);",
            "art.animate().alpha(1f).setDuration(FADE_IN_MILLIS).start();",
            "",
            "// 横滑分页进度实时读取（0=控制中心页，1=通知页）：",
            "boolean onCcPage = currentSwitchProgress() <= 0.5f;");

    private static final String SRC_CALENDAR_LIFECYCLE = join(
            "void install() {",
            INDENT + "hookAfter(Instrumentation.class, \"callActivityOnCreate\",",
            INDENT + INDENT + "new Class<?>[]{Activity.class, Bundle.class},",
            INDENT + INDENT + "(receiver, args) -> schedule((Activity) args[0]));",
            INDENT + "hookAfter(Activity.class, \"onContentChanged\", new Class<?>[]{},",
            INDENT + INDENT + "(receiver, args) -> schedule((Activity) receiver));",
            INDENT + "// 每帧守卫：晚绑定的卡片在绘制前重新磨砂",
            INDENT + "Method draw = View.class.getDeclaredMethod(\"draw\", Canvas.class);",
            INDENT + "module.hook(draw).intercept(chain -> {",
            INDENT + INDENT + "View view = (View) chain.getThisObject();",
            INDENT + INDENT + "if (view.getBackground() != null) enforceRowFrost(view);",
            INDENT + INDENT + "return chain.proceed(chain.getArgs().toArray(new Object[0]));",
            INDENT + "});",
            "}");

    private static final String SRC_CALENDAR_FROST = join(
            "private void frostRow(View view, Drawable background, boolean night) {",
            INDENT + "int wanted = Color.argb(night ? CardAlpha.dark() : CardAlpha.light(),",
            INDENT + INDENT + "255, 255, 255);",
            INDENT + "if (isAlreadyFrosted(background, wanted)) return;",
            INDENT + "session.saveRow(view, background);",
            INDENT + "GradientDrawable frost = new GradientDrawable();",
            INDENT + "frost.setShape(GradientDrawable.RECTANGLE);",
            INDENT + "frost.setCornerRadius(CARD_CORNER_RADIUS_PX);",
            INDENT + "frost.setColor(wanted);",
            INDENT + "view.setBackground(frost);",
            "}");

    private static final String SRC_CALENDAR_INTERIOR = join(
            "// 农历标题条 / 事件卡背景是 LayerDrawable/StateListDrawable：",
            "// 剥开包装后把不透明填充层置零，描边与按压涟漪保留",
            "if (isFillLikeDrawable(unwrapCurrent(background))) {",
            INDENT + "clearInteriorFill(view, background, night);",
            "} else if (background instanceof LayerDrawable) {",
            INDENT + "stripInteriorLayers(view, background);",
            "}",
            "",
            "private void stripInteriorLayers(View view, Drawable background) {",
            INDENT + "LayerDrawable layers = (LayerDrawable) background.mutate();",
            INDENT + "for (int index = 0; index < layers.getNumberOfLayers(); index++) {",
            INDENT + INDENT + "Drawable layer = layers.getDrawable(index);",
            INDENT + INDENT + "if (isFillLikeDrawable(unwrapCurrent(layer))) {",
            INDENT + INDENT + INDENT + "layer.setAlpha(0);",
            INDENT + INDENT + "}",
            INDENT + "}",
            "}");

    private static final String SRC_CALENDAR_STRIP = join(
            "private boolean isIndicatorStrip(View view) {",
            INDENT + "// layout_indicator.xml：indicator_layout 实色 + ",
            INDENT + "// indicator_container 不透明矢量（浅白/深黑）= 灰条本体",
            INDENT + "return id == indicator_layout || id == indicator_container;",
            "}",
            "",
            "private static boolean isBottomTabBar(View view) {",
            INDENT + "// 底栏无资源 id，按几何特征匹配：全宽、120-280px、贴底",
            INDENT + "return width >= 1000 && height >= 120 && height <= 280 && xy[1] >= 2400;",
            "}");

    private static final String SRC_CALENDAR_MASK = join(
            "private void clearMonthLightSurfaces(Activity activity, ...) {",
            INDENT + "// home_root 半透明白遮罩 #B3FFFFFF",
            INDENT + "if (alpha > 0 && alpha < 255 && max - min <= 24) { ... }",
            INDENT + "// miuix.smooth 面板底（浅深两版都清）",
            INDENT + "if (sheet.getClass().getName().startsWith(\"miuix.smooth\")) { ... }",
            INDENT + "// month_top_mask_view 灰色蒙层 → INVISIBLE",
            INDENT + "// RecyclerView#list #F7F7F7 兜底色",
            "}",
            "",
            "// 浅色清理在夜间/日页/停止时还原；深色清理在白天还原",
            "restoreLightOnly(activity, decor, session, pass);",
            "restoreDarkOnly(session);");

    private static final String SRC_SETTINGS_SURFACE = join(
            "private void schedule(Activity activity) {",
            INDENT + "if (!isTarget(activity)) return;   // 具体活动白名单",
            INDENT + "decor.post(() -> applyAndRelease(activity, false));",
            INDENT + "decor.postDelayed(() -> applyAndRelease(activity, true), 900L);",
            "}",
            "",
            "// apply(): 挂主题背景位图 → 清理其上的纯色宿主（ColorDrawable 兜底）",
            "// 模式切换后 ThemeResources 异步更新，scheduleModeRetry 1.5s 后重试",
            "// 夜间优先使用宿主自己的深色画布（NIGHT_OWN_DARK_PACKAGES）");

    private static final String SRC_AIASST_CARDS = join(
            "// HyperCellLayout NinePatch 与白色 GradientDrawable 行，每帧强制：",
            "private void adjustBeforeDraw(View card) {",
            INDENT + "int target = night ? CardAlpha.dark() : CardAlpha.light();",
            INDENT + "Drawable background = unwrap(card.getBackground());",
            INDENT + "if (background instanceof NinePatchDrawable) {",
            INDENT + INDENT + "background.setAlpha(target);",
            INDENT + "} else if (isLargeWhiteCard(background)) {",
            INDENT + INDENT + "background.setAlpha(target);",
            INDENT + "}",
            "}");

    private static final String SRC_TRANSLATE_TILES = join(
            "// CardView cardBackgroundColor 的 RGB 保留、alpha 换成设定值：",
            "if (current != 0 && ((current >>> 24) & 0xFF) != CardAlpha.light()) {",
            INDENT + "int target = (CardAlpha.light() << 24) | (current & 0x00FFFFFF);",
            INDENT + "card.setCardBackgroundColor(target);",
            "}");

    private static final String SRC_MMS_THEME = join(
            "// 会话艺术解析后挂到 DecorView + ActionBarOverlayLayout：",
            "decor.setBackground(artwork);",
            "overlay.setBackground(artwork);",
            "// ActionBarContainer 的灰条在 onDraw 中绘制，改用反射清主背景：",
            "Method setPrimary = actionBarContainer.getClass()",
            INDENT + ".getDeclaredMethod(\"setPrimaryBackground\", Drawable.class);",
            "setPrimary.invoke(bar, (Drawable) null);");

    private static final String SRC_MMS_CARDS = join(
            "// 验证码卡片每帧 alpha 强制（列表重绑会覆盖原值）：",
            "hook(View.class.getDeclaredMethod(\"draw\", Canvas.class)).intercept(chain -> {",
            INDENT + "enforceCardAlpha((View) chain.getThisObject());",
            INDENT + "return chain.proceed(...);",
            "});");

    private static final String SRC_CONTACTS_CARDS = join(
            "// 联系人白卡：保留 RGB、按当前模式写入共享透明度",
            "background.setAlpha(CardAlpha.light());",
            "log(\"card \" + String.format(\"#%08X\", color) + \" -> alpha set\");");

    private static final String SRC_OBFUSCATED_DECORATION = join(
            "// jadx 证据：混淆 ItemDecoration 基类用继承 Paint 画卡片行，",
            "// 颜色每次 drawPath 从卡片 ColorDrawable 字段读取。",
            "// hook 该基类 onDraw，在绘制前把字段 drawable 的 alpha 调成设定值：",
            "module.hook(baseOnDraw).intercept((chain) -> {",
            INDENT + "dimCardFields(thisObject);",
            INDENT + "return chain.proceed(chain.getArgs().toArray(new Object[0]));",
            "});");

    private static final String SRC_VERIFIED_DECORATION = join(
            "// 已验证的具体 RecyclerView ItemDecoration 白卡：",
            "// KNOWN_RENDERERS 记录每个应用的装饰器类，绘制前把卡片字段 alpha 化",
            "for (String renderer : KNOWN_RENDERERS.get(packageName)) {",
            INDENT + "hookDecoration(classLoader.loadClass(renderer));",
            "}");

    private static final String SRC_PREFERENCE_PAINT = join(
            "// 电话设置的 MIUIX FrameDecoration：",
            "// 白组 drawable 与其 Canvas Paint 一起按透明度处理",
            "hook(frameDecorationOnDraw).intercept((chain) -> {",
            INDENT + "dimGroupDrawables(thisObject);",
            INDENT + "return chain.proceed(...);",
            "});");

    private static final String SRC_SIM_CARDS = join(
            "// SIM 卡片：夜间保持原生 alpha，白天写入共享透明度",
            "int wanted = isNight(activity) ? nativeAlpha : CardAlpha.light();");

    private static final String SRC_CAMERA_KEEP = join(
            "// 现场验证：相机拍摄页在壁纸上自绘全幅黑底画布，属于原作者设计。",
            "// 按机主决定保持，不做运行时强改。");

    private static final String SRC_RESERVED = join(
            "// 预留开关：当前无运行时改动，仅保留启用/禁用，便于以后手工扩展。");

    private static final String SRC_BUSINESS_CARDS = join(
            "// jadx 证据：互传/云服务/账号的行卡片由 ServiceSmallCardView 背景绘制。",
            "// 仅按类名匹配这两处背景写入浅色透明度，不清任何普通 View 背景；",
            "// 账号那张特意区分的大服务卡保留原样：",
            "if (isServiceSmallCard(background)) {",
            INDENT + "background.setAlpha(CardAlpha.light());   // 浅色 0x48",
            "}");

    private static String join(String... lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (builder.length() > 0) builder.append('\n');
            builder.append(line);
        }
        return builder.toString();
    }
}
