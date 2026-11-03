param(
    [string]$Version = "2.4.3",
    [int]$VersionCode = 71,
    [string]$SdkRoot = "",
    [string]$JdkHome = "",
    [string]$StorePass = $env:HP_STORE_PASS,
    [string]$KeystorePath = ""
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent $MyInvocation.MyCommand.Path
$Main = Join-Path $Root "app\src\main"
$Build = Join-Path $Root "build"
$Dist = Join-Path $Root "dist"
if (-not $KeystorePath) { $KeystorePath = Join-Path $Root "keystore\harness-phone.jks" }
$Keystore = $KeystorePath
$AarClasses = Join-Path $Root "libs\aar\classes.jar"
$AarJni = Join-Path $Root "libs\aar\jni"
$KeyAlias = "harness_phone"

if (-not $SdkRoot) {
    $candidates = @()
    if ($env:ANDROID_SDK_ROOT) { $candidates += $env:ANDROID_SDK_ROOT }
    if ($env:ANDROID_HOME) { $candidates += $env:ANDROID_HOME }
    $candidates += "F:\documents\HARNESS-Code\_android-toolchain\sdk"
    $candidates += (Join-Path $env:LOCALAPPDATA "Android\Sdk")
    $candidates += "C:\Android\Sdk"
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "platforms\android-34\android.jar"))) {
            $SdkRoot = $candidate
            break
        }
    }
}
if (-not $SdkRoot) { throw "Android SDK (platforms;android-34) not found. Pass -SdkRoot." }

if (-not $JdkHome) {
    $candidates = @()
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    $candidates += "F:\documents\HARNESS-Code\_android-toolchain\jdk17"
    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path (Join-Path $candidate "bin\javac.exe"))) {
            $JdkHome = $candidate
            break
        }
    }
}
if (-not $JdkHome) { throw "JDK 17 not found. Pass -JdkHome." }

$BuildTools = Join-Path $SdkRoot "build-tools\34.0.0"
$AndroidJar = Join-Path $SdkRoot "platforms\android-34\android.jar"
$Aapt2 = Join-Path $BuildTools "aapt2.exe"
$D8 = Join-Path $BuildTools "d8.bat"
$ZipAlign = Join-Path $BuildTools "zipalign.exe"
$ApkSigner = Join-Path $BuildTools "apksigner.bat"
$Javac = Join-Path $JdkHome "bin\javac.exe"
$KeyTool = Join-Path $JdkHome "bin\keytool.exe"

# The tsnet binding is a build artifact and is not committed; explode it from the
# aar the user dropped into libs/.
$AarFile = Join-Path $Root "libs\tsnetmobile.aar"
if (-not (Test-Path $AarClasses) -and (Test-Path $AarFile)) {
    $AarDir = Join-Path $Build "aar"
    Remove-Item $AarDir -Recurse -Force -ErrorAction SilentlyContinue
    New-Item -ItemType Directory -Force -Path $AarDir | Out-Null
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($AarFile, $AarDir)
    $AarClasses = Join-Path $AarDir "classes.jar"
    $AarJni = Join-Path $AarDir "jni"
}

foreach ($tool in @($Aapt2, $D8, $ZipAlign, $ApkSigner, $Javac, $KeyTool, $AndroidJar, $AarClasses)) {
    if (-not (Test-Path $tool)) { throw "Missing build tool: $tool" }
}

$env:JAVA_HOME = $JdkHome
$env:Path = "$JdkHome\bin;$BuildTools;$env:Path"

New-Item -ItemType Directory -Force -Path $Build, $Dist | Out-Null
Remove-Item (Join-Path $Build "res.zip") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $Build "unsigned.apk") -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $Build "classes") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $Build "dex") -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $Build "gen") -Recurse -Force -ErrorAction SilentlyContinue

