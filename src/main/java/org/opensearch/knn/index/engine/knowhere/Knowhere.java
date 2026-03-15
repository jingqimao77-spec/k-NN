/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.engine.knowhere;

import com.google.common.collect.ImmutableMap;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.engine.KNNLibraryIndexingContext;
import org.opensearch.knn.index.engine.KNNMethod;
import org.opensearch.knn.index.engine.KNNMethodConfigContext;
import org.opensearch.knn.index.engine.KNNMethodContext;
import org.opensearch.knn.index.engine.MethodResolver;
import org.opensearch.knn.index.engine.NativeLibrary;
import org.opensearch.knn.index.engine.ResolvedMethodContext;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;

/**
 * Implements NativeLibrary for the knowhere native library.
 */
public class Knowhere extends NativeLibrary {

    private final static String CURRENT_VERSION = "10";

    final static Map<String, KNNMethod> METHODS = ImmutableMap.of(METHOD_DISKANN, new DiskANNMethod());

    public final static Knowhere INSTANCE = new Knowhere(
        METHODS,
        Collections.emptyMap(),
        CURRENT_VERSION,
        KNNConstants.KNOWHERE_EXTENSION
    );

    private final MethodResolver methodResolver;

    private Knowhere(
        Map<String, KNNMethod> methods,
        Map<SpaceType, Function<Float, Float>> scoreTranslation,
        String currentVersion,
        String extension
    ) {
        super(methods, scoreTranslation, currentVersion, extension);
        this.methodResolver = new KnowhereMethodResolver();
    }

    @Override
    public ResolvedMethodContext resolveMethod(
        KNNMethodContext knnMethodContext,
        KNNMethodConfigContext knnMethodConfigContext,
        boolean shouldRequireTraining,
        final SpaceType spaceType
    ) {
        return methodResolver.resolveMethod(knnMethodContext, knnMethodConfigContext, shouldRequireTraining, spaceType);
    }

    @Override
    public Float distanceToRadialThreshold(Float distance, SpaceType spaceType) {
        return distance;
    }

    @Override
    public Float scoreToRadialThreshold(Float score, SpaceType spaceType) {
        return score;
    }

    @Override
    public boolean supportsRemoteIndexBuild(KNNLibraryIndexingContext knnLibraryIndexingContext) {
        return false;
    }
}
