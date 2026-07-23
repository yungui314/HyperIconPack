$ErrorActionPreference = "Stop"

function Resolve-Adb {
    $candidates = @()
    if ($env:ANDROID_HOME) { $candidates += (Join-Path $env:ANDROID_HOME "platform-tools\adb.exe") }
    if ($env:ANDROID_SDK_ROOT) { $candidates += (Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe") }
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbCmd) { $candidates += $adbCmd.Source }
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) { return $candidate }
    }
    throw "adb was not found. Set ANDROID_HOME or install platform-tools on PATH."
}

$adb = Resolve-Adb
$devices = & $adb devices
if ($devices -notmatch "`tdevice") {
    throw "No authorized Android device is connected. Enable USB debugging or pair Wireless debugging, then run adb devices -l."
}

& $adb logcat -c
Write-Host "Waiting for HyperIconPack / LSPosed logs. Change the icon-pack setting or restart System Launcher now."
& $adb logcat -v threadtime | Select-String -Pattern "HyperIconPack|LSPosed|Xposed"
