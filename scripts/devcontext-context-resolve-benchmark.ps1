param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$CasesPath = "docs/benchmarks/context-resolve/context-resolve-benchmark-cases.json",
    [string]$OutputDir = "docs/reports",
    [string]$ProjectRoot = "",
    [int]$TimeoutSeconds = 60,
    [switch]$ListCases,
    [switch]$KeepProject,
    [switch]$Help
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

if ($Help) {
    @"
DevContext context resolve benchmark

Usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-context-resolve-benchmark.ps1

Common options:
  -BaseUrl http://localhost:18080
  -CasesPath docs/benchmarks/context-resolve/context-resolve-benchmark-cases.json
  -OutputDir docs/reports
  -ProjectRoot target/context-resolve-benchmark-custom
  -ListCases
  -KeepProject

The script creates a temporary Spring Boot fixture, asks DevContext to generate
context assets, calls /context/resolve for curated questions, and writes
Markdown/JSON reports under docs/reports.
"@ | Write-Host
    exit 0
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $PathValue))
}

function New-RunId {
    return (Get-Date).ToString("yyyyMMdd-HHmmss")
}

function Invoke-DevContextApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )
    $uri = $BaseUrl.TrimEnd("/") + $Path
    $headers = @{
        Accept = "application/json"
    }
    if ($null -eq $Body) {
        $response = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -Headers $headers -TimeoutSec $TimeoutSeconds
    } else {
        $json = $Body | ConvertTo-Json -Depth 80
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
        $response = Invoke-WebRequest `
            -UseBasicParsing `
            -Method $Method `
            -Uri $uri `
            -Headers $headers `
            -ContentType "application/json; charset=utf-8" `
            -Body $bytes `
            -TimeoutSec $TimeoutSeconds
    }

    if ($null -ne $response.RawContentStream) {
        $response.RawContentStream.Position = 0
        $reader = New-Object System.IO.StreamReader($response.RawContentStream, [System.Text.Encoding]::UTF8)
        $content = $reader.ReadToEnd()
    } else {
        $content = [string]$response.Content
    }
    if ([string]::IsNullOrWhiteSpace($content)) {
        return $null
    }
    return $content | ConvertFrom-Json
}

function Write-TextFile {
    param(
        [string]$PathValue,
        [string]$Content
    )
    $parent = Split-Path -Parent $PathValue
    if (-not (Test-Path -LiteralPath $parent)) {
        New-Item -ItemType Directory -Path $parent | Out-Null
    }
    $Content.TrimStart() | Set-Content -Encoding UTF8 -LiteralPath $PathValue
}

function Initialize-BenchmarkProject {
    param([string]$Root)

    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    $targetRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "target"))
    if (-not $resolvedRoot.StartsWith($targetRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "ProjectRoot must stay under $targetRoot for safe cleanup"
    }
    if (Test-Path -LiteralPath $resolvedRoot) {
        Remove-Item -LiteralPath $resolvedRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $resolvedRoot | Out-Null

    Write-TextFile -PathValue (Join-Path $resolvedRoot "pom.xml") -Content @"
<project>
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.acme</groupId>
  <artifactId>life-service-context-fixture</artifactId>
  <dependencies>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-data-redis</artifactId>
    </dependency>
    <dependency>
      <groupId>io.micrometer</groupId>
      <artifactId>micrometer-core</artifactId>
    </dependency>
  </dependencies>
</project>
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "README.md") -Content @"
# life-service fixture

This fixture models a flash-sale voucher order service.

Core flow:
1. Warm up flash-sale vouchers before traffic starts.
2. Validate flash-sale time and stock through Redis Lua.
3. Create voucher orders through the order command handler.
4. Close unpaid voucher orders after timeout and release stock.
5. Protect hot endpoints with sliding-window rate limiting.
6. Keep product and voucher reads fast with two-level cache and explicit invalidation.

Runtime performance numbers such as QPS and P95 must come from benchmark reports or monitoring data, not source-code structure alone.
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/resources/lua/seckill-stock.lua") -Content @"
-- flash-sale stock deduction script
-- Deduct voucher stock atomically before creating the order.
local stockKey = KEYS[1]
local userKey = KEYS[2]
local stock = tonumber(redis.call('GET', stockKey) or '0')
if stock <= 0 then
  return 1
end
if redis.call('SISMEMBER', userKey, ARGV[1]) == 1 then
  return 2
end
redis.call('DECR', stockKey)
redis.call('SADD', userKey, ARGV[1])
return 0
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/LifeServiceApplication.java") -Content @"
package com.acme.lifeservice;

import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LifeServiceApplication {
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/controller/VoucherOrderController.java") -Content @"
package com.acme.lifeservice.order.controller;

import com.acme.lifeservice.order.service.FlashSaleOrderCommandHandler;
import com.acme.lifeservice.order.metrics.OrderMetrics;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class VoucherOrderController {
    private final FlashSaleOrderCommandHandler flashSaleOrderCommandHandler;
    private final OrderMetrics orderMetrics;

    @PostMapping("/seckill")
    public String createFlashSaleVoucherOrder() {
        orderMetrics.recordSeckillRequest();
        return flashSaleOrderCommandHandler.createVoucherOrder();
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/service/FlashSaleOrderCommandHandler.java") -Content @"
package com.acme.lifeservice.order.service;

import org.springframework.transaction.annotation.Transactional;

public class FlashSaleOrderCommandHandler {
    private final SeckillLuaScriptExecutor seckillLuaScriptExecutor;
    private final VoucherOrderRepository voucherOrderRepository;

    @Transactional
    public String createVoucherOrder() {
        seckillLuaScriptExecutor.validateFlashSaleTimeAndStock();
        voucherOrderRepository.saveVoucherOrder();
        return "created";
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/service/SeckillLuaScriptExecutor.java") -Content @"
package com.acme.lifeservice.order.service;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

public class SeckillLuaScriptExecutor {
    private final StringRedisTemplate stringRedisTemplate;
    private final DefaultRedisScript<Long> flashSaleLuaScript;

    public Long validateFlashSaleTimeAndStock() {
        // Redis Lua keeps flash-sale time and stock validation atomic.
        return stringRedisTemplate.execute(flashSaleLuaScript);
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/service/VoucherOrderRepository.java") -Content @"
package com.acme.lifeservice.order.service;

public class VoucherOrderRepository {
    public void saveVoucherOrder() {
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/job/OrderCloseScheduler.java") -Content @"
package com.acme.lifeservice.order.job;

import com.acme.lifeservice.order.service.VoucherOrderCloseService;
import org.springframework.scheduling.annotation.Scheduled;

public class OrderCloseScheduler {
    private final VoucherOrderCloseService voucherOrderCloseService;

    @Scheduled(fixedDelay = 30000)
    public void closeTimeoutVoucherOrders() {
        voucherOrderCloseService.closeExpiredOrdersAndReleaseStock();
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/service/VoucherOrderCloseService.java") -Content @"
package com.acme.lifeservice.order.service;

public class VoucherOrderCloseService {
    private final StockReleaseService stockReleaseService;

    public void closeExpiredOrdersAndReleaseStock() {
        stockReleaseService.releaseVoucherStock();
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/service/StockReleaseService.java") -Content @"
package com.acme.lifeservice.order.service;

public class StockReleaseService {
    public void releaseVoucherStock() {
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/controller/VoucherOrderPaymentController.java") -Content @"
package com.acme.lifeservice.order.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/payments")
public class VoucherOrderPaymentController {
    @PostMapping("/callback")
    public String handlePaymentCallback() {
        return "ok";
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/common/rate/RateLimitAspect.java") -Content @"
package com.acme.lifeservice.common.rate;

public class RateLimitAspect {
    private final SlidingWindowRateLimiter slidingWindowRateLimiter;

    public void checkRateLimit() {
        slidingWindowRateLimiter.acquire();
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/common/rate/SlidingWindowRateLimiter.java") -Content @"
package com.acme.lifeservice.common.rate;

public class SlidingWindowRateLimiter {
    public boolean acquire() {
        return true;
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/common/cache/TwoLevelCacheClient.java") -Content @"
package com.acme.lifeservice.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;

public class TwoLevelCacheClient {
    private final StringRedisTemplate stringRedisTemplate;
    private final Cache<String, String> localCache;

    public String getProductSnapshot(String key) {
        return localCache.getIfPresent(key);
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/common/cache/CacheInvalidationService.java") -Content @"
package com.acme.lifeservice.common.cache;

public class CacheInvalidationService {
    private final TwoLevelCacheClient twoLevelCacheClient;

    public void invalidateProductSnapshot() {
        // Explicit cache invalidation protects read-through cache freshness.
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/metrics/OrderMetrics.java") -Content @"
package com.acme.lifeservice.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

public class OrderMetrics {
    private final MeterRegistry meterRegistry;

    public void recordSeckillRequest() {
        Counter.builder("life_service_seckill_requests").register(meterRegistry).increment();
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/auth/controller/AuthController.java") -Content @"
package com.acme.lifeservice.auth.controller;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    @PostMapping("/login")
    public String login() {
        return "token";
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/auth/AuthInterceptor.java") -Content @"
package com.acme.lifeservice.auth;

public class AuthInterceptor {
    public boolean verifyToken() {
        return true;
    }
}
"@

    return $resolvedRoot
}

function New-BenchmarkProject {
    param(
        [string]$Root,
        [string]$RunId
    )
    $body = @{
        name = "context-resolve-benchmark-$RunId"
        rootPath = $Root
        defaultBranch = "main"
    }
    $response = Invoke-DevContextApi -Method "Post" -Path "/api/projects" -Body $body
    if (-not $response.success) {
        throw "Create project failed: $($response.message)"
    }
    return $response.data
}

function Generate-ContextAssets {
    param([long]$ProjectId)
    $body = @{
        overwriteGenerated = $true
        overwriteManual = $false
    }
    $response = Invoke-DevContextApi -Method "Post" -Path "/api/projects/$ProjectId/context/generate" -Body $body
    if (-not $response.success) {
        throw "Context generation failed: $($response.message)"
    }
    return $response.data
}

function Resolve-Question {
    param(
        [long]$ProjectId,
        [object]$Case
    )
    $body = @{
        question = [string]$Case.question
        maxItems = [int]$Case.maxItems
    }
    $response = Invoke-DevContextApi -Method "Post" -Path "/api/projects/$ProjectId/context/resolve" -Body $body
    if (-not $response.success) {
        throw "Context resolve failed: $($response.message)"
    }
    return $response.data
}

function Get-CandidateFiles {
    param([object]$ResolveData)
    $files = New-Object System.Collections.ArrayList
    foreach ($candidate in @($ResolveData.candidates)) {
        [void]$files.Add([string]$candidate.file)
    }
    return @($files)
}

function Get-MatchedTerms {
    param([object]$ResolveData)
    $terms = New-Object System.Collections.ArrayList
    foreach ($term in @($ResolveData.queryTerms)) {
        [void]$terms.Add(([string]$term).ToLowerInvariant())
    }
    foreach ($candidate in @($ResolveData.candidates)) {
        foreach ($term in @($candidate.matchedTerms)) {
            [void]$terms.Add(([string]$term).ToLowerInvariant())
        }
    }
    return @($terms | Select-Object -Unique)
}

function Test-AnyExpectedFile {
    param(
        [string[]]$Actual,
        [string[]]$Expected
    )
    if ($Expected.Count -eq 0) {
        return $true
    }
    foreach ($file in $Actual) {
        if ($Expected -contains $file) {
            return $true
        }
    }
    return $false
}

function Test-Terms {
    param(
        [string[]]$Actual,
        [string[]]$Expected
    )
    if ($Expected.Count -eq 0) {
        return $true
    }
    foreach ($term in $Expected) {
        if (-not ($Actual -contains $term.ToLowerInvariant())) {
            return $false
        }
    }
    return $true
}

function Test-EvidenceGuard {
    param([object]$ResolveData)
    $runtimeEvidenceText = -join @([char]0x8FD0, [char]0x884C, [char]0x8BC1, [char]0x636E)
    $performanceConclusionText = -join @([char]0x771F, [char]0x5B9E, [char]0x6027, [char]0x80FD, [char]0x7ED3, [char]0x8BBA)
    $texts = New-Object System.Collections.ArrayList
    foreach ($note in @($ResolveData.notes)) {
        [void]$texts.Add([string]$note)
    }
    foreach ($item in @($ResolveData.items)) {
        [void]$texts.Add([string]$item.content)
    }
    foreach ($text in $texts) {
        if (($text.Contains($runtimeEvidenceText)) `
                -or ($text.Contains($performanceConclusionText)) `
                -or ($text.ToLowerInvariant() -like "*runtime evidence*")) {
            return $true
        }
    }
    return $false
}

function Measure-Cases {
    param(
        [long]$ProjectId,
        [object[]]$Cases
    )
    $caseResults = New-Object System.Collections.ArrayList

    foreach ($case in $Cases) {
        Write-Host "Resolving case: $($case.name)"
        $resolved = Resolve-Question -ProjectId $ProjectId -Case $case
        $candidateFiles = @(Get-CandidateFiles -ResolveData $resolved)
        $matchedTerms = @(Get-MatchedTerms -ResolveData $resolved)
        $expectedFiles = @($case.expectedFiles | ForEach-Object { [string]$_ })
        $forbiddenFiles = @($case.forbiddenFiles | ForEach-Object { [string]$_ })
        $expectedTerms = @($case.expectedTerms | ForEach-Object { [string]$_ })
        $top1Files = @($candidateFiles | Select-Object -First 1)
        $top3Files = @($candidateFiles | Select-Object -First 3)
        $forbiddenHits = @($candidateFiles | Where-Object { $forbiddenFiles -contains $_ })
        $top1Hit = Test-AnyExpectedFile -Actual $top1Files -Expected $expectedFiles
        $top3Hit = Test-AnyExpectedFile -Actual $top3Files -Expected $expectedFiles
        $termHit = Test-Terms -Actual $matchedTerms -Expected $expectedTerms
        $deepScanPass = ([bool]$resolved.needsDeepScan) -eq ([bool]$case.expectedNeedsDeepScan)
        $forbiddenPass = $forbiddenHits.Count -eq 0
        $evidenceGuardPass = if ([bool]$case.requiresEvidenceGuard) { Test-EvidenceGuard -ResolveData $resolved } else { $true }

        [void]$caseResults.Add([pscustomobject]@{
            name = $case.name
            question = $case.question
            status = $resolved.status
            needsDeepScan = [bool]$resolved.needsDeepScan
            expectedNeedsDeepScan = [bool]$case.expectedNeedsDeepScan
            top1Hit = [bool]$top1Hit
            top3Hit = [bool]$top3Hit
            termHit = [bool]$termHit
            deepScanPass = [bool]$deepScanPass
            evidenceGuardPass = [bool]$evidenceGuardPass
            forbiddenPass = [bool]$forbiddenPass
            expectedFiles = $expectedFiles
            returnedFiles = $candidateFiles
            forbiddenHits = $forbiddenHits
            expectedTerms = $expectedTerms
            matchedTerms = $matchedTerms
            notes = @($resolved.notes)
            candidateCount = $candidateFiles.Count
        })
    }

    return @($caseResults)
}

function Measure-Rate {
    param(
        [object[]]$Items,
        [string]$Property
    )
    if ($Items.Count -eq 0) {
        return 0
    }
    $hits = @($Items | Where-Object { [bool]($_.$Property) }).Count
    return [math]::Round($hits / $Items.Count, 3)
}

function Measure-Average {
    param(
        [object[]]$Items,
        [string]$Property
    )
    if ($Items.Count -eq 0) {
        return 0
    }
    $sum = 0
    foreach ($item in $Items) {
        $sum += [double]$item.$Property
    }
    return [math]::Round($sum / $Items.Count, 3)
}

function New-Summary {
    param([object[]]$Cases)
    return [pscustomobject]@{
        caseCount = $Cases.Count
        top1HitRate = Measure-Rate -Items $Cases -Property "top1Hit"
        top3HitRate = Measure-Rate -Items $Cases -Property "top3Hit"
        termHitRate = Measure-Rate -Items $Cases -Property "termHit"
        deepScanAccuracy = Measure-Rate -Items $Cases -Property "deepScanPass"
        evidenceGuardPassRate = Measure-Rate -Items $Cases -Property "evidenceGuardPass"
        forbiddenPassRate = Measure-Rate -Items $Cases -Property "forbiddenPass"
        averageCandidateCount = Measure-Average -Items $Cases -Property "candidateCount"
    }
}

function Write-Reports {
    param(
        [string]$RunId,
        [object]$Project,
        [object]$Generation,
        [object]$Summary,
        [object[]]$Cases,
        [string]$FixtureRoot,
        [string]$OutputDir
    )
    $resolvedOutputDir = Resolve-RepoPath $OutputDir
    if (-not (Test-Path -LiteralPath $resolvedOutputDir)) {
        New-Item -ItemType Directory -Path $resolvedOutputDir | Out-Null
    }

    $jsonPath = Join-Path $resolvedOutputDir "context-resolve-benchmark-$RunId.json"
    $mdPath = Join-Path $resolvedOutputDir "context-resolve-benchmark-$RunId.md"
    $payload = [pscustomobject]@{
        runId = $RunId
        baseUrl = $BaseUrl
        project = $Project
        fixtureRoot = $FixtureRoot
        generatedFiles = $Generation.generatedFiles
        generatedSkippedFiles = $Generation.generatedSkippedFiles
        summary = $Summary
        cases = $Cases
        generatedAt = (Get-Date).ToString("o")
    }
    $payload | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -LiteralPath $jsonPath

    $lines = @()
    $lines += "# DevContext Context Resolve Benchmark $RunId"
    $lines += ""
    $lines += "## Environment"
    $lines += ""
    $lines += "- Base URL: ``$BaseUrl``"
    $lines += "- Project ID: ``$($Project.id)``"
    $lines += "- Fixture root: ``$FixtureRoot``"
    $lines += ""
    $lines += "## Summary"
    $lines += ""
    $lines += "| Cases | Top1Hit | Top3Hit | TermHit | DeepScanAccuracy | EvidenceGuard | ForbiddenPass | AvgCandidates |"
    $lines += "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    $lines += "| $($Summary.caseCount) | $($Summary.top1HitRate) | $($Summary.top3HitRate) | $($Summary.termHitRate) | $($Summary.deepScanAccuracy) | $($Summary.evidenceGuardPassRate) | $($Summary.forbiddenPassRate) | $($Summary.averageCandidateCount) |"
    $lines += ""
    $lines += "## Cases"
    $lines += ""
    $lines += "| Case | Status | DeepScan | Top1 | Top3 | Terms | Evidence | Forbidden | Returned files | Notes |"
    $lines += "| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | --- | --- |"
    foreach ($case in $Cases) {
        $returned = ($case.returnedFiles | Select-Object -First 5) -join "<br>"
        $notes = ($case.notes | Select-Object -First 2) -join "<br>"
        $lines += "| $($case.name) | $($case.status) | $($case.needsDeepScan) | $($case.top1Hit) | $($case.top3Hit) | $($case.termHit) | $($case.evidenceGuardPass) | $($case.forbiddenPass) | $returned | $notes |"
    }
    $lines += ""
    $lines += "## Generated Assets"
    $lines += ""
    foreach ($file in @($Generation.generatedFiles)) {
        $lines += "- ``$file``"
    }
    $lines += ""
    $lines += "## Notes"
    $lines += ""
    $lines += "- This benchmark validates static context routing. It does not claim runtime performance."
    $lines += "- Performance questions should pass the evidence guard by asking for runtime evidence."
    $lines += "- A failed Top1 case can still be usable if Top3 is stable, but it means the UI should show multiple candidates."

    $lines | Set-Content -Encoding UTF8 -LiteralPath $mdPath

    return [pscustomobject]@{
        jsonPath = $jsonPath
        markdownPath = $mdPath
    }
}

$resolvedCasesPath = Resolve-RepoPath $CasesPath
if (-not (Test-Path -LiteralPath $resolvedCasesPath)) {
    throw "CasesPath not found: $resolvedCasesPath"
}
$caseConfig = Get-Content -Raw -Encoding UTF8 -LiteralPath $resolvedCasesPath | ConvertFrom-Json
$cases = @($caseConfig.cases)

if ($ListCases) {
    foreach ($case in $cases) {
        Write-Host "$($case.name): $($case.question)"
    }
    exit 0
}

if ($cases.Count -eq 0) {
    throw "No context resolve benchmark cases found"
}

$runId = New-RunId
if ([string]::IsNullOrWhiteSpace($ProjectRoot)) {
    $ProjectRoot = Join-Path $repoRoot "target/context-resolve-benchmark-$runId"
}
$resolvedProjectRoot = Resolve-RepoPath $ProjectRoot

Write-Host "Checking DevContext health at $BaseUrl"
[void](Invoke-DevContextApi -Method "Get" -Path "/api/health")

Write-Host "Creating benchmark fixture at $resolvedProjectRoot"
$fixtureRoot = Initialize-BenchmarkProject -Root $resolvedProjectRoot

Write-Host "Registering benchmark project"
$project = New-BenchmarkProject -Root $fixtureRoot -RunId $runId

Write-Host "Generating project context assets"
$generation = Generate-ContextAssets -ProjectId ([long]$project.id)

Write-Host "Running context resolve evaluation with $($cases.Count) cases"
$caseResults = Measure-Cases -ProjectId ([long]$project.id) -Cases $cases
$summary = New-Summary -Cases $caseResults
$report = Write-Reports -RunId $runId -Project $project -Generation $generation -Summary $summary -Cases $caseResults -FixtureRoot $fixtureRoot -OutputDir $OutputDir

if (-not $KeepProject) {
    Write-Host "Benchmark fixture remains under target for report inspection."
}

Write-Host ""
Write-Host "Context resolve benchmark complete"
Write-Host "Top1HitRate:           $($summary.top1HitRate)"
Write-Host "Top3HitRate:           $($summary.top3HitRate)"
Write-Host "TermHitRate:           $($summary.termHitRate)"
Write-Host "DeepScanAccuracy:      $($summary.deepScanAccuracy)"
Write-Host "EvidenceGuardPassRate: $($summary.evidenceGuardPassRate)"
Write-Host "ForbiddenPassRate:     $($summary.forbiddenPassRate)"
Write-Host "Markdown report:       $($report.markdownPath)"
Write-Host "JSON report:           $($report.jsonPath)"
