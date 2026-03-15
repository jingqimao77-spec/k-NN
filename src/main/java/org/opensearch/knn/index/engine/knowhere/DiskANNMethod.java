/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.engine.knowhere;

import com.google.common.collect.ImmutableSet;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.VectorDataType;
import org.opensearch.knn.index.engine.AbstractKNNMethod;
import org.opensearch.knn.index.engine.MethodComponent;
import org.opensearch.knn.index.engine.Parameter;

import java.util.List;
import java.util.Set;

import static org.opensearch.knn.common.KNNConstants.DISKANN_ACCELERATE_BUILD;
import static org.opensearch.knn.common.KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB;
import static org.opensearch.knn.common.KNNConstants.DISKANN_DISK_PQ_DIMS;
import static org.opensearch.knn.common.KNNConstants.DISKANN_MAX_DEGREE;
import static org.opensearch.knn.common.KNNConstants.DISKANN_NUM_BUILD_THREAD;
import static org.opensearch.knn.common.KNNConstants.DISKANN_PQ_CODE_BUDGET_GB_RATIO;
import static org.opensearch.knn.common.KNNConstants.DISKANN_SEARCH_CACHE_BUDGET_GB_RATIO;
import static org.opensearch.knn.common.KNNConstants.DISKANN_SEARCH_LIST_SIZE;
import static org.opensearch.knn.common.KNNConstants.DISKANN_USE_BFS_CACHE;
import static org.opensearch.knn.common.KNNConstants.DISKANN_WARM_UP;
import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;

/**
 * Knowhere DiskANN method implementation.
 */
public class DiskANNMethod extends AbstractKNNMethod {

    private static final Set<VectorDataType> SUPPORTED_DATA_TYPES = ImmutableSet.of(VectorDataType.FLOAT);

    public static final List<SpaceType> SUPPORTED_SPACES = List.of(
        SpaceType.UNDEFINED,
        SpaceType.L2,
        SpaceType.INNER_PRODUCT,
        SpaceType.COSINESIMIL
    );

    static final MethodComponent DISKANN_COMPONENT = initMethodComponent();

    public DiskANNMethod() {
        super(DISKANN_COMPONENT, Set.copyOf(SUPPORTED_SPACES), new DiskANNSearchContext());
    }

    private static MethodComponent initMethodComponent() {
        int maxBuildThreads = Math.max(1, Runtime.getRuntime().availableProcessors());
        return MethodComponent.Builder.builder(METHOD_DISKANN)
            .addSupportedDataTypes(SUPPORTED_DATA_TYPES)
            .addParameter(
                DISKANN_MAX_DEGREE,
                new Parameter.IntegerParameter(DISKANN_MAX_DEGREE, 48, (v, context) -> v > 0 && v <= 2048)
            )
            .addParameter(
                DISKANN_SEARCH_LIST_SIZE,
                new Parameter.IntegerParameter(DISKANN_SEARCH_LIST_SIZE, 128, (v, context) -> v > 0)
            )
            .addParameter(
                DISKANN_PQ_CODE_BUDGET_GB_RATIO,
                new Parameter.DoubleParameter(DISKANN_PQ_CODE_BUDGET_GB_RATIO, 0.0, (v, context) -> v >= 0)
            )
            .addParameter(
                DISKANN_BUILD_DRAM_BUDGET_GB,
                new Parameter.DoubleParameter(DISKANN_BUILD_DRAM_BUDGET_GB, 0.0, (v, context) -> v >= 0)
            )
            .addParameter(
                DISKANN_DISK_PQ_DIMS,
                new Parameter.IntegerParameter(DISKANN_DISK_PQ_DIMS, 0, (v, context) -> v >= 0)
            )
            .addParameter(
                DISKANN_ACCELERATE_BUILD,
                new Parameter.BooleanParameter(DISKANN_ACCELERATE_BUILD, false, (v, context) -> true)
            )
            .addParameter(
                DISKANN_NUM_BUILD_THREAD,
                new Parameter.IntegerParameter(DISKANN_NUM_BUILD_THREAD, null, (v, context) -> v >= 1 && v <= maxBuildThreads)
            )
            .addParameter(
                DISKANN_SEARCH_CACHE_BUDGET_GB_RATIO,
                new Parameter.DoubleParameter(DISKANN_SEARCH_CACHE_BUDGET_GB_RATIO, 0.0, (v, context) -> v >= 0)
            )
            .addParameter(
                DISKANN_WARM_UP,
                new Parameter.BooleanParameter(DISKANN_WARM_UP, false, (v, context) -> true)
            )
            .addParameter(
                DISKANN_USE_BFS_CACHE,
                new Parameter.BooleanParameter(DISKANN_USE_BFS_CACHE, false, (v, context) -> true)
            )
            .build();
    }
}
