package com.devcontext.domain.codemap;

import java.util.List;

public record CodeMapFileEntry(
        String path,
        String kind,
        String language,
        String module,
        List<String> roles
) {
    public CodeMapFileEntry {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
