# MiuiHome Android 17 native handoff

This directory builds the Zygisk Next half of the Android 17 back-gesture
handoff. Xiaomi's native MiuiHome runtime remains the physical side-window and
DOWN owner; this module identifies an accepted launcher stream and publishes
its immutable input identity to the companion SystemUI hook. SystemUI then owns
the indicator, pilfering, Shell navigation, and predictive-back animation.

Android 17's Flutter/Rust launcher also publishes its existing ALL_APPS state
through the same authenticated launcher-to-SystemUI channel. The native hook
resolves the mapped Flutter AOT callback family at runtime. It requires one
unique accepted drawer transition, its matching completion callback, and the
paired Overview enter/exit callbacks with shared immutable relationships.
A raw AArch64 shim preserves Flutter's x15 Dart stack and fixed runtime
registers before entering C++. Overview uses only the same authenticated,
generation-bearing native MiuiHome-to-SystemUI state channel; no second
SystemUI callback owns that state, and neither path installs a MiuiHome-process
LSPosed hook.
If Flutter replaces only one page containing the paired Overview callbacks,
the native bridge unregisters both stale inline-hook records, requires both
exact callback fingerprints after unhook, and reinstalls the pair as one
fail-closed repair before publishing any state.

It also enables HyperOS 4's existing native Circle to Search path when the companion app
preference is on. MiuiHome remains the sole owner of the bottom long press through its
`LongPressDetector`/`LongPressManager`, including native animation and cancellation. The
module does not install another input monitor. It changes a successful false result only
for MiuiHome's exact `android.software.contextualsearch` or
`com.google.android.feature.CONTEXTUAL_SEARCH` feature query. On exact 5334/5436 profiles, or
when the bounded runtime resolver proves the same unique route, it also preserves the normal
native long-press closure's completion marker and routes that terminal callback directly to
Xiaomi's existing `circle_to_search_helper::invoke(1)`,
bypassing only Flutter's rejection of the absent `NavLongPress` setting. It does not hook the
consuming `FnOnce` shim or issue its own voice-interaction Binder transaction. The preference arrives on
the existing identity-sharing SystemUI arbiter broadcast after sender-package and UID
verification; missing or invalid state defaults off.
SystemUI listens for the remote preference change and republishes the authenticated
state with the current arbiter generation, so an already loaded HyperOS 4 native module
applies the switch on the next long press without restarting the phone. Xiaomi's helper
rechecks feature support; no native gesture owner is rebuilt.

This is no longer an observation-only probe. It contains device-proven static
profiles for MiuiHome `4371`, `5334`, and `5402`. Builds `5436` and `5450`
remain offline reference evidence for the bounded runtime resolver and are
deliberately absent from the active native registry.

## Requirements and scope

- Android 17 HyperOS with the exact supported `hyos_spawner` build below.
- arm64 device with Zygisk Next `1.4.5` or newer, exposing main API `4` and
  HYOS runtime API `1`.
- MiuiHome `4371`, `5334`, or `5402`, selected automatically from immutable
  native identity, or `54xx` and later through the bounded runtime resolver.
  The host script requires the caller to confirm the exact active four-digit
  dynamic build before mutation.
- The companion LSPosed module enabled for its existing Android 17 SystemUI
  and `system` scopes. This ZN module alone does not create an AOSP gesture.

Zygisk Next injects the library only into:

```text
/system_ext/bin/hyos_spawner
```

The executable and controller remain inside the module directory. Nothing is
mounted into `/system/bin`, and neither the runtime nor the deployment tools
read or write Android properties.

The Zygisk Next module enabled state is the only runtime gate. A normal module
installation does not restart a process; after the user reboots, an enabled ZN
module immediately attempts the fail-closed profile match.

## Supported identities

Shared native runtime:

