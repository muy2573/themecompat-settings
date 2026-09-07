package com.muy257.themecompat.settings;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * Card translucency tuning: one slider for daylight and one for night,
 * written to the LSPosed remote preferences the hook side re-reads every
 * few seconds.  Defaults are the owner's references (72 / 61 byte alpha,
 * i.e. 0x48 / 0x3D).
 */
public final class AlphaActivity extends Activity {
    private static final String KEY_LIGHT = "light";
    private static final String KEY_DARK = "dark";
    private static final int DEFAULT_LIGHT = 0x48;
    private static final int DEFAULT_DARK = 0x3D;

    private SharedPreferences store;
    private TextView lightValue;
    private TextView darkValue;
    private MiuiSlider lightSlider;
    private MiuiSlider darkSlider;
    private LinearLayout lightPreviewCard;
    private LinearLayout darkPreviewCard;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        store = ThemeCompatApplication.cardAlphaPreferences();
        ScrollView scroll = Ui.bounceScrollView(this);
        scroll.setBackgroundColor(Ui.pageColor(this));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 32));

        TextView title = Ui.title(this, "卡片透明度调节", 24);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(title);

        if (store == null) {
            LinearLayout card = Ui.cardContainer(this);
            TextView offline = Ui.body(this,
                    "LSPosed 服务尚未连接，无法读取或保存。请确认本模块已在 LSPosed "
                            + "中启用后重新打开应用。", 13.5f, Ui.textPrimary(this));
            int pad = Ui.dp(this, 16);
            offline.setPadding(pad, pad, pad, pad);
            card.addView(offline);
            page.addView(card, Ui.cardParams(this));
            scroll.addView(page);
            setContentView(scroll);
        Ui.applyStatusBar(this);
            Ui.applyTopInset(this, scroll);
            return;
        }

        page.addView(Ui.sectionTitle(this, "浅色模式"));
        page.addView(alphaCard(KEY_LIGHT, DEFAULT_LIGHT, true));
        page.addView(Ui.sectionTitle(this, "深色模式"));
        page.addView(alphaCard(KEY_DARK, DEFAULT_DARK, false));

        page.addView(Ui.sectionTitle(this, "预览"));
        LinearLayout preview = new LinearLayout(this);
        preview.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams previewParams = Ui.cardParams(this);
        previewParams.topMargin = Ui.dp(this, 6);
        preview.setLayoutParams(previewParams);
        FrameLayout lightTile = previewTile(true);
        FrameLayout darkTile = previewTile(false);
        lightPreviewCard = lightTile.findViewWithTag("preview_card");
        darkPreviewCard = darkTile.findViewWithTag("preview_card");
        LinearLayout.LayoutParams lightParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 132), 1f);
        LinearLayout.LayoutParams darkParams = new LinearLayout.LayoutParams(
                0, Ui.dp(this, 132), 1f);
        darkParams.leftMargin = Ui.dp(this, 12);
        preview.addView(lightTile, lightParams);
        preview.addView(darkTile, darkParams);
        page.addView(preview);

        LinearLayout resetCard = Ui.cardContainer(this);
        resetCard.addView(Ui.row(this, 0, "恢复默认值", null, false, view -> {
            store.edit().putInt(KEY_LIGHT, DEFAULT_LIGHT)
                    .putInt(KEY_DARK, DEFAULT_DARK).commit();
            refreshAll();
            Toast.makeText(this, "已恢复默认", Toast.LENGTH_SHORT).show();
        }));
        page.addView(resetCard, Ui.cardParams(this));

        scroll.addView(page);
        setContentView(scroll);
        Ui.applyStatusBar(this);
        Ui.applyTopInset(this, scroll);
    }

    private int current(String key, int fallback) {
        try {
            return store.getInt(key, fallback);
        } catch (Throwable error) {
            return fallback;
        }
    }

    private View alphaCard(String key, int fallback, boolean light) {
        LinearLayout card = Ui.cardContainer(this);
        int initial = current(key, fallback);

        LinearLayout labelRow = new LinearLayout(this);
        labelRow.setOrientation(LinearLayout.HORIZONTAL);
        labelRow.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Ui.dp(this, 16);
        labelRow.setPadding(pad, pad, pad, 0);
        TextView label = Ui.title(this, light ? "浅色卡片不透明度" : "深色卡片不透明度", 15);
        labelRow.addView(label, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = Ui.body(this, percentText(initial), 14, Ui.ACCENT);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        labelRow.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(labelRow);

        TextView byteNote = Ui.body(this, byteText(initial), 12, Ui.textSecondary(this));
        byteNote.setPadding(pad, Ui.dp(this, 2), pad, 0);
        card.addView(byteNote);

        MiuiSlider slider = new MiuiSlider(this);
        slider.setValue(initial / 255f);
        LinearLayout.LayoutParams sliderParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 44));
        sliderParams.leftMargin = pad;
        sliderParams.rightMargin = pad;
        slider.setLayoutParams(sliderParams);
        if (light) lightSlider = slider; else darkSlider = slider;
        if (light) lightValue = value; else darkValue = value;
        slider.setListener(rawValue -> {
            int alpha = Math.round(rawValue * 255f);
            value.setText(percentText(alpha));
            byteNote.setText(byteText(alpha));
            store.edit().putInt(key, alpha).apply();
            updatePreview(light, alpha);
        });
        card.addView(slider);
        return card;
    }

    /** Preview bases are fixed page-background colours so both modes stay readable. */
    private void updatePreview(boolean light, int alpha) {
        LinearLayout card = light ? lightPreviewCard : darkPreviewCard;
        if (card != null) {
            card.setBackground(Ui.round(Color.argb(alpha, 255, 255, 255), 22, this));
        }
    }

    private void refreshAll() {
        int light = current(KEY_LIGHT, DEFAULT_LIGHT);
        int dark = current(KEY_DARK, DEFAULT_DARK);
        if (lightSlider != null) {
            lightSlider.setValue(light / 255f);
            lightValue.setText(percentText(light));
        }
        if (darkSlider != null) {
            darkSlider.setValue(dark / 255f);
            darkValue.setText(percentText(dark));
        }
        updatePreview(true, light);
        updatePreview(false, dark);
    }

    private String percentText(int alpha) {
        return Math.round(alpha / 255f * 100f) + "%";
    }

    private String byteText(int alpha) {
        return String.format("alpha %d/255（0x%02X）· 透明度 %d%%",
                alpha, alpha, 100 - Math.round(alpha / 255f * 100f));
    }

    /** Fixed-base sample: light tile sits on the daylight page colour, dark tile on the
     *  night page colour, regardless of the current system mode — the card on top is the
     *  only thing that changes with the sliders. */
    private FrameLayout previewTile(boolean light) {
        int base = light ? 0xFFF2F3F5 : 0xFF111113;
        int alpha = current(light ? KEY_LIGHT : KEY_DARK, light ? DEFAULT_LIGHT : DEFAULT_DARK);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackground(Ui.round(base, 18, this));
        LinearLayout card = new LinearLayout(this);
        card.setGravity(Gravity.CENTER);
        card.setTag("preview_card");
        card.setBackground(Ui.round(Color.argb(alpha, 255, 255, 255), 22, this));
        TextView label = new TextView(this);
        label.setText(light ? "浅色" : "深色");
        label.setTextSize(12.5f);
        label.setTextColor(light ? 0xFF181819 : 0xFFF2F2F4);
        card.addView(label);
        tile.addView(card, new FrameLayout.LayoutParams(
                Ui.dp(this, 128), Ui.dp(this, 76), Gravity.CENTER));
        return tile;
    }
}
