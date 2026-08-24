[CmdletBinding()]
param(
    [ValidateSet('Status', 'Verify', 'Deploy', 'Capture')]
    [string]$Action = 'Status',
    [Parameter(Mandatory = $true)]
    [string]$Serial,
    [ValidateSet('Debug', 'Release')]
    [string]$Variant = 'Debug',
    [string]$Apk,
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$Nl = [Environment]::NewLine
$PackageName = 'dev.codex.miuibackgesturehook'
$RepoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$EvidenceRoot = Join-Path $PSScriptRoot 'out\lsposed-device-tests'

function Resolve-Adb {
    $command = Get-Command adb -ErrorAction SilentlyContinue
    if ($null -ne $command) { return $command.Source }
    $properties = Join-Path $RepoRoot 'local.properties'
    if (Test-Path -LiteralPath $properties -PathType Leaf) {
        $line = Get-Content -LiteralPath $properties |
            Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($null -ne $line) {
            $sdk = $line.Substring('sdk.dir='.Length)
            $sdk = $sdk.Replace('\:', ':').Replace('\\', '\')
            $candidate = Join-Path $sdk 'platform-tools\adb.exe'
            if (Test-Path -LiteralPath $candidate -PathType Leaf) {
                return $candidate
            }
        }
    }
    throw 'adb is unavailable and local.properties does not identify an Android SDK.'
}

$Adb = Resolve-Adb

function Resolve-BuildTool {
    param([string]$Name)
    $sdk = Split-Path (Split-Path $Adb -Parent) -Parent
    $directory = Get-ChildItem -LiteralPath (Join-Path $sdk 'build-tools') `
        -Directory | Where-Object { $_.Name -match '^\d+(\.\d+){2}$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if ($null -eq $directory) { throw 'No stable Android build-tools directory exists.' }
    $candidate = Join-Path $directory.FullName $Name
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "Android build tool is missing: $candidate"
    }
    $candidate
}

function Invoke-Adb {
    param([string[]]$Arguments, [switch]$AllowFailure)
    $lines = @(& $Adb -s $Serial @Arguments 2>&1 |
        ForEach-Object { $_.ToString() })
    $exitCode = $LASTEXITCODE
    if ($exitCode -ne 0 -and -not $AllowFailure) {
        throw "adb failed ($exitCode): $($Arguments -join ' ')$Nl$($lines -join $Nl)"
    }
    [pscustomobject]@{
        ExitCode = $exitCode
        Lines = $lines
        Text = $lines -join $Nl
    }
}

function Invoke-Root {
    param([string]$Command, [switch]$AllowFailure)
    $singleQuote = [char]39
    $doubleQuote = [char]34
    $quoteEscape = -join @(
        $singleQuote, $doubleQuote, $singleQuote, $doubleQuote, $singleQuote)
    $normalized = $Command.Replace("`r`n", "`n").Trim()
    $escaped = $normalized.Replace($singleQuote.ToString(), $quoteEscape)
    $remote = [string]::Concat('su -c ', $singleQuote, $escaped, $singleQuote)
    Invoke-Adb -Arguments @('shell', $remote) -AllowFailure:$AllowFailure
}

function Assert-Device {
    if ((Invoke-Adb -Arguments @('get-state')).Text.Trim() -ne 'device') {
        throw "Device $Serial is not online."
    }
    if ((Invoke-Root -Command 'id -u').Text.Trim() -ne '0') {
        throw "Device $Serial has no adb-accessible root shell."
    }
}

function Assert-NoForeignNativeOwner {
    $command = @'
spawner=""
for pid in $(ps -A -o USER,PID,PPID | awk '$1 == "root" && $3 == 1 {print $2}'); do
  [ "$(readlink "/proc/$pid/exe" 2>/dev/null)" = "/system_ext/bin/hyos_spawner" ] || continue
  spawner="$pid"
done
[ -n "$spawner" ] || exit 41
for pid in "$spawner" $(ps -A -o PID,PPID | awk -v owner="$spawner" '$2 == owner {print $1}'); do
  [ -r "/proc/$pid/maps" ] || continue
  if grep -F '/data/adb/modules/miui-home-hyos-zn/' "/proc/$pid/maps" >/dev/null 2>&1; then
    echo "$pid"
    exit 42
  fi
done
'@
    $result = Invoke-Root -Command $command -AllowFailure
    if ($result.ExitCode -eq 42) {
        throw "Refusing deployment: PID $($result.Text.Trim()) maps a foreign native module owner."
    }
    if ($result.ExitCode -ne 0) {
        throw "Refusing deployment: native-owner state is unreadable (exit=$($result.ExitCode))."
    }
}

function Get-ExactSpawnerPid {
    $command = @'
for pid in $(ps -A -o USER,PID,PPID | awk '$1 == "root" && $3 == 1 {print $2}'); do
  [ "$(readlink "/proc/$pid/exe" 2>/dev/null)" = "/system_ext/bin/hyos_spawner" ] || continue
  echo "$pid"
done
'@
    $result = Invoke-Root -Command $command -AllowFailure
    $pids = @($result.Lines | Where-Object { $_ -match '^\d+$' })
    if ($result.ExitCode -ne 0 -or $pids.Count -ne 1) { return $null }
    [int]$pids[0]
}

function Get-LauncherProcesses {
    $command = @'
for pid in $(pidof com.miui.home 2>/dev/null); do
  [ -r "/proc/$pid/cmdline" ] || continue
  name="$(tr '\000' '\n' < "/proc/$pid/cmdline" | head -n 1)"
  [ "$name" = "com.miui.home" ] || continue
  ppid="$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$pid/status")"
  echo "$pid:$ppid"
done
'@
    $result = Invoke-Root -Command $command -AllowFailure
    $processes = [System.Collections.Generic.List[object]]::new()
    foreach ($line in $result.Lines) {
        if ($line -match '^(\d+):(\d+)$') {
            $processes.Add([pscustomobject]@{
                Pid = [int]$Matches[1]
                ParentPid = [int]$Matches[2]
            })
        }
    }
    @($processes)
}

function Get-SystemUiPid {
    $text = (Invoke-Adb -Arguments @(
        'shell', 'pidof', 'com.android.systemui') -AllowFailure).Text.Trim()
    if ($text -match '^\d+$') { return [int]$text }
    return $null
}

function Get-InstalledApkPath {
    $result = Invoke-Adb -Arguments @('shell', 'pm', 'path', $PackageName) `
        -AllowFailure
    $paths = @($result.Lines | ForEach-Object {
        if ($_ -match '^package:(/data/app/[^\s]+/base\.apk)$') {
            $Matches[1]
        }
    })
    if ($result.ExitCode -ne 0 -or $paths.Count -ne 1) { return $null }
    $paths[0]
}

function Get-RemoteApkIdentity {
    param([string]$Path)
    if ($Path -notmatch '^/data/app/[A-Za-z0-9_~+=./-]+/base\.apk$') {
        throw "Unsafe installed APK path: $Path"
    }
    $result = Invoke-Root -Command @"
sha256sum '$Path' | awk '{print `$1}'
stat -c '%D:%i:%s' '$Path'
"@
    if ($result.Lines.Count -lt 2 -or
            $result.Lines[0] -notmatch '^[0-9a-fA-F]{64}$' -or
            $result.Lines[1] -notmatch '^[0-9a-fA-F]+:\d+:\d+$') {
        throw "Installed APK identity is unreadable: $Path"
    }
    [pscustomobject]@{
        Path = $Path
        Sha256 = $result.Lines[0].ToLowerInvariant()
        DeviceInodeSize = $result.Lines[1]
        Inode = [int64]($result.Lines[1].Split(':')[1])
    }
}

