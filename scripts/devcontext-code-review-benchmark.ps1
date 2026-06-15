param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$CasesPath = "docs/benchmarks/code-review/code-review-benchmark-cases.json",
    [string]$ProjectRoot = "",
    [string]$Mode = "strict",
    [int]$TimeoutSeconds = 120,
    [int]$RetryCount = 1,
    [ValidateSet("all", "dev", "holdout")]
    [string]$Split = "all",
    [string]$CaseName = "",
    [int]$CaseLimit = 0,
    [string]$OutputDir = "docs/reports",
    [switch]$ListCases,
    [switch]$ReviewMemorySmoke,
    [switch]$ReviewMemoryRealLlmSmoke,
    [switch]$Help
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"
try {
    [Console]::InputEncoding = [System.Text.Encoding]::UTF8
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    $OutputEncoding = [System.Text.UTF8Encoding]::new($false)
} catch {
    # Encoding setup is best effort for older PowerShell hosts.
}

if ($Help) {
    @"
DevContext code review benchmark

Usage:
  powershell -ExecutionPolicy Bypass -File scripts/devcontext-code-review-benchmark.ps1

Common options:
  -BaseUrl http://localhost:18080
  -CasesPath docs/benchmarks/code-review/code-review-benchmark-cases.json
  -ProjectRoot target/code-review-benchmark-custom
  -Mode strict
  -CaseName "null dereference"
  -Split dev
  -ListCases
  -CaseLimit 1
  -TimeoutSeconds 90
  -RetryCount 1
  -OutputDir docs/reports
  -ReviewMemorySmoke
  -ReviewMemoryRealLlmSmoke

The script creates a temporary benchmark project, submits fixed diff fixtures to
the AI Code Review API, evaluates ReviewIssue quality, and writes Markdown/JSON
reports under docs/reports.

Use -ReviewMemorySmoke to run the focused offline Review memory signal smoke.
That smoke exercises a stateful prior false_positive feedback flow through the
Spring test harness and writes JSON/Markdown artifacts under target.

Use -ReviewMemoryRealLlmSmoke to run the explicit manual real-provider Review
memory smoke. It uses the local Gemini/DeepSeek configuration when present,
seeds one prior false_positive feedback signal, performs one later Review with
the configured provider, and writes JSON/Markdown artifacts under target. It is
not part of the default benchmark, default Maven tests, or CI path.

Use -ListCases to inspect the selected fixture set without requiring the
DevContext service or an LLM provider.
"@ | Write-Host
    exit 0
}

. (Join-Path $PSScriptRoot "devcontext-report-metadata.ps1")

function Write-LatestReviewMemorySmokeReport {
    param([string]$ReportDir)

    if (-not (Test-Path -LiteralPath $ReportDir)) {
        Write-Host "Smoke report directory was not created: $ReportDir"
        return
    }

    $latestJson = Get-ChildItem -LiteralPath $ReportDir -Filter "*.json" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    $latestMarkdown = Get-ChildItem -LiteralPath $ReportDir -Filter "*.md" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($null -ne $latestMarkdown) {
        Write-Host "Markdown smoke report: $($latestMarkdown.FullName)"
    }
    if ($null -ne $latestJson) {
        Write-Host "JSON smoke report:     $($latestJson.FullName)"
        try {
            $report = Get-Content -Raw -Encoding UTF8 -LiteralPath $latestJson.FullName | ConvertFrom-Json
            if ($report.PSObject.Properties.Name -contains "failureCategory") {
                Write-Host "Failure category:      $($report.failureCategory)"
            }
            if ($report.PSObject.Properties.Name -contains "skipped") {
                Write-Host "Skipped:               $($report.skipped)"
            }
            if ($report.PSObject.Properties.Name -contains "provider" -and $report.PSObject.Properties.Name -contains "model") {
                Write-Host "LLM provider/model:    $($report.provider)/$($report.model)"
            }
        } catch {
            Write-Host "Smoke report summary could not be parsed: $($_.Exception.Message)"
        }
    }
}

if ($ReviewMemorySmoke) {
    Write-Host "Running focused Review memory signal benchmark smoke..."
    & mvn "-Dtest=ReviewMemorySignalBenchmarkSmokeTests" test
    $smokeReportDir = Join-Path (Get-Location) "target/review-memory-signal-benchmark-smoke"
    Write-LatestReviewMemorySmokeReport -ReportDir $smokeReportDir
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
    exit 0
}

if ($ReviewMemoryRealLlmSmoke) {
    Write-Host "Running manual real-provider Review memory signal smoke..."
    & mvn "-Dtest=ReviewMemorySignalRealLlmSmokeManual" test
    $smokeExitCode = $LASTEXITCODE
    $smokeReportDir = Join-Path (Get-Location) "target/review-memory-signal-real-llm-smoke"
    Write-LatestReviewMemorySmokeReport -ReportDir $smokeReportDir
    if ($smokeExitCode -ne 0) {
        exit $smokeExitCode
    }
    exit 0
}

function New-RunId {
    return (Get-Date).ToString("yyyyMMdd-HHmmss")
}

function Resolve-WorkspacePath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return Join-Path (Get-Location) $Path
}

function As-Array {
    param([object]$Value)
    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [System.Array]) {
        return @($Value)
    }
    return @($Value)
}

function New-BuiltInCodeReviewBenchmarkCases {
    return [pscustomobject]@{
        cases = @(
            [pscustomobject]@{
                name = "builtin-no-issue-safe-refactor"
                category = "no_issue"
                split = "dev"
                diffPath = "builtin/no-issue-safe-refactor.diff"
                expectedIssues = @()
                allowedExtraIssues = @()
            },
            [pscustomobject]@{
                name = "builtin-null-handling"
                category = "null_handling"
                split = "dev"
                diffPath = "builtin/null-handling.diff"
                expectedIssues = @(
                    [pscustomobject]@{
                        category = "null_handling"
                        severity = "critical"
                        filePath = "src/main/java/com/acme/UserService.java"
                        lineHint = 7
                        semanticAnchors = @(
                            @("null", "nullable")
                            @("dereference", "getName")
                            @("findById", "repository")
                        )
                        titleAnchors = @("null", "dereference")
                        keywords = @("repository", "getName")
                    }
                )
                allowedExtraIssues = @()
            },
            [pscustomobject]@{
                name = "builtin-authorization-delete"
                category = "authorization_security"
                split = "dev"
                diffPath = "builtin/authorization-delete.diff"
                expectedIssues = @(
                    [pscustomobject]@{
                        category = "authorization_security"
                        severity = "critical"
                        filePath = "src/main/java/com/acme/ProjectController.java"
                        lineHint = 6
                        semanticAnchors = @(
                            @("authorization", "authorize", "ownership")
                            @("delete", "destructive")
                        )
                        titleAnchors = @("authorization", "delete")
                        keywords = @("project", "delete")
                    }
                )
                allowedExtraIssues = @()
            },
            [pscustomobject]@{
                name = "builtin-path-traversal"
                category = "input_validation"
                split = "dev"
                diffPath = "builtin/path-traversal.diff"
                expectedIssues = @(
                    [pscustomobject]@{
                        category = "input_validation"
                        severity = "critical"
                        filePath = "src/main/java/com/acme/FileController.java"
                        lineHint = 7
                        semanticAnchors = @(
                            @("path traversal", "traversal", "normalize")
                            @("user-controlled", "filename", "path")
                        )
                        titleAnchors = @("path", "traversal")
                        keywords = @("Files.readString", "fileName")
                    }
                )
                allowedExtraIssues = @()
            },
            [pscustomobject]@{
                name = "builtin-idempotency-retry"
                category = "idempotency_retry"
                split = "dev"
                diffPath = "builtin/idempotency-retry.diff"
                expectedIssues = @(
                    [pscustomobject]@{
                        category = "idempotency_retry"
                        severity = "warning"
                        filePath = "src/main/java/com/acme/PaymentWebhookHandler.java"
                        lineHint = 7
                        semanticAnchors = @(
                            @("idempotent", "idempotency", "deduplicate")
                            @("retry", "redelivery", "webhook")
                        )
                        titleAnchors = @("idempot", "retry")
                        keywords = @("eventId", "save")
                    }
                )
                allowedExtraIssues = @()
            },
            [pscustomobject]@{
                name = "builtin-missing-tests"
                category = "missing_tests"
                split = "dev"
                diffPath = "builtin/missing-tests.diff"
                expectedIssues = @(
                    [pscustomobject]@{
                        category = "missing_tests"
                        severity = "warning"
                        filePath = "src/main/java/com/acme/DiscountService.java"
                        lineHint = 6
                        lineRequired = $false
                        semanticAnchors = @(
                            @("test", "tests", "coverage")
                            @("discount", "business rule", "boundary")
                        )
                        titleAnchors = @("test", "coverage")
                        keywords = @("discount")
                    }
                )
                allowedExtraIssues = @()
            }
        )
    }
}

function Get-BuiltInCodeReviewBenchmarkFixtures {
    return @(
        [pscustomobject]@{
            path = "builtin/no-issue-safe-refactor.diff"
            content = @'
diff --git a/src/main/java/com/acme/UserService.java b/src/main/java/com/acme/UserService.java
--- a/src/main/java/com/acme/UserService.java
+++ b/src/main/java/com/acme/UserService.java
@@ -2,7 +2,8 @@ package com.acme;

 class UserService {
     String userName(Long id) {
-        return "benchmark";
+        String fallback = "benchmark";
+        return fallback;
     }
 }
'@
        },
        [pscustomobject]@{
            path = "builtin/null-handling.diff"
            content = @'
diff --git a/src/main/java/com/acme/UserService.java b/src/main/java/com/acme/UserService.java
--- a/src/main/java/com/acme/UserService.java
+++ b/src/main/java/com/acme/UserService.java
@@ -2,6 +2,8 @@ package com.acme;

 class UserService {
     String userName(Long id) {
+        User user = userRepository.findById(id);
+        return user.getName();
         return "benchmark";
     }
 }
'@
        },
        [pscustomobject]@{
            path = "builtin/authorization-delete.diff"
            content = @'
diff --git a/src/main/java/com/acme/ProjectController.java b/src/main/java/com/acme/ProjectController.java
--- a/src/main/java/com/acme/ProjectController.java
+++ b/src/main/java/com/acme/ProjectController.java
@@ -1,5 +1,8 @@
 package com.acme;

 class ProjectController {
+    @DeleteMapping("/api/projects/{projectId}")
+    void deleteProject(Long projectId) {
+        projectService.delete(projectId);
+    }
 }
'@
        },
        [pscustomobject]@{
            path = "builtin/path-traversal.diff"
            content = @'
diff --git a/src/main/java/com/acme/FileController.java b/src/main/java/com/acme/FileController.java
--- a/src/main/java/com/acme/FileController.java
+++ b/src/main/java/com/acme/FileController.java
@@ -1,5 +1,9 @@
 package com.acme;

 class FileController {
+    String read(String fileName) throws Exception {
+        Path target = Path.of(baseDir, fileName);
+        return Files.readString(target);
+    }
 }
'@
        },
        [pscustomobject]@{
            path = "builtin/idempotency-retry.diff"
            content = @'
diff --git a/src/main/java/com/acme/PaymentWebhookHandler.java b/src/main/java/com/acme/PaymentWebhookHandler.java
--- a/src/main/java/com/acme/PaymentWebhookHandler.java
+++ b/src/main/java/com/acme/PaymentWebhookHandler.java
@@ -1,5 +1,9 @@
 package com.acme;

 class PaymentWebhookHandler {
+    void handle(WebhookEvent event) {
+        paymentProcessor.capture(event);
+        paymentRepository.save(new Payment(event.eventId()));
+    }
 }
'@
        },
        [pscustomobject]@{
            path = "builtin/missing-tests.diff"
            content = @'
diff --git a/src/main/java/com/acme/DiscountService.java b/src/main/java/com/acme/DiscountService.java
--- a/src/main/java/com/acme/DiscountService.java
+++ b/src/main/java/com/acme/DiscountService.java
@@ -1,7 +1,7 @@
 package com.acme;

 class DiscountService {
     int discountPercent(Customer customer) {
-        return customer.isVip() ? 10 : 0;
+        return customer.isVip() ? 25 : 5;
     }
 }
'@
        }
    )
}

