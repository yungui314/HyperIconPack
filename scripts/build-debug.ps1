param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$GradleArguments = @()
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot

function Resolve-JavaHome {
    if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    $java = Get-Command java -ErrorAction SilentlyContinue
    if ($java) {
        return (Split-Path (Split-Path $java.Source -Parent) -Parent)
    }
    throw "JAVA_HOME is not set and java.exe was not found on PATH."
}

function Resolve-AndroidSdk {
    if ($env:ANDROID_HOME -and (Test-Path $env:ANDROID_HOME)) { return $env:ANDROID_HOME }
    if ($env:ANDROID_SDK_ROOT -and (Test-Path $env:ANDROID_SDK_ROOT)) { return $env:ANDROID_SDK_ROOT }
    $localProps = Join-Path $projectRoot "local.properties"
    if (Test-Path $localProps) {
        $sdkLine = Get-Content $localProps | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -Last 1
        if ($sdkLine) {
            $sdkDir = $sdkLine -replace '^sdk\.dir=', ''
            $sdkDir = $sdkDir -replace '\\', '\'
            if (Test-Path $sdkDir) { return $sdkDir }
        }
    }
    throw "ANDROID_HOME / ANDROID_SDK_ROOT is not set (and local.properties has no sdk.dir)."
}

$env:JAVA_HOME = Resolve-JavaHome
$androidSdk = Resolve-AndroidSdk
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
