[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..')

$Paths = @{
    Cli = Join-Path $PSScriptRoot 'bin\hsctl'
    Deploy = Join-Path $PSScriptRoot 'safe-device-test.ps1'
    Native = Join-Path $PSScriptRoot 'zn_module.cpp'
    Profiles = Join-Path $PSScriptRoot 'launcher-profiles.json'
    ProfileGenerator = Join-Path $PSScriptRoot 'generate-launcher-profiles.py'
    ProfileHeader = Join-Path $PSScriptRoot 'launcher_profiles.h'
    ProfileVerifier = Join-Path $PSScriptRoot 'verify-launcher-profiles.py'
    RuntimeResolver = Join-Path $PSScriptRoot 'runtime_profile_resolver.cpp'
    RuntimeVerifier = Join-Path $PSScriptRoot 'verify-runtime-profile.py'
    StatusProtocol = Join-Path $RepoRoot 'app\src\main\java\dev\codex\miuibackgesturehook\ZnStatusProtocol.java'
    StatusState = Join-Path $RepoRoot 'app\src\main\java\dev\codex\miuibackgesturehook\ZnStatusUiState.kt'
    StatusUi = Join-Path $RepoRoot 'app\src\main\java\dev\codex\miuibackgesturehook\activity\PredictiveBackSettingsActivity.kt'
    StatusSystemUi = Join-Path $RepoRoot 'app\src\main\java\dev\codex\miuibackgesturehook\hooks\systemui\SystemUiHookRuntime.java'
    StatusMiuiHome = Join-Path $RepoRoot 'app\src\main\java\dev\codex\miuibackgesturehook\hooks\miuihome\MiuiHomeHookRuntime.java'
    LiveTranslate = Join-Path $RepoRoot 'app\src\main\java\dev\codex\miuibackgesturehook\hooks\googleapp\GoogleAppLiveTranslateRuntime.java'
    Build = Join-Path $PSScriptRoot 'build.ps1'
    AppBuild = Join-Path $RepoRoot 'app\build.gradle.kts'
    Readme = Join-Path $PSScriptRoot 'README.md'
    Customize = Join-Path $PSScriptRoot 'customize.sh.in'
    Uninstall = Join-Path $PSScriptRoot 'uninstall.sh'
}
foreach ($Path in $Paths.Values) {
    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        throw "Missing required file: $Path"
    }
}

$Text = @{}
foreach ($Entry in $Paths.GetEnumerator()) {
    $Text[$Entry.Key] = Get-Content -LiteralPath $Entry.Value -Raw
}

$RequiredCli = @(
    '#!/system/bin/sh',
    'activate --confirm',
    'rollback --confirm',
    'hsctl counters',
    'znmod enable "$MODULE_ID" svc',
    'znmod disable "$MODULE_ID" svc',
    'znmod reload "$MODULE_ID" svc',
    'kill -TERM "$old_pid"',
    'am start -W -a android.intent.action.MAIN',
    'mapped_module_pids',
    'timeout -k 1 1 "$ZNCTL" dump-zn -sa',
    'rollback_runtime activation-',
    'activation_state=ready-for-one-gesture'
)
foreach ($Needle in $RequiredCli) {
    if (-not $Text.Cli.Contains($Needle)) {
        throw "hsctl contract is missing: $Needle"
    }
}

