# ============================================================
# bug_clear.ps1 - Clear old diagnostics before reproducing a bug
# Usage: .\tools\bug_clear.ps1 [device]   (default 192.168.1.33:5555)
# ============================================================

param(
    [string]$Device = "192.168.1.33:5555"
)

$pkg = "com.jinn.voiceinput"
$logDir = "/storage/emulated/0/JinnIme/logs"

Write-Host "== Clear old logs (device=$Device) ==" -ForegroundColor Cyan

# Clear logcat
& adb -s $Device logcat -c 2>$null
Write-Host "[1/3] logcat cleared" -ForegroundColor Yellow

# Delete app log files
& adb -s $Device shell "rm -f $logDir/jinn-*.log $logDir/logcat-*.log" 2>$null
Write-Host "[2/3] app logs deleted" -ForegroundColor Yellow

# Baseline: time + version + git commit
$stamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
$ver = & adb -s $Device shell "dumpsys package $pkg | grep versionName" 2>$null
$git = git -C (Join-Path $PSScriptRoot "..") rev-parse --short HEAD 2>$null
Write-Host "[3/3] baseline: time=$stamp version=$ver git=$git" -ForegroundColor Yellow

Write-Host ""
Write-Host "== Reproduce the bug now, then run .\tools\bug_capture.ps1 ==" -ForegroundColor Green
