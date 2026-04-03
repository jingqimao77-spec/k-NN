/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.knowhere;

import com.google.common.collect.Multimap;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Request;
import org.opensearch.client.Response;
import org.opensearch.common.settings.Settings;
import org.opensearch.common.unit.TimeValue;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.MediaTypeRegistry;
import org.opensearch.knn.KNNJsonQueryBuilder;
import org.opensearch.knn.KNNRestTestCase;
import org.opensearch.knn.NestedKnnDocBuilder;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.KNNSettings;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.engine.KNNEngine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.opensearch.knn.common.KNNConstants.COMPRESSION_LEVEL_PARAMETER;
import static org.opensearch.knn.common.KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB;
import static org.opensearch.knn.common.KNNConstants.EXPAND_NESTED;
import static org.opensearch.knn.common.Constants.FIELD_FILTER;
import static org.opensearch.knn.common.Constants.FIELD_TERM;
import static org.opensearch.knn.common.KNNConstants.K;
import static org.opensearch.knn.common.KNNConstants.KNN;
import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;
import static org.opensearch.knn.common.KNNConstants.PATH;
import static org.opensearch.knn.common.KNNConstants.QUERY;
import static org.opensearch.knn.common.KNNConstants.TYPE_NESTED;
import static org.opensearch.knn.common.KNNConstants.VECTOR;
import static org.opensearch.knn.common.KNNConstants.MODE_PARAMETER;

public class KnowhereFilterAndNestedIT extends KNNRestTestCase {

