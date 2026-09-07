package com.muy257.themecompat.settings;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/**
 * Read-only bridge to the framework's public service. This deliberately does
 * not call requestScope/removeScope: the user remains the sole editor of the
 * module scope in LSPosed.
 */
public final class ThemeCompatApplication extends Application
        implements XposedServiceHelper.OnServiceListener {
    private static final String TAG = "ThemeCompatScope";
    private static final String CARD_ALPHA_STORE = "card_alpha";
    private static final String HOOK_SWITCH_STORE = "hook_switches";
    private static volatile List<String> selectedScope = Collections.emptyList();
    private static volatile String status = "等待 LSPosed 服务连接";
    private static volatile android.content.SharedPreferences cardAlphaStore;
    private static volatile android.content.SharedPreferences hookSwitchStore;
    private static volatile XposedService service;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        // Modern libxposed deliberately requires explicit registration.
        // This mirrors HyperCeiler's public service-bridge initialization.
        XposedServiceHelper.registerListener(this);
    }

    @Override
    public void onServiceBind(XposedService bound) {
        service = bound;
        refreshScope();
        try {
            cardAlphaStore = service.getRemotePreferences(CARD_ALPHA_STORE);
            hookSwitchStore = service.getRemotePreferences(HOOK_SWITCH_STORE);
        } catch (Throwable error) {
            Log.w(TAG, "远端偏好不可用：" + error);
        }
    }

    /**
     * Re-reads the live scope from the bound LSPosed service.  Used by the
     * 重启作用域 page so a scope edit made in LSPosed shows without reopening
     * the module.  Returns the refreshed status line either way.
     */
    static String refreshScope() {
        XposedService current = service;
        if (current == null) {
            status = "LSPosed 服务未连接；请从 LSPosed 管理器内打开本模块后重试";
            return status;
        }
        try {
            List<String> scope = current.getScope();
            selectedScope = scope == null ? Collections.emptyList()
                    : Collections.unmodifiableList(new ArrayList<>(scope));
            status = "LSPosed 已连接；实际勾选 " + selectedScope.size() + " 个作用域";
            Log.i(TAG, status + "：" + String.join(", ", selectedScope));
        } catch (Throwable error) {
            selectedScope = Collections.emptyList();
            status = "读取 LSPosed 作用域失败：" + error.getClass().getSimpleName();
            Log.e(TAG, status, error);
        }
        return status;
    }

    @Override
    public void onServiceDied(XposedService dead) {
        service = null;
        selectedScope = Collections.emptyList();
        status = "LSPosed 服务已断开";
        Log.w(TAG, status);
    }

    /** Remote preferences shared with the hook side, or null before bind. */
    static android.content.SharedPreferences cardAlphaPreferences() {
        return cardAlphaStore;
    }

    /** Per-app hook switches, or null before the LSPosed service binds. */
    static android.content.SharedPreferences hookSwitchPreferences() {
        return hookSwitchStore;
    }

    static String scopeSummary() {
        List<String> scope = selectedScope;
        if (scope.isEmpty()) return status;
        return status + "：\n" + String.join("\n", scope);
    }

    static String scopeStatusLine() {
        return status;
    }

    /** Returns LSPosed's actual selected scope, never the static candidates. */
    static List<String> selectedScopeSnapshot() {
        return new ArrayList<>(selectedScope);
    }
}
