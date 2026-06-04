package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.context.ProjectContextAssetApplicationService;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.context.ContextDocumentStatus;
import com.devcontext.domain.context.ContextGenerationResult;
import com.devcontext.domain.context.ProjectContextStatus;
import com.devcontext.domain.project.Project;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:sqlite:target/devcontext-test.sqlite",
        "devcontext.llm.provider=mock",
        "devcontext.llm.gemini.api-key=",
        "devcontext.llm.deepseek.api-key="
})
@AutoConfigureMockMvc
class Mvp1ProjectContextAssetsTests {

    @Autowired
    private ProjectApplicationService projectService;

    @Autowired
    private ProjectContextAssetApplicationService contextAssetService;

    @Autowired
    private MockMvc mockMvc;

    @TempDir
    private Path projectRoot;

    @Test
    void generatesProjectContextAssetsAndPreservesManualFiles() throws Exception {
        createSpringBootFixture(projectRoot);

        Project project = projectService.createProject("demo-context-project", projectRoot.toString(), "main");
        ContextGenerationResult result = contextAssetService.generate(project.id(), true, false);

        assertThat(result.generatedFiles()).contains(
                "AGENTS.md",
                ".ai/AI_README.md",
                ".ai/code-map.json",
                ".ai/generated/project-structure.md",
                ".ai/generated/tech-architecture.md",
                ".ai/generated/dev-guide.md",
                ".ai/generated/core-flows.md"
        );
        assertThat(result.manualCreatedFiles()).contains(
                ".ai/manual/business-context.md",
                ".ai/manual/coding-preferences.md",
                ".ai/manual/decisions.md",
                ".ai/manual/pitfalls.md"
        );
        assertThat(result.todos()).contains("Fill in business context in .ai/manual/business-context.md.");

        assertThat(Files.readString(projectRoot.resolve("AGENTS.md"))).contains("Project AI Context Entry");
        assertThat(Files.readString(projectRoot.resolve(".ai/AI_README.md"))).contains("demo-context-project");
        assertThat(Files.readString(projectRoot.resolve(".ai/generated/dev-guide.md"))).contains("mvn test");
        assertThat(Files.readString(projectRoot.resolve(".ai/generated/project-structure.md")))
                .doesNotContain(".ai/reviews")
                .doesNotContain("data")
                .doesNotContain("target");
        assertThat(Files.readString(projectRoot.resolve(".ai/code-map.json")))
                .contains("Spring Boot")
                .contains("HelloController")
                .contains("mvn spring-boot:run")
                .doesNotContain("review-1.md")
                .doesNotContain("devcontext.sqlite");

        Path manualBusinessContext = projectRoot.resolve(".ai/manual/business-context.md");
        Files.writeString(manualBusinessContext, "custom business context");

        String secondResponse = mockMvc.perform(post("/api/projects/{projectId}/context/generate", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "overwriteGenerated": true,
                                  "overwriteManual": false
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(secondResponse).contains("manualSkippedFiles", ".ai/manual/business-context.md");
        assertThat(Files.readString(manualBusinessContext)).isEqualTo("custom business context");

        ProjectContextStatus status = contextAssetService.getStatus(project.id());
        assertThat(status.documents())
                .extracting(ContextDocumentStatus::path)
                .contains("AGENTS.md", ".ai/code-map.json", ".ai/manual/business-context.md");
        assertThat(status.documents())
                .filteredOn(document -> document.path().equals("AGENTS.md"))
                .allMatch(ContextDocumentStatus::exists);
        assertThat(status.quality()).isNotNull();
        assertThat(status.quality().level()).isEqualTo("low");
        assertThat(status.quality().issues())
                .noneMatch(issue -> issue.path().equals(".ai/generated/core-flows.md")
                        && issue.title().equals("核心流程未确认"));

        mockMvc.perform(get("/api/projects/{projectId}/context", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quality.level").value("low"))
                .andExpect(jsonPath("$.data.quality.issues").isNotEmpty());
    }

    @Test
    void generatedAssetsUseReadmeAndCodeEntrypointsAsEvidence() throws Exception {
        createRichSpringBootFixture(projectRoot);

        Project project = projectService.createProject("life-service-like-project", projectRoot.toString(), "main");
        contextAssetService.generate(project.id(), true, false);

        String agents = Files.readString(projectRoot.resolve("AGENTS.md"));
        String aiReadme = Files.readString(projectRoot.resolve(".ai/AI_README.md"));
        String codeMap = Files.readString(projectRoot.resolve(".ai/code-map.json"));
        String architecture = Files.readString(projectRoot.resolve(".ai/generated/tech-architecture.md"));
        String coreFlows = Files.readString(projectRoot.resolve(".ai/generated/core-flows.md"));
        String devGuide = Files.readString(projectRoot.resolve(".ai/generated/dev-guide.md"));

        assertThat(agents)
                .contains("面向本地生活服务的闪购代金券系统")
                .doesNotContain("TODO: Summarize the project purpose");
        assertThat(aiReadme)
                .contains("秒杀代金券抢购")
                .contains("Redis + Lua")
                .contains("Prometheus 指标观测");
        assertThat(architecture)
                .contains("HTTP API Entrypoints")
                .contains("POST /api/v1/flash-sale/vouchers/warmup")
                .contains("POST /api/v1/orders/seckill")
                .contains("Redis Lua")
                .contains("Micrometer");
        assertThat(coreFlows)
                .contains("Product Flow From README")
                .contains("用户进入代金券详情页")
                .contains("Inferred Backend Flow Candidates")
                .contains("Code trace")
                .contains("Data Access And Resource Evidence")
                .contains("FlashSaleOrderCommandHandler")
                .contains("SeckillLuaScriptExecutor")
                .contains("src/main/resources/lua/seckill-stock.lua")
                .contains("POST /api/v1/orders/seckill")
                .doesNotContain("TODO: Describe the most important");
        assertThat(devGuide)
                .contains("docker compose up -d")
                .contains("mvn test");
        assertThat(codeMap)
                .contains("\"symbols\"")
                .contains("\"endpoints\"")
                .contains("\"dependencies\"")
                .contains("\"technologies\"")
                .contains("\"runtimeComponents\"")
                .contains("\"domainTerms\"")
                .contains("\"path\" : \"/api/v1/orders/seckill\"")
                .contains("\"toType\" : \"FlashSaleOrderCommandHandler\"")
                .contains("\"toType\" : \"SeckillLuaScriptExecutor\"")
                .contains("\"technology\" : \"Redis Lua\"")
                .contains("\"term\" : \"flash-sale\"")
                .contains("\"term\" : \"voucher-order\"");

        ProjectContextStatus status = contextAssetService.getStatus(project.id());
        assertThat(status.quality().level()).isNotEqualTo("low");
        assertThat(status.quality().issues())
                .noneMatch(issue -> issue.path().equals(".ai/generated/core-flows.md")
                        && issue.title().equals("核心流程未确认"));
    }

    @Test
    void generateCanOnlyFillMissingAssetsWithoutOverwritingExistingGeneratedFiles() throws Exception {
        createSpringBootFixture(projectRoot);

        Project project = projectService.createProject("missing-only-context-project", projectRoot.toString(), "main");
        contextAssetService.generate(project.id(), true, false);

        Path agents = projectRoot.resolve("AGENTS.md");
        Files.writeString(agents, "custom generated entry should be preserved");
        Files.delete(projectRoot.resolve(".ai/generated/dev-guide.md"));

        ContextGenerationResult result = contextAssetService.generate(project.id(), false, false);

        assertThat(result.generatedSkippedFiles()).contains("AGENTS.md", ".ai/AI_README.md", ".ai/code-map.json");
        assertThat(result.generatedFiles()).contains(".ai/generated/dev-guide.md");
        assertThat(Files.readString(agents)).isEqualTo("custom generated entry should be preserved");
    }

    @Test
    void resolvesQuestionContextFromGeneratedCodeMap() throws Exception {
        createRichSpringBootFixture(projectRoot);

        Project project = projectService.createProject("life-service-context-resolution", projectRoot.toString(), "main");
        contextAssetService.generate(project.id(), true, false);

        String seckillResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "秒杀订单流程是什么？",
                                  "maxItems": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andExpect(jsonPath("$.data.needsDeepScan").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(seckillResponse)
                .contains("voucher-order")
                .contains("flash-sale")
                .contains("VoucherOrderController")
                .contains("FlashSaleOrderCommandHandler")
                .contains("SeckillLuaScriptExecutor")
                .contains("QUESTION_RELATED_SOURCE");

        String englishSeckillResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "How does the flash-sale voucher order flow work?",
                                  "maxItems": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andExpect(jsonPath("$.data.needsDeepScan").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(englishSeckillResponse)
                .contains("voucher-order")
                .contains("flash-sale")
                .contains("VoucherOrderController")
                .contains("FlashSaleOrderCommandHandler")
                .contains("SeckillLuaScriptExecutor");

        String japaneseSeckillResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "フラッシュセール注文の流れは？",
                                  "maxItems": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andExpect(jsonPath("$.data.needsDeepScan").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(japaneseSeckillResponse)
                .contains("voucher-order")
                .contains("flash-sale")
                .contains("VoucherOrderController")
                .contains("FlashSaleOrderCommandHandler")
                .contains("SeckillLuaScriptExecutor");

        String rateLimitResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "限流在哪里做？",
                                  "maxItems": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(rateLimitResponse)
                .contains("rate-limit")
                .contains("RateLimitAspect")
                .contains("SlidingWindowRateLimiter")
                .contains("src/main/java/com/acme/lifeservice/common/rate/RateLimitAspect.java");

        String orderCloseResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "订单超时关闭在哪里？",
                                  "maxItems": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(orderCloseResponse)
                .contains("order-close")
                .contains("timeout")
                .contains("OrderCloseScheduler")
                .contains("VoucherOrderCloseService")
                .contains("src/main/java/com/acme/lifeservice/order/job/OrderCloseScheduler.java");

        String japaneseOrderCloseResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "注文のタイムアウト処理はどこ？",
                                  "maxItems": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(japaneseOrderCloseResponse)
                .contains("order-close")
                .contains("timeout")
                .contains("OrderCloseScheduler")
                .contains("VoucherOrderCloseService")
                .contains("src/main/java/com/acme/lifeservice/order/job/OrderCloseScheduler.java");

        String performanceResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "秒杀性能怎么样？",
                                  "maxItems": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(performanceResponse)
                .contains("运行证据")
                .contains("真实性能结论")
                .contains("VoucherOrderController")
                .contains("OrderMetrics");

        String japanesePerformanceResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "フラッシュセールのパフォーマンスは？",
                                  "maxItems": 4
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(japanesePerformanceResponse)
                .contains("运行证据")
                .contains("真实性能结论")
                .contains("flash-sale")
                .contains("performance")
                .contains("VoucherOrderController");

        String luaScriptResponse = mockMvc.perform(post("/api/projects/{projectId}/context/resolve", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "question": "库存扣减 Lua 脚本在哪里？",
                                  "maxItems": 5
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("resolved"))
                .andExpect(jsonPath("$.data.needsDeepScan").value(false))
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(luaScriptResponse)
                .contains("stock-deduction")
                .contains("script")
                .contains("QUESTION_DEEP_SCAN_SOURCE")
                .contains("src/main/resources/lua/seckill-stock.lua")
                .contains("redis.call")
                .contains("受控源码/资源深扫");
    }

    private void createSpringBootFixture(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/com/example/web"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.createDirectories(root.resolve("src/test/java/com/example"));
        Files.createDirectories(root.resolve(".ai/reviews"));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("target/classes"));
        Files.createDirectories(root.resolve("logs"));
        Files.writeString(root.resolve("pom.xml"), """
                <project>
                  <dependencies>
                    <dependency>
                      <groupId>org.springframework.boot</groupId>
                      <artifactId>spring-boot-starter-web</artifactId>
                    </dependency>
                  </dependencies>
                </project>
                """);
        Files.writeString(root.resolve("README.md"), "# Demo Context Project\n");
        Files.writeString(root.resolve(".ai/reviews/review-1.md"), "# Old Review\n");
        Files.writeString(root.resolve("data/devcontext.sqlite"), "sqlite-data");
        Files.writeString(root.resolve("logs/app.log"), "log-data");
        Files.writeString(root.resolve("src/main/resources/application.yml"), "server:\n  port: 18080\n");
        Files.writeString(root.resolve("src/main/java/com/example/DemoApplication.java"), """
                package com.example;

                import org.springframework.boot.autoconfigure.SpringBootApplication;

                @SpringBootApplication
                public class DemoApplication {
                    public static void main(String[] args) {
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/example/web/HelloController.java"), """
                package com.example.web;

                import org.springframework.web.bind.annotation.GetMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                public class HelloController {
                    @GetMapping("/hello")
                    public String hello() {
                        return "hello";
                    }
                }
                """);
        Files.writeString(root.resolve("src/test/java/com/example/DemoApplicationTests.java"), """
                package com.example;

                class DemoApplicationTests {
                }
                """);
    }

    private void createRichSpringBootFixture(Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/com/acme/lifeservice/flashsale/controller"));
        Files.createDirectories(root.resolve("src/main/java/com/acme/lifeservice/order/controller"));
        Files.createDirectories(root.resolve("src/main/java/com/acme/lifeservice/order/job"));
        Files.createDirectories(root.resolve("src/main/java/com/acme/lifeservice/order/service"));
        Files.createDirectories(root.resolve("src/main/java/com/acme/lifeservice/common/rate"));
        Files.createDirectories(root.resolve("src/main/java/com/acme/lifeservice/common/metrics"));
        Files.createDirectories(root.resolve("src/main/resources"));
        Files.createDirectories(root.resolve("src/main/resources/lua"));
        Files.createDirectories(root.resolve("frontend"));

        Files.writeString(root.resolve("pom.xml"), """
                <project>
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
                """);
        Files.writeString(root.resolve("README.zh-CN.md"), """
                # Life Service

                面向本地生活服务的闪购代金券系统，提供商户、代金券、秒杀下单和订单履约能力。

                ## 项目定位

                项目聚焦本地生活优惠券交易闭环，不自动处理真实支付清结算。

                ## 功能概览

                - 秒杀代金券抢购
                - Redis + Lua 时间窗和库存校验
                - Prometheus 指标观测

                ## Demo Flow

                1. 用户进入代金券详情页，系统读取活动时间、库存和商户信息。
                2. 活动预热阶段把闪购库存与时间窗口写入 Redis。
                3. 用户提交秒杀下单请求，后端通过 Lua 校验时间、库存和一人一单。
                4. 下单成功后写入订单，并通过指标记录成功率和延迟。

                ## 技术栈

                | Layer | Tech |
                | --- | --- |
                | Backend | Spring Boot |
                | Cache | Redis |
                | Observability | Micrometer |

                ## 快速启动

                ```bash
                docker compose up -d
                mvn test
                ```
                """);
        Files.writeString(root.resolve("docker-compose.yml"), "services:\n  redis:\n    image: redis:7\n");
        Files.writeString(root.resolve("src/main/resources/application.yml"), "spring:\n  data:\n    redis:\n      host: localhost\n");
        Files.writeString(root.resolve("src/main/resources/lua/seckill-stock.lua"), """
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
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/flashsale/controller/FlashSaleVoucherWarmupController.java"), """
                package com.acme.lifeservice.flashsale.controller;

                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/v1/flash-sale/vouchers")
                public class FlashSaleVoucherWarmupController {
                    private final FlashSaleVoucherStartupWarmupRunner warmupRunner = new FlashSaleVoucherStartupWarmupRunner();

                    @PostMapping("/warmup")
                    public void warmup() {
                    }
                }

                class FlashSaleVoucherStartupWarmupRunner {
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/order/controller/VoucherOrderController.java"), """
                package com.acme.lifeservice.order.controller;

                import com.acme.lifeservice.order.service.FlashSaleOrderCommandHandler;
                import org.springframework.web.bind.annotation.PostMapping;
                import org.springframework.web.bind.annotation.RequestMapping;
                import org.springframework.web.bind.annotation.RestController;

                @RestController
                @RequestMapping("/api/v1/orders")
                public class VoucherOrderController {
                    private final FlashSaleOrderCommandHandler commandHandler = new FlashSaleOrderCommandHandler();

                    @PostMapping("/seckill")
                    public void seckill() {
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/order/service/FlashSaleOrderCommandHandler.java"), """
                package com.acme.lifeservice.order.service;

                public class FlashSaleOrderCommandHandler {
                    private final SeckillLuaScriptExecutor luaScriptExecutor = new SeckillLuaScriptExecutor();

                    public void createOrder() {
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/order/service/SeckillLuaScriptExecutor.java"), """
                package com.acme.lifeservice.order.service;

                import org.springframework.data.redis.core.StringRedisTemplate;

                public class SeckillLuaScriptExecutor {
                    private final StringRedisTemplate redisTemplate = null;

                    public void executeLuaScript() {
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/order/job/OrderCloseScheduler.java"), """
                package com.acme.lifeservice.order.job;

                import com.acme.lifeservice.order.service.VoucherOrderCloseService;
                import org.springframework.scheduling.annotation.Scheduled;

                public class OrderCloseScheduler {
                    private final VoucherOrderCloseService closeService = new VoucherOrderCloseService();

                    @Scheduled(fixedDelay = 30000)
                    public void closeTimeoutVoucherOrders() {
                        closeService.closeExpiredOrdersAndReleaseStock();
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/order/service/VoucherOrderCloseService.java"), """
                package com.acme.lifeservice.order.service;

                public class VoucherOrderCloseService {
                    private final StockReleaseService stockReleaseService = new StockReleaseService();

                    public void closeExpiredOrdersAndReleaseStock() {
                        stockReleaseService.releaseVoucherStock();
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/order/service/StockReleaseService.java"), """
                package com.acme.lifeservice.order.service;

                public class StockReleaseService {
                    public void releaseVoucherStock() {
                    }
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/common/rate/RateLimitAspect.java"), """
                package com.acme.lifeservice.common.rate;

                import org.springframework.stereotype.Component;

                @Component
                public class RateLimitAspect {
                    private final SlidingWindowRateLimiter limiter = new SlidingWindowRateLimiter();
                }

                class SlidingWindowRateLimiter {
                }
                """);
        Files.writeString(root.resolve("src/main/java/com/acme/lifeservice/common/metrics/OrderMetrics.java"), """
                package com.acme.lifeservice.common.metrics;

                import io.micrometer.core.instrument.MeterRegistry;
                import org.springframework.stereotype.Component;

                @Component
                public class OrderMetrics {
                    private final MeterRegistry meterRegistry = null;
                }
                """);
    }
}