    private static final String COMPRESSION_LEVEL_1X = "1x";
    private static final String FILTER_FIELD_NAME = "color";
    private static final String FILTER_VALUE_RED = "red";
    private static final String FILTER_VALUE_BLUE = "blue";
    private static final String NESTED_FIELD_NAME = "test_nested";
    private static final String NESTED_VECTOR_FIELD_NAME = "test_vector";
    private static final String PARKING_FIELD_NAME = "parking";
    private static final String STORAGE_FIELD_NAME = "storage";
    private static final String INNER_HITS = "inner_hits";
    private static final String FIELD_VALUE_TRUE = "true";
    private static final String FIELD_VALUE_FALSE = "false";

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(CLIENT_SOCKET_TIMEOUT, TimeValue.timeValueMinutes(10)).build();
    }

    public void testFilteredSearchWithKnowhereDiskann_whenFilterSelectsSubset_thenOnlyFilteredDocsReturned() throws Exception {
        String indexName = newIndexName("test-index-knowhere-filtered");

        try {
            createFilteredKnowhereIndex(indexName, 1);
            addFilteredDoc(indexName, "red-0", new float[] { 100.0f, 100.0f, 100.0f }, FILTER_VALUE_RED, null);
            addFilteredDoc(indexName, "red-1", new float[] { 110.0f, 110.0f, 110.0f }, FILTER_VALUE_RED, null);
            addFilteredDoc(indexName, "red-2", new float[] { 120.0f, 120.0f, 120.0f }, FILTER_VALUE_RED, null);
            addFilteredDoc(indexName, "red-3", new float[] { 130.0f, 130.0f, 130.0f }, FILTER_VALUE_RED, null);
            addFilteredDoc(indexName, "blue-0", new float[] { 0.0f, 0.0f, 0.0f }, FILTER_VALUE_BLUE, null);
            addFilteredDoc(indexName, "blue-1", new float[] { 1.0f, 1.0f, 1.0f }, FILTER_VALUE_BLUE, null);
            refreshIndex(indexName);
            forceMergeKnnIndex(indexName);

            Response response = searchKNNIndex(indexName, createFilteredQuery(new float[] { 0.0f, 0.0f, 0.0f }, 2), 2);
            String responseBody = EntityUtils.toString(response.getEntity());

            assertEquals(List.of("red-0", "red-1"), parseIds(responseBody));
            assertEquals(2, parseHits(responseBody));
            assertEquals(2, parseTotalSearchHits(responseBody));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testFilteredSearchWithKnowhereDiskann_whenMultipleShards_thenOnlyFilteredDocsReturned() throws Exception {
        String indexName = newIndexName("test-index-knowhere-filtered-shards");

        try {
            createFilteredKnowhereIndex(indexName, 2);
            List<String> routingValues = findRoutingValuesForDifferentShards(indexName);
            String routingA = routingValues.get(0);
            String routingB = routingValues.get(1);

            addFilteredDoc(indexName, "red-a0", new float[] { 100.0f, 100.0f, 100.0f }, FILTER_VALUE_RED, routingA);
            addFilteredDoc(indexName, "red-a1", new float[] { 110.0f, 110.0f, 110.0f }, FILTER_VALUE_RED, routingA);
            addFilteredDoc(indexName, "red-a2", new float[] { 120.0f, 120.0f, 120.0f }, FILTER_VALUE_RED, routingA);
            addFilteredDoc(indexName, "blue-a", new float[] { 0.0f, 0.0f, 0.0f }, FILTER_VALUE_BLUE, routingA);
            addFilteredDoc(indexName, "red-b0", new float[] { 200.0f, 200.0f, 200.0f }, FILTER_VALUE_RED, routingB);
            addFilteredDoc(indexName, "red-b1", new float[] { 210.0f, 210.0f, 210.0f }, FILTER_VALUE_RED, routingB);
            addFilteredDoc(indexName, "red-b2", new float[] { 220.0f, 220.0f, 220.0f }, FILTER_VALUE_RED, routingB);
            addFilteredDoc(indexName, "blue-b", new float[] { 1.0f, 1.0f, 1.0f }, FILTER_VALUE_BLUE, routingB);
            refreshIndex(indexName);
            forceMergeKnnIndex(indexName);

            Response response = searchKNNIndex(indexName, createFilteredQuery(new float[] { 0.0f, 0.0f, 0.0f }, 2), 2);
            String responseBody = EntityUtils.toString(response.getEntity());

            assertEquals(List.of("red-a0", "red-a1"), parseIds(responseBody));
            assertEquals(2, parseHits(responseBody));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testNestedSearchWithKnowhere_whenKIsTwo_thenReturnTwoParentResults() throws Exception {
        String indexName = newIndexName("test-index-knowhere-nested");

        try {
            createNestedKnowhereIndex(indexName, 2);
            for (int i = 0; i < 15; i++) {
                String doc = NestedKnnDocBuilder.create(NESTED_FIELD_NAME)
                    .addVectors(
                        NESTED_VECTOR_FIELD_NAME,
                        new Float[] { (float) i, (float) i },
                        new Float[] { (float) i, (float) i }
                    )
                    .build();
                addKnnDoc(indexName, String.valueOf(i), doc);
            }
            refreshIndex(indexName);
            forceMergeKnnIndex(indexName);

            Response response = searchKNNIndex(indexName, createNestedQuery(new float[] { 14.0f, 14.0f }, 2, null, null), 2);
            String responseBody = EntityUtils.toString(response.getEntity());

            assertEquals(List.of("14", "13"), parseIds(responseBody));
            assertEquals(2, parseHits(responseBody));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testNestedSearchWithKnowhere_whenFilterApplied_thenReturnOnlyMatchingParents() throws Exception {
        String indexName = newIndexName("test-index-knowhere-nested-filter");

        try {
            createNestedKnowhereIndex(indexName, 3);
            addNestedFilteredDoc(indexName, "1", 100.0f, FIELD_VALUE_TRUE);
            addNestedFilteredDoc(indexName, "2", 110.0f, FIELD_VALUE_TRUE);
            addNestedFilteredDoc(indexName, "3", 120.0f, FIELD_VALUE_TRUE);
            addNestedFilteredDoc(indexName, "9", 0.0f, FIELD_VALUE_FALSE);
            refreshIndex(indexName);
            forceMergeKnnIndex(indexName);

            Response response = searchKNNIndex(indexName, createNestedQuery(new float[] { 0.0f, 0.0f, 0.0f }, 2, PARKING_FIELD_NAME, FIELD_VALUE_TRUE), 2);
            String responseBody = EntityUtils.toString(response.getEntity());

            assertEquals(List.of("1", "2"), parseIds(responseBody));
            assertEquals(2, parseHits(responseBody));
            assertEquals(2, parseTotalSearchHits(responseBody));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    public void testExpandNestedDocsWithKnowhere_whenFilterAppliedOnNestedField_thenReturnFilteredNestedDocs() throws Exception {
        String indexName = newIndexName("test-index-knowhere-nested-expand");

        try {
            createNestedKnowhereIndex(indexName, 1);
            addNestedDocWithMetadata(indexName, "1", 100.0f, List.of(FIELD_VALUE_FALSE, FIELD_VALUE_FALSE));
            addNestedDocWithMetadata(indexName, "2", 0.0f, List.of(FIELD_VALUE_TRUE, FIELD_VALUE_TRUE));
            addNestedDocWithMetadata(indexName, "3", 10.0f, List.of(FIELD_VALUE_TRUE, FIELD_VALUE_FALSE));
            refreshIndex(indexName);
            forceMergeKnnIndex(indexName);

            Response response = searchKNNIndex(
                indexName,
                createExpandNestedQuery(
                    new float[] { 0.0f },
                    10,
                    NESTED_FIELD_NAME + "." + STORAGE_FIELD_NAME,
                    FIELD_VALUE_TRUE
                ),
                10
            );
            String responseBody = EntityUtils.toString(response.getEntity());

            Multimap<String, Integer> docIdToOffsets = parseInnerHits(responseBody, NESTED_FIELD_NAME);
            assertEquals(2, docIdToOffsets.keySet().size());
            assertEquals(2, docIdToOffsets.get("2").size());
            assertEquals(1, docIdToOffsets.get("3").size());
            assertTrue(docIdToOffsets.get("3").contains(0));
        } finally {
            deleteIndexQuietly(indexName);
        }
    }

    private void createFilteredKnowhereIndex(String indexName, int shardCount) throws Exception {
        Settings settings = Settings.builder()
            .put(getKNNDefaultIndexSettings())
            .put("number_of_shards", shardCount)
            .put(KNNSettings.ADVANCED_FILTERED_EXACT_SEARCH_THRESHOLD, 0)
            .build();
        createKnnIndex(indexName, settings, createFilteredKnowhereMapping());
    }

    private void createNestedKnowhereIndex(String indexName, int dimension) throws Exception {
        Settings settings = Settings.builder()
            .put(getKNNDefaultIndexSettings())
            .put(KNNSettings.ADVANCED_FILTERED_EXACT_SEARCH_THRESHOLD, 0)
            .build();
        createKnnIndex(indexName, settings, createNestedKnowhereMapping(dimension));
    }

    private String createFilteredKnowhereMapping() throws IOException {
        return XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject(FIELD_NAME)
            .field(KNNConstants.TYPE, KNNConstants.TYPE_KNN_VECTOR)
            .field(KNNConstants.DIMENSION, 3)
            .field("doc_values", true)
            .field(MODE_PARAMETER, "on_disk")
            .field(COMPRESSION_LEVEL_PARAMETER, COMPRESSION_LEVEL_1X)
            .startObject(KNNConstants.KNN_METHOD)
            .field(KNNConstants.NAME, METHOD_DISKANN)
            .field(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, SpaceType.L2.getValue())
            .field(KNNConstants.KNN_ENGINE, KNNEngine.KNOWHERE.getName())
            .startObject(KNNConstants.PARAMETERS)
            .field(DISKANN_BUILD_DRAM_BUDGET_GB, 4.0)
            .endObject()
            .endObject()
            .endObject()
            .startObject(FILTER_FIELD_NAME)
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject()
            .toString();
    }

    private String createNestedKnowhereMapping(int dimension) throws IOException {
        return XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject(NESTED_FIELD_NAME)
            .field("type", "nested")
            .startObject("properties")
            .startObject(NESTED_VECTOR_FIELD_NAME)
            .field(KNNConstants.TYPE, KNNConstants.TYPE_KNN_VECTOR)
            .field(KNNConstants.DIMENSION, dimension)
            .field("doc_values", true)
            .field(MODE_PARAMETER, "on_disk")
            .field(COMPRESSION_LEVEL_PARAMETER, COMPRESSION_LEVEL_1X)
            .startObject(KNNConstants.KNN_METHOD)
            .field(KNNConstants.NAME, METHOD_DISKANN)
            .field(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, SpaceType.L2.getValue())
            .field(KNNConstants.KNN_ENGINE, KNNEngine.KNOWHERE.getName())
            .startObject(KNNConstants.PARAMETERS)
            .field(DISKANN_BUILD_DRAM_BUDGET_GB, 4.0)
            .endObject()
            .endObject()
            .endObject()
            .startObject(STORAGE_FIELD_NAME)
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject()
            .startObject(PARKING_FIELD_NAME)
            .field("type", "keyword")
            .endObject()
            .endObject()
            .endObject()
            .toString();
    }

    private void addFilteredDoc(String indexName, String docId, float[] vector, String filterValue, String routing) throws IOException {
        String document = XContentFactory.jsonBuilder()
            .startObject()
            .field(FIELD_NAME, vector)
            .field(FILTER_FIELD_NAME, filterValue)
            .endObject()
            .toString();
        addKnnDoc(indexName, docId, document, routing);
    }

    private void addNestedFilteredDoc(String indexName, String docId, float value, String parkingValue) throws IOException {
        String doc = NestedKnnDocBuilder.create(NESTED_FIELD_NAME)
            .addVectors(
                NESTED_VECTOR_FIELD_NAME,
                new Float[] { value, value, value },
                new Float[] { value, value, value },
                new Float[] { value, value, value }
            )
            .addTopLevelField(PARKING_FIELD_NAME, parkingValue)
            .build();
        addKnnDoc(indexName, docId, doc);
    }

    private void addNestedDocWithMetadata(String indexName, String docId, float value, List<String> storageValues) throws IOException {
        NestedKnnDocBuilder builder = NestedKnnDocBuilder.create(NESTED_FIELD_NAME);
        for (String storageValue : storageValues) {
            builder.addVectorWithMetadata(NESTED_VECTOR_FIELD_NAME, new Float[] { value }, STORAGE_FIELD_NAME, storageValue);
        }
        addKnnDoc(indexName, docId, builder.build());
    }

    private String createFilteredQuery(float[] queryVector, int k) throws IOException {
        return KNNJsonQueryBuilder.builder()
            .fieldName(FIELD_NAME)
            .vector(toFloatObjects(queryVector))
            .k(k)
            .filterFieldName(FILTER_FIELD_NAME)
            .filterValue(FILTER_VALUE_RED)
            .build()
            .getQueryString();
    }

    private String createExpandNestedQuery(float[] queryVector, int k, String filterFieldName, String filterValue) throws IOException {
        return XContentFactory.jsonBuilder()
            .startObject()
            .startObject(QUERY)
            .startObject(TYPE_NESTED)
            .field(PATH, NESTED_FIELD_NAME)
            .startObject(QUERY)
            .startObject(KNN)
            .startObject(NESTED_FIELD_NAME + "." + NESTED_VECTOR_FIELD_NAME)
            .field(VECTOR, queryVector)
            .field(K, k)
            .field(EXPAND_NESTED, true)
            .startObject(FIELD_FILTER)
            .startObject(FIELD_TERM)
            .field(filterFieldName, filterValue)
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .field(INNER_HITS)
            .startObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .toString();
    }

    private String createNestedQuery(float[] queryVector, int k, String filterFieldName, String filterValue) throws IOException {
        return KNNJsonQueryBuilder.builder()
            .nestedFieldName(NESTED_FIELD_NAME)
            .fieldName(NESTED_VECTOR_FIELD_NAME)
            .vector(toFloatObjects(queryVector))
            .k(k)
            .filterFieldName(filterFieldName)
            .filterValue(filterValue)
            .build()
            .getQueryString();
    }

    private List<String> findRoutingValuesForDifferentShards(String indexName) throws Exception {
        Map<Integer, String> shardToRouting = new LinkedHashMap<>();
        for (int i = 0; i < 200; i++) {
            String routing = "routing-" + i;
            int shard = resolveShardForRouting(indexName, routing);
            shardToRouting.putIfAbsent(shard, routing);
            if (shardToRouting.size() >= 2) {
                return new ArrayList<>(shardToRouting.values());
            }
        }
        fail("Unable to find routing values for two different shards");
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private int resolveShardForRouting(String indexName, String routing) throws Exception {
        Request request = new Request("GET", "/" + indexName + "/_search_shards");
        request.addParameter("routing", routing);
        Response response = client().performRequest(request);
        String responseBody = EntityUtils.toString(response.getEntity());

        Map<String, Object> responseMap = createParser(MediaTypeRegistry.getDefaultMediaType().xContent(), responseBody).map();
        List<Object> shardGroups = (List<Object>) responseMap.get("shards");
        assertEquals(1, shardGroups.size());

        List<Object> shardCopies = (List<Object>) shardGroups.get(0);
        Map<String, Object> shardInfo = (Map<String, Object>) shardCopies.get(0);
        return (Integer) shardInfo.get("shard");
    }

    private Float[] toFloatObjects(float[] vector) {
        Float[] boxed = new Float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            boxed[i] = vector[i];
        }
        return boxed;
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
