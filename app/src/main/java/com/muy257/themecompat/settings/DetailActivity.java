package com.muy257.themecompat.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Static, user-facing inventory of what the MTZ generator does and does not change. */
public final class DetailActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(createContent());
        Ui.applyStatusBar(this);
    }

    private View createContent() {
        ScrollView scroll = Ui.bounceScrollView(this);
        scroll.setBackgroundColor(Ui.pageColor(this));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 32));

        TextView title = Ui.title(this, "修补详情", 24);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(title);
        TextView intro = Ui.body(this,
                "生成器只在目标资源缺失时，复制你所选主题包内已经存在的素材；"
                        + "不覆盖已有资源，也不会改原始 .mtz。",
                13, Ui.textSecondary(this));
        intro.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(intro);

        page.addView(Ui.sectionTitle(this, "静态修补：通用窗口背景别名"));
        page.addView(sectionCard(
                "设置、相机、日历、时钟、文件管理、联系人、短信、录音机、相册、应用安装器、"
                        + "指南针、通知、AI通话、翻译、垃圾清理。\n\n"
                        + "仅补齐缺少的 window_bg、miuix_appcompat_window_bg、"
                        + "miuix_appcompat_settings_window_bg、secondary_window_bg 的浅色/深色路径"
                        + "及 fallback 映射。\n\n"
                        + "深色画布缺失的模块按审计结论借用同套图：指南针、通知借短信；"
                        + "AI通话、录音机借通讯录；垃圾清理、翻译借文件管理；相册借主题商店。"
                        + "绝不借用设置的全局深色图。"));

        page.addView(Ui.sectionTitle(this, "静态修补：已验证的模块专用路径"));
        page.addView(sectionCard(
                "联系人：搜索框 MIUIX 别名。\n"
                        + "短信：修正原主题夜间 miuix_appcompat_window_bg_drak 的拼写路由；"
                        + "移除验证码页继承的白色 9-patch（由兼容模块改为挂主题画布）。\n"
                        + "文件管理：修正夜间 window_bg_dark 的 fallback 路由。\n"
                        + "主题商店：补齐 ThemeResourceProxyTabActivity 请求的无密度 drawable 路径。\n"
                        + "小米钱包：仅复制主题自带 app_brand.webp 至夜间启动页路径；主页壁纸不处理。"));

        page.addView(Ui.sectionTitle(this, "运行时兼容层（需要在 LSP 手动勾选）"));
        page.addView(sectionCard(
                "设置及跳转页、电话设置页（含移动网络）、联系人、日历、时钟、文件管理、"
                        + "短信（含验证码页）、录音机、相册、应用安装器、主题商店（含系统个性化页）、"
                        + "未成年人守护、互传、云服务、音质音效、垃圾清理、传送门、翻译、小米账号、指南针、AI通话、"
                        + "系统界面（控制中心立绘，默认关闭，可在 Hook 软件列表打开）。\n\n"
                        + "这一层处理 ROM 在主题背景上额外绘制的纯色遮罩、混淆卡片绘制器、"
                        + "月视图卡片磨砂与日程卡内部填充，并为有夜间别名的宿主应用优先使用其自身深色壁纸；"
                        + "系统界面一项只在控制中心挂载主题立绘，不向 MTZ 写入图片。"
                        + "卡片透明度可在 Hook 页统一调节。"));

        page.addView(Ui.sectionTitle(this, "明确不写入生成主题"));
        page.addView(sectionCard(
                "哔哩哔哩（静态尝试已撤销）、小米市场、扫一扫、录屏，以及当前不能在 LSP 作用域页"
                        + "选择的系统组件。这些项目会继续在适配清单中保留“未解决”状态，"
                        + "避免生成器把失败路径再次写入主题。相机深色画布为原作者素材且风格不匹配，"
                        + "保持回退，待单独设计。"));

        page.addView(Ui.sectionTitle(this, "安全边界"));
        page.addView(sectionCard(
                "先校验 description.xml 与内层 ZIP；所有新增资源均来自当前所选主题；"
                        + "已有文件绝不覆盖；原始输入包保持不变。实时进度和本次实际写入摘要"
                        + "显示在“修补”页的日志与摘要卡片中。"));
        scroll.addView(page);
        Ui.applyTopInset(this, scroll);
        return scroll;
    }

    private View sectionCard(String content) {
        LinearLayout card = Ui.cardContainer(this);
        TextView body = Ui.body(this, content, 13.5f, Ui.textPrimary(this));
        int pad = Ui.dp(this, 16);
        body.setPadding(pad, pad, pad, pad);
        card.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
