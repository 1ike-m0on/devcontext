package com.devcontext.ports.context;

import com.devcontext.domain.context.ContextAsset;
import com.devcontext.domain.context.ContextAssetDefinition;
import com.devcontext.domain.context.ContextAssetWriteReport;
import com.devcontext.domain.context.ContextDocumentStatus;
import java.util.List;

public interface ContextAssetStore {

    List<ContextAssetWriteReport> writeAssets(
            String rootPath,
            List<ContextAsset> assets,
            boolean overwriteGenerated,
            boolean overwriteManual
    );

    List<ContextDocumentStatus> inspect(String rootPath, List<ContextAssetDefinition> definitions);
}
