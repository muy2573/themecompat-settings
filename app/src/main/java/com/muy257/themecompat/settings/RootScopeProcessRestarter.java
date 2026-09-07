package com.muy257.themecompat.settings;

import android.text.TextUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Root-only, package-aware process reload helper for the actual LSPosed scope. */
final class RootScopeProcessRestarter {
    private static final long ROOT_CHECK_TIMEOUT_MS = 4_000L;
    private static final long FORCE_STOP_TIMEOUT_MS = 8_000L;
    // The companion must remain alive long enough to report the result.  Every
    // other valid package returned by LSPosed is deliberately honored,
    // including SystemUI, its plugin and the launcher when the user selected
    // them in the scope.
    private static final Set<String> PROTECTED_SCOPES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("com.muy257.themecompat")));
    // am force-stop reports success but leaves these processes (and their
    // hooks) untouched; they need a direct SIGKILL on the live PID.  SystemUI
    // ignores force-stop on this ROM entirely — the visible reload only ever
    // came from killall/kill -9.
    private static final Set<String> SIGKILL_PROCESSES = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.android.phone", "com.android.systemui", "miui.systemui.plugin")));

    private RootScopeProcessRestarter() { }

    static RootStatus checkRoot() {
        CommandResult result = runSu("id", ROOT_CHECK_TIMEOUT_MS);
        String output = result.output.trim();
        boolean root = result.completed() && result.exitCode == 0 && output.contains("uid=0");
        if (root) return new RootStatus(true, "Root 可用（" + oneLine(output) + "）");
        if (result.timedOut) return new RootStatus(false, "Root 检测超时（su 未在 4 秒内响应）");
        if (!result.started) return new RootStatus(false, "未找到或无法启动 su");
        String detail = TextUtils.isEmpty(output) ? "exit=" + result.exitCode : oneLine(output);
        return new RootStatus(false, "Root 不可用（" + detail + "）");
    }

    static RestartResult stopScopedPackages(List<String> scope) {
        List<String> stopped = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (String packageName : scope) {
            if (TextUtils.isEmpty(packageName)) continue;
            if (!packageName.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
                skipped.add(packageName + "（不是应用包名）");
                continue;
            }
            if (PROTECTED_SCOPES.contains(packageName)) {
                skipped.add(packageName + "（核心进程保护）");
                continue;
            }
            CommandResult result = runSu("am force-stop --user 0 " + packageName,
                    FORCE_STOP_TIMEOUT_MS);
            if (result.completed() && result.exitCode == 0) {
                if (SIGKILL_PROCESSES.contains(packageName)) {
                    // The persistent radio process and SystemUI survive
                    // am force-stop; only a direct SIGKILL on the live PID
                    // actually reloads their hooks.
                    runSu("kill -9 $(pidof " + packageName + ") 2>/dev/null", 6_000L);
                    stopped.add(packageName + "（已 kill -9）");
                } else {
                    stopped.add(packageName);
                }
            } else {
                String reason = result.timedOut ? "超时" : (TextUtils.isEmpty(result.output)
                        ? "exit=" + result.exitCode : oneLine(result.output));
                failed.add(packageName + "（" + reason + "）");
            }
        }
        return new RestartResult(stopped, skipped, failed);
    }

    private static CommandResult runSu(String command, long timeoutMs) {
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        } catch (Throwable error) {
            return new CommandResult(false, false, -1, error.getClass().getSimpleName());
        }
        boolean finished;
        try {
            finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new CommandResult(true, true, -1, "interrupted");
        }
        if (!finished) {
            process.destroyForcibly();
            return new CommandResult(true, true, -1, "");
        }
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (output.length() < 2_000) output.append(line).append('\n');
            }
        } catch (Throwable ignored) {
            // Exit code remains authoritative for am force-stop.
        }
        return new CommandResult(true, false, process.exitValue(), output.toString());
    }

    private static String oneLine(String value) {
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static final class RootStatus {
        final boolean available;
        final String message;
        RootStatus(boolean available, String message) { this.available = available; this.message = message; }
    }

    static final class RestartResult {
        final List<String> stopped;
        final List<String> skipped;
        final List<String> failed;
        RestartResult(List<String> stopped, List<String> skipped, List<String> failed) {
            this.stopped = stopped; this.skipped = skipped; this.failed = failed;
        }
    }

    private static final class CommandResult {
        final boolean started;
        final boolean timedOut;
        final int exitCode;
        final String output;
        CommandResult(boolean started, boolean timedOut, int exitCode, String output) {
            this.started = started; this.timedOut = timedOut;
            this.exitCode = exitCode; this.output = output;
        }
        boolean completed() { return started && !timedOut; }
    }
}
