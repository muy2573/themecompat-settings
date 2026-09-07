package com.muy257.themecompat.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/**
 * Stable card alpha for the SMS verification-code page cards.
 *
 * v0.33.24 set VerificationCardUI's GradientDrawable to alpha 0x90 once at
 * construction plus two posted passes; the canvas trace then proved the page
 * draws the same cards alternating between alpha 255 and 0x90, because the
 * list rebinds and re-applies its own alpha after our posted passes run.
 * Enforcing the alpha immediately before every card draw is the only hook
 * point that cannot be overtaken by a rebind.
 *
 * v0.33.27: the alpha is enforced in both day and night modes.  The first
 * version filtered on a near-white base colour, which silently skipped the
 * dark card (#1C-ish fill) and left it opaque over the dark artwork.  The
 * receiver is scoped to VerificationCardUI and the background to
 * GradientDrawable, so the card fill is the only drawable this touches.
 */
final class MessagingVerificationCardAdapter {
    private static final String PACKAGE_NAME = "com.android.mms";
    private static final String CARD_CLASS = "com.miui.smsextra.ui.VerificationCardUI";
    private static final String LOG_TAG = "MessagingVerifyCards";
    private static final int CARD_ALPHA = 0x90;

    private final XposedModule module;
    private final ClassLoader classLoader;
    private volatile boolean logged;

    MessagingVerificationCardAdapter(XposedModule module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() {
        Class<?> cardType;
        try {
            cardType = Class.forName(CARD_CLASS, false, classLoader);
        } catch (Throwable error) {
            module.log(Log.ERROR, LOG_TAG, "Cannot resolve " + CARD_CLASS, error);
            return;
        }
        try {
            Method draw = View.class.getDeclaredMethod("draw", Canvas.class);
            draw.setAccessible(true);
            module.hook(draw).intercept(chain -> {
                Object receiver = chain.getThisObject();
                if (!(receiver instanceof View) || !cardType.isInstance(receiver)) {
                    return chain.proceed(chain.getArgs().toArray(new Object[0]));
                }
                adjustBeforeDraw((View) receiver);
                return chain.proceed(chain.getArgs().toArray(new Object[0]));
            });
            module.log(Log.INFO, LOG_TAG, "per-draw alpha guard installed");
        } catch (Throwable error) {
            module.log(Log.ERROR, LOG_TAG, "Cannot hook View.draw", error);
        }
    }

    private void adjustBeforeDraw(View card) {
        Context context = card.getContext();
        if (context == null || !PACKAGE_NAME.equals(context.getPackageName())) return;
        Drawable background = card.getBackground();
        if (!(background instanceof GradientDrawable)) return;
        GradientDrawable gradient = (GradientDrawable) background;
        if (gradient.getAlpha() == CARD_ALPHA) return;
        try {
            gradient.mutate();
            gradient.setAlpha(CARD_ALPHA);
            card.invalidate();
        } catch (Throwable error) {
            module.log(Log.WARN, LOG_TAG, "cannot adjust card alpha", error);
            return;
        }
        if (!logged) {
            logged = true;
            module.log(Log.INFO, LOG_TAG, "per-draw alpha enforced target=0x"
                    + Integer.toHexString(CARD_ALPHA));
        }
    }
}
