/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.knowhere;

import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Response;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.knn.KNNRestTestCase;
import org.opensearch.knn.KNNResult;
import org.opensearch.knn.TestUtils;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNQueryBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.opensearch.knn.common.KNNConstants.COMPRESSION_LEVEL_PARAMETER;
import static org.opensearch.knn.common.KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB;
import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;
import static org.opensearch.knn.common.KNNConstants.MODE_PARAMETER;
import static org.opensearch.knn.index.KNNSettings.INDEX_KNN_ADVANCED_APPROXIMATE_THRESHOLD;

public class KnowhereRecallIT extends KNNRestTestCase {

    private static final String COMPRESSION_LEVEL_1X = "1x";
    private static final int TEST_DIMENSION = 32;
    private static final int DOC_COUNT = 512;
    private static final int QUERY_COUNT = 50;
    private static final int TEST_K = 20;
    private static final int SHARD_COUNT = 1;
    private static final int REPLICA_COUNT = 0;

    private static final float[][] INDEX_VECTORS = TestUtils.getIndexVectors(DOC_COUNT, TEST_DIMENSION, true);
    private static final float[][] QUERY_VECTORS = TestUtils.getQueryVectors(QUERY_COUNT, TEST_DIMENSION, DOC_COUNT, true);
    private static final Map<SpaceType, List<Set<String>>> GROUND_TRUTH = Map.of(
        SpaceType.L2,
        TestUtils.computeGroundTruthValues(INDEX_VECTORS, QUERY_VECTORS, SpaceType.L2, TEST_K),
        SpaceType.COSINESIMIL,
        TestUtils.computeGroundTruthValues(INDEX_VECTORS, QUERY_VECTORS, SpaceType.COSINESIMIL, TEST_K),
        SpaceType.INNER_PRODUCT,
        TestUtils.computeGroundTruthValues(INDEX_VECTORS, QUERY_VECTORS, SpaceType.INNER_PRODUCT, TEST_K)
    );
    private static final Map<String, Object> DEFAULT_BUILD_PARAMETERS = Map.of(DISKANN_BUILD_DRAM_BUDGET_GB, 4.0);
    private static final Map<String, Object> SEARCH_METHOD_PARAMETERS = Map.of("search_list", 100, "beamwidth", 100);

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(CLIENT_SOCKET_TIMEOUT, TimeValue.timeValueMinutes(10)).build();
    }

    public void testRecall_whenKnowhereDiskannL2_thenRecallAbove90Percent() throws Exception {
        assertRecallAboveThreshold(SpaceType.L2, 0.90d);
    }

    public void testRecall_whenKnowhereDiskannInnerProduct_thenRecallAbove85Percent() throws Exception {
        assertRecallAboveThreshold(SpaceType.INNER_PRODUCT, 0.85d);
    }

    public void testRecall_whenKnowhereDiskannCosine_thenRecallAbove90Percent() throws Exception {
        assertRecallAboveThreshold(SpaceType.COSINESIMIL, 0.90d);
    }

    private void assertRecallAboveThreshold(SpaceType spaceType, double minimumRecall) throws Exception {
        String indexName = newIndexName("test-index-knowhere-recall-" + spaceType.getValue());

        try {
            createIndexAndIngestDocs(indexName, createKnowhereDiskannMapping(TEST_DIMENSION, spaceType));
            List<List<String>> searchResults = bulkSearch(indexName, FIELD_NAME, QUERY_VECTORS, TEST_K, SEARCH_METHOD_PARAMETERS);
            double recallValue = TestUtils.calculateRecallValue(searchResults, GROUND_TRUTH.get(spaceType), TEST_K);

            assertTrue("Recall for " + spaceType + " was " + recallValue, recallValue >= minimumRecall);
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    private void createIndexAndIngestDocs(String indexName, String mapping) throws Exception {
        createKnnIndex(indexName, getSettings(), mapping);
        bulkAddKnnDocs(indexName, FIELD_NAME, INDEX_VECTORS, DOC_COUNT);
        forceMergeKnnIndex(indexName);
    }

    private List<List<String>> bulkSearch(
        String indexName,
        String fieldName,
        float[][] queryVectors,
        int k,
        Map<String, ?> methodParameters
    ) throws Exception {
        List<List<String>> searchResults = new ArrayList<>();

        for (float[] queryVector : queryVectors) {
            KNNQueryBuilder knnQueryBuilder = KNNQueryBuilder.builder()
                .fieldName(fieldName)
                .vector(queryVector)
                .k(k)
                .methodParameters(methodParameters)
                .build();
            Response response = searchKNNIndex(indexName, knnQueryBuilder, k);
            List<KNNResult> results = parseSearchResponse(EntityUtils.toString(response.getEntity()), fieldName);

            assertEquals(k, results.size());

            List<String> docIds = new ArrayList<>();
            for (KNNResult result : results) {
                docIds.add(result.getDocId());
            }
            searchResults.add(docIds);
        }

        return searchResults;
    }

    private String createKnowhereDiskannMapping(int dimension, SpaceType spaceType) throws IOException {
        XContentBuilder builder = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject(FIELD_NAME)
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

        appendParameters(builder, DEFAULT_BUILD_PARAMETERS);

        return builder.endObject().endObject().endObject().endObject().endObject().toString();
    }

    private Settings getSettings() {
        return Settings.builder()
            .put("number_of_shards", SHARD_COUNT)
            .put("number_of_replicas", REPLICA_COUNT)
            .put("index.knn", true)
            .put(INDEX_KNN_ADVANCED_APPROXIMATE_THRESHOLD, 0)
            .build();
    }

    private void appendParameters(XContentBuilder builder, Map<String, ?> parameters) throws IOException {
        for (Map.Entry<String, ?> entry : parameters.entrySet()) {
            builder.field(entry.getKey(), entry.getValue());
        }
    }

    private void deleteIndexQuietly(String indexName) {
        try {
            deleteIndex(indexName);
        } catch (Exception ignored) {
            // Best-effort cleanup for tests that may fail before index creation finishes.
        }
    }

    private String newIndexName(String prefix) {
        return prefix + "-" + randomAlphaOfLength(5).toLowerCase();
    }
}
