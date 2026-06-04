package com.devcontext.ports.project;

import com.devcontext.domain.project.Project;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository {

    Project save(Project project);

    Project update(Project project);

    Optional<Project> findById(Long id);

    List<Project> findAll();

    void deleteById(Long id);
}
