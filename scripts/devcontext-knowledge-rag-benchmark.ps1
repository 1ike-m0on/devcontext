param(
    [string]$BaseUrl = "http://localhost:18080",
    [string]$CasesPath = "docs/benchmarks/knowledge-rag/knowledge-rag-acceptance-cases.json",
    [string]$OutputDir = "docs/reports",
    [int]$TopK = 5,
    [long]$SourceId = 0,
    [string]$SourceRoot = "",
    [string]$SourceType = "project_ai_docs",
    [int]$TimeoutSeconds = 120,
    [int]$CaseLimit = 0,
    [switch]$SkipAsk,
    [switch]$Reindex,
    [switch]$ListCases,
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

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot
$DefaultCasesPath = "docs/benchmarks/knowledge-rag/knowledge-rag-acceptance-cases.json"

if ($Help) {
    @"
DevContext knowledge RAG acceptance benchmark

Usage:
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\devcontext-knowledge-rag-benchmark.ps1

Common options:
  -BaseUrl http://localhost:18080
  -CasesPath docs/benchmarks/knowledge-rag/knowledge-rag-acceptance-cases.json
  -SourceId 12
  -Reindex
  -SkipAsk
  -ListCases
  -CaseLimit 2
  -TopK 5
  -OutputDir docs/reports

By default, the script creates a temporary life-service fixture, indexes it as a
project_ai_docs source, evaluates curated knowledge-search and RAG-answer cases,
and writes Markdown/JSON reports under docs/reports. Clean checkouts fall back
to built-in cases when the private docs cases file is absent.
"@ | Write-Host
    exit 0
}

. (Join-Path $PSScriptRoot "devcontext-report-metadata.ps1")

function New-RunId {
    return (Get-Date).ToString("yyyyMMdd-HHmmss")
}

function Resolve-RepoPath {
    param([string]$PathValue)
    if ([System.IO.Path]::IsPathRooted($PathValue)) {
        return [System.IO.Path]::GetFullPath($PathValue)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $repoRoot $PathValue))
}

function As-Array {
    param([object]$Value)
    if ($null -eq $Value) {
        return @()
    }
    if ($Value -is [System.Array]) {
        return @($Value | ForEach-Object { $_ })
    }
    if ($Value -is [System.Collections.IEnumerable] -and -not ($Value -is [string])) {
        return @($Value | ForEach-Object { $_ })
    }
    return @($Value)
}

function Get-StringArray {
    param([object]$Value)
    $items = @()
    foreach ($item in (As-Array $Value)) {
        if ($null -ne $item -and -not [string]::IsNullOrWhiteSpace([string]$item)) {
            $items += [string]$item
        }
    }
    return $items
}

function Has-Property {
    param(
        [object]$Object,
        [string]$Name
    )
    return $null -ne $Object -and $Object.PSObject.Properties.Name -contains $Name
}

