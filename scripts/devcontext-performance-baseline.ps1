param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$DecisionCounts = "10,100,500",
    [int]$Iterations = 20,
    [int]$Warmup = 3,
    [int]$TopK = 5,
    [int]$TimeoutSeconds = 60,
    [string]$OutputDir = "docs/reports",
    [switch]$KeepData,
    [switch]$Help
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

if ($Help) {
    @"
DevContext performance baseline runner

Usage:
  powershell -ExecutionPolicy Bypass -File scripts/devcontext-performance-baseline.ps1

Common options:
  -BaseUrl http://localhost:18080
  -DecisionCounts "10,100,500"
  -Iterations 20
  -Warmup 3
  -TopK 5
  -OutputDir docs/reports
  -KeepData

Notes:
  The script seeds temporary active Decision Cards, measures key Decision Memory
  endpoints, writes Markdown and JSON reports, then soft-deprecates seeded data
  unless -KeepData is provided.
"@ | Write-Host
    exit 0
}

function New-RunId {
    return (Get-Date).ToString("yyyyMMdd-HHmmss")
}

function Convert-DecisionCounts {
    param([string]$RawCounts)
    $counts = New-Object System.Collections.Generic.List[int]
    if ($null -eq $RawCounts -or $RawCounts.Trim().Length -eq 0) {
        return [int[]]@()
    }

    $parts = $RawCounts.Split(",", [System.StringSplitOptions]::RemoveEmptyEntries)
    foreach ($part in $parts) {
        $trimmed = $part.Trim()
        if ($trimmed -notmatch "^\d+$") {
            throw "DecisionCounts contains an invalid value: $trimmed"
        }
        $counts.Add([int]$trimmed)
    }

    return [int[]]@($counts | Sort-Object -Unique)
}

function Get-Millis {
    param([System.Diagnostics.Stopwatch]$Stopwatch)
    return [math]::Round($Stopwatch.Elapsed.TotalMilliseconds, 2)
}

function Get-Percentile {
    param(
        [double[]]$Values,
        [double]$Percentile
    )
    if ($Values.Count -eq 0) {
        return 0
    }
    $sorted = @($Values | Sort-Object)
    $index = [math]::Ceiling(($Percentile / 100.0) * $sorted.Count) - 1
    if ($index -lt 0) {
        $index = 0
    }
    if ($index -ge $sorted.Count) {
        $index = $sorted.Count - 1
    }
    return [math]::Round([double]$sorted[$index], 2)
}

function Get-Stats {
    param([object[]]$Samples)
    $successes = @($Samples | Where-Object { $_.Success })
    $durations = @($successes | ForEach-Object { [double]$_.DurationMs })
    $successRate = 0
    if ($Samples.Count -gt 0) {
        $successRate = [math]::Round($successes.Count / $Samples.Count, 4)
    }
    $avg = 0
    if ($durations.Count -gt 0) {
        $avg = [math]::Round((($durations | Measure-Object -Average).Average), 2)
    }
    return [pscustomobject]@{
        samples = $Samples.Count
        successCount = $successes.Count
        successRate = $successRate
        avgMs = $avg
        p50Ms = Get-Percentile -Values $durations -Percentile 50
        p95Ms = Get-Percentile -Values $durations -Percentile 95
        p99Ms = Get-Percentile -Values $durations -Percentile 99
        maxMs = Get-Percentile -Values $durations -Percentile 100
    }
}

