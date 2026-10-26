# 日志自检：判断"一直等待服务器数据"到底是哪一段断了。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\diag-log.ps1
#   powershell -ExecutionPolicy Bypass -File tools\diag-log.ps1 -Log "C:\path\to\latest.log"
#
# 不带 -Log 时会自动找：工程内 run\logs\latest.log，以及常见的 .minecraft 布局。
#
# 它按"链路顺序"检查日志，第一次断在哪一步就报哪一步，避免来回猜。
#
# 注意：Minecraft 的 latest.log 在中文 Windows 上是 **GBK/936** 编码，
# 用 UTF-8 读会把中文变乱码，导致按中文匹配永远失败（踩过）。
param(
    [string]$Log = ''
)

$ErrorActionPreference = 'Stop'

if (-not $Log) {
    $root = Split-Path -Parent $PSScriptRoot
    $candidates = @(
        (Join-Path $root 'run\logs\latest.log')
    )
    # 常见启动器布局：向上两级找 .minecraft
    foreach ($base in @($root, (Split-Path -Parent $root), (Split-Path -Parent (Split-Path -Parent $root)))) {
        $mc = Join-Path $base '.minecraft'
        if (Test-Path $mc) {
            $candidates += (Join-Path $mc 'logs\latest.log')
            # 版本隔离实例
            foreach ($v in (Get-ChildItem (Join-Path $mc 'versions') -Directory -ErrorAction SilentlyContinue)) {
                $candidates += (Join-Path $v.FullName 'logs\latest.log')
            }
        }
    }
    $Log = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $Log -or -not (Test-Path $Log)) {
    Write-Host 'ERROR: 找不到 latest.log。请用 -Log 指定路径。' -ForegroundColor Red
    exit 2
}

Write-Host "log : $Log"
Write-Host "time: $((Get-Item $Log).LastWriteTime)"
Write-Host ''

# 先按 UTF-8 试；不像本模组日志就按 GBK（中文系统上 Minecraft 写的是 GBK）
$bytes = [System.IO.File]::ReadAllBytes($Log)
$utf8 = [System.Text.Encoding]::UTF8.GetString($bytes)
if ($utf8 -match '村民繁殖观测|客户端就绪|扫描=') {
    $text = $utf8
    $enc = 'UTF-8'
} else {
    $text = [System.Text.Encoding]::GetEncoding(936).GetString($bytes)
    $enc = 'GBK/936'
}
Write-Host "enc : $enc"
Write-Host ''

$lines = $text -split "`r?`n"
$vbw = @($lines | Where-Object { $_ -match '\[VBW\]' })

function Count-Of($pattern) {
    @($vbw | Where-Object { $_ -match $pattern }).Count
}

# ---- 链路按顺序检查 ----
$steps = @(
    @{ Name = '模组已加载（公共侧）';  Pattern = '村民繁殖观测已加载' },
    @{ Name = '客户端就绪';            Pattern = '客户端就绪' },
    @{ Name = '客户端发出开关(prefs)'; Pattern = '已发送客户端开关给服务器' },
    @{ Name = '服务端收到开关';         Pattern = '的开关：' },
    @{ Name = '服务端真的扫描了';       Pattern = '扫描=\d+ 名村民' },
    @{ Name = '客户端收到快照';         Pattern = '快照 #\d+' }
)

$firstBroken = $null
Write-Host '=== 链路检查（按顺序）==='
foreach ($s in $steps) {
    $n = Count-Of $s.Pattern
    if ($n -gt 0) {
        Write-Host ("  [OK]   {0}  ({1} 条)" -f $s.Name, $n)
    } else {
        Write-Host ("  [MISS] {0}" -f $s.Name) -ForegroundColor Yellow
        if (-not $firstBroken) { $firstBroken = $s.Name }
    }
}

Write-Host ''
Write-Host '=== 本模组日志的最后 12 条 ==='
$vbw | Select-Object -Last 12 | ForEach-Object { Write-Host ("    " + $_) }

Write-Host ''
Write-Host '=== 异常（本模组相关）==='
# 只认真异常：排除正常日志里本来就有的"失败"字样
# （例如"已载入统计：出生 0 次 / 失败 0 次"是正常输出，曾被这里误判成异常行）
$errs = @($vbw | Where-Object {
    $_ -match 'Exception|Caused by|扫描失败|绘制失败|添加|解析.*失败|读取.*失败|写入.*失败|处理.*失败'
} | Where-Object { $_ -notmatch '已载入统计|已发送|的开关：' })
if ($errs.Count -gt 0) {
    $errs | Select-Object -Last 15 | ForEach-Object { Write-Host ("    " + $_) -ForegroundColor Red }
} else {
    Write-Host '    没有本模组相关的异常行'
}

Write-Host ''
if ($firstBroken) {
    Write-Host "结论：链路第一次断在「$firstBroken」。" -ForegroundColor Yellow
    Write-Host '对照：'
    Write-Host '  - 断在「服务端真的扫描了」：扫描闸门/开关判定有问题'
    Write-Host '      事故1：用 Long.MIN_VALUE 当"从未扫描"哨兵，now-last 溢出成负数，永不扫描'
    Write-Host '      事故2：用维度时钟做闸门，下界/末地是固定时间维度，时钟恒 0，只扫一次'
    Write-Host '  - 断在「客户端收到快照」：包注册/发送失败，或客户端接收器没挂上'
    Write-Host '  - 断在「服务端收到开关」：prefs 没发出去，或玩家没进世界'
    exit 1
} else {
    Write-Host '链路完整。' -ForegroundColor Green
    exit 0
}
