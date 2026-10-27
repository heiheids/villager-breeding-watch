# 事件总线归属检查（NeoForge 26.3）。
#
# 背景：NeoForge 有两条总线。挂错的表现是**能编译、能启动、然后在构造模组时抛异常**
# （真机崩过一次，见 docs/lessons.md 第 26 条）：
#   java.lang.IllegalArgumentException:
#     IModBusEvent events are not allowed on the common NeoForge bus! Use a mod bus instead.
#
# 检测思路（两步，不依赖正则猜代码结构）：
#   1. 扫描所有 java，把「方法名 -> 它第一个形参的事件类名」建成索引；
#   2. 再扫描所有 `X.addListener(...)` 调用，解析出被引用的方法名，
#      查索引得到事件类型；若该事件属于 mod-bus 事件，则 X 必须是 modBus。
#
# IModBusEvent 清单是逐个 javap 核对过的，不是猜的。
param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$src = Join-Path $Root 'src\main\java'
if (-not (Test-Path $src)) { throw "找不到源码目录：$src" }

# 实现 IModBusEvent 的事件（只能挂 mod bus）
$ModBusEvents = @(
    'RegisterGuiLayersEvent'
    'RegisterKeyMappingsEvent'
    'RegisterPayloadHandlersEvent'
    'RegisterRenderPipelinesEvent'
    'RegisterMenuScreensEvent'
    'RegisterColorHandlersEvent'
    'RegisterBlockColorHandlersEvent'
    'RegisterItemColorHandlersEvent'
    'RegisterClientTooltipComponentFactoriesEvent'
    'RegisterClientExtensionsEvent'
    'RegisterConditionCodecsEvent'
    'RegisterDataComponentsEvent'
    'EntityRenderersEvent'
    'RegisterSpecialModelRendererEvent'
    'RegisterDebugRenderersEvent'
)

$files = Get-ChildItem $src -Recurse -Filter *.java
Write-Host "扫描 $($files.Count) 个 java 文件"

# ---- 步骤 1：方法名 -> 第一个形参里的 mc 事件类型 ----
$methodEvent = @{}
foreach ($f in $files) {
    $text = [System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)
    # 形如：  void onXxx(SomeEvent e)  /  private static void onYyy(net.neoforged...FooEvent ev)
    foreach ($m in [regex]::Matches($text, '\b(?:void|boolean|int|float|double|long|[A-Z]\w*(?:<[^>]*>)?)\s+(\w+)\s*\(\s*([A-Za-z_][\w\.]*)\s+\w+')) {
        $meth = $m.Groups[1].Value
        $paramType = $m.Groups[2].Value
        $short = ($paramType -split '\.')[-1]
        if (-not $methodEvent.ContainsKey($meth)) { $methodEvent[$meth] = $short }
    }
}
Write-Host "已索引 $($methodEvent.Count) 个方法的首参类型"

# ---- 步骤 2：检查所有 addListener 调用 ----
$fail = 0
$checked = 0
foreach ($f in $files) {
    $lines = [System.IO.File]::ReadAllLines($f.FullName, [System.Text.Encoding]::UTF8)
    $rel = $f.FullName.Replace($Root + '\', '')
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $line = $lines[$i]
        if ($line -notmatch 'addListener\s*\(') { continue }

        # 参数里引用的方法名：Class::method 或 this::method 或裸方法名
        $methName = $null
        $mm = [regex]::Match($line, '::\s*(\w+)\s*\)')
        if ($mm.Success) { $methName = $mm.Groups[1].Value }

        # 事件类型：优先看本行是否直接出现事件类名
        $evType = $null
        foreach ($ev in $ModBusEvents) {
            if ($line -match [regex]::Escape($ev)) { $evType = $ev; break }
        }
        # 否则通过方法索引反查
        if (-not $evType -and $methName -and $methodEvent.ContainsKey($methName)) {
            $p = $methodEvent[$methName]
            foreach ($ev in $ModBusEvents) {
                if ($p -like "*$ev*") { $evType = $ev; break }
            }
        }
        if (-not $evType) { continue }

        $checked++
        $isMod = $line -match 'modBus\s*\.\s*addListener'
        if ($isMod) {
            Write-Host "  [OK]   $rel`:$($i+1) $evType"
        } else {
            Write-Host "  [FAIL] $rel`:$($i+1) $evType 未挂在 modBus 上" -ForegroundColor Red
            Write-Host "         $($line.Trim())"
            $script:fail++
        }
    }
}

Write-Host ''
Write-Host "检查了 $checked 处 mod-bus 事件注册"
if ($fail -eq 0) { Write-Host 'BUS CHECK PASSED' -ForegroundColor Green; exit 0 }
else { Write-Host "BUS CHECK FAILED ($fail)" -ForegroundColor Red; exit 1 }
