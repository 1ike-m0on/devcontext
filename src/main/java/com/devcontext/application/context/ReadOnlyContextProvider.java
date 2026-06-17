package com.devcontext.application.context;

import com.devcontext.domain.context.ReadOnlyContextFileReadRequest;
import com.devcontext.domain.context.ReadOnlyContextFileSearchRequest;
import com.devcontext.domain.context.ReadOnlyContextReadResult;
import com.devcontext.domain.context.ReadOnlyContextSearchResult;

public interface ReadOnlyContextProvider {

    ReadOnlyContextReadResult readFile(ReadOnlyContextFileReadRequest request);

    ReadOnlyContextSearchResult searchFiles(ReadOnlyContextFileSearchRequest request);
}