function Write-BuiltInCodeReviewBenchmarkFixtures {
    param([string]$Root)
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    foreach ($fixture in Get-BuiltInCodeReviewBenchmarkFixtures) {
        $target = Join-Path $Root $fixture.path
        $targetDir = Split-Path -Parent $target
        if (-not (Test-Path -LiteralPath $targetDir)) {
            New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
        }
        Set-Content -Encoding UTF8 -LiteralPath $target -Value $fixture.content
    }
    return $Root
}

function Get-ObjectPropertyValue {
    param(
        [object]$Object,
        [string]$Name,
        [object]$Default = $null
    )
    if ($null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name) {
        return $Object.$Name
    }
    return $Default
}

function Normalize-ReviewContextCoverage {
    param([object]$Coverage)

    if ($null -eq $Coverage) {
        return [pscustomobject]@{
            sourceCount = 0
            totalTokenEstimate = 0
            sourceTypes = @()
            sources = @()
            reviewRules = $false
            projectProfile = $false
            projectGraph = $false
            reviewMemorySignals = $false
            decisionMemory = $false
        }
    }

    $sourceTypesValue = Get-ObjectPropertyValue -Object $Coverage -Name "sourceTypes" -Default @()
    $sourceTypes = @(As-Array $sourceTypesValue | ForEach-Object { [string]$_ })
    $sourcesValue = Get-ObjectPropertyValue -Object $Coverage -Name "sources" -Default @()
    $sources = @()
    foreach ($source in As-Array $sourcesValue) {
        $sources += [pscustomobject]@{
            type = [string](Get-ObjectPropertyValue -Object $source -Name "type" -Default "")
            title = [string](Get-ObjectPropertyValue -Object $source -Name "title" -Default "")
            source = [string](Get-ObjectPropertyValue -Object $source -Name "source" -Default "")
            priority = [int](Get-ObjectPropertyValue -Object $source -Name "priority" -Default 0)
            tokenEstimate = [int](Get-ObjectPropertyValue -Object $source -Name "tokenEstimate" -Default 0)
        }
    }

    return [pscustomobject]@{
        sourceCount = [int](Get-ObjectPropertyValue -Object $Coverage -Name "sourceCount" -Default $sources.Count)
        totalTokenEstimate = [int](Get-ObjectPropertyValue -Object $Coverage -Name "totalTokenEstimate" -Default 0)
        sourceTypes = $sourceTypes
        sources = $sources
        reviewRules = [bool](Get-ObjectPropertyValue -Object $Coverage -Name "reviewRules" -Default $false)
        projectProfile = [bool](Get-ObjectPropertyValue -Object $Coverage -Name "projectProfile" -Default $false)
        projectGraph = [bool](Get-ObjectPropertyValue -Object $Coverage -Name "projectGraph" -Default $false)
        reviewMemorySignals = [bool](Get-ObjectPropertyValue -Object $Coverage -Name "reviewMemorySignals" -Default $false)
        decisionMemory = [bool](Get-ObjectPropertyValue -Object $Coverage -Name "decisionMemory" -Default $false)
    }
}

function Get-ContextCoverageFlagCount {
    param(
        [object[]]$Cases,
        [string]$Property
    )
    return @($Cases | Where-Object {
            $coverage = Get-ObjectPropertyValue -Object $_ -Name "contextCoverage" -Default $null
            $null -ne $coverage -and [bool](Get-ObjectPropertyValue -Object $coverage -Name $Property -Default $false)
        }).Count
}

function Get-CaseSplit {
    param([object]$Case)
    if ($null -ne $Case -and $Case.PSObject.Properties.Name -contains "split" -and -not [string]::IsNullOrWhiteSpace([string]$Case.split)) {
        return [string]$Case.split
    }
    return "dev"
}

function Get-Sum {
    param(
        [object[]]$Items,
        [string]$Property
    )
    $sum = 0.0
    foreach ($item in @($Items)) {
        if ($null -eq $item -or -not ($item.PSObject.Properties.Name -contains $Property)) {
            continue
        }
        $value = $item.$Property
        if ($null -eq $value) {
            continue
        }
        $sum += [double]$value
    }
    return $sum
}

function Get-Average {
    param(
        [object[]]$Items,
        [string]$Property
    )
    $values = @()
    foreach ($item in @($Items)) {
        if ($null -eq $item -or -not ($item.PSObject.Properties.Name -contains $Property)) {
            continue
        }
        $value = $item.$Property
        if ($null -ne $value) {
            $values += [double]$value
        }
    }
    if ($values.Count -eq 0) {
        return 0
    }
    $sum = 0.0
    foreach ($value in $values) {
        $sum += [double]$value
    }
    return $sum / $values.Count
}

function Get-FailureType {
    param([string]$Message)
    if ([string]::IsNullOrWhiteSpace($Message)) {
        return "unknown"
    }
    $normalized = $Message.ToLowerInvariant()
    if ($normalized.Contains("timed out") -or $normalized.Contains("timeout")) {
        return "llm_timeout"
    }
    if ($normalized.Contains("llm_auth_failed") -or $normalized.Contains("authentication fails") -or $normalized.Contains("invalid") -or $normalized.Contains("api key")) {
        return "llm_auth_failed"
    }
    if ($normalized.Contains("llm_call_failed") -or $normalized.Contains("http 502")) {
        return "llm_call_failed"
    }
    if ($normalized.Contains("curl")) {
        return "http_client"
    }
    if ($normalized.Contains("java")) {
        return "review_helper"
    }
    return "unknown"
}

function Invoke-DevContextApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    $uri = ($BaseUrl.TrimEnd("/") + $Path)
    $methodName = $Method.Trim().ToUpperInvariant()
    $bodyFile = $null
    $responseFile = $null
    try {
        $responseFile = [System.IO.Path]::GetTempFileName()
        if ($null -eq $Body) {
            & curl.exe -sS -X $methodName -H "Accept: application/json" -o $responseFile $uri
        } else {
            $json = $Body | ConvertTo-Json -Depth 100
            $bodyFile = [System.IO.Path]::GetTempFileName()
            Set-Content -Encoding UTF8 -LiteralPath $bodyFile -Value $json
            & curl.exe -sS -X $methodName -H "Accept: application/json" -H "Content-Type: application/json" --data-binary "@$bodyFile" -o $responseFile $uri
        }

        if ($LASTEXITCODE -ne 0) {
            throw "curl HTTP request failed with exit code $LASTEXITCODE"
        }
        $responseText = Get-Content -Raw -Encoding UTF8 -LiteralPath $responseFile
        if ([string]::IsNullOrWhiteSpace($responseText)) {
            return $null
        }
        $response = $responseText | ConvertFrom-Json
        if ($response.PSObject.Properties.Name -contains "success" -and -not $response.success) {
            throw "$($response.errorCode): $($response.message)"
        }
        return $response
    } finally {
        if ($null -ne $bodyFile) {
            Remove-Item -LiteralPath $bodyFile -Force -ErrorAction SilentlyContinue
        }
        if ($null -ne $responseFile) {
            Remove-Item -LiteralPath $responseFile -Force -ErrorAction SilentlyContinue
        }
    }
}

function Get-LlmReportMetadata {
    try {
        $response = Invoke-DevContextApi -Method "Get" -Path "/api/settings/llm"
        return New-DevContextLlmReportMetadata -Data $response.data
    } catch {
        return New-DevContextLlmReportMetadata -MetadataError $_.Exception.Message
    }
}

function Invoke-DevContextReviewApi {
    param(
        [long]$ProjectId,
        [string]$CaseName,
        [string]$DiffPath
    )
    $uri = ($BaseUrl.TrimEnd("/") + "/api/projects/$ProjectId/reviews")
    $helperPath = Resolve-WorkspacePath "scripts/DevContextHttpClient.java"
    $compareBranch = "benchmark/$CaseName"
    $responseFile = [System.IO.Path]::GetTempFileName()
    try {
        $responseText = & java $helperPath "REVIEW" $uri ([string]$TimeoutSeconds) "main" $compareBranch $Mode $DiffPath $responseFile 2>&1

        if ($LASTEXITCODE -ne 0) {
            $errorText = ($responseText -join [Environment]::NewLine).Trim()
            if ([string]::IsNullOrWhiteSpace($errorText)) {
                $errorText = "java review helper failed with exit code $LASTEXITCODE"
            }
            throw $errorText
        }
        $responseBody = Get-Content -Raw -Encoding UTF8 -LiteralPath $responseFile
        if ([string]::IsNullOrWhiteSpace($responseBody)) {
            return $null
        }
        $response = $responseBody | ConvertFrom-Json
        if ($response.PSObject.Properties.Name -contains "success" -and -not $response.success) {
            throw "$($response.errorCode): $($response.message)"
        }
        return $response
    } finally {
        Remove-Item -LiteralPath $responseFile -Force -ErrorAction SilentlyContinue
    }
}

function Initialize-BenchmarkProject {
    param(
        [string]$Root,
        [string]$RunId
    )
    New-Item -ItemType Directory -Path $Root -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Root ".ai/manual") -Force | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $Root "src/main/java/com/acme") -Force | Out-Null

    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $Root "pom.xml") -Value "<project><modelVersion>4.0.0</modelVersion><artifactId>code-review-benchmark</artifactId></project>"
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $Root "AGENTS.md") -Value @"
# Code Review Benchmark Project

Review Java backend diffs carefully. Pay attention to null handling,
transaction boundaries, idempotency, security, and missing tests.
"@
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $Root ".ai/manual/coding-preferences.md") -Value @"
# Coding Preferences

- Prefer explicit null handling.
- Use transaction boundaries for multi-write business operations.
- Webhook handlers must be idempotent.
- Never trust user-controlled file paths without normalization and root checks.
- Business rule changes require boundary tests.
- If production business logic changes and no test file changes are present,
  report the missing focused tests as a warning ReviewIssue.
"@
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $Root "src/main/java/com/acme/UserService.java") -Value @"
package com.acme;