$RequiredDeploy = @(
    "[ValidateSet('Status', 'Deploy', 'Rollback', 'Capture')]",
    "ExpectedVersionCode = '801024371'",
    "ExpectedVersionCode5334 = '801025334'",
    "ExpectedVersionCode5402 = '801025402'",
    "ExpectedVersionCode5436 = '801025436'",
    '[switch]$Confirm5334',
    '[switch]$Confirm5402',
    '[switch]$Confirm5436',
    '[string]$ConfirmDynamic54xx',
    '-ConfirmDynamic54xx <build>',
    '$expectedBuild -lt 5400',
    '$activeBuild -ne $expectedBuild',
    'Hidden system packages:',
    '$MinimumZnVersionCode = 845',
    'Assert-CompatibleZygiskNext',
    'Get-FileHash -Algorithm SHA256',
    '.next-$ShortHash',
    'Get-ModuleMappedPids',
    'Show-HostStatus',
    'Stop-ExactSpawner',
    'Restore-CleanHome',
    'znmod disable',
    "Invoke-Hsctl -Command 'activate --confirm'",
    "Invoke-Hsctl -Command 'rollback --confirm'",
    'Get-LatestTombstone',
    '$Command.Replace("`r`n", "`n").Trim()',
    'native-counters.txt',
    'crash-logcat.txt',
    'process-events.txt',
    'diagnostics.map',
    'Write-Evidence -Phase "activated-$($profile.Id)-before-gesture"'
)
foreach ($Needle in $RequiredDeploy) {
    if (-not $Text.Deploy.Contains($Needle)) {
        throw "safe deployment contract is missing: $Needle"
    }
}

$Forbidden = @(
    'set' + 'prop',
    '/system/bin/hsctl',
    'killall ',
    'am force-stop ',
    'reboot',
    'business-enable',
    'bridge-enable',
    'ENABLE_MARKER=',
    'ARBITER_BRIDGE_MARKER='
)
foreach ($Key in @('Cli', 'Deploy', 'Customize', 'Uninstall')) {
    foreach ($Needle in $Forbidden) {
        if ($Text[$Key].Contains($Needle)) {
            throw "$Key contains forbidden deployment behavior: $Needle"
        }
    }
}

