# 源码级防回归检查：扫出"编译能过、运行静默失效"的危险写法。
#
# 这类问题编译器抓不到，只能靠模式扫描 + 可执行断言（见 tools\RegressionTest.java）。
#
# 用法： powershell -ExecutionPolicy Bypass -File tools\check-source.ps1
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$src = Join-Path $Root 'src'
if (-not (Test-Path $src)) { throw "找不到源码目录：$src" }

$fail = 0
function Fail($msg, $hits) {
    Write-Host "  [FAIL] $msg" -ForegroundColor Red
    $hits | Select-Object -First 6 | ForEach-Object { Write-Host "         $_" }
    $script:fail++
}
function Ok($msg) { Write-Host "  [OK]   $msg" }

$files = Get-ChildItem $src -Recurse -Filter *.java

Write-Host "源码检查：$($files.Count) 个 .java"
Write-Host ''

# ---- 1. 禁止用 Long.MIN_VALUE / Integer.MIN_VALUE 当"从未发生"的哨兵参与时间差 ----
$hits = @()
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    # 去掉注释再判断，避免注释里提到也被误报
    $code = ($text -split "`r?`n") | Where-Object { $_ -notmatch '^\s*(//|\*|/\*)' }
    $code = $code -join "`n"
    if ($code -match 'put\([^)]*,\s*Long\.MIN_VALUE\s*\)') {
        $hits += "$($f.Name): 把 Long.MIN_VALUE 存进了 map（会参与 now-last 溢出）"
    }
    if ($code -match '=\s*Long\.MIN_VALUE\s*;') {
        $hits += "$($f.Name): 用 Long.MIN_VALUE 初始化时间戳字段（会参与 now-last 溢出）"
    }
}
if ($hits.Count -gt 0) { Fail '没有把 Long.MIN_VALUE 当"从未发生"的哨兵' $hits }
else { Ok '没有把 Long.MIN_VALUE 当"从未发生"的哨兵' }

# ---- 2. 扫描节拍不得使用维度时钟（下界/末地固定时间，时钟恒 0）----
# 注意：tick() 顶部用维度时钟驱动"存档节拍"是合法的（那是游戏刻，主世界总是有效）。
# 这里只针对 **扫描闸门** 那一处判断：闸门比较的变量与记录的时刻都必须是 tick 计数。
$hits = @()
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    if ($f.Name -eq 'WatchServer.java') {
        if ($text -notmatch 'getTickCount\(\)') {
            $hits += "$($f.Name): tick() 的节拍没有用 server.getTickCount()"
        }
        if ($text -match 'if\s*\(\s*last\s*!=\s*null\s*&&\s*now\s*-\s*last\s*<') {
            $hits += "$($f.Name): 扫描闸门用的是 now（维度时钟），下界恒 0 只会扫一次"
        }
        if ($text -match 'LAST_SCAN\.put\([^)]*,\s*now\s*\)') {
            $hits += "$($f.Name): 扫描时刻记的是 now（维度时钟），应记 tick 计数"
        }
    }
}
if ($hits.Count -gt 0) { Fail '扫描节拍使用 tick 计数而非维度时钟' $hits }
else { Ok '扫描节拍使用 tick 计数而非维度时钟' }

# ---- 3. HUD 的"数据新鲜度"不得用世界时间 ----
$hits = @()
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    if ($f.Name -eq 'HUD.java') {
        if ($text -match 'getDefaultClockTime\(\).*' -and $text -match 'fresh\s*=.*levelTime') {
            $hits += "$($f.Name): fresh 判断仍在用维度时钟（下界永远显示'数据已停止更新'）"
        }
        if ($text -notmatch 'lastSnapshotMillis') {
            $hits += "$($f.Name): fresh 判断没有用 clientHub 的墙钟时间"
        }
    }
}
if ($hits.Count -gt 0) { Fail 'HUD 新鲜度用墙钟而非世界时间' $hits }
else { Ok 'HUD 新鲜度用墙钟而非世界时间' }

# ---- 4. 公共源码集不得引用客户端类 ----
$mainSrc = Join-Path $src 'main\java'
$hits = @()
if (Test-Path $mainSrc) {
    foreach ($f in (Get-ChildItem $mainSrc -Recurse -Filter *.java)) {
        $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
        if ($text -match 'import\s+net\.minecraft\.client\.|import\s+net\.fabricmc\.[\w.]*api\.client\.') {
            $hits += "$($f.Name)"
        }
    }
}
if ($hits.Count -gt 0) { Fail '公共源码集不引用客户端类' $hits }
else { Ok '公共源码集不引用客户端类' }

# ---- 5. 渲染入口必须是 extractRenderState，不能是 render ----
$hits = @()
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    if ($text -match 'public\s+void\s+render\s*\(\s*GuiGraphicsExtractor') {
        $hits += "$($f.Name): 重写了 render(GuiGraphicsExtractor)，26.3 永远不会调用"
    }
}
if ($hits.Count -gt 0) { Fail '界面重写的是 extractRenderState' $hits }
else { Ok '界面重写的是 extractRenderState（不是 render）' }

Write-Host ''
if ($fail -eq 0) { Write-Host 'SOURCE CHECK PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "SOURCE CHECK FAILED ($fail)" -ForegroundColor Red; exit 1 }
