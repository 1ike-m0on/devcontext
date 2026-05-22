package com.devcontext.application.review.context;

import com.devcontext.domain.git.GitDiff;
import com.devcontext.domain.project.Project;

public record ReviewContextRequest(
        Project project,
        GitDiff diff
) {
}