if ($Text.Customize.Contains('$MODPATH/system')) {
    throw 'Installer must not create a system overlay.'
}
if ($Text.Native.Contains('__system_property')) {
    throw 'Native experiment must not read Android properties.'
}
if (-not $Text.Native.Contains('bool IsExplicitlyEnabled()') -or
        -not $Text.Native.Contains('bool IsArbiterBridgeEnabled()')) {
    throw 'Native ZN-state gate contract is missing.'
}
foreach ($Needle in @(
        'ZYGISK_NEXT_API_VERSION',
        'ZYGISK_NEXT_HYOS_API_VERSION',
        'g_api.getRuntime()',
        'runtime->type != ZN_RUNTIME_HYOS',
        'runtime->registerModule(&kHyosModule)',
        'OnHyosAppSpecialized',
        '!StringsEqual(args->process_name, kLauncherProcessName)',
        '!StringsEqual(args->package_name, kLauncherProcessName)')) {
    if (-not $Text.Native.Contains($Needle)) {
        throw "Native HYOS runtime API contract is missing: $Needle"
    }
}
$minimalStart = $Text.Native.IndexOf('bool InstallClaimedBusinessHooksForProfile(')
$minimalEnd = $Text.Native.IndexOf('void ObserveLauncherHandle(', $minimalStart)
if ($minimalStart -lt 0 -or $minimalEnd -le $minimalStart) {
    throw 'Cannot isolate the profile-driven business hook installer.'
}
$minimalInstaller = $Text.Native.Substring($minimalStart, $minimalEnd - $minimalStart)
foreach ($Needle in @(
        'HookBackCallbackQuery', 'HookBackSwipeStart', 'HookBackCancelled',
        'HookBackInvoke', 'HookInterruptOpenPoll',
        'MiuiHomeHyosAcceptedLogBoundaryHook')) {
    if ($minimalInstaller.Contains($Needle)) {
        throw "Minimal handoff installer contains an out-of-scope hook: $Needle"
    }
}
if (-not $minimalInstaller.Contains('HookGestureBackTouchProcessor') -or
        -not $minimalInstaller.Contains('HookGestureStubBackHandler') -or
        -not $minimalInstaller.Contains('TryInstallArbiterBridge')) {
    throw 'Minimal handoff installer is missing its side boundary, diagnostics, or bridge.'
}
foreach ($Needle in @(
        'void RepairBusinessHooksIfRemapped(',
        'outer_original != inner_original',
        'outer_original != stub_back_handler_original',
        'profile->pointer_handler_prologue',
        'profile->touch_processor_prologue',
        'profile->side_handler_prologue',
        'BusinessHookTopology::kLegacyThreeStage',
        'g_api.inlineUnhook(',
        'g_business_repair_success_count')) {
    if (-not $minimalInstaller.Contains($Needle)) {
        throw "Launcher remap repair contract is missing: $Needle"
    }
}
foreach ($Needle in @(
        'profile->side_handler_offset',
        'profile->side_edge_field_offset',
        'g_pending_down.edge != stub_edge',
        'CurrentEventMatchesOwnedStream(event)',
        'published GestureStubView accepted Back DOWN',
        'preventing only GestureInputBackHelper::on_touch_event')) {
    if (-not $Text.Native.Contains($Needle)) {
        throw "GestureStubView Back-only handoff contract is missing: $Needle"
    }
}
foreach ($Needle in @(
        'PackageManager_has_system_feature',
        'android.software.contextualsearch',
        'com.google.android.feature.CONTEXTUAL_SEARCH',
        'contextual_search_enabled',
        'VerifySystemUiUid(sender_uid)',
        'IsNativeSuccess(result) && result.bytes[1] == uint8_t{0}',
        'g_contextual_feature_override_count',
        'HookContextualLongPressHandler',
        'CleanupContextualLongPressClosure',
        'closure_storage',
        'closure_owner',
        'static_cast<uint8_t*>(completion_state) + 0x10u',
        'profile->contextual_search_invoke_offset',
        'g_contextual_search_invoke_count')) {
    if (-not $Text.Native.Contains($Needle)) {
        throw "Native launcher contextual-search contract is missing: $Needle"
    }
}
foreach ($Needle in @(
        'EXTRA_CONTEXTUAL_SEARCH_ENABLED',
        'isContextualSearchLongPressRuntimeEnabled()',
        'setShareIdentityEnabled(true)')) {
    if (-not $Text.StatusMiuiHome.Contains($Needle)) {
        throw "SystemUI contextual-search state publication is missing: $Needle"
    }
}
foreach ($Needle in @(
        'contextualSearchStatePreferenceListener',
        'registerOnSharedPreferenceChangeListener(',
        'unregisterOnSharedPreferenceChangeListener(',
        'contextualSearchPreference:',
        'nativeLauncherOwnsContextualSearchLongPress()')) {
    if (-not $Text.StatusSystemUi.Contains($Needle)) {
        throw "SystemUI live contextual-search refresh is missing: $Needle"
    }
}
foreach ($Needle in @(
        'preserveLiveTranslateActionVisibility',
        '.intercept(this::preserveLiveTranslateActionVisibility)',
        '.intercept(this::overrideLiveTranslateBooleanGate)')) {
    if (-not $Text.LiveTranslate.Contains($Needle)) {
        throw "Live Translate first-screen visibility contract is missing: $Needle"
    }
}
$AppVersionMatches = [regex]::Matches(
    $Text.AppBuild, '(?m)^\s*versionName\s*(?:=\s*)?"[^"]+"\s*$')