function Get-PropertyValue {
    param(
        [object]$Object,
        [string]$Name,
        [object]$Default = $null
    )
    if (Has-Property $Object $Name) {
        return $Object.$Name
    }
    return $Default
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

    try {
        if ($null -eq $Body) {
            $response = Invoke-WebRequest -UseBasicParsing -Method $Method -Uri $uri -Headers $headers -TimeoutSec $TimeoutSeconds
        } else {
            $json = $Body | ConvertTo-Json -Depth 100
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
    } catch {
        $status = ""
        $content = ""
        if ($null -ne $_.Exception.Response) {
            $status = "HTTP $([int]$_.Exception.Response.StatusCode): "
            try {
                $stream = $_.Exception.Response.GetResponseStream()
                if ($null -ne $stream) {
                    $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8)
                    $content = $reader.ReadToEnd()
                }
            } catch {
                $content = ""
            }
        }
        throw ($status + $content + " " + $_.Exception.Message).Trim()
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

function Get-DefaultKnowledgeRagAcceptanceCases {
    $json = @'
[
  {
    "name": "benchmark p95 uses required primary evidence",
    "query": "What is the benchmark p95 latency for flash sale checkout?",
    "topK": 1,
    "expectedPlanEvidenceTypes": ["BENCHMARK"],
    "expectedEvidenceTypes": ["BENCHMARK"],
    "requiredEvidenceTypesAtK": ["BENCHMARK"],
    "expectedScoreReasonsAtK": [
      "required_evidence:BENCHMARK",
      "preferred_evidence:BENCHMARK*",
      "source_reliability:primary",
      "specific_engineering_evidence:benchmark_file"
    ],
    "expectedSourcePaths": ["docs/benchmarks/load-test.md"],
    "forbiddenSourcePaths": ["docs/roadmap/v3-future-performance.md"],
    "maxGenericDocsAtK": 0,
    "expectedEvidenceEvaluationStatus": "sufficient",
    "expectedNoAnswerRequired": false,
    "expectedEvaluationMatchedRequired": ["BENCHMARK"],
    "answerMustContainAll": ["p95", "135ms"],
    "answerMustContainAny": ["[S1]", "docs/benchmarks/load-test.md"],
    "answerMustNotContain": ["not available", "Insufficient evidence"],
    "noAnswerExpected": false
  },
  {
    "name": "missing throughput benchmark triggers no-answer guard",
    "query": "What is the sustained throughput SLO for the order metrics dashboard?",
    "topK": 1,
    "expectedPlanEvidenceTypes": ["BENCHMARK", "OBSERVABILITY"],
    "expectedEvidenceTypes": ["OBSERVABILITY"],
    "expectedScoreReasonsAtK": [
      "preferred_evidence:OBSERVABILITY*",
      "source_reliability:primary"
    ],
    "expectedSourcePaths": [
      "src/main/java/com/acme/lifeservice/order/metrics/OrderMetrics.java",
      "monitoring/*"
    ],
    "forbiddenSourcePaths": ["docs/benchmarks/*"],
    "maxGenericDocsAtK": 1,
    "expectedEvidenceEvaluationStatus": "insufficient_evidence",
    "expectedNoAnswerRequired": true,
    "expectedEvaluationMissingRequired": ["BENCHMARK"],
    "answerMustContainAll": ["Insufficient evidence", "BENCHMARK", "no-answer guard"],
    "answerMustNotContain": ["135ms", "240ms"],
    "noAnswerExpected": true
  },
  {
    "name": "cache invalidation prefers primary cache evidence",
    "query": "How does cache invalidation handle Redis delete failure?",
    "topK": 3,
    "expectedPlanEvidenceTypes": ["CACHE", "SERVICE_CODE"],
    "expectedEvidenceTypes": ["CACHE"],
    "expectedScoreReasonsAtK": [
      "preferred_evidence:CACHE*",
      "source_reliability:primary",
      "specific_engineering_evidence:cache_file"
    ],
    "expectedSourcePaths": [
      "docs/design/cache-aside.md",
      "src/main/java/com/acme/lifeservice/common/cache/CacheInvalidationService.java"
    ],
    "forbiddenSourcePaths": [".ai/generated/core-flows.md"],
    "maxGenericDocsAtK": 1,
    "expectedEvidenceEvaluationStatus": "sufficient",
    "expectedNoAnswerRequired": false,
    "answerMustContainAll": ["Redis", "cache"],
    "answerMustContainAny": ["retry", "ls_cache_delete_task", "CacheInvalidationService"],
    "answerMustNotContain": ["benchmark"],
    "noAnswerExpected": false
  },
  {
    "name": "sql index question uses schema evidence",
    "query": "Which SQL indexes support voucher order lookup?",
    "topK": 3,
    "expectedPlanEvidenceTypes": ["SQL_SCHEMA"],
    "expectedEvidenceTypes": ["SQL_SCHEMA"],
    "expectedScoreReasonsAtK": [
      "preferred_evidence:SQL_SCHEMA*",
      "source_reliability:primary",
      "specific_engineering_evidence:sql_file"
    ],
    "expectedSourcePaths": ["src/main/resources/db/schema.sql"],
    "forbiddenSourcePaths": [".ai/generated/core-flows.md", "docs/roadmap/*"],
    "maxGenericDocsAtK": 0,
    "expectedEvidenceEvaluationStatus": "sufficient",
    "expectedNoAnswerRequired": false,
    "answerMustContainAll": ["idx_voucher_order_user_id", "voucher_order"],
    "answerMustContainAny": ["SQL", "schema", "index"],
    "answerMustNotContain": ["Insufficient evidence"],
    "noAnswerExpected": false
  },
  {
    "name": "payment callback cites queue and service evidence",
    "query": "How does payment callback RocketMQ idempotency work?",
    "topK": 4,
    "expectedPlanEvidenceTypes": ["QUEUE", "SERVICE_CODE"],
    "expectedEvidenceTypes": ["QUEUE", "SERVICE_CODE"],
    "expectedScoreReasonsAtK": [
      "preferred_evidence:QUEUE*",
      "source_reliability:primary"
    ],
    "expectedSourcePaths": [
      "src/main/java/com/acme/lifeservice/payment/mq/PaymentCallbackConsumer.java",
      "src/main/java/com/acme/lifeservice/payment/service/PaymentIdempotencyService.java"
    ],
    "forbiddenSourcePaths": [".ai/generated/core-flows.md"],
    "maxGenericDocsAtK": 1,
    "expectedEvidenceEvaluationStatus": "sufficient",
    "expectedNoAnswerRequired": false,
    "answerMustContainAll": ["RocketMQ", "idempotency"],
    "answerMustContainAny": ["PaymentCallbackConsumer", "markProcessing", "markSuccess"],
    "answerMustNotContain": ["Insufficient evidence"],
    "noAnswerExpected": false
  },
  {
    "name": "manual overview remains valid fallback",
    "query": "Give a high level overview of operations for the local demo.",
    "topK": 3,
    "expectedPlanEvidenceTypes": ["MANUAL_DOC", "GENERATED_DOC"],
    "expectedEvidenceTypes": ["MANUAL_DOC"],
    "expectedScoreReasonsAtK": [
      "preferred_evidence:MANUAL_DOC*"
    ],
    "expectedSourcePaths": [".ai/manual/operator-runbook.md"],
    "forbiddenSourcePaths": ["docs/benchmarks/*"],
    "maxGenericDocsAtK": 3,
    "expectedEvidenceEvaluationStatus": "sufficient",
    "expectedNoAnswerRequired": false,
    "answerMustContainAll": ["Docker Compose", "Prometheus", "Grafana"],
    "answerMustContainAny": ["runbook", "operator"],
    "answerMustNotContain": ["135ms", "Insufficient evidence"],
    "noAnswerExpected": false
  }
]
'@
    return @($json | ConvertFrom-Json)
}

function Initialize-KnowledgeFixture {
    param([string]$Root)

    $resolvedRoot = [System.IO.Path]::GetFullPath($Root)
    New-Item -ItemType Directory -Path $resolvedRoot -Force | Out-Null

    Write-TextFile -PathValue (Join-Path $resolvedRoot "README.md") -Content @"
# life-service knowledge fixture

This fixture models a flash-sale voucher service. It intentionally contains
code, SQL, deployment, monitoring, and design evidence so DevContext can prove
that knowledge answers are grounded in inspectable project assets.

Core flow:
1. Warm up voucher stock and user eligibility into Redis before traffic starts.
2. Validate flash-sale time, duplicate purchase, and stock through Redis Lua.
3. Create voucher orders after the atomic stock check succeeds.
4. Consume payment callback messages asynchronously through RocketMQ.
5. Keep hot merchant, voucher, and product reads fast with Cache Aside.

Runtime numbers and latency percentiles must not be invented from source-code
structure or overview notes alone.
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "docs/design/cache-aside.md") -Content @"
# Cache Aside design

The service uses Cache Aside for read-heavy merchant, product, and voucher
display data.

Read flow:
1. Query Caffeine local cache first.
2. If local cache misses, query Redis.
3. If Redis misses, query MySQL and write the result back to Redis and Caffeine.

Update and invalidation flow:
1. Update MySQL first.
2. Delete Redis and Caffeine cache entries after the database update succeeds.
3. If Redis deletion fails, write a row into the `ls_cache_delete_task` retry
   table.
4. A scheduled compensation job retries failed cache deletions.
5. All cached entries also keep a TTL as the final safety net.

This design avoids long-lived stale data after invalidation failure without
requiring every read request to hit MySQL.
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "docs/roadmap/v3-future-performance.md") -Content @"
# Future performance ideas

This document is intentionally speculative. It lists possible future benchmark
ideas but does not contain current QPS, P95, or production evidence.
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "docs/benchmarks/load-test.md") -Content @"
# Flash sale load test

This is the accepted benchmark report for the flash sale checkout path.

Result summary:
- p95 latency: 135ms
- p99 latency: 240ms
- error rate: 0.02%

Use this report, not roadmap notes, when answering current latency questions.
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot ".ai/manual/operator-runbook.md") -Content @"
# Operator runbook

The service is operated through Docker Compose for local demos. Operators should
check MySQL, Redis, RocketMQ, Prometheus, and Grafana health before traffic.
This manual runbook is a fallback overview and does not replace code, SQL,
configuration, or measured evidence for detailed questions.
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/common/cache/TwoLevelCacheClient.java") -Content @"
package com.acme.lifeservice.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.data.redis.core.StringRedisTemplate;

public class TwoLevelCacheClient {
    private final Cache<String, String> caffeine;
    private final StringRedisTemplate redisTemplate;

    public TwoLevelCacheClient(Cache<String, String> caffeine, StringRedisTemplate redisTemplate) {
        this.caffeine = caffeine;
        this.redisTemplate = redisTemplate;
    }

