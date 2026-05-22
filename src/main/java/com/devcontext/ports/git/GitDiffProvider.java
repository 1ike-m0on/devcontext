package com.devcontext.ports.git;

import com.devcontext.domain.git.GitDiff;

public interface GitDiffProvider {

    GitDiff diff(String rootPath, String baseBranch, String compareBranch);
}
