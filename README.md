# MIUI SystemUI Back Gesture Hook

LSPosed module using modern Xposed API 102 for SystemUI-side MIUI back gesture research.

The compatibility options restore Circle to Search from the visible gesture handle and
can expose Google App's full-screen Live Translate action without bypassing the system
screen-capture consent flow. Both options default to off. Android 16 uses the SystemUI
gesture-handle path; Android 17/HyperOS 4 delegates the long press, animation, cancellation,
and contextual-search launch to MiuiHome's native implementation, enabled through the
companion Zygisk Next module.

## Build

The arm64-v8a Zygisk Next native package can be built with:

    .\gradlew.bat buildZnPackage -PznConfiguration=Release

The task uses the cross-platform Python builder under
experiments/miui-home-hyos-zn/ and accepts -PznNdkPath, -PznCmakePath, and
-PznPython overrides.

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## AOSP References

Checked-in AOSP reference snippets live under:

```text
refs/android16/aosp_back_16/
```

The directory is split by component:

```text
refs/android16/aosp_back_16/shell/
refs/android16/aosp_back_16/systemui/
```

Xiaomi APKs, JARs, native libraries, decompilation, and device evidence remain local-only
under ignored `refs/android17` paths and must not be committed. See `refs/README.md`.

The native `hyos_spawner` research module source and safe deployment tooling live under:

```text
experiments/miui-home-hyos-zn/
```

The module settings screen includes a KernelSU-style runtime status card. It
performs an authenticated nonce challenge through SystemUI and the Zygisk Next
launcher bridge, so it reports the actual SystemUI arbiter, resolved runtime
profile, and native hook state without reading module files or trusting a
single process-local flag. A tap refreshes the current status.

## Scope

The static scope is declared in:

```text
app/src/main/resources/META-INF/xposed/scope.list
```

Current scopes:

```text
com.android.systemui
com.miui.home
com.google.android.googlequicksearchbox
system
```

The Google App scope is inert unless the Live Translate option is enabled.

`com.miui.home` remains in the static list for Android 16. On Android 17 and newer,
the module exits before installing any MiuiHome-process LSPosed hook; hot reload also removes
old MiuiHome hook handles instead of replacing them. Launcher-side Android 17 work is isolated
to the native ZN experiment.

## Compatibility

The Android 17 native MiuiHome integration targets Xiaomi System Launcher build `4371` only.

## Hot Reload

API 102 hot reload is enabled through:

```text
autoHotReload=true
```

The module implements `onHotReloading(...)` and `onHotReloaded(...)`.

## Entry

The module entry is:

```text
dev.codex.miuibackgesturehook.MiuiBackGestureHook
```

Registered through:

```text
app/src/main/resources/META-INF/xposed/java_init.list
```

## License

Apache License 2.0. See [LICENSE](LICENSE).

Bundled third-party components and their separate license terms are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
