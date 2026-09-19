package com.muy257.themecompat.settings;

/** Shared, versioned contract between the static MTZ patcher and runtime Hook. */
final class CompatibilityContract {
    static final String PATCH_MARKER = "themecompat-settings:v1";
    static final String PATCH_MARKER_LINE = "[" + PATCH_MARKER + "]";
    static final String HOOK_MASTER_KEY = "hook_master";

    private CompatibilityContract() { }
}
