/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.engine.knowhere;

import com.google.common.collect.ImmutableMap;
import org.opensearch.knn.index.engine.KNNLibrarySearchContext;
import org.opensearch.knn.index.engine.Parameter;
import org.opensearch.knn.index.engine.model.QueryContext;
import org.opensearch.knn.index.query.request.MethodParameter;

import java.util.Map;

/**
 * DiskANN search context for knowhere engine.
 */
public final class DiskANNSearchContext implements KNNLibrarySearchContext {

    private final Map<String, Parameter<?>> supportedMethodParameters = ImmutableMap.<String, Parameter<?>>builder()
        .put(
            MethodParameter.SEARCH_LIST.getName(),
            new Parameter.IntegerParameter(MethodParameter.SEARCH_LIST.getName(), null, (value, context) -> value > 0)
        )
        .put(
            MethodParameter.BEAMWIDTH.getName(),
            new Parameter.IntegerParameter(MethodParameter.BEAMWIDTH.getName(), null, (value, context) -> value > 0)
        )
        .build();

    @Override
    public Map<String, Parameter<?>> supportedMethodParameters(QueryContext ctx) {
        return supportedMethodParameters;
    }
}