    public Optional<String> get(String key, Supplier<Optional<String>> dbLoader, Duration ttl) {
        String local = caffeine.getIfPresent(key);
        if (local != null) {
            return Optional.of(local);
        }
        String remote = redisTemplate.opsForValue().get(key);
        if (remote != null) {
            caffeine.put(key, remote);
            return Optional.of(remote);
        }
        Optional<String> loaded = dbLoader.get();
        loaded.ifPresent(value -> {
            redisTemplate.opsForValue().set(key, value, ttl);
            caffeine.put(key, value);
        });
        return loaded;
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/common/cache/CacheInvalidationService.java") -Content @"
package com.acme.lifeservice.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.data.redis.core.StringRedisTemplate;

public class CacheInvalidationService {
    private final Cache<String, String> caffeine;
    private final StringRedisTemplate redisTemplate;
    private final CacheDeleteTaskRepository retryRepository;

    public CacheInvalidationService(Cache<String, String> caffeine, StringRedisTemplate redisTemplate, CacheDeleteTaskRepository retryRepository) {
        this.caffeine = caffeine;
        this.redisTemplate = redisTemplate;
        this.retryRepository = retryRepository;
    }

    public void invalidateAfterDatabaseUpdate(String key) {
        caffeine.invalidate(key);
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ex) {
            retryRepository.saveFailedDeleteTask(key, ex.getMessage());
        }
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/resources/db/schema.sql") -Content @"
create table voucher_order (
    id bigint primary key,
    user_id bigint not null,
    voucher_id bigint not null,
    status varchar(32) not null,
    created_at timestamp not null,
    paid_at timestamp null
);

create index idx_voucher_order_user_id on voucher_order(user_id);
create index idx_voucher_order_status_created_at on voucher_order(status, created_at);
create index idx_voucher_order_voucher_id on voucher_order(voucher_id);

create table ls_cache_delete_task (
    id bigint primary key,
    cache_key varchar(255) not null,
    status varchar(32) not null,
    retry_count int not null,
    last_error varchar(1024),
    created_at timestamp not null
);
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/resources/mapper/VoucherOrderMapper.xml") -Content @"
<mapper namespace="com.acme.lifeservice.order.mapper.VoucherOrderMapper">
  <select id="findByUserId" resultType="VoucherOrder">
    select * from voucher_order where user_id = #{userId} order by created_at desc
  </select>
  <select id="findPendingOrders" resultType="VoucherOrder">
    select * from voucher_order where status = 'PENDING' and created_at &lt; #{deadline}
  </select>
  <select id="findByVoucherId" resultType="VoucherOrder">
    select * from voucher_order where voucher_id = #{voucherId}
  </select>
</mapper>
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/order/metrics/OrderMetrics.java") -Content @"
package com.acme.lifeservice.order.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

public class OrderMetrics {
    private final Counter seckillRequests;
    private final Counter seckillFailures;
    private final Timer orderCreateTimer;

    public OrderMetrics(MeterRegistry registry) {
        this.seckillRequests = Counter.builder("life_service_seckill_requests").register(registry);
        this.seckillFailures = Counter.builder("life_service_seckill_failures").register(registry);
        this.orderCreateTimer = Timer.builder("life_service_order_create_latency").register(registry);
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "monitoring/prometheus.yml") -Content @"
scrape_configs:
  - job_name: life-service
    metrics_path: /actuator/prometheus
    static_configs:
      - targets: ["life-service:8080"]
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "monitoring/grafana-dashboard.json") -Content @"
{
  "title": "life-service flash sale dashboard",
  "panels": [
    { "title": "Seckill requests", "expr": "life_service_seckill_requests_total" },
    { "title": "Order latency", "expr": "life_service_order_create_latency_seconds" }
  ]
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/resources/application.yml") -Content @"
spring:
  datasource:
    url: jdbc:mysql://mysql:3306/life_service
  data:
    redis:
      host: redis
rocketmq:
  name-server: rocketmq:9876
management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,metrics
  endpoint:
    prometheus:
      enabled: true
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "docker-compose.yml") -Content @"
services:
  mysql:
    image: mysql:8
  redis:
    image: redis:7
  rocketmq:
    image: apache/rocketmq:5
  prometheus:
    image: prom/prometheus
    volumes:
      - ./monitoring/prometheus.yml:/etc/prometheus/prometheus.yml
  grafana:
    image: grafana/grafana
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/payment/mq/PaymentCallbackConsumer.java") -Content @"
package com.acme.lifeservice.payment.mq;

import com.acme.lifeservice.payment.service.PaymentIdempotencyService;

public class PaymentCallbackConsumer {
    private final PaymentIdempotencyService idempotencyService;

    public PaymentCallbackConsumer(PaymentIdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    public void consume(PaymentEvent event) {
        if (!idempotencyService.markProcessing(event.paymentNo())) {
            return;
        }
        // RocketMQ consumer updates order payment status asynchronously.
        idempotencyService.markSuccess(event.paymentNo());
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/payment/mq/PaymentEventProducer.java") -Content @"
package com.acme.lifeservice.payment.mq;

public class PaymentEventProducer {
    public void publishPaymentEvent(PaymentEvent event) {
        // Send payment_event through RocketMQ.
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/java/com/acme/lifeservice/payment/service/PaymentIdempotencyService.java") -Content @"
package com.acme.lifeservice.payment.service;

public class PaymentIdempotencyService {
    public boolean markProcessing(String paymentNo) {
        return true;
    }

    public void markSuccess(String paymentNo) {
    }
}
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot "src/main/resources/lua/seckill-stock.lua") -Content @"
local stockKey = KEYS[1]
local userKey = KEYS[2]
local userId = ARGV[1]
local stock = tonumber(redis.call('GET', stockKey) or '0')
if stock <= 0 then
  return 1
end
if redis.call('SISMEMBER', userKey, userId) == 1 then
  return 2
end
redis.call('DECR', stockKey)
redis.call('SADD', userKey, userId)
return 0
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot ".ai/generated/core-flows.md") -Content @"
# Generated core flows

The project provides flash-sale order creation, payment callback consumption,
cache invalidation, and observability. This generated summary is intentionally
short; detailed answers must cite code, SQL, config, or design documents.
"@

    Write-TextFile -PathValue (Join-Path $resolvedRoot ".ai/code-map.json") -Content @"
{
  "modules": [
    {
      "name": "cache",
      "files": [
        "src/main/java/com/acme/lifeservice/common/cache/TwoLevelCacheClient.java",
        "src/main/java/com/acme/lifeservice/common/cache/CacheInvalidationService.java"
      ]
    },
    {
      "name": "payment-mq",
      "files": [
        "src/main/java/com/acme/lifeservice/payment/mq/PaymentCallbackConsumer.java",
        "src/main/java/com/acme/lifeservice/payment/mq/PaymentEventProducer.java"
      ]
    }
  ]
}
"@

    return $resolvedRoot
}

function Normalize-PathText {
    param([string]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return ([string]$Value).Replace("\", "/").TrimStart("./").ToLowerInvariant()
}

function Test-PathMatches {
    param(
        [string]$PathValue,
        [string[]]$Patterns
    )
    $Patterns = @(Get-StringArray $Patterns)
    $path = Normalize-PathText $PathValue
    foreach ($pattern in $Patterns) {
        $normalizedPattern = Normalize-PathText $pattern
        if ($path -like $normalizedPattern) {
            return $true
        }
    }
    return $false
}

function Get-ResultEvidenceTypes {
    param([object]$Result)
    return Get-StringArray (Get-PropertyValue $Result "evidenceTypes" @())
}

function Get-ResultScoreReasons {
    param([object]$Result)
    return Get-StringArray (Get-PropertyValue $Result "scoreReasons" @())
}

function Test-ArrayContainsAll {
    param(
        [string[]]$Actual,
        [string[]]$Expected
    )
    $Actual = @(Get-StringArray $Actual)
    $Expected = @(Get-StringArray $Expected)
    foreach ($expectedValue in $Expected) {
        if (-not ($Actual -contains $expectedValue)) {
            return $false
        }
    }
    return $true
}

function Get-ResultsAtK {
    param(
        [object[]]$Results,
        [int]$Limit
    )
    return @($Results | Select-Object -First $Limit)
}

function Test-AnyEvidenceAtK {
    param(
        [object[]]$Results,
        [string[]]$Expected,
        [int]$Limit
    )
    $Expected = @(Get-StringArray $Expected)
    if ($Expected.Count -eq 0) {
        return $true
    }
    foreach ($result in (Get-ResultsAtK $Results $Limit)) {
        $types = Get-ResultEvidenceTypes $result
        foreach ($expectedType in $Expected) {
            if ($types -contains $expectedType) {
                return $true
            }
        }
    }
    return $false
}

function Test-AllEvidenceAtK {
    param(
        [object[]]$Results,
        [string[]]$Expected,
        [int]$Limit
    )
    $Expected = @(Get-StringArray $Expected)
    foreach ($expectedType in $Expected) {
        if (-not (Test-AnyEvidenceAtK -Results $Results -Expected @($expectedType) -Limit $Limit)) {
            return $false
        }
    }
    return $true
}

function Test-AllScoreReasonsAtK {
    param(
        [object[]]$Results,
        [string[]]$Expected,
        [int]$Limit
    )
    $Expected = @(Get-StringArray $Expected)
    if ($Expected.Count -eq 0) {
        return $true
    }
    $reasons = @()
    foreach ($result in (Get-ResultsAtK -Results $Results -Limit $Limit)) {
        $reasons += Get-ResultScoreReasons $result
    }
    foreach ($expectedReason in $Expected) {
        $matched = $false
        foreach ($reason in $reasons) {
            if ($reason -eq $expectedReason -or $reason -like $expectedReason) {
                $matched = $true
                break
            }
        }
        if (-not $matched) {
            return $false
        }
    }
    return $true
}

function Test-PlanEvidence {
    param(
        [object]$Plan,
        [string[]]$Expected
    )
    $Expected = @(Get-StringArray $Expected)
    if ($Expected.Count -eq 0) {
        return $true
    }
    $types = @()
    $types += Get-StringArray (Get-PropertyValue $Plan "requiredEvidenceTypes" @())
    $types += Get-StringArray (Get-PropertyValue $Plan "preferredEvidenceTypes" @())
    foreach ($expectedType in $Expected) {
        if (-not ($types -contains $expectedType)) {
            return $false
        }
    }
    return $true
}

function Test-EvidenceEvaluation {
    param(
        [object]$Evaluation,
        [string]$ExpectedStatus,
        [object]$ExpectedNoAnswerRequired,
        [string[]]$ExpectedMatchedRequired,
        [string[]]$ExpectedMissingRequired
    )
    if ($null -eq $Evaluation) {
        return [string]::IsNullOrWhiteSpace($ExpectedStatus) `
            -and $null -eq $ExpectedNoAnswerRequired `
            -and @(Get-StringArray $ExpectedMatchedRequired).Count -eq 0 `
            -and @(Get-StringArray $ExpectedMissingRequired).Count -eq 0
    }

    if (-not [string]::IsNullOrWhiteSpace($ExpectedStatus)) {
        if ([string](Get-PropertyValue $Evaluation "status" "") -ne $ExpectedStatus) {
            return $false
        }
    }

    if ($null -ne $ExpectedNoAnswerRequired) {
        if ([bool](Get-PropertyValue $Evaluation "noAnswerRequired" $false) -ne [bool]$ExpectedNoAnswerRequired) {
            return $false
        }
    }

    $matchedRequired = Get-StringArray (Get-PropertyValue $Evaluation "matchedRequiredEvidenceTypes" @())
    if (-not (Test-ArrayContainsAll -Actual $matchedRequired -Expected $ExpectedMatchedRequired)) {
        return $false
    }

    $missingRequired = Get-StringArray (Get-PropertyValue $Evaluation "missingRequiredEvidenceTypes" @())
    if (-not (Test-ArrayContainsAll -Actual $missingRequired -Expected $ExpectedMissingRequired)) {
        return $false
    }

    return $true
}

function Test-TextContainsAll {
    param(
        [string]$Text,
        [string[]]$Terms
    )
    $Terms = @(Get-StringArray $Terms)
    foreach ($term in $Terms) {
        if ($Text.IndexOf($term, [System.StringComparison]::OrdinalIgnoreCase) -lt 0) {
            return $false
        }
    }
    return $true
}

function Test-TextContainsAny {
    param(
        [string]$Text,
        [string[]]$Terms
    )
    $Terms = @(Get-StringArray $Terms)
    if ($Terms.Count -eq 0) {
        return $true
    }
    foreach ($term in $Terms) {
        if ($Text.IndexOf($term, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $true
        }
    }
    return $false
}

function Test-TextContainsNone {
    param(
        [string]$Text,
        [string[]]$Terms
    )
    $Terms = @(Get-StringArray $Terms)
    foreach ($term in $Terms) {
        if ($Text.IndexOf($term, [System.StringComparison]::OrdinalIgnoreCase) -ge 0) {
            return $false
        }
    }
    return $true
}

function Test-OnlyGenericEvidence {
    param([object]$Result)
    $types = @(Get-ResultEvidenceTypes $Result)
    if ($types.Count -eq 0) {
        return $true
    }
    foreach ($type in $types) {
        if ($type -ne "GENERATED_DOC" -and $type -ne "MANUAL_DOC" -and $type -ne "CODE_MAP") {
            return $false
        }
    }
    return $true
}

function Get-Average {
    param([double[]]$Values)
    if ($Values.Count -eq 0) {
        return 0.0
    }
    $sum = 0.0
    foreach ($value in $Values) {
        $sum += $value
    }
    return [Math]::Round($sum / $Values.Count, 3)
}

function Get-Rate {
    param([bool[]]$Values)
    if ($Values.Count -eq 0) {
        return 0.0
    }
    $hits = 0
    foreach ($value in $Values) {
        if ($value) {
            $hits += 1
        }
    }
    return [Math]::Round($hits / $Values.Count, 3)
}

function ConvertTo-MdCell {
    param([object]$Value)
    if ($null -eq $Value) {
        return ""
    }
    return ([string]$Value).Replace("|", "\|").Replace("`r", " ").Replace("`n", " ")
}

function Join-Md {
    param([object[]]$Values)
    $items = @()
    foreach ($value in $Values) {
        if ($null -ne $value -and -not [string]::IsNullOrWhiteSpace([string]$value)) {
            $items += [string]$value
        }
    }
    return ConvertTo-MdCell ($items -join ", ")
}

function Evaluate-KnowledgeCase {
    param(
        [object]$Case,
        [long]$ResolvedSourceId
    )
    $caseTopK = [int](Get-PropertyValue $Case "topK" $TopK)
    if ($caseTopK -le 0) {
        $caseTopK = $TopK
    }
    $query = [string]$Case.query
    $searchBody = @{
        sourceId = $ResolvedSourceId
        query = $query
        topK = $caseTopK
    }
    $searchResponse = Invoke-DevContextApi -Method "Post" -Path "/api/knowledge/search" -Body $searchBody
    $searchData = $searchResponse.data
    $results = @($searchData.results)
    $topResults = @(Get-ResultsAtK -Results $results -Limit $caseTopK)

    $expectedPlanEvidenceTypes = @(Get-StringArray (Get-PropertyValue $Case "expectedPlanEvidenceTypes" @()))
    $expectedEvidenceTypes = @(Get-StringArray (Get-PropertyValue $Case "expectedEvidenceTypes" @()))
    $requiredEvidenceTypesAtK = @(Get-StringArray (Get-PropertyValue $Case "requiredEvidenceTypesAtK" @()))
    $expectedScoreReasonsAtK = @(Get-StringArray (Get-PropertyValue $Case "expectedScoreReasonsAtK" @()))
    $expectedSourcePaths = @(Get-StringArray (Get-PropertyValue $Case "expectedSourcePaths" @()))
    $forbiddenSourcePaths = @(Get-StringArray (Get-PropertyValue $Case "forbiddenSourcePaths" @()))
    $answerMustContainAny = @(Get-StringArray (Get-PropertyValue $Case "answerMustContainAny" @()))
    $answerMustContainAll = @(Get-StringArray (Get-PropertyValue $Case "answerMustContainAll" @()))
    $answerMustNotContain = @(Get-StringArray (Get-PropertyValue $Case "answerMustNotContain" @()))
    $maxGenericDocsAtK = [int](Get-PropertyValue $Case "maxGenericDocsAtK" 999)
    $noAnswerExpected = [bool](Get-PropertyValue $Case "noAnswerExpected" $false)
    $expectedEvidenceEvaluationStatus = [string](Get-PropertyValue $Case "expectedEvidenceEvaluationStatus" "")
    $expectedEvaluationMatchedRequired = @(Get-StringArray (Get-PropertyValue $Case "expectedEvaluationMatchedRequired" @()))
    $expectedEvaluationMissingRequired = @(Get-StringArray (Get-PropertyValue $Case "expectedEvaluationMissingRequired" @()))
    $expectedNoAnswerRequired = $null
    if (Has-Property $Case "expectedNoAnswerRequired") {
        $expectedNoAnswerRequired = [bool]$Case.expectedNoAnswerRequired
    }

    $planEvidenceHit = Test-PlanEvidence -Plan $searchData.queryPlan -Expected $expectedPlanEvidenceTypes
    $evidenceTop1Hit = Test-AnyEvidenceAtK -Results $results -Expected $expectedEvidenceTypes -Limit 1
    $evidenceTop3Hit = Test-AnyEvidenceAtK -Results $results -Expected $expectedEvidenceTypes -Limit 3
    $requiredEvidenceAtKPass = Test-AllEvidenceAtK -Results $results -Expected $requiredEvidenceTypesAtK -Limit $caseTopK
    $scoreReasonAtKPass = Test-AllScoreReasonsAtK -Results $results -Expected $expectedScoreReasonsAtK -Limit $caseTopK

    $sourceHitAtK = $false
    foreach ($result in $topResults) {
        if (Test-PathMatches -PathValue ([string]$result.filePath) -Patterns $expectedSourcePaths) {
            $sourceHitAtK = $true
            break
        }
    }

    $forbiddenSourcePass = $true
    $forbiddenHits = @()
    foreach ($result in $topResults) {
        if (Test-PathMatches -PathValue ([string]$result.filePath) -Patterns $forbiddenSourcePaths) {
            $forbiddenSourcePass = $false
            $forbiddenHits += [string]$result.filePath
        }
    }

    $genericDocCount = 0
    foreach ($result in $topResults) {
        if (Test-OnlyGenericEvidence $result) {
            $genericDocCount += 1
        }
    }
    $genericDocPass = $genericDocCount -le $maxGenericDocsAtK
    $genericDocPollutionAtK = if ($topResults.Count -eq 0) { 0.0 } else { [Math]::Round($genericDocCount / $topResults.Count, 3) }

    $askSuccess = $false
    $citationSourceHit = $false
    $citationEvidenceHit = $false
    $answerKeywordHit = $false
    $answerForbiddenPass = $false
    $noAnswerPass = $false
    $evidenceEvaluationPass = $false
    $evidenceEvaluationStatus = ""
    $evidenceEvaluationSufficient = $false
    $evidenceEvaluationNoAnswerRequired = $false
    $evidenceEvaluationMatchedRequired = @()
    $evidenceEvaluationMissingRequired = @()
    $evidenceEvaluationMatchedPreferred = @()
    $evidenceEvaluationMissingPreferred = @()
    $evidenceEvaluationReasons = @()
    $answer = ""
    $citations = @()
    $askError = ""

    if (-not $SkipAsk) {
        try {
            $askResponse = Invoke-DevContextApi -Method "Post" -Path "/api/knowledge/ask" -Body $searchBody
            $askData = $askResponse.data
            $askSuccess = $true
            $answer = [string]$askData.answer
            $citations = @($askData.citations)
            $evidenceEvaluation = Get-PropertyValue $askData "evidenceEvaluation" $null
            if ($null -ne $evidenceEvaluation) {
                $evidenceEvaluationStatus = [string](Get-PropertyValue $evidenceEvaluation "status" "")
                $evidenceEvaluationSufficient = [bool](Get-PropertyValue $evidenceEvaluation "sufficient" $false)
                $evidenceEvaluationNoAnswerRequired = [bool](Get-PropertyValue $evidenceEvaluation "noAnswerRequired" $false)
                $evidenceEvaluationMatchedRequired = Get-StringArray (Get-PropertyValue $evidenceEvaluation "matchedRequiredEvidenceTypes" @())
                $evidenceEvaluationMissingRequired = Get-StringArray (Get-PropertyValue $evidenceEvaluation "missingRequiredEvidenceTypes" @())
                $evidenceEvaluationMatchedPreferred = Get-StringArray (Get-PropertyValue $evidenceEvaluation "matchedPreferredEvidenceTypes" @())
                $evidenceEvaluationMissingPreferred = Get-StringArray (Get-PropertyValue $evidenceEvaluation "missingPreferredEvidenceTypes" @())
                $evidenceEvaluationReasons = Get-StringArray (Get-PropertyValue $evidenceEvaluation "reasons" @())
            }
            foreach ($citation in $citations) {
                if (Test-PathMatches -PathValue ([string]$citation.filePath) -Patterns $expectedSourcePaths) {
                    $citationSourceHit = $true
                }
                $types = Get-ResultEvidenceTypes $citation
                foreach ($expectedType in $expectedEvidenceTypes) {
                    if ($types -contains $expectedType) {
                        $citationEvidenceHit = $true
                    }
                }
            }
            $answerKeywordHit = (Test-TextContainsAll -Text $answer -Terms $answerMustContainAll) -and (Test-TextContainsAny -Text $answer -Terms $answerMustContainAny)
            $answerForbiddenPass = Test-TextContainsNone -Text $answer -Terms $answerMustNotContain
            $evidenceEvaluationPass = Test-EvidenceEvaluation `
                -Evaluation $evidenceEvaluation `
                -ExpectedStatus $expectedEvidenceEvaluationStatus `
                -ExpectedNoAnswerRequired $expectedNoAnswerRequired `
                -ExpectedMatchedRequired $expectedEvaluationMatchedRequired `
                -ExpectedMissingRequired $expectedEvaluationMissingRequired
            if ($noAnswerExpected) {
                $noAnswerPass = $answerKeywordHit -and $answerForbiddenPass -and $evidenceEvaluationNoAnswerRequired
            } else {
                $noAnswerPass = $true
            }
        } catch {
            $askError = $_.Exception.Message
        }
    }

    $topFiles = @()
    foreach ($result in $topResults) {
        $topFiles += [pscustomobject]@{
            filePath = [string]$result.filePath
            evidenceTypes = Get-ResultEvidenceTypes $result
            scoreReasons = Get-ResultScoreReasons $result
            fusedScore = [double]$result.fusedScore
            keywordScore = [double]$result.keywordScore
            vectorScore = [double]$result.vectorScore
        }
    }

    return [pscustomobject]@{
        name = [string]$Case.name
        query = $query
        topK = $caseTopK
        retrievalRecordId = [long]$searchData.retrievalRecordId
        planRequiredEvidenceTypes = Get-StringArray (Get-PropertyValue $searchData.queryPlan "requiredEvidenceTypes" @())
        planPreferredEvidenceTypes = Get-StringArray (Get-PropertyValue $searchData.queryPlan "preferredEvidenceTypes" @())
        normalizedTerms = Get-StringArray (Get-PropertyValue $searchData.queryPlan "normalizedTerms" @())
        planEvidenceHit = $planEvidenceHit
        evidenceTop1Hit = $evidenceTop1Hit
        evidenceTop3Hit = $evidenceTop3Hit
        requiredEvidenceAtKPass = $requiredEvidenceAtKPass
        scoreReasonAtKPass = $scoreReasonAtKPass
        expectedScoreReasonsAtK = $expectedScoreReasonsAtK
        sourceHitAtK = $sourceHitAtK
        forbiddenSourcePass = $forbiddenSourcePass
        forbiddenSourceHits = $forbiddenHits
        genericDocCountAtK = $genericDocCount
        genericDocPollutionAtK = $genericDocPollutionAtK
        genericDocPass = $genericDocPass
        noAnswerExpected = $noAnswerExpected
        askSuccess = $askSuccess
        askError = $askError
        citationSourceHit = $citationSourceHit
        citationEvidenceHit = $citationEvidenceHit
        answerKeywordHit = $answerKeywordHit
        answerForbiddenPass = $answerForbiddenPass
        noAnswerPass = $noAnswerPass
        evidenceEvaluationPass = $evidenceEvaluationPass
        evidenceEvaluationStatus = $evidenceEvaluationStatus
        evidenceEvaluationSufficient = $evidenceEvaluationSufficient
        evidenceEvaluationNoAnswerRequired = $evidenceEvaluationNoAnswerRequired
        evidenceEvaluationMatchedRequired = $evidenceEvaluationMatchedRequired
        evidenceEvaluationMissingRequired = $evidenceEvaluationMissingRequired
        evidenceEvaluationMatchedPreferred = $evidenceEvaluationMatchedPreferred
        evidenceEvaluationMissingPreferred = $evidenceEvaluationMissingPreferred
        evidenceEvaluationReasons = $evidenceEvaluationReasons
        answer = $answer
        citations = $citations
        topFiles = $topFiles
    }
}

function Write-Reports {
    param(
        [string]$RunId,
        [long]$ResolvedSourceId,
        [string]$ResolvedSourceRoot,
        [object]$IndexResult,
        [object[]]$CaseResults,
        [object]$LlmMetadata,
        [string]$OutputDir
    )
    if (-not (Test-Path -LiteralPath $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir | Out-Null
    }

    $jsonPath = Join-Path $OutputDir "knowledge-rag-benchmark-$RunId.json"
    $mdPath = Join-Path $OutputDir "knowledge-rag-benchmark-$RunId.md"

    $askCases = @($CaseResults | Where-Object { $_.askSuccess -or -not [string]::IsNullOrWhiteSpace($_.askError) })
    $noAnswerCases = @($CaseResults | Where-Object { $_.noAnswerExpected })
    $summary = [pscustomobject]@{
        caseCount = $CaseResults.Count
        sourceId = $ResolvedSourceId
        sourceRoot = $ResolvedSourceRoot
        topK = $TopK
        skipAsk = [bool]$SkipAsk
        searchPlanHitRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.planEvidenceHit })
        evidenceTop1HitRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.evidenceTop1Hit })
        evidenceTop3HitRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.evidenceTop3Hit })
        requiredEvidenceAtKPassRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.requiredEvidenceAtKPass })
        scoreReasonAtKPassRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.scoreReasonAtKPass })
        sourceHitAtKRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.sourceHitAtK })
        forbiddenSourcePassRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.forbiddenSourcePass })
        genericDocPassRate = Get-Rate @($CaseResults | ForEach-Object { [bool]$_.genericDocPass })
        averageGenericDocPollutionAtK = Get-Average @($CaseResults | ForEach-Object { [double]$_.genericDocPollutionAtK })
        askSuccessRate = if ($SkipAsk) { $null } else { Get-Rate @($askCases | ForEach-Object { [bool]$_.askSuccess }) }
        citationSourceHitRate = if ($SkipAsk) { $null } else { Get-Rate @($askCases | ForEach-Object { [bool]$_.citationSourceHit }) }
        citationEvidenceHitRate = if ($SkipAsk) { $null } else { Get-Rate @($askCases | ForEach-Object { [bool]$_.citationEvidenceHit }) }
        answerKeywordHitRate = if ($SkipAsk) { $null } else { Get-Rate @($askCases | ForEach-Object { [bool]$_.answerKeywordHit }) }
        answerForbiddenPassRate = if ($SkipAsk) { $null } else { Get-Rate @($askCases | ForEach-Object { [bool]$_.answerForbiddenPass }) }
        evidenceEvaluationPassRate = if ($SkipAsk) { $null } else { Get-Rate @($askCases | ForEach-Object { [bool]$_.evidenceEvaluationPass }) }
        noAnswerAccuracy = if ($SkipAsk) { $null } else { Get-Rate @($noAnswerCases | ForEach-Object { [bool]$_.noAnswerPass }) }
    }

    $payload = [pscustomobject]@{
        runId = $RunId
        baseUrl = $BaseUrl
        casesPath = $CasesPath
        llm = $LlmMetadata
        generatedAt = (Get-Date).ToString("o")
        indexResult = $IndexResult
        summary = $summary
        cases = $CaseResults
    }
    $payload | ConvertTo-Json -Depth 100 | Set-Content -Encoding UTF8 -LiteralPath $jsonPath

    $lines = @()
    $lines += "# DevContext Knowledge RAG Acceptance Benchmark $RunId"
    $lines += ""
    $lines += "## Environment"
    $lines += ""
    $lines += "- Base URL: ``$BaseUrl``"
    $lines += "- Source ID: ``$ResolvedSourceId``"
    $lines += "- Source root: ``$ResolvedSourceRoot``"
    $lines += "- Source type: ``$SourceType``"
    $lines += "- TopK default: ``$TopK``"
    $lines += "- Skip ask: ``$([bool]$SkipAsk)``"
    $lines = Add-DevContextLlmReportMarkdownLines -Lines $lines -LlmMetadata $LlmMetadata
    $lines += ""
    $lines += "## Summary"
    $lines += ""
    $lines += "| Cases | PlanHit | EvidenceTop1 | EvidenceTop3 | Required@K | ScoreReason@K | Source@K | ForbiddenPass | GenericPass | AvgGenericPollution | AskSuccess | CitationSource | CitationEvidence | EvidenceEvaluation | AnswerKeyword | AnswerForbidden | NoAnswerAccuracy |"
    $lines += "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |"
    $lines += "| $($summary.caseCount) | $($summary.searchPlanHitRate) | $($summary.evidenceTop1HitRate) | $($summary.evidenceTop3HitRate) | $($summary.requiredEvidenceAtKPassRate) | $($summary.scoreReasonAtKPassRate) | $($summary.sourceHitAtKRate) | $($summary.forbiddenSourcePassRate) | $($summary.genericDocPassRate) | $($summary.averageGenericDocPollutionAtK) | $($summary.askSuccessRate) | $($summary.citationSourceHitRate) | $($summary.citationEvidenceHitRate) | $($summary.evidenceEvaluationPassRate) | $($summary.answerKeywordHitRate) | $($summary.answerForbiddenPassRate) | $($summary.noAnswerAccuracy) |"
    $lines += ""
    $lines += "## Cases"
    $lines += ""
    $lines += "| Case | Plan | EvidenceTop1 | EvidenceTop3 | Required@K | ScoreReason@K | Source@K | Forbidden | Generic | Ask | Evaluation | Citation | Answer | Top files |"
    $lines += "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |"
    foreach ($case in $CaseResults) {
        $topFiles = @($case.topFiles | Select-Object -First 3 | ForEach-Object { "$($_.filePath) [$($_.evidenceTypes -join '+')] {$($_.scoreReasons -join '+')}" })
        $askDisplay = if ($SkipAsk) { "" } else { [string]$case.askSuccess }
        $evaluationPass = if ($SkipAsk) { "" } else { [string]$case.evidenceEvaluationPass }
        $citationPass = if ($SkipAsk) { "" } else { "$($case.citationSourceHit)/$($case.citationEvidenceHit)" }
        $answerPass = if ($SkipAsk) { "" } else { "$($case.answerKeywordHit)/$($case.answerForbiddenPass)" }
        $lines += "| $(ConvertTo-MdCell $case.name) | $($case.planEvidenceHit) | $($case.evidenceTop1Hit) | $($case.evidenceTop3Hit) | $($case.requiredEvidenceAtKPass) | $($case.scoreReasonAtKPass) | $($case.sourceHitAtK) | $($case.forbiddenSourcePass) | $($case.genericDocPass) | $(ConvertTo-MdCell $askDisplay) | $(ConvertTo-MdCell $evaluationPass) | $(ConvertTo-MdCell $citationPass) | $(ConvertTo-MdCell $answerPass) | $(Join-Md $topFiles) |"
    }
    $lines += ""
    $lines += "## Case Details"
    foreach ($case in $CaseResults) {
        $lines += ""
        $lines += "### $($case.name)"
        $lines += ""
        $lines += "- Query: $($case.query)"
        $lines += "- Retrieval record: ``$($case.retrievalRecordId)``"
        $lines += "- Plan required evidence: ``$($case.planRequiredEvidenceTypes -join ', ')``"
        $lines += "- Plan preferred evidence: ``$($case.planPreferredEvidenceTypes -join ', ')``"
        $lines += "- Normalized terms: ``$($case.normalizedTerms -join ', ')``"
        $lines += "- Generic doc pollution: ``$($case.genericDocPollutionAtK)``"
        $lines += "- Expected score reasons at K: ``$($case.expectedScoreReasonsAtK -join ', ')``"
        if (-not $SkipAsk -and $case.askSuccess) {
            $lines += "- Evidence evaluation: status=``$($case.evidenceEvaluationStatus)`` sufficient=``$($case.evidenceEvaluationSufficient)`` noAnswerRequired=``$($case.evidenceEvaluationNoAnswerRequired)``"
            $lines += "- Evidence evaluation matched required: ``$($case.evidenceEvaluationMatchedRequired -join ', ')``"
            $lines += "- Evidence evaluation missing required: ``$($case.evidenceEvaluationMissingRequired -join ', ')``"
            $lines += "- Evidence evaluation reasons: ``$($case.evidenceEvaluationReasons -join ' | ')``"
        }
        if (-not $SkipAsk -and -not $case.askSuccess) {
            $lines += "- Ask error: ``$(ConvertTo-MdCell $case.askError)``"
        }
        $lines += ""
        $lines += "| Rank | File | Evidence | Score reasons | Fused | Keyword | Vector |"
        $lines += "| ---: | --- | --- | --- | ---: | ---: | ---: |"
        $rank = 1
        foreach ($file in $case.topFiles) {
            $lines += "| $rank | $(ConvertTo-MdCell $file.filePath) | $(Join-Md $file.evidenceTypes) | $(Join-Md $file.scoreReasons) | $($file.fusedScore) | $($file.keywordScore) | $($file.vectorScore) |"
            $rank += 1
        }
    }
    $lines += ""
    $lines += "## Metric Notes"
    $lines += ""
    $lines += "- PlanHit: query planner selected the expected evidence types."
    $lines += "- EvidenceTop1/Top3: retrieved chunks include expected concrete evidence types."
    $lines += "- ScoreReason@K: TopK retrieved chunks include expected ranking reasons such as required evidence or primary reliability."
    $lines += "- Source@K: retrieved file paths include expected source paths."
    $lines += "- GenericPass: generated/manual summary docs did not dominate the TopK."
    $lines += "- EvidenceEvaluation checks `/api/knowledge/ask` evidence grounding status and required-evidence matches."
    $lines += "- AnswerKeyword and AnswerForbidden are guardrail checks, not human grading."
    $lines += "- NoAnswerAccuracy checks that performance questions do not invent QPS/P95 without benchmark evidence."

    $lines | Set-Content -Encoding UTF8 -LiteralPath $mdPath

    return [pscustomobject]@{
        summary = $summary
        jsonPath = $jsonPath
        markdownPath = $mdPath
    }
}

