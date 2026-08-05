package dev.codex.miuibackgesturehook;

public final class PredictiveBackPreferences {
    public static final String GROUP = "predictive_back_opt_in";
    public static final String KEY_PACKAGES = "packages";
    public static final String KEY_HYPEROS_INDICATOR = "hyperos_indicator_style";
    public static final boolean DEFAULT_HYPEROS_INDICATOR = false;
    public static final String KEY_HYPEROS_HAPTICS = "hyperos_indicator_haptics";
    public static final boolean DEFAULT_HYPEROS_HAPTICS = false;
    /** Only used to migrate the removed AOSP-specific switch once. */
    public static final String LEGACY_KEY_AOSP_HYPEROS_HAPTICS =
            "aosp_gesture_hyperos_haptics";
    public static final String KEY_HYPEROS_HAPTICS_ENHANCED =
            "hyperos_indicator_haptics_enhanced";
    public static final boolean DEFAULT_HYPEROS_HAPTICS_ENHANCED = false;
    public static final String KEY_HYPEROS_SLIDE_ANIMATION =
            "hyperos_slide_back_animation";
    public static final boolean DEFAULT_HYPEROS_SLIDE_ANIMATION = false;
    public static final String KEY_MODULE_LOGGING = "module_logging";
    public static final boolean DEFAULT_MODULE_LOGGING = true;

    private PredictiveBackPreferences() {
    }
}
