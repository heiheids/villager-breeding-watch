# 把三份 txt 文档转换成 docs/ 下的 Markdown。
#
# 原文档是等宽纯文本，用 ascii 下划线（==== / ----）和制表线（────）表示标题层级。
# 手工搬运容易破坏缩进与代码块，所以用脚本按规则转换，保证内容零丢失。
#
# 规则：
#   TITLE\n====...        -> # TITLE
#   HEADING\n----...      -> ## HEADING
#   ────\nHEADING\n────    -> ## HEADING（制表线包裹式）
#   其余行原样保留（含 ``` 代码块、缩进）
#
# 用法： powershell -ExecutionPolicy Bypass -File tools/txt2md.ps1
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$docsDir = Join-Path $Root 'docs'
New-Item -ItemType Directory -Force -Path $docsDir | Out-Null

# 源文件 -> 目标文件名
$map = @(
    @{ Src = '使用说明.txt';                 Dst = 'usage.md';     Title = '使用说明' },
    @{ Src = '技术说明.txt';                 Dst = 'technical.md'; Title = '技术说明' },
    @{ Src = '村民生育管理器-经验与踩坑.txt'; Dst = 'lessons.md';   Title = '经验与踩坑' }
)

function Test-Sep([string]$s, [char]$ch) {
    if ($s.Length -lt 4) { return $false }
    foreach ($c in $s.ToCharArray()) { if ($c -ne $ch) { return $false } }
    return $true
}

function Test-AnySep([string]$s) {
    return ((Test-Sep $s '=') -or (Test-Sep $s '-') -or (Test-Sep $s '─'))
}

foreach ($m in $map) {
    $srcPath = Join-Path $Root $m.Src
    if (-not (Test-Path -LiteralPath $srcPath)) {
        Write-Host "  [SKIP] 不存在：$($m.Src)"
        continue
    }

    $lines = [System.IO.File]::ReadAllLines($srcPath, [System.Text.Encoding]::UTF8)
    $out = [System.Collections.Generic.List[string]]::new()
    $inFence = $false
    $i = 0

    while ($i -lt $lines.Count) {
        $line = $lines[$i]

        # 代码块内一律原样保留
        if ($line -match '^\s*```') {
            $inFence = -not $inFence
            $out.Add($line)
            $i++
            continue
        }
        if ($inFence) {
            $out.Add($line)
            $i++
            continue
        }

        # 制表线包裹：──── / HEADING / ────
        if ((Test-Sep $line '─') -and ($i + 2 -lt $lines.Count) -and
            -not (Test-AnySep $lines[$i + 1]) -and ($lines[$i + 1].Trim() -ne '') -and
            (Test-Sep $lines[$i + 2] '─')) {
            $heading = $lines[$i + 1].Trim()
            $out.Add('## ' + $heading)
            $out.Add('')
            $i += 3
            continue
        }

        # 孤立制表线：丢弃（避免在 Markdown 里变成奇怪字符）
        if (Test-Sep $line '─') {
            $i++
            continue
        }

        # TITLE / ====
        if (($i + 1 -lt $lines.Count) -and (Test-Sep $lines[$i + 1] '=') -and ($line.Trim() -ne '')) {
            $out.Add('# ' + $line.Trim())
            $i += 2
            continue
        }

        # HEADING / ----
        if (($i + 1 -lt $lines.Count) -and (Test-Sep $lines[$i + 1] '-') -and ($line.Trim() -ne '')) {
            $out.Add('## ' + $line.Trim())
            $i += 2
            continue
        }

        # 孤立 ==== / ---- 行：丢弃
        if ((Test-Sep $line '=') -or (Test-Sep $line '-')) {
            $i++
            continue
        }

        $out.Add($line)
        $i++
    }

    # 收敛连续空行（最多 2 个）
    $final = [System.Collections.Generic.List[string]]::new()
    $blank = 0
    foreach ($l in $out) {
        if ($l.Trim() -eq '') {
            $blank++
            if ($blank -le 2) { $final.Add('') }
        } else {
            $blank = 0
            $final.Add($l)
        }
    }

    $dstPath = Join-Path $docsDir $m.Dst
    $text = ($final -join "`n").TrimEnd() + "`n"
    [System.IO.File]::WriteAllText($dstPath, $text, (New-Object System.Text.UTF8Encoding($false)))

    $h1 = ($final | Where-Object { $_ -match '^# ' }).Count
    $h2 = ($final | Where-Object { $_ -match '^## ' }).Count
    Write-Host ("  [OK] {0} -> docs/{1}  ({2} 行, {3} 个一级标题, {4} 个二级标题)" -f `
        $m.Src, $m.Dst, $final.Count, $h1, $h2)
}

Write-Host ''
Write-Host '转换完成。'