```text
hyos_spawner path:       /system_ext/bin/hyos_spawner
hyos_spawner Build ID:   87f2632e7d68fda0226366fda5346c2d
launcher process:        /proc/self/cmdline == com.miui.home
entry symbol:            app_entry_point
```

The broadcast-private dylib has different Build IDs across ROMs even when its
hooked symbol and GOT layout are identical. The arbiter bridge therefore gates
the mutation on the exact loaded image path, resolved symbol RVA, GOT RVA and
current GOT value instead of a per-ROM Build ID allowlist. The `5334` launcher
profile has been validated on both the author's device and a Redmi K90
(`annibale`, `OS4.0.0.18.XPKCNXM`, `MiuiSystemUI 17.03.260226.r`) with the full
`verify-launcher-profiles.py` PASS.

Launcher profiles:

| Profile | Package identity | Native identity | Hook topology |
| --- | --- | --- | --- |
| `4371` | `801024371` / `RELEASE-8.01.02.4371-260727-08131546-R` | SHA-256 `a84365f864f88f85165b086bc03ba563efd09386c72f0c21926788fd90a028f9`; entry `0x885d00`; side boundary `0xc6e954`; edge `+0xec`; ALL_APPS `0xa756e8` | `legacy_three_stage` |
| `5334` | `801025334` / `RELEASE-8.01.02.5334-260807-08151151-R` | SHA-256 `a67fe9e3ef3880f920cce92eb1c006c7fe12a83c0632f2397b118e6915043091`; entry `0xc8ffd8`; side boundary `0x80c3bc`; edge `+0xf4`; ALL_APPS `0x9eb9d4`; long-press `Fn` `0x71af90`; native invoke `0xadc22c` | `side_boundary_only` |
| `5402` | `801025402` / `RELEASE-8.01.02.5402-260807-08181825-R` | SHA-256 `053b3b6ad84815fb319b2f87e766f67cbf1a22fcfe7f7ed2cd03502e569e0f98`; entry `0xc94b14`; side boundary `0x810010`; edge `+0xf4` | `side_boundary_only` |
| `5450` (runtime reference) | `801025450` / `RELEASE-8.01.02.5450-260807-08211429-R` | launcher SHA-256 `c0e6123e303923441e7b1f93ceed70b780e7c293c70d600807a6f7ad0403b9be`; the recorded RVAs are offline assertions only; native and Dart addresses are resolved from their mapped ELFs | `runtime-side-v1` (not active) |
| `5436` (offline reference) | `801025436` / `RELEASE-8.01.02.5436-260807-08202148-R` | SHA-256 `638dd126d9e6acf6185bcda1e7d798e86f9e172927689348f2b6a99de57004a4`; entry `0xc940c8`; side boundary `0x810374`; edge `+0xf4`; long-press `Fn` `0x71d75c`; native invoke `0xadff10` | `side_boundary_only` (not active) |

Package version is enforced by the host deployment script. At runtime the
module first validates the exact `app_entry_point` RVA and immutable code
fingerprints of the active static profiles. Hook sites validate their own
prologues again.

If no static profile matches, the v1 runtime resolver is limited to the Android
17 `side_boundary_only` family represented by `5334`, `5402`, `5436`, and `5450`. The side
prologue is only a candidate seed. A candidate is accepted only when its edge
field load and the ordered `getActionMasked`, `getActionIndex`, `getRawX`, and
`getRawY` calls resolve through the corresponding ELF PLT relocations. The
resolver independently requires the `Runtime_inc_strong` / application-thread
binder / `Runtime_dec_strong` graph, its adjacent RW non-executable pointer and
state, and two identical RString-vtable constructions anchored by
`Bundle_default`, `malloc`, and `memcpy`. Every address must lie in a compatible
`PT_LOAD` segment and every result must be unique. Only then is one immutable
in-process profile snapshot published. Zero matches, multiple matches, a
broken relationship, or a partial result installs no business hook.

