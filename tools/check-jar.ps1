# 产物自检：从源码推导校验清单，避免"功能加了但没打包进去"。
#
# 校验项：
#   1. fabric.mod.json 存在且 main / client 入口点齐全（26.x 必须写在这里）
#   2. 入口点类真的在 jar 里
#   3. 两份语言文件都在，且键集完全一致
#   4. 图标存在
#   5. 没有误把可选依赖 / 客户端类打进公共包以外的地方
#   6. 公共侧（main 源码集）不引用任何客户端类
param(
    [string]$Jar = '',
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
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

    # 1 + 2 入口点
    $modJson = $names -contains 'fabric.mod.json'
    Check $modJson 'fabric.mod.json 存在'
    if ($modJson) {
        $e = $z.GetEntry('fabric.mod.json')
        $r = New-Object System.IO.StreamReader($e.Open())
        $text = $r.ReadToEnd(); $r.Close()
        $mod = $text | ConvertFrom-Json
        Check ($mod.entrypoints.main -contains 'com.heishandashu.breedingwatch.VbwMod') 'main 入口点已声明'
        Check ($mod.entrypoints.client -contains 'com.heishandashu.breedingwatch.client.VbwClient') 'client 入口点已声明（26.x 必须写在 fabric.mod.json）'
        Check ($mod.authors -contains '黑山大叔') '作者信息已写入'
        Check ($mod.contact.homepage -eq 'https://space.bilibili.com/19428259') '主页已写入'
        foreach ($ep in @('com/heishandashu/breedingwatch/VbwMod.class',
                          'com/heishandashu/breedingwatch/client/VbwClient.class')) {
            Check ($names -contains $ep) "入口点类存在：$ep"
        }
        Check ($names -contains $mod.icon) "图标存在：$($mod.icon)"
    }

    # 3 语言文件键集
    $langOk = $true
    $sets = @{}
    foreach ($code in @('zh_cn','en_us')) {
        $path = "vbw_lang/$code.json"
        if (-not ($names -contains $path)) { $langOk = $false; continue }
        $e = $z.GetEntry($path)
        $r = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
        $txt = $r.ReadToEnd(); $r.Close()
        $obj = $txt | ConvertFrom-Json
        $flat = [System.Collections.Generic.List[string]]::new()
        $stack = New-Object System.Collections.Stack
        $stack.Push(@('', $obj))
        while ($stack.Count -gt 0) {
            $pair = $stack.Pop()
            $prefix = $pair[0]; $node = $pair[1]
            foreach ($k in $node.PSObject.Properties.Name) {
                if ($k.StartsWith('_')) { continue }
                $full = if ($prefix) { "$prefix.$k" } else { $k }
                $v = $node.$k
                if ($v -is [pscustomobject]) { $stack.Push(@($full, $v)) }
                else { $flat.Add($full) }
            }
        }
        $sets[$code] = $flat
        Write-Host "  [$code] $($flat.Count) keys"
    }
    Check $langOk '两份语言文件都存在'
    if ($sets.Count -eq 2) {
        $onlyZh = Compare-Object $sets['zh_cn'] $sets['en_us'] | Where-Object SideIndicator -eq '<='
        $onlyEn = Compare-Object $sets['zh_cn'] $sets['en_us'] | Where-Object SideIndicator -eq '=>'
        Check ((-not $onlyZh) -and (-not $onlyEn)) '中英文键集完全一致'
        if ($onlyZh) { Write-Host "        中文多出：$($onlyZh.InputObject -join ', ')" }
        if ($onlyEn) { Write-Host "        英文多出：$($onlyEn.InputObject -join ', ')" }
    }

    # 4 原版界面读的标准语言文件（按键绑定 / 分类）
    #    这一组曾经整个漏掉，表现是按键绑定界面直接显示原始翻译键
    #    key.villagerbreedingwatch.settings —— 因为原版读的是 assets/<id>/lang/，
    #    与模组自绘界面用的 vbw_lang/ 是两套完全独立的文件。
    $stdOk = $true
    foreach ($code in @('zh_cn','en_us')) {
        $path = "assets/villagerbreedingwatch/lang/$code.json"
        if (-not ($names -contains $path)) { $stdOk = $false; Write-Host "        缺少 $path" }
    }
    Check $stdOk '标准语言文件存在（assets/<id>/lang/，供原版界面读取）'

    if ($stdOk) {
        $e = $z.GetEntry('assets/villagerbreedingwatch/lang/zh_cn.json')
        $r = New-Object System.IO.StreamReader($e.Open(), [System.Text.Encoding]::UTF8)
        $stdZh = ($r.ReadToEnd() | ConvertFrom-Json); $r.Close()

        # 代码里实际注册的按键键名，必须都能在这份文件里翻到
        $required = @(
            'key.category.villagerbreedingwatch.main',
            'key.villagerbreedingwatch.settings',
            'key.villagerbreedingwatch.stats',
            'key.villagerbreedingwatch.toggle'
        )
        $missing = @()
        foreach ($k in $required) {
            $v = $stdZh.PSObject.Properties[$k]
            if (-not $v) { $missing += $k }
        }
        Check ($missing.Count -eq 0) '按键与分类的翻译键齐全（不会再显示原始键名）'
        if ($missing.Count -gt 0) { $missing | ForEach-Object { Write-Host "        缺：$_" } }

        # 不允许无前缀的通用键混进全局命名空间（会和别的模组撞键）
        $badKeys = @($stdZh.PSObject.Properties.Name | Where-Object {
            -not $_.StartsWith('_') -and -not $_.StartsWith('key.category.villagerbreedingwatch') `
            -and -not $_.StartsWith('key.villagerbreedingwatch')
        })
        Check ($badKeys.Count -eq 0) '标准语言文件只含带模组前缀的键（不污染全局键空间）'
        if ($badKeys.Count -gt 0) { $badKeys | ForEach-Object { Write-Host "        多余：$_" } }
    }

    # 5 不该打进去的东西
    $bad = $names | Where-Object { $_ -match '\.gradle-home|\.gradle/|tools/|build\.gradle|gradle\.properties' }
    Check (-not $bad) '没有误打包构建脚本 / 缓存'

    # 6 公共侧不引用客户端类
    $mainSrc = Join-Path $Root 'src\main\java'
    $clientRefs = @()
    if (Test-Path $mainSrc) {
        $clientRefs = Get-ChildItem $mainSrc -Recurse -Filter *.java |
            Select-String -Pattern 'breedingwatch\.client\.|net\.minecraft\.client\.|net\.fabricmc\.fabric\.api\.client\.'
        if ($clientRefs) { $clientRefs | Select-Object -First 8 | ForEach-Object { Write-Host "        $($_.Path):$($_.LineNumber)" } }
    }
    Check (-not $clientRefs) '公共源码集不引用客户端类（专用服务端不会崩）'
} finally {
    $z.Dispose()
}

Write-Host ''
if ($fail -eq 0) { Write-Host 'SELF-CHECK PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "SELF-CHECK FAILED ($fail)" -ForegroundColor Red; exit 1 }