class UserService {
    String userName(Long id) {
        return "benchmark";
    }
}
"@
    Set-Content -Encoding UTF8 -LiteralPath (Join-Path $Root ".ai/code-map.json") -Value @"
{
  "generatedAt": "$((Get-Date).ToString("o"))",
  "projectName": "code-review-benchmark-$RunId",
  "rootPath": "$($Root -replace '\\', '\\')",
  "language": "Java",
  "framework": "Spring Boot",
  "buildTool": "Maven",
  "gitBranch": "main",
  "gitCommit": "benchmark",
  "modules": [
    {
      "name": "user",
      "path": "src/main/java/com/acme",
      "classes": ["UserService"],
      "responsibility": "benchmark user workflow"
    }
  ],
  "entrypoints": [
    {
      "type": "service",
      "file": "src/main/java/com/acme/UserService.java",
      "methods": ["userName"]
    }
  ],
  "symbols": [
    {
      "name": "UserService",
      "role": "service",
      "module": "user",
      "file": "src/main/java/com/acme/UserService.java",
      "methods": ["userName"],
      "endpoints": [],
      "dependencies": [],
      "technologies": ["Java"],
      "domainTerms": ["user"]
    }
  ],
  "endpoints": [],
  "dependencies": [],
  "technologies": [
    {
      "technology": "Spring Boot",
      "classes": ["UserService"],
      "files": ["src/main/java/com/acme/UserService.java"]
    }
  ],
  "runtimeComponents": [],
  "domainTerms": [],
  "commands": {
    "test": "mvn test"
  },
  "configs": ["pom.xml"],
  "testRoots": ["src/test/java"],
  "docs": ["AGENTS.md", ".ai/manual/coding-preferences.md"],
  "todos": []
}
"@
}

function New-BenchmarkProject {
    param(
        [string]$Root,
        [string]$RunId
    )
    $resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
    $response = Invoke-DevContextApi -Method "Post" -Path "/api/projects" -Body @{
        name = "code-review-benchmark-$RunId"
        rootPath = $resolvedRoot
        defaultBranch = "main"
    }
    return $response.data
}

function Initialize-BenchmarkContextSources {
    param([object]$Project)
    try {
        $profile = Invoke-DevContextApi -Method "Get" -Path "/api/projects/$($Project.id)/profile"
        Write-Host "ProjectProfile context warmed: status=$($profile.data.status)"
    } catch {
        Write-Warning "ProjectProfile context warmup failed: $($_.Exception.Message)"
    }
    try {
        $graph = Invoke-DevContextApi -Method "Get" -Path "/api/projects/$($Project.id)/graph"
        Write-Host "ProjectGraph context status: $($graph.data.status), nodes=$($graph.data.nodeCount), edges=$($graph.data.edgeCount)"
    } catch {
        Write-Warning "ProjectGraph context inspection failed: $($_.Exception.Message)"
    }
}

function Normalize-MatchText {
    param([string]$Value)
    if ([string]::IsNullOrWhiteSpace($Value)) {
        return ""
    }
    $text = $Value.ToLowerInvariant()
    $text = $text -replace "idempotency|idempotent|idempotence", "idempot"
    $text = $text -replace "deduplication|deduplicated|deduplicating", "deduplicate"
    $text = $text -replace "redeliveries|redelivered|redelivery|redelivering", "redeliver"
    $text = $text -replace "repeated deliveries|repeated delivery", "redeliver"
    $text = $text -replace "n\s*\+\s*1|n plus one", "n+1"
    $text = $text -replace "round-trip|round trip", "roundtrip"
    return $text
}

function Get-IssueText {
    param([object]$Issue)
    return Normalize-MatchText ((@(
        $Issue.title,
        $Issue.description,
        $Issue.impact,
        $Issue.suggestion,
        $Issue.confidence,
        $Issue.severity,
        $Issue.filePath
    ) -join " "))
}

function Get-IssueMainText {
    param([object]$Issue)
    return Normalize-MatchText ((@(
        $Issue.title,
        $Issue.description,
        $Issue.filePath
    ) -join " "))
}

function Get-IssueTitleText {
    param([object]$Issue)
    if ($null -eq $Issue -or -not ($Issue.PSObject.Properties.Name -contains "title") -or $null -eq $Issue.title) {
        return ""
    }
    return Normalize-MatchText ([string]$Issue.title)
}

function Test-TextContainsTerm {
    param(
        [string]$Text,
        [string]$Term
    )
    if ([string]::IsNullOrWhiteSpace($Term)) {
        return $false
    }
    $needle = Normalize-MatchText $Term
    if ([string]::IsNullOrWhiteSpace($needle)) {
        return $false
    }
    return $Text.Contains($needle)
}

function Get-IssueKey {
    param(
        [object]$Issue,
        [int]$Index
    )
    if ($Issue.PSObject.Properties.Name -contains "id" -and $null -ne $Issue.id) {
        return [string]$Issue.id
    }
    return "index:$Index"
}

function Test-LineRequired {
    param([object]$Expected)
    if ($Expected.PSObject.Properties.Name -contains "lineRequired" -and $false -eq [bool]$Expected.lineRequired) {
        return $false
    }
    return $true
}

function Get-LineMatch {
    param(
        [object]$Issue,
        [object]$Expected
    )
    if (-not (Test-LineRequired -Expected $Expected)) {
        return $true
    }
    if ($null -eq $Expected.lineHint) {
        return $false
    }
    if (-not ($Issue.PSObject.Properties.Name -contains "lineNumber") -or $null -eq $Issue.lineNumber) {
        return $false
    }
    $tolerance = if ($null -eq $Expected.lineTolerance) { 5 } else { [int]$Expected.lineTolerance }
    return ([math]::Abs([int]$Issue.lineNumber - [int]$Expected.lineHint) -le $tolerance)
}

function Get-KeywordMatches {
    param(
        [object]$Issue,
        [object]$Expected,
        [string]$Property = "keywords"
    )
    if (-not ($Expected.PSObject.Properties.Name -contains $Property)) {
        return @()
    }
    $issueText = if ($Property -eq "forbiddenKeywords") { Get-IssueMainText -Issue $Issue } else { Get-IssueText -Issue $Issue }
    $matches = @()
    foreach ($keyword in As-Array $Expected.$Property) {
        $needle = [string]$keyword
        if (Test-TextContainsTerm -Text $issueText -Term $needle) {
            $matches += $needle
        }
    }
    return $matches
}

function Get-TitleAnchorMatches {
    param(
        [object]$Issue,
        [object]$Expected
    )
    if (-not ($Expected.PSObject.Properties.Name -contains "titleAnchors")) {
        return @()
    }
    $titleText = Get-IssueTitleText -Issue $Issue
    $matches = @()
    foreach ($group in @($Expected.titleAnchors)) {
        $groupMatches = @()
        if ($group -is [System.Array]) {
            foreach ($keyword in @($group)) {
                $needle = [string]$keyword
                if (Test-TextContainsTerm -Text $titleText -Term $needle) {
                    $groupMatches += $needle
                }
            }
        } else {
            $needle = [string]$group
            if (Test-TextContainsTerm -Text $titleText -Term $needle) {
                $groupMatches += $needle
            }
        }
        if ($groupMatches.Count -gt 0) {
            $matches += ($groupMatches -join "|")
        }
    }
    return $matches
}

function Get-RequiredAnchorGroups {
    param([object]$Expected)
    $groups = New-Object System.Collections.Generic.List[object]

    if ($Expected.PSObject.Properties.Name -contains "semanticAnchors") {
        foreach ($group in @($Expected.semanticAnchors)) {
            $terms = @()
            if ($group -is [System.Array]) {
                foreach ($term in @($group)) {
                    $needle = [string]$term
                    if (-not [string]::IsNullOrWhiteSpace($needle)) {
                        $terms += $needle
                    }
                }
            } else {
                $needle = [string]$group
                if (-not [string]::IsNullOrWhiteSpace($needle)) {
                    $terms += $needle
                }
            }
            if ($terms.Count -gt 0) {
                [void]$groups.Add($terms)
            }
        }
    } elseif ($Expected.PSObject.Properties.Name -contains "mustKeywords") {
        foreach ($keyword in As-Array $Expected.mustKeywords) {
            $needle = [string]$keyword
            if (-not [string]::IsNullOrWhiteSpace($needle)) {
                [void]$groups.Add(@($needle))
            }
        }
    }

    return @($groups.ToArray())
}

function Get-RequiredAnchorMatches {
    param(
        [object]$Issue,
        [object]$Expected
    )
    $issueText = Get-IssueText -Issue $Issue
    $matches = @()
    foreach ($group in Get-RequiredAnchorGroups -Expected $Expected) {
        $groupMatches = @()
        foreach ($keyword in @($group)) {
            $needle = [string]$keyword
            if (Test-TextContainsTerm -Text $issueText -Term $needle) {
                $groupMatches += $needle
            }
        }
        if ($groupMatches.Count -gt 0) {
            $matches += ($groupMatches -join "|")
        }
    }
    return $matches
}

function Get-RequiredAnchorMisses {
    param(
        [object]$Issue,
        [object]$Expected
    )
    $issueText = Get-IssueText -Issue $Issue
    $misses = @()
    foreach ($group in Get-RequiredAnchorGroups -Expected $Expected) {
        $groupMatches = @()
        $groupTerms = @()
        foreach ($keyword in @($group)) {
            $needle = [string]$keyword
            if ([string]::IsNullOrWhiteSpace($needle)) {
                continue
            }
            $groupTerms += $needle
            if (Test-TextContainsTerm -Text $issueText -Term $needle) {
                $groupMatches += $needle
            }
        }
        if ($groupTerms.Count -gt 0 -and $groupMatches.Count -eq 0) {
            $misses += ($groupTerms -join "|")
        }
    }
    return $misses
}

function Get-HardAnchorTerms {
    param([object]$Expected)
    $terms = @()
    foreach ($group in Get-RequiredAnchorGroups -Expected $Expected) {
        foreach ($keyword in @($group)) {
            $needle = [string]$keyword
            if (-not [string]::IsNullOrWhiteSpace($needle)) {
                $terms += $needle
                break
            }
        }
    }
    return $terms
}

function Get-HardKeywordMisses {
    param(
        [object]$Issue,
        [object]$Expected
    )
    $issueText = Get-IssueText -Issue $Issue
    $misses = @()
    foreach ($keyword in @(Get-HardAnchorTerms -Expected $Expected)) {
        $needle = [string]$keyword
        if (-not (Test-TextContainsTerm -Text $issueText -Term $needle)) {
            $misses += $needle
        }
    }
    foreach ($keyword in As-Array $Expected.keywords) {
        $needle = [string]$keyword
        if (-not (Test-TextContainsTerm -Text $issueText -Term $needle)) {
            $misses += $needle
        }
    }
    return $misses
}

function Test-HardIssueMatch {
    param(
        [object]$Issue,
        [object]$Expected
    )
    $hasExpectedFilePath = (($Expected.PSObject.Properties.Name -contains "filePath") -and -not [string]::IsNullOrWhiteSpace([string]$Expected.filePath))
    if ($hasExpectedFilePath -and $Issue.filePath -ne $Expected.filePath) {
        return $false
    }
    if (-not (Get-LineMatch -Issue $Issue -Expected $Expected)) {
        return $false
    }
    $hasExpectedSeverity = (($Expected.PSObject.Properties.Name -contains "severity") -and -not [string]::IsNullOrWhiteSpace([string]$Expected.severity))
    if ($hasExpectedSeverity -and $Issue.severity -ne $Expected.severity) {
        return $false
    }
    if (@(Get-KeywordMatches -Issue $Issue -Expected $Expected -Property "forbiddenKeywords").Count -gt 0) {
        return $false
    }
    return (@(Get-HardKeywordMisses -Issue $Issue -Expected $Expected).Count -eq 0)
}