function Get-LatestTombstone {
    $command = @'
for item in /data/tombstones/tombstone_*; do
  [ -f "$item" ] && stat -c '%Y:%s:%n' "$item"
done | sort -n | tail -n 1
'@
    (Invoke-Root -Command $command -AllowFailure).Text.Trim()
}

function Get-LsposedLogSnapshot {
    $command = @'
path="$(ls -1t /data/adb/lspd/log/modules_*.log 2>/dev/null | head -n 1)"
[ -n "$path" ] || exit 0
size="$(stat -c '%s' "$path" 2>/dev/null)" || exit 0
echo "$path"
echo "$size"
'@
    $result = Invoke-Root -Command $command -AllowFailure
    if ($result.Lines.Count -lt 2 -or
            $result.Lines[0] -notmatch '^/data/adb/lspd/log/modules_[^/]+\.log$' -or
            $result.Lines[1] -notmatch '^\d+$') {
        return [pscustomobject]@{ Path = ''; Size = [int64]0 }
    }
    [pscustomobject]@{
        Path = $result.Lines[0]
        Size = [int64]$result.Lines[1]
    }
}

function Read-LsposedLogDelta {
    param([pscustomobject]$Before)
    $current = Get-LsposedLogSnapshot
    if ([string]::IsNullOrWhiteSpace($current.Path)) { return '' }
    if ($current.Path -eq $Before.Path -and $current.Size -ge $Before.Size) {
        $offset = $Before.Size + 1
        return (Invoke-Root -Command "tail -c +$offset '$($current.Path)'" `
            -AllowFailure).Text
    }
    return (Invoke-Root -Command "cat '$($current.Path)'" -AllowFailure).Text
}

function Wait-Api102HotReload {
    param([int]$ExpectedSystemUiPid, [pscustomobject]$LogBefore)
    for ($attempt = 0; $attempt -lt 100; $attempt++) {
        $currentPid = Get-SystemUiPid
        if ($null -eq $currentPid -or $currentPid -ne $ExpectedSystemUiPid) {
            throw 'SystemUI restarted during installation; API-102 hot reload was not preserved.'
        }
        $delta = Read-LsposedLogDelta -Before $LogBefore
        if ($delta -match 'Hot reloaded, build=.*process=com\.android\.systemui') {
            return $delta
        }
        Start-Sleep -Milliseconds 200
    }
    throw 'No fresh SystemUI API-102 hot-reload completion appeared within 20 seconds.'
}

function Stop-ExactSpawner {
    param([int]$OldPid)
    if ((Get-ExactSpawnerPid) -ne $OldPid) {
        throw "PID $OldPid is no longer the exact root hyos_spawner."
    }
    Invoke-Root -Command "kill -TERM $OldPid" | Out-Null
    for ($attempt = 0; $attempt -lt 150; $attempt++) {
        $newPid = Get-ExactSpawnerPid
        if ($null -ne $newPid -and $newPid -ne $OldPid) { return $newPid }
        Start-Sleep -Milliseconds 100
    }
    throw 'hyos_spawner did not reach a replacement PID within 15 seconds.'
}

function Start-AndWaitLauncher {
    param([int]$ExpectedParent, [int[]]$RejectedPids)
    Invoke-Adb -Arguments @(
        'shell', 'am', 'start', '-W', '-a', 'android.intent.action.MAIN',
        '-c', 'android.intent.category.HOME') -AllowFailure | Out-Null
    for ($attempt = 0; $attempt -lt 200; $attempt++) {
        $launchers = @(Get-LauncherProcesses)
        $accepted = @($launchers | Where-Object {
            $_.ParentPid -eq $ExpectedParent -and $_.Pid -notin $RejectedPids
        })
        $rejectedAlive = @($launchers | Where-Object { $_.Pid -in $RejectedPids })
        if ($accepted.Count -eq 1 -and $rejectedAlive.Count -eq 0) {
            return $accepted[0]
        }
        Start-Sleep -Milliseconds 100
    }
    throw 'MiuiHome did not reach one clean child under the replacement spawner.'
}

function Assert-LauncherMapsCurrentApk {
    param(
        [int]$LauncherPid,
        [pscustomobject]$ApkIdentity,
        [int64[]]$RejectedInodes = @()
    )
    $result = Invoke-Root -Command `
        "grep -F ' $($ApkIdentity.Path)' /proc/$LauncherPid/maps" -AllowFailure
    if ($result.ExitCode -ne 0 -or $result.Lines.Count -eq 0) {
        throw "Launcher PID $LauncherPid does not map the active module APK."
    }
    if ($result.Text.Contains('(deleted)')) {
        throw "Launcher PID $LauncherPid still maps a deleted APK inode."
    }
    foreach ($line in $result.Lines) {
        $columns = @($line.Trim() -split '\s+')
        if ($columns.Count -lt 6 -or $columns[4] -notmatch '^\d+$' -or
                [int64]$columns[4] -ne $ApkIdentity.Inode) {
            throw "Launcher PID $LauncherPid maps the active path through a different inode."
        }
    }
    if ($RejectedInodes.Count -ne 0) {
        $allMaps = Invoke-Root -Command "cat /proc/$LauncherPid/maps"
        foreach ($line in $allMaps.Lines) {
            $columns = @($line.Trim() -split '\s+')
            if ($columns.Count -ge 5 -and $columns[4] -match '^\d+$' -and
                    [int64]$columns[4] -in $RejectedInodes) {
                throw "Launcher PID $LauncherPid retains a rejected APK inode."
            }
        }
    }
    $wrong = Invoke-Root -Command `
        "grep -F '$PackageName' /proc/$LauncherPid/maps | grep -F '(deleted)'" `
        -AllowFailure
    if ($wrong.ExitCode -eq 0 -and -not [string]::IsNullOrWhiteSpace($wrong.Text)) {
        throw "Launcher PID $LauncherPid retains an older module APK mapping."
    }
    $result.Text
}

function Wait-NativeReadyStatus {
    param([int]$LauncherPid)
    $logs = Invoke-Adb -Arguments @(
        'shell', 'logcat', '-d', '-v', 'threadtime', '-t', '2000',
        '-s', 'MiuiHomeHyosLsp:V', 'MiuiHomeHyosMadvise:V', '*:S') `
        -AllowFailure
    $selected = @($logs.Lines | Where-Object {
        $_ -match "\s$LauncherPid\s+\d+\s+[VDIWEF]\s+MiuiHomeHyos"
    })
    $nativeLog = $selected -join $Nl
    if ($nativeLog.Contains('LSPosed launcher hooks rejected without madvise guard') -or
            $nativeLog.Contains('unsupported hyos_spawner Build ID')) {
        throw "Native initialization rejected the replacement Launcher.$Nl$nativeLog"
    }

    # Startup lines are diagnostic only: Xiaomi may prune them before logcat can
    # collect the buffer.  The module's own UI performs the authenticated
    # module -> SystemUI -> native -> SystemUI -> module challenge and classifies
    # Ready only after the native profile, business hooks, bridge, drawer hook,
    # overview hook, and SystemUI input monitor all report ready.
    $statusComponent = "$PackageName/.activity.PredictiveBackSettingsActivity"
    $remoteDump = "/data/local/tmp/mbgh-native-status-$LauncherPid.xml"
    $lastText = ''
    $statusLogBefore = Get-LsposedLogSnapshot
    try {
        $start = Invoke-Adb -Arguments @(
            'shell', 'am', 'start', '-S', '-W', '-n', $statusComponent) `
            -AllowFailure
        if ($start.ExitCode -ne 0 -or $start.Text -notmatch 'Status:\s+ok') {
            throw "Could not start the authenticated native status probe.$Nl$($start.Text)"
        }
        for ($attempt = 0; $attempt -lt 20; $attempt++) {
            Start-Sleep -Milliseconds 250
            $statusLog = Read-LsposedLogDelta -Before $statusLogBefore
            $nativeStatusLines = @($statusLog -split "`r?`n" | Where-Object {
                $_ -match 'Published module runtime status reply' -and
                $_ -match 'nativeResponse=true'
            })
            $nativeStatus = $nativeStatusLines | Select-Object -Last 1
            if ($null -ne $nativeStatus -and
                    $nativeStatus -match 'statusReady=true') {
                return "authenticated_native_status=ready$Nl$nativeStatus"
            }
            if ($null -ne $nativeStatus -and
                    $nativeStatus -match 'statusReady=false') {
                throw "Authenticated native status reported not ready.$Nl$nativeStatus"
            }
            # APKs built before statusReady was added still need the visible UI
            # compatibility path. New builds no longer depend on foreground
            # ownership once the authenticated nonce exchange has completed.
            Invoke-Adb -Arguments @(
                'shell', 'uiautomator', 'dump', $remoteDump) `
                -AllowFailure | Out-Null
            $dump = Invoke-Adb -Arguments @(
                'shell', 'cat', $remoteDump) -AllowFailure
            if ($dump.ExitCode -ne 0) { continue }
            $matches = [regex]::Matches($dump.Text, 'text="([^"]+)"')
            $lastText = (@($matches | ForEach-Object {
                [System.Net.WebUtility]::HtmlDecode($_.Groups[1].Value)
            }) | Select-Object -Unique) -join ' | '
            if ($lastText.Contains('Native hook is working') -or
                    $lastText.Contains('原生 Hook 运行正常')) {
                return "authenticated_native_status=ready$Nl$lastText"
            }
        }
        throw "Authenticated native status did not become ready.$Nl$lastText"
    } finally {
        Invoke-Adb -Arguments @(
            'shell', 'rm', '-f', $remoteDump) -AllowFailure | Out-Null
        # Remove only the temporary settings UI used by the challenge.  The
        # LSPosed hooks live in their scoped processes and remain untouched.
        Invoke-Adb -Arguments @(
            'shell', 'am', 'force-stop', $PackageName) -AllowFailure | Out-Null
    }
}

function Assert-LocalApk {
    param([string]$Path)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $required = @(
            'META-INF/xposed/native_init.list',
            'lib/arm64-v8a/libmiui_home_hyos_lsp.so',
            'lib/arm64-v8a/liblsplt.so'
        )
        foreach ($entryName in $required) {
            if ($null -eq $archive.GetEntry($entryName)) {
                throw "APK is missing $entryName."
            }
        }
        $entry = $archive.GetEntry('META-INF/xposed/native_init.list')
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try { $nativeList = $reader.ReadToEnd() } finally { $reader.Dispose() }
        if ($nativeList.Trim() -ne 'libmiui_home_hyos_lsp.so') {
            throw 'APK native_init.list does not name the LSPosed HYOS payload exactly.'
        }
    } finally {
        $archive.Dispose()
    }
    $zipalign = Resolve-BuildTool -Name 'zipalign.exe'
    & $zipalign -c -P 16 4 $Path
    if ($LASTEXITCODE -ne 0) { throw 'APK 16KB ZIP alignment verification failed.' }
    $apksigner = Resolve-BuildTool -Name 'apksigner.bat'
    & $apksigner verify $Path
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
}

function Resolve-DeploymentApk {
    if (-not [string]::IsNullOrWhiteSpace($Apk)) {
        return (Resolve-Path -LiteralPath $Apk).Path
    }
    $variantLower = $Variant.ToLowerInvariant()
    $output = Join-Path $RepoRoot `
        "app\build\outputs\apk\$variantLower\app-$variantLower.apk"
    if (-not $SkipBuild) {
        $gradle = Join-Path $RepoRoot 'gradlew.bat'
        & $gradle ":app:assemble$Variant" | Out-Host
        if ($LASTEXITCODE -ne 0) {
            throw "Gradle assemble$Variant failed: $LASTEXITCODE"
        }
    }
    if (-not (Test-Path -LiteralPath $output -PathType Leaf)) {
        throw "Deployment APK does not exist: $output"
    }
    (Resolve-Path -LiteralPath $output).Path
}

function Write-Evidence {
    param([string]$Phase, [string]$Extra = '')
    $target = Join-Path $EvidenceRoot `
        "$(Get-Date -Format 'yyyyMMdd-HHmmss')-$Phase"
    [System.IO.Directory]::CreateDirectory($target) | Out-Null
    $status = [System.Collections.Generic.List[string]]::new()
    $status.Add("serial=$Serial")
    $status.Add("hyos_pid=$(Get-ExactSpawnerPid)")
    $status.Add("systemui_pid=$(Get-SystemUiPid)")
    $path = Get-InstalledApkPath
    $status.Add("apk_path=$path")
    if ($null -ne $path) {
        try {
            $identity = Get-RemoteApkIdentity -Path $path
            $status.Add("apk_sha256=$($identity.Sha256)")
            $status.Add("apk_device_inode_size=$($identity.DeviceInodeSize)")
        } catch {
            $status.Add("apk_identity_error=$($_.Exception.Message)")
        }
    }
    foreach ($launcher in @(Get-LauncherProcesses)) {
        $status.Add("launcher_pid=$($launcher.Pid),parent=$($launcher.ParentPid)")
    }
    if (-not [string]::IsNullOrWhiteSpace($Extra)) { $status.Add($Extra) }
    [System.IO.File]::WriteAllText(
        (Join-Path $target 'device-status.txt'), ($status -join $Nl) + $Nl)
    $logs = Invoke-Adb -Arguments @(
        'shell', 'logcat', '-d', '-v', 'threadtime', '-t', '3000',
        '-s', 'MiuiHomeHyosLsp:V', 'MiuiHomeHyosMadvise:V', '*:S') `
        -AllowFailure
    $selected = @($logs.Lines | Where-Object {
        $_ -match 'MiuiHomeHyos'
    })
    [System.IO.File]::WriteAllText(
        (Join-Path $target 'runtime-logcat.txt'), ($selected -join $Nl) + $Nl)
    $latestLsposed = Get-LsposedLogSnapshot
    $lsposed = if ([string]::IsNullOrWhiteSpace($latestLsposed.Path)) {
        ''
    } else {
        (Invoke-Root -Command "tail -n 8000 '$($latestLsposed.Path)'" `
            -AllowFailure).Text
    }
    [System.IO.File]::WriteAllText(
        (Join-Path $target 'lsposed-module-log.txt'), $lsposed + $Nl)
    $tombstones = Invoke-Root -Command @'
for item in /data/tombstones/tombstone_*; do
  [ -f "$item" ] && stat -c '%Y %s %n' "$item"
done
'@ -AllowFailure
    [System.IO.File]::WriteAllText(
        (Join-Path $target 'tombstones.txt'), $tombstones.Text + $Nl)
    $target
}

function Install-Apk {
    param(
        [string]$Path,
        [switch]$EnableRollback,
        [switch]$AllowDowngrade,
        [switch]$AllowFailure
    )
    $arguments = [System.Collections.Generic.List[string]]::new()
    $arguments.Add('install')
    $arguments.Add('-r')
    if ($EnableRollback) {
        $arguments.Add('--enable-rollback')
        # Retain app data and remote preferences if this APK is rolled back.
        $arguments.Add('2')
    }
    if ($AllowDowngrade) { $arguments.Add('-d') }
    $arguments.Add($Path)
    $result = Invoke-Adb -Arguments @($arguments) -AllowFailure:$AllowFailure
    if (-not $AllowFailure -and -not $result.Text.Contains('Success')) {
        throw "Package installation did not report success.$Nl$($result.Text)"
    }
    $result
}

function Invoke-Rollback {
    param([string]$RollbackApk, [string]$ExpectedHash)
    if (-not (Test-Path -LiteralPath $RollbackApk -PathType Leaf)) {
        return 'rollback_apk=unavailable'
    }
    $rollbackMethod = 'platform'
    $platform = Invoke-Adb -Arguments @(
        'shell', 'pm', 'rollback-app', $PackageName) -AllowFailure
    $identity = $null
    if ($platform.ExitCode -eq 0 -and $platform.Text.Contains('Success')) {
        for ($attempt = 0; $attempt -lt 200; $attempt++) {
            $candidatePath = Get-InstalledApkPath
            if ($null -ne $candidatePath) {
                try {
                    $candidate = Get-RemoteApkIdentity -Path $candidatePath
                    if ($candidate.Sha256 -eq $ExpectedHash) {
                        $identity = $candidate
                        break
                    }
                } catch {
                    # PackageManager may briefly expose neither generation.
                }
            }
            Start-Sleep -Milliseconds 100
        }
    }
    if ($null -eq $identity) {
        $rollbackMethod = 'pulled-apk-fallback'
        $install = Install-Apk -Path $RollbackApk -AllowDowngrade -AllowFailure
        if ($install.ExitCode -ne 0 -or -not $install.Text.Contains('Success')) {
            return "rollback_install=failed$Nl" +
                "platform=$($platform.Text)$Nl" +
                "fallback=$($install.Text)"
        }
        $currentPath = Get-InstalledApkPath
        if ($null -eq $currentPath) {
            return 'rollback_install=identity-unavailable'
        }
        $identity = Get-RemoteApkIdentity -Path $currentPath
    }
    if ($identity.Sha256 -ne $ExpectedHash) {
        return "rollback_install=hash-mismatch,actual=$($identity.Sha256)"
    }
    $oldSpawner = Get-ExactSpawnerPid
    if ($null -eq $oldSpawner) { return 'rollback_spawner=unavailable' }
    $oldLaunchers = @((Get-LauncherProcesses) | ForEach-Object { $_.Pid })
    try {
        $newSpawner = Stop-ExactSpawner -OldPid $oldSpawner
        $launcher = Start-AndWaitLauncher -ExpectedParent $newSpawner `
            -RejectedPids $oldLaunchers
        Assert-LauncherMapsCurrentApk -LauncherPid $launcher.Pid `
            -ApkIdentity $identity | Out-Null
        Wait-NativeReadyStatus -LauncherPid $launcher.Pid | Out-Null
        return "rollback=restored,method=$rollbackMethod," +
            "hyos_pid=$newSpawner,launcher_pid=$($launcher.Pid)"
    } catch {
        return "rollback_activation=failed,error=$($_.Exception.Message)"
    }
}

function Show-Status {
    Assert-NoForeignNativeOwner
    'foreign_native_owner=absent'
    "hyos_pid=$(Get-ExactSpawnerPid)"
    "systemui_pid=$(Get-SystemUiPid)"
    $path = Get-InstalledApkPath
    "apk_path=$path"
    if ($null -ne $path) {
        $identity = Get-RemoteApkIdentity -Path $path
        "apk_sha256=$($identity.Sha256)"
        "apk_device_inode_size=$($identity.DeviceInodeSize)"
    }
    foreach ($launcher in @(Get-LauncherProcesses)) {
        "launcher_pid=$($launcher.Pid),parent=$($launcher.ParentPid)"
    }
}

Assert-Device
switch ($Action) {
    'Status' {
        Show-Status
    }
    'Verify' {
        Assert-NoForeignNativeOwner
        $spawner = Get-ExactSpawnerPid
        if ($null -eq $spawner) { throw 'Exact root hyos_spawner is unavailable.' }
        $launchers = @(Get-LauncherProcesses)
        $launcher = @($launchers | Where-Object {
            $_.ParentPid -eq $spawner
        })
        if ($launcher.Count -ne 1 -or $launchers.Count -ne 1) {
            throw 'Current Launcher is not one clean child of the exact spawner.'
        }
        $path = Get-InstalledApkPath
        if ($null -eq $path) { throw 'The module APK is not installed.' }
        $identity = Get-RemoteApkIdentity -Path $path
        Assert-LauncherMapsCurrentApk -LauncherPid $launcher[0].Pid `
            -ApkIdentity $identity | Out-Null
        Wait-NativeReadyStatus -LauncherPid $launcher[0].Pid
        "apk_sha256=$($identity.Sha256)"
        "hyos_pid=$spawner"
        "launcher_pid=$($launcher[0].Pid)"
    }
    'Capture' {
        "evidence=$(Write-Evidence -Phase 'manual-capture')"
    }
    'Deploy' {
        Assert-NoForeignNativeOwner
        $deploymentApk = Resolve-DeploymentApk
        Assert-LocalApk -Path $deploymentApk
        $localHash = (Get-FileHash -Algorithm SHA256 `
            -LiteralPath $deploymentApk).Hash.ToLowerInvariant()
        $systemUiBefore = Get-SystemUiPid
        if ($null -eq $systemUiBefore) { throw 'SystemUI is unavailable.' }
        $spawnerBefore = Get-ExactSpawnerPid
        if ($null -eq $spawnerBefore) { throw 'Exact root hyos_spawner is unavailable.' }
        $launchersBefore = @((Get-LauncherProcesses) |
            ForEach-Object { $_.Pid })
        $tombstoneBefore = Get-LatestTombstone
        $lsposedLogBefore = Get-LsposedLogSnapshot
        if ([string]::IsNullOrWhiteSpace($lsposedLogBefore.Path)) {
            throw 'LSPosed module log is unavailable; API-102 hot reload cannot be proven.'
        }
        $installedBeforePath = Get-InstalledApkPath
        if ($null -eq $installedBeforePath) {
            throw 'This guarded update path requires an already installed module APK.'
        }
        $rollbackApk = Join-Path ([System.IO.Path]::GetTempPath()) `
            "miui-back-gesture-hook-rollback-$([guid]::NewGuid().ToString('N')).apk"
        $rollbackHash = ''
        $mutationStarted = $false
        try {
            if ($null -ne $installedBeforePath) {
                $installedBefore = Get-RemoteApkIdentity -Path $installedBeforePath
                $rollbackHash = $installedBefore.Sha256
                Invoke-Adb -Arguments @(
                    'pull', $installedBeforePath, $rollbackApk) | Out-Null
                if ((Get-FileHash -Algorithm SHA256 `
                        -LiteralPath $rollbackApk).Hash.ToLowerInvariant() -ne
                        $rollbackHash) {
                    throw 'Pulled rollback APK hash does not match the installed package.'
                }
            }

            Install-Apk -Path $deploymentApk -EnableRollback | Out-Null
            $mutationStarted = $true
            $installedPath = Get-InstalledApkPath
            if ($null -eq $installedPath) {
                throw 'PackageManager did not publish one active base APK after installation.'
            }
            $installed = Get-RemoteApkIdentity -Path $installedPath
            if ($installed.Sha256 -ne $localHash) {
                throw "Installed APK hash mismatch: $($installed.Sha256)"
            }
            Wait-Api102HotReload -ExpectedSystemUiPid $systemUiBefore `
                -LogBefore $lsposedLogBefore | Out-Null

            $spawnerNow = Get-ExactSpawnerPid
            if ($spawnerNow -ne $spawnerBefore) {
                throw 'hyos_spawner changed unexpectedly before the controlled replacement.'
            }
            $spawnerAfter = Stop-ExactSpawner -OldPid $spawnerBefore
            $launcherAfter = Start-AndWaitLauncher -ExpectedParent $spawnerAfter `
                -RejectedPids $launchersBefore
            Assert-LauncherMapsCurrentApk -LauncherPid $launcherAfter.Pid `
                -ApkIdentity $installed -RejectedInodes @($installedBefore.Inode) |
                Out-Null
            Wait-NativeReadyStatus -LauncherPid $launcherAfter.Pid | Out-Null
            Start-Sleep -Milliseconds 1500
            $tombstoneAfter = Get-LatestTombstone
            if ($tombstoneAfter -ne $tombstoneBefore) {
                throw "A new tombstone appeared during activation: $tombstoneAfter"
            }
            "apk_sha256=$localHash"
            "apk_path=$installedPath"
            "hyos_old_pid=$spawnerBefore"
            "hyos_new_pid=$spawnerAfter"
            "launcher_pid=$($launcherAfter.Pid)"
            "launcher_parent=$($launcherAfter.ParentPid)"
            'api102_hot_reload=verified'
            'native_generation=current-apk'
            "evidence=$(Write-Evidence -Phase 'lsposed-native-activated' `
                -Extra "apk_sha256=$localHash")"
        } catch {
            $failure = $_.Exception.Message
            $rollback = if ($mutationStarted -and
                    -not [string]::IsNullOrWhiteSpace($rollbackHash)) {
                Invoke-Rollback -RollbackApk $rollbackApk `
                    -ExpectedHash $rollbackHash
            } else {
                'rollback=not-attempted'
            }
            $evidence = Write-Evidence -Phase 'lsposed-native-failure' `
                -Extra "failure=$failure$Nl$rollback"
            throw "$failure$Nl$rollback$Nl evidence=$evidence"
        } finally {
            if (Test-Path -LiteralPath $rollbackApk -PathType Leaf) {
                Remove-Item -LiteralPath $rollbackApk -Force
            }
        }
    }
}
