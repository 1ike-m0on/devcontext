package com.devcontext.application.knowledge;

import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RagPromptBuilder {

    public String build(String query, String rewrittenQuery, List<KnowledgeSearchResult> results) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are DevContext's local knowledge base assistant.
                Answer the user question using only the retrieved context below.
                Cite sources with [S1], [S2] style references.
                If the answer is not supported by the retrieved context, say what is missing and do not invent details.

                User question:
                """);
        prompt.append(query).append("\n\n");
        prompt.append("Rewritten query:\n").append(rewrittenQuery).append("\n\n");
        prompt.append("Retrieved context:\n");
        if (results.isEmpty()) {
            prompt.append("No context was retrieved.\n");
            return prompt.toString();
        }
        int index = 1;
        for (KnowledgeSearchResult result : results) {
            prompt.append("\n[S").append(index++).append("] ")
                    .append(result.filePath());
            if (result.headingPath() != null && !result.headingPath().isBlank()) {
                prompt.append(" > ").append(result.headingPath());
            }
            prompt.append("\n");
            prompt.append(result.content()).append("\n");
        }
        return prompt.toString();
    }
}