function Get-BestIssueMatch {
    param(
        [object]$Expected,
        [object[]]$Issues,
        [System.Collections.Generic.HashSet[string]]$UsedIssueKeys
    )
    $best = $null
    for ($i = 0; $i -lt $Issues.Count; $i++) {
        $issue = $Issues[$i]
        $issueKey = Get-IssueKey -Issue $issue -Index $i
        if ($UsedIssueKeys.Contains($issueKey)) {
            continue
        }

        $hasExpectedFilePath = (($Expected.PSObject.Properties.Name -contains "filePath") -and -not [string]::IsNullOrWhiteSpace([string]$Expected.filePath))
        $fileMatch = (-not $hasExpectedFilePath -or $issue.filePath -eq $Expected.filePath)
        if ($hasExpectedFilePath -and -not $fileMatch) {
            continue
        }

        $requiredAnchorMatches = @(Get-RequiredAnchorMatches -Issue $issue -Expected $Expected)
        $requiredAnchorMisses = @(Get-RequiredAnchorMisses -Issue $issue -Expected $Expected)
        if ($requiredAnchorMisses.Count -gt 0) {
            continue
        }

        $forbiddenKeywordMatches = @(Get-KeywordMatches -Issue $issue -Expected $Expected -Property "forbiddenKeywords")
        if ($forbiddenKeywordMatches.Count -gt 0) {
            continue
        }

        $hasExpectedSeverity = (($Expected.PSObject.Properties.Name -contains "severity") -and -not [string]::IsNullOrWhiteSpace([string]$Expected.severity))
        $severityMatch = ($hasExpectedSeverity -and $issue.severity -eq $Expected.severity)
        $lineMatch = Get-LineMatch -Issue $issue -Expected $Expected
        $keywordMatches = @(Get-KeywordMatches -Issue $issue -Expected $Expected -Property "keywords")
        $titleAnchorMatches = @(Get-TitleAnchorMatches -Issue $issue -Expected $Expected)
        $hardKeywordMisses = @(Get-HardKeywordMisses -Issue $issue -Expected $Expected)
        $hardMatch = Test-HardIssueMatch -Issue $issue -Expected $Expected

        $score = 0.0
        if ($fileMatch -and $hasExpectedFilePath) { $score += 0.25 }
        if ($requiredAnchorMatches.Count -gt 0) { $score += 0.35 }
        if ($titleAnchorMatches.Count -gt 0) { $score += [math]::Min(0.20, 0.10 * $titleAnchorMatches.Count) }
        if ($keywordMatches.Count -gt 0) { $score += [math]::Min(0.20, 0.05 * $keywordMatches.Count) }
        if ($lineMatch) { $score += 0.15 }
        if ($severityMatch -and $hasExpectedSeverity) { $score += 0.05 }

        $candidate = [pscustomobject]@{
            score = [math]::Round($score, 3)
            issue = $issue
            issueKey = $issueKey
            fileMatch = $fileMatch
            severityMatch = $severityMatch
            lineMatch = $lineMatch
            requiredAnchorMatches = $requiredAnchorMatches
            requiredAnchorMisses = $requiredAnchorMisses
            requiredKeywordMatches = $requiredAnchorMatches
            titleAnchorMatches = $titleAnchorMatches
            forbiddenKeywordMatches = $forbiddenKeywordMatches
            keywordMatches = $keywordMatches
            hardMatch = $hardMatch
            hardKeywordMisses = $hardKeywordMisses
            matchMode = if ($hardMatch) { "hard" } else { "semantic_rescue" }
        }
        if ($null -eq $best -or $candidate.score -gt $best.score) {
            $best = $candidate
        }
    }
    if ($null -ne $best -and $best.score -ge 0.55) {
        return $best
    }
    return $null
}

