package com.devcontext.adapters.filesystem;

import com.devcontext.domain.context.ContextAsset;
import com.devcontext.domain.context.ContextAssetDefinition;
import com.devcontext.domain.context.ContextAssetWriteReport;
import com.devcontext.domain.context.ContextDocumentStatus;
import com.devcontext.ports.context.ContextAssetStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LocalContextAssetStore implements ContextAssetStore {

    @Override
    public List<ContextAssetWriteReport> writeAssets(
            String rootPath,
            List<ContextAsset> assets,
            boolean overwriteGenerated,
            boolean overwriteManual
    ) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        return assets.stream()
                .map(asset -> writeAsset(root, asset, overwriteGenerated, overwriteManual))
                .toList();
    }

    @Override
    public List<ContextDocumentStatus> inspect(String rootPath, List<ContextAssetDefinition> definitions) {
        Path root = Path.of(rootPath).toAbsolutePath().normalize();
        return definitions.stream()
                .map(definition -> inspect(root, definition))
                .toList();
    }

    private ContextAssetWriteReport writeAsset(
            Path root,
            ContextAsset asset,
            boolean overwriteGenerated,
            boolean overwriteManual
    ) {
        Path target = resolveInsideRoot(root, asset.relativePath());
        boolean exists = Files.exists(target);
        boolean canOverwrite = asset.manual() ? overwriteManual : overwriteGenerated;
        if (exists && !canOverwrite) {
            return new ContextAssetWriteReport(
                    asset.type(),
                    asset.relativePath(),
                    asset.generated(),
                    asset.manual(),
                    true,
                    false,
                    true,
                    asset.manual() ? "manually_edited" : "generated",
                    Instant.now()
            );
        }
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(target, asset.content());
            return new ContextAssetWriteReport(
                    asset.type(),
                    asset.relativePath(),
                    asset.generated(),
                    asset.manual(),
                    true,
                    true,
                    false,
                    asset.manual() ? "manual_template" : "generated",
                    Instant.now()
            );
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write context asset: " + asset.relativePath(), e);
        }
    }

    private ContextDocumentStatus inspect(Path root, ContextAssetDefinition definition) {
        Path target = resolveInsideRoot(root, definition.relativePath());
        boolean exists = Files.exists(target);
        return new ContextDocumentStatus(
                definition.type(),
                definition.relativePath(),
                exists,
                definition.generated(),
                exists ? (definition.manual() ? "manually_edited" : "generated") : "missing",
                null,
                exists ? modifiedAt(target) : null
        );
    }

    private Path resolveInsideRoot(Path root, String relativePath) {
        Path target = root.resolve(relativePath).toAbsolutePath().normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Context asset path escapes project root: " + relativePath);
        }
        return target;
    }

    private Instant modifiedAt(Path target) {
        try {
            return Files.getLastModifiedTime(target).toInstant();
        } catch (IOException e) {
            return null;
        }
    }
}