function Invoke-DevContextApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    $uri = $Path
    if (-not $Path.StartsWith("http")) {
        $uri = $BaseUrl.TrimEnd("/") + $Path
    }

    $params = @{
        Method = $Method
        Uri = $uri
        TimeoutSec = $TimeoutSeconds
        ErrorAction = "Stop"
    }
    if ($null -ne $Body) {
        $params.ContentType = "application/json"
        $params.Body = ($Body | ConvertTo-Json -Depth 50)
    }

    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $response = Invoke-RestMethod @params
        $watch.Stop()
        $success = $false
        if ($null -ne $response.success) {
            $success = [bool]$response.success
        }
        return [pscustomobject]@{
            Success = $success
            DurationMs = Get-Millis -Stopwatch $watch
            Data = $response.data
            Message = $response.message
            ErrorCode = $response.errorCode
        }
    } catch {
        $watch.Stop()
        return [pscustomobject]@{
            Success = $false
            DurationMs = Get-Millis -Stopwatch $watch
            Data = $null
            Message = $_.Exception.Message
            ErrorCode = "REQUEST_FAILED"
        }
    }
}

function Measure-Endpoint {
    param(
        [string]$Name,
        [scriptblock]$Action
    )
    for ($i = 0; $i -lt $Warmup; $i++) {
        [void](& $Action)
    }

    $samples = @()
    $lastData = $null
    for ($i = 0; $i -lt $Iterations; $i++) {
        $sample = & $Action
        $samples += $sample
        if ($sample.Success) {
            $lastData = $sample.Data
        }
    }

    return [pscustomobject]@{
        name = $Name
        stats = Get-Stats -Samples $samples
        lastData = $lastData
    }
}

function New-DecisionPayload {
    param(
        [int]$Index,
        [string]$Tag,
        [string]$RunId
    )

    if ($Index -eq 1) {
        return @{
            title = "Perf $RunId cursor pagination decision"
            scenario = "Payment and order history lists are append-heavy and slow when deep offset pagination scans many rows."
            options = @("offset pagination", "cursor pagination")
            decision = "Use cursor pagination with createdAt and id as a stable composite cursor."
            reasons = @("Avoids deep offset scans.", "Keeps sequential browsing stable while new rows arrive.")
            tradeOffs = @("Cannot jump to arbitrary page numbers.", "Requires cursor encoding and validation.")
            applicableWhen = @("Append-heavy ledger.", "Users browse records sequentially.")
            notApplicableWhen = @("The product requires exact numbered page jumps.")
            outcome = "Reduced performance risk for growing list pages."
            evidence = @(@{ type = "performance-baseline"; ref = $RunId; summary = "Seeded canonical cursor decision." })
            status = "active"
            tags = @($Tag, "perf-cursor")
        }
    }

    if ($Index -eq 2) {
        return @{
            title = "Perf $RunId product detail cache decision"
            scenario = "Product detail pages are read-heavy and repeatedly query stable product snapshots."
            options = @("direct database read", "read-through cache")
            decision = "Use read-through cache with explicit invalidation for product detail snapshots."
            reasons = @("Reduces repeated database reads.", "Keeps hot product pages responsive.")
            tradeOffs = @("Requires cache invalidation.", "May serve stale data if invalidation fails.")
            applicableWhen = @("Product detail data is read-heavy.", "Product updates are less frequent than reads.")
            notApplicableWhen = @("The page must always read the latest transactional state.")
            outcome = "Reduced repeated database access for hot product pages."
            evidence = @(@{ type = "performance-baseline"; ref = $RunId; summary = "Seeded canonical cache decision." })
            status = "active"
            tags = @($Tag, "perf-cache")
        }
    }

    $category = $Index % 5
    if ($category -eq 0) {
        $title = "Perf $RunId tenant isolation decision $Index"
        $scenario = "Tenant scoped administrative reads must prevent cross-tenant data access for sample $Index."
        $decision = "Require tenantId in repository queries and validate tenant scope at the application boundary."
        $tags = @($Tag, "perf-tenant")
    } elseif ($category -eq 1) {
        $title = "Perf $RunId webhook retry decision $Index"
        $scenario = "External webhook delivery can fail transiently and needs controlled retry behavior for sample $Index."
        $decision = "Use bounded exponential backoff with idempotency keys for webhook delivery."
        $tags = @($Tag, "perf-retry")
    } elseif ($category -eq 2) {
        $title = "Perf $RunId audit logging decision $Index"
        $scenario = "Sensitive operations need traceable audit records without blocking the main request for sample $Index."
        $decision = "Write audit events asynchronously after validating the command result."
        $tags = @($Tag, "perf-audit")
    } elseif ($category -eq 3) {
        $title = "Perf $RunId file upload validation decision $Index"
        $scenario = "Uploaded files need type and size validation before storage for sample $Index."
        $decision = "Validate content type, size, and extension before persisting upload metadata."
        $tags = @($Tag, "perf-upload")
    } else {
        $title = "Perf $RunId async export decision $Index"
        $scenario = "Large exports should not block request threads while reports are generated for sample $Index."
        $decision = "Create an export job and process it asynchronously with status polling."
        $tags = @($Tag, "perf-export")
    }

    return @{
        title = $title
        scenario = $scenario
        options = @("simple implementation", "controlled engineering pattern")
        decision = $decision
        reasons = @("Keeps the behavior explicit.", "Makes future reuse easier to evaluate.")
        tradeOffs = @("Adds implementation structure.", "Requires tests around the chosen boundary.")
        applicableWhen = @("The same problem shape appears again.")
        notApplicableWhen = @("The product constraints are materially different.")
        outcome = "Seeded non-target Decision Card for performance baseline."
        evidence = @(@{ type = "performance-baseline"; ref = $RunId; summary = "Synthetic baseline seed $Index." })
        status = "active"
        tags = $tags
    }
}

