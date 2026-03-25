/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.knowhere;

import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.knn.KNNRestTestCase;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNQueryBuilder;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.opensearch.knn.common.KNNConstants.COMPRESSION_LEVEL_PARAMETER;
import static org.opensearch.knn.common.KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB;
import static org.opensearch.knn.common.KNNConstants.DISKANN_DISK_PQ_DIMS;
import static org.opensearch.knn.common.KNNConstants.DISKANN_MAX_DEGREE;
import static org.opensearch.knn.common.KNNConstants.DISKANN_NUM_BUILD_THREAD;
import static org.opensearch.knn.common.KNNConstants.DISKANN_SEARCH_LIST_SIZE;
import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;
import static org.opensearch.knn.common.KNNConstants.MODE_PARAMETER;

public class KnowhereIT extends KNNRestTestCase {

    private static final String COMPRESSION_LEVEL_1X = "1x";

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(CLIENT_SOCKET_TIMEOUT, TimeValue.timeValueMinutes(10)).build();
    }

    public void testEndToEnd_DiskANN_L2() throws Exception {
        String indexName = newIndexName("test-index-knowhere-l2");
        int dimension = 16;
        float[][] vectors = createOrdinalVectors(64, dimension, 10.0f);

        try {
            createKnnIndex(indexName, createKnowhereDiskannMapping(FIELD_NAME, dimension, SpaceType.L2, defaultBuildParameters()));
            bulkIndexVectors(indexName, FIELD_NAME, vectors);
            refreshIndex(indexName);

            assertQueryReturnsDocIds(indexName, FIELD_NAME, vectors[0], 1, null, List.of("0"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testEndToEnd_DiskANN_InnerProduct() throws Exception {
        String indexName = newIndexName("test-index-knowhere-ip");
        int dimension = 8;
        float[][] vectors = createBasisVectors(dimension);

        try {
            createKnnIndex(indexName, createKnowhereDiskannMapping(FIELD_NAME, dimension, SpaceType.INNER_PRODUCT, defaultBuildParameters()));
            bulkIndexVectors(indexName, FIELD_NAME, vectors);
            refreshIndex(indexName);

            assertQueryReturnsDocIds(indexName, FIELD_NAME, vectors[3], 1, null, List.of("3"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testEndToEnd_DiskANN_Cosine() throws Exception {
        String indexName = newIndexName("test-index-knowhere-cosine");
        int dimension = 8;
        float[][] vectors = createBasisVectors(dimension);

        try {
            createKnnIndex(indexName, createKnowhereDiskannMapping(FIELD_NAME, dimension, SpaceType.COSINESIMIL, defaultBuildParameters()));
            bulkIndexVectors(indexName, FIELD_NAME, vectors);
            refreshIndex(indexName);

            assertQueryReturnsDocIds(indexName, FIELD_NAME, vectors[5], 1, null, List.of("5"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testEndToEnd_DiskANN_withNonDefaultBuildParams_thenSucceed() throws Exception {
        String indexName = newIndexName("test-index-knowhere-build-params");
        int dimension = 16;
        float[][] vectors = createBasisVectors(dimension);
        Map<String, Object> buildParameters = Map.of(
            DISKANN_MAX_DEGREE,
            64,
            DISKANN_SEARCH_LIST_SIZE,
            96,
            DISKANN_BUILD_DRAM_BUDGET_GB,
            4.0,
            DISKANN_DISK_PQ_DIMS,
            8,
            DISKANN_NUM_BUILD_THREAD,
            1
        );

        try {
            createKnnIndex(indexName, createKnowhereDiskannMapping(FIELD_NAME, dimension, SpaceType.L2, buildParameters));
            bulkIndexVectors(indexName, FIELD_NAME, vectors);
            refreshIndex(indexName);

            assertQueryReturnsDocIds(indexName, FIELD_NAME, vectors[7], 1, null, List.of("7"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testEndToEnd_DiskANN_withSearchListAndBeamwidth_thenSucceed() throws Exception {
        String indexName = newIndexName("test-index-knowhere-search-params");
        int dimension = 16;
        float[][] vectors = createOrdinalVectors(48, dimension, 10.0f);
        Map<String, Object> methodParameters = Map.of("search_list", 64, "beamwidth", 64);

        try {
            createKnnIndex(indexName, createKnowhereDiskannMapping(FIELD_NAME, dimension, SpaceType.L2, defaultBuildParameters()));
            bulkIndexVectors(indexName, FIELD_NAME, vectors);
            refreshIndex(indexName);

            assertQueryReturnsDocIds(indexName, FIELD_NAME, vectors[11], 1, methodParameters, List.of("11"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testEndToEnd_DiskANN_whenDeleteDocsAndForceMerge_thenSearchReflectsLiveDocs() throws Exception {
        String indexName = newIndexName("test-index-knowhere-delete-merge");
        int dimension = 16;
        float[][] vectors = createOrdinalVectors(8, dimension, 10.0f);
        float[] queryVector = vectors[2];

        try {
            createKnnIndex(indexName, createKnowhereDiskannMapping(FIELD_NAME, dimension, SpaceType.L2, defaultBuildParameters()));
            bulkIndexVectors(indexName, FIELD_NAME, vectors);
            refreshIndex(indexName);

            assertQueryReturnsDocIds(indexName, FIELD_NAME, queryVector, 1, null, List.of("2"));

            deleteKnnDoc(indexName, "2");
            refreshIndex(indexName);
            forceMergeKnnIndex(indexName);

            assertQueryOmitsDocId(indexName, FIELD_NAME, queryVector, 3, null, "2");
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testEndToEnd_DiskANN_mediumDataset_whenQueryUsesIndexedVector_thenTop1MatchesSelf() throws Exception {
        String indexName = newIndexName("test-index-knowhere-medium");
        int dimension = 128;
        int docCount = 300;
        float[][] vectors = createOrdinalVectors(docCount, dimension, 1.0f);

        try {
            createKnnIndex(indexName, createKnowhereDiskannMapping(FIELD_NAME, dimension, SpaceType.L2, defaultBuildParameters()));
            bulkIndexVectors(indexName, FIELD_NAME, vectors);
            refreshIndex(indexName);

            assertQueryReturnsDocIds(indexName, FIELD_NAME, vectors[173], 1, null, List.of("173"));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    private String createKnowhereDiskannMapping(
        String fieldName,
        int dimension,
        SpaceType spaceType,
        Map<String, Object> methodParameters
    ) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject(fieldName)
            .field(KNNConstants.TYPE, KNNConstants.TYPE_KNN_VECTOR)
            .field(KNNConstants.DIMENSION, dimension)
            .field("doc_values", true)
            .field(MODE_PARAMETER, "on_disk")
            .field(COMPRESSION_LEVEL_PARAMETER, COMPRESSION_LEVEL_1X)
            .startObject(KNNConstants.KNN_METHOD)
            .field(KNNConstants.NAME, METHOD_DISKANN)
            .field(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, spaceType.getValue())
            .field(KNNConstants.KNN_ENGINE, KNNEngine.KNOWHERE.getName())
            .startObject(KNNConstants.PARAMETERS);

        appendParameters(builder, methodParameters);

        return builder.endObject().endObject().endObject().endObject().endObject().toString();
    }

    private void bulkIndexVectors(String indexName, String fieldName, float[][] vectors) throws IOException {
        Request bulkRequest = new Request("POST", "/_bulk");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < vectors.length; i++) {
            body.append("{ \"index\" : { \"_index\" : \"")
                .append(indexName)
                .append("\", \"_id\" : \"")
                .append(i)
                .append("\" } }\n")
                .append("{ \"")
                .append(fieldName)
                .append("\" : ")
                .append(Arrays.toString(vectors[i]))
                .append(" }\n");
        }
        bulkRequest.setJsonEntity(body.toString());
        Response bulkResponse = client().performRequest(bulkRequest);
        assertEquals(200, bulkResponse.getStatusLine().getStatusCode());
    }

    private void assertQueryReturnsDocIds(
        String indexName,
        String fieldName,
        float[] queryVector,
        int k,
        Map<String, ?> methodParameters,
        List<String> expectedDocIds
    ) throws Exception {
        KNNQueryBuilder knnQueryBuilder = KNNQueryBuilder.builder()
            .fieldName(fieldName)
            .vector(queryVector)
            .k(k)
            .methodParameters(methodParameters)
            .build();

        Response response = searchKNNIndex(indexName, knnQueryBuilder, k);
        String responseBody = EntityUtils.toString(response.getEntity());

        assertEquals(expectedDocIds.size(), parseHits(responseBody));
        assertEquals(expectedDocIds, parseIds(responseBody));
    }

    private void assertQueryOmitsDocId(
        String indexName,
        String fieldName,
        float[] queryVector,
        int k,
        Map<String, ?> methodParameters,
        String unexpectedDocId
    ) throws Exception {
        KNNQueryBuilder knnQueryBuilder = KNNQueryBuilder.builder()
            .fieldName(fieldName)
            .vector(queryVector)
            .k(k)
            .methodParameters(methodParameters)
            .build();

        Response response = searchKNNIndex(indexName, knnQueryBuilder, k);
        String responseBody = EntityUtils.toString(response.getEntity());

        assertTrue(parseHits(responseBody) > 0);
        assertFalse(parseIds(responseBody).contains(unexpectedDocId));
    }

    private Map<String, Object> defaultBuildParameters() {
        return Map.of(DISKANN_BUILD_DRAM_BUDGET_GB, 4.0);
    }

    private void appendParameters(XContentBuilder builder, Map<String, ?> parameters) throws IOException {
        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            builder.field(entry.getKey(), entry.getValue());
        }
    }

    private float[][] createOrdinalVectors(int docCount, int dimension, float spacing) {
        float[][] vectors = new float[docCount][dimension];
        for (int doc = 0; doc < docCount; doc++) {
            for (int dim = 0; dim < dimension; dim++) {
                vectors[doc][dim] = (doc * spacing) + (dim * 0.001f);
            }
        }
        return vectors;
    }

    private float[][] createBasisVectors(int dimension) {
        float[][] vectors = new float[dimension][dimension];
        for (int i = 0; i < dimension; i++) {
            vectors[i][i] = 1.0f;
        }
        return vectors;
    }

    private float[] createConstantVector(int dimension, float value) {
        float[] vector = new float[dimension];
        Arrays.fill(vector, value);
        return vector;
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
