package com.devcontext;

import static org.assertj.core.api.Assertions.assertThat;

import com.devcontext.application.evidence.SourceEvidenceLoopProbe;
import com.devcontext.application.knowledge.SourceEvidenceLoopKnowledgeAdapter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceEvidenceLoopProbeTests {

    @TempDir
    private Path projectRoot;

    @Test
    void adapterSelectsCompactPrimarySourceEvidenceWithoutGeneratedDocsNoise() throws Exception {
        writeProjectFile("src/main/java/com/acme/context/SourceEvidenceLoopProbe.java", """
                package com.acme.context;

                import org.springframework.stereotype.Service;

                @Service
                public class SourceEvidenceLoopProbe {
                    public EvidencePack selectEvidencePack(SourceEvidenceRequest request) {
                        return new EvidencePack(request.question(), request.intent());
                    }
                }
                """);
        writeProjectFile("src/main/java/com/acme/context/SourceEvidenceLoopKnowledgeAdapter.java", """
                package com.acme.context;

                import org.springframework.stereotype.Service;

                @Service
                public class SourceEvidenceLoopKnowledgeAdapter {
                    private final SourceEvidenceLoopProbe probe;

                    public SourceEvidenceLoopKnowledgeAdapter(SourceEvidenceLoopProbe probe) {
                        this.probe = probe;
                    }

                    public EvidencePack selectEvidencePack(SourceEvidenceRequest request) {
                        return probe.selectEvidencePack(request);
                    }
                }
                """);
        writeProjectFile("src/test/java/com/acme/context/SourceEvidenceLoopProbeTests.java", """
                package com.acme.context;

                import org.junit.jupiter.api.Test;

                class SourceEvidenceLoopProbeTests {
                    @Test
                    void adapterSelectsSourceEvidenceLoopPrimaryPaths() {
                        SourceEvidenceLoopKnowledgeAdapter adapter =
                                new SourceEvidenceLoopKnowledgeAdapter(new SourceEvidenceLoopProbe());
                        adapter.selectEvidencePack(new SourceEvidenceRequest("question", "implementation_detail"));
                    }
                }
                """);
        writeProjectFile("docs/source-evidence-loop.md", "SourceEvidenceLoopKnowledgeAdapter implementation guide");
        writeProjectFile(".ai/generated/source-evidence-loop.md", "Generated SourceEvidenceLoopProbe summary");
        writeProjectFile("README.md", "SourceEvidenceLoopProbe and SourceEvidenceLoopKnowledgeAdapter notes");

        SourceEvidenceLoopKnowledgeAdapter adapter =
                new SourceEvidenceLoopKnowledgeAdapter(new SourceEvidenceLoopProbe());

        List<String> paths = adapter.selectEvidencePack(
                        projectRoot,
                        "SourceEvidenceLoopProbe 和 SourceEvidenceLoopKnowledgeAdapter 是怎么选择 evidence pack 的？核心源码和 contract test 在哪里？",
                        "implementation_detail")
                .primarySourcePaths();

        assertThat(paths)
                .contains(
                        "src/main/java/com/acme/context/SourceEvidenceLoopProbe.java",
                        "src/main/java/com/acme/context/SourceEvidenceLoopKnowledgeAdapter.java",
                        "src/test/java/com/acme/context/SourceEvidenceLoopProbeTests.java"
                );
        assertThat(paths)
                .noneMatch(path -> path.startsWith("docs/"))
                .noneMatch(path -> path.startsWith(".ai/"))
                .noneMatch(path -> path.equalsIgnoreCase("README.md"));
    }

    private void writeProjectFile(String relativePath, String content) throws Exception {
        Path file = projectRoot.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