function Evaluate-ReviewCase {
    param(
        [object]$Case,
        [object]$Project,
        [string]$CasesDir
    )
    $diffPath = Resolve-WorkspacePath (Join-Path $CasesDir $Case.diffPath)
    $requiredEvents = @(
        "RUN_STARTED",
        "GIT_DIFF_COLLECTED",
        "PROJECT_CONTEXT_LOADED",
        "PROMPT_BUILT",
        "LLM_CALL_STARTED",
        "LLM_CALLED",
        "LLM_RESPONSE_PARSED",
        "REVIEW_ISSUES_SAVED",
        "RUN_FINISHED"
    )
    $attemptResults = @()

    try {
        $create = $null
        $maxAttempts = [math]::Max(1, $RetryCount + 1)
        for ($attempt = 1; $attempt -le $maxAttempts; $attempt++) {
            if ($attempt -eq 1) {
                Write-Host "  Submitting review request..."
            } else {
                Write-Host "  Retrying review request ($attempt/$maxAttempts)..."
            }
            try {
                $create = Invoke-DevContextReviewApi -ProjectId ([long]$Project.id) -CaseName $Case.name -DiffPath $diffPath
                $attemptResults += [pscustomobject]@{
                    attempt = $attempt
                    success = $true
                    failureType = $null
                    errorMessage = $null
                }
                break
            } catch {
                $errorText = $_.Exception.Message
                $attemptResults += [pscustomobject]@{
                    attempt = $attempt
                    success = $false
                    failureType = Get-FailureType -Message $errorText
                    errorMessage = $errorText
                }
                if ($attempt -ge $maxAttempts) {
                    throw
                }
                Start-Sleep -Seconds ([math]::Min(5, $attempt * 2))
            }
        }
        $reviewId = [long]$create.data.reviewId
        Write-Host "  Review created: reviewId=$reviewId, runId=$($create.data.runId)"
        Write-Host "  Fetching review detail and events..."
        $detail = Invoke-DevContextApi -Method "Get" -Path "/api/reviews/$reviewId"
        $eventsResponse = Invoke-DevContextApi -Method "Get" -Path "/api/reviews/$reviewId/events"

        $issues = @(As-Array $detail.data.issues)
        $expectedIssues = @(As-Array $Case.expectedIssues)
        $allowedExtraIssues = @()
        if ($Case.PSObject.Properties.Name -contains "allowedExtraIssues") {
            $allowedExtraIssues = @(As-Array $Case.allowedExtraIssues)
        }
        $usedIssueKeys = New-Object System.Collections.Generic.HashSet[string]
        $expectedResults = @()
        $allowedExtraResults = @()
        $hitCount = 0
        $allowedExtraHitCount = 0
        $lineHitCount = 0
        $hardExpectedHitCount = 0
        $semanticExpectedRescueCount = 0
        $hardAllowedExtraHitCount = 0
        $semanticAllowedExtraRescueCount = 0

        foreach ($expected in $expectedIssues) {
            $match = Get-BestIssueMatch -Expected $expected -Issues $issues -UsedIssueKeys $usedIssueKeys
            if ($null -ne $match) {
                [void]$usedIssueKeys.Add($match.issueKey)
                $hitCount++
                if ($match.lineMatch) {
                    $lineHitCount++
                }
                if ($match.hardMatch) {
                    $hardExpectedHitCount++
                } else {
                    $semanticExpectedRescueCount++
                }
                $expectedResults += [pscustomobject]@{
                    category = $expected.category
                    expectedSeverity = $expected.severity
                    expectedFilePath = $expected.filePath
                    lineHint = $expected.lineHint
                    hit = $true
                    matchScore = $match.score
                    matchedIssueId = $match.issue.id
                    matchedTitle = $match.issue.title
                    matchedSeverity = $match.issue.severity
                    matchedFilePath = $match.issue.filePath
                    matchedLineNumber = $match.issue.lineNumber
                    fileMatch = $match.fileMatch
                    severityMatch = $match.severityMatch
                    lineMatch = $match.lineMatch
                    requiredAnchorMatches = $match.requiredAnchorMatches
                    requiredAnchorMisses = $match.requiredAnchorMisses
                    requiredKeywordMatches = $match.requiredKeywordMatches
                    titleAnchorMatches = $match.titleAnchorMatches
                    forbiddenKeywordMatches = $match.forbiddenKeywordMatches
                    keywordMatches = $match.keywordMatches
                    hardMatch = $match.hardMatch
                    hardKeywordMisses = $match.hardKeywordMisses
                    matchMode = $match.matchMode
                }
            } else {
                $expectedResults += [pscustomobject]@{
                    category = $expected.category
                    expectedSeverity = $expected.severity
                    expectedFilePath = $expected.filePath
                    lineHint = $expected.lineHint
                    hit = $false
                    matchScore = 0
                    matchedIssueId = $null
                    matchedTitle = $null
                    matchedSeverity = $null
                    matchedFilePath = $null
                    matchedLineNumber = $null
                    fileMatch = $false
                    severityMatch = $false
                    lineMatch = $false
                    requiredAnchorMatches = @()
                    requiredAnchorMisses = @()
                    requiredKeywordMatches = @()
                    titleAnchorMatches = @()
                    forbiddenKeywordMatches = @()
                    keywordMatches = @()
                    hardMatch = $false
                    hardKeywordMisses = @()
                    matchMode = "miss"
                }
            }
        }

        foreach ($allowed in $allowedExtraIssues) {
            $match = Get-BestIssueMatch -Expected $allowed -Issues $issues -UsedIssueKeys $usedIssueKeys
            if ($null -ne $match) {
                [void]$usedIssueKeys.Add($match.issueKey)
                $allowedExtraHitCount++
                if ($match.hardMatch) {
                    $hardAllowedExtraHitCount++
                } else {
                    $semanticAllowedExtraRescueCount++
                }
                $allowedExtraResults += [pscustomobject]@{
                    category = $allowed.category
                    expectedSeverity = if ($allowed.PSObject.Properties.Name -contains "severity") { $allowed.severity } else { $null }
                    expectedFilePath = if ($allowed.PSObject.Properties.Name -contains "filePath") { $allowed.filePath } else { $null }
                    lineHint = if ($allowed.PSObject.Properties.Name -contains "lineHint") { $allowed.lineHint } else { $null }
                    hit = $true
                    matchScore = $match.score
                    matchedIssueId = $match.issue.id
                    matchedTitle = $match.issue.title
                    matchedSeverity = $match.issue.severity
                    matchedFilePath = $match.issue.filePath
                    matchedLineNumber = $match.issue.lineNumber
                    fileMatch = $match.fileMatch
                    severityMatch = $match.severityMatch
                    lineMatch = $match.lineMatch
                    requiredAnchorMatches = $match.requiredAnchorMatches
                    requiredAnchorMisses = $match.requiredAnchorMisses
                    requiredKeywordMatches = $match.requiredKeywordMatches
                    titleAnchorMatches = $match.titleAnchorMatches
                    forbiddenKeywordMatches = $match.forbiddenKeywordMatches
                    keywordMatches = $match.keywordMatches
                    hardMatch = $match.hardMatch
                    hardKeywordMisses = $match.hardKeywordMisses
                    matchMode = $match.matchMode
                }
            } else {
                $allowedExtraResults += [pscustomobject]@{
                    category = $allowed.category
                    expectedSeverity = if ($allowed.PSObject.Properties.Name -contains "severity") { $allowed.severity } else { $null }
                    expectedFilePath = if ($allowed.PSObject.Properties.Name -contains "filePath") { $allowed.filePath } else { $null }
                    lineHint = if ($allowed.PSObject.Properties.Name -contains "lineHint") { $allowed.lineHint } else { $null }
                    hit = $false
                    matchScore = 0
                    matchedIssueId = $null
                    matchedTitle = $null
                    matchedSeverity = $null
                    matchedFilePath = $null
                    matchedLineNumber = $null
                    fileMatch = $false
                    severityMatch = $false
                    lineMatch = $false
                    requiredAnchorMatches = @()
                    requiredAnchorMisses = @()
                    requiredKeywordMatches = @()
                    titleAnchorMatches = @()
                    forbiddenKeywordMatches = @()
                    keywordMatches = @()
                    hardMatch = $false
                    hardKeywordMisses = @()
                    matchMode = "miss"
                }
            }
        }

        $returnedIssueCount = $issues.Count
        $isNoIssueCase = ($expectedIssues.Count -eq 0 -and $allowedExtraIssues.Count -eq 0)
        $noIssuePass = ($isNoIssueCase -and $returnedIssueCount -eq 0)
        $acceptedIssueCount = $usedIssueKeys.Count
        $falsePositiveCount = [math]::Max(0, $returnedIssueCount - $acceptedIssueCount)
        $hardAcceptedIssueCount = $hardExpectedHitCount + $hardAllowedExtraHitCount
        $semanticRescueCount = $semanticExpectedRescueCount + $semanticAllowedExtraRescueCount
        $hardFalsePositiveCount = [math]::Max(0, $returnedIssueCount - $hardAcceptedIssueCount)
        $unexpectedIssues = @()
        for ($i = 0; $i -lt $issues.Count; $i++) {
            $issue = $issues[$i]
            $issueKey = Get-IssueKey -Issue $issue -Index $i
            if (-not $usedIssueKeys.Contains($issueKey)) {
                $unexpectedIssues += $issue
            }
        }
        $eventTypes = @(As-Array $eventsResponse.data.events | ForEach-Object { $_.eventType })
        $presentEventCount = @($requiredEvents | Where-Object { $eventTypes -contains $_ }).Count
        $eventCompleteness = if ($requiredEvents.Count -eq 0) { 1 } else { $presentEventCount / $requiredEvents.Count }
        $structuredParseSuccess = ($eventTypes -contains "LLM_RESPONSE_PARSED")
        $issueOutputPresent = $(if ($isNoIssueCase) { $returnedIssueCount -eq 0 } else { $returnedIssueCount -gt 0 })
        $contextCoverage = Normalize-ReviewContextCoverage -Coverage $create.data.contextCoverage

        return [pscustomobject]@{
            name = $Case.name
            category = $Case.category
            split = Get-CaseSplit -Case $Case
            reviewId = $reviewId
            runId = [long]$create.data.runId
            success = $true
            parseSuccess = $structuredParseSuccess
            issueOutputPresent = $issueOutputPresent
            isNoIssueCase = $isNoIssueCase
            noIssuePass = $noIssuePass
            expectedIssueCount = $expectedIssues.Count
            allowedExtraIssueCount = $allowedExtraIssues.Count
            returnedIssueCount = $returnedIssueCount
            hitCount = $hitCount
            allowedExtraHitCount = $allowedExtraHitCount
            acceptedIssueCount = $acceptedIssueCount
            falsePositiveCount = $falsePositiveCount
            unexpectedIssueCount = $falsePositiveCount
            hardExpectedHitCount = $hardExpectedHitCount
            semanticExpectedRescueCount = $semanticExpectedRescueCount
            hardAllowedExtraHitCount = $hardAllowedExtraHitCount
            semanticAllowedExtraRescueCount = $semanticAllowedExtraRescueCount
            hardAcceptedIssueCount = $hardAcceptedIssueCount
            semanticRescueCount = $semanticRescueCount
            issueRecall = if ($expectedIssues.Count -eq 0) { 1 } else { [math]::Round($hitCount / $expectedIssues.Count, 3) }
            issuePrecision = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round($usedIssueKeys.Count / $returnedIssueCount, 3) }
            knownIssuePrecision = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round($hitCount / $returnedIssueCount, 3) }
            acceptedIssuePrecision = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round($acceptedIssueCount / $returnedIssueCount, 3) }
            falsePositiveRate = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round($falsePositiveCount / $returnedIssueCount, 3) }
            unexpectedIssueRate = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round($falsePositiveCount / $returnedIssueCount, 3) }
            hardAcceptedIssuePrecision = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round($hardAcceptedIssueCount / $returnedIssueCount, 3) }
            hardUnexpectedIssueRate = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round($hardFalsePositiveCount / $returnedIssueCount, 3) }
            semanticUnexpectedDelta = if ($returnedIssueCount -eq 0) { 0 } else { [math]::Round(($falsePositiveCount - $hardFalsePositiveCount) / $returnedIssueCount, 3) }
            locationAccuracy = if ($hitCount -eq 0) { 0 } else { [math]::Round($lineHitCount / $hitCount, 3) }
            eventCompleteness = [math]::Round($eventCompleteness, 3)
            missingEvents = @($requiredEvents | Where-Object { $eventTypes -notcontains $_ })
            contextCoverage = $contextCoverage
            contextSourceTypes = $contextCoverage.sourceTypes
            contextReviewRules = $contextCoverage.reviewRules
            contextProjectProfile = $contextCoverage.projectProfile
            contextProjectGraph = $contextCoverage.projectGraph
            contextReviewMemorySignals = $contextCoverage.reviewMemorySignals
            contextDecisionMemory = $contextCoverage.decisionMemory
            attemptCount = $attemptResults.Count
            retryCount = [math]::Max(0, $attemptResults.Count - 1)
            attempts = $attemptResults
            failureType = $null
            expectedResults = $expectedResults
            allowedExtraResults = $allowedExtraResults
            unexpectedIssues = $unexpectedIssues
            issues = $issues
            errorMessage = $null
        }
    } catch {
        $expectedIssueCount = @(As-Array $Case.expectedIssues).Count
        $allowedExtraIssueCount = 0
        if ($Case.PSObject.Properties.Name -contains "allowedExtraIssues") {
            $allowedExtraIssueCount = @(As-Array $Case.allowedExtraIssues).Count
        }
        return [pscustomobject]@{
            name = $Case.name
            category = $Case.category
            split = Get-CaseSplit -Case $Case
            reviewId = $null
            runId = $null
            success = $false
            parseSuccess = $false
            issueOutputPresent = $false
            isNoIssueCase = ($expectedIssueCount -eq 0 -and $allowedExtraIssueCount -eq 0)
            noIssuePass = $false
            expectedIssueCount = $expectedIssueCount
            allowedExtraIssueCount = $allowedExtraIssueCount
            returnedIssueCount = 0
            hitCount = 0
            allowedExtraHitCount = 0
            acceptedIssueCount = 0
            falsePositiveCount = 0
            unexpectedIssueCount = 0
            hardExpectedHitCount = 0
            semanticExpectedRescueCount = 0
            hardAllowedExtraHitCount = 0
            semanticAllowedExtraRescueCount = 0
            hardAcceptedIssueCount = 0
            semanticRescueCount = 0
            issueRecall = 0
            issuePrecision = 0
            knownIssuePrecision = 0
            acceptedIssuePrecision = 0
            falsePositiveRate = 0
            unexpectedIssueRate = 0
            hardAcceptedIssuePrecision = 0
            hardUnexpectedIssueRate = 0
            semanticUnexpectedDelta = 0
            locationAccuracy = 0
            eventCompleteness = 0
            missingEvents = $requiredEvents
            contextCoverage = Normalize-ReviewContextCoverage -Coverage $null
            contextSourceTypes = @()
            contextReviewRules = $false
            contextProjectProfile = $false
            contextProjectGraph = $false
            contextReviewMemorySignals = $false
            contextDecisionMemory = $false
            attemptCount = $attemptResults.Count
            retryCount = [math]::Max(0, $attemptResults.Count - 1)
            attempts = $attemptResults
            failureType = Get-FailureType -Message $_.Exception.Message
            expectedResults = @()
            allowedExtraResults = @()
            unexpectedIssues = @()
            issues = @()
            errorMessage = $_.Exception.Message
        }
    }
}