After `libapp.so` is loaded, the launcher process resolves the Dart
feature family directly from its mapped ELF. The drawer check must be unique,
must expose two adjacent ALL_APPS/HOME isolate-group slots, and both state
loads must share one slow-path call. The completion callback must call that
same target. Overview enter and exit must each be unique and must share their
state slot, pool object, preparation call, and publication call. The exported
snapshot-instructions and GNU build-ID symbols must belong to compatible
`PT_LOAD` segments. Any missing or ambiguous relation installs no Dart hook.
The unique `_onDrawerVisibilityChanged` callback owns ALL_APPS publication; the
drawer-check function and its adjacent ALL_APPS/HOME slots remain independent
resolution anchors. Both the callback and Overview pair are hooked from the
mapped runtime snapshot.
Profiles with an existing verified native drawer or Overview route never enter
this resolver, so the working Rust path keeps ownership.

Circle to Search is an independent optional extension of that snapshot. It requires one support
function with the two ordered `PackageManager_has_system_feature` calls, one Xiaomi invoke
function that directly calls that support function, and one normal long-press `Fn` with the exact
captured `completion_state + 0x10` release-store and fallback shape. Only a unique triple publishes
the two hook addresses. Missing imports or any ambiguity leaves those addresses empty while
preserving the already-proven dynamic side profile.

This is not a generic AOB scanner, and it never copies offsets from the nearest
version. `4371` remains strict-static because its legacy three-stage topology
is outside the dynamic family. Library SHA-256 remains an offline
profile-verification input. The native runtime resolver uses only the already
loaded ELF image and never opens the APK or parses JSON/XML. Dart resolution
also uses only the already-loaded `libapp.so`; SystemUI sends no address,
fingerprint, path, or profile data.

`4371` keeps two transparent legacy diagnostic hooks around the side boundary.
`5334`, `5402`, and `5436` use only the reviewed side boundary because Xiaomi inlined
the older processor stages. All profiles hand off only a launcher-accepted BACK
stream.

## Loader path

The confirmed native ownership chain is:

```text
hyos_spawner
  register HYOS runtime callback
    -> post-fork onAppSpecialized(com.miui.home, com.miui.home)
  dlopen("libhyper_os_shell.so")
    -> libhyper_os_shell.so
         android_dlopen_ext("libhyper_os_app_public.so")
           -> libhyper_os_app_public.so
                dlsym(handle, "app_entry_point")
                  -> APK libapp_launcher.so
```

Runtime registration is fail-closed. The loader observation hooks are installed
only after Zygisk Next reports the HYOS runtime, exposes API version 1 or newer,
and accepts the module callback. The specialization callback retains no runtime
pointer or string: it records only atomic process-local identity and lifecycle
ordering diagnostics. Business hooks continue to install from the existing
launcher loader path; specialization does not perform ELF scanning or hook
installation.

The module hooks only the relevant owner PLT slots and then installs
profile-bound hooks in the resolved launcher image. It does not hook the system
linker globally and does not depend on the randomized `/data/app` directory.

The authenticated launcher-to-SystemUI state channel uses an explicit
identity-sharing broadcast. The private Rust broadcast tail hook preserves its
raw AArch64 result ABI (`x8`, including the `sp+0x58` option slot); do not replace
it with an ordinary C++ aggregate-return hook.

## First boot and first gesture

After a standard ZIP install and reboot, the module starts enabled if the root
manager and Zygisk Next report it enabled. There is no additional marker or
feature switch.

SystemUI readiness can arrive after the launcher bridge is installed. A formal
test normally starts directly with exactly one side gesture and one immediate
capture. Do not add a routine warmup. Only when pre-gesture evidence proves
that a build cannot become ready until MiuiHome reaches its processor, label
one separate gesture as a **readiness warmup**, capture it independently, and
then run the one-gesture formal test. If the receiver, generation, identity, or
native readiness check is missing, the bridge fails closed instead of sending
an unauthenticated handoff.

## Profiles

