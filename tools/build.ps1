# 构建脚本：自动定位 JDK 25 + 工程内 GRADLE_USER_HOME（不在工程外产生缓存）。
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File tools\build.ps1
#   powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Offline
#   powershell -ExecutionPolicy Bypass -File tools\build.ps1 -Jdk "C:\path\to\jdk-25"
#
# JDK 查找顺序（先命中先用）：
#   1. -Jdk 参数
#   2. 环境变量 JAVA_HOME（且主版本必须是 25）
#   3. .gradle-home\jdks 下由 Gradle toolchain 自动下载的 JDK
#   4. PATH 里的 javac（且主版本必须是 25）
param(
    [switch]$Offline,
    [string]$Jdk = ''
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot

# java -version 把版本写到 stderr。在 $ErrorActionPreference='Stop' 下，
# 用 2>&1 重定向会把 stderr 变成一条 NativeCommandError 终止脚本，
# 所以这里必须临时把策略放宽，否则永远读不到版本号（踩过）。
function Get-JavaMajor([string]$exe) {
    $prev = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $out = (& $exe -version 2>&1 | Out-String)
        if ($out -match 'version "(\d+)') { return [int]$Matches[1] }
        if ($out -match 'openjdk (\d+)') { return [int]$Matches[1] }
    } catch {
    } finally {
        $ErrorActionPreference = $prev
    }
    return 0
}

function Resolve-Jdk25 {
    param([string]$Explicit)

    $candidates = @()
    if ($Explicit) { $candidates += $Explicit }
    if ($env:JAVA_HOME) { $candidates += $env:JAVA_HOME }
    foreach ($d in (Get-ChildItem (Join-Path $root '.gradle-home\jdks') -Directory -ErrorAction SilentlyContinue)) {
        $candidates += $d.FullName
    }
    $onPath = Get-Command javac -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += (Split-Path -Parent (Split-Path -Parent $onPath.Source)) }

    foreach ($c in $candidates) {
        $exe = Join-Path $c 'bin\java.exe'
        if (Test-Path $exe) {
            $major = Get-JavaMajor $exe
            if ($major -ge 25) { return $c }
            Write-Host "  skip $c (Java $major, need >= 25)"
        }
    }
    return ''
}

$jdk = Resolve-Jdk25 -Explicit $Jdk
if (-not $jdk) {
    Write-Host 'ERROR: 找不到 JDK 25。请用 -Jdk 指定，或设置 JAVA_HOME。' -ForegroundColor Red
    Write-Host '       模组要求 java >= 25（见 fabric.mod.json）。'
    exit 2
}

$env:JAVA_HOME = $jdk
$env:GRADLE_USER_HOME = Join-Path $root '.gradle-home'
$env:PATH = "$jdk\bin;$env:PATH"

Write-Host "JDK             : $jdk ($(Get-JavaMajor (Join-Path $jdk 'bin\java.exe')))"
Write-Host "GRADLE_USER_HOME: $env:GRADLE_USER_HOME"
if ($Offline) { Write-Host 'mode            : offline' }
Write-Host ''

Push-Location $root
try {
    $gradleArgs = @('build', '--no-daemon', '--console=plain')
    if ($Offline) { $gradleArgs += '--offline' }
    & "$root\gradlew.bat" @gradleArgs
    $code = $LASTEXITCODE
    Write-Host "exit=$code"
    Get-ChildItem (Join-Path $root 'build\libs') -ErrorAction SilentlyContinue |
        Select-Object Name, Length, LastWriteTime | Format-Table -AutoSize | Out-String | Write-Host
    exit $code
} finally {
    Pop-Location
}
