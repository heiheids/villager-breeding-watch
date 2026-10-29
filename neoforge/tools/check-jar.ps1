# 产物自检（NeoForge 26.3 版）。
#
# 校验项：
#   1. META-INF/neoforge.mods.toml 存在，modId / version / 作者 / 依赖齐全
#   2. 主类真的在 jar 里
#   3. 两套语言文件都在，且键集一致
#   4. 图标存在
#   5. 没有误打包构建脚本 / 缓存
#   6. 没有把 Fabric 的类混进来（跨加载器污染）
param(
    [string]$Jar = '',
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'

# 期望版本号从 gradle.properties 读，避免每次升版本都要改本脚本
$ExpectedVersion = (Select-String -Path (Join-Path $Root 'gradle.properties') -Pattern '^version=(.+)$').Matches.Groups[1].Value.Trim()
Add-Type -AssemblyName System.IO.Compression.FileSystem

if (-not $Jar) {
    $Jar = Get-ChildItem (Join-Path $Root 'build\libs') -Filter '*.jar' |
        Where-Object Name -notmatch 'sources' |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Jar -or -not (Test-Path $Jar)) { throw "找不到 jar：$Jar" }

Write-Host "jar : $Jar"
Write-Host "size: $((Get-Item $Jar).Length) bytes"

$fail = 0
function Check($ok, $msg) {
    if ($ok) { Write-Host "  [OK]   $msg" }
    else { Write-Host "  [FAIL] $msg" -ForegroundColor Red; $script:fail++ }
}

$z = [System.IO.Compression.ZipFile]::OpenRead($Jar)
try {
    $names = $z.Entries | ForEach-Object { $_.FullName }

    $tomlPath = 'META-INF/neoforge.mods.toml'
    $hasToml = $names -contains $tomlPath
    Check $hasToml 'META-INF/neoforge.mods.toml 存在'

    if ($hasToml) {
        $e = $z.GetEntry($tomlPath)
        $r = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
        $toml = $r.ReadToEnd(); $r.Close()

        Check ($toml -match 'modId\s*=\s*"villagerbreedingwatch"') 'modId 正确'
        Check ($toml -match ([regex]::Escape("version = ""$ExpectedVersion"""))) "版本号是 $ExpectedVersion"
        Check ($toml -match 'authors\s*=\s*"黑山大叔"') '作者信息已写入'
        Check ($toml -match '19428259') '主页已写入'
        Check ($toml -match 'modId\s*=\s*"neoforge"') 'neoforge 依赖已声明'
    }

    # 主类
    Check ($names -contains 'com/heishandashu/breedingwatch/VbwMod.class') '主类 VbwMod 存在'
    Check ($names -contains 'com/heishandashu/breedingwatch/client/VbwClient.class') '客户端类 VbwClient 存在'

    # 语言文件：两套
    $langOk = $true
    $sets = @{}
    foreach ($code in @('zh_cn', 'en_us')) {
        foreach ($prefix in @('vbw_lang', 'assets/villagerbreedingwatch/lang')) {
            $path = "$prefix/$code.json"
            if (-not ($names -contains $path)) { $langOk = $false; Write-Host "        缺少 $path" }
        }
    }
    Check $langOk '两套语言文件齐全（vbw_lang + assets/<id>/lang）'

    # vbw_lang 键集一致
    foreach ($code in @('zh_cn', 'en_us')) {
        $path = "vbw_lang/$code.json"
        if ($names -contains $path) {
            $e = $z.GetEntry($path)
            $r = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
            $obj = ($r.ReadToEnd() | ConvertFrom-Json); $r.Close()
            $flat = [System.Collections.Generic.List[string]]::new()
            $stack = New-Object System.Collections.Stack
            $stack.Push(@('', $obj))
            while ($stack.Count -gt 0) {
                $pair = $stack.Pop(); $prefix = $pair[0]; $node = $pair[1]
                foreach ($k in $node.PSObject.Properties.Name) {
                    if ($k.StartsWith('_')) { continue }
                    $full = if ($prefix) { "$prefix.$k" } else { $k }
                    $v = $node.$k
                    if ($v -is [pscustomobject]) { $stack.Push(@($full, $v)) } else { $flat.Add($full) }
                }
            }
            $sets[$code] = $flat
            Write-Host "  [$code] $($flat.Count) keys"
        }
    }
    if ($sets.Count -eq 2) {
        $diff = Compare-Object $sets['zh_cn'] $sets['en_us']
        Check (-not $diff) '中英文键集完全一致'
    }

    # 图标
    Check ($names -contains 'assets/villagerbreedingwatch/textures/icon.png') '图标存在'

    # 误打包
    $bad = $names | Where-Object { $_ -match '\.gradle-home|\.gradle/|tools/|build\.gradle|gradle\.properties' }
    Check (-not $bad) '没有误打包构建脚本 / 缓存'

    # 跨加载器污染：NeoForge 的 jar 里不该出现 fabric 的类
    $fabricClasses = Get-ChildItem (Join-Path $Root 'src') -Recurse -Filter *.java -EA SilentlyContinue |
        Select-String -Pattern 'import net\.fabricmc\.'
    Check (-not $fabricClasses) '源码中没有残留 net.fabricmc 引用（未被 Fabric 版污染）'
    if ($fabricClasses) {
        $fabricClasses | Select-Object -First 5 | ForEach-Object { Write-Host "        $($_.Path):$($_.LineNumber)" }
    }
} finally {
    $z.Dispose()
}

Write-Host ''
if ($fail -eq 0) { Write-Host 'SELF-CHECK PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "SELF-CHECK FAILED ($fail)" -ForegroundColor Red; exit 1 }
