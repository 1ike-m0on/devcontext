package com.devcontext.domain.codemap;

import java.util.List;

public record CodeDomainTerm(
        String term,
        List<String> classes,
        List<String> files
) {
}
