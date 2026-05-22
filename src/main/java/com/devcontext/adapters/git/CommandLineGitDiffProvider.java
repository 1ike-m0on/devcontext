package com.devcontext.adapters.git;

import com.devcontext.common.error.ApiException;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.ports.git.GitDiffProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CommandLineGitDiffProvider implements GitDiffProvider {

    private static final int MAX_DIFF_CHARS = 120_000;

    @Override
    public GitDiff diff(String rootPath, String baseBranch, String compareBranch) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        ensureGitRepository(root);
        String diffText = runGit(root, "diff", "--no-ext-diff", "--unified=80", baseBranch + "..." + compareBranch);
        String changedFilesText = runGit(root, "diff", "--name-only", baseBranch + "..." + compareBranch);
        boolean truncated = diffText.length() > MAX_DIFF_CHARS;
        String usableDiff = truncated ? diffText.substring(0, MAX_DIFF_CHARS) : diffText;
        List<String> changedFiles = changedFilesText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        return new GitDiff(usableDiff, changedFiles, sha256(diffText), truncated);
    }

    private void ensureGitRepository(Path root) {
        String result = runGit(root, "rev-parse", "--is-inside-work-tree");
        if (!"true".equals(result.trim())) {
            throw new ApiException("NOT_GIT_REPOSITORY", "Project path is not a Git repository", HttpStatus.BAD_REQUEST);
        }
    }

    private String runGit(Path root, String... args) {
        try {
            ProcessBuilder builder = new ProcessBuilder(buildCommand(root, args));
            builder.redirectErrorStream(true);
            Process process = builder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process));
            boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ApiException("GIT_DIFF_TIMEOUT", "Git diff command timed out", HttpStatus.BAD_REQUEST);
            }
            String output = outputFuture.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new ApiException("GIT_DIFF_FAILED", compact(output), HttpStatus.BAD_REQUEST);
            }
            return output;
        } catch (IOException e) {
            throw new ApiException("GIT_DIFF_FAILED", "Failed to run git: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApiException("GIT_DIFF_FAILED", "Git command was interrupted", HttpStatus.BAD_REQUEST);
        } catch (ExecutionException | TimeoutException e) {
            throw new ApiException("GIT_DIFF_FAILED", "Failed to read git output: " + e.getMessage(), HttpStatus.BAD_REQUEST);
        }
    }

    private String readOutput(Process process) {
        try {
            return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private List<String> buildCommand(Path root, String... args) {
        java.util.ArrayList<String> command = new java.util.ArrayList<>();
        command.add("git");
        command.add("-C");
        command.add(root.toString());
        command.addAll(List.of(args));
        return command;
    }

    private String sha256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String compact(String output) {
        String compact = output == null ? "" : output.replaceAll("\\s+", " ").trim();
        if (compact.isBlank()) {
            return "Git diff failed";
        }
        return compact.length() > 300 ? compact.substring(0, 300) : compact;
    }
}
