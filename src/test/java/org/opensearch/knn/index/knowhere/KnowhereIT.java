/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.knowhere;

import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.opensearch.client.Response;
import org.opensearch.knn.KNNRestTestCase;
import org.opensearch.knn.KNNResult;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNQueryBuilder;

import java.util.List;

import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;

public class KnowhereIT extends KNNRestTestCase {

    public void testEndToEnd_DiskANN() throws Exception {
        int dimension = 128;
        String indexName = "test-index-knowhere";
        String fieldName = "test-field";

        String mapping = createKnnIndexMapping(fieldName, dimension, METHOD_DISKANN, KNNEngine.KNOWHERE.getName(), SpaceType.L2.getValue());
        createKnnIndex(indexName, mapping);

        // Add doc
        float[] vector = new float[dimension];
        for (int i = 0; i < dimension; i++) vector[i] = (float) i;
        addKnnDoc(indexName, "1", fieldName, vector);

        refreshIndex(indexName);

        // Search
        int k = 1;
        float[] queryVector = new float[dimension];
        for (int i = 0; i < dimension; i++) queryVector[i] = (float) i;
        
        KNNQueryBuilder knnQueryBuilder = new KNNQueryBuilder(fieldName, queryVector, k);
        Response response = searchKNNIndex(indexName, knnQueryBuilder, k);
        String responseBody = EntityUtils.toString(response.getEntity());
        List<KNNResult> results = parseSearchResponse(responseBody, fieldName);
        
        assertEquals(1, results.size());
        assertEquals("1", results.get(0).getDocId());
        
        deleteKNNIndex(indexName);
    }
}
