$ErrorActionPreference = "Stop"

# Auto-detect JDK (Gradle 9 needs 17+ to run, the project toolchain is 21: pick the newest JDK available
# so Gradle never has to download a toolchain from foojay during an offline/blocked build)
function Get-JdkMajorVersion([string]$homePath) {
    $releaseFile = Join-Path $homePath "release"
    if (Test-Path $releaseFile) {
        $m = Select-String -Path $releaseFile -Pattern '^JAVA_VERSION="([0-9]+)' | Select-Object -First 1
        if ($m -and $m.Matches.Count -gt 0) { return [int]$m.Matches[0].Groups[1].Value }
    }
    return 0
}

$jdkCandidates = @()
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    $jdkCandidates += Get-Item $env:JAVA_HOME
}
foreach ($jbr in @(
    "$env:LOCALAPPDATA\Programs\Android Studio\jbr",
    "C:\Program Files\Android\Android Studio\jbr",
    "D:\Android\Android Studio\jbr",
    "$env:ProgramFiles\Android\Android Studio\jbr"
)) {
    if ($jbr -and (Test-Path (Join-Path $jbr "bin\javac.exe"))) {
        $jdkCandidates += Get-Item $jbr
    }
}
$jdkCandidates += @(
    Get-ChildItem -Path "$env:USERPROFILE\.gradle\jdks\*" -ErrorAction SilentlyContinue
    Get-ChildItem -Path "C:\Program Files\Eclipse Adoptium\jdk-*" -ErrorAction SilentlyContinue
    Get-ChildItem -Path "C:\Program Files\Microsoft\jdk-*" -ErrorAction SilentlyContinue
    Get-ChildItem -Path "C:\Program Files\Java\jdk-*" -ErrorAction SilentlyContinue
)
$jdkCandidates = $jdkCandidates |
    Where-Object { $_ -and (Test-Path (Join-Path $_.FullName "bin\javac.exe")) } |
    Sort-Object { Get-JdkMajorVersion $_.FullName } -Descending
if ($jdkCandidates.Count -gt 0) {
    $env:JAVA_HOME = $jdkCandidates[0].FullName
}
if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\javac.exe"))) {
    throw "JDK not found. Please install JDK 17+ (JDK 21 recommended) and set JAVA_HOME."
}

# Auto-detect Android SDK
$SDK = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA "Android\Sdk")) |
    Where-Object { $_ -and (Test-Path $_) } | Select-Object -First 1
if (-not $SDK) {
    throw "Android SDK not found. Please set ANDROID_HOME or ANDROID_SDK_ROOT."
}
$env:ANDROID_HOME = $SDK
$env:ANDROID_SDK_ROOT = $SDK

$BT = Get-ChildItem -Path (Join-Path $SDK "build-tools") -Directory -ErrorAction SilentlyContinue |
    Sort-Object { [version]($_.Name -replace '-.*$', '') } -Descending |
    Select-Object -First 1 -ExpandProperty FullName
if (-not $BT -or -not (Test-Path (Join-Path $BT "d8.bat"))) {
    throw "Android SDK build-tools (with d8.bat) not found under $SDK\build-tools"
}

$ANDROID_JAR = Get-ChildItem -Path (Join-Path $SDK "platforms") -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^android-(\d+)' -and [int]$Matches[1] -ge 30 } |
    Sort-Object { [double]($_.Name -replace '^android-', '' -replace '-.*$', '') } -Descending |
    ForEach-Object { Join-Path $_.FullName "android.jar" } |
    Where-Object { Test-Path $_ } |
    Select-Object -First 1
if (-not $ANDROID_JAR) {
    throw "Android SDK platform (API 30+) android.jar not found under $SDK\platforms"
}

$env:PATH = "$($env:JAVA_HOME)\bin;$BT;$env:PATH"
Write-Host "Using JDK: $env:JAVA_HOME"
Write-Host "Using Build-Tools: $BT"
Write-Host "Using android.jar: $ANDROID_JAR"

$ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$BUILD = Join-Path $ROOT "build"
New-Item -ItemType Directory -Force "$BUILD\bridge_classes" | Out-Null
New-Item -ItemType Directory -Force "$BUILD\out" | Out-Null
Remove-Item -Recurse -Force "$BUILD\bridge_classes\*" -ErrorAction SilentlyContinue

