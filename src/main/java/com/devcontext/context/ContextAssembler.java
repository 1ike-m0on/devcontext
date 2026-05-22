package com.devcontext.context;

import com.devcontext.domain.context.ContextItem;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class ContextAssembler {

    private final List<ContextProvider> providers;

    public ContextAssembler(List<ContextProvider> providers) {
        this.providers = providers;
    }

    public List<ContextItem> assemble(ContextRequest request) {
        return providers.stream()
                .filter(provider -> provider.supports(request))
                .flatMap(provider -> provider.provide(request).stream())
                .sorted(Comparator.comparingInt(ContextItem::priority).reversed())
                .toList();
    }
}

