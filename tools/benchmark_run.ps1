# E7SA 识别层离线回归台（第零批 V1 · 已接入落地报告）
#
# 作用：每次改动后跑一次，断言「识别层没有退化」，并归档基线用于对比。
# 数据来源：App 内诊断基准写出的 benchmark_report.json（合法 JSON，可 adb pull）
#
# 用法：
#   .\benchmark_run.ps1 -Tag baseline                  # 自动尝试长按触发
#   .\benchmark_run.ps1 -Tag after-fix -Manual         # 提示你手动长按版本号
#   .\benchmark_run.ps1 -Tag x -MinRecall 0.9 -MaxFalse 1
#   .\benchmark_run.ps1 -Tag x -Engine traditional     # 改断言另一套引擎
#
# V1 双引擎：App 侧一次基准会把**两套引擎都测**（报告 engines[]），本脚本默认对
# yolo 断言，并把两套引擎的指标并排打印 —— 避免"拿传统引擎的数字对比 YOLO 基线"
# 这种失真（旧版只测当前配置引擎，实测已经踩过一次）。
#
# 前置条件：手机已连接、无障碍已启用、E7SA 在前台（Steam 或 BA 主题均可，两主题都有诊断入口）、机器人未运行

param(
    [Parameter(Mandatory=$true)][string]$Tag,
    [string]$Engine = 'yolo',
    [double]$MinRecall = 0.85,
    [int]$MaxFalse = 3,
    [int]$MaxAvgMs = 0,          # 0 = 不检查耗时
    [switch]$Manual,
    [int]$TapX = 0,              # 0 = 自动按屏幕尺寸计算（横竖屏通用）
    [int]$TapY = 0,
    [int]$TimeoutSec = 90
)

$ErrorActionPreference = 'Continue'   # adb 会往 stderr 写进度信息，Stop 模式会误判为错误
# adb 路径：优先用环境变量 ADB，其次从 PATH 查找（不再写死本机路径）
$adb = if ($env:ADB) { $env:ADB } else { (Get-Command adb -ErrorAction SilentlyContinue).Source }
if (-not $adb) { throw 'adb not found: set $env:ADB or add adb to PATH' }
$remote = '/sdcard/Android/data/com.e7.shop/files/benchmark_report.json'
$outDir = Join-Path $PSScriptRoot 'reports'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null

# 版本号位于头图右上角：x ≈ 逻辑屏宽-80、y ≈ 120。
# 注意 wm size 报的是**物理尺寸**（不含旋转），必须结合 mDisplayRotation 换算逻辑尺寸：
#   竖屏 ROTATION_0   1272x2800 → (1192,120)   实测 1194,126
#   横屏 ROTATION_90  2800x1272 → (2720,120)   实测 2717,108
if ($TapX -le 0 -or $TapY -le 0) {
    $sizeLine = (& $adb shell wm size) -join ' '
    $rotLine = ((& $adb shell dumpsys window | Select-String 'mDisplayRotation=' | Select-Object -First 1) -join ' ')
    if ($sizeLine -match '(\d+)x(\d+)') {
        $pw = [int]$Matches[1]; $ph = [int]$Matches[2]
        if ($rotLine -match 'ROTATION_(90|270)') { $lw = $ph; $lh = $pw } else { $lw = $pw; $lh = $ph }
        $TapX = $lw - 80
        $TapY = 120
        Write-Host "物理 ${pw}x${ph} 逻辑 ${lw}x${lh} -> 版本号触发点 ($TapX,$TapY)"
    } else {
        $TapX = 1192; $TapY = 120
        Write-Host "无法读取屏幕尺寸，使用竖屏默认坐标 ($TapX,$TapY)"
    }
}

Write-Host "=== E7SA 识别层回归台 ===" -ForegroundColor Cyan
Write-Host "标记: $Tag"

$dev = & $adb devices | Select-String '\tdevice$'
if (-not $dev) { Write-Error "没有已连接设备" }
$proc = (& $adb shell pidof com.e7.shop) -join ''
Write-Host "E7SA 进程: $(if($proc){"运行中 (pid=$proc)"}else{'未运行'})"

# 清掉旧报告，确保读到的是本次结果
& $adb shell "rm -f $remote" 2>&1 | Out-Null

if ($Manual) {
    Write-Host "`n>>> 请在手机上长按版本号（v1.0）触发识别基准 <<<" -ForegroundColor Yellow
} else {
    Write-Host "尝试自动触发（长按版本号 @ $TapX,$TapY）..."
    & $adb shell input swipe $TapX $TapY $TapX $TapY 900 2>&1 | Out-Null
}

Write-Host "等待报告生成（最多 $TimeoutSec 秒）..."
$found = $false
$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    Start-Sleep -Seconds 3
    $ls = & $adb shell "ls $remote 2>/dev/null" 2>&1 | Out-String
    if ($ls -match 'benchmark_report\.json') { $found = $true; break }
}

