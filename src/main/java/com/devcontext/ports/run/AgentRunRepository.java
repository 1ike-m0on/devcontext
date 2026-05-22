package com.devcontext.ports.run;

import com.devcontext.domain.run.AgentRun;
import java.util.Optional;

public interface AgentRunRepository {

    AgentRun save(AgentRun run);

    AgentRun update(AgentRun run);

    Optional<AgentRun> findById(Long id);
}

