/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.engine.knowhere;

import org.opensearch.common.ValidationException;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.engine.AbstractMethodResolver;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.engine.KNNMethodConfigContext;
import org.opensearch.knn.index.engine.KNNMethodContext;
import org.opensearch.knn.index.engine.ResolvedMethodContext;
import org.opensearch.knn.index.mapper.CompressionLevel;
import org.opensearch.knn.index.mapper.Mode;

import java.util.Set;

import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;
import static org.opensearch.knn.index.engine.knowhere.DiskANNMethod.DISKANN_COMPONENT;

/**
 * Method resolution logic for knowhere engine.
 */
public class KnowhereMethodResolver extends AbstractMethodResolver {

    private static final Set<CompressionLevel> SUPPORTED_COMPRESSION_LEVELS = Set.of(CompressionLevel.x1);

    @Override
    public ResolvedMethodContext resolveMethod(
        KNNMethodContext knnMethodContext,
        KNNMethodConfigContext knnMethodConfigContext,
        boolean shouldRequireTraining,
        final SpaceType spaceType
    ) {
        validateConfig(knnMethodConfigContext, shouldRequireTraining);
        KNNMethodContext resolvedKNNMethodContext = initResolvedKNNMethodContext(
            knnMethodContext,
            KNNEngine.KNOWHERE,
            spaceType,
            METHOD_DISKANN
        );
        resolveMethodParams(resolvedKNNMethodContext.getMethodComponentContext(), knnMethodConfigContext, DISKANN_COMPONENT);
        return ResolvedMethodContext.builder().knnMethodContext(resolvedKNNMethodContext).compressionLevel(CompressionLevel.x1).build();
    }

    private void validateConfig(KNNMethodConfigContext knnMethodConfigContext, boolean shouldRequireTraining) {
        ValidationException validationException = validateNotTrainingContext(shouldRequireTraining, KNNEngine.KNOWHERE, null);
        validationException = validateCompressionSupported(
            knnMethodConfigContext.getCompressionLevel(),
            SUPPORTED_COMPRESSION_LEVELS,
            KNNEngine.KNOWHERE,
            validationException
        );

        if (Mode.isConfigured(knnMethodConfigContext.getMode()) && knnMethodConfigContext.getMode() != Mode.ON_DISK) {
            validationException = validationException == null ? new ValidationException() : validationException;
            validationException.addValidationError("Knowhere engine only supports on_disk mode");
        }

        if (validationException != null) {
            throw validationException;
        }
    }
}
