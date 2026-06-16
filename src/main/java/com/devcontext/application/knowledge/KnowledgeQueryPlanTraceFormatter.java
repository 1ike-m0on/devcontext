package com.devcontext.application.knowledge;

import com.devcontext.domain.knowledge.KnowledgeQueryPlan;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeQueryPlanTraceFormatter {

    public Map<String, Object> compactTrace(KnowledgeQueryPlan queryPlan) {
        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("intent", queryPlan.intent());
        trace.put("normalizedTerms", queryPlan.normalizedTerms());
        trace.put("requiredEvidenceTypes", queryPlan.requiredEvidenceTypes());
        trace.put("preferredEvidenceTypes", queryPlan.preferredEvidenceTypes());
        trace.put("requiredSourceKinds", queryPlan.requiredSourceKinds());
        trace.put("preferredSourceKinds", queryPlan.preferredSourceKinds());
        trace.put("forbiddenSourceKinds", queryPlan.forbiddenSourceKinds());
        trace.put("fallbackStrategy", queryPlan.fallbackStrategy());
        trace.put("planningReasons", queryPlan.planningReasons());
        return trace;
    }

    public String summary(KnowledgeQueryPlan queryPlan) {
        return "intent=" + queryPlan.intent()
                + "; requiredEvidence=" + queryPlan.requiredEvidenceTypes()
                + "; preferredEvidence=" + queryPlan.preferredEvidenceTypes()
                + "; preferredSourceKinds=" + queryPlan.preferredSourceKinds()
                + "; fallback=" + queryPlan.fallbackStrategy()
                + "; reasons=" + queryPlan.planningReasons();
    }
}