Write-Host "=== 1. Compiling ExtBridge daemon -> extbridge.jar ==="
& javac -encoding UTF-8 --release 11 -cp $ANDROID_JAR -d "$BUILD\bridge_classes" "$ROOT\bridge\src\com\extbridge\daemon\ExtBridge.java"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$bridgeClasses = Get-ChildItem -Recurse -Filter "*.class" "$BUILD\bridge_classes" | ForEach-Object { $_.FullName }
& "$BT\d8.bat" --min-api 30 --output "$BUILD\out\extbridge.jar" @bridgeClasses
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# Normalize extbridge.jar ZIP headers (clear streaming Data Descriptor bit 0x0008 and pin deterministic timestamp)
if (Get-Command python -ErrorAction SilentlyContinue) {
    python -c "import sys, zipfile; p=sys.argv[1]; z=zipfile.ZipFile(p,'r'); items=[(i.filename,z.read(i.filename)) for i in z.infolist()]; z.close(); out=zipfile.ZipFile(p,'w'); [out.writestr(zipfile.ZipInfo(k,(2026,1,1,0,0,0)),v,compress_type=zipfile.ZIP_DEFLATED) for k,v in items]; out.close()" "$BUILD\out\extbridge.jar"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$genAssetsDir = Join-Path $ROOT "helper\assets"
New-Item -ItemType Directory -Force $genAssetsDir | Out-Null
Copy-Item -Path "$BUILD\out\extbridge.jar" -Destination "$genAssetsDir\extbridge.jar" -Force

# Write local.properties for Gradle Android SDK discovery (preserve existing keystore.* overrides)
$localPropsPath = Join-Path $ROOT "local.properties"
$keptLines = @()
if (Test-Path $localPropsPath) {
    $keptLines = Get-Content $localPropsPath | Where-Object { $_ -match '^keystore\.' }
}
$sdkEscaped = $SDK -replace '\\', '/'
$propsContent = @("sdk.dir=$sdkEscaped") + $keptLines
# Write without BOM so Gradle's java.util.Properties never sees a malformed first key
[System.IO.File]::WriteAllLines($localPropsPath, $propsContent, (New-Object System.Text.UTF8Encoding($false)))

Write-Host "=== 2. Building Helper APK with Gradle + Jetpack Compose + Miuix ==="
Push-Location $ROOT
try {
    $gradleArgs = @(":helper:assembleRelease", "--console=plain")
    if ($env:CI -eq "true") {
        $gradleArgs += "--no-daemon"
    }
    if ($env:EXTBRIDGE_ALLOW_UNSIGNED_RELEASE -eq "1") {
        $gradleArgs += "-PallowUnsignedRelease=true"
    }
    & "$ROOT\gradlew.bat" @gradleArgs
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
} finally {
    Pop-Location
}

$EXPECTED_CERT_SHA256 = "e4f67b3012a16633006c64bfc9d5a95c7bdda10826bd729777c2a3066f49117c"
# A developer-provided local keystore (hzgndz.keystore or local.properties keystore.file) has its own
# certificate, so the official-fingerprint gate only applies to the official key material.
$localPropsFile = Join-Path $ROOT "local.properties"
$hasCustomLocalKeystore = (Test-Path (Join-Path $ROOT "hzgndz.keystore")) -or (
    (Test-Path $localPropsFile) -and (Select-String -Path $localPropsFile -Pattern '^keystore\.file=' -Quiet)
)
$requireReleaseKey = ($env:EXTBRIDGE_REQUIRE_RELEASE_KEY -eq "1") -or ($env:GITHUB_REF -like "refs/tags/v*")
Remove-Item -Force "$BUILD\out\exthelper.apk", "$BUILD\out\exthelper-unsigned.apk" -ErrorAction SilentlyContinue

$signedApk = Get-ChildItem -Path "$ROOT\helper\build\outputs\apk\release" -Filter "*-release.apk" -ErrorAction SilentlyContinue | Select-Object -First 1
$unsignedApk = Get-ChildItem -Path "$ROOT\helper\build\outputs\apk\release" -Filter "*-unsigned.apk" -ErrorAction SilentlyContinue | Select-Object -First 1

if ($signedApk) {
    $apksigner = Join-Path $BT "apksigner.bat"
    if (Test-Path $apksigner) {
        $certOut = & $apksigner verify --print-certs $signedApk.FullName 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0) {
            throw "APK signature verification failed:`n$certOut"
        }
        if (($env:ALLOW_CUSTOM_RELEASE_CERT -ne "1") -and (-not $hasCustomLocalKeystore) -and ($certOut -notmatch $EXPECTED_CERT_SHA256)) {
            throw "APK signer SHA-256 does not match official release certificate ($EXPECTED_CERT_SHA256):`n$certOut"
        }
    }
    Copy-Item -Path $signedApk.FullName -Destination "$BUILD\out\exthelper.apk" -Force
} elseif ($unsignedApk) {
    if ($requireReleaseKey) {
        throw "Official release build requires release signing key, but only unsigned APK was produced."
    }
    Write-Warning "No release keystore configured; produced unsigned build\out\exthelper-unsigned.apk (for verification only)."
    Copy-Item -Path $unsignedApk.FullName -Destination "$BUILD\out\exthelper-unsigned.apk" -Force
} else {
    throw "Gradle assembleRelease succeeded, but no APK found in helper\build\outputs\apk\release"
}

Write-Host "=== BUILD COMPLETE ==="
Get-ChildItem "$BUILD\out" | Select-Object Name, Length