if ($AppVersionMatches.Count -ne 1 -or
        -not $Text.Build.Contains('$PSVersionTable.PSEdition') -or
        -not $Text.Build.Contains("Join-Path `$RepoRoot 'app\build.gradle'") -or
        -not $Text.Build.Contains('git -C $RepoRoot rev-list --count HEAD') -or
        -not $Text.Build.Contains("generate-launcher-profiles.py") -or
        $Text.Build.Contains("generate-launcher-profiles.ps1") -or
        $Text.Build -match "(?m)^\s*`$Version\s*=\s*'\d") {
    throw 'App and ZN package versions no longer share the canonical BuildConfig sources.'
}
foreach ($Needle in @(
        'library_sha256',
        'rva_to_file_offset(',
        'require_executable=True',
        'identity_fingerprints',
        'side_handler')) {
    if (-not $Text.ProfileVerifier.Contains($Needle)) {
        throw "Offline launcher profile verifier is missing: $Needle"
    }
}
if (-not $Text.Native.Contains('ResolveLauncherProfile(') -or
        -not $Text.Native.Contains('MatchesLauncherProfile(') -or
        -not $Text.Native.Contains('InstallLauncherInputHooksForProfile(')) {
    throw 'Launcher hooks are not selected through the fail-closed profile registry.'
}
foreach ($Needle in @(
        'ResolveSideBoundaryProfile(',
        'BusinessHookTopology::kSideBoundaryOnly',
        'DT_JMPREL',
        'kMotionActionMasked',
        'kRuntimeGetBinder',
        'ResolveContextualImports',
        'ResolveContextualSearch',
        'kContextualLongPressPrologue',
        'contextual_long_press_handler_offset',
        'state_offset == *pointer_offset + sizeof(uintptr_t)',
        'matches != 1u')) {
    if (-not $Text.RuntimeResolver.Contains($Needle)) {
        throw "Runtime launcher profile resolver is missing: $Needle"
    }
}
foreach ($Needle in @(
        'ACTION_QUERY',
        'ACTION_REPLY',
        'EXTRA_NATIVE_READY',
        'EXTRA_LEGACY_MODE',
        'EXTRA_LEGACY_READY',
        'EXTRA_NONCE')) {
    if (-not $Text.StatusProtocol.Contains($Needle)) {
        throw "Runtime status protocol is missing: $Needle"
    }
}
foreach ($Needle in @(
        'HandleRuntimeStatusQuery',
        'kRuntimeStatusResponseAction',
        'status_native_profile_resolved',
        'status_native_business_state',
        'status_native_bridge_state')) {
    if (-not $Text.Native.Contains($Needle)) {
        throw "Native runtime status bridge is missing: $Needle"
    }
}
foreach ($Needle in @(
        'registerStatusReceiver()',
        'requestZnStatus()',
        'getSentFromPackage()',
        'ZnRuntimeStatusCard(',
        'PressFeedbackType.Tilt',
        'Icons.Rounded.CheckCircleOutline',
        'Icons.Rounded.WarningAmber',
        'Icons.Rounded.ErrorOutline',
        'NativeNoResponse',
        'systemUiResponseReceived',
        'systemUiReadyReported')) {
    if (-not $Text.StatusUi.Contains($Needle)) {
        throw "Runtime status UI contract is missing: $Needle"
    }
}
foreach ($Needle in @(
        'handleModuleRuntimeStatusQuery',
        'handleNativeRuntimeStatusReply',
        'isTrustedModuleStatusSender',
        'setShareIdentityEnabled(true)',
        'pendingModuleStatusNonce',
        'systemUiInputArbiterStateAction()',
        'resolveCurrentApplicationContext')) {
    if (-not $Text.StatusSystemUi.Contains($Needle)) {
        throw "SystemUI runtime status bridge is missing: $Needle"
    }
}
foreach ($Needle in @(
        'publishLegacyRuntimeStatusReply',
        'EXTRA_STATUS_LEGACY_READY',
        'setShareIdentityEnabled(true)')) {
    if (-not $Text.StatusMiuiHome.Contains($Needle)) {
        throw "MiuiHome Android 16 runtime status bridge is missing: $Needle"
    }
}
foreach ($Needle in @(
        'legacyMode',
        'EXTRA_LEGACY_READY',
        'LegacyNotReady')) {
    if (-not $Text.StatusState.Contains($Needle)) {
        throw "Android 16 runtime status model is missing: $Needle"
    }
}
foreach ($Needle in @(
        'SIDE_PROLOGUE',
        'Runtime_get_application_thread_binder',
        'resolve_side(image)',
        'resolve_contextual_long_press(image)',
        'corrupted side boundary did not fail closed',
        'corrupted contextual long-press Fn did not fail closed')) {
    if (-not $Text.RuntimeVerifier.Contains($Needle)) {
        throw "Runtime launcher profile regression is missing: $Needle"
    }
}

