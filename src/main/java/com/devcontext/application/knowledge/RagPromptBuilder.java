package com.devcontext.application.knowledge;

import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RagPromptBuilder {

    public String build(String query, String rewrittenQuery, KnowledgeQueryPlan queryPlan, List<KnowledgeSearchResult> results) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are DevContext's local knowledge base assistant.
                Answer the user question using only the retrieved context below.
                Cite sources with [S1], [S2] style references.
                If the answer is not supported by the retrieved context, say what is missing and do not invent details.
                Follow the evidence plan. Prefer concrete implementation evidence over high-level summaries when the question asks for details.
                If required evidence is missing, answer with a clear missing-evidence statement and list what should be indexed next.

                User question:
                """);
        prompt.append(query).append("\n\n");
        prompt.append("Rewritten query:\n").append(rewrittenQuery).append("\n\n");
        prompt.append("Evidence plan:\n")
                .append("- normalized terms: ").append(queryPlan.normalizedTerms()).append("\n")
                .append("- required evidence: ").append(queryPlan.requiredEvidenceTypes()).append("\n")
                .append("- preferred evidence: ").append(queryPlan.preferredEvidenceTypes()).append("\n")
                .append("- no-answer policy: ").append(queryPlan.noAnswerPolicy()).append("\n\n");
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
            prompt.append("\nEvidence types: ").append(evidenceTypes(result.evidenceTypes()));
            prompt.append("\n");
            prompt.append(result.content()).append("\n");
        }
        return prompt.toString();
    }

    private String evidenceTypes(List<KnowledgeEvidenceType> evidenceTypes) {
        if (evidenceTypes == null || evidenceTypes.isEmpty()) {
            return "[]";
        }
        return evidenceTypes.toString();
    }
}
