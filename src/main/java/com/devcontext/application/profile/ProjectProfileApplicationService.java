package com.devcontext.application.profile;

import com.devcontext.application.knowledge.KnowledgeCoverageService;
import com.devcontext.application.project.ProjectApplicationService;
import com.devcontext.domain.codemap.CodeEndpoint;
import com.devcontext.domain.codemap.CodeEntrypoint;
import com.devcontext.domain.codemap.CodeMap;
import com.devcontext.domain.codemap.CodeModule;
import com.devcontext.domain.codemap.CodeTechnologySignal;
import com.devcontext.domain.context.ContextDocument;
import com.devcontext.domain.evidence.EvidenceType;
import com.devcontext.domain.knowledge.EvidenceCoverageReport;
import com.devcontext.domain.knowledge.KnowledgeEvidenceType;
import com.devcontext.domain.knowledge.KnowledgeSource;
import com.devcontext.domain.profile.ProjectProfile;
import com.devcontext.domain.profile.ProjectProfileFact;
import com.devcontext.domain.profile.ProjectProfileFreshnessSummary;
import com.devcontext.domain.profile.ProjectProfileResponse;
import com.devcontext.domain.profile.ProjectProfileSourceReference;
import com.devcontext.domain.project.Project;
import com.devcontext.ports.context.ContextDocumentRepository;
import com.devcontext.ports.knowledge.KnowledgeSourceRepository;
import com.devcontext.ports.profile.ProjectProfileRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class ProjectProfileApplicationService {

    private static final String CODE_MAP_PATH = ".ai/code-map.json";

    private final ProjectApplicationService projectService;
    private final ProjectProfileRepository profileRepository;
    private final ContextDocumentRepository contextDocumentRepository;
    private final KnowledgeSourceRepository knowledgeSourceRepository;
    private final KnowledgeCoverageService knowledgeCoverageService;
    private final ObjectMapper objectMapper;

    public ProjectProfileApplicationService(
            ProjectApplicationService projectService,
            ProjectProfileRepository profileRepository,
            ContextDocumentRepository contextDocumentRepository,
            KnowledgeSourceRepository knowledgeSourceRepository,
            KnowledgeCoverageService knowledgeCoverageService,
            ObjectMapper objectMapper
    ) {
        this.projectService = projectService;
        this.profileRepository = profileRepository;
        this.contextDocumentRepository = contextDocumentRepository;
        this.knowledgeSourceRepository = knowledgeSourceRepository;
        this.knowledgeCoverageService = knowledgeCoverageService;
        this.objectMapper = objectMapper;
    }

    public ProjectProfile getProfile(Long projectId) {
        Project project = projectService.getProject(projectId);
        Optional<ProjectProfile> existing = profileRepository.findByProjectId(projectId);
        return buildProfile(project, existing);
    }

    public ProjectProfileResponse getProfileResponse(Long projectId) {
        Project project = projectService.getProject(projectId);
        Optional<ProjectProfile> existing = profileRepository.findByProjectId(projectId);
        ProjectProfile profile = buildProfile(project, existing);
        ProjectProfile freshnessBasis = existing.orElse(profile);
        return ProjectProfileResponse.from(
                profile,
                freshnessSummary(project, freshnessBasis, existing.isEmpty(), profile.warnings())
        );
    }

    private ProjectProfile buildProfile(Project project, Optional<ProjectProfile> existing) {
        Long projectId = project.id();
        Instant now = Instant.now();
        List<ProjectProfileFact> facts = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        addProjectFacts(project, facts);
        Optional<CodeMap> codeMap = readCodeMap(project, warnings);
        codeMap.ifPresent(map -> addCodeMapFacts(map, facts));
        addContextAssetFacts(projectId, facts, warnings);
        addKnowledgeEvidenceCoverage(project, facts, warnings);

        String status = hasDegradedSource(warnings) ? "degraded" : "ready";
        ProjectProfile profile = new ProjectProfile(
                existing.map(ProjectProfile::id).orElse(null),
                projectId,
                status,
                summary(project, status, facts),
                deduplicateFacts(facts),
                warnings.stream().distinct().toList(),
                now,
                existing.map(ProjectProfile::createdAt).orElse(now),
                now
        );
        return profileRepository.upsertByProjectId(profile);
    }

    private ProjectProfileFreshnessSummary freshnessSummary(
            Project project,
            ProjectProfile profile,
            boolean missingBeforeBuild,
            List<String> currentWarnings
    ) {
        Instant lastBuiltAt = profile == null ? null : profile.generatedAt();
        Set<String> sourcePaths = sourcePaths(profile);
        List<String> staleReasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>(safeList(currentWarnings));
        if (missingBeforeBuild) {
            staleReasons.add("no_profile");
        }
        if (sourcePaths.isEmpty()) {
            staleReasons.add("no_source_references");
        }
        if (lastBuiltAt != null) {
            addNewerSourceArtifactReasons(project, sourcePaths, lastBuiltAt, staleReasons);
            addNewerContextDocumentReasons(project.id(), lastBuiltAt, staleReasons);
            addNewerKnowledgeSourceReasons(project, lastBuiltAt, staleReasons);
        }
        staleReasons = staleReasons.stream().distinct().toList();
        warnings = warnings.stream().distinct().toList();
        return new ProjectProfileFreshnessSummary(
                freshnessStatus(profile, missingBeforeBuild, sourcePaths.size(), staleReasons, warnings),
                lastBuiltAt,
                sourcePaths.size(),
                staleReasons,
                warnings
        );
    }

    private String freshnessStatus(
            ProjectProfile profile,
            boolean missingBeforeBuild,
            int sourceCount,
            List<String> staleReasons,
            List<String> warnings
    ) {
        boolean staleSource = staleReasons.stream().anyMatch(reason -> reason.endsWith("_newer"));
        if (staleSource) {
            return "stale_source";
        }
        if (sourceCount == 0) {
            return "no_source_references";
        }
        if (profile != null && !"ready".equalsIgnoreCase(profile.status())) {
            return "degraded";
        }
        if (!warnings.isEmpty() && hasDegradedSource(warnings)) {
            return "degraded";
        }
        if (missingBeforeBuild) {
            return "no_profile";
        }
        return "current";
    }

    private Set<String> sourcePaths(ProjectProfile profile) {
        Set<String> sourcePaths = new LinkedHashSet<>();
        if (profile == null) {
            return sourcePaths;
        }
        for (ProjectProfileFact fact : safeList(profile.facts())) {
            for (ProjectProfileSourceReference reference : safeList(fact.sourceReferences())) {
                if (!isBlank(reference.sourcePath())) {
                    sourcePaths.add(reference.sourcePath().trim());
                }
            }
        }
        return sourcePaths;
    }

    private void addNewerSourceArtifactReasons(
            Project project,
            Set<String> sourcePaths,
            Instant lastBuiltAt,
            List<String> staleReasons
    ) {
        Path root = Path.of(project.rootPath()).toAbsolutePath().normalize();
        for (String sourcePath : sourcePaths) {
            Path resolved = resolveSourcePath(root, sourcePath);
            if (resolved == null || !Files.isRegularFile(resolved)) {
                continue;
            }
            try {
                if (Files.getLastModifiedTime(resolved).toInstant().isAfter(lastBuiltAt)) {
                    staleReasons.add("source_artifact_newer");
                }
            } catch (IOException ignored) {
                // Freshness is advisory; unreadable source mtimes should not block profile reads.
            }
        }
    }

    private void addNewerContextDocumentReasons(Long projectId, Instant lastBuiltAt, List<String> staleReasons) {
        for (ContextDocument document : contextDocumentRepository.findByProjectId(projectId)) {
            if (document.updatedAt() != null && document.updatedAt().isAfter(lastBuiltAt)) {
                staleReasons.add("context_document_newer");
                return;
            }
        }
    }

    private void addNewerKnowledgeSourceReasons(Project project, Instant lastBuiltAt, List<String> staleReasons) {
        boolean newer = knowledgeSourceRepository.findAll().stream()
                .filter(source -> samePath(project.rootPath(), source.rootPath()))
                .anyMatch(source -> source.updatedAt() != null && source.updatedAt().isAfter(lastBuiltAt));
        if (newer) {
            staleReasons.add("knowledge_source_newer");
        }
    }

    private Path resolveSourcePath(Path root, String sourcePath) {
        try {
            Path raw = Path.of(sourcePath);
            Path resolved = raw.isAbsolute() ? raw : root.resolve(raw);
            resolved = resolved.toAbsolutePath().normalize();
            if (!resolved.startsWith(root)) {
                return null;
            }
            return resolved;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private void addProjectFacts(Project project, List<ProjectProfileFact> facts) {
        ProjectProfileSourceReference source = source(project.rootPath(), EvidenceType.CONFIG);
        addFact(facts, "tech_stack", "registered_language", project.language(), source);
        addFact(facts, "tech_stack", "registered_framework", project.framework(), source);
        addFact(facts, "project", "default_branch", project.defaultBranch(), source);
    }

    private Optional<CodeMap> readCodeMap(Project project, List<String> warnings) {
        Path root = Path.of(project.rootPath()).toAbsolutePath().normalize();
        Path codeMapPath = root.resolve(CODE_MAP_PATH).toAbsolutePath().normalize();
        if (!codeMapPath.startsWith(root) || !Files.isRegularFile(codeMapPath)) {
            warnings.add("Missing .ai/code-map.json; profile uses project metadata and available records only.");
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(Files.readString(codeMapPath), CodeMap.class));
        } catch (IOException e) {
            warnings.add(".ai/code-map.json could not be parsed; profile uses project metadata and available records only.");
            return Optional.empty();
        }
    }

    private void addCodeMapFacts(CodeMap codeMap, List<ProjectProfileFact> facts) {
        ProjectProfileSourceReference codeMapSource = source(CODE_MAP_PATH, EvidenceType.CODE_MAP);
        addFact(facts, "tech_stack", "language", codeMap.language(), codeMapSource);
        addFact(facts, "tech_stack", "framework", codeMap.framework(), codeMapSource);
        addFact(facts, "tech_stack", "build_tool", codeMap.buildTool(), codeMapSource);

        safeList(codeMap.technologies()).stream()
                .limit(30)
                .forEach(technology -> addTechnologyFact(facts, technology));
        safeList(codeMap.modules()).stream()
                .limit(40)
                .forEach(module -> addModuleFact(facts, module));
        safeList(codeMap.entrypoints()).stream()
                .limit(30)
                .forEach(entrypoint -> addEntrypointFact(facts, entrypoint));
        safeList(codeMap.endpoints()).stream()
                .limit(60)
                .forEach(endpoint -> addEndpointFact(facts, endpoint));
    }

    private void addTechnologyFact(List<ProjectProfileFact> facts, CodeTechnologySignal technology) {
        String sourcePath = safeList(technology.files()).stream().findFirst().orElse(CODE_MAP_PATH);
        String value = technology.technology()
                + sourcesSuffix("classes", safeList(technology.classes()), 8);
        addFact(facts, "tech_stack", technology.technology(), value, source(sourcePath, EvidenceType.SERVICE_CODE));
    }

    private void addModuleFact(List<ProjectProfileFact> facts, CodeModule module) {
        String value = module.path()
                + (isBlank(module.responsibility()) ? "" : " - " + module.responsibility())
                + sourcesSuffix("classes", safeList(module.classes()), 8);
        addFact(facts, "module", module.name(), value, source(CODE_MAP_PATH, EvidenceType.CODE_MAP));
    }

    private void addEntrypointFact(List<ProjectProfileFact> facts, CodeEntrypoint entrypoint) {
        String value = entrypoint.type()
                + " " + entrypoint.file()
                + sourcesSuffix("methods", safeList(entrypoint.methods()), 8);
        addFact(facts, "entrypoint", entrypoint.file(), value, source(entrypoint.file(), EvidenceType.API_CONTROLLER));
    }

    private void addEndpointFact(List<ProjectProfileFact> facts, CodeEndpoint endpoint) {
        String name = endpoint.httpMethod() + " " + endpoint.path();
        String value = endpoint.className()
                + (isBlank(endpoint.handlerMethod()) ? "" : "#" + endpoint.handlerMethod())
                + (isBlank(endpoint.module()) ? "" : " module=" + endpoint.module());
        addFact(facts, "endpoint", name, value, source(endpoint.file(), EvidenceType.API_CONTROLLER));
    }

    private void addContextAssetFacts(Long projectId, List<ProjectProfileFact> facts, List<String> warnings) {
        List<ContextDocument> documents = contextDocumentRepository.findByProjectId(projectId);
        if (documents.isEmpty()) {
            warnings.add("No context document records found; context asset facts are degraded.");
            return;
        }
        for (ContextDocument document : documents) {
            EvidenceType evidenceType = contextEvidenceType(document);
            String value = document.filePath()
                    + " status=" + document.status()
                    + " generated=" + document.generated();
            addFact(facts, "context_asset", document.type(), value, source(document.filePath(), evidenceType));
            if (!"written".equalsIgnoreCase(document.status()) && !"skipped".equalsIgnoreCase(document.status())) {
                warnings.add("Context asset " + document.filePath() + " is " + document.status() + ".");
            }
        }
    }

    private void addKnowledgeEvidenceCoverage(Project project, List<ProjectProfileFact> facts, List<String> warnings) {
        List<KnowledgeSource> matchingSources = knowledgeSourceRepository.findAll().stream()
                .filter(source -> samePath(project.rootPath(), source.rootPath()))
                .sorted(Comparator.comparing(KnowledgeSource::updatedAt).reversed())
                .toList();
        if (matchingSources.isEmpty()) {
            warnings.add("No indexed knowledge source found for the project root; evidence coverage facts are unavailable.");
            return;
        }
        boolean addedCoverage = false;
        for (KnowledgeSource source : matchingSources) {
            EvidenceCoverageReport report = knowledgeCoverageService.buildReport(source.id());
            if (report.chunksIndexed() == 0) {
                continue;
            }
            addedCoverage = true;
            for (Map.Entry<KnowledgeEvidenceType, Integer> entry : report.coverage().entrySet()) {
                KnowledgeEvidenceType evidenceType = entry.getKey();
                addFact(
                        facts,
                        "evidence_coverage",
                        evidenceType.canonicalName(),
                        "chunks=" + entry.getValue() + "; sourceId=" + source.id(),
                        source(source.rootPath(), evidenceType.taxonomyType())
                );
            }
            warnings.addAll(report.warnings());
        }
        if (!addedCoverage) {
            warnings.add("Indexed knowledge sources exist but have no chunks; evidence coverage facts are unavailable.");
        }
    }

    private EvidenceType contextEvidenceType(ContextDocument document) {
        if (CODE_MAP_PATH.equals(document.filePath())) {
            return EvidenceType.CODE_MAP;
        }
        return document.generated() ? EvidenceType.GENERATED_DOC : EvidenceType.MANUAL_DOC;
    }

    private ProjectProfileSourceReference source(String sourcePath, EvidenceType evidenceType) {
        return new ProjectProfileSourceReference(
                sourcePath,
                evidenceType.canonicalName(),
                evidenceType.sourceKind().value(),
                evidenceType.sourceReliability().value()
        );
    }

    private void addFact(
            List<ProjectProfileFact> facts,
            String factType,
            String name,
            String value,
            ProjectProfileSourceReference sourceReference
    ) {
        if (isBlank(value) || "unknown".equalsIgnoreCase(value)) {
            return;
        }
        facts.add(new ProjectProfileFact(
                factType,
                name,
                value,
                List.of(sourceReference)
        ));
    }

    private List<ProjectProfileFact> deduplicateFacts(List<ProjectProfileFact> facts) {
        Map<String, ProjectProfileFact> deduped = new LinkedHashMap<>();
        for (ProjectProfileFact fact : facts) {
            String key = fact.factType() + "\n" + fact.name() + "\n" + fact.value();
            deduped.putIfAbsent(key, fact);
        }
        return deduped.values().stream().toList();
    }

    private String summary(Project project, String status, List<ProjectProfileFact> facts) {
        Set<String> factTypes = facts.stream()
                .map(ProjectProfileFact::factType)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return "ProjectProfile MVP for " + project.name()
                + " is " + status
                + " with " + facts.size()
                + " facts across " + factTypes.size()
                + " fact types.";
    }

    private boolean hasDegradedSource(List<String> warnings) {
        return warnings.stream().anyMatch(warning ->
                warning.startsWith("Missing")
                        || warning.startsWith("No context")
                        || warning.startsWith("No indexed")
                        || warning.startsWith("Indexed knowledge")
                        || warning.contains("could not be parsed"));
    }

    private boolean samePath(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return Path.of(left).toAbsolutePath().normalize()
                .equals(Path.of(right).toAbsolutePath().normalize());
    }

    private String sourcesSuffix(String label, List<String> values, int limit) {
        if (values.isEmpty()) {
            return "";
        }
        return "; " + label + "=" + values.stream().limit(limit).collect(Collectors.joining(", "));
    }

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
