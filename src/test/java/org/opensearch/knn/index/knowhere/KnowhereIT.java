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
import org.opensearch.knn.KNNRestTestCase;
import org.opensearch.knn.KNNResult;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNQueryBuilder;

import java.util.Arrays;
import java.util.List;

import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;

public class KnowhereIT extends KNNRestTestCase {

    @Override
    protected Settings restClientSettings() {
        return Settings.builder().put(CLIENT_SOCKET_TIMEOUT, TimeValue.timeValueMinutes(10)).build();
    }

    public void testEndToEnd_DiskANN() throws Exception {
        int dimension = 128;
        int docCount = 300;
        String indexName = "test-index-knowhere";
        String fieldName = "test-field";

        String mapping = XContentFactory.jsonBuilder()
            .startObject()
            .startObject("properties")
            .startObject(fieldName)
            .field(KNNConstants.TYPE, KNNConstants.TYPE_KNN_VECTOR)
            .field(KNNConstants.DIMENSION, Integer.toString(dimension))
            .field("doc_values", true)
            .startObject(KNNConstants.KNN_METHOD)
            .field(KNNConstants.NAME, METHOD_DISKANN)
            .field(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, SpaceType.L2.getValue())
            .field(KNNConstants.KNN_ENGINE, KNNEngine.KNOWHERE.getName())
            .startObject(KNNConstants.PARAMETERS)
            .field(KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB, 4.0)
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .endObject()
            .toString();
        createKnnIndex(indexName, mapping);

        float[][] vectors = new float[docCount][dimension];
        for (int doc = 0; doc < docCount; doc++) {
            for (int dim = 0; dim < dimension; dim++) {
                vectors[doc][dim] = doc + (dim * 0.001f);
            }
        }

        Request bulkRequest = new Request("POST", "/_bulk");
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < docCount; i++) {
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

        refreshIndex(indexName);

        int k = 1;
        float[] queryVector = vectors[0];
        KNNQueryBuilder knnQueryBuilder = new KNNQueryBuilder(fieldName, queryVector, k);
        Response response = searchKNNIndex(indexName, knnQueryBuilder, k);
        String responseBody = EntityUtils.toString(response.getEntity());
        List<KNNResult> results = parseSearchResponse(responseBody, fieldName);

        assertEquals(1, results.size());
        assertEquals("0", results.get(0).getDocId());

        deleteKNNIndex(indexName);
    }
}