function Summarize-Results {
    param(
        [object[]]$Cases,
        [switch]$SkipGroups
    )
    $caseCount = $Cases.Count
    $completedCases = @($Cases | Where-Object { $_.success })
    $failedCases = @($Cases | Where-Object { -not $_.success })
    $successCount = $completedCases.Count
    $parseSuccessCount = @($Cases | Where-Object { $_.parseSuccess }).Count
    $expectedCount = Get-Sum -Items $Cases -Property "expectedIssueCount"
    $hitCount = Get-Sum -Items $Cases -Property "hitCount"
    $allowedExtraHitCount = Get-Sum -Items $Cases -Property "allowedExtraHitCount"
    $acceptedIssueCount = Get-Sum -Items $Cases -Property "acceptedIssueCount"
    $returnedCount = Get-Sum -Items $Cases -Property "returnedIssueCount"
    $falsePositiveCount = Get-Sum -Items $Cases -Property "falsePositiveCount"
    $hardExpectedHitCount = Get-Sum -Items $Cases -Property "hardExpectedHitCount"
    $semanticExpectedRescueCount = Get-Sum -Items $Cases -Property "semanticExpectedRescueCount"
    $hardAllowedExtraHitCount = Get-Sum -Items $Cases -Property "hardAllowedExtraHitCount"
    $semanticAllowedExtraRescueCount = Get-Sum -Items $Cases -Property "semanticAllowedExtraRescueCount"
    $hardAcceptedIssueCount = Get-Sum -Items $Cases -Property "hardAcceptedIssueCount"
    $semanticRescueCount = Get-Sum -Items $Cases -Property "semanticRescueCount"
    $hardFalsePositiveCount = [math]::Max(0, $returnedCount - $hardAcceptedIssueCount)
    $locationDenominator = Get-Sum -Items @($Cases | Where-Object { $_.hitCount -gt 0 }) -Property "hitCount"
    $lineHitCount = 0
    foreach ($case in $Cases) {
        foreach ($expected in As-Array $case.expectedResults) {
            if ($expected.hit -and $expected.lineMatch) {
                $lineHitCount++
            }
        }
    }
    $completedExpectedCount = Get-Sum -Items $completedCases -Property "expectedIssueCount"
    $completedHitCount = Get-Sum -Items $completedCases -Property "hitCount"
    $completedAllowedExtraHitCount = Get-Sum -Items $completedCases -Property "allowedExtraHitCount"
    $completedAcceptedIssueCount = Get-Sum -Items $completedCases -Property "acceptedIssueCount"
    $completedReturnedCount = Get-Sum -Items $completedCases -Property "returnedIssueCount"
    $completedFalsePositiveCount = Get-Sum -Items $completedCases -Property "falsePositiveCount"
    $completedHardExpectedHitCount = Get-Sum -Items $completedCases -Property "hardExpectedHitCount"
    $completedSemanticExpectedRescueCount = Get-Sum -Items $completedCases -Property "semanticExpectedRescueCount"
    $completedHardAllowedExtraHitCount = Get-Sum -Items $completedCases -Property "hardAllowedExtraHitCount"
    $completedSemanticAllowedExtraRescueCount = Get-Sum -Items $completedCases -Property "semanticAllowedExtraRescueCount"
    $completedHardAcceptedIssueCount = Get-Sum -Items $completedCases -Property "hardAcceptedIssueCount"
    $completedSemanticRescueCount = Get-Sum -Items $completedCases -Property "semanticRescueCount"
    $completedHardFalsePositiveCount = [math]::Max(0, $completedReturnedCount - $completedHardAcceptedIssueCount)
    $completedLocationDenominator = Get-Sum -Items @($completedCases | Where-Object { $_.hitCount -gt 0 }) -Property "hitCount"
    $completedLineHitCount = 0
    foreach ($case in $completedCases) {
        foreach ($expected in As-Array $case.expectedResults) {
            if ($expected.hit -and $expected.lineMatch) {
                $completedLineHitCount++
            }
        }
    }
    $eventCompleteness = Get-Average -Items $Cases -Property "eventCompleteness"
    $completedEventCompleteness = Get-Average -Items $completedCases -Property "eventCompleteness"
    $retryTriggeredCount = @($Cases | Where-Object { $_.retryCount -gt 0 }).Count
    $totalRetryCount = Get-Sum -Items $Cases -Property "retryCount"
    $llmTimeoutCount = @($failedCases | Where-Object { $_.failureType -eq "llm_timeout" }).Count
    $noIssueCases = @($Cases | Where-Object { $_.isNoIssueCase })
    $completedNoIssueCases = @($completedCases | Where-Object { $_.isNoIssueCase })
    $noIssuePassCount = @($noIssueCases | Where-Object { $_.noIssuePass }).Count
    $completedNoIssuePassCount = @($completedNoIssueCases | Where-Object { $_.noIssuePass }).Count
    $noIssueFalsePositiveCount = Get-Sum -Items $noIssueCases -Property "returnedIssueCount"
    $completedNoIssueFalsePositiveCount = Get-Sum -Items $completedNoIssueCases -Property "returnedIssueCount"
    $contextCoverageCases = @($completedCases | Where-Object {
            $null -ne (Get-ObjectPropertyValue -Object $_ -Name "contextCoverage" -Default $null)
        })
    $contextCoverageSourceTypes = @($contextCoverageCases | ForEach-Object {
            $coverage = Get-ObjectPropertyValue -Object $_ -Name "contextCoverage" -Default $null
            As-Array (Get-ObjectPropertyValue -Object $coverage -Name "sourceTypes" -Default @())
        } | Sort-Object -Unique)
    $contextCoverage = [pscustomobject]@{
        completedCaseCount = $contextCoverageCases.Count
        reviewRulesCount = Get-ContextCoverageFlagCount -Cases $contextCoverageCases -Property "reviewRules"
        projectProfileCount = Get-ContextCoverageFlagCount -Cases $contextCoverageCases -Property "projectProfile"
        projectGraphCount = Get-ContextCoverageFlagCount -Cases $contextCoverageCases -Property "projectGraph"
        reviewMemorySignalsCount = Get-ContextCoverageFlagCount -Cases $contextCoverageCases -Property "reviewMemorySignals"
        decisionMemoryCount = Get-ContextCoverageFlagCount -Cases $contextCoverageCases -Property "decisionMemory"
        observedSourceTypes = $contextCoverageSourceTypes
    }
    $splitSummaries = @()
    $categorySummaries = @()
    if (-not $SkipGroups) {
        foreach ($group in @($Cases | Group-Object -Property split | Sort-Object Name)) {
            $groupSummary = Summarize-Results -Cases @($group.Group) -SkipGroups
            $groupSummary | Add-Member -NotePropertyName split -NotePropertyValue $group.Name
            $splitSummaries += $groupSummary
        }
        foreach ($group in @($Cases | Group-Object -Property category | Sort-Object Name)) {
            $groupSummary = Summarize-Results -Cases @($group.Group) -SkipGroups
            $groupSummary | Add-Member -NotePropertyName category -NotePropertyValue $group.Name
            $categorySummaries += $groupSummary
        }
    }
    return [pscustomobject]@{
        caseCount = $caseCount
        completedCaseCount = $successCount
        failedCaseCount = $failedCases.Count
        reviewSuccessRate = if ($caseCount -eq 0) { 0 } else { [math]::Round($successCount / $caseCount, 3) }
        reviewIssueParseSuccessRate = if ($caseCount -eq 0) { 0 } else { [math]::Round($parseSuccessCount / $caseCount, 3) }
        knownIssueRecall = if ($expectedCount -eq 0) { 0 } else { [math]::Round($hitCount / $expectedCount, 3) }
        knownIssuePrecision = if ($returnedCount -eq 0) { 0 } else { [math]::Round($hitCount / $returnedCount, 3) }
        issuePrecision = if ($returnedCount -eq 0) { 0 } else { [math]::Round($acceptedIssueCount / $returnedCount, 3) }
        acceptedIssuePrecision = if ($returnedCount -eq 0) { 0 } else { [math]::Round($acceptedIssueCount / $returnedCount, 3) }
        falsePositiveRate = if ($returnedCount -eq 0) { 0 } else { [math]::Round($falsePositiveCount / $returnedCount, 3) }
        unexpectedIssueRate = if ($returnedCount -eq 0) { 0 } else { [math]::Round($falsePositiveCount / $returnedCount, 3) }
        hardAcceptedIssuePrecision = if ($returnedCount -eq 0) { 0 } else { [math]::Round($hardAcceptedIssueCount / $returnedCount, 3) }
        hardUnexpectedIssueRate = if ($returnedCount -eq 0) { 0 } else { [math]::Round($hardFalsePositiveCount / $returnedCount, 3) }
        semanticUnexpectedDelta = if ($returnedCount -eq 0) { 0 } else { [math]::Round(($falsePositiveCount - $hardFalsePositiveCount) / $returnedCount, 3) }
        locationAccuracy = if ($locationDenominator -eq 0) { 0 } else { [math]::Round($lineHitCount / $locationDenominator, 3) }
        eventCompleteness = [math]::Round($eventCompleteness, 3)
        completedKnownIssueRecall = if ($completedExpectedCount -eq 0) { 0 } else { [math]::Round($completedHitCount / $completedExpectedCount, 3) }
        completedKnownIssuePrecision = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round($completedHitCount / $completedReturnedCount, 3) }
        completedIssuePrecision = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round($completedAcceptedIssueCount / $completedReturnedCount, 3) }
        completedAcceptedIssuePrecision = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round($completedAcceptedIssueCount / $completedReturnedCount, 3) }
        completedFalsePositiveRate = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round($completedFalsePositiveCount / $completedReturnedCount, 3) }
        completedUnexpectedIssueRate = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round($completedFalsePositiveCount / $completedReturnedCount, 3) }
        completedHardAcceptedIssuePrecision = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round($completedHardAcceptedIssueCount / $completedReturnedCount, 3) }
        completedHardUnexpectedIssueRate = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round($completedHardFalsePositiveCount / $completedReturnedCount, 3) }
        completedSemanticUnexpectedDelta = if ($completedReturnedCount -eq 0) { 0 } else { [math]::Round(($completedFalsePositiveCount - $completedHardFalsePositiveCount) / $completedReturnedCount, 3) }
        completedLocationAccuracy = if ($completedLocationDenominator -eq 0) { 0 } else { [math]::Round($completedLineHitCount / $completedLocationDenominator, 3) }
        completedEventCompleteness = [math]::Round($completedEventCompleteness, 3)
        noIssueCaseCount = $noIssueCases.Count
        noIssuePassCount = $noIssuePassCount
        noIssuePassRate = if ($noIssueCases.Count -eq 0) { 0 } else { [math]::Round($noIssuePassCount / $noIssueCases.Count, 3) }
        noIssueFalsePositiveCount = $noIssueFalsePositiveCount
        completedNoIssueCaseCount = $completedNoIssueCases.Count
        completedNoIssuePassCount = $completedNoIssuePassCount
        completedNoIssuePassRate = if ($completedNoIssueCases.Count -eq 0) { 0 } else { [math]::Round($completedNoIssuePassCount / $completedNoIssueCases.Count, 3) }
        completedNoIssueFalsePositiveCount = $completedNoIssueFalsePositiveCount
        llmTimeoutCount = $llmTimeoutCount
        retryTriggeredCount = $retryTriggeredCount
        totalRetryCount = $totalRetryCount
        expectedIssueCount = $expectedCount
        hitCount = $hitCount
        allowedExtraHitCount = $allowedExtraHitCount
        acceptedIssueCount = $acceptedIssueCount
        returnedIssueCount = $returnedCount
        falsePositiveCount = $falsePositiveCount
        unexpectedIssueCount = $falsePositiveCount
        hardExpectedHitCount = $hardExpectedHitCount
        semanticExpectedRescueCount = $semanticExpectedRescueCount
        hardAllowedExtraHitCount = $hardAllowedExtraHitCount
        semanticAllowedExtraRescueCount = $semanticAllowedExtraRescueCount
        hardAcceptedIssueCount = $hardAcceptedIssueCount
        semanticRescueCount = $semanticRescueCount
        hardFalsePositiveCount = $hardFalsePositiveCount
        completedExpectedIssueCount = $completedExpectedCount
        completedHitCount = $completedHitCount
        completedAllowedExtraHitCount = $completedAllowedExtraHitCount
        completedAcceptedIssueCount = $completedAcceptedIssueCount
        completedReturnedIssueCount = $completedReturnedCount
        completedFalsePositiveCount = $completedFalsePositiveCount
        completedUnexpectedIssueCount = $completedFalsePositiveCount
        completedHardExpectedHitCount = $completedHardExpectedHitCount
        completedSemanticExpectedRescueCount = $completedSemanticExpectedRescueCount
        completedHardAllowedExtraHitCount = $completedHardAllowedExtraHitCount
        completedSemanticAllowedExtraRescueCount = $completedSemanticAllowedExtraRescueCount
        completedHardAcceptedIssueCount = $completedHardAcceptedIssueCount
        completedSemanticRescueCount = $completedSemanticRescueCount
        completedHardFalsePositiveCount = $completedHardFalsePositiveCount
        contextCoverage = $contextCoverage
        splitSummaries = $splitSummaries
        categorySummaries = $categorySummaries
    }
}

