package com.devcontext.domain.codemap;

import java.util.List;

public record CodeModule(
        String name,
        String path,
        List<String> classes,
        String responsibility
) {
}
