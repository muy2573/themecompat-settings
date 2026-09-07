package com.muy257.themecompat.settings;

import android.app.Activity;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Generic second-level text page: a title plus titled section cards fed by intent extras,
 *  so short reference material (适用范围 / 使用说明 / 作用域) stays off the main pages. */
public final class TextPageActivity extends Activity {
    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        String pageTitle = getIntent().getStringExtra("title");
        String[] heads = getIntent().getStringArrayExtra("heads");
        String[] bodies = getIntent().getStringArrayExtra("bodies");

        ScrollView scroll = Ui.bounceScrollView(this);
        scroll.setBackgroundColor(Ui.pageColor(this));
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 32));

        TextView title = Ui.title(this, pageTitle == null ? "" : pageTitle, 24);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 4));
        page.addView(title);

        if (bodies != null) {
            for (int index = 0; index < bodies.length; index++) {
                LinearLayout card = Ui.cardContainer(this);
                int pad = Ui.dp(this, 16);
                String head = heads != null && index < heads.length ? heads[index] : null;
                if (head != null) {
                    TextView headView = Ui.title(this, head, 15);
                    headView.setPadding(pad, pad, pad, 0);
                    card.addView(headView);
                    TextView body = Ui.body(this, bodies[index], 13.5f, Ui.textPrimary(this));
                    body.setPadding(pad, Ui.dp(this, 6), pad, pad);
                    card.addView(body);
                } else {
                    TextView body = Ui.body(this, bodies[index], 13.5f, Ui.textPrimary(this));
                    body.setPadding(pad, pad, pad, pad);
                    card.addView(body);
                }
                page.addView(card, Ui.cardParams(this));
            }
        }
        scroll.addView(page);
        setContentView(scroll);
        Ui.applyStatusBar(this);
        Ui.applyTopInset(this, scroll);
    }

    @Override
    public void onBackPressed() {
        finish();
    }
}
