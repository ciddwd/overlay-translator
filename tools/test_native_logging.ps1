param(
    [Parameter(Mandatory = $true)][string]$NdkPath
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$toolchainPath = Join-Path $NdkPath 'toolchains/llvm/prebuilt/windows-x86_64'
$clangPath = Join-Path $toolchainPath 'bin/clang++.exe'
$nmPath = Join-Path $toolchainPath 'bin/llvm-nm.exe'
$sysrootPath = Join-Path $toolchainPath 'sysroot'
$outputPath = Join-Path $projectPath 'llama-android/build/logging-smoke'
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null

$cases = @(
    @{ Name = 'debug'; Enabled = 1; Optimization = '-O0'; ExpectedLogging = $true },
    @{ Name = 'release-unoptimized'; Enabled = 0; Optimization = '-O0'; ExpectedLogging = $false },
    @{ Name = 'release-optimized'; Enabled = 0; Optimization = '-O2'; ExpectedLogging = $false },
    @{ Name = 'unspecified-default'; Enabled = $null; Optimization = '-O0'; ExpectedLogging = $false }
)
foreach ($case in $cases) {
    $objectPath = Join-Path $outputPath ($case.Name + '.o')
    $compileArgs = @(
        '--target=aarch64-linux-android33', "--sysroot=$sysrootPath", '-std=c++17',
        $case.Optimization, '-DLOG_MIN_LEVEL=2',
        '-I', (Join-Path $projectPath 'llama-android/src/main/cpp'),
        '-I', (Join-Path $projectPath 'third_party/llama.cpp/examples/llama.android/lib/src/main/cpp'),
        '-I', (Join-Path $projectPath 'third_party/llama.cpp/ggml/include'),
        '-c', (Join-Path $projectPath 'llama-android/src/test/cpp/logging_smoke.cpp'),
        '-o', $objectPath
    )
    if ($null -ne $case.Enabled) { $compileArgs += "-DGAMEOCR_NATIVE_LOGCAT=$($case.Enabled)" }
    & $clangPath @compileArgs
    if ($LASTEXITCODE -ne 0) { throw "Native compile failed: $($case.Name)" }
    $symbols = (& $nmPath --undefined-only $objectPath) -join "`n"
    if ($LASTEXITCODE -ne 0) { throw "Native symbol inspection failed: $($case.Name)" }
    foreach ($symbol in @('__android_log_print', '__android_log_write', '__android_log_is_loggable', 'expensive_log_argument')) {
        if ($symbols.Contains($symbol) -ne $case.ExpectedLogging) {
            throw "Unexpected $symbol reference in $($case.Name): $symbols"
        }
    }
    Write-Output "PASS $($case.Name)"
}
