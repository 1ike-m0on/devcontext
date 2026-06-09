param(
    [string]$BaseUrl = "http://localhost:18080",
    [int]$TopK = 5,
    [long]$ProjectId = 90501,
    [long]$OtherProjectId = 90502,
    [int]$TimeoutSeconds = 60,
    [string]$OutputDir = "docs/reports",
    [switch]$KeepData,
    [switch]$Help
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

if ($Help) {
    @"
DevContext decision recall quality benchmark

Usage:
  powershell -ExecutionPolicy Bypass -File scripts/devcontext-recall-quality-benchmark.ps1

Common options:
  -BaseUrl http://localhost:18080
  -TopK 5
  -ProjectId 90501
  -OtherProjectId 90502
  -OutputDir docs/reports
  -KeepData

The script seeds a small curated Decision Card set, evaluates recall quality
with HitRate, MRR, Precision@K, Recall@K, FalsePositive@K, and forbidden-hit
checks, writes Markdown and JSON reports, then soft-deprecates the seed data
unless -KeepData is provided.
"@ | Write-Host
    exit 0
}

. (Join-Path $PSScriptRoot "devcontext-report-metadata.ps1")

function New-RunId {
    return (Get-Date).ToString("yyyyMMdd-HHmmss")
}

function Invoke-DevContextApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    $uri = ($BaseUrl.TrimEnd("/") + $Path)
    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -TimeoutSec $TimeoutSeconds
    }
    $json = $Body | ConvertTo-Json -Depth 80
    return Invoke-RestMethod -Method $Method -Uri $uri -ContentType "application/json" -Body $json -TimeoutSec $TimeoutSeconds
}

function Get-LlmReportMetadata {
    try {
        $response = Invoke-DevContextApi -Method "Get" -Path "/api/settings/llm"
        if ($null -ne $response -and $response.PSObject.Properties.Name -contains "success" -and -not $response.success) {
            return New-DevContextLlmReportMetadata -MetadataError "$($response.errorCode): $($response.message)"
        }
        return New-DevContextLlmReportMetadata -Data $response.data
    } catch {
        return New-DevContextLlmReportMetadata -MetadataError $_.Exception.Message
    }
}

function New-Decision {
    param(
        [object]$ProjectScope,
        [string]$Status,
        [string]$Title,
        [string]$Scenario,
        [string]$Decision,
        [string[]]$Tags,
        [string]$RunId
    )
    $body = @{
        title = $Title
        scenario = $Scenario
        options = @("baseline alternative", "selected decision")
        decision = $Decision
        reasons = @("Recall quality benchmark seed.")
        tradeOffs = @("Seeded data should be cleaned after evaluation.")
        applicableWhen = @("The same engineering problem shape appears again.")
        notApplicableWhen = @("The product constraints invalidate the original decision.")
        outcome = "Improves Decision Reuse evaluation coverage."
        evidence = @(@{
            type = "recall-quality-benchmark"
            ref = $RunId
            summary = "Seeded by local recall quality benchmark."
        })
        status = $Status
        tags = $Tags
    }
    if ($null -ne $ProjectScope) {
        $body.projectId = [long]$ProjectScope
    }
    $response = Invoke-DevContextApi -Method "Post" -Path "/api/decisions" -Body $body
    return [long]$response.data.decisionId
}