$Manifest = $Text.Profiles | ConvertFrom-Json
if ($Manifest.schema_version -ne 1) {
    throw 'Unexpected launcher profile schema version.'
}
$Profiles = @($Manifest.profiles)
$ProfileIds = (@($Profiles | ForEach-Object { $_.id } | Sort-Object) -join ',')
if ($Profiles.Count -ne 3 -or $ProfileIds -ne '4371,5334,5402') {
    throw 'The active launcher profile manifest must contain exactly 4371, 5334, and 5402.'
}
$Profile4371 = @($Profiles | Where-Object { $_.id -eq '4371' })[0]
$Profile5334 = @($Profiles | Where-Object { $_.id -eq '5334' })[0]
$Profile5402 = @($Profiles | Where-Object { $_.id -eq '5402' })[0]
if ($Profile4371.hook_topology -ne 'legacy_three_stage' -or
        $Profile4371.entry_offset -ne '0x885d00' -or
        $Profile4371.side_handler.offset -ne '0xc6e954' -or
        $Profile4371.side_handler.edge_field_offset -ne '0xec' -or
        $Profile4371.abi.runtime_ready_value -ne 3) {
    throw '4371 launcher profile no longer preserves its proven hook contract.'
}
if ($Profile5334.hook_topology -ne 'side_boundary_only' -or
        $Profile5334.entry_offset -ne '0xc8ffd8' -or
        $Profile5334.side_handler.offset -ne '0x80c3bc' -or
        $Profile5334.side_handler.edge_field_offset -ne '0xf4' -or
        $Profile5334.contextual_search.long_press_handler_offset -ne '0x71af90' -or
        $Profile5334.contextual_search.invoke_offset -ne '0xadc22c' -or
        $Profile5334.abi.runtime_state_offset -ne '0x132ab80' -or
        $Profile5334.abi.runtime_ready_value -ne 0) {
    throw '5334 launcher profile no longer matches the reviewed static boundary and contextual-search route.'
}
if ($Profile5402.version_code -ne 801025402 -or
        $Profile5402.version_name -ne 'RELEASE-8.01.02.5402-260807-08181825-R' -or
        $Profile5402.library_sha256 -ne
            '053b3b6ad84815fb319b2f87e766f67cbf1a22fcfe7f7ed2cd03502e569e0f98' -or
        $Profile5402.hook_topology -ne 'side_boundary_only' -or
        $Profile5402.entry_offset -ne '0xc94b14' -or
        $Profile5402.side_handler.offset -ne '0x810010' -or
        $Profile5402.side_handler.edge_field_offset -ne '0xf4' -or
        $Profile5402.abi.rstring_vtable_offset -ne '0x12b8450' -or
        $Profile5402.abi.runtime_pointer_offset -ne '0x132fa00' -or
        $Profile5402.abi.runtime_state_offset -ne '0x132fa08' -or
        $Profile5402.abi.runtime_ready_value -ne 0) {
    throw '5402 launcher profile no longer matches the reviewed static boundary.'
}
foreach ($Needle in @(
        'launcher-profiles.json',
        'Generated from launcher-profiles.json',
        'has no identity fingerprint',
        'is missing a diagnostic hook fingerprint')) {
    if (-not $Text.ProfileGenerator.Contains($Needle)) {
        throw "Launcher profile generator validation is missing: $Needle"
    }
}
if ($Text.Native.Contains('gesture_type == kGestureTypeBack4371') -or
        $Text.Native.Contains('published classified Back DOWN at processor boundary')) {
    throw 'Shared GestureInputMonitor still owns the retired classified handoff.'
}
if ($Text.Native.Contains('published accepted DOWN at processor boundary')) {
    throw 'Shared GestureInputMonitor outer still publishes an unclassified DOWN.'
}
if (-not $Text.Readme.Contains('[safe-device-test.ps1](safe-device-test.ps1)') -or
        -not $Text.Readme.Contains('exactly one formal side gesture') -or
        -not $Text.Readme.Contains('readiness warmup')) {
    throw 'README does not document the only approved deployment/test flow.'
}

[pscustomobject]@{
    Controller = $Paths.Cli
    HostDeployment = $Paths.Deploy
    Commands = 'status, activate --confirm, rollback --confirm, counters, logs, help'
    RuntimeGate = 'Zygisk Next module enabled state'
    Result = 'PASS'
}
