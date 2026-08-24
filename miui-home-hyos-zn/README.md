# MiuiHome LSPosed native hook

This directory contains the Android 17 MiuiHome native payload embedded in the
LSPosed module APK. The directory name is retained for source-path continuity;
this branch has no standalone native-module package, installer, controller,
activation path, or alternate exported module entry.

The only produced native library is:

```text
lib/arm64-v8a/libmiui_home_hyos_lsp.so
```

The APK declares it through `META-INF/xposed/native_init.list`, and the internal
LSPosed build owns HYOS injection. The library exports only `native_init`.
Launcher business hooks, profile validation, the runtime/Dart resolvers,
authenticated state broadcasts, and the MiCTS-style `madvise` guard are built
into this APK payload.

## Build

Use the application build; there is no separate native-module package task:

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:assembleRelease
```

Debug is the normal iterative target. Release is reserved for a final delivery
candidate.

## Rebootless iterative deployment

Use the guarded deployment script:

```powershell
.\miui-home-hyos-zn\safe-lsposed-native-deploy.ps1 `
    -Action Deploy -Serial <adb-serial>
```

It builds Debug by default, validates the complete APK, installs it through
PackageManager with rollback enabled, proves the existing SystemUI API-102 hot
reload, and then replaces only the exact root `hyos_spawner`. A clean MiuiHome
child must map the current APK inode. The script then runs the module's
authenticated module -> SystemUI -> native status challenge; the dynamic
profile, business/bridge hooks, drawer/overview hooks, and SystemUI monitor must
all report ready, with no new tombstone.

The script never extracts or pushes the native `.so`, never installs anything
under `/system/bin`, never writes Android system properties, and never performs
a whole-device reboot. A retired standalone owner mapping is treated as a hard
conflict; the script does not enable, disable, reload, or otherwise control it.

Useful variants:

```powershell
# Deploy an existing Debug APK without rebuilding.
.\miui-home-hyos-zn\safe-lsposed-native-deploy.ps1 `
    -Action Deploy -Serial <adb-serial> -SkipBuild

# Deploy one explicitly selected complete APK.
.\miui-home-hyos-zn\safe-lsposed-native-deploy.ps1 `
    -Action Deploy -Serial <adb-serial> -Apk <apk-path>

# Read-only process/package status.
.\miui-home-hyos-zn\safe-lsposed-native-deploy.ps1 `
    -Action Status -Serial <adb-serial>

# Verify the current APK mapping and authenticated native readiness.
.\miui-home-hyos-zn\safe-lsposed-native-deploy.ps1 `
    -Action Verify -Serial <adb-serial>

# Capture process, LSPosed-log, native-log, and tombstone evidence.
.\miui-home-hyos-zn\safe-lsposed-native-deploy.ps1 `
    -Action Capture -Serial <adb-serial>
```

## Resolver verification

The launcher profile generator and offline verifiers remain available because
they validate the LSPosed payload itself:

```powershell
python .\miui-home-hyos-zn\generate-launcher-profiles.py `
    --manifest .\miui-home-hyos-zn\launcher-profiles.json `
    --output .\miui-home-hyos-zn\generated\launcher_profiles.generated.h

python .\miui-home-hyos-zn\verify-launcher-profiles.py `
    --manifest .\miui-home-hyos-zn\launcher-profiles.json

python .\miui-home-hyos-zn\verify-runtime-profile.py `
    --manifest .\miui-home-hyos-zn\launcher-profiles.json
```

Local Xiaomi binaries and reverse-engineering workspaces remain ignored and
must never be committed.
