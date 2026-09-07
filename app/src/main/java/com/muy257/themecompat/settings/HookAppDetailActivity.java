package com.muy257.themecompat.settings;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * One hooked app: its Hook elements as rows; tapping a row expands an
 * embedded, height-capped source viewer so the page never grows beyond one
 * screen per element.
 */
public final class HookAppDetailActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        String name = getIntent().getStringExtra("name");
        String packageName = getIntent().getStringExtra("package");
        HookRegistry.HookApp app = find(name, packageName);

        ScrollView scroll = Ui.bounceScrollView(this);
        scroll.setBackgroundColor(Ui.pageColor(this));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 32));

        TextView title = Ui.title(this, name == null ? "Hook 详情" : name, 24);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(title);
        TextView summary = Ui.body(this, packageName == null ? "" : packageName, 13,
                Ui.textSecondary(this));
        summary.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(summary);

        if (app == null) {
            TextView missing = Ui.body(this, "未找到该应用的 Hook 记录。", 14,
                    Ui.textSecondary(this));
            missing.setPadding(Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 22), 0);
            page.addView(missing);
        } else {
            for (HookRegistry.HookItem item : app.items) {
                page.addView(elementCard(item));
            }
        }
        scroll.addView(page);
        setContentView(scroll);
        Ui.applyStatusBar(this);
        Ui.applyTopInset(this, scroll);
    }

    private HookRegistry.HookApp find(String name, String packageName) {
        for (HookRegistry.HookApp candidate : HookRegistry.apps()) {
            if (candidate.name.equals(name) && candidate.packageName.equals(packageName)) {
                return candidate;
            }
        }
        return null;
    }

    private View elementCard(HookRegistry.HookItem item) {
        LinearLayout card = Ui.cardContainer(this);
        View row = Ui.row(this, 0, item.title, item.summary, true, null);
        LinearLayout sourceBox = new LinearLayout(this);
        sourceBox.setOrientation(LinearLayout.VERTICAL);
        sourceBox.setVisibility(View.GONE);
        TextView source = new TextView(this);
        source.setText(item.source);
        source.setTextSize(11);
        source.setTypeface(Typeface.MONOSPACE);
        source.setTextColor(Ui.textSecondary(this));
        source.setLineSpacing(Ui.dp(this, 2), 1f);
        source.setPadding(Ui.dp(this, 16), Ui.dp(this, 4), Ui.dp(this, 16), Ui.dp(this, 12));
        source.setTextIsSelectable(true);
        ScrollView sourceScroll = Ui.bounceScrollView(this);
        sourceScroll.addView(source, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 250));
        scrollParams.leftMargin = Ui.dp(this, 10);
        scrollParams.rightMargin = Ui.dp(this, 10);
        scrollParams.bottomMargin = Ui.dp(this, 6);
        sourceScroll.setLayoutParams(scrollParams);
        sourceScroll.setBackground(Ui.round(Ui.night(this) ? 0xFF17181A : 0xFFF5F6F8,
                12, this));
        sourceScroll.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        sourceBox.addView(sourceScroll);
        card.addView(row);
        card.addView(separator());
        card.addView(sourceBox);
        row.setOnClickListener(view -> {
            boolean expanded = sourceBox.getVisibility() == View.VISIBLE;
            sourceBox.setVisibility(expanded ? View.GONE : View.VISIBLE);
            View chevron = row.findViewWithTag("chevron");
            if (chevron != null) {
                chevron.animate().rotation(expanded ? 0f : 90f).setDuration(160L).start();
            }
        });
        return card;
    }

    private View separator() {
        View view = new View(this);
        view.setBackgroundColor(Ui.separatorColor(this));
        view.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Ui.dp(this, 1))));
        return view;
    }
}