function New-Decision {
    param([object]$Payload)
    $result = Invoke-DevContextApi -Method "Post" -Path "/api/decisions" -Body $Payload
    if (-not $result.Success) {
        throw "Failed to create decision: $($result.Message)"
    }
    return [long]$result.Data.decisionId
}

function Add-DecisionSeeds {
    param(
        [int]$TargetCount,
        [string]$Tag,
        [string]$RunId,
        [System.Collections.ArrayList]$SeededIds
    )
    while ($SeededIds.Count -lt $TargetCount) {
        $index = $SeededIds.Count + 1
        $payload = New-DecisionPayload -Index $index -Tag $Tag -RunId $RunId
        $id = New-Decision -Payload $payload
        [void]$SeededIds.Add($id)
        if (($SeededIds.Count % 50) -eq 0 -or $SeededIds.Count -eq $TargetCount) {
            Write-Host "Seeded $($SeededIds.Count)/$TargetCount Decision Cards"
        }
    }
}

function Get-UrlEncoded {
    param([string]$Value)
    return [System.Uri]::EscapeDataString($Value)
}

function Write-Reports {
    param(
        [string]$RunId,
        [string]$Tag,
        [int[]]$DecisionScales,
        [object[]]$Rows,
        [long[]]$SeededIds,
        [bool]$CleanupDone,
        [string]$OutputDir
    )
    if (-not (Test-Path -LiteralPath $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir | Out-Null
    }

    $jsonPath = Join-Path $OutputDir "performance-baseline-$RunId.json"
    $mdPath = Join-Path $OutputDir "performance-baseline-$RunId.md"

    $payload = [pscustomobject]@{
        runId = $RunId
        tag = $Tag
        baseUrl = $BaseUrl
        decisionCounts = $DecisionScales
        iterations = $Iterations
        warmup = $Warmup
        topK = $TopK
        seededDecisionIds = $SeededIds
        cleanupDone = $CleanupDone
        generatedAt = (Get-Date).ToString("o")
        rows = $Rows
    }
    $payload | ConvertTo-Json -Depth 80 | Set-Content -Encoding UTF8 -LiteralPath $jsonPath

    $lines = @()
    $lines += "# DevContext Performance Baseline $RunId"
    $lines += ""
    $lines += "## Environment"
    $lines += ""
    $lines += "- Base URL: ``$BaseUrl``"
    $lines += "- Seed tag: ``$Tag``"
    $lines += "- Decision counts: ``$($DecisionScales -join ', ')``"
    $lines += "- Iterations: ``$Iterations``"
    $lines += "- Warmup: ``$Warmup``"
    $lines += "- TopK: ``$TopK``"
    $lines += "- Cleanup done: ``$CleanupDone``"
    $lines += ""
    $lines += "## Summary"
    $lines += ""
    $lines += "| Scale | Endpoint | Success | Avg ms | P50 | P95 | P99 | Max | HitRate | MRR | Precision@K | Recall@K |"
    $lines += "| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    foreach ($row in $Rows) {
        $stats = $row.stats
        $metrics = $row.metrics
        $lines += "| $($row.scale) | $($row.endpoint) | $($stats.successRate) | $($stats.avgMs) | $($stats.p50Ms) | $($stats.p95Ms) | $($stats.p99Ms) | $($stats.maxMs) | $($metrics.hitRate) | $($metrics.mrr) | $($metrics.precisionAtK) | $($metrics.recallAtK) |"
    }
    $lines += ""
    $lines += "## Seeded Data"
    $lines += ""
    $lines += "- Seeded IDs: ``$($SeededIds -join ', ')``"
    $lines += ""
    $lines += "## Notes"
    $lines += ""
    $lines += "- ``Decision Search`` and ``Recall Evaluation`` measure the core Decision Memory recall loop."
    $lines += "- ``Duplicate Candidates`` is expected to grow faster because it compares Decision Card pairs."
    $lines += "- ``Batch Rebuild Embeddings`` includes VectorStore upsert cost."
    $lines += "- Reports are local-only because ``docs/`` is ignored by Git."

    $lines | Set-Content -Encoding UTF8 -LiteralPath $mdPath
    return [pscustomobject]@{
        jsonPath = $jsonPath
        markdownPath = $mdPath
    }
}

