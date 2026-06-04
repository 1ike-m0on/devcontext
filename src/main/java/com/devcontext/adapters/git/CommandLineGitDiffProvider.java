package com.devcontext.adapters.git;

import com.devcontext.common.error.ApiException;
import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.git.GitReviewSource;
import com.devcontext.ports.git.GitDiffProvider;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CommandLineGitDiffProvider implements GitDiffProvider {

    private static final int MAX_DIFF_CHARS = 120_000;
    private static final int MAX_UNTRACKED_FILE_CHARS = 40_000;
    private static final int MAX_UNTRACKED_FILE_BYTES = 200_000;

    @Override
    public GitDiff diff(String rootPath, String baseBranch, String compareBranch, List<String> selectedFiles) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        ensureGitRepository(root);
        List<String> pathspecs = normalizeSelectedFiles(root, selectedFiles);
        String range = baseBranch + "..." + compareBranch;
        return diffByArgs(root, "branch", baseBranch, compareBranch, List.of(range), List.of(range), pathspecs);
    }

    @Override
    public GitDiff workingTreeDiff(String rootPath, List<String> selectedFiles) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        ensureGitRepository(root);
        List<String> pathspecs = normalizeSelectedFiles(root, selectedFiles);
        String trackedDiff = runGit(root, diffCommand(List.of("HEAD"), pathspecs, true).toArray(String[]::new));
        List<String> trackedFiles = changedFiles(runGit(root, diffCommand(List.of("HEAD"), pathspecs, false).toArray(String[]::new)));
        List<String> untrackedFiles = filterSelectedFiles(untrackedFiles(root), pathspecs);
        String fullDiff = joinDiffs(trackedDiff, renderUntrackedDiffs(root, untrackedFiles));
        boolean truncated = fullDiff.length() > MAX_DIFF_CHARS;
        String usableDiff = truncated ? fullDiff.substring(0, MAX_DIFF_CHARS) : fullDiff;
        return new GitDiff(
                usableDiff,
                combineFiles(trackedFiles, untrackedFiles),
                sha256(fullDiff),
                truncated,
                "working_tree",
                "HEAD",
                "working_tree"
        );
    }

    @Override
    public GitDiff currentBranchDiff(String rootPath, String defaultBranch, List<String> selectedFiles) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        ensureGitRepository(root);
        List<String> pathspecs = normalizeSelectedFiles(root, selectedFiles);
        String currentBranch = currentBranch(root);
        String safeDefaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch.trim();
        String range = safeDefaultBranch + "..." + currentBranch;
        return diffByArgs(root, "current_branch", safeDefaultBranch, currentBranch, List.of(range), List.of(range), pathspecs);
    }

    @Override
    public GitDiff lastCommitDiff(String rootPath, List<String> selectedFiles) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        ensureGitRepository(root);
        List<String> pathspecs = normalizeSelectedFiles(root, selectedFiles);
        return diffByArgs(root, "last_commit", "HEAD~1", "HEAD", List.of("HEAD~1", "HEAD"), List.of("HEAD~1", "HEAD"), pathspecs);
    }

    @Override
    public List<GitReviewSource> inspectSources(String rootPath, String defaultBranch) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        ensureGitRepository(root);
        String currentBranch = currentBranch(root);
        String safeDefaultBranch = defaultBranch == null || defaultBranch.isBlank() ? "main" : defaultBranch.trim();
        List<GitReviewSource> sources = new ArrayList<>();

        List<String> untrackedFiles = untrackedFiles(root);
        List<String> workingTreeFiles = combineFiles(changedFilesOrEmpty(root, "HEAD"), untrackedFiles);
        boolean hasWorkingTree = !workingTreeFiles.isEmpty();
        sources.add(new GitReviewSource(
                "working_tree",
                "审查当前改动",
                "审查已跟踪文件的 staged / unstaged 改动，并纳入未跟踪的新文件。",
                hasWorkingTree,
                hasWorkingTree,
                "HEAD",
                "working_tree",
                currentBranch,
                workingTreeFiles.size(),
                workingTreeFiles,
                workingTreeReason(hasWorkingTree, untrackedFiles.size()),
                untrackedFiles.size(),
                untrackedFiles,
                untrackedFiles.isEmpty() ? null : "检测到未跟踪文件，审查时会按新文件内容纳入 diff。"
        ));

        List<String> branchFiles = changedFilesOrEmpty(root, safeDefaultBranch + "..." + currentBranch);
        boolean hasBranchDiff = !branchFiles.isEmpty();
        sources.add(new GitReviewSource(
                "current_branch",
                "审查当前分支",
                "审查当前分支相对默认分支的完整改动。",
                hasBranchDiff,
                !hasWorkingTree && hasBranchDiff,
                safeDefaultBranch,
                currentBranch,
                currentBranch,
                branchFiles.size(),
                branchFiles,
                hasBranchDiff ? "当前分支相对默认分支存在改动。" : "当前分支相对默认分支没有可审查改动。"
        ));

        List<String> lastCommitFiles = changedFilesOrEmpty(root, "HEAD~1", "HEAD");
        boolean hasLastCommit = !lastCommitFiles.isEmpty();
        sources.add(new GitReviewSource(
                "last_commit",
                "审查最近提交",
                "只审查 HEAD 最近一次提交的改动。",
                hasLastCommit,
                !hasWorkingTree && !hasBranchDiff && hasLastCommit,
                "HEAD~1",
                "HEAD",
                currentBranch,
                lastCommitFiles.size(),
                lastCommitFiles,
                hasLastCommit ? "最近一次提交有可审查改动。" : "最近一次提交不可用或没有改动。"
        ));

        return sources;
    }

    private GitDiff diffByArgs(
            Path root,
            String sourceType,
            String baseRef,
            String compareRef,
            List<String> diffRefs,
            List<String> nameRefs,
            List<String> pathspecs
    ) {
        List<String> diffArgs = diffCommand(diffRefs, pathspecs, true);
        List<String> nameArgs = diffCommand(nameRefs, pathspecs, false);
        String diffText = runGit(root, diffArgs.toArray(String[]::new));
        String changedFilesText = runGit(root, nameArgs.toArray(String[]::new));
        boolean truncated = diffText.length() > MAX_DIFF_CHARS;
        String usableDiff = truncated ? diffText.substring(0, MAX_DIFF_CHARS) : diffText;
        List<String> changedFiles = changedFiles(changedFilesText);
        return new GitDiff(usableDiff, changedFiles, sha256(diffText), truncated, sourceType, baseRef, compareRef);
    }

    private List<String> diffCommand(List<String> refs, List<String> pathspecs, boolean includePatch) {
        List<String> args = new ArrayList<>();
        args.add("diff");
        if (includePatch) {
            args.add("--no-ext-diff");
            args.add("--unified=80");
        } else {
            args.add("--name-only");
        }
        args.addAll(refs);
        if (!pathspecs.isEmpty()) {
            args.add("--");
            args.addAll(pathspecs);
        }
        return args;
    }

    private List<String> changedFilesOrEmpty(Path root, String... refs) {
        try {
            List<String> args = new ArrayList<>(List.of("diff", "--name-only"));
            args.addAll(List.of(refs));
            return changedFiles(runGit(root, args.toArray(String[]::new)));
        } catch (ApiException e) {
            return List.of();
        }
    }

    private List<String> changedFiles(String changedFilesText) {
        return changedFilesText.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .distinct()
                .toList();
    }

    private List<String> normalizeSelectedFiles(Path root, List<String> selectedFiles) {
        if (selectedFiles == null || selectedFiles.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String selectedFile : selectedFiles) {
            if (selectedFile == null || selectedFile.isBlank()) {
                continue;
            }
            String relative = selectedFile.trim().replace('\\', '/');
            Path relativePath = Path.of(relative);
            if (relativePath.isAbsolute() || hasParentTraversal(relativePath)) {
                throw new ApiException("REVIEW_FILE_SELECTION_INVALID", "Selected review file must be project-relative: " + selectedFile, HttpStatus.BAD_REQUEST);
            }
            Path target = root.resolve(relative).normalize();
            if (!target.startsWith(root)) {
                throw new ApiException("REVIEW_FILE_SELECTION_INVALID", "Selected review file is outside project root: " + selectedFile, HttpStatus.BAD_REQUEST);
            }
            normalized.add(relative);
        }
        return normalized.stream().distinct().toList();
    }

    private boolean hasParentTraversal(Path relativePath) {
        for (Path pathPart : relativePath) {
            if ("..".equals(pathPart.toString())) {
                return true;
            }
        }
        return false;
    }

    private List<String> filterSelectedFiles(List<String> files, List<String> selectedFiles) {
        if (selectedFiles.isEmpty()) {
            return files;
        }
        return files.stream()
                .filter(selectedFiles::contains)
                .toList();
    }

    private List<String> untrackedFiles(Path root) {
        try {
            return changedFiles(runGit(root, "ls-files", "--others", "--exclude-standard"));
        } catch (ApiException e) {
            return List.of();
        }
    }

    private List<String> combineFiles(List<String> first, List<String> second) {
        List<String> files = new ArrayList<>();
        files.addAll(first);
        files.addAll(second);
        return files.stream()
                .filter(file -> file != null && !file.isBlank())
                .distinct()
                .toList();
    }

    private String joinDiffs(String trackedDiff, String untrackedDiff) {
        String left = trackedDiff == null ? "" : trackedDiff.trim();
        String right = untrackedDiff == null ? "" : untrackedDiff.trim();
        if (left.isBlank()) {
            return right;
        }
        if (right.isBlank()) {
            return left;
        }
        return left + System.lineSeparator() + System.lineSeparator() + right;
    }

    private String renderUntrackedDiffs(Path root, List<String> untrackedFiles) {
        if (untrackedFiles.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        for (String file : untrackedFiles) {
            Path path = root.resolve(file).normalize();
            if (!path.startsWith(root) || !Files.isRegularFile(path)) {
                continue;
            }
            builder.append(renderUntrackedDiff(root, file, path));
        }
        return builder.toString();
    }

    private String renderUntrackedDiff(Path root, String file, Path path) {
        String content;
        boolean byteTruncated = false;
        try {
            byte[] bytes;
            try (InputStream inputStream = Files.newInputStream(path)) {
                bytes = inputStream.readNBytes(MAX_UNTRACKED_FILE_BYTES + 1);
            }
            if (bytes.length > MAX_UNTRACKED_FILE_BYTES) {
                byteTruncated = true;
                bytes = Arrays.copyOf(bytes, MAX_UNTRACKED_FILE_BYTES);
            }
            if (looksBinary(bytes)) {
                content = "[binary file omitted]";
            } else {
                content = new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            content = "[failed to read untracked file: " + e.getMessage() + "]";
        }

        boolean fileTruncated = byteTruncated || content.length() > MAX_UNTRACKED_FILE_CHARS;
        String visibleContent = fileTruncated ? content.substring(0, MAX_UNTRACKED_FILE_CHARS) : content;
        long lineCount = Math.max(1, visibleContent.lines().count());

        StringBuilder builder = new StringBuilder();
        builder.append("diff --git a/").append(file).append(" b/").append(file).append(System.lineSeparator());
        builder.append("new file mode 100644").append(System.lineSeparator());
        builder.append("--- /dev/null").append(System.lineSeparator());
        builder.append("+++ b/").append(file).append(System.lineSeparator());
        builder.append("@@ -0,0 +1,").append(lineCount).append(" @@").append(System.lineSeparator());
        visibleContent.lines().forEach(line -> builder.append('+').append(line).append(System.lineSeparator()));
        if (fileTruncated) {
            builder.append("+... [untracked file truncated by DevContext: ")
                    .append(root.relativize(path))
                    .append("]")
                    .append(System.lineSeparator());
        }
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    private boolean looksBinary(byte[] bytes) {
        int sampleSize = Math.min(bytes.length, 4096);
        for (int i = 0; i < sampleSize; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }

    private String workingTreeReason(boolean hasWorkingTree, int untrackedFileCount) {
        if (!hasWorkingTree) {
            return "没有检测到 staged、unstaged 或未跟踪的新文件。";
        }
        if (untrackedFileCount > 0) {
            return "检测到未提交改动，其中包含 " + untrackedFileCount + " 个未跟踪文件。";
        }
        return "检测到未提交改动。";
    }

    private String currentBranch(Path root) {
        String branch = runGit(root, "rev-parse", "--abbrev-ref", "HEAD").trim();
        return branch.isBlank() ? "HEAD" : branch;
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
            Process process = builder.start();
            CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> readOutput(process.getInputStream()));
            CompletableFuture<String> errorFuture = CompletableFuture.supplyAsync(() -> readOutput(process.getErrorStream()));
            boolean finished = process.waitFor(Duration.ofSeconds(15).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new ApiException("GIT_DIFF_TIMEOUT", "Git diff command timed out", HttpStatus.BAD_REQUEST);
            }
            String output = outputFuture.get(1, TimeUnit.SECONDS);
            String error = errorFuture.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new ApiException("GIT_DIFF_FAILED", compact(error.isBlank() ? output : error), HttpStatus.BAD_REQUEST);
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

    private String readOutput(java.io.InputStream inputStream) {
        try {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
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
