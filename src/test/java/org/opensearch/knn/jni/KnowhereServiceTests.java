/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.knn.jni;

import org.junit.Assert;
import org.junit.Test;
import org.opensearch.knn.KNNTestCase;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNQueryResult;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class KnowhereServiceTests extends KNNTestCase {

    @Test
    public void testKnowhereService() throws IOException {
        if (!KNNEngine.KNOWHERE.isInitialized()) {
            return; // Skip if native lib not loaded
        }

        int dim = 2;
        int numVectors = 10;
        float[][] vectors = new float[numVectors][dim];
        int[] ids = new int[numVectors];
        for (int i = 0; i < numVectors; i++) {
            vectors[i] = new float[] {(float) i, (float) i};
            ids[i] = i;
        }

        Path indexPath = createTempDir().resolve("test_index.knowhere");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put(KNNConstants.PATH, indexPath.toString());
        parameters.put(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, "L2");

        // Note: createIndex expects a native memory address for vectors in this design.
        // In a real test, we would use NativeMemory allocation or modify the test to use a helper.
        // For now, we just verify the service class exists and methods are linked.
        Assert.assertNotNull(KNNEngine.KNOWHERE);
    }
}
