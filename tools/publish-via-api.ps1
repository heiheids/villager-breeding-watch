# 通过 GitHub REST Contents API 上传仓库内容。
#
# 为什么不用 git push：本机到 github.com:443 的 git 传输被阻断（Connection reset /
# timeout），但 gh 的 API 通道是通的（rate limit 正常）。所以改用 Contents API
# 逐文件 PUT —— 对 53 个小文件（约 280 KB）完全够用。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools/publish-via-api.ps1
#   powershell -ExecutionPolicy Bypass -File tools/publish-via-api.ps1 -DryRun
param(
    [string]$Repo = 'heiheids/villager-breeding-watch',
    [string]$Branch = 'main',
    [string]$Message = '村民繁殖观测 v2.0.0 — Minecraft 26.3 Fabric',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$tmp = Join-Path $root '..\_publish_tmp.json'
$tmp = [System.IO.Path]::GetFullPath($tmp)

# 用 -z + 不转义，拿原始 UTF-8 路径（否则中文名会变成 \344\275\277...）
Push-Location $root
try {
    $raw = git -c core.quotepath=false ls-files -z
} finally {
    Pop-Location
}
$files = ($raw -split "`0") | Where-Object { $_ -ne '' }
Write-Host "repo   : $Repo"
Write-Host "branch : $Branch"
Write-Host "files  : $($files.Count)"
if ($DryRun) { Write-Host 'mode   : DRY RUN（不上传）' }
Write-Host ''

$ok = 0
$failed = @()

foreach ($rel in $files) {
    $full = Join-Path $root $rel
    if (-not (Test-Path -LiteralPath $full)) {
        Write-Host "  [SKIP] 本地不存在：$rel"
        continue
    }

    $bytes = [System.IO.File]::ReadAllBytes($full)
    $b64 = [Convert]::ToBase64String($bytes)

    # 已存在则要带 sha 才能更新
    $sha = $null
    $apiPath = "repos/$Repo/contents/$($rel -replace '\\','/')"
    try {
        $existing = gh api "$apiPath`?ref=$Branch" --jq '.sha' 2>$null
        if ($LASTEXITCODE -eq 0 -and $existing) { $sha = $existing.Trim() }
    } catch { }

    $payload = @{
        message = $Message
        content = $b64
        branch  = $Branch
    }
    if ($sha) { $payload.sha = $sha }

    $json = $payload | ConvertTo-Json -Compress
    [System.IO.File]::WriteAllText($tmp, $json, (New-Object System.Text.UTF8Encoding($false)))

    if ($DryRun) {
        $act = if ($sha) { 'update' } else { 'create' }
        Write-Host ("  [DRY]  {0,-8} {1} ({2} bytes)" -f $act, $rel, $bytes.Length)
        $ok++
        continue
    }

    $out = gh api $apiPath --method PUT --input $tmp --jq '.commit.sha' 2>&1
    if ($LASTEXITCODE -eq 0) {
        $ok++
        Write-Host ("  [OK]   {0}" -f $rel)
    } else {
        $failed += $rel
        Write-Host ("  [FAIL] {0} :: {1}" -f $rel, ($out -join ' ')) -ForegroundColor Red
    }
    Start-Sleep -Milliseconds 120      # 轻节流，避免触发 secondary rate limit
}

Remove-Item $tmp -Force -ErrorAction SilentlyContinue

Write-Host ''
Write-Host "uploaded: $ok / $($files.Count)"
if ($failed.Count -gt 0) {
    Write-Host "失败 $($failed.Count) 个：" -ForegroundColor Red
    $failed | ForEach-Object { Write-Host "  $_" }
    exit 1
}
Write-Host 'DONE' -ForegroundColor Green
Write-Host "仓库：https://github.com/$Repo"