function Write-Reports {
    param(
        [string]$RunId,
        [string]$Tag,
        [object]$Result,
        [long[]]$SeededIds,
        [bool]$CleanupDone,
        [object]$LlmMetadata,
        [string]$OutputDir
    )
    if (-not (Test-Path -LiteralPath $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir | Out-Null
    }

    $jsonPath = Join-Path $OutputDir "recall-quality-$RunId.json"
    $mdPath = Join-Path $OutputDir "recall-quality-$RunId.md"

    $payload = [pscustomobject]@{
        runId = $RunId
        tag = $Tag
        baseUrl = $BaseUrl
        projectId = $ProjectId
        otherProjectId = $OtherProjectId
        topK = $TopK
        seededDecisionIds = $SeededIds
        cleanupDone = $CleanupDone
        llm = $LlmMetadata
        generatedAt = (Get-Date).ToString("o")
        evaluation = $Result
    }
    $payload | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -LiteralPath $jsonPath

    $lines = @()
    $lines += "# DevContext Decision Recall Quality Benchmark $RunId"
    $lines += ""
    $lines += "## Environment"
    $lines += ""
    $lines += "- Base URL: ``$BaseUrl``"
    $lines += "- Seed tag: ``$Tag``"
    $lines += "- Project ID: ``$ProjectId``"
    $lines += "- Other project ID: ``$OtherProjectId``"
    $lines += "- TopK: ``$TopK``"
    $lines += "- Cleanup done: ``$CleanupDone``"
    $lines = Add-DevContextLlmReportMarkdownLines -Lines $lines -LlmMetadata $LlmMetadata
    $lines += ""
    $lines += "## Summary"
    $lines += ""
    $lines += "| CaseCount | HitRate@K | MRR | Precision@K | Recall@K | FalsePositive@K | ForbiddenPassRate |"
    $lines += "| ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    $lines += "| $($Result.caseCount) | $($Result.hitRate) | $($Result.meanReciprocalRank) | $($Result.averagePrecisionAtK) | $($Result.averageRecallAtK) | $($Result.averageFalsePositiveAtK) | $($Result.forbiddenPassRate) |"
    $lines += ""
    $lines += "## Cases"
    $lines += ""
    $lines += "| Case | Hit | Rank | Precision@K | Recall@K | FalsePositive@K | ForbiddenPass | Expected | Returned | ForbiddenHits |"
    $lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- | --- |"
    foreach ($case in $Result.cases) {
        $rank = if ($null -eq $case.firstHitRank) { "" } else { $case.firstHitRank }
        $expected = $case.expectedDecisionIds -join ", "
        $returned = $case.returnedDecisionIds -join ", "
        $forbiddenHits = $case.forbiddenHitDecisionIds -join ", "
        $lines += "| $($case.name) | $($case.hit) | $rank | $($case.precisionAtK) | $($case.recallAtK) | $($case.falsePositiveAtK) | $($case.forbiddenPass) | $expected | $returned | $forbiddenHits |"
    }
    $lines += ""
    $lines += "## Seeded Data"
    $lines += ""
    $lines += "- Seeded IDs: ``$($SeededIds -join ', ')``"
    $lines += ""
    $lines += "## Notes"
    $lines += ""
    $lines += "- This report focuses on Decision Reuse quality, not endpoint latency."
    $lines += "- ``forbiddenDecisionIds`` checks that deprecated, wrong-project, or wrong-tag decisions do not leak into TopK."
    $lines += "- ``FalsePositive@K`` counts returned decisions that are not listed as expected for a case."

    $lines | Set-Content -Encoding UTF8 -LiteralPath $mdPath
    return [pscustomobject]@{
        jsonPath = $jsonPath
        markdownPath = $mdPath
    }
}

if ($TopK -lt 1 -or $TopK -gt 20) {
    throw "TopK must be between 1 and 20"
}
if ($ProjectId -eq $OtherProjectId) {
    throw "ProjectId and OtherProjectId must be different"
}

$runId = New-RunId
$tag = "recall-quality-$runId"
$otherTag = "recall-quality-other-$runId"
$seededIds = New-Object System.Collections.ArrayList
$cleanupDone = $false
$result = $null

Write-Host "Checking DevContext health at $BaseUrl"
$health = Invoke-DevContextApi -Method "Get" -Path "/api/health"
if (-not $health.Success) {
    throw "DevContext health check failed"
}

try {
    Write-Host "Seeding recall quality Decision Cards"
    $cursorId = New-Decision `
        -ProjectScope $ProjectId `
        -Status "active" `
        -Title "Quality $runId cursor pagination decision" `
        -Scenario "Payment and order ledgers are append-heavy and slow when deep offset scans grow." `
        -Decision "Use cursor pagination with createdAt and id as a stable composite cursor." `
        -Tags @($tag, "pagination", "ledger") `
        -RunId $runId
    [void]$seededIds.Add($cursorId)

    $deprecatedCursorId = New-Decision `
        -ProjectScope $ProjectId `
        -Status "deprecated" `
        -Title "Quality $runId deprecated cursor pagination decision" `
        -Scenario "Payment and order ledgers are append-heavy and slow when deep offset scans grow." `
        -Decision "Use cursor pagination with createdAt and id as a stable composite cursor." `
        -Tags @($tag, "pagination", "ledger") `
        -RunId $runId
    [void]$seededIds.Add($deprecatedCursorId)

    $wrongProjectCursorId = New-Decision `
        -ProjectScope $OtherProjectId `
        -Status "active" `
        -Title "Quality $runId other project cursor pagination decision" `
        -Scenario "Payment and order ledgers are append-heavy and slow when deep offset scans grow." `
        -Decision "Use cursor pagination with createdAt and id as a stable composite cursor." `
        -Tags @($tag, "pagination", "ledger") `
        -RunId $runId
    [void]$seededIds.Add($wrongProjectCursorId)

    $wrongTagCursorId = New-Decision `
        -ProjectScope $ProjectId `
        -Status "active" `
        -Title "Quality $runId wrong tag cursor pagination decision" `
        -Scenario "Payment and order ledgers are append-heavy and slow when deep offset scans grow." `
        -Decision "Use cursor pagination with createdAt and id as a stable composite cursor." `
        -Tags @($otherTag, "pagination", "ledger") `
        -RunId $runId
    [void]$seededIds.Add($wrongTagCursorId)

    $cacheId = New-Decision `
        -ProjectScope $ProjectId `
        -Status "active" `
        -Title "Quality $runId product detail read-through cache decision" `
        -Scenario "Product detail pages are read-heavy and repeatedly query stable catalog snapshots." `
        -Decision "Use read-through cache with explicit invalidation for product detail snapshots." `
        -Tags @($tag, "cache", "product-detail") `
        -RunId $runId
    [void]$seededIds.Add($cacheId)

    $wrongTagCacheId = New-Decision `
        -ProjectScope $ProjectId `
        -Status "active" `
        -Title "Quality $runId wrong tag product cache decision" `
        -Scenario "Product detail pages are read-heavy and repeatedly query stable catalog snapshots." `
        -Decision "Use read-through cache with explicit invalidation for product detail snapshots." `
        -Tags @($otherTag, "cache", "product-detail") `
        -RunId $runId
    [void]$seededIds.Add($wrongTagCacheId)

    $asyncExportId = New-Decision `
        -ProjectScope $ProjectId `
        -Status "active" `
        -Title "Quality $runId async export decision" `
        -Scenario "Large report exports block request threads and make users wait for long-running jobs." `
        -Decision "Use asynchronous export jobs with polling and downloadable artifacts." `
        -Tags @($tag, "async-export", "report") `
        -RunId $runId
    [void]$seededIds.Add($asyncExportId)

    $cases = @(
        @{
            name = "cursor semantic recall without title words"
            query = "Payment history becomes slow on deep pages while users only move next and previous."
            projectId = $ProjectId
            tags = @($tag, "pagination")
            topK = $TopK
            expectedDecisionIds = @($cursorId)
            forbiddenDecisionIds = @($deprecatedCursorId, $wrongProjectCursorId, $wrongTagCursorId)
        },
        @{
            name = "cache semantic recall"
            query = "Product detail screens repeatedly read stable catalog snapshots and should reduce database hits."
            projectId = $ProjectId
            tags = @($tag, "cache")
            topK = $TopK
            expectedDecisionIds = @($cacheId)
            forbiddenDecisionIds = @($wrongTagCacheId, $asyncExportId)
        },
        @{
            name = "status filter excludes deprecated decision"
            query = "Which prior decision helps an append-heavy ledger avoid slow offset pagination?"
            projectId = $ProjectId
            tags = @($tag, "pagination")
            topK = $TopK
            expectedDecisionIds = @($cursorId)
            forbiddenDecisionIds = @($deprecatedCursorId)
        },
        @{
            name = "project scope excludes other project decision"
            query = "How should this project handle sequential ledger browsing without deep offset scans?"
            projectId = $ProjectId
            tags = @($tag, "pagination")
            topK = $TopK
            expectedDecisionIds = @($cursorId)
            forbiddenDecisionIds = @($wrongProjectCursorId)
        },
        @{
            name = "tag filter excludes same-topic wrong tag decision"
            query = "How should product detail pages avoid repeated database reads with a cache?"
            projectId = $ProjectId
            tags = @($tag, "cache")
            topK = $TopK
            expectedDecisionIds = @($cacheId)
            forbiddenDecisionIds = @($wrongTagCacheId)
        }
    )

    Write-Host "Running recall quality evaluation with $($cases.Count) cases"
    $response = Invoke-DevContextApi -Method "Post" -Path "/api/decisions/recall-evaluations" -Body @{ cases = $cases }
    $result = $response.data
} finally {
    if (-not $KeepData -and $seededIds.Count -gt 0) {
        Write-Host "Soft-deprecating $($seededIds.Count) seeded Decision Cards"
        $cleanupBody = @{
            decisionIds = @($seededIds | ForEach-Object { [long]$_ })
            status = "deprecated"
        }
        $cleanup = Invoke-DevContextApi -Method "Patch" -Path "/api/decisions/batch-status" -Body $cleanupBody
        $cleanupDone = $cleanup.Success
    }
}

if ($null -eq $result) {
    throw "Recall quality evaluation did not produce a result"
}

$report = Write-Reports `
    -RunId $runId `
    -Tag $tag `
    -Result $result `
    -SeededIds @($seededIds | ForEach-Object { [long]$_ }) `
    -CleanupDone $cleanupDone `
    -LlmMetadata (Get-LlmReportMetadata) `
    -OutputDir $OutputDir

Write-Host ""
Write-Host "Recall quality benchmark complete"
Write-Host "HitRate@K:           $($result.hitRate)"
Write-Host "MRR:                 $($result.meanReciprocalRank)"
Write-Host "Precision@K:         $($result.averagePrecisionAtK)"
Write-Host "Recall@K:            $($result.averageRecallAtK)"
Write-Host "FalsePositive@K:     $($result.averageFalsePositiveAtK)"
Write-Host "ForbiddenPassRate:   $($result.forbiddenPassRate)"
Write-Host "Markdown report:     $($report.markdownPath)"
Write-Host "JSON report:         $($report.jsonPath)"