$casesFile = Resolve-RepoPath $CasesPath
$defaultCasesFile = Resolve-RepoPath $DefaultCasesPath
if (Test-Path -LiteralPath $casesFile) {
    $loadedCases = Get-Content -Raw -Encoding UTF8 -LiteralPath $casesFile | ConvertFrom-Json
} elseif ([string]::Equals($casesFile, $defaultCasesFile, [System.StringComparison]::OrdinalIgnoreCase)) {
    $loadedCases = Get-DefaultKnowledgeRagAcceptanceCases
} else {
    throw "CasesPath does not exist: $casesFile"
}
$cases = @(As-Array $loadedCases)
if ($CaseLimit -gt 0) {
    $cases = @($cases | Select-Object -First $CaseLimit)
}

if ($ListCases) {
    $index = 1
    foreach ($case in $cases) {
        Write-Host ("{0}. {1} :: {2}" -f $index, $case.name, $case.query)
        $index += 1
    }
    exit 0
}

Write-Host "Checking DevContext health at $BaseUrl"
Invoke-DevContextApi -Method "Get" -Path "/api/health" | Out-Null

$runId = New-RunId
$resolvedSourceRoot = ""
$resolvedSourceId = $SourceId
$indexResult = $null

if ($resolvedSourceId -le 0) {
    if ([string]::IsNullOrWhiteSpace($SourceRoot)) {
        $resolvedSourceRoot = Join-Path $repoRoot "target/knowledge-rag-acceptance-$runId"
        Write-Host "Creating knowledge fixture at $resolvedSourceRoot"
        Initialize-KnowledgeFixture -Root $resolvedSourceRoot | Out-Null
    } else {
        $resolvedSourceRoot = Resolve-RepoPath $SourceRoot
    }

    Write-Host "Creating knowledge source"
    $sourceResponse = Invoke-DevContextApi -Method "Post" -Path "/api/knowledge-sources" -Body @{
        name = "knowledge-rag-acceptance-$runId"
        rootPath = $resolvedSourceRoot
        sourceType = $SourceType
    }
    $resolvedSourceId = [long]$sourceResponse.data.id
    $Reindex = $true
} else {
    $resolvedSourceRoot = if ([string]::IsNullOrWhiteSpace($SourceRoot)) { "(existing source)" } else { Resolve-RepoPath $SourceRoot }
}

