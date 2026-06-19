param(
    [ValidateSet("query-understanding", "evidence-pack", "evidence-pack-pending", "boundary", "cross-language", "external-aider-polyglot", "external-repobench", "external-multi-swe-bench", "external-swe-bench-lite", "external-defects4j", "all")]
    [string]$Suite = "query-understanding",
    [int]$CaseLimit = 0,
    [string]$Keywords = "",
    [string]$DatasetFile = "",
    [string]$RunName = "source-grounded-context",
    [string]$RunDir = "",
    [switch]$StatsOnly,
    [switch]$Continue,
    [switch]$All
)

$ErrorActionPreference = "Stop"

if ($All) {
    $Suite = "all"
}

$latestPathFile = Join-Path (Join-Path "target" "context-benchmark-runs") "context-benchmark-latest.txt"
$previousLatestRun = ""
if (Test-Path -LiteralPath $latestPathFile) {
    $previousLatestRun = (Get-Content -Raw -LiteralPath $latestPathFile).Trim()
}

$mavenArgs = @(
    "test",
    "-Dtest=ContextBenchmarkCliTests",
    "-DcontextBenchmark.invoked=true",
    "-DcontextBenchmark.suite=$Suite",
    "-DcontextBenchmark.caseLimit=$CaseLimit",
    "-DcontextBenchmark.runName=$RunName"
)

if ($Keywords -ne "") {
    $mavenArgs += "-DcontextBenchmark.keywords=$Keywords"
}

if ($DatasetFile -ne "") {
    $mavenArgs += "-DcontextBenchmark.datasetFile=$DatasetFile"
}

if ($RunDir -ne "") {
    $mavenArgs += "-DcontextBenchmark.runDir=$RunDir"
}

if ($StatsOnly) {
    $mavenArgs += "-DcontextBenchmark.statsOnly=true"
}

if ($Continue) {
    $mavenArgs += "-DcontextBenchmark.continue=true"
}

& mvn @mavenArgs
$exitCode = $LASTEXITCODE

if (Test-Path -LiteralPath $latestPathFile) {
    $latestRun = Get-Content -Raw -LiteralPath $latestPathFile
    $latestRun = $latestRun.Trim()
    if ($latestRun -ne "" -and ($exitCode -eq 0 -or $latestRun -ne $previousLatestRun)) {
        Write-Host "Context benchmark report: $latestRun"
        Write-Host "Summary JSON: $(Join-Path $latestRun 'context-benchmark-summary.json')"
        Write-Host "Summary Markdown: $(Join-Path $latestRun 'context-benchmark-summary.md')"
    } elseif ($exitCode -ne 0) {
        Write-Host "Context benchmark report: not generated for this failed invocation"
    }
} elseif ($exitCode -ne 0) {
    Write-Host "Context benchmark report: not generated for this failed invocation"
}

exit $exitCode
