package com.devcontext.application.review.context;

import com.devcontext.domain.context.ContextItem;
import java.util.List;

public interface ReviewContextProvider {

    boolean supports(ReviewContextRequest request);

    List<ContextItem> provide(ReviewContextRequest request);
}
