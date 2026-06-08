package com.devcontext.application.llm;

public final class LlmErrorTypes {

    public static final String AUTH_FAILED = "LLM_AUTH_FAILED";
    public static final String QUOTA_EXCEEDED = "LLM_QUOTA_EXCEEDED";
    public static final String TIMEOUT = "LLM_TIMEOUT";
    public static final String NETWORK_FAILED = "LLM_NETWORK_FAILED";
    public static final String PROXY_REQUIRED = "LLM_PROXY_REQUIRED";
    public static final String PARSE_FAILED = "LLM_PARSE_FAILED";
    public static final String PROVIDER_NOT_CONFIGURED = "LLM_PROVIDER_NOT_CONFIGURED";

    private LlmErrorTypes() {
    }

    public static String fromHttpStatus(int statusCode) {
        if (statusCode == 401 || statusCode == 403) {
            return AUTH_FAILED;
        }
        if (statusCode == 408 || statusCode == 504) {
            return TIMEOUT;
        }
        if (statusCode == 429) {
            return QUOTA_EXCEEDED;
        }
        return NETWORK_FAILED;
    }

    public static String fromMessage(String message) {
        String normalized = message == null ? "" : message.toLowerCase();
        if (normalized.contains("proxy")) {
            return PROXY_REQUIRED;
        }
        if (normalized.contains("timeout") || normalized.contains("timed out")) {
            return TIMEOUT;
        }
        return NETWORK_FAILED;
    }
}