if ($Reindex) {
    Write-Host "Indexing knowledge source #$resolvedSourceId"
    $indexResponse = Invoke-DevContextApi -Method "Post" -Path "/api/knowledge-sources/$resolvedSourceId/index"
    $indexResult = $indexResponse.data
    Write-Host "Indexed $($indexResult.documentsIndexed) documents, $($indexResult.chunksIndexed) chunks"
}

Write-Host "Running knowledge RAG acceptance benchmark with $($cases.Count) cases"
$caseResults = @()
foreach ($case in $cases) {
    Write-Host "Running case: $($case.name)"
    try {
        $caseResults += Evaluate-KnowledgeCase -Case $case -ResolvedSourceId $resolvedSourceId
    } catch {
        $caseResults += [pscustomobject]@{
            name = [string]$case.name
            query = [string]$case.query
            topK = [int](Get-PropertyValue $case "topK" $TopK)
            retrievalRecordId = $null
            planRequiredEvidenceTypes = @()
            planPreferredEvidenceTypes = @()
            normalizedTerms = @()
            planEvidenceHit = $false
            evidenceTop1Hit = $false
            evidenceTop3Hit = $false
            requiredEvidenceAtKPass = $false
            scoreReasonAtKPass = $false
            expectedScoreReasonsAtK = @(Get-StringArray (Get-PropertyValue $case "expectedScoreReasonsAtK" @()))
            sourceHitAtK = $false
            forbiddenSourcePass = $false
            forbiddenSourceHits = @()
            genericDocCountAtK = 0
            genericDocPollutionAtK = 0.0
            genericDocPass = $false
            noAnswerExpected = [bool](Get-PropertyValue $case "noAnswerExpected" $false)
            askSuccess = $false
            askError = $_.Exception.Message
            citationSourceHit = $false
            citationEvidenceHit = $false
            answerKeywordHit = $false
            answerForbiddenPass = $false
            noAnswerPass = $false
            evidenceEvaluationPass = $false
            evidenceEvaluationStatus = ""
            evidenceEvaluationSufficient = $false
            evidenceEvaluationNoAnswerRequired = $false
            evidenceEvaluationMatchedRequired = @()
            evidenceEvaluationMissingRequired = @()
            evidenceEvaluationMatchedPreferred = @()
            evidenceEvaluationMissingPreferred = @()
            evidenceEvaluationReasons = @()
            answer = ""
            citations = @()
            topFiles = @()
        }
        Write-Host "  failed: $($_.Exception.Message)"
    }
}