Write-Host "[1/7] Compile resources"
& $Aapt2 compile --dir (Join-Path $Main "res") -o (Join-Path $Build "res.zip")
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "[2/7] Link resources"
& $Aapt2 link -o (Join-Path $Build "unsigned.apk") -I $AndroidJar `
    --manifest (Join-Path $Main "AndroidManifest.xml") `
    --java (Join-Path $Build "gen") `
    --min-sdk-version 26 --target-sdk-version 34 `
    --version-code $VersionCode --version-name $Version `
    -R (Join-Path $Build "res.zip") --auto-add-overlay
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "[3/7] Compile Java"
$Classes = Join-Path $Build "classes"
New-Item -ItemType Directory -Force -Path $Classes | Out-Null
$Sources = @()
$Sources += Get-ChildItem (Join-Path $Main "java") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$Sources += Get-ChildItem (Join-Path $Build "gen") -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
$SourceFile = Join-Path $Build "sources.txt"
$Sources | Set-Content -LiteralPath $SourceFile -Encoding ASCII
& $Javac -encoding UTF-8 -source 1.8 -target 1.8 -Xlint:-options -Xlint:-deprecation -classpath "$AndroidJar;$AarClasses" -d $Classes "@$SourceFile"
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "[4/7] Generate DEX"
$DexDir = Join-Path $Build "dex"
New-Item -ItemType Directory -Force -Path $DexDir | Out-Null
Push-Location $Classes
& (Join-Path $JdkHome "bin\jar.exe") xf $AarClasses
Pop-Location
$ClassFile = Join-Path $Build "classes.txt"
Get-ChildItem $Classes -Recurse -Filter "*.class" | ForEach-Object { $_.FullName } | Set-Content -LiteralPath $ClassFile -Encoding ASCII
& $D8 --lib $AndroidJar --min-api 26 --output $DexDir "@$ClassFile"
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "[5/7] Package APK"
Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$Unsigned = Join-Path $Build "unsigned.apk"
$WithDex = Join-Path $Build "withdex.apk"
Copy-Item $Unsigned $WithDex -Force
$Zip = [System.IO.Compression.ZipFile]::Open($WithDex, [System.IO.Compression.ZipArchiveMode]::Update)
$Existing = $Zip.GetEntry("classes.dex")
if ($Existing) { $Existing.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $Zip,
    (Join-Path $DexDir "classes.dex"),
    "classes.dex",
    [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
foreach ($abi in @("arm64-v8a", "x86_64")) {
    $so = Join-Path (Join-Path $AarJni $abi) "libgojni.so"
    if (Test-Path $so) {
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $Zip,
            $so,
            "lib/$abi/libgojni.so",
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
}
$AssetsDir = Join-Path $Main "assets"
if (Test-Path -LiteralPath $AssetsDir) {
    Get-ChildItem -LiteralPath $AssetsDir -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($AssetsDir.Length).TrimStart('\', '/').Replace('\', '/')
        $entryName = "assets/$relative"
        $old = $Zip.GetEntry($entryName)
        if ($old) { $old.Delete() }
        [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
            $Zip,
            $_.FullName,
            $entryName,
            [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
    }
}
# BSD-3-Clause / Apache-2.0 both require the notices to travel with the binary,
# so the repo-root notices file is packed into the APK itself as an asset.
$NoticesSrc = Join-Path (Split-Path -Parent $Root) "THIRD-PARTY-NOTICES.md"
if (Test-Path -LiteralPath $NoticesSrc) {
    $entryName = "assets/licenses/THIRD-PARTY-NOTICES.txt"
    $old = $Zip.GetEntry($entryName)
    if ($old) { $old.Delete() }
    [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
        $Zip,
        $NoticesSrc,
        $entryName,
        [System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
} else {
    Write-Warning "THIRD-PARTY-NOTICES.md not found; the APK will ship without license notices."
}
$Zip.Dispose()

$Aligned = Join-Path $Build "aligned.apk"
& $ZipAlign -f 4 $WithDex $Aligned
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "[6/7] Sign APK"
$FinalName = "DSH-Phone-Bridge-" + $Version
if (-not (Test-Path $Keystore) -or -not $StorePass) {
    # No signing key on this machine (the key never lives in the repository), so
    # build an unsigned APK that can still be installed for testing.
    $UnsignedFinal = Join-Path $Dist ($FinalName + "-unsigned.apk")
    Copy-Item -LiteralPath $Aligned -Destination $UnsignedFinal -Force
    Write-Host ""
    Write-Host "APK : $UnsignedFinal (unsigned)"
    Write-Host "Set HP_STORE_PASS and -KeystorePath to sign a release build."
    exit 0
}

$Final = Join-Path $Dist ($FinalName + ".apk")
$SignedTemp = Join-Path $Dist "dsh-phone-bridge-signed.apk"
& $ApkSigner sign --ks $Keystore --ks-key-alias $KeyAlias `
    --ks-pass ("pass:" + $StorePass) --key-pass ("pass:" + $StorePass) `
    --out $SignedTemp $Aligned
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }
Move-Item -LiteralPath $SignedTemp -Destination $Final -Force
$VerifyOutput = & $ApkSigner verify --verbose $Final 2>&1
$VerifyExit = $LASTEXITCODE
$VerifyOutput | Select-Object -First 5
if ($VerifyExit -ne 0) { throw "signature verification failed" }

Write-Host "[7/7] Checksum"
$Hash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Final).Hash
$HashFile = Join-Path $Dist ($FinalName + "-SHA256.txt")
"$Hash  $(Split-Path -Leaf $Final)" | Set-Content -LiteralPath $HashFile -Encoding UTF8

Write-Host ""
Write-Host "APK : $Final"
Write-Host "SIZE: $([math]::Round((Get-Item $Final).Length / 1MB, 2)) MB"
Write-Host "SHA : $Hash"
exit 0
