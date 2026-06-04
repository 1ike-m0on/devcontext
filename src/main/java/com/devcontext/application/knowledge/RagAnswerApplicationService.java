package com.devcontext.application.knowledge;

import com.devcontext.application.run.AgentRunApplicationService;
import com.devcontext.config.DevContextLlmProperties;
import com.devcontext.domain.knowledge.KnowledgeRunDetail;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.RagAnswerResult;
import com.devcontext.domain.llm.LlmRequest;
import com.devcontext.domain.llm.LlmResponse;
import com.devcontext.domain.run.AgentRun;
import com.devcontext.ports.knowledge.RetrievalRecordRepository;
import com.devcontext.ports.llm.LlmClient;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class RagAnswerApplicationService {

    private final KnowledgeSearchApplicationService searchService;
    private final RagPromptBuilder promptBuilder;
    private final LlmClient llmClient;
    private final DevContextLlmProperties llmProperties;
    private final AgentRunApplicationService runService;
    private final RetrievalRecordRepository retrievalRecordRepository;

    public RagAnswerApplicationService(
            KnowledgeSearchApplicationService searchService,
            RagPromptBuilder promptBuilder,
            LlmClient llmClient,
            DevContextLlmProperties llmProperties,
            AgentRunApplicationService runService,
            RetrievalRecordRepository retrievalRecordRepository
    ) {
        this.searchService = searchService;
        this.promptBuilder = promptBuilder;
        this.llmClient = llmClient;
        this.llmProperties = llmProperties;
        this.runService = runService;
        this.retrievalRecordRepository = retrievalRecordRepository;
    }

    public RagAnswerResult ask(RagAskCommand command) {
        AgentRun run = runService.startRun(null, "KNOWLEDGE_RAG_ASK", llmProperties.modelName(), "v0.4");
        try {
            KnowledgeSearchResponse searchResponse = searchService.search(new KnowledgeSearchCommand(
                    command.query(),
                    command.sourceId(),
                    command.topK()
            ), run.id());
            runService.recordEvent(run.id(), "KNOWLEDGE_QUERY_REWRITTEN", searchResponse.query(), searchResponse.rewrittenQuery(), "success", null, null);
            runService.recordEvent(run.id(), "KNOWLEDGE_QUERY_PLAN_BUILT", searchResponse.query(), searchResponse.queryPlan().preferredEvidenceTypes().toString(), "success", null, null);
            runService.recordEvent(run.id(), "KNOWLEDGE_RETRIEVED", searchResponse.rewrittenQuery(), searchResponse.results().size() + " chunks retrieved", "success", null, null);
            runService.recordEvent(run.id(), "KNOWLEDGE_EVIDENCE_RETRIEVED", searchResponse.queryPlan().requiredEvidenceTypes().toString(), evidenceDistribution(searchResponse), "success", null, null);

            String prompt = promptBuilder.build(searchResponse.query(), searchResponse.rewrittenQuery(), searchResponse.queryPlan(), searchResponse.results());
            runService.recordEvent(run.id(), "PROMPT_BUILT", "knowledge RAG prompt", prompt.length() + " chars", "success", null, null);

            LlmResponse response = llmClient.chat(new LlmRequest(prompt, llmProperties.modelName()));
            runService.recordEvent(run.id(), "LLM_CALLED", llmProperties.modelName(), "LLM response generated", "success", null, null);
            runService.recordEvent(run.id(), "RAG_ANSWER_GENERATED", searchResponse.rewrittenQuery(), response.content().length() + " chars", "success", null, null);
            runService.finishRun(run, response.inputTokenEstimate(), response.outputTokenEstimate());
            return new RagAnswerResult(
                    run.id(),
                    searchResponse.retrievalRecordId(),
                    searchResponse.query(),
                    searchResponse.rewrittenQuery(),
                    searchResponse.queryPlan(),
                    response.content(),
                    searchResponse.results()
            );
        } catch (RuntimeException e) {
            runService.failRun(run, e.getMessage());
            throw e;
        }
    }

    public KnowledgeRunDetail getRunDetail(Long runId) {
        return new KnowledgeRunDetail(
                runService.getRun(runId),
                runService.listEvents(runId),
                retrievalRecordRepository.findByRunId(runId)
        );
    }

    private String evidenceDistribution(KnowledgeSearchResponse response) {
        return response.results().stream()
                .flatMap(result -> result.evidenceTypes().stream())
                .collect(Collectors.groupingBy(Enum::name, Collectors.counting()))
                .toString();
    }
}
