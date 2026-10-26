# 生成模组图标 assets/villagerbreedingwatch/textures/icon.png（128x128）
#
# 结构简明：圆角底板 + 一颗心（能繁殖）+ 一条虚线连到小方框（床）。
# 这三个元素正好是程序的三件事：状态、能不能生、村民与床的关系。
# 用 4 倍尺寸绘制再缩小，得到平滑边缘。
Add-Type -AssemblyName System.Drawing

$S = 4
$N = 128
$W = $N * $S

$bmp = New-Object System.Drawing.Bitmap($W, $W, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$g = [System.Drawing.Graphics]::FromImage($bmp)
$g.SmoothingMode = [System.Drawing.Drawing2D.SmoothingMode]::AntiAlias
$g.Clear([System.Drawing.Color]::Transparent)

# ---------- 圆角底板（竖向渐变） ----------
$r = 22 * $S
$path = New-Object System.Drawing.Drawing2D.GraphicsPath
$d = 2 * $r
$path.AddArc(0, 0, $d, $d, 180, 90)
$path.AddArc($W - $d, 0, $d, $d, 270, 90)
$path.AddArc($W - $d, $W - $d, $d, $d, 0, 90)
$path.AddArc(0, $W - $d, $d, $d, 90, 90)
$path.CloseFigure()

$brush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point(0, 0)),
    (New-Object System.Drawing.Point(0, $W)),
    [System.Drawing.Color]::FromArgb(255, 30, 52, 46),
    [System.Drawing.Color]::FromArgb(255, 12, 22, 20))
$g.FillPath($brush, $path)

# 内描边，让底板在深色界面里也有边界
$penEdge = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(60, 140, 220, 170), (1.5 * $S))
$g.DrawPath($penEdge, $path)

# ---------- 心（可繁殖） ----------
function New-HeartPath([double]$cx, [double]$cy, [double]$size) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.FillMode = [System.Drawing.Drawing2D.FillMode]::Winding
    $k = $size / 32.0
    # 左右两个圆 + 下方三角，缩放到 size
    $p.AddEllipse([float]($cx - 16 * $k), [float]($cy - 12 * $k), [float](32 * $k), [float](32 * $k))
    $p.AddPolygon(@(
        (New-Object System.Drawing.PointF([float]($cx - 15.5 * $k), [float]($cy + 2 * $k))),
        (New-Object System.Drawing.PointF([float]($cx + 15.5 * $k), [float]($cy + 2 * $k))),
        (New-Object System.Drawing.PointF([float]$cx, [float]($cy + 20 * $k)))
    ))
    return $p
}

$heartCx = $W * 0.42
$heartCy = $W * 0.40
$heartSize = $W * 0.46
$heart = New-HeartPath $heartCx $heartCy $heartSize
$heartBrush = New-Object System.Drawing.Drawing2D.LinearGradientBrush(
    (New-Object System.Drawing.Point(0, [int]($heartCy - $heartSize / 2))),
    (New-Object System.Drawing.Point(0, [int]($heartCy + $heartSize))),
    [System.Drawing.Color]::FromArgb(255, 124, 227, 139),
    [System.Drawing.Color]::FromArgb(255, 46, 160, 92))
$g.FillPath($heartBrush, $heart)

# ---------- 虚线连到右侧的小方框（床） ----------
$linePen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(230, 64, 204, 230), (3.0 * $S))
$linePen.DashStyle = [System.Drawing.Drawing2D.DashStyle]::Dash
$linePen.DashPattern = @(2.0, 1.6)
$g.DrawLine($linePen,
    [float]($W * 0.62), [float]($W * 0.62),
    [float]($W * 0.76), [float]($W * 0.74))

# 方框
$boxX = $W * 0.72
$boxY = $W * 0.68
$boxW = $W * 0.20
$boxPen = New-Object System.Drawing.Pen([System.Drawing.Color]::FromArgb(255, 240, 210, 120), (3.0 * $S))
$g.DrawRectangle($boxPen, [float]$boxX, [float]$boxY, [float]$boxW, [float]$boxW)

# ---------- 缩小并保存 ----------
$g.Dispose()
$small = New-Object System.Drawing.Bitmap($N, $N, [System.Drawing.Imaging.PixelFormat]::Format32bppArgb)
$sg = [System.Drawing.Graphics]::FromImage($small)
$sg.InterpolationMode = [System.Drawing.Drawing2D.InterpolationMode]::HighQualityBicubic
$sg.PixelOffsetMode = [System.Drawing.Drawing2D.PixelOffsetMode]::HighQuality
$sg.DrawImage($bmp, 0, 0, $N, $N)
$sg.Dispose()
$bmp.Dispose()

$out = Join-Path $PSScriptRoot "..\src\main\resources\assets\villagerbreedingwatch\textures\icon.png"
$out = [System.IO.Path]::GetFullPath($out)
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
$small.Save($out, [System.Drawing.Imaging.ImageFormat]::Png)
$small.Dispose()
Write-Host "written: $out"
