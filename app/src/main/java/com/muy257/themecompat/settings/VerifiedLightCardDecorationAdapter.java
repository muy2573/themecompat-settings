package com.muy257.themecompat.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

/**
 * Adjusts only confirmed Canvas card renderers.  The affected pages place
 * transparent item Views over a RecyclerView.ItemDecoration; changing either
 * View background therefore cannot affect the opaque card itself.
 *
 * The adapter applies immediately before ItemDecoration.onDraw/onDrawOver.
 * That matters because several HyperOS pages recreate their Paint during a
 * day/night configuration change.  Dark mode is strictly observational: no
 * field is changed while the current drawing context is dark.
 */
final class VerifiedLightCardDecorationAdapter {
    private static final int LIGHT_CARD_ALPHA = 0x48;

    private static final Map<String, List<String>> KNOWN_RENDERERS;
    static {
        Map<String, List<String>> renderers = new HashMap<>();
        renderers.put("com.xiaomi.misettings", Collections.singletonList("xk.i"));
        renderers.put("com.miui.contentextension", Collections.singletonList(
                "miuix.preference.PreferenceFragment$FrameDecoration"));
        renderers.put("com.miui.misound", Collections.singletonList("h1.l$c"));
        renderers.put("com.android.providers.downloads.ui", Collections.singletonList(
                "com.android.providers.downloads.ui.view.XLCardItemDecoration"));
        renderers.put("com.xiaomi.aiasst.service", Arrays.asList(
                "miuix.recyclerview.card.CardItemDecoration",
                "miuix.preference.PreferenceFragment$FrameDecoration"));
        // 未成年人守护 guide page: its miuix build obfuscates the preference
        // frame decoration as PreferenceFragment$d over the shared v8.a base
        // (same Paint-driven card family as the account renderer).
        renderers.put("com.miui.greenguard", Collections.singletonList(
                "miuix.preference.PreferenceFragment$d"));
        KNOWN_RENDERERS = Collections.unmodifiableMap(renderers);
    }

    private final XposedModule module;
    private final ClassLoader classLoader;
    private final String targetPackage;
    private final Map<Object, Context> contexts =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<Object, CardState> states =
            Collections.synchronizedMap(new WeakHashMap<>());

    VerifiedLightCardDecorationAdapter(XposedModule module, ClassLoader classLoader,
            String targetPackage) {
        this.module = module;
        this.classLoader = classLoader;
        this.targetPackage = targetPackage;
    }

    static boolean supports(String packageName) {
        return KNOWN_RENDERERS.containsKey(packageName);
    }

    void install() {
        List<String> names = KNOWN_RENDERERS.get(targetPackage);
        if (names == null) return;
        for (String name : names) installRenderer(name);
    }

    private void installRenderer(String name) {
        try {
            Class<?> renderer = Class.forName(name, false, classLoader);
            int constructors = hookConstructors(renderer);
            int draws = hookDraws(renderer);
            module.log(Log.INFO, "VerifiedCards", "Hooked package=" + targetPackage
                    + " renderer=" + name + " constructors=" + constructors + " draws=" + draws);
        } catch (Throwable error) {
            module.log(Log.ERROR, "VerifiedCards", "Cannot hook confirmed renderer package="
                    + targetPackage + " class=" + name, error);
        }
    }

    private int hookConstructors(Class<?> renderer) {
        int count = 0;
        for (Constructor<?> constructor : renderer.getDeclaredConstructors()) {
            try {
                constructor.setAccessible(true);
                module.hook(constructor).intercept(chain -> {
                    Object[] args = chain.getArgs().toArray(new Object[0]);
                    Object result = chain.proceed(args);
                    Context context = contextFrom(args);
                    if (context != null) contexts.put(chain.getThisObject(), context);
                    applyForLight(chain.getThisObject(), context);
                    return result;
                });
                count++;
            } catch (Throwable ignored) {
                // Synthetic vendor overloads are optional; onDraw remains the authoritative hook.
            }
        }
        return count;
    }

