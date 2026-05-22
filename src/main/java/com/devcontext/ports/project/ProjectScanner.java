package com.devcontext.ports.project;

import com.devcontext.domain.project.ProjectScan;

public interface ProjectScanner {

    ProjectScan scan(String rootPath);
}
