package com.devcontext.domain.codemap;

import java.util.List;

public record CodeTechnologySignal(
        String technology,
        List<String> classes,
        List<String> files
) {
}