$report = Write-Reports -RunId $runId -ResolvedSourceId $resolvedSourceId -ResolvedSourceRoot $resolvedSourceRoot -IndexResult $indexResult -CaseResults $caseResults -LlmMetadata (Get-LlmReportMetadata) -OutputDir (Resolve-RepoPath $OutputDir)

Write-Host ""
Write-Host "Knowledge RAG acceptance benchmark complete"
Write-Host "PlanHit:                 $($report.summary.searchPlanHitRate)"
Write-Host "EvidenceTop1:            $($report.summary.evidenceTop1HitRate)"
Write-Host "EvidenceTop3:            $($report.summary.evidenceTop3HitRate)"
Write-Host "RequiredEvidence@K:      $($report.summary.requiredEvidenceAtKPassRate)"
Write-Host "ScoreReason@K:           $($report.summary.scoreReasonAtKPassRate)"
Write-Host "SourceHit@K:             $($report.summary.sourceHitAtKRate)"
Write-Host "ForbiddenSourcePass:     $($report.summary.forbiddenSourcePassRate)"
Write-Host "GenericDocPass:          $($report.summary.genericDocPassRate)"
if ($SkipAsk) {
    Write-Host "AnswerKeywordHit:        skipped"
    Write-Host "NoAnswerAccuracy:        skipped"
} else {
    Write-Host "EvidenceEvaluationPass:  $($report.summary.evidenceEvaluationPassRate)"
    Write-Host "AnswerKeywordHit:        $($report.summary.answerKeywordHitRate)"
    Write-Host "NoAnswerAccuracy:        $($report.summary.noAnswerAccuracy)"
}
Write-Host "Markdown report:         $($report.markdownPath)"
Write-Host "JSON report:             $($report.jsonPath)"
