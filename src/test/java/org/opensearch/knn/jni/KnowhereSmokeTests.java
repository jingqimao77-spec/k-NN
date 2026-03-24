/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.jni;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.junit.Test;
import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.jni.JNICommons;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNQueryResult;
import org.opensearch.knn.index.store.IndexInputWithBuffer;
import org.opensearch.knn.index.store.IndexOutputWithBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class KnowhereSmokeTests {

    @Test
    public void testCreateLoadQueryFree() throws Exception {
        runCreateLoadQueryFreeTest(
            2,
            new int[] { 0, 1, 2, 3 },
            new float[] {
                0.0f, 0.0f,
                1.0f, 1.0f,
                10.0f, 10.0f,
                11.0f, 11.0f
            },
            new float[] { 0.0f, 0.0f },
            1.0
        );
    }

    @Test
    public void testCreateLoadQueryFree_mediumDataset() throws Exception {
        final int dim = 128;
        final int docCount = 300;
        final int[] ids = new int[docCount];
        final float[] vectors = new float[docCount * dim];
        for (int doc = 0; doc < docCount; doc++) {
            ids[doc] = doc;
            for (int d = 0; d < dim; d++) {
                vectors[(doc * dim) + d] = doc + (d * 0.001f);
            }
        }

        final float[] query = new float[dim];
        System.arraycopy(vectors, 0, query, 0, dim);

        runCreateLoadQueryFreeTest(dim, ids, vectors, query, 4.0);
    }

    @Test
    public void testCreateLoadQueryFree_smallSegmentSizedDataset() throws Exception {
        final int dim = 128;
        final int docCount = 94;
        final int[] ids = new int[docCount];
        final float[] vectors = new float[docCount * dim];
        for (int doc = 0; doc < docCount; doc++) {
            ids[doc] = doc;
            for (int d = 0; d < dim; d++) {
                vectors[(doc * dim) + d] = doc + (d * 0.001f);
            }
        }

        final float[] query = new float[dim];
        System.arraycopy(vectors, 0, query, 0, dim);

        runCreateLoadQueryFreeTest(dim, ids, vectors, query, 4.0);
    }

    private void runCreateLoadQueryFreeTest(int dim, int[] ids, float[] vectors, float[] queryVector, double buildDramBudgetGb)
        throws Exception {
        Class.forName("org.opensearch.knn.jni.KnowhereService");
        assertTrue("Knowhere JNI library failed to initialize", KNNEngine.KNOWHERE.isInitialized());

        long address = 0L;
        try {
            final float[][] vectorMatrix = new float[ids.length][dim];
            for (int doc = 0; doc < ids.length; doc++) {
                System.arraycopy(vectors, doc * dim, vectorMatrix[doc], 0, dim);
            }
            address = JNICommons.storeVectorData(0L, vectorMatrix, (long) vectors.length);

            final Path tempDir = Files.createTempDirectory("knowhere-smoke-");
            final String manifestFileName = "smoke_diskann.knowhere";

            final Map<String, Object> buildParams = new HashMap<>();
            buildParams.put(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, "L2");
            buildParams.put(KNNConstants.DISKANN_MAX_DEGREE, 48);
            buildParams.put(KNNConstants.DISKANN_SEARCH_LIST_SIZE, 128);
            buildParams.put(KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB, buildDramBudgetGb);
            buildParams.put(KNNConstants.DISKANN_PQ_CODE_BUDGET_GB_RATIO, 0.125);
            buildParams.put(KNNConstants.DISKANN_SEARCH_CACHE_BUDGET_GB_RATIO, 0.0);
            buildParams.put(KNNConstants.DISKANN_DISK_PQ_DIMS, 0);
            buildParams.put(KNNConstants.DISKANN_ACCELERATE_BUILD, false);

            try (Directory directory = FSDirectory.open(tempDir);
                 IndexOutput manifestOutput = directory.createOutput(manifestFileName, IOContext.DEFAULT)) {
                IndexOutputWithBuffer output = new IndexOutputWithBuffer(manifestOutput, directory, IOContext.DEFAULT, manifestFileName);
                KnowhereService.createIndex(ids, address, dim, output, buildParams);
                address = 0L;
            }

            assertTrue("Knowhere build did not create any index files", Files.list(tempDir).findAny().isPresent());

            final Map<String, Object> queryParams = new HashMap<>();
            queryParams.put(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, "L2");
            queryParams.put(KNNConstants.DISKANN_SEARCH_LIST_SIZE, 128);

            try (Directory directory = FSDirectory.open(tempDir);
                 IndexInput manifestInput = directory.openInput(manifestFileName, IOContext.READONCE)) {
                IndexInputWithBuffer readStream = new IndexInputWithBuffer(manifestInput, directory, IOContext.READONCE, manifestFileName);
                long indexPointer = KnowhereService.loadIndex(readStream, queryParams);
                assertTrue("Knowhere loadIndex returned null pointer", indexPointer != 0L);

                try {
                    final KNNQueryResult[] results = KnowhereService.queryIndex(indexPointer, queryVector, 1, queryParams);
                    assertNotNull(results);
                    assertEquals(1, results.length);
                    assertEquals(0, results[0].getId());
                } finally {
                    KnowhereService.free(indexPointer);
                }
            }
        } finally {
            JNICommons.freeVectorData(address);
        }
    }
}
