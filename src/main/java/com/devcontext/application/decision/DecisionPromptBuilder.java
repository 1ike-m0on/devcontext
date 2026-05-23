package com.devcontext.application.decision;

import com.devcontext.domain.decision.DecisionCard;
import com.devcontext.domain.decision.DecisionEvidence;
import com.devcontext.domain.decision.DecisionSearchResult;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class DecisionPromptBuilder {

    public String build(DecisionReuseAdviceCommand command, List<DecisionSearchResult> matches) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("""
                You are DevContext's Decision Memory advisor.
                Compare the user's current engineering question with recalled historical decision cards.
                Do not blindly reuse old decisions. Explain what transfers, what does not, and what should be verified.

                Output in Markdown with these sections:
                1. Current Problem Understanding
                2. Recalled Historical Decisions
                3. Evidence And Credibility
                4. Similarities
                5. Differences
                6. Reusable Experience
                7. Do Not Directly Reuse
                8. Recommendation
                9. Questions To Confirm

                Current question:
                """);
        prompt.append(valueOr(command.query(), "(empty)")).append("\n\n");
        prompt.append("Requested tags: ").append(String.join(", ", safeList(command.tags()))).append("\n\n");
        prompt.append("Recalled decision cards:\n");
        if (matches.isEmpty()) {
            prompt.append("- No historical decision cards were recalled. Say this clearly and give conservative generic advice.\n");
            return prompt.toString();
        }
        for (DecisionSearchResult match : matches) {
            DecisionCard card = match.decision();
            prompt.append("\n## Decision Card #").append(card.id()).append("\n");
            prompt.append("- Title: ").append(card.title()).append("\n");
            prompt.append("- Status: ").append(card.status()).append("\n");
            prompt.append("- Tags: ").append(String.join(", ", safeList(card.tags()))).append("\n");
            prompt.append("- Scenario: ").append(card.scenario()).append("\n");
            prompt.append("- Options: ").append(String.join("; ", safeList(card.options()))).append("\n");
            prompt.append("- Decision: ").append(card.decision()).append("\n");
            prompt.append("- Reasons: ").append(String.join("; ", safeList(card.reasons()))).append("\n");
            prompt.append("- Trade-offs: ").append(String.join("; ", safeList(card.tradeOffs()))).append("\n");
            prompt.append("- Applicable when: ").append(String.join("; ", safeList(card.applicableWhen()))).append("\n");
            prompt.append("- Not applicable when: ").append(String.join("; ", safeList(card.notApplicableWhen()))).append("\n");
            prompt.append("- Outcome: ").append(valueOr(card.outcome(), "Unknown")).append("\n");
            prompt.append("- Search score: ").append(match.score()).append("\n");
            prompt.append("- Matched tags: ").append(String.join(", ", match.matchedTags())).append("\n");
            prompt.append("- Matched terms: ").append(String.join(", ", match.matchedTerms())).append("\n");
            prompt.append("- Evidence:\n");
            if (card.evidence() == null || card.evidence().isEmpty()) {
                prompt.append("  - No evidence recorded.\n");
            } else {
                for (DecisionEvidence evidence : card.evidence()) {
                    prompt.append("  - [").append(valueOr(evidence.type(), "unknown")).append("] ")
                            .append(valueOr(evidence.ref(), "no-ref")).append(": ")
                            .append(valueOr(evidence.summary(), "no-summary")).append("\n");
                }
            }
        }
        return prompt.toString();
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String valueOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