    private int hookDraws(Class<?> renderer) {
        int count = 0;
        for (Class<?> cursor = renderer; cursor != null && cursor != Object.class;
                cursor = cursor.getSuperclass()) {
            for (Method method : cursor.getDeclaredMethods()) {
                String name = method.getName();
                if (!"onDraw".equals(name) && !"onDrawOver".equals(name)) continue;
                try {
                    method.setAccessible(true);
                    module.hook(method).intercept(chain -> {
                        Object receiver = chain.getThisObject();
                        Object[] args = chain.getArgs().toArray(new Object[0]);
                        // An inherited ItemDecoration method can be shared by unrelated decorations.
                        if (renderer.isInstance(receiver)) {
                            Context context = contextFrom(args);
                            if (context == null) context = contexts.get(receiver);
                            applyForLight(receiver, context);
                        }
                        return chain.proceed(args);
                    });
                    count++;
                } catch (Throwable ignored) {
                    // Continue with the remaining drawing overloads.
                }
            }
        }
        return count;
    }

    private void applyForLight(Object renderer, Context context) {
        if (renderer == null || context == null || isNight(context)) return;
        if (!targetPackage.equals(context.getPackageName())) return;
        try {
            CardState state = states.get(renderer);
            if (state == null) {
                state = capture(renderer);
                states.put(renderer, state);
            }
            if (state.colors.isEmpty() && state.paints.isEmpty() && state.cards.isEmpty()) return;
            for (Drawable card : state.cards) {
                if (card.getAlpha() != LIGHT_CARD_ALPHA) card.setAlpha(LIGHT_CARD_ALPHA);
            }
            for (ColorDrawable drawable : state.colors) drawable.setAlpha(LIGHT_CARD_ALPHA);
            for (PaintState paint : state.paints) {
                int source = paint.originalColor;
                paint.paint.setColor(Color.argb(LIGHT_CARD_ALPHA, Color.red(source),
                        Color.green(source), Color.blue(source)));
            }
            if (!state.logged) {
                state.logged = true;
                module.log(Log.INFO, "VerifiedCards", "Applied shallow card alpha=0x48 package="
                        + targetPackage + " renderer=" + renderer.getClass().getName()
                        + " drawables=" + state.colors.size() + " paints=" + state.paints.size());
            }
        } catch (Throwable error) {
            module.log(Log.WARN, "VerifiedCards", "Cannot adjust confirmed renderer package="
                    + targetPackage + " class=" + renderer.getClass().getName(), error);
        }
    }

    private static CardState capture(Object renderer) throws IllegalAccessException {
        CardState state = new CardState();
        for (Class<?> type = renderer.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(renderer);
                if (value instanceof ColorDrawable) {
                    ColorDrawable drawable = (ColorDrawable) value;
                    if (isOpaqueNearWhite(drawable.getColor())) state.colors.add(drawable);
                } else if (value instanceof Paint) {
                    Paint paint = (Paint) value;
                    if (isOpaqueNearWhite(paint.getColor())) {
                        state.paints.add(new PaintState(paint, paint.getColor()));
                    }
                } else if (value instanceof Drawable
                        && value.getClass().getName().contains("CardDrawable")) {
                    // MIUI's CardDrawable fills the card through a clipped
                    // drawable draw rather than the shared Paint, and its
                    // colour cannot be read cheaply — dim by alpha instead.
                    state.cards.add((Drawable) value);
                }
            }
        }
        return state;
    }

    private static Context contextFrom(Object[] values) {
        for (Object value : values) {
            if (value instanceof Context) return (Context) value;
            if (value instanceof View) return ((View) value).getContext();
        }
        return null;
    }

    private static boolean isOpaqueNearWhite(int color) {
        if (Color.alpha(color) != 255) return false;
        return Color.red(color) >= 226 && Color.green(color) >= 226 && Color.blue(color) >= 226;
    }

    private static boolean isNight(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private static final class CardState {
        final List<ColorDrawable> colors = new ArrayList<>();
        final List<PaintState> paints = new ArrayList<>();
        final List<Drawable> cards = new ArrayList<>();
        boolean logged;
    }

    private static final class PaintState {
        final Paint paint;
        final int originalColor;

        PaintState(Paint paint, int originalColor) {
            this.paint = paint;
            this.originalColor = originalColor;
        }
    }
}