$runId = New-RunId
$tag = "perf-baseline-$runId"
$seededIds = New-Object System.Collections.ArrayList
$cleanupDone = $false
$rows = @()

$DecisionScales = [int[]]@(Convert-DecisionCounts -RawCounts $DecisionCounts)
if ($DecisionScales.Count -eq 0 -or $DecisionScales[0] -lt 2) {
    throw "DecisionCounts must include values >= 2"
}
if (($DecisionScales | Measure-Object -Maximum).Maximum -gt 5000) {
    throw "DecisionCounts max is too large for this local baseline script. Use <= 5000."
}
if ($Iterations -lt 1) {
    throw "Iterations must be >= 1"
}
if ($Warmup -lt 0) {
    throw "Warmup must be >= 0"
}
if ($TopK -lt 1 -or $TopK -gt 20) {
    throw "TopK must be between 1 and 20"
}

Write-Host "Decision scales: $($DecisionScales -join ', ')"
Write-Host "Checking DevContext health at $BaseUrl"
$health = Invoke-DevContextApi -Method "Get" -Path "/api/health"
if (-not $health.Success) {
    throw "DevContext health check failed: $($health.Message)"
}

try {
    foreach ($scale in $DecisionScales) {
        Write-Host ""
        Write-Host "=== Scale: $scale Decision Cards ==="
        Add-DecisionSeeds -TargetCount $scale -Tag $tag -RunId $runId -SeededIds $seededIds

        $cursorDecisionId = [long]$seededIds[0]
        $cacheDecisionId = [long]$seededIds[1]
        $encodedTag = Get-UrlEncoded -Value $tag

        $searchBody = @{
            query = "For a payment history list, how should I avoid slow deep offset scans while users browse sequential pages?"
            tags = @($tag)
            topK = $TopK
        }
        $search = Measure-Endpoint -Name "Decision Search" -Action {
            Invoke-DevContextApi -Method "Post" -Path "/api/decisions/search" -Body $searchBody
        }
        $rows += [pscustomobject]@{
            scale = $scale
            endpoint = $search.name
            stats = $search.stats
            metrics = [pscustomobject]@{ hitRate = ""; mrr = ""; precisionAtK = ""; recallAtK = "" }
        }

        $evaluationBody = @{
            cases = @(
                @{
                    name = "cursor recall"
                    query = "For a payment history list, how should I avoid slow deep offset scans while users browse sequential pages?"
                    tags = @($tag)
                    topK = $TopK
                    expectedDecisionIds = @($cursorDecisionId)
                },
                @{
                    name = "cache recall"
                    query = "For a product detail page, how should I reduce repeated database reads with cache?"
                    tags = @($tag)
                    topK = $TopK
                    expectedDecisionIds = @($cacheDecisionId)
                }
            )
        }
        $evaluation = Measure-Endpoint -Name "Recall Evaluation" -Action {
            Invoke-DevContextApi -Method "Post" -Path "/api/decisions/recall-evaluations" -Body $evaluationBody
        }
        $metrics = [pscustomobject]@{ hitRate = ""; mrr = ""; precisionAtK = ""; recallAtK = "" }
        if ($null -ne $evaluation.lastData) {
            $metrics = [pscustomobject]@{
                hitRate = $evaluation.lastData.hitRate
                mrr = $evaluation.lastData.meanReciprocalRank
                precisionAtK = $evaluation.lastData.averagePrecisionAtK
                recallAtK = $evaluation.lastData.averageRecallAtK
            }
        }
        $rows += [pscustomobject]@{
            scale = $scale
            endpoint = $evaluation.name
            stats = $evaluation.stats
            metrics = $metrics
        }

        $duplicate = Measure-Endpoint -Name "Duplicate Candidates" -Action {
            Invoke-DevContextApi -Method "Get" -Path "/api/decisions/duplicate-candidates?status=active&tag=$encodedTag&minScore=0.95"
        }
        $rows += [pscustomobject]@{
            scale = $scale
            endpoint = $duplicate.name
            stats = $duplicate.stats
            metrics = [pscustomobject]@{ hitRate = ""; mrr = ""; precisionAtK = ""; recallAtK = "" }
        }

        $rebuildBody = @{
            status = "active"
            tag = $tag
        }
        $rebuild = Measure-Endpoint -Name "Batch Rebuild Embeddings" -Action {
            Invoke-DevContextApi -Method "Post" -Path "/api/decisions/embeddings/rebuild" -Body $rebuildBody
        }
        $rows += [pscustomobject]@{
            scale = $scale
            endpoint = $rebuild.name
            stats = $rebuild.stats
            metrics = [pscustomobject]@{ hitRate = ""; mrr = ""; precisionAtK = ""; recallAtK = "" }
        }
    }
} finally {
    if (-not $KeepData -and $seededIds.Count -gt 0) {
        Write-Host ""
        Write-Host "Soft-deprecating $($seededIds.Count) seeded Decision Cards"
        $cleanupBody = @{
            decisionIds = @($seededIds | ForEach-Object { [long]$_ })
            status = "deprecated"
        }
        $cleanup = Invoke-DevContextApi -Method "Patch" -Path "/api/decisions/batch-status" -Body $cleanupBody
        $cleanupDone = $cleanup.Success
        if (-not $cleanup.Success) {
            Write-Warning "Cleanup failed: $($cleanup.Message)"
        }
    }
}

$report = Write-Reports -RunId $runId -Tag $tag -DecisionScales $DecisionScales -Rows $rows -SeededIds @($seededIds | ForEach-Object { [long]$_ }) -CleanupDone $cleanupDone -OutputDir $OutputDir

Write-Host ""
Write-Host "Performance baseline complete"
Write-Host "Markdown report: $($report.markdownPath)"
Write-Host "JSON report:     $($report.jsonPath)"
