package android.window;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;

import java.util.function.Supplier;

/** Compile-only declaration for the hidden framework dispatcher identity. */
public final class WindowOnBackInvokedDispatcher {
    private WindowOnBackInvokedDispatcher() {
        throw new RuntimeException("Stub");
    }

    public static boolean isOnBackInvokedCallbackEnabled(
            ActivityInfo activityInfo, ApplicationInfo applicationInfo,
            Supplier<Context> contextSupplier) {
        throw new RuntimeException("Stub");
    }
}
