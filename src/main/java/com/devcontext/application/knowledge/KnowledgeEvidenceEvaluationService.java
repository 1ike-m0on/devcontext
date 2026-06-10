package com.devcontext.application.knowledge;

import com.devcontext.domain.knowledge.EvidenceCitationAssessment;
import com.devcontext.domain.knowledge.EvidenceEvaluation;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeSearchResponse;
import com.devcontext.domain.knowledge.KnowledgeSearchResult;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class KnowledgeEvidenceEvaluationService {

    public EvidenceEvaluation evaluate(KnowledgeSearchResponse response) {
        List<KnowledgeEvidenceType> required = safeTypes(response.queryPlan().requiredEvidenceTypes());
        List<KnowledgeEvidenceType> preferred = safeTypes(response.queryPlan().preferredEvidenceTypes());
        List<KnowledgeSearchResult> results = response.results() == null ? List.of() : response.results();

        LinkedHashSet<KnowledgeEvidenceType> observed = new LinkedHashSet<>();
        List<EvidenceCitationAssessment> citationAssessments = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            KnowledgeSearchResult result = results.get(i);
            List<KnowledgeEvidenceType> evidenceTypes = safeTypes(result.evidenceTypes());
            observed.addAll(evidenceTypes);
            citationAssessments.add(new EvidenceCitationAssessment(
                    i + 1,
                    result.filePath(),
                    evidenceTypes,
                    sourceReliabilities(evidenceTypes),
                    overlaps(evidenceTypes, required),
                    overlaps(evidenceTypes, preferred)
            ));
        }

        List<KnowledgeEvidenceType> matchedRequired = intersection(required, observed);
        List<KnowledgeEvidenceType> missingRequired = difference(required, observed);
        List<KnowledgeEvidenceType> matchedPreferred = intersection(preferred, observed);
        List<KnowledgeEvidenceType> missingPreferred = difference(preferred, observed);
        boolean noRetrievedContext = results.isEmpty();
        boolean noAnswerRequired = noRetrievedContext || !missingRequired.isEmpty();
        List<String> reasons = reasons(noRetrievedContext, matchedRequired, missingRequired, matchedPreferred, missingPreferred);
        return new EvidenceEvaluation(
                noAnswerRequired ? "insufficient_evidence" : "sufficient",
                !noAnswerRequired,
                noAnswerRequired,
                required,
                matchedRequired,
                missingRequired,
                preferred,
                matchedPreferred,
                missingPreferred,
                observed.stream().toList(),
                citationAssessments,
                reasons
        );
    }

    public String insufficientEvidenceAnswer(EvidenceEvaluation evaluation) {
        if (evaluation.missingRequiredEvidenceTypes().isEmpty()) {
            return "Insufficient evidence: no retrieved citations were available, so DevContext cannot answer without inventing details. "
                    + "Index or retrieve project evidence that matches the query plan, then ask again.";
        }
        return "Insufficient evidence: required evidence "
                + evaluation.missingRequiredEvidenceTypes()
                + " was not found in the retrieved citations. DevContext is applying the no-answer guard instead of asking the LLM to infer. "
                + "Index or retrieve the missing evidence type(s), then ask again.";
    }

    private List<String> reasons(
            boolean noRetrievedContext,
            List<KnowledgeEvidenceType> matchedRequired,
            List<KnowledgeEvidenceType> missingRequired,
            List<KnowledgeEvidenceType> matchedPreferred,
            List<KnowledgeEvidenceType> missingPreferred
    ) {
        List<String> reasons = new ArrayList<>();
        if (noRetrievedContext) {
            reasons.add("No retrieved citations were available.");
        }
        if (missingRequired.isEmpty()) {
            reasons.add(matchedRequired.isEmpty()
                    ? "No required evidence was requested by the query plan."
                    : "Required evidence is satisfied: " + matchedRequired + ".");
        } else {
            reasons.add("Missing required evidence: " + missingRequired + ".");
        }
        if (matchedPreferred.isEmpty()) {
            if (missingPreferred.isEmpty()) {
                reasons.add("No preferred evidence was requested by the query plan.");
            } else {
                reasons.add("No preferred evidence matched; missing preferred evidence: " + missingPreferred + ".");
            }
        } else {
            reasons.add("Preferred evidence matched: " + matchedPreferred + ".");
        }
        return reasons;
    }

    private List<String> sourceReliabilities(List<KnowledgeEvidenceType> evidenceTypes) {
        LinkedHashSet<String> reliabilities = new LinkedHashSet<>();
        for (KnowledgeEvidenceType evidenceType : evidenceTypes) {
            reliabilities.add(evidenceType.sourceReliability().value());
        }
        return reliabilities.stream().toList();
    }

    private boolean overlaps(List<KnowledgeEvidenceType> left, List<KnowledgeEvidenceType> right) {
        Set<KnowledgeEvidenceType> rightSet = new LinkedHashSet<>(right);
        return left.stream().anyMatch(rightSet::contains);
    }

    private List<KnowledgeEvidenceType> intersection(List<KnowledgeEvidenceType> expected, Set<KnowledgeEvidenceType> observed) {
        return expected.stream()
                .filter(observed::contains)
                .toList();
    }

    private List<KnowledgeEvidenceType> difference(List<KnowledgeEvidenceType> expected, Set<KnowledgeEvidenceType> observed) {
        return expected.stream()
                .filter(type -> !observed.contains(type))
                .toList();
    }

    private List<KnowledgeEvidenceType> safeTypes(List<KnowledgeEvidenceType> values) {
        return values == null ? List.of() : values;
    }
}
