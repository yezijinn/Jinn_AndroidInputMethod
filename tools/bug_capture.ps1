# ============================================================
# bug_capture.ps1 - One-click diagnostic bundle for CapsWriterIME
#
# Uses ADB + Root(KernelSU) to collect a complete diagnostic snapshot:
#   - app logs (jinn-*.log)
#   - logcat (full, system-wide under root)
#   - dumpsys input_method / window / activity / meminfo
#   - appops / package / permissions
#   - clipboard DB (read-only copy, root)
#   - packaged as bug_YYYYMMDD_HHMMSS.zip
#
# Usage:
#   .\tools\bug_capture.ps1 [device]      (default 192.168.1.33:5555)
#   .\tools\bug_clear.ps1                 clear old logs first
# ============================================================

param(
    [string]$Device = "192.168.1.33:5555"
)

$ErrorActionPreference = "Continue"
$stamp = Get-Date -Format "yyyyMMdd_HHmmss"
$pkg = "com.jinn.voiceinput"

# Project root = parent of tools dir
$proj = Split-Path $PSScriptRoot -Parent
$outDir = Join-Path $proj "build\bug"
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
$captureDir = Join-Path $outDir "capture_$stamp"
New-Item -ItemType Directory -Force -Path $captureDir | Out-Null

function Adb-Shell([string]$args) {
    & adb -s $Device shell $args 2>$null
}

function Adb-Root([string]$args) {
    & adb -s $Device shell su -c $args 2>$null
}

Write-Host "== Capture start: $stamp device=$Device ==" -ForegroundColor Cyan

# 1. App logs
Write-Host "[1/9] app logs..." -ForegroundColor Yellow
$logDir = "/storage/emulated/0/JinnIme/logs"
$files = Adb-Shell "ls $logDir/jinn-*.log"
foreach ($f in $files) {
    $n = $f.Trim()
    if ($n) { & adb -s $Device pull $n $captureDir 2>$null | Out-Null }
}

# 2. logcat (system-wide under root)
Write-Host "[2/9] logcat..." -ForegroundColor Yellow
& adb -s $Device shell su -c "logcat -d -v threadtime" | Out-File -Encoding utf8 (Join-Path $captureDir "logcat.txt")

# 3. dumpsys input_method
Write-Host "[3/9] dumpsys input_method..." -ForegroundColor Yellow
& adb -s $Device shell su -c "dumpsys input_method" | Out-File -Encoding utf8 (Join-Path $captureDir "dumpsys_input_method.txt")

# 4. dumpsys window
Write-Host "[4/9] dumpsys window..." -ForegroundColor Yellow
Adb-Shell "dumpsys window" | Out-File -Encoding utf8 (Join-Path $captureDir "dumpsys_window.txt")

# 5. dumpsys activity
Write-Host "[5/9] dumpsys activity..." -ForegroundColor Yellow
Adb-Shell "dumpsys activity top" | Out-File -Encoding utf8 (Join-Path $captureDir "dumpsys_activity.txt")

# 6. meminfo / cpu
Write-Host "[6/9] meminfo/cpu..." -ForegroundColor Yellow
Adb-Shell "dumpsys meminfo $pkg" | Out-File -Encoding utf8 (Join-Path $captureDir "dumpsys_meminfo.txt")
Adb-Shell "top -n 1 -m 20" | Out-File -Encoding utf8 (Join-Path $captureDir "cpu_top.txt")

# 7. appops / package
Write-Host "[7/9] appops/package..." -ForegroundColor Yellow
& adb -s $Device shell su -c "appops get $pkg" | Out-File -Encoding utf8 (Join-Path $captureDir "appops.txt")
& adb -s $Device shell su -c "dumpsys package $pkg" | Out-File -Encoding utf8 (Join-Path $captureDir "package_info.txt")

# 8. Clipboard DB (read-only copy)
Write-Host "[8/9] clipboard db..." -ForegroundColor Yellow
& adb -s $Device shell su -c "cp /data/data/$pkg/databases/jinn_clipboard.db /sdcard/JinnIme/clipboard_capture.db && chmod 644 /sdcard/JinnIme/clipboard_capture.db" 2>$null
$captureDb = Join-Path $captureDir "clipboard.db"
$pullOut = & adb -s $Device pull "/sdcard/JinnIme/clipboard_capture.db" $captureDb 2>&1
if ($LASTEXITCODE -ne 0) { Write-Host "WARN: pull clipboard db failed: $pullOut" -ForegroundColor Red }

# 9. ANR / tombstones
Write-Host "[9/9] anr/tombstones..." -ForegroundColor Yellow
& adb -s $Device shell su -c "ls -lt /data/anr/" | Out-File -Encoding utf8 (Join-Path $captureDir "anr_list.txt")
& adb -s $Device shell su -c "ls -lt /data/tombstones/" | Out-File -Encoding utf8 (Join-Path $captureDir "tombstones_list.txt")

# Package into zip
Write-Host "== Packing..." -ForegroundColor Yellow
$zipPath = Join-Path $outDir "bug_$stamp.zip"
if (Get-Command Compress-Archive -ErrorAction SilentlyContinue) {
    Compress-Archive -Path (Join-Path $captureDir "*") -DestinationPath $zipPath -Force
    Write-Host "Done: $zipPath" -ForegroundColor Green
    Write-Host "Tip: temp files kept at $captureDir" -ForegroundColor DarkGray
} else {
    Write-Host "Compress-Archive unavailable; raw files at: $captureDir" -ForegroundColor Yellow
}
