/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.knowhere;

import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.hamcrest.Matchers;
import org.opensearch.client.ResponseException;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.knn.KNNRestTestCase;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.VectorDataType;
import org.opensearch.knn.index.engine.KNNEngine;

import java.io.IOException;
import java.util.Map;

import static org.opensearch.knn.common.KNNConstants.COMPRESSION_LEVEL_PARAMETER;
import static org.opensearch.knn.common.KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB;
import static org.opensearch.knn.common.KNNConstants.DISKANN_MAX_DEGREE;
import static org.opensearch.knn.common.KNNConstants.DISKANN_SEARCH_LIST_SIZE;
import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;
import static org.opensearch.knn.common.KNNConstants.MODE_PARAMETER;
import static org.opensearch.knn.common.KNNConstants.VECTOR_DATA_TYPE_FIELD;

public class KnowhereValidationIT extends KNNRestTestCase {

    private static final String COMPRESSION_LEVEL_1X = "1x";

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(CLIENT_SOCKET_TIMEOUT, TimeValue.timeValueMinutes(10)).build();
    }

    public void testIndexCreation_whenVectorDataTypeIsByte_thenFail() throws Exception {
        String indexName = newIndexName("test-index-knowhere-byte");

        ResponseException exception = expectThrows(
            ResponseException.class,
            () -> createKnnIndex(
                indexName,
                createKnowhereDiskannMapping(8, SpaceType.L2, VectorDataType.BYTE.getValue(), "on_disk", COMPRESSION_LEVEL_1X, defaultBuildParameters())
            )
        );

        String responseBody = EntityUtils.toString(exception.getResponse().getEntity());
        assertThat(responseBody, Matchers.containsString("Compression and mode cannot be used for non-float32 data type"));
        assertThat(responseBody, Matchers.containsString(FIELD_NAME));
    }

    public void testIndexCreation_whenModeIsInMemory_thenFail() throws Exception {
        String indexName = newIndexName("test-index-knowhere-mode");

        ResponseException exception = expectThrows(
            ResponseException.class,
            () -> createKnnIndex(
                indexName,
                createKnowhereDiskannMapping(8, SpaceType.L2, null, "in_memory", COMPRESSION_LEVEL_1X, defaultBuildParameters())
            )
        );

        String responseBody = EntityUtils.toString(exception.getResponse().getEntity());
        assertThat(responseBody, Matchers.containsString("Knowhere engine only supports on_disk mode"));
    }

    public void testIndexCreation_whenCompressionLevelIs2x_thenFail() throws Exception {
        String indexName = newIndexName("test-index-knowhere-compression");

        ResponseException exception = expectThrows(
            ResponseException.class,
            () -> createKnnIndex(indexName, createKnowhereDiskannMapping(8, SpaceType.L2, null, "on_disk", "2x", defaultBuildParameters()))
        );

        String responseBody = EntityUtils.toString(exception.getResponse().getEntity());
        assertThat(responseBody, Matchers.containsString("does not support"));
        assertThat(responseBody, Matchers.containsString("2x"));
    }

    public void testIndexCreation_whenMaxDegreeIsNegative_thenFail() throws Exception {
        String indexName = newIndexName("test-index-knowhere-max-degree");

        ResponseException exception = expectThrows(
            ResponseException.class,
            () -> createKnnIndex(
                indexName,
                createKnowhereDiskannMapping(
                    8,
                    SpaceType.L2,
                    null,
                    "on_disk",
                    COMPRESSION_LEVEL_1X,
                    Map.of(DISKANN_MAX_DEGREE, -1)
                )
            )
        );

        String responseBody = EntityUtils.toString(exception.getResponse().getEntity());
        assertThat(responseBody, Matchers.containsString("parameter validation failed for Integer parameter [max_degree]"));
    }

    public void testIndexCreation_whenSearchListSizeIsZero_thenFail() throws Exception {
        String indexName = newIndexName("test-index-knowhere-search-list-size");

        ResponseException exception = expectThrows(
            ResponseException.class,
            () -> createKnnIndex(
                indexName,
                createKnowhereDiskannMapping(
                    8,
                    SpaceType.L2,
                    null,
                    "on_disk",
                    COMPRESSION_LEVEL_1X,
                    Map.of(DISKANN_SEARCH_LIST_SIZE, 0)
                )
            )
        );

        String responseBody = EntityUtils.toString(exception.getResponse().getEntity());
        assertThat(responseBody, Matchers.containsString("parameter validation failed for Integer parameter [search_list_size]"));
    }

    public void testSearch_whenSearchListIsZero_thenFail() throws Exception {
        String indexName = newIndexName("test-index-knowhere-query-search-list");

        try {
            createValidKnowhereIndex(indexName);

            ResponseException exception = expectThrows(
                ResponseException.class,
                () -> searchKNNIndex(indexName, buildSearchQuery(FIELD_NAME, 1, new float[] { 1.0f, 0.0f, 0.0f, 0.0f }, Map.of("search_list", 0)), 1)
            );

            String responseBody = EntityUtils.toString(exception.getResponse().getEntity());
            assertThat(responseBody, Matchers.containsString("search_list should be greater than 0"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testSearch_whenBeamwidthIsZero_thenFail() throws Exception {
        String indexName = newIndexName("test-index-knowhere-query-beamwidth");

        try {
            createValidKnowhereIndex(indexName);

            ResponseException exception = expectThrows(
                ResponseException.class,
                () -> searchKNNIndex(indexName, buildSearchQuery(FIELD_NAME, 1, new float[] { 1.0f, 0.0f, 0.0f, 0.0f }, Map.of("beamwidth", 0)), 1)
            );

            String responseBody = EntityUtils.toString(exception.getResponse().getEntity());
            assertThat(responseBody, Matchers.containsString("beamwidth should be greater than 0"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    private void createValidKnowhereIndex(String indexName) throws Exception {
        createKnnIndex(indexName, createKnowhereDiskannMapping(4, SpaceType.L2, null, "on_disk", COMPRESSION_LEVEL_1X, defaultBuildParameters()));
        addKnnDoc(indexName, "1", FIELD_NAME, new float[] { 1.0f, 0.0f, 0.0f, 0.0f });
    }

    private String createKnowhereDiskannMapping(
        int dimension,
        SpaceType spaceType,
        String vectorDataType,
        String mode,
        String compressionLevel,
        Map<String, Object> buildParameters
    ) throws IOException {
        var builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject(FIELD_NAME)
            .field(KNNConstants.TYPE, KNNConstants.TYPE_KNN_VECTOR)
            .field(KNNConstants.DIMENSION, dimension)
            .field("doc_values", true);

        if (vectorDataType != null) {
            builder.field(VECTOR_DATA_TYPE_FIELD, vectorDataType);
        }
        if (mode != null) {
            builder.field(MODE_PARAMETER, mode);
        }
        if (compressionLevel != null) {
            builder.field(COMPRESSION_LEVEL_PARAMETER, compressionLevel);
        }

        builder.startObject(KNNConstants.KNN_METHOD)
            .field(KNNConstants.NAME, METHOD_DISKANN)
            .field(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, spaceType.getValue())
            .field(KNNConstants.KNN_ENGINE, KNNEngine.KNOWHERE.getName())
            .startObject(KNNConstants.PARAMETERS);

        appendParameters(builder, buildParameters);

        return builder.endObject().endObject().endObject().endObject().endObject().toString();
    }

    private Map<String, Object> defaultBuildParameters() {
        return Map.of(DISKANN_BUILD_DRAM_BUDGET_GB, 4.0);
    }

    private void appendParameters(XContentBuilder builder, Map<String, ?> parameters) throws IOException {
        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            builder.field(entry.getKey(), entry.getValue());
        }
    }

    private String newIndexName(String prefix) {
        return prefix + "-" + randomAlphaOfLength(5).toLowerCase();
    }

    private void deleteIndexQuietly(String indexName) {
        try {
            deleteKNNIndex(indexName);
        } catch (Exception ignored) {
            // Best effort cleanup for tests that intentionally exercise failure paths.
        }
    }
}
