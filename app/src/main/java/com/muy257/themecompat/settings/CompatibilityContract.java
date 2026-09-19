package com.muy257.themecompat.settings;

/** Shared, versioned contract between the static MTZ patcher and runtime Hook. */
final class CompatibilityContract {
    static final String PATCH_MARKER = "themecompat-settings:v1";
    static final String PATCH_MARKER_LINE = "[" + PATCH_MARKER + "]";
    /**
     * Record the patcher writes into every module archive it rewrites. The
     * theme engine copies each module archive verbatim into
     * /data/system/theme/&lt;package&gt; on every apply, so a marker found
     * there is proof that the currently applied theme came from this
     * patcher. The description.xml marker must not be used for this: its
     * Settings.Secure copy ("maml") is left stale by HyperOS when a later
     * theme has no maml component.
     */
    static final String PAYLOAD_MARKER_ENTRY = "themecompat.marker";
    static final String PAYLOAD_MARKER_CONTENT = PATCH_MARKER + "\n";
    static final String HOOK_MASTER_KEY = "hook_master";

    private CompatibilityContract() { }
}
