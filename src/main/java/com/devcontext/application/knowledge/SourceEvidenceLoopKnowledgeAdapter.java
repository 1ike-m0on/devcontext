package com.devcontext.application.knowledge;

import com.devcontext.application.evidence.SourceEvidenceLoopProbe;
import com.devcontext.application.evidence.SourceEvidenceLoopProbe.EvidenceFragment;
import com.devcontext.application.evidence.SourceEvidenceLoopProbe.ProbeRequest;
import com.devcontext.application.evidence.SourceEvidenceLoopProbe.ProbeResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class SourceEvidenceLoopKnowledgeAdapter {

    private static final int DEFAULT_MAX_ITERATIONS = 1;
    private static final int DEFAULT_MAX_EVIDENCE_TOKENS = 20_000;
    private static final int MAX_MODEL_READ_LINES = 160;
    private static final int MAX_EXPLICIT_FOCUS_READ_LINES = 220;

    private final SourceEvidenceLoopProbe probe;

    public SourceEvidenceLoopKnowledgeAdapter(SourceEvidenceLoopProbe probe) {
        this.probe = probe;
    }

    public SourceEvidencePack selectEvidencePack(Path projectRoot, String question, String intent) {
        ProbeResult result = probe.run(new ProbeRequest(
                projectRoot,
                question,
                DEFAULT_MAX_ITERATIONS,
                DEFAULT_MAX_EVIDENCE_TOKENS,
                intent
        ));
        List<EvidenceFragment> primaryEvidence = alignDatabaseModelEvidence(
                projectRoot,
                result.intent(),
                result.evidencePack()
        );
        primaryEvidence = alignExplicitFocusEvidence(projectRoot, question, primaryEvidence);
        List<String> missingGroups = result.sufficiency().missingGroups();
        if ("database_detail".equals(result.intent())
                && primaryEvidence.stream().anyMatch(fragment -> "entity_or_model".equals(fragment.evidenceGroup()))) {
            missingGroups = missingGroups.stream()
                    .filter(group -> !"entity_or_model".equals(group))
                    .toList();
        }
        return new SourceEvidencePack(
                result.intent(),
                primaryEvidence,
                missingGroups,
                result.blockedLegacySources()
        );
    }

    private List<EvidenceFragment> alignDatabaseModelEvidence(
            Path projectRoot,
            String intent,
            List<EvidenceFragment> primaryEvidence
    ) {
        if (!"database_detail".equals(intent) || primaryEvidence == null || primaryEvidence.isEmpty()) {
            return primaryEvidence == null ? List.of() : primaryEvidence;
        }
        LinkedHashSet<String> modelNames = new LinkedHashSet<>();
        for (EvidenceFragment fragment : primaryEvidence) {
            if ("repository_sql".equals(fragment.evidenceGroup())) {
                modelNames.addAll(repositoryModelNames(fragment.content()));
            }
        }
        if (modelNames.isEmpty()) {
            return primaryEvidence;
        }

        List<EvidenceFragment> modelFragments = new ArrayList<>();
        for (String modelName : modelNames) {
            findModelPath(projectRoot, modelName)
                    .map(path -> readModelFragment(projectRoot, path, modelName))
                    .ifPresent(modelFragments::add);
        }
        if (modelFragments.isEmpty()) {
            return primaryEvidence;
        }

        Set<String> modelPaths = modelFragments.stream()
                .map(fragment -> normalizePath(fragment.path()))
                .collect(LinkedHashSet::new, Set::add, Set::addAll);
        boolean alreadySelected = primaryEvidence.stream()
                .filter(fragment -> "entity_or_model".equals(fragment.evidenceGroup()))
                .map(fragment -> normalizePath(fragment.path()))
                .collect(LinkedHashSet::new, Set::add, Set::addAll)
                .containsAll(modelPaths);
        if (alreadySelected) {
            return primaryEvidence;
        }

        List<EvidenceFragment> repaired = new ArrayList<>();
        for (EvidenceFragment fragment : primaryEvidence) {
            if (!"entity_or_model".equals(fragment.evidenceGroup())) {
                repaired.add(fragment);
            }
        }
        repaired.addAll(modelFragments);
        return List.copyOf(repaired);
    }

    private List<EvidenceFragment> alignExplicitFocusEvidence(
            Path projectRoot,
            String question,
            List<EvidenceFragment> primaryEvidence
    ) {
        List<EvidenceFragment> explicitFragments = explicitFocusFragments(projectRoot, question);
        if (explicitFragments.isEmpty()) {
            return primaryEvidence == null ? List.of() : primaryEvidence;
        }
        LinkedHashMap<String, EvidenceFragment> selected = new LinkedHashMap<>();
        boolean testFocus = explicitFragments.stream()
                .anyMatch(fragment -> "test_or_contract".equals(fragment.evidenceGroup()));
        if (testFocus) {
            for (EvidenceFragment fragment : explicitFragments) {
                selected.put(fragmentKey(fragment), fragment);
            }
        }
        for (EvidenceFragment fragment : primaryEvidence == null ? List.<EvidenceFragment>of() : primaryEvidence) {
            selected.putIfAbsent(fragmentKey(fragment), fragment);
        }
        if (!testFocus) {
            for (EvidenceFragment fragment : explicitFragments) {
                if ("test_or_contract".equals(fragment.evidenceGroup())) {
                    selected.entrySet().removeIf(entry ->
                            entry.getKey().startsWith("test_or_contract|")
                                    && !normalizePath(entry.getValue().path()).equals(normalizePath(fragment.path())));
                }
                selected.put(fragmentKey(fragment), fragment);
            }
        }
        return List.copyOf(selected.values());
    }

    private List<EvidenceFragment> explicitFocusFragments(Path projectRoot, String question) {
        if (projectRoot == null || !Files.isDirectory(projectRoot) || question == null || question.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> classNames = explicitClassNames(question);
        if (classNames.isEmpty()) {
            return List.of();
        }
        boolean testFocus = question.toLowerCase().contains("test")
                || question.toLowerCase().contains("contract")
                || question.contains("\u6d4b\u8bd5");
        LinkedHashSet<String> wantedNames = new LinkedHashSet<>(classNames);
        if (testFocus) {
            for (String className : classNames) {
                if (!className.endsWith("Test") && !className.endsWith("Tests")) {
                    wantedNames.add(className + "Test");
                    wantedNames.add(className + "Tests");
                }
            }
        }

        List<EvidenceFragment> fragments = new ArrayList<>();
        for (String wantedName : wantedNames) {
            findJavaPathByPrimaryType(projectRoot, wantedName)
                    .map(path -> readExplicitFocusFragment(projectRoot, path, wantedName))
                    .ifPresent(fragments::add);
        }
        return fragments;
    }

    private LinkedHashSet<String> explicitClassNames(String question) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\b[A-Z][A-Za-z0-9_]{3,}\\b").matcher(question);
        while (matcher.find()) {
            String name = matcher.group();
            if (!Set.of("SQL", "API", "LLM", "RAG").contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    private Optional<Path> findJavaPathByPrimaryType(Path projectRoot, String primaryTypeName) {
        if (projectRoot == null || primaryTypeName == null || primaryTypeName.isBlank() || !Files.isDirectory(projectRoot)) {
            return Optional.empty();
        }
        try (var paths = Files.walk(projectRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> normalizePath(projectRoot.relativize(path).toString()).endsWith(".java"))
                    .filter(path -> primaryTypeName.equals(primaryJavaTypeName(readFile(path))))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private EvidenceFragment readExplicitFocusFragment(Path projectRoot, Path path, String primaryTypeName) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            lines = List.of("public class " + primaryTypeName + " {}");
        }
        int end = Math.min(lines.size(), MAX_EXPLICIT_FOCUS_READ_LINES);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < end; i++) {
            content.append(i + 1).append(": ").append(lines.get(i)).append("\n");
        }
        String relativePath = normalizePath(projectRoot.relativize(path).toString());
        return new EvidenceFragment(
                relativePath,
                1,
                Math.max(1, end),
                evidenceGroupForExplicitFocus(relativePath),
                primaryTypeName,
                content.toString(),
                "explicit_query_focus:" + primaryTypeName
        );
    }

    private String evidenceGroupForExplicitFocus(String relativePath) {
        String path = normalizePath(relativePath);
        if (path.startsWith("src/test/") || path.contains("/src/test/")) {
            return "test_or_contract";
        }
        if (path.contains("/domain/") || path.contains("/model/")) {
            return "key_model_result";
        }
        return "application_service";
    }

    private String fragmentKey(EvidenceFragment fragment) {
        return normalizePath(fragment.evidenceGroup()) + "|" + normalizePath(fragment.path());
    }

    private List<String> repositoryModelNames(String repositoryContent) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        addPatternMatches(names, repositoryContent, "\\bRowMapper\\s*<\\s*([A-Z][A-Za-z0-9_]*)\\s*>");
        addPatternMatches(names, repositoryContent, "\\bnew\\s+([A-Z][A-Za-z0-9_]*)\\s*\\(");
        return names.stream()
                .filter(name -> !Set.of("String", "Long", "Integer", "Instant", "PreparedStatement").contains(name))
                .toList();
    }

    private void addPatternMatches(Set<String> names, String content, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(content == null ? "" : content);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
    }

    private Optional<Path> findModelPath(Path projectRoot, String modelName) {
        if (projectRoot == null || modelName == null || modelName.isBlank() || !Files.isDirectory(projectRoot)) {
            return Optional.empty();
        }
        try (var paths = Files.walk(projectRoot)) {
            return paths
                    .filter(Files::isRegularFile)
                    .filter(path -> normalizePath(projectRoot.relativize(path).toString()).endsWith(".java"))
                    .filter(path -> {
                        String relativePath = normalizePath(projectRoot.relativize(path).toString());
                        return !relativePath.contains("repository")
                                && (relativePath.contains("/domain/") || relativePath.contains("/model/"));
                    })
                    .filter(path -> modelName.equals(primaryJavaTypeName(readFile(path))))
                    .findFirst();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private EvidenceFragment readModelFragment(Path projectRoot, Path path, String modelName) {
        List<String> lines;
        try {
            lines = Files.readAllLines(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            lines = List.of("public record " + modelName + "() {}");
        }
        int end = Math.min(lines.size(), MAX_MODEL_READ_LINES);
        StringBuilder content = new StringBuilder();
        for (int i = 0; i < end; i++) {
            content.append(i + 1).append(": ").append(lines.get(i)).append("\n");
        }
        return new EvidenceFragment(
                normalizePath(projectRoot.relativize(path).toString()),
                1,
                Math.max(1, end),
                "entity_or_model",
                modelName,
                content.toString(),
                "repository_model_reference:" + modelName
        );
    }

    private String primaryJavaTypeName(String content) {
        Matcher matcher = Pattern.compile("\\b(?:record|class|interface|enum)\\s+([A-Z][A-Za-z0-9_]*)")
                .matcher(content == null ? "" : content);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private String normalizePath(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replace('\\', '/');
    }

    public record SourceEvidencePack(
            String intent,
            List<EvidenceFragment> primaryEvidence,
            List<String> missingEvidenceGroups,
            List<String> blockedLegacySources
    ) {
        public SourceEvidencePack {
            intent = intent == null ? "" : intent;
            primaryEvidence = primaryEvidence == null ? List.of() : List.copyOf(primaryEvidence);
            missingEvidenceGroups = missingEvidenceGroups == null ? List.of() : List.copyOf(missingEvidenceGroups);
            blockedLegacySources = blockedLegacySources == null ? List.of() : List.copyOf(blockedLegacySources);
        }

        public List<String> primarySourcePaths() {
            return primaryEvidence.stream()
                    .map(EvidenceFragment::path)
                    .distinct()
                    .toList();
        }
    }
}
