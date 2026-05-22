package com.devcontext.ports.run;

import com.devcontext.domain.run.AgentEvent;
import java.util.List;

public interface AgentEventRepository {

    AgentEvent save(AgentEvent event);

    List<AgentEvent> findByRunId(Long runId);
}

