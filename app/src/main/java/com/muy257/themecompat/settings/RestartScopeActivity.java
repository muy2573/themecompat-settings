package com.muy257.themecompat.settings;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.muy257.themecompat.R;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 重启作用域: the actual LSPosed scope as a checkbox list plus a refresh
 *  action, so the owner picks exactly which scoped processes to end instead
 *  of restarting the whole scope blind. */
public final class RestartScopeActivity extends Activity {
    private final Set<String> checked = new HashSet<>();
    private final List<Ui.MiuixCheckBox> rowBoxes = new ArrayList<>();
    private final List<String> rowPackages = new ArrayList<>();
    private Ui.MiuixCheckBox selectAllBox;

    private LinearLayout listCard;
    private TextView statusView;
    private TextView resultView;
    private View restartRow;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = Ui.bounceScrollView(this);
        scroll.setBackgroundColor(Ui.pageColor(this));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 32));

        TextView title = Ui.title(this, "重启作用域", 24);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(title);
        statusView = Ui.body(this, ThemeCompatApplication.scopeStatusLine(), 13,
                Ui.textSecondary(this));
        statusView.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(statusView);

        LinearLayout actionsCard = Ui.cardContainer(this);
        actionsCard.addView(Ui.row(this, R.drawable.ic_refresh, "刷新作用域列表",
                "重新读取 LSPosed 实际勾选的作用域", false,
                view -> refreshScope(true)));
        actionsCard.addView(Ui.separator(this, 51));
        restartRow = Ui.row(this, R.drawable.ic_hook, "重启勾选的进程",
                "结束勾选应用的进程，下次打开时重新加载 Hook", false,
                view -> restartChecked());
        actionsCard.addView(restartRow);
        page.addView(actionsCard, Ui.cardParams(this));

        resultView = Ui.body(this, "", 12.5f, Ui.textSecondary(this));
        resultView.setPadding(Ui.dp(this, 22), Ui.dp(this, 10), Ui.dp(this, 22), 0);
        page.addView(resultView);

        page.addView(Ui.sectionTitle(this, "作用域应用"));
        LinearLayout selectAllLine = new LinearLayout(this);
        selectAllLine.setOrientation(LinearLayout.HORIZONTAL);
        selectAllLine.setGravity(android.view.Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lineParams.topMargin = Ui.dp(this, 4);
        selectAllLine.setLayoutParams(lineParams);
        TextView sectionLabel = Ui.body(this, "全选", 13, Ui.textSecondary(this));
        sectionLabel.setPadding(Ui.dp(this, 30), 0, 0, 0);
        sectionLabel.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        selectAllLine.addView(sectionLabel);
        selectAllBox = new Ui.MiuixCheckBox(this);
        selectAllBox.setState(Ui.MiuixCheckBox.STATE_ON);
        selectAllBox.setOnCheckedChange(value -> applySelectAll(value));
        LinearLayout.LayoutParams selectAllParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        selectAllParams.rightMargin = Ui.dp(this, 30);
        selectAllLine.addView(selectAllBox, selectAllParams);
        page.addView(selectAllLine);
        listCard = Ui.cardContainer(this);
        page.addView(listCard, Ui.cardParams(this));

        scroll.addView(page);
        setContentView(scroll);
        Ui.applyStatusBar(this);
        Ui.applyTopInset(this, scroll);
        refreshScope(false);
    }

    private void refreshScope(boolean announce) {
        setStatus("正在读取 LSPosed 作用域…");
        new Thread(() -> {
            String message = ThemeCompatApplication.refreshScope();
            runOnUiThread(() -> {
                setStatus(message);
                rebuildList();
                if (announce) {
                    Toast.makeText(this, "已刷新："
                            + ThemeCompatApplication.selectedScopeSnapshot().size()
                            + " 个作用域", Toast.LENGTH_SHORT).show();
                }
            });
        }, "ThemeCompatScopeRefresh").start();
    }

    private void rebuildList() {
        List<String> scope = ThemeCompatApplication.selectedScopeSnapshot();
        listCard.removeAllViews();
        checked.clear();
        if (scope.isEmpty()) {
            TextView empty = Ui.body(this,
                    "未读取到作用域。请先在 LSPosed 管理器中勾选本模块的作用域应用，"
                            + "再回到本页点击“刷新作用域列表”。", 13, Ui.textSecondary(this));
            empty.setPadding(Ui.dp(this, 16), Ui.dp(this, 12),
                    Ui.dp(this, 16), Ui.dp(this, 12));
            listCard.addView(empty);
            return;
        }
        PackageManager pm = getPackageManager();
        List<String[]> rows = new ArrayList<>();
        for (String packageName : scope) {
            String label;
            try {
                label = String.valueOf(pm.getApplicationLabel(
                        pm.getApplicationInfo(packageName, 0)));
            } catch (Throwable ignored) {
                label = packageName + "（未安装）";
            }
            rows.add(new String[]{label, packageName});
        }
        java.text.Collator collator = java.text.Collator.getInstance(Locale.CHINA);
        rows.sort((left, right) -> collator.compare(left[0], right[0]));

        rowBoxes.clear();
        rowPackages.clear();
        for (String[] row : rows) {
            listCard.addView(Ui.separator(this, 51));
            checked.add(row[1]);
            Ui.MiuixCheckBox box = new Ui.MiuixCheckBox(this);
            box.setState(Ui.MiuixCheckBox.STATE_ON);
            box.setOnCheckedChange(value -> {
                if (value) checked.add(row[1]);
                else checked.remove(row[1]);
                syncSelectAll();
            });
            rowBoxes.add(box);
            rowPackages.add(row[1]);
            LinearLayout container = Ui.row(this, 0, row[0], row[1], false,
                    view -> box.performClick());
            container.addView(box, checkboxParams());
            listCard.addView(container);
        }
        syncSelectAll();
    }

    /** Select-all tap: mixed or off selects everything, on clears everything. */
    private void applySelectAll(boolean selectAll) {
        checked.clear();
        int state = selectAll ? Ui.MiuixCheckBox.STATE_ON : Ui.MiuixCheckBox.STATE_OFF;
        for (Ui.MiuixCheckBox box : rowBoxes) box.setState(state);
        if (selectAll) checked.addAll(rowPackages);
    }

    private void syncSelectAll() {
        if (selectAllBox == null) return;
        boolean all = checked.size() == rowBoxes.size() && !rowBoxes.isEmpty();
        boolean none = checked.isEmpty();
        selectAllBox.setState(all ? Ui.MiuixCheckBox.STATE_ON
                : none ? Ui.MiuixCheckBox.STATE_OFF
                : Ui.MiuixCheckBox.STATE_PARTIAL);
    }

    private LinearLayout.LayoutParams checkboxParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = Ui.dp(this, 10);
        return params;
    }

    private void setStatus(String message) {
        statusView.setText(message);
    }

    private void restartChecked() {
        List<String> selected = new ArrayList<>(checked);
        if (selected.isEmpty()) {
            Toast.makeText(this, "请先勾选需要重启的应用", Toast.LENGTH_SHORT).show();
            return;
        }
        restartRow.setClickable(false);
        resultView.setText("正在重启 " + selected.size() + " 个应用的进程…");
        new Thread(() -> {
            RootScopeProcessRestarter.RootStatus root = RootScopeProcessRestarter.checkRoot();
            if (!root.available) {
                runOnUiThread(() -> {
                    resultView.setText(root.message);
                    restartRow.setClickable(true);
                    Toast.makeText(this, "Root 不可用，未结束任何进程",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            RootScopeProcessRestarter.RestartResult result =
                    RootScopeProcessRestarter.stopScopedPackages(selected);
            runOnUiThread(() -> {
                StringBuilder text = new StringBuilder("已结束 ")
                        .append(result.stopped.size()).append(" 个进程");
                if (!result.skipped.isEmpty()) {
                    text.append("\n跳过：").append(String.join("；", result.skipped));
                }
                if (!result.failed.isEmpty()) {
                    text.append("\n失败：").append(String.join("；", result.failed));
                }
                text.append("\n下次打开对应应用时将重新加载 Hook。");
                resultView.setText(text);
                restartRow.setClickable(true);
                Toast.makeText(this, "已处理 " + result.stopped.size() + " 个作用域应用",
                        Toast.LENGTH_LONG).show();
            });
        }, "ThemeCompatScopeRestart").start();
    }
}
