package com.devcontext.domain.knowledge;

import com.devcontext.domain.run.AgentEvent;
import com.devcontext.domain.run.AgentRun;
import java.util.List;

public record KnowledgeRunDetail(
        AgentRun run,
        List<AgentEvent> events,
        List<RetrievalRecord> retrievalRecords
) {
}
