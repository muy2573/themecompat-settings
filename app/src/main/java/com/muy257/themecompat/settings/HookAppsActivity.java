package com.muy257.themecompat.settings;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Hook 软件 list: every curated app, its adapter count, and a per-app
 *  on/off switch for the whole runtime hook layer of that app. */
public final class HookAppsActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = Ui.bounceScrollView(this);
        scroll.setBackgroundColor(Ui.pageColor(this));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 32));

        TextView title = Ui.title(this, "Hook 软件", 24);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(title);
        TextView summary = Ui.body(this,
                "点击应用查看具体 Hook 的元素，条目可展开查看源码。右侧开关控制该应用的"
                        + " Hook 是否加载：改动在该应用下次启动时生效"
                        + "（可用 Hook 页的“重启作用域”立即生效）。"
                        + "除系统界面默认关闭外，其余默认开启。", 13,
                Ui.textSecondary(this));
        summary.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(summary);

        LinearLayout card = Ui.cardContainer(this);
        boolean first = true;
        for (HookRegistry.HookApp app : HookRegistry.apps()) {
            if (!first) card.addView(Ui.separator(this, 51));
            first = false;
            LinearLayout row = Ui.row(this, 0, app.name, app.packageName + " · "
                            + app.items.size() + " 项 Hook", false,
                    view -> startActivity(new Intent(this, HookAppDetailActivity.class)
                            .putExtra("name", app.name)
                            .putExtra("package", app.packageName)));
            row.addView(buildSwitch(app.name, app.packageName), switchParams());
            card.addView(row);
        }
        page.addView(card, Ui.cardParams(this));
        scroll.addView(page);
        setContentView(scroll);
        Ui.applyStatusBar(this);
        Ui.applyTopInset(this, scroll);
    }

    private View buildSwitch(String appName, String packageName) {
        Ui.MiuixSwitch toggle = new Ui.MiuixSwitch(this);
        android.content.SharedPreferences store =
                ThemeCompatApplication.hookSwitchPreferences();
        boolean enabled = store != null
                ? store.getBoolean(HookSwitches.key(packageName),
                        HookSwitches.defaultEnabled(packageName))
                : HookSwitches.defaultEnabled(packageName);
        toggle.setChecked(enabled);
        toggle.setOnCheckedChange(value -> {
            android.content.SharedPreferences editable =
                    ThemeCompatApplication.hookSwitchPreferences();
            if (editable == null) {
                toggle.setChecked(!value);
                return;
            }
            editable.edit().putBoolean(HookSwitches.key(packageName), value).apply();
            Toast.makeText(this, appName + " 的 Hook 将在其下次启动时"
                            + (value ? "加载" : "跳过"),
                    Toast.LENGTH_SHORT).show();
        });
        return toggle;
    }

    private LinearLayout.LayoutParams switchParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(this, 10);
        return params;
    }
}