function Write-Reports {
    param(
        [string]$RunId,
        [object]$Project,
        [object]$Summary,
        [object[]]$Cases,
        [object]$LlmMetadata,
        [string]$OutputDir,
        [string]$CasesSource,
        [string]$ResolvedCasesPath,
        [string]$FixturesRoot
    )
    if (-not (Test-Path -LiteralPath $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir | Out-Null
    }
    $jsonPath = Join-Path $OutputDir "code-review-benchmark-$RunId.json"
    $mdPath = Join-Path $OutputDir "code-review-benchmark-$RunId.md"
    $payload = [pscustomobject]@{
        runId = $RunId
        baseUrl = $BaseUrl
        casesSource = $CasesSource
        casesPath = $CasesPath
        resolvedCasesPath = $ResolvedCasesPath
        fixturesRoot = $FixturesRoot
        split = $Split
        project = $Project
        mode = $Mode
        timeoutSeconds = $TimeoutSeconds
        retryCount = $RetryCount
        llm = $LlmMetadata
        generatedAt = (Get-Date).ToString("o")
        summary = $Summary
        cases = $Cases
    }
    $payload | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -LiteralPath $jsonPath

    $lines = @()
    $lines += "# DevContext CodeReview Benchmark $RunId"
    $lines += ""
    $lines += "## Environment"
    $lines += ""
    $lines += "- Base URL: ``$BaseUrl``"
    $lines += "- Cases source: ``$CasesSource``"
    $lines += "- Cases: ``$CasesPath``"
    $lines += "- Resolved cases path: ``$ResolvedCasesPath``"
    $lines += "- Fixtures root: ``$FixturesRoot``"
    $lines += "- Split: ``$Split``"
    $lines += "- Project ID: ``$($Project.id)``"
    $lines += "- Project root: ``$($Project.rootPath)``"
    $lines += "- Mode: ``$Mode``"
    $lines += "- Timeout seconds: ``$TimeoutSeconds``"
    $lines += "- Retry count: ``$RetryCount``"
    $lines = Add-DevContextLlmReportMarkdownLines -Lines $lines -LlmMetadata $LlmMetadata
    $lines += ""
    $lines += "## Summary"
    $lines += ""
    $lines += "| Cases | Completed | Failed | Review Success | Structured Parse | LLM Timeouts | Retry Cases | Total Retries |"
    $lines += "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    $lines += "| $($Summary.caseCount) | $($Summary.completedCaseCount) | $($Summary.failedCaseCount) | $($Summary.reviewSuccessRate) | $($Summary.reviewIssueParseSuccessRate) | $($Summary.llmTimeoutCount) | $($Summary.retryTriggeredCount) | $($Summary.totalRetryCount) |"
    $lines += ""
    $lines += "## Quality Metrics"
    $lines += ""
    $lines += "| Scope | Known Issue Recall | Known Precision | Accepted Precision | Unexpected Issue Rate | Location Accuracy | Event Completeness |"
    $lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"
    $lines += "| End-to-end | $($Summary.knownIssueRecall) | $($Summary.knownIssuePrecision) | $($Summary.acceptedIssuePrecision) | $($Summary.unexpectedIssueRate) | $($Summary.locationAccuracy) | $($Summary.eventCompleteness) |"
    $lines += "| Completed cases only | $($Summary.completedKnownIssueRecall) | $($Summary.completedKnownIssuePrecision) | $($Summary.completedAcceptedIssuePrecision) | $($Summary.completedUnexpectedIssueRate) | $($Summary.completedLocationAccuracy) | $($Summary.completedEventCompleteness) |"
    $lines += ""
    $lines += "## No-Issue Metrics"
    $lines += ""
    $lines += "| Scope | No-Issue Cases | Pass Count | Pass Rate | False Positive Issues |"
    $lines += "| --- | ---: | ---: | ---: | ---: |"
    $lines += "| End-to-end | $($Summary.noIssueCaseCount) | $($Summary.noIssuePassCount) | $($Summary.noIssuePassRate) | $($Summary.noIssueFalsePositiveCount) |"
    $lines += "| Completed cases only | $($Summary.completedNoIssueCaseCount) | $($Summary.completedNoIssuePassCount) | $($Summary.completedNoIssuePassRate) | $($Summary.completedNoIssueFalsePositiveCount) |"
    $lines += ""
    $lines += "## Matcher Diagnostics"
    $lines += ""
    $lines += "| Scope | Hard Accepted Precision | Semantic Accepted Precision | Semantic Rescue Count | Hard Unexpected Rate | Semantic Unexpected Rate | Semantic Unexpected Delta |"
    $lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: |"
    $lines += "| End-to-end | $($Summary.hardAcceptedIssuePrecision) | $($Summary.acceptedIssuePrecision) | $($Summary.semanticRescueCount) | $($Summary.hardUnexpectedIssueRate) | $($Summary.unexpectedIssueRate) | $($Summary.semanticUnexpectedDelta) |"
    $lines += "| Completed cases only | $($Summary.completedHardAcceptedIssuePrecision) | $($Summary.completedAcceptedIssuePrecision) | $($Summary.completedSemanticRescueCount) | $($Summary.completedHardUnexpectedIssueRate) | $($Summary.completedUnexpectedIssueRate) | $($Summary.completedSemanticUnexpectedDelta) |"
    $lines += ""
    $lines += "## Context Coverage"
    $lines += ""
    $lines += "| Source | Completed Cases Present |"
    $lines += "| --- | ---: |"
    $lines += "| Review rules | $($Summary.contextCoverage.reviewRulesCount) |"
    $lines += "| ProjectProfile context | $($Summary.contextCoverage.projectProfileCount) |"
    $lines += "| ProjectGraph context | $($Summary.contextCoverage.projectGraphCount) |"
    $lines += "| Review memory signals | $($Summary.contextCoverage.reviewMemorySignalsCount) |"
    $lines += "| Decision memory | $($Summary.contextCoverage.decisionMemoryCount) |"
    $lines += ""
    $observedSourceTypes = (As-Array $Summary.contextCoverage.observedSourceTypes) -join ", "
    $lines += "- Observed context source types: $observedSourceTypes"
    $lines += ""
    $lines += "## Context Coverage By Case"
    $lines += ""
    $lines += "| Case | Rules | ProjectProfile | ProjectGraph | Review Memory | Decision Memory | Source Count | Tokens | Source Types |"
    $lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"
    foreach ($case in $Cases) {
        $coverage = Get-ObjectPropertyValue -Object $case -Name "contextCoverage" -Default $null
        $sourceTypes = (As-Array (Get-ObjectPropertyValue -Object $coverage -Name "sourceTypes" -Default @())) -join ", "
        $lines += "| $($case.name) | $([bool](Get-ObjectPropertyValue -Object $coverage -Name "reviewRules" -Default $false)) | $([bool](Get-ObjectPropertyValue -Object $coverage -Name "projectProfile" -Default $false)) | $([bool](Get-ObjectPropertyValue -Object $coverage -Name "projectGraph" -Default $false)) | $([bool](Get-ObjectPropertyValue -Object $coverage -Name "reviewMemorySignals" -Default $false)) | $([bool](Get-ObjectPropertyValue -Object $coverage -Name "decisionMemory" -Default $false)) | $(Get-ObjectPropertyValue -Object $coverage -Name "sourceCount" -Default 0) | $(Get-ObjectPropertyValue -Object $coverage -Name "totalTokenEstimate" -Default 0) | $sourceTypes |"
    }
    $lines += ""
    $lines += "## Case Matcher Diagnostics"
    $lines += ""
    $lines += "| Case | Split | Hard Accepted | Semantic Rescue | Hard Accepted Precision | Semantic Accepted Precision | Hard Unexpected Rate | Semantic Unexpected Rate | Delta |"
    $lines += "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    foreach ($case in $Cases) {
        $lines += "| $($case.name) | $($case.split) | $($case.hardAcceptedIssueCount) | $($case.semanticRescueCount) | $($case.hardAcceptedIssuePrecision) | $($case.acceptedIssuePrecision) | $($case.hardUnexpectedIssueRate) | $($case.unexpectedIssueRate) | $($case.semanticUnexpectedDelta) |"
    }
    $lines += ""
    $lines += "## Split Summary"
    $lines += ""
    $lines += "| Split | Cases | Completed | Recall | Accepted Precision | Unexpected Rate | No-Issue Pass Rate | Events |"
    $lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    foreach ($splitSummary in As-Array $Summary.splitSummaries) {
        $lines += "| $($splitSummary.split) | $($splitSummary.caseCount) | $($splitSummary.completedCaseCount) | $($splitSummary.completedKnownIssueRecall) | $($splitSummary.completedAcceptedIssuePrecision) | $($splitSummary.completedUnexpectedIssueRate) | $($splitSummary.completedNoIssuePassRate) | $($splitSummary.completedEventCompleteness) |"
    }
    $lines += ""
    $lines += "## Category Summary"
    $lines += ""
    $lines += "| Category | Cases | Completed | Recall | Accepted Precision | Unexpected Rate | No-Issue Pass Rate | Events |"
    $lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    foreach ($categorySummary in As-Array $Summary.categorySummaries) {
        $lines += "| $($categorySummary.category) | $($categorySummary.caseCount) | $($categorySummary.completedCaseCount) | $($categorySummary.completedKnownIssueRecall) | $($categorySummary.completedAcceptedIssuePrecision) | $($categorySummary.completedUnexpectedIssueRate) | $($categorySummary.completedNoIssuePassRate) | $($categorySummary.completedEventCompleteness) |"
    }
    $lines += ""
    $lines += "## Cases"
    $lines += ""
    $lines += "| Case | Split | Category | No-Issue | No-Issue Pass | Success | Structured Parse | Issue Output | Recall | Known Precision | Accepted Precision | Unexpected Rate | Location | Events | Attempts | Failure Type | ReviewId | Error |"
    $lines += "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: | --- |"
    foreach ($case in $Cases) {
        $errorText = if ($null -eq $case.errorMessage) { "" } else { $case.errorMessage.Replace("|", "/") }
        $failureType = if ($null -eq $case.failureType) { "" } else { $case.failureType }
        $issueOutput = if ($case.PSObject.Properties.Name -contains "issueOutputPresent") { $case.issueOutputPresent } else { $case.parseSuccess }
        $lines += "| $($case.name) | $($case.split) | $($case.category) | $($case.isNoIssueCase) | $($case.noIssuePass) | $($case.success) | $($case.parseSuccess) | $issueOutput | $($case.issueRecall) | $($case.knownIssuePrecision) | $($case.acceptedIssuePrecision) | $($case.unexpectedIssueRate) | $($case.locationAccuracy) | $($case.eventCompleteness) | $($case.attemptCount) | $failureType | $($case.reviewId) | $errorText |"
    }
    $lines += ""
    $lines += "## Expected Issue Matches"
    $lines += ""
    foreach ($case in $Cases) {
        $lines += "### $($case.name)"
        $lines += ""
        foreach ($expected in As-Array $case.expectedResults) {
            $anchorMatches = if ($expected.PSObject.Properties.Name -contains "requiredAnchorMatches") { $expected.requiredAnchorMatches } else { $expected.requiredKeywordMatches }
            $anchorMisses = if ($expected.PSObject.Properties.Name -contains "requiredAnchorMisses") { $expected.requiredAnchorMisses } else { @() }
            $titleAnchorMatches = if ($expected.PSObject.Properties.Name -contains "titleAnchorMatches") { $expected.titleAnchorMatches } else { @() }
            $hardMisses = if ($expected.PSObject.Properties.Name -contains "hardKeywordMisses") { $expected.hardKeywordMisses } else { @() }
            $matchMode = if ($expected.PSObject.Properties.Name -contains "matchMode") { $expected.matchMode } else { "" }
            $hardMatch = if ($expected.PSObject.Properties.Name -contains "hardMatch") { $expected.hardMatch } else { $false }
            $lines += "- $($expected.category): hit=$($expected.hit), mode=$matchMode, hardMatch=$hardMatch, score=$($expected.matchScore), issueId=$($expected.matchedIssueId), title=$($expected.matchedTitle), lineMatch=$($expected.lineMatch), anchors=$($anchorMatches -join ', '), titleAnchors=$($titleAnchorMatches -join ', '), missingAnchors=$($anchorMisses -join ', '), hardMissing=$($hardMisses -join ', '), keywords=$($expected.keywordMatches -join ', ')"
        }
        if (@(As-Array $case.expectedResults).Count -eq 0) {
            $lines += "- No expected issue results recorded."
        }
        $lines += ""
    }
    $lines += "## Allowed Extra Issue Matches"
    $lines += ""
    foreach ($case in $Cases) {
        $lines += "### $($case.name)"
        $lines += ""
        foreach ($allowed in As-Array $case.allowedExtraResults) {
            $anchorMatches = if ($allowed.PSObject.Properties.Name -contains "requiredAnchorMatches") { $allowed.requiredAnchorMatches } else { $allowed.requiredKeywordMatches }
            $anchorMisses = if ($allowed.PSObject.Properties.Name -contains "requiredAnchorMisses") { $allowed.requiredAnchorMisses } else { @() }
            $titleAnchorMatches = if ($allowed.PSObject.Properties.Name -contains "titleAnchorMatches") { $allowed.titleAnchorMatches } else { @() }
            $hardMisses = if ($allowed.PSObject.Properties.Name -contains "hardKeywordMisses") { $allowed.hardKeywordMisses } else { @() }
            $matchMode = if ($allowed.PSObject.Properties.Name -contains "matchMode") { $allowed.matchMode } else { "" }
            $hardMatch = if ($allowed.PSObject.Properties.Name -contains "hardMatch") { $allowed.hardMatch } else { $false }
            $lines += "- $($allowed.category): hit=$($allowed.hit), mode=$matchMode, hardMatch=$hardMatch, score=$($allowed.matchScore), issueId=$($allowed.matchedIssueId), title=$($allowed.matchedTitle), lineMatch=$($allowed.lineMatch), anchors=$($anchorMatches -join ', '), titleAnchors=$($titleAnchorMatches -join ', '), missingAnchors=$($anchorMisses -join ', '), hardMissing=$($hardMisses -join ', '), keywords=$($allowed.keywordMatches -join ', ')"
        }
        if (@(As-Array $case.allowedExtraResults).Count -eq 0) {
            $lines += "- No allowed extra issue annotations recorded."
        }
        $lines += ""
    }
    $lines += "## Unexpected Issues"
    $lines += ""
    foreach ($case in $Cases) {
        $lines += "### $($case.name)"
        $lines += ""
        foreach ($issue in As-Array $case.unexpectedIssues) {
            $description = if ($null -eq $issue.description) { "" } else { ([string]$issue.description).Replace("|", "/") }
            if ($description.Length -gt 180) {
                $description = $description.Substring(0, 180) + "..."
            }
            $lines += "- issueId=$($issue.id), severity=$($issue.severity), title=$($issue.title), file=$($issue.filePath), line=$($issue.lineNumber), description=$description"
        }
        if (@(As-Array $case.unexpectedIssues).Count -eq 0) {
            $lines += "- No unexpected issues."
        }
        $lines += ""
    }
    $lines += "## Notes"
    $lines += ""
    $lines += "- Mock LLM results validate plumbing only; use a real LLM provider for quality numbers."
    $lines += "- Known issue recall is based on fixture annotations and matching across file, semantic anchors, forbidden keywords, optional keywords, line, and severity."
    $lines += "- Severity is a weak matching signal; benchmark quality should not depend on the model choosing the exact same severity label."
    $lines += "- ``semanticAnchors`` use OR groups for natural wording variants; every group must have at least one matched term."
    $lines += "- Matcher diagnostics compare a conservative hard baseline with the current semantic matcher."
    $lines += "- Hard matches require file, required line when enabled, severity when declared, no forbidden keywords, the first term from each semantic anchor group, and all supporting ``keywords``."
    $lines += "- Semantic rescue means the current semantic matcher accepted an issue that the hard baseline would miss."
    $lines += "- Semantic unexpected delta = semantic unexpected rate - hard unexpected rate. Negative means semantic matching reduced unexpected findings; positive means it may be adding false-positive risk."
    $lines += "- ``titleAnchors`` are optional tie-breaker anchors. They boost matches whose issue title directly names the expected risk."
    $lines += "- ``mustKeywords`` remains supported as a legacy exact-anchor format; prefer ``semanticAnchors`` for new fixtures."
    $lines += "- ``forbiddenKeywords`` prevent broad terms from matching the wrong issue category."
    $lines += "- ``allowedExtraIssues`` mark reasonable model findings that are not the primary known issue for a fixture."
    $lines += "- Known precision counts only required fixture hits; accepted precision counts required hits plus allowed extra findings."
    $lines += "- Unexpected issue rate means returned issues that matched neither required fixture annotations nor allowed extra annotations."
    $lines += "- No-issue pass rate measures cases with no expected findings where the model correctly returns no ReviewIssue."
    $lines += "- Unexpected issues should be reviewed after each run; reasonable findings should become ``allowedExtraIssues``, while low-value findings should drive prompt changes."
    $lines += "- Structured parse means the review workflow emitted the LLM_RESPONSE_PARSED event; Issue Output separately shows whether a non-no-issue case produced at least one ReviewIssue or a no-issue case stayed empty."
    $lines += "- End-to-end metrics count failed LLM calls as misses; completed-only metrics measure ReviewIssue quality after the model returns a structured response."
    $lines += "- Event completeness checks that the review workflow produced traceable AgentRun events."
    $lines | Set-Content -Encoding UTF8 -LiteralPath $mdPath

    return [pscustomobject]@{
        jsonPath = $jsonPath
        markdownPath = $mdPath
    }
}

