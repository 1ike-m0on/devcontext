package com.devcontext.application.context;

public record QuestionContextResolveCommand(
        String question,
        Integer maxItems
) {
}
