package dev.codex.miuibackgesturehook.hooks.googleapp;

import android.content.SharedPreferences;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import dev.codex.miuibackgesturehook.PredictiveBackPreferences;
import dev.codex.miuibackgesturehook.hooks.miuihome.MiuiHomeHookRuntime;
import io.github.libxposed.api.XposedInterface;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

/** Android 16 Google App compatibility for the Circle to Search translation action. */
public abstract class GoogleAppLiveTranslateRuntime extends MiuiHomeHookRuntime {
    protected static final String GOOGLE_APP =
            "com.google.android.googlequicksearchbox";

    private static final String LIVE_TRANSLATE_SYSTEM_FEATURE =
            "com.google.android.feature.CONTEXTUAL_SEARCH_LIVE_TRANSLATE";
    private static final int LIVE_TRANSLATE_ACTION_ID = 271520;

    private volatile SharedPreferences liveTranslatePreferences;
    private volatile boolean liveTranslatePreferenceFailureLogged;
    private volatile boolean dexKitLibraryLoaded;
    protected final AtomicInteger googleLiveTranslateResolutionInFlight =
            new AtomicInteger();
    protected volatile String googleAppSourceDir;

    protected String resolveGoogleAppSourceDir() {
        String cached = googleAppSourceDir;
        if (cached != null && !cached.isEmpty()) {
            return cached;
        }
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod(
                    "currentApplication");
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                cached = ((Context) application).getApplicationInfo().sourceDir;
                googleAppSourceDir = cached;
            }
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Could not recover Google App source path after hot reload", throwable);
        }
        return cached;
    }

    protected void installGoogleAppLiveTranslateHooks(
            ClassLoader classLoader, String sourceDir, Set<String> existingHookIds) {
        googleAppSourceDir = sourceDir;
        if (!isContextualSearchLiveTranslateEnabled()) {
            moduleLog(Log.INFO, TAG,
                    "Google live-translate compatibility disabled; leaving Google App stock");
            return;
        }

        installLiveTranslateSystemFeatureHook(classLoader, existingHookIds);
        if (existingHookIds.contains("google_live_translate_action_visibility")
                && existingHookIds.contains("google_live_translate_capability")) {
            return;
        }
        if (sourceDir == null || sourceDir.isEmpty()) {
            moduleLog(Log.WARN, TAG,
                    "Google App source path unavailable; live-translate gates remain stock");
            return;
        }

        googleLiveTranslateResolutionInFlight.incrementAndGet();
        try {
            Class<?> actionClass = resolveLiveTranslateActionClass(classLoader, sourceDir);
            if (actionClass == null) {
                moduleLog(Log.WARN, TAG,
                        "Could not uniquely resolve the Android 16 live-translate action");
                return;
            }
            if (!existingHookIds.contains("google_live_translate_action_visibility")) {
                installActionVisibilityHook(actionClass);
            }
            if (!existingHookIds.contains("google_live_translate_capability")) {
                installCapabilityHook(actionClass);
            }
        } catch (Throwable throwable) {
            moduleLog(Log.ERROR, TAG,
                    "Failed to install Google live-translate compatibility", throwable);
        } finally {
            googleLiveTranslateResolutionInFlight.decrementAndGet();
        }
    }

    private void installLiveTranslateSystemFeatureHook(
            ClassLoader classLoader, Set<String> existingHookIds) {
        if (existingHookIds.contains("google_live_translate_system_feature")) {
            return;
        }
        try {
            Class<?> packageManagerClass = Class.forName(
                    "android.app.ApplicationPackageManager", false, classLoader);
            Method method = packageManagerClass.getDeclaredMethod(
                    "hasSystemFeature", String.class);
            recordHookHandle(hook(method)
                    .setId("google_live_translate_system_feature")
                    .intercept(this::overrideLiveTranslateSystemFeature));
        } catch (Throwable throwable) {
            moduleLog(Log.WARN, TAG,
                    "Live-translate system-feature gate unavailable", throwable);
        }
    }

    private Class<?> resolveLiveTranslateActionClass(
            ClassLoader classLoader, String sourceDir) throws Throwable {
        ensureDexKitLibraryLoaded();
        try (DexKitBridge bridge = DexKitBridge.create(sourceDir)) {
            FindMethod query = FindMethod.create().matcher(
                    MethodMatcher.create()
                            .paramCount(0)
                            .returnType("int")
                            .usingNumbers(Integer.valueOf(LIVE_TRANSLATE_ACTION_ID)));
            MethodDataList matches = bridge.findMethod(query);
            Class<?> resolved = null;
            for (MethodData match : matches) {
                if (match.getParamCount() != 0
                        || !"int".equals(match.getReturnTypeName())) {
                    continue;
                }
                Class<?> candidate = match.getClassInstance(classLoader);
                if (findExactBooleanMethod(candidate, "i") == null
                        || findCapabilityMethod(candidate) == null) {
                    continue;
                }
                if (resolved != null && resolved != candidate) {
                    moduleLog(Log.WARN, TAG,
                            "Ambiguous live-translate action classes: "
                                    + resolved.getName() + " and " + candidate.getName());
                    return null;
                }
                resolved = candidate;
            }
            return resolved;
        }
    }

    private void ensureDexKitLibraryLoaded() {
        if (dexKitLibraryLoaded) {
            return;
        }
        synchronized (this) {
            if (!dexKitLibraryLoaded) {
                System.loadLibrary("dexkit");
                dexKitLibraryLoaded = true;
            }
        }
    }

    private void installActionVisibilityHook(Class<?> actionClass) throws Throwable {
        Method visibility = findExactBooleanMethod(actionClass, "i");
        if (visibility == null) {
            throw new NoSuchMethodException(actionClass.getName() + ".i():boolean");
        }
        boolean deoptimized = deoptimize(visibility);
        recordHookHandle(hook(visibility)
                .setId("google_live_translate_action_visibility")
                .intercept(this::overrideLiveTranslateBooleanGate));
        moduleLog(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared live-translate action visibility"
                        + ", owner=" + actionClass.getName()
                        + ", deoptimized=" + deoptimized);
    }

    private void installCapabilityHook(Class<?> actionClass) throws Throwable {
        Method capability = findCapabilityMethod(actionClass);
        if (capability == null) {
            throw new NoSuchMethodException(
                    actionClass.getName() + " live-translate capability");
        }
        boolean deoptimized = deoptimize(capability);
        recordHookHandle(hook(capability)
                .setId("google_live_translate_capability")
                .intercept(this::overrideLiveTranslateBooleanGate));
        moduleLog(deoptimized ? Log.INFO : Log.WARN, TAG,
                "Prepared live-translate capability gate"
                        + ", executable=" + capability
                        + ", deoptimized=" + deoptimized);
    }

    private static Method findCapabilityMethod(Class<?> actionClass) {
        Constructor<?> matchingConstructor = null;
        for (Constructor<?> constructor : actionClass.getDeclaredConstructors()) {
            if (constructor.getParameterCount() == 3) {
                if (matchingConstructor != null) {
                    return null;
                }
                matchingConstructor = constructor;
            }
        }
        if (matchingConstructor == null) {
            return null;
        }
        Class<?>[] parameters = matchingConstructor.getParameterTypes();
        return parameters.length == 3
                ? findExactBooleanMethod(parameters[1], "a") : null;
    }

    private static Method findExactBooleanMethod(Class<?> owner, String name) {
        try {
            Method method = owner.getDeclaredMethod(name);
            if (method.getReturnType() != Boolean.TYPE
                    || method.getParameterCount() != 0) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return null;
        }
    }

    protected Object overrideLiveTranslateSystemFeature(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        List<Object> args = chain.getArgs();
        if (args.size() == 1
                && LIVE_TRANSLATE_SYSTEM_FEATURE.equals(args.get(0))
                && isContextualSearchLiveTranslateEnabled()) {
            return Boolean.TRUE;
        }
        return result;
    }

    protected Object overrideLiveTranslateBooleanGate(
            XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        return isContextualSearchLiveTranslateEnabled() ? Boolean.TRUE : result;
    }

    protected boolean isContextualSearchLiveTranslateEnabled() {
        try {
            SharedPreferences preferences = liveTranslatePreferences;
            if (preferences == null) {
                synchronized (this) {
                    preferences = liveTranslatePreferences;
                    if (preferences == null) {
                        preferences = getRemotePreferences(
                                PredictiveBackPreferences.GROUP);
                        liveTranslatePreferences = preferences;
                    }
                }
            }
            boolean enabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LIVE_TRANSLATE,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LIVE_TRANSLATE);
            boolean contextualSearchEnabled = preferences.getBoolean(
                    PredictiveBackPreferences.KEY_CONTEXTUAL_SEARCH_LONG_PRESS,
                    PredictiveBackPreferences.DEFAULT_CONTEXTUAL_SEARCH_LONG_PRESS);
            liveTranslatePreferenceFailureLogged = false;
            return enabled && contextualSearchEnabled;
        } catch (Throwable throwable) {
            if (!liveTranslatePreferenceFailureLogged) {
                liveTranslatePreferenceFailureLogged = true;
                moduleLog(Log.ERROR, TAG,
                        "Live-translate preference unavailable; preserving Google App behavior",
                        throwable);
            }
            return false;
        }
    }
}
