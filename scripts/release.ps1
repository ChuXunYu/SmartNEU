param(
    [string]$Repo = "aizhangz/SmartNEU",
    [string]$HostName = "git.aizhangz.shop",
    [string]$Token = $env:FORGEJO_TOKEN,
    [switch]$SkipPush
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $root

if (git status --porcelain) {
    throw "工作区不干净，请先提交或清理后再发布。"
}

$manifest = Get-Content "neu-lite-android/AndroidManifest.xml" -Raw
$version = [regex]::Match($manifest, 'android:versionName="([^"]+)"').Groups[1].Value
$versionCode = [regex]::Match($manifest, 'android:versionCode="([^"]+)"').Groups[1].Value
if (-not $version) { throw "无法读取 versionName" }
$tag = "v$version"

if (git tag -l $tag) { throw "标签 $tag 已存在，不能重复发布。" }

$changelog = Get-Content "CHANGELOG.md" -Raw
$escaped = [regex]::Escape($version)
$m = [regex]::Match($changelog, "(?ms)^## v?$escaped[^\r\n]*\r?\n(.*?)(?=^## |\z)")
if (-not $m.Success) { throw "CHANGELOG.md 中没有 $version 的发布说明。" }
$changes = $m.Groups[1].Value.Trim()

Write-Host "== build =="
Push-Location "neu-lite-android"
try {
    & pwsh -NoProfile -ExecutionPolicy Bypass -File .\build.ps1
    if ($LASTEXITCODE -ne 0) { throw "APK 构建失败" }
} finally {
    Pop-Location
}

$apk = Join-Path $root "neu-lite-android/日在NEU.apk"
if (-not (Test-Path -LiteralPath $apk)) { throw "找不到 APK: $apk" }
$hash = (Get-FileHash -LiteralPath $apk -Algorithm SHA256).Hash
$assetName = "日在NEU-v$version.apk"

$notes = @"
# 日在NEU v$version

$changes

## 构建信息

- versionCode: $versionCode
- 文件: ``$assetName``
- SHA-256: ``$hash``

## 下载

见本 Release 的 Assets。
"@

if (-not $Token) {
    $cred = "protocol=https`nhost=$HostName`n`n" | git credential fill 2>$null
    $Token = ($cred | Where-Object { $_ -match '^password=' } | Select-Object -First 1) -replace '^password=', ''
}
if (-not $Token) { throw "缺少 Forgejo token：设置 FORGEJO_TOKEN，或先让 Git Credential Manager 保存凭据。" }

Write-Host "== tag =="
git tag -a $tag -m "日在NEU $tag"
if (-not $SkipPush) {
    git push origin main
    git push origin $tag
    if ((git remote get-url github 2>$null)) {
        git push github main
        git push github $tag
    }
}

Write-Host "== release =="
$headers = @{
    Authorization = "token $Token"
    Accept = "application/json"
    "User-Agent" = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) neu-lite-release"
}
$api = "https://$HostName/api/v1/repos/$Repo"
$payload = @{
    tag_name = $tag
    name = "日在NEU $version"
    body = $notes
    draft = $false
    prerelease = $false
} | ConvertTo-Json -Depth 5

$release = Invoke-RestMethod -Uri "$api/releases" -Method Post -Headers $headers `
    -ContentType "application/json" -Body $payload

Write-Host "== upload =="
$upload = "$api/releases/$($release.id)/assets?name=$([uri]::EscapeDataString($assetName))"
$asset = Invoke-RestMethod -Uri $upload -Method Post -Headers $headers -Form @{
    attachment = Get-Item -LiteralPath $apk
}

Write-Host ""
Write-Host "Release: $($release.html_url)"
Write-Host "Asset:   $($asset.browser_download_url)"
Write-Host "APK:     $apk"
Write-Host "SHA256:  $hash"
