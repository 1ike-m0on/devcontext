package com.devcontext.domain.context;

import java.util.List;

public record ProjectContextStatus(
        Long projectId,
        List<ContextDocumentStatus> documents
) {
}
