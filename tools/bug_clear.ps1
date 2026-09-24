# ============================================================
# bug_clear.ps1 - Clear old diagnostics before reproducing a bug
# Usage: .\tools\bug_clear.ps1 [device]   (默认取 $env:JINN_DEVICE)
# ============================================================

param(
    [string]$Device = $env:JINN_DEVICE   # 手机地址（adb 网络调试），例：$env:JINN_DEVICE = '192.0.2.10:5555'
)

$pkg = "com.jinn.inputmethod"
# 日志目录随版本变化：现版本写「应用专属外部目录」，旧版本可能写共享存储 —— 一起清
$logDirs = @(
    "/sdcard/Android/data/$pkg/files/logs",
    "/storage/emulated/0/Android/data/$pkg/files/logs",
    "/storage/emulated/0/JinnIme/logs"
)

Write-Host "== Clear old logs (device=$Device) ==" -ForegroundColor Cyan

# Clear logcat
& adb -s $Device logcat -c 2>$null
Write-Host "[1/3] logcat cleared" -ForegroundColor Yellow

# Delete app log files（三个候选目录都清，幂等）
foreach ($d in $logDirs) {
    & adb -s $Device shell "rm -f $d/jinn-*.log $d/logcat-*.log" 2>$null
}
Write-Host "[2/3] app logs deleted" -ForegroundColor Yellow

# Baseline: time + version + git commit
$stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$ver = & adb -s $Device shell "dumpsys package $pkg | grep versionName" 2>$null
$git = git -C (Join-Path $PSScriptRoot "..") rev-parse --short HEAD 2>$null
Write-Host "[3/3] baseline: time=$stamp version=$ver git=$git" -ForegroundColor Yellow

Write-Host ""
Write-Host "== Reproduce the bug now, then run .\tools\bug_capture.ps1 ==" -ForegroundColor Green