[`launcher-profiles.json`](launcher-profiles.json) is the canonical reviewed
source. [`generate-launcher-profiles.py`](generate-launcher-profiles.py)
validates it and emits the C++ core registry into the build directory. Dart
RVAs and fingerprints in `dart_runtime_reference_profiles` are offline
regression evidence only; active launcher profiles contain no Dart addresses,
and the generated native registry emits zero/null Dart fields. The
running launcher fills a separate immutable profile only after the mapped-AOT
resolver proves one complete unique callback family.

When locally retained launcher ELFs are available, verify profiles without
adding those proprietary binaries or decompiler projects to Git:

```powershell
python .\miui-home-hyos-zn\verify-launcher-profiles.py `
  --library 4371=<4371-libapp_launcher.so> `
  --library 5334=<5334-libapp_launcher.so> `
  --library 5402=<5402-libapp_launcher.so>
```

The verifier checks the recorded digest, translates every RVA through the ELF
LOAD table, and compares all identity and hook bytes inside executable
segments. Add `--dart-library 5450=<5450-libapp.so>` to reproduce the reviewed
Dart offsets and structural-resolver result from a locally retained AOT image.

The structural resolver has a separate dual-sample regression. It verifies
that the production constraints reproduce every recorded `5334`/`5402` ABI
offset and that corrupting the unique side candidate fails closed:

```powershell
python .\miui-home-hyos-zn\verify-runtime-profile.py `
  --library 5334=<5334-libapp_launcher.so> `
  --library 5402=<5402-libapp_launcher.so>
```

The dynamic snapshot is the fail-closed compatibility and deployment boundary
for `54xx` and later. Recorded 54xx offsets remain verifier inputs and are not
emitted into the active native registry.

## Build

From the repository root:

```powershell
.\gradlew.bat buildZnPackage -PznConfiguration=Debug
```

Use `Release` only for a distributable package:

```powershell
.\gradlew.bat buildZnPackage -PznConfiguration=Release
```

The Gradle task invokes the cross-platform build_zn_package.py builder. It
uses ANDROID_NDK_HOME and cmake from PATH by default; override them with
-PznNdkPath=... and -PznCmakePath=... when necessary. build.ps1 remains as a
Windows compatibility entry point that delegates to the same Python builder;
it does not maintain a second packaging or diagnostics implementation.

Output is written under:

```text
out/miui-home-hyos-zn/<Configuration>/
out/packages/miui-home-hyos-zn-<timestamp>.zip
```

The build invokes the Python profile generator, verifies ELF64/AArch64 plus
BTI/PAC, enforces the single `zn_module` export, checks both assembly tail
shims, generates `diagnostics.map`, and then packages the module. Release
builds remove compiler debug sections only after resolving that map, then
revalidate the ELF contract and every diagnostic address before packaging;
Debug and RelWithDebInfo retain their debug information. Its version
name comes from `app/build.gradle.kts`; its version
code is the current Git commit count, matching the main app BuildConfig source.

The ZIP intentionally contains no Xiaomi library, APK, Ghidra project, JADX
output, or other decompiled/proprietary artifact.

## Installation and distribution

Install the Release ZIP from the KernelSU or Magisk module UI/CLI, with Zygisk
Next already available, then reboot. The installer only places files below the
module directory and deliberately does not restart `hyos_spawner` or MiuiHome.

A recipient with the exact shared runtime and either supported MiuiHome version
gets the matching profile automatically. A recipient on any other launcher or
native runtime gets a fail-closed no-hook result; this is not a promise of broad
HyperOS compatibility.

The companion LSPosed module and its Android 17 configuration must also be
installed. If it is absent or not ready, the ZN half cannot transfer ownership
to SystemUI.

## Controlled device deployment

For development, do not overwrite a mapped ELF and do not invoke a package
installer for each live iteration. Use
[safe-device-test.ps1](safe-device-test.ps1). It verifies the exact installed
MiuiHome package and Zygisk Next versionCode `845` or newer, stages the payload
under a content-derived name, disables the ZN module before replacing files,
replaces only the exact root
`hyos_spawner`, verifies parentage and mappings, checks for a new tombstone, and
automatically rolls back on failure.

Status and evidence capture are read-only and require no profile confirmation:

```powershell
.\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Status -Serial <adb-serial>

