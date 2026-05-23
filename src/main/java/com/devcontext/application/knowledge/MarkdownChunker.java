package com.devcontext.application.knowledge;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class MarkdownChunker {

    private static final int MAX_CHARS = 1400;

    public List<MarkdownChunk> chunk(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<MarkdownChunk> chunks = new ArrayList<>();
        String currentHeading = "";
        StringBuilder buffer = new StringBuilder();

        for (String line : content.replace("\r\n", "\n").split("\n")) {
            if (isHeadingLine(line)) {
                flush(chunks, currentHeading, buffer);
                currentHeading = line.replaceFirst("^#+", "").trim();
                buffer.append(line).append('\n');
            } else {
                buffer.append(line).append('\n');
                if (buffer.length() >= MAX_CHARS) {
                    flush(chunks, currentHeading, buffer);
                }
            }
        }
        flush(chunks, currentHeading, buffer);
        return chunks;
    }

    private void flush(List<MarkdownChunk> chunks, String heading, StringBuilder buffer) {
        String text = buffer.toString().trim();
        if (text.isBlank()) {
            buffer.setLength(0);
            return;
        }
        if (!hasBodyContent(text)) {
            buffer.setLength(0);
            return;
        }
        if (text.length() <= MAX_CHARS) {
            chunks.add(new MarkdownChunk(heading, text));
        } else {
            int start = 0;
            while (start < text.length()) {
                int end = Math.min(text.length(), start + MAX_CHARS);
                chunks.add(new MarkdownChunk(heading, text.substring(start, end).trim()));
                start = end;
            }
        }
        buffer.setLength(0);
    }

    private boolean hasBodyContent(String text) {
        for (String line : text.replace("\r\n", "\n").split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isBlank() || isHeadingLine(trimmed)) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean isHeadingLine(String line) {
        return line != null && line.trim().matches("^#{1,6}\\s+.+");
    }

    public record MarkdownChunk(
            String headingPath,
            String content
    ) {
    }
}
