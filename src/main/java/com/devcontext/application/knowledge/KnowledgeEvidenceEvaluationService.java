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
        KnowledgeQueryGuardPlan guardPlan = KnowledgeQueryGuardPlan.from(response);

        LinkedHashSet<KnowledgeEvidenceType> observed = new LinkedHashSet<>();
        LinkedHashSet<KnowledgeEvidenceType> strongObserved = new LinkedHashSet<>();
        LinkedHashSet<KnowledgeEvidenceType> weakObserved = new LinkedHashSet<>();
        LinkedHashSet<String> weakReasons = new LinkedHashSet<>();
        List<EvidenceCitationAssessment> citationAssessments = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            KnowledgeSearchResult result = results.get(i);
            List<KnowledgeEvidenceType> evidenceTypes = safeTypes(result.evidenceTypes());
            observed.addAll(evidenceTypes);
            List<String> citationSourceKinds = sourceKinds(evidenceTypes);
            List<String> citationWeaknessReasons = weaknessReasons(result, evidenceTypes, citationSourceKinds, guardPlan);
            boolean weakEvidence = !citationWeaknessReasons.isEmpty();
            if (weakEvidence) {
                weakObserved.addAll(evidenceTypes);
                weakReasons.addAll(citationWeaknessReasons);
            } else {
                strongObserved.addAll(evidenceTypes);
            }
            citationAssessments.add(new EvidenceCitationAssessment(
                    i + 1,
                    result.filePath(),
                    evidenceTypes,
                    sourceReliabilities(evidenceTypes),
                    overlaps(evidenceTypes, required),
                    overlaps(evidenceTypes, preferred),
                    citationSourceKinds,
                    weakEvidence,
                    citationWeaknessReasons,
                    result.scoreReasons()
            ));
        }

        List<KnowledgeEvidenceType> matchedRequired = intersection(required, strongObserved);
        List<KnowledgeEvidenceType> missingRequired = difference(required, strongObserved);
        List<KnowledgeEvidenceType> matchedPreferred = intersection(preferred, strongObserved);
        List<KnowledgeEvidenceType> missingPreferred = difference(preferred, strongObserved);
        List<KnowledgeEvidenceType> weakMatchedRequired = intersection(required, weakObserved);
        List<KnowledgeEvidenceType> weakMatchedPreferred = intersection(preferred, weakObserved);
        boolean noRetrievedContext = results.isEmpty();
        GuardDecision decision = decide(
                noRetrievedContext,
                required,
                matchedRequired,
                missingRequired,
                matchedPreferred,
                weakMatchedRequired,
                weakMatchedPreferred,
                strongObserved,
                guardPlan,
                results
        );
        boolean noAnswerRequired = decision == GuardDecision.INSUFFICIENT;
        List<String> reasons = reasons(
                noRetrievedContext,
                matchedRequired,
                missingRequired,
                matchedPreferred,
                missingPreferred,
                weakMatchedRequired,
                weakMatchedPreferred,
                weakReasons.stream().toList(),
                decision
        );
        return new EvidenceEvaluation(
                decision.status,
                decision == GuardDecision.SUPPORTED,
                noAnswerRequired,
                required,
                matchedRequired,
                missingRequired,
                preferred,
                matchedPreferred,
                missingPreferred,
                observed.stream().toList(),
                citationAssessments,
                reasons,
                decision.value,
                weakObserved.stream().toList(),
                weakReasons.stream().toList()
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

    public String partialEvidenceAnswer(EvidenceEvaluation evaluation) {
        StringBuilder answer = new StringBuilder();
        answer.append("Partial evidence: DevContext found citations, but the answer guard did not find enough strong evidence ")
                .append("to state a confident implementation conclusion. ");
        if (!evaluation.weakEvidenceTypes().isEmpty()) {
            answer.append("Weak evidence observed: ")
                    .append(evaluation.weakEvidenceTypes())
                    .append(". ");
        }
        if (!evaluation.weakEvidenceReasons().isEmpty()) {
            answer.append("Weakness reasons: ")
                    .append(evaluation.weakEvidenceReasons())
                    .append(". ");
        }
        if (!evaluation.missingRequiredEvidenceTypes().isEmpty()) {
            answer.append("Missing strong required evidence: ")
                    .append(evaluation.missingRequiredEvidenceTypes())
                    .append(". ");
        }
        answer.append("Use the citations as hints only, and index or retrieve stronger primary evidence before relying on a final answer.");
        return answer.toString();
    }

    private List<String> reasons(
            boolean noRetrievedContext,
            List<KnowledgeEvidenceType> matchedRequired,
            List<KnowledgeEvidenceType> missingRequired,
            List<KnowledgeEvidenceType> matchedPreferred,
            List<KnowledgeEvidenceType> missingPreferred,
            List<KnowledgeEvidenceType> weakMatchedRequired,
            List<KnowledgeEvidenceType> weakMatchedPreferred,
            List<String> weakReasons,
            GuardDecision decision
    ) {
        List<String> reasons = new ArrayList<>();
        reasons.add("Answer guard decision: " + decision.value + ".");
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
        if (!weakMatchedRequired.isEmpty()) {
            reasons.add("Required evidence was found only in weak citations: " + weakMatchedRequired + ".");
        }
        if (!weakMatchedPreferred.isEmpty()) {
            reasons.add("Preferred evidence was found only in weak citations: " + weakMatchedPreferred + ".");
        }
        if (!weakReasons.isEmpty()) {
            reasons.add("Weak evidence reasons: " + weakReasons + ".");
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

    private List<String> sourceKinds(List<KnowledgeEvidenceType> evidenceTypes) {
        LinkedHashSet<String> sourceKinds = new LinkedHashSet<>();
        evidenceTypes.stream()
                .map(type -> type.sourceKind().value())
                .forEach(sourceKinds::add);
        return sourceKinds.stream().toList();
    }

    private List<String> weaknessReasons(
            KnowledgeSearchResult result,
            List<KnowledgeEvidenceType> evidenceTypes,
            List<String> sourceKinds,
            KnowledgeQueryGuardPlan guardPlan
    ) {
        LinkedHashSet<String> reasons = new LinkedHashSet<>();
        if (evidenceTypes.contains(KnowledgeEvidenceType.GENERATED_DOC)) {
            reasons.add("generated_doc");
        }
        if (isDerivedOnly(evidenceTypes)) {
            reasons.add("derived_only");
        }
        for (String sourceKind : sourceKinds) {
            if (guardPlan.forbiddenSourceKinds().contains(sourceKind)) {
                reasons.add("forbidden_source_kind:" + sourceKind);
            }
        }
        if (!guardPlan.projectOverview()
                && !guardPlan.expectedSourceKinds().isEmpty()
                && sourceKinds.stream().noneMatch(guardPlan.expectedSourceKinds()::contains)) {
            reasons.add("source_kind_mismatch");
        }
        for (String scoreReason : safeStrings(result.scoreReasons())) {
            if ("todo_penalty".equals(scoreReason)) {
                reasons.add("todo_like");
            }
            if ("generic_doc_penalty".equals(scoreReason)) {
                reasons.add("generic_doc");
            }
        }
        return reasons.stream().toList();
    }

    private GuardDecision decide(
            boolean noRetrievedContext,
            List<KnowledgeEvidenceType> required,
            List<KnowledgeEvidenceType> matchedRequired,
            List<KnowledgeEvidenceType> missingRequired,
            List<KnowledgeEvidenceType> matchedPreferred,
            List<KnowledgeEvidenceType> weakMatchedRequired,
            List<KnowledgeEvidenceType> weakMatchedPreferred,
            Set<KnowledgeEvidenceType> strongObserved,
            KnowledgeQueryGuardPlan guardPlan,
            List<KnowledgeSearchResult> results
    ) {
        if (noRetrievedContext) {
            return GuardDecision.INSUFFICIENT;
        }
        if (!required.isEmpty()) {
            if (missingRequired.isEmpty()) {
                return GuardDecision.SUPPORTED;
            }
            if (!matchedRequired.isEmpty() || !weakMatchedRequired.isEmpty()) {
                return GuardDecision.PARTIAL;
            }
            return GuardDecision.INSUFFICIENT;
        }
        if (!matchedPreferred.isEmpty()) {
            return GuardDecision.SUPPORTED;
        }
        if (guardPlan.projectOverview() && !strongObserved.isEmpty()) {
            return GuardDecision.SUPPORTED;
        }
        if (!weakMatchedPreferred.isEmpty() || !results.isEmpty()) {
            return GuardDecision.PARTIAL;
        }
        return GuardDecision.INSUFFICIENT;
    }

    private boolean isDerivedOnly(List<KnowledgeEvidenceType> evidenceTypes) {
        return !evidenceTypes.isEmpty()
                && evidenceTypes.stream().allMatch(type ->
                type == KnowledgeEvidenceType.GENERATED_DOC || type == KnowledgeEvidenceType.CODE_MAP);
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

    private List<String> safeStrings(List<String> values) {
        return values == null ? List.of() : values;
    }

    private enum GuardDecision {
        SUPPORTED("supported", "sufficient"),
        PARTIAL("partial", "partial_evidence"),
        INSUFFICIENT("insufficient_evidence", "insufficient_evidence");

        private final String value;
        private final String status;

        GuardDecision(String value, String status) {
            this.value = value;
            this.status = status;
        }
    }

    private record KnowledgeQueryGuardPlan(
            String intent,
            List<String> expectedSourceKinds,
            List<String> forbiddenSourceKinds
    ) {
        static KnowledgeQueryGuardPlan from(KnowledgeSearchResponse response) {
            LinkedHashSet<String> expectedSourceKinds = new LinkedHashSet<>();
            expectedSourceKinds.addAll(response.queryPlan().requiredSourceKinds());
            expectedSourceKinds.addAll(response.queryPlan().preferredSourceKinds());
            return new KnowledgeQueryGuardPlan(
                    response.queryPlan().intent(),
                    expectedSourceKinds.stream().toList(),
                    response.queryPlan().forbiddenSourceKinds()
            );
        }

        KnowledgeQueryGuardPlan {
            intent = intent == null ? "" : intent;
            expectedSourceKinds = expectedSourceKinds == null ? List.of() : List.copyOf(expectedSourceKinds);
            forbiddenSourceKinds = forbiddenSourceKinds == null ? List.of() : List.copyOf(forbiddenSourceKinds);
        }

        boolean projectOverview() {
            return "project_overview".equals(intent);
        }
    }
}
