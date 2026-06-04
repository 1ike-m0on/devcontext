package com.devcontext.ports.git;

import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.git.GitReviewSource;
import java.util.List;

public interface GitDiffProvider {

    default GitDiff diff(String rootPath, String baseBranch, String compareBranch) {
        return diff(rootPath, baseBranch, compareBranch, List.of());
    }

    GitDiff diff(String rootPath, String baseBranch, String compareBranch, List<String> selectedFiles);

    default GitDiff workingTreeDiff(String rootPath) {
        return workingTreeDiff(rootPath, List.of());
    }

    GitDiff workingTreeDiff(String rootPath, List<String> selectedFiles);

    default GitDiff currentBranchDiff(String rootPath, String defaultBranch) {
        return currentBranchDiff(rootPath, defaultBranch, List.of());
    }

    GitDiff currentBranchDiff(String rootPath, String defaultBranch, List<String> selectedFiles);

    default GitDiff lastCommitDiff(String rootPath) {
        return lastCommitDiff(rootPath, List.of());
    }

    GitDiff lastCommitDiff(String rootPath, List<String> selectedFiles);

    List<GitReviewSource> inspectSources(String rootPath, String defaultBranch);
}
