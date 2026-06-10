package com.devcontext.application.memory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class ObservationPrivacySanitizer {

    private static final Pattern AUTHORIZATION_BEARER = Pattern.compile("(?i)(authorization\\s*:\\s*bearer\\s+)[^\\s,;]+");
    private static final Pattern API_KEY = Pattern.compile("(?i)(api[-_ ]?key\\s*[:=]\\s*)[^\\s,;}\\]]+");
    private static final Pattern GEMINI_KEY = Pattern.compile("(?i)AIza[0-9A-Za-z_-]{16,}");
    private static final Pattern OPENAI_STYLE_KEY = Pattern.compile("(?i)sk-[0-9A-Za-z_-]{16,}");
    private static final Pattern WINDOWS_USER_HOME = Pattern.compile("(?i)[A-Z]:\\\\Users\\\\[^\\\\\\s]+");
    private static final Pattern UNIX_USER_HOME = Pattern.compile("(?i)/(Users|home)/[^/\\s]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public String title(String value) {
        return sanitize(value, 200);
    }

    public String summary(String value) {
        return sanitize(value, 2000);
    }

    public String error(String value) {
        return sanitize(value, 500);
    }

    public String metadataText(String value) {
        return sanitize(value, 500);
    }

    public String feedbackHash(String userFeedback) {
        String normalized = normalize(sanitize(userFeedback, 2000));
        if (normalized.isBlank()) {
            return "empty";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available", e);
        }
    }

    private String sanitize(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String sanitized = value;
        sanitized = AUTHORIZATION_BEARER.matcher(sanitized).replaceAll("$1***");
        sanitized = API_KEY.matcher(sanitized).replaceAll("$1***");
        sanitized = GEMINI_KEY.matcher(sanitized).replaceAll("***");
        sanitized = OPENAI_STYLE_KEY.matcher(sanitized).replaceAll("***");
        sanitized = WINDOWS_USER_HOME.matcher(sanitized).replaceAll("[USER_HOME]");
        sanitized = UNIX_USER_HOME.matcher(sanitized).replaceAll("/[USER_HOME]");
        sanitized = sanitized.trim();
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        return sanitized.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private String normalize(String value) {
        if (value == null) {
            return "";
        }
        return WHITESPACE.matcher(value.trim()).replaceAll(" ");
    }
}
