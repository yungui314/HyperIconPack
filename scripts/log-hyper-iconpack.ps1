$ErrorActionPreference = "Stop"
$adb = "D:\Dev\Environment\Android\SDK\platform-tools\adb.exe"

if (-not (Test-Path $adb)) {
    throw "adb was not found: $adb"
}

$devices = & $adb devices
if ($devices -notmatch "`tdevice") {
    throw "No authorized Android device is connected. Enable USB debugging or pair Wireless debugging, then run adb devices -l."
}

& $adb logcat -c
Write-Host "Waiting for HyperIconPack / LSPosed logs. Change the icon-pack setting or restart System Launcher now."
& $adb logcat -v threadtime | Select-String -Pattern "HyperIconPack|LSPosed|Xposed"
