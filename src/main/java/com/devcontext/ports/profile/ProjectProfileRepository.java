package com.devcontext.ports.profile;

import com.devcontext.domain.profile.ProjectProfile;
import java.util.Optional;

public interface ProjectProfileRepository {

    ProjectProfile upsertByProjectId(ProjectProfile profile);

    Optional<ProjectProfile> findByProjectId(Long projectId);
}
