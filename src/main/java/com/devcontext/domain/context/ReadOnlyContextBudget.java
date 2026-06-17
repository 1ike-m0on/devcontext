package com.devcontext.domain.context;

public record ReadOnlyContextBudget(
        int maxMatches,
        int maxFiles,
        int maxCharacters,
        int maxLines
) {
    public ReadOnlyContextBudget {
        maxMatches = Math.max(0, maxMatches);
        maxFiles = Math.max(0, maxFiles);
        maxCharacters = Math.max(0, maxCharacters);
        maxLines = Math.max(0, maxLines);
    }

    public static ReadOnlyContextBudget read(int maxFiles, int maxCharacters, int maxLines) {
        return new ReadOnlyContextBudget(1, maxFiles, maxCharacters, maxLines);
    }

    public static ReadOnlyContextBudget search(int maxMatches, int maxFiles, int maxCharacters, int maxLines) {
        return new ReadOnlyContextBudget(maxMatches, maxFiles, maxCharacters, maxLines);
    }
}