if (-not $found) {
    if (-not $Manual) {
        Write-Host "`n自动触发未生效，切换为手动模式再等一次..." -ForegroundColor Yellow
        Write-Host ">>> 请长按版本号 <<<" -ForegroundColor Yellow
        $deadline = (Get-Date).AddSeconds($TimeoutSec)
        while ((Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 3
            $ls = & $adb shell "ls $remote 2>/dev/null" 2>&1 | Out-String
            if ($ls -match 'benchmark_report\.json') { $found = $true; break }
        }
    }
}
if (-not $found) {
    Write-Host "`n[FAIL] 没有拿到报告文件。" -ForegroundColor Red
    Write-Host "排查：1) E7SA 是否在前台  2) 机器人是否在运行（运行中会跳过）"
    Write-Host "      3) 无障碍服务是否启用  4) 用 -Manual 明确手动长按"
    exit 2
}

# 取回并解析
$local = Join-Path $outDir "report_$Tag.json"
& $adb pull $remote $local *> $null
$data = Get-Content $local -Raw | ConvertFrom-Json

# V1 双引擎：报告 engines[] 含两套引擎的完整指标；旧报告无该字段时退化为顶层单引擎
$engineList = @()
if ($data.PSObject.Properties['engines']) { $engineList = @($data.engines) }
if ($engineList.Count -eq 0) { $engineList = @($data) }

$sel = $engineList | Where-Object { $_.id -eq $Engine } | Select-Object -First 1
if (-not $sel) {
    $sel = $engineList[0]
    Write-Host "[warn] 报告里没有 engine=$Engine，改用 $($sel.id) 断言" -ForegroundColor Yellow
}

Write-Host "`n=== 双引擎对比（* = 本次断言对象）===" -ForegroundColor Cyan
foreach ($e in $engineList) {
    $flag = if ($e.id -eq $sel.id) { '*' } else { ' ' }
    $act = ''
    if ($e.PSObject.Properties['actual'] -and $e.actual -and $e.actual -ne $e.id) { $act = " (实际=$($e.actual))" }
    Write-Host ("  {0} {1,-12}{2} 召回 {3,7:P1}  误检 {4,2}  均值 {5,4}ms  中位 {6,4}ms  P95 {7,4}ms" -f `
        $flag, $e.id, $act, [double]$e.recall, [int]$e.falseCand, [int]$e.avgMs, [int]$e.medianMs, [int]$e.p95Ms)
}

Write-Host "`n=== 逐张结果（engine=$($sel.id)）===" -ForegroundColor Cyan
foreach ($s in $sel.detail) {
    if ($s.PSObject.Properties['error']) {
        Write-Host ("  ERR  {0,-34} {1}" -f $s.img, $s.error) -ForegroundColor Red
    } else {
        $mark = if ([int]$s.detected -ge [int]$s.gtCount -and [int]$s.falseCand -eq 0) { 'OK ' } else { '!! ' }
        $cand = ($s.candidates -join ',')
        Write-Host ("  {0} {1,-34} {2,-10} gt={3} det={4} false={5} {6,5}ms  [{7}]" -f `
            $mark, $s.img, $s.scene, $s.gtCount, $s.detected, $s.falseCand, $s.ms, $cand)
    }
}

$recall = [double]$sel.recall
Write-Host "`n=== 汇总（断言 engine=$($sel.id)，共 $($engineList.Count) 套引擎）===" -ForegroundColor Cyan
Write-Host ("  样本 {0} 张（错误 {1}）  gt={2}  检出={3}  误检={4}" -f `
    $sel.samples, $sel.errors, $sel.gtTotal, $sel.detected, $sel.falseCand)
Write-Host ("  召回率 {0:P1}  (阈值 {1:P0})" -f $recall, $MinRecall)
Write-Host ("  误检   {0}      (阈值 ≤{1})" -f $sel.falseCand, $MaxFalse)
Write-Host ("  耗时   均值 {0}ms  中位 {1}ms  P95 {2}ms  区间 {3}-{4}ms" -f `
    $sel.avgMs, $sel.medianMs, $sel.p95Ms, $sel.minMs, $sel.maxMs)

$pass = ($recall -ge $MinRecall) -and ([int]$sel.falseCand -le $MaxFalse) -and ([int]$sel.errors -eq 0)
if ($MaxAvgMs -gt 0) { $pass = $pass -and ([int]$sel.avgMs -le $MaxAvgMs) }

$summary = [PSCustomObject]@{
    tag = $Tag; timestamp = (Get-Date -Format 's'); engine = $sel.id
    samples = $sel.samples; errors = $sel.errors
    gtTotal = $sel.gtTotal; detected = $sel.detected; falseCand = $sel.falseCand
    recall = $recall; avgMs = $sel.avgMs; medianMs = $sel.medianMs; p95Ms = $sel.p95Ms
    engines = @($engineList | ForEach-Object {
        [PSCustomObject]@{
            id = $_.id; recall = $_.recall; falseCand = $_.falseCand
            avgMs = $_.avgMs; medianMs = $_.medianMs; p95Ms = $_.p95Ms
        }
    })
    pass = $pass
}
$sumPath = Join-Path $outDir "summary_${Tag}_$(Get-Date -Format 'yyyyMMdd_HHmmss').json"
$summary | ConvertTo-Json -Depth 5 | Set-Content -Path $sumPath -Encoding UTF8
Write-Host "`n摘要已归档: $sumPath"

if ($pass) { Write-Host "[PASS] 识别层未退化" -ForegroundColor Green; exit 0 }
else { Write-Host "[FAIL] 指标未达标" -ForegroundColor Red; exit 1 }
