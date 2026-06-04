package com.devcontext.adapters.web;

import com.devcontext.application.knowledge.CreateKnowledgeSourceCommand;
import com.devcontext.application.knowledge.KnowledgeIndexApplicationService;
import com.devcontext.application.knowledge.KnowledgeSearchApplicationService;
import com.devcontext.application.knowledge.KnowledgeSearchCommand;
import com.devcontext.application.knowledge.RagAnswerApplicationService;
import com.devcontext.application.knowledge.RagAskCommand;
import com.devcontext.common.api.ApiResponse;
import com.devcontext.domain.knowledge.EvidenceCoverageReport;
import com.devcontext.domain.knowledge.KnowledgeIndexResult;
import com.devcontext.domain.knowledge.KnowledgeRunDetail;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.knowledge.RagAnswerResult;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KnowledgeController {

    private final KnowledgeIndexApplicationService indexService;
    private final KnowledgeSearchApplicationService searchService;
    private final RagAnswerApplicationService ragAnswerService;

    public KnowledgeController(
            KnowledgeIndexApplicationService indexService,
            KnowledgeSearchApplicationService searchService,
            RagAnswerApplicationService ragAnswerService
    ) {
        this.indexService = indexService;
        this.searchService = searchService;
        this.ragAnswerService = ragAnswerService;
    }

    @PostMapping("/api/knowledge-sources")
    public ApiResponse<KnowledgeSource> createSource(@RequestBody CreateKnowledgeSourceRequest request) {
        return ApiResponse.ok(indexService.createSource(new CreateKnowledgeSourceCommand(
                request.name(),
                request.rootPath(),
                request.sourceType()
        )));
    }

    @GetMapping("/api/knowledge-sources")
    public ApiResponse<List<KnowledgeSource>> listSources() {
        return ApiResponse.ok(indexService.listSources());
    }

    @PostMapping("/api/knowledge-sources/{sourceId}/index")
    public ApiResponse<KnowledgeIndexResult> indexSource(@PathVariable Long sourceId) {
        return ApiResponse.ok(indexService.indexSource(sourceId));
    }

    @GetMapping("/api/knowledge-sources/{sourceId}/coverage")
    public ApiResponse<EvidenceCoverageReport> coverage(@PathVariable Long sourceId) {
        return ApiResponse.ok(indexService.coverage(sourceId));
    }

    @PostMapping("/api/knowledge/search")
    public ApiResponse<KnowledgeSearchResponse> search(@RequestBody KnowledgeSearchRequest request) {
        return ApiResponse.ok(searchService.search(new KnowledgeSearchCommand(
                request.query(),
                request.sourceId(),
                request.topK()
        )));
    }

    @PostMapping("/api/knowledge/ask")
    public ApiResponse<RagAnswerResult> ask(@RequestBody RagAskRequest request) {
        return ApiResponse.ok(ragAnswerService.ask(new RagAskCommand(
                request.query(),
                request.sourceId(),
                request.topK()
        )));
    }

    @GetMapping("/api/knowledge/runs/{runId}")
    public ApiResponse<KnowledgeRunDetail> getRun(@PathVariable Long runId) {
        return ApiResponse.ok(ragAnswerService.getRunDetail(runId));
    }

    public record CreateKnowledgeSourceRequest(
            String name,
            String rootPath,
            String sourceType
    ) {
    }

    public record KnowledgeSearchRequest(
            String query,
            Long sourceId,
            Integer topK
    ) {
    }

    public record RagAskRequest(
            String query,
            Long sourceId,
            Integer topK
    ) {
    }
}
