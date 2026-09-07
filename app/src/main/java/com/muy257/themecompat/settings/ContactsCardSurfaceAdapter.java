package com.muy257.themecompat.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** Exact, verified card painter adjustment for Contacts only. */
final class ContactsCardSurfaceAdapter {
    private static final String PACKAGE_NAME = "com.android.contacts";
    private static final String DECORATION_CLASS = "miuix.recyclerview.card.CardItemDecoration";
    // Twice the previously tested #24 alpha: still translucent but readable on light wallpaper.
    private static final int LIGHT_CARD_ALPHA = 0x48;

    private final XposedModule module;
    private final ClassLoader classLoader;

    ContactsCardSurfaceAdapter(XposedModule module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    void install() {
        try {
            Class<?> type = Class.forName(DECORATION_CLASS, false, classLoader);
            Method resolveStyle = type.getDeclaredMethod("w", Context.class);
            resolveStyle.setAccessible(true);
            module.hook(resolveStyle).intercept(chain -> {
                Object[] args = chain.getArgs().toArray(new Object[0]);
                Object result = chain.proceed(args);
                try {
                    Context context = args.length > 0 && args[0] instanceof Context
                            ? (Context) args[0] : null;
                    apply(chain.getThisObject(), context);
                } catch (Throwable error) {
                    module.log(Log.WARN, "ContactsCards", "Cannot adjust card-group painter", error);
                }
                return result;
            });
            module.log(Log.INFO, "ContactsCards", "Hooked CardItemDecoration.w(Context)");
        } catch (Throwable error) {
            module.log(Log.ERROR, "ContactsCards", "Cannot hook " + DECORATION_CLASS, error);
        }
    }

    private void apply(Object decoration, Context context) throws ReflectiveOperationException {
        if (decoration == null || context == null || !PACKAGE_NAME.equals(context.getPackageName())) return;
        if (isNight(context)) return;
        Field field = decoration.getClass().getDeclaredField("q");
        field.setAccessible(true);
        Object value = field.get(decoration);
        if (!(value instanceof ColorDrawable)) return;
        ColorDrawable background = (ColorDrawable) value;
        int color = background.getColor();
        if (Color.alpha(color) != 255 || Color.red(color) < 232 || Color.green(color) < 232
                || Color.blue(color) < 232) return;
        background.setAlpha(CardAlpha.light());
        module.log(Log.INFO, "ContactsCards", "light cardGroupBackground "
                + String.format("#%08X", color) + " -> alpha=0x48");
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
