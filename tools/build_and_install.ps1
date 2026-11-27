# QMHelper 本地构建 / 安装 / 热重载 / 看日志一条龙
#
# 为什么需要这个脚本：
#   * LSPosed 是**进程内注入**，改完模块只需要「杀掉 Q音 再启动」就会重载新模块，
#     不需要重启模拟器、也不需要重启 LSPosed —— 所以迭代循环就是"装 + 杀 + 起 + 看日志"。
#   * LSPosed 的模块日志不在 logcat 里，而在 /data/adb/lspd/log/ 下，且需要 root 才能读。
#     这里直接 tail 那个文件，按模块包名过滤。
#
# 用法：
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\build_and_install.ps1                  # 构建 + 装 + 热重载 + 打日志
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\build_and_install.ps1 -SkipBuild        # 只装已有的包
#   pwsh -NoProfile -ExecutionPolicy Bypass -File tools\build_and_install.ps1 -BuildOnly        # 只编译 debug + release
param(
    [switch]$SkipBuild,
    [switch]$Release,
    [switch]$DebugBuild,
    [switch]$BuildOnly,
    [string]$Serial = "emulator-5554",
    [int]$WaitSeconds = 22
)

$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$workspace = Split-Path -Parent $repo
Set-Location $repo

$env:JAVA_HOME = "$workspace\_tools\jdk-21.0.12.1+1"
$env:GRADLE_USER_HOME = "$workspace\_tools\gradle-home"

if (($Release -and $DebugBuild) -or ($BuildOnly -and $SkipBuild)) {
    throw "参数组合冲突"
}

if (-not $SkipBuild) {
    # 每次构建同时出 debug + release：release 是测试/发布主包，debug 用于带符号排查
    Write-Host "== 构建 debug + release ==" -ForegroundColor Cyan
    & .\gradlew.bat --no-daemon :app:assembleDebug :app:assembleRelease
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if ($BuildOnly) { return }

# 默认安装 release。显式选择包类型时，缺包直接报错。
$variants = if ($DebugBuild) { @("debug") } elseif ($Release) { @("release") } else { @("release", "debug") }
$apk = $null
foreach ($variant in $variants) {
    $dir = "app\build\outputs\apk\$variant"
    $candidate = Get-ChildItem "$dir\*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($candidate) { $apk = $candidate; break }
}
if (-not $apk) { Write-Host "!! 找不到 APK" -ForegroundColor Red; exit 1 }
Write-Host "== 安装 $($apk.Name) ($([math]::Round($apk.Length/1MB,1)) MB) ==" -ForegroundColor Cyan
& adb -s $Serial install -r -g $apk.FullName 2>&1 | Select-Object -Last 2
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$lspctl = "/data/adb/modules/zygisk_lsposed/lspctl"
Write-Host "== 启用模块 + 作用域 ==" -ForegroundColor Cyan
& adb -s $Serial shell "su -c '$lspctl scope set --user=0 io.github.xiangfa_test.qmhelper com.tencent.qqmusic >/dev/null 2>&1; $lspctl module enable --user=0 io.github.xiangfa_test.qmhelper >/dev/null 2>&1'" 2>&1 | Out-Null

Write-Host "== 热重载：杀掉 Q音 再启动 ==" -ForegroundColor Cyan
& adb -s $Serial shell am force-stop com.tencent.qqmusic
Start-Sleep 2
& adb -s $Serial shell am start -n com.tencent.qqmusic/.activity.AppStarterActivity 2>&1 | Select-Object -First 2

Write-Host "== 等 $WaitSeconds 秒，抓模块日志 ==" -ForegroundColor Cyan
Start-Sleep $WaitSeconds

$log = & adb -s $Serial shell "su -c 'ls -t /data/adb/lspd/log/modules_*.log | head -1'"
$log = $log.Trim()
& adb -s $Serial shell "su -c 'tail -400 $log'" 2>&1 |
    Select-String -Pattern 'qmhelper|QMHelper|Exception|Error|Couldn''t|couldn''t' |
    Select-Object -Last 80 | ForEach-Object { $_.Line }