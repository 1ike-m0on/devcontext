package com.devcontext.context;

import com.devcontext.domain.context.ContextItem;
import java.util.List;

public interface ContextProvider {

    boolean supports(ContextRequest request);

    List<ContextItem> provide(ContextRequest request);
}

