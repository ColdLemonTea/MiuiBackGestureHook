[CmdletBinding()]
param(
    [string]$NdkPath = 'D:\env\AndroidSDK\ndk\30.0.14904198',
    [string]$CMakePath = 'D:\env\AndroidSDK\cmake\3.22.1\bin\cmake.exe',
    [ValidateSet('Debug', 'Release', 'RelWithDebInfo')]
    [string]$Configuration = 'Release'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ($PSVersionTable.PSEdition -ne 'Core' -or
        $PSVersionTable.PSVersion.Major -lt 7) {
    throw 'PowerShell 7 or newer is required. Run this script with pwsh.'
}

$Builder = Join-Path $PSScriptRoot 'build_zn_package.py'
if (-not (Test-Path -LiteralPath $Builder -PathType Leaf)) {
    throw "Canonical ZN builder does not exist: $Builder"
}
& (Join-Path $PSScriptRoot 'verify-hsctl.ps1') | Out-Host
if ($LASTEXITCODE -ne 0) {
    throw "ZN controller verification failed: $LASTEXITCODE"
}
$Python = (Get-Command python -ErrorAction Stop).Source
$Arguments = @(
    $Builder,
    '--configuration', $Configuration,
    '--ndk-path', $NdkPath,
    '--cmake-path', $CMakePath
)

& $Python @Arguments
if ($LASTEXITCODE -ne 0) {
    throw "Canonical ZN builder failed: $LASTEXITCODE"
}
