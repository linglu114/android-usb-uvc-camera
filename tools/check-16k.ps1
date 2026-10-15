<#
.SYNOPSIS
    校验 APK 的 16 KB 页对齐（官方口径）：ELF LOAD 段对齐 + ZIP 对齐。
.DESCRIPTION
    1) 解压 APK，对 lib/arm64-v8a/*.so 逐个检查 PT_LOAD 的 align 是否 >= 2**14；
    2) 用 zipalign -c -P 16 4 校验 ZIP 层对齐；
    3) 报告 APK 内实际存在的 ABI 列表。
    退出码：0 = 全部通过；1 = 有未对齐项或缺依赖工具。
.EXAMPLE
    tools\check-16k.ps1 -Apk app\build\outputs\apk\debug\app-debug.apk
#>
param(
    [Parameter(Mandatory = $true)][string]$Apk,
    [string]$NdkRoot = 'D:\Data\SDK\Android\ndk',
    [string]$BuildToolsRoot = 'D:\Data\SDK\Android\build-tools',
    [int]$RequiredAlign = 16384
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path $Apk)) { Write-Error "APK not found: $Apk"; exit 1 }
$apkFull = (Resolve-Path $Apk).Path
Write-Host "APK: $apkFull"
Write-Host ("Size: {0:N0} bytes" -f (Get-Item $apkFull).Length)

# --- 工具定位 ---
$objdump = Get-ChildItem $NdkRoot -Recurse -Filter 'llvm-objdump.exe' -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
if (-not $objdump) { Write-Error 'llvm-objdump.exe not found under NDK root'; exit 1 }
$zipalign = Get-ChildItem $BuildToolsRoot -Recurse -Filter 'zipalign.exe' -ErrorAction SilentlyContinue |
    Sort-Object FullName -Descending | Select-Object -First 1
Write-Host "objdump : $($objdump.FullName)"
if ($zipalign) { Write-Host "zipalign: $($zipalign.FullName)" } else { Write-Warning 'zipalign not found; ZIP check skipped' }

# --- 解压 APK ---
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ('apkcheck-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($apkFull, $tmp)

    $libDir = Join-Path $tmp 'lib'
    if (-not (Test-Path $libDir)) { Write-Error 'No lib/ directory in APK (no native code packaged)'; exit 1 }

    $abis = Get-ChildItem $libDir -Directory | Select-Object -ExpandProperty Name
    Write-Host ("ABIs packaged : " + ($abis -join ', '))
    if ($abis -contains 'armeabi-v7a' -or $abis -contains 'x86') {
        Write-Warning 'APK contains 32-bit ABIs whose .so may be 4 KB aligned; target is arm64-v8a only.'
    }

    $arm64 = Join-Path $libDir 'arm64-v8a'
    if (-not (Test-Path $arm64)) { Write-Error 'lib/arm64-v8a missing; 16 KB check cannot pass'; exit 1 }

    $failed = 0
    $checked = 0
    foreach ($so in Get-ChildItem $arm64 -Filter '*.so') {
        $checked++
        $lines = & $objdump.FullName -p $so.FullName 2>$null | Select-String 'LOAD'
        $aligns = @()
        foreach ($l in $lines) {
            if ($l.Line -match 'align\s+2\*\*(\d+)') { $aligns += [int]$Matches[1] }
        }
        if ($aligns.Count -eq 0) {
            Write-Host ("  FAIL  {0}  (no parseable LOAD align)" -f $so.Name)
            $failed++
            continue
        }
        $minPow = ($aligns | Measure-Object -Minimum).Minimum
        $minBytes = [math]::Pow(2, $minPow)
        if ($minBytes -ge $RequiredAlign) {
            Write-Host ("  OK    {0}  minAlign=2**{1}" -f $so.Name, $minPow)
        } else {
            Write-Host ("  FAIL  {0}  minAlign=2**{1} (need 2**14)" -f $so.Name, $minPow)
            $failed++
        }
    }
    Write-Host "ELF check: $checked file(s), $failed failure(s)"

    $zipOk = $true
    if ($zipalign) {
        Write-Host 'ZIP 16 KB alignment check:'
        $out = & $zipalign.FullName -v -c -P 16 4 $apkFull 2>&1
        $tail = ($out | Select-Object -Last 3) -join ' | '
        Write-Host "  $tail"
        if ($LASTEXITCODE -ne 0) { $zipOk = $false; Write-Host '  FAIL  zipalign -P 16 verification failed' }
    }

    if ($failed -eq 0 -and $zipOk) { Write-Host 'RESULT: PASS (16 KB aligned)'; exit 0 }
    Write-Host 'RESULT: FAIL'; exit 1
}
finally {
    Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
}
