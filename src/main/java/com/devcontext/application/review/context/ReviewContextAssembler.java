package com.devcontext.application.review.context;

import com.devcontext.domain.context.ContextItem;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ReviewContextAssembler {

    private final List<ReviewContextProvider> providers;

    public ReviewContextAssembler(List<ReviewContextProvider> providers) {
        this.providers = providers;
    }

    public List<ContextItem> assemble(ReviewContextRequest request) {
        return providers.stream()
                .filter(provider -> provider.supports(request))
                .flatMap(provider -> provider.provide(request).stream())
                .sorted(Comparator.comparingInt(ContextItem::priority).reversed())
                .toList();
    }
}