.\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Capture -Serial <adb-serial>
```

Deploy one exact profile:

```powershell
.\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Deploy -Serial <adb-serial> `
  -PackageZip <debug-zip> -Confirm4371

.\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Deploy -Serial <adb-serial> `
  -PackageZip <debug-zip> -Confirm5334

.\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Deploy -Serial <adb-serial> `
  -PackageZip <debug-zip> -Confirm5402

.\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Deploy -Serial <adb-serial> `
  -PackageZip <debug-zip> -ConfirmDynamic54xx 5450
```

Rollback uses the matching confirmation:

```powershell
.\miui-home-hyos-zn\safe-device-test.ps1 `
  -Action Rollback -Serial <adb-serial> -Confirm5402
```

After `Deploy`:

1. Wait for Home to be stable.
2. Perform exactly one formal side gesture.
3. Stop and run `Capture` before another test.

Do not add a routine warmup for a fresh launcher or generation. Only if the
pre-gesture capture proves readiness cannot otherwise be reached, capture one
separately labelled warmup and then perform the one formal gesture.

Evidence is stored under `out/device-tests/` and includes native counters,
filtered logcat, LSPosed logs, crash logs, process events, and tombstone state.

If launcher crashes, stop testing. Let the automatic rollback finish; if the
launcher remains in its crash/safe-mode state, reboot. Reinstall MiuiHome once
only when the launcher still does not recover after rollback/reboot, then wait
for a stable Home before deploying again.

## On-device controller

The package installs:

```text
/data/adb/modules/miui-home-hyos-zn/bin/hsctl
```

Run it through a root shell:

```sh
hsctl status
hsctl counters
hsctl logs 120
hsctl activate --confirm
hsctl rollback --confirm
```

`activate` and `rollback` are bounded runtime operations for an already staged
module. They do not install packages, write properties, kill MiuiHome directly,
or reboot the device. Host-side development should still use
`safe-device-test.ps1`, which supplies the exact package/profile guards and
evidence capture around these commands.

Common healthy evidence after handoff includes installed business/bridge state,
a ready nonzero arbiter generation, an accepted Stub BACK DOWN, a published
identity token, and suppression only after that token is accepted. Interpret
counters together with SystemUI logs; a counter alone is not proof that Shell
owned the gesture.

For the HYOS callback boundary, healthy launcher evidence has
`hyos_runtime_registration=3`, `hyos_runtime_type=1`,
`hyos_runtime_api_version>=1`, `hyos_specialize_count=1`,
`hyos_specialize_rejected=0`, and `hyos_launcher_specialized=1`. Compare the
three lifecycle sequence fields to determine whether final launcher library and
entry observation occurred before or after specialization. This diagnostic
revision deliberately leaves first-gesture business-hook repair enabled.

## Recovery and safety invariants

- Never overwrite `libmiui_home_hyos_zn.so` while it is mapped.
- Never deploy through `/system/bin` or a system overlay.
- Never use Android properties as gates, diagnostics, or recovery controls.
- Never broaden injection beyond the exact `hyos_spawner` path.
- Never add Xiaomi binaries, APK contents, JADX output, Ghidra projects, or
  proprietary disassembly artifacts to the repository.
- A profile or hook mismatch must preserve Xiaomi behavior and fail closed.
- A crash during controlled deployment must trigger rollback before further
  gestures are tested.

Historical loader and reverse-engineering details are kept in the repository's
root Android 17 native-loader report. This README describes the current module
contract, not the chronological experiment diary.
