package com.devcontext.benchmark.context;

import java.nio.file.Path;
import java.util.Locale;

public class ContextBenchmarkReportNormalizer {

    public String path(Path root, Path file) {
        return path(root.relativize(file.toAbsolutePath().normalize()).toString());
    }

    public String path(String value) {
        return value == null ? "" : value.replace('\\', '/').replaceAll("/+", "/");
    }

    public String lowerPath(String value) {
        return path(value).toLowerCase(Locale.ROOT);
    }

    public String caseFileName(String caseId) {
        String safe = caseId == null ? "case" : caseId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]+", "-");
        safe = safe.replaceAll("-{2,}", "-").replaceAll("^-|-$", "");
        return (safe.isBlank() ? "case" : safe) + ".json";
    }

    public boolean matchesGlob(String pattern, String path) {
        String regex = globToRegex(lowerPath(pattern));
        return lowerPath(path).matches(regex);
    }

    private String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                boolean doublestar = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                if (doublestar) {
                    regex.append(".*");
                    i++;
                } else {
                    regex.append("[^/]*");
                }
            } else if (c == '?') {
                regex.append('.');
            } else if ("\\.[]{}()+-^$|".indexOf(c) >= 0) {
                regex.append('\\').append(c);
            } else {
                regex.append(c);
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
