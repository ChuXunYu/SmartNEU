$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$sdk = 'D:\Scoop\apps\android-clt\current'
$bt = Join-Path $sdk 'build-tools\36.0.0'
$androidJar = Join-Path $sdk 'platforms\android-36\android.jar'
$build = Join-Path $root 'build'

Write-Host '== clean =='
if (Test-Path $build) { Remove-Item $build -Recurse -Force }
New-Item -ItemType Directory -Force (Join-Path $build 'flat'), (Join-Path $build 'classes'), (Join-Path $build 'dex') | Out-Null

Write-Host '== aapt2 compile =='
Get-ChildItem (Join-Path $root 'res') -Recurse -File | ForEach-Object {
    & (Join-Path $bt 'aapt2.exe') compile -o (Join-Path $build 'flat') $_.FullName
    if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed: $($_.Name)" }
}

Write-Host '== aapt2 link =='
$flats = Get-ChildItem (Join-Path $build 'flat') -Filter *.flat | ForEach-Object { $_.FullName }
$linkArgs = @('link', '-o', (Join-Path $build 'base.apk'), '-I', $androidJar,
    '--manifest', (Join-Path $root 'AndroidManifest.xml'),
    '-A', (Join-Path $root 'assets'), '--auto-add-overlay')
foreach ($f in $flats) { $linkArgs += @('-R', $f) }
& (Join-Path $bt 'aapt2.exe') @linkArgs
if ($LASTEXITCODE -ne 0) { throw 'aapt2 link failed' }

Write-Host '== javac =='
$srcs = Get-ChildItem (Join-Path $root 'src') -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& javac -encoding UTF-8 -source 11 -target 11 -classpath $androidJar -d (Join-Path $build 'classes') $srcs
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

Write-Host '== d8 =='
$classes = Get-ChildItem (Join-Path $build 'classes') -Recurse -Filter *.class | ForEach-Object { $_.FullName }
& (Join-Path $bt 'd8.bat') --lib $androidJar --min-api 24 --output (Join-Path $build 'dex') $classes
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

Write-Host '== add classes.dex =='
Copy-Item (Join-Path $build 'dex\classes.dex') (Join-Path $build 'classes.dex') -Force
Push-Location $build
& 'D:\Scoop\shims\7z.exe' a -tzip (Join-Path $build 'base.apk') classes.dex | Out-Null
Pop-Location
if ($LASTEXITCODE -ne 0) { throw 'add dex failed' }

Write-Host '== zipalign =='
& (Join-Path $bt 'zipalign.exe') -f 4 (Join-Path $build 'base.apk') (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'zipalign failed' }

Write-Host '== keystore =='
$ks = Join-Path $root 'neu-lite.keystore'
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -alias neulite -keyalg RSA -keysize 2048 `
        -validity 10000 -storepass android -keypass android `
        -dname 'CN=neu-lite, C=CN' 2>$null
    if ($LASTEXITCODE -ne 0) { throw 'keytool failed' }
}

Write-Host '== sign =='
$out = Join-Path $root '日在NEU.apk'
if (Test-Path $out) { Remove-Item $out -Force }
& (Join-Path $bt 'apksigner.bat') sign --ks $ks --ks-key-alias neulite `
    --ks-pass pass:android --key-pass pass:android --out $out (Join-Path $build 'aligned.apk')
if ($LASTEXITCODE -ne 0) { throw 'apksigner failed' }

& (Join-Path $bt 'apksigner.bat') verify --print-certs $out | Select-Object -First 6
Write-Host ("APK: {0} ({1:N1} KB)" -f $out, ((Get-Item $out).Length / 1KB))
