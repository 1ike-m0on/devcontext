package com.devcontext.domain.codemap;

import java.util.List;

public record CodeEntrypoint(
        String type,
        String file,
        List<String> methods
) {
}