$runId = New-RunId
$resolvedCasesPath = Resolve-WorkspacePath $CasesPath
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Join-Path (Get-Location) "target/code-review-benchmark-$runId"
}

$usingBuiltInCases = -not (Test-Path -LiteralPath $resolvedCasesPath)
if ($usingBuiltInCases) {
    $casesDir = Write-BuiltInCodeReviewBenchmarkFixtures -Root (Join-Path (Get-Location) "target/code-review-benchmark-built-in-$runId-$PID")
    $casesSource = "built-in"
    Write-Host "Benchmark cases file not found at $resolvedCasesPath"
    Write-Host "Using built-in CodeReview benchmark cases with fixtures at $casesDir"
    $caseDocument = New-BuiltInCodeReviewBenchmarkCases
} else {
    $casesDir = Split-Path -Parent $resolvedCasesPath
    $casesSource = "file"
    Write-Host "Loading benchmark cases from $resolvedCasesPath"
    $caseDocument = Get-Content -Raw -Encoding UTF8 -LiteralPath $resolvedCasesPath | ConvertFrom-Json
}
$cases = @(As-Array $caseDocument.cases)
if ($cases.Count -eq 0) {
    throw "Benchmark cases are empty"
}
if ($Split -ne "all") {
    $cases = @($cases | Where-Object { (Get-CaseSplit -Case $_) -eq $Split })
    if ($cases.Count -eq 0 -and -not $ListCases) {
        throw "No benchmark cases matched Split '$Split'"
    }
}
if (-not [string]::IsNullOrWhiteSpace($CaseName)) {
    $cases = @($cases | Where-Object { $_.name -eq $CaseName })
    if ($cases.Count -eq 0) {
        throw "No benchmark case matched CaseName '$CaseName'"
    }
}
if ($CaseLimit -gt 0) {
    $cases = @($cases | Select-Object -First $CaseLimit)
}
Write-Host "Selected $($cases.Count) benchmark case(s)"
if ($ListCases) {
    Write-Host "Case source: $casesSource"
    Write-Host "Cases path: $resolvedCasesPath"
    Write-Host "Fixtures root: $casesDir"
    foreach ($case in $cases) {
        $expectedCount = @(As-Array $case.expectedIssues).Count
        $allowedCount = @(As-Array $case.allowedExtraIssues).Count
        Write-Host ("- {0} [{1}] category={2}, diff={3}, expected={4}, allowed={5}" -f $case.name, (Get-CaseSplit -Case $case), $case.category, $case.diffPath, $expectedCount, $allowedCount)
    }
    exit 0
}

Write-Host "Checking DevContext health at $BaseUrl"
$health = Invoke-DevContextApi -Method "Get" -Path "/api/health"
if (-not $health.Success) {
    throw "DevContext health check failed"
}

Write-Host "Creating benchmark project at $ProjectRoot"
Initialize-BenchmarkProject -Root $ProjectRoot -RunId $runId
$project = New-BenchmarkProject -Root $ProjectRoot -RunId $runId
Initialize-BenchmarkContextSources -Project $project

$caseResults = @()
foreach ($case in $cases) {
    Write-Host "Running review case: $($case.name)"
    $caseResult = Evaluate-ReviewCase -Case $case -Project $project -CasesDir $casesDir
    $caseResults += $caseResult
    if ($caseResult.success) {
        Write-Host "  Result: success, recall=$($caseResult.issueRecall), precision=$($caseResult.issuePrecision), reviewId=$($caseResult.reviewId)"
    } else {
        Write-Host "  Result: failed - $($caseResult.errorMessage)"
    }
}

$summary = Summarize-Results -Cases $caseResults
$report = Write-Reports -RunId $runId -Project $project -Summary $summary -Cases $caseResults -LlmMetadata (Get-LlmReportMetadata) -OutputDir $OutputDir -CasesSource $casesSource -ResolvedCasesPath $resolvedCasesPath -FixturesRoot $casesDir

Write-Host ""
Write-Host "CodeReview benchmark complete"
Write-Host "Completed cases:           $($summary.completedCaseCount)/$($summary.caseCount)"
Write-Host "Review success rate:       $($summary.reviewSuccessRate)"
Write-Host "Structured parse rate:    $($summary.reviewIssueParseSuccessRate)"
Write-Host "LLM timeout count:         $($summary.llmTimeoutCount)"
Write-Host "Retry cases:               $($summary.retryTriggeredCount)"
Write-Host "Total retries:             $($summary.totalRetryCount)"
Write-Host "End-to-end recall:         $($summary.knownIssueRecall)"
Write-Host "Completed recall:          $($summary.completedKnownIssueRecall)"
Write-Host "Completed known precision: $($summary.completedKnownIssuePrecision)"
Write-Host "Completed accepted prec.:  $($summary.completedAcceptedIssuePrecision)"
Write-Host "Completed unexpected rate: $($summary.completedUnexpectedIssueRate)"
Write-Host "No-issue pass rate:        $($summary.completedNoIssuePassRate)"
Write-Host "No-issue FP issues:        $($summary.completedNoIssueFalsePositiveCount)"
Write-Host "Hard accepted prec.:       $($summary.completedHardAcceptedIssuePrecision)"
Write-Host "Semantic rescue count:     $($summary.completedSemanticRescueCount)"
Write-Host "Semantic unexpected delta: $($summary.completedSemanticUnexpectedDelta)"
Write-Host "Completed location:        $($summary.completedLocationAccuracy)"
Write-Host "Completed events:          $($summary.completedEventCompleteness)"
Write-Host "Context coverage rules/profile/graph: $($summary.contextCoverage.reviewRulesCount)/$($summary.contextCoverage.projectProfileCount)/$($summary.contextCoverage.projectGraphCount)"
Write-Host "Markdown report:           $($report.markdownPath)"
Write-Host "JSON report:               $($report.jsonPath)"
