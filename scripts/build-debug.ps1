param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @()
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$javaHome = "D:\Dev\Environment\Java\jdk17"
$androidSdk = "D:\Dev\Environment\Android\SDK"

if (-not (Test-Path (Join-Path $javaHome "bin\java.exe"))) {
    throw "JDK 17 was not found: $javaHome"
}
if (-not (Test-Path (Join-Path $androidSdk "platforms\android-37.0\android.jar"))) {
    throw "Android SDK Platform 37.0 was not found: $androidSdk"
}

$env:JAVA_HOME = $javaHome
$env:ANDROID_HOME = $androidSdk
$env:ANDROID_SDK_ROOT = $androidSdk

Push-Location $projectRoot
try {
    & .\gradlew.bat :app:assembleDebug --no-daemon @GradleArguments
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}
