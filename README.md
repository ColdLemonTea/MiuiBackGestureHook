# MIUI Back Gesture Hook

An LSPosed + Zygisk Next companion module for Xiaomi MIUI/HyperOS back gestures.

The LSPosed component integrates with SystemUI and the system back pipeline. The
arm64-v8a Zygisk Next component supplies the native MiuiHome hook required by
Android 17 / HyperOS 4. Together they restore predictive-back behavior while
keeping Xiaomi's native launcher transitions intact.

Optional integrations (off by default):

- Circle to Search from the gesture handle;
- Google App full-screen Live Translate, with the platform screen-capture consent
  flow unchanged.

Android 16 uses the SystemUI gesture path. Android 17 and newer keep launcher-side
long press, cancellation, animation, and contextual-search ownership in native
MiuiHome through Zygisk Next.

## Compatibility

| Platform | Required components | Launcher support |
| --- | --- | --- |
| Android 16 | LSPosed | SystemUI gesture path |
| Android 17 / HyperOS 4+ | LSPosed + Zygisk Next | `4371` static profile; `53xx` and newer builds use the runtime resolver when their native topology validates |

The native companion is arm64-v8a only. Unsupported or ambiguous native layouts
fail closed without installing business hooks.

## Build

Build the LSPosed release APK:

```powershell
.\gradlew.bat :app:assembleRelease
```

Build the arm64-v8a Zygisk Next package:

```powershell
.\gradlew.bat buildZnPackage -PznConfiguration=Release
```

The ZN task uses the cross-platform Python builder in
`miui-home-hyos-zn/`. NDK, CMake, and Python can be overridden with
`-PznNdkPath`, `-PznCmakePath`, and `-PznPython`.

Outputs:

```text
app/build/outputs/apk/release/app-release.apk
out/packages/miui-home-hyos-zn-*.zip
```

## Scope and runtime

The static LSPosed scope is:

```text
com.android.systemui
com.miui.home
com.google.android.googlequicksearchbox
system
```

The Google App scope is used only when Live Translate is enabled. On Android 17
and newer, MiuiHome remains listed for compatibility, but launcher work is done
by the native Zygisk Next companion rather than a duplicate Java input hook.

API 102 hot reload is enabled with `autoHotReload=true`. The settings screen can
refresh the authenticated SystemUI/ZN runtime status and resolved launcher profile.

## References

Checked-in AOSP references are under `refs/android16/aosp_back_16/`. Xiaomi
artifacts and device evidence remain local-only under ignored `refs/android17`
paths; see [refs/README.md](refs/README.md).

Native ZN sources and deployment tools are under
`miui-home-hyos-zn/`.

## License

Apache License 2.0. See [LICENSE](LICENSE).

Bundled third-party components and their separate license terms are listed in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
