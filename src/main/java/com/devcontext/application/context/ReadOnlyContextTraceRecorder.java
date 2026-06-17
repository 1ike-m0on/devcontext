package com.devcontext.application.context;

import com.devcontext.domain.context.ReadOnlyContextProviderTrace;

public interface ReadOnlyContextTraceRecorder {

    void record(ReadOnlyContextProviderTrace trace);
}
