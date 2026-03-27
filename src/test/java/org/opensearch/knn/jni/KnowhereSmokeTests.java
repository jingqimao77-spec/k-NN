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
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.FilterIdsSelector;
import org.opensearch.knn.index.query.KNNQueryResult;
import org.opensearch.knn.index.store.IndexInputWithBuffer;
import org.opensearch.knn.index.store.IndexOutputWithBuffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KnowhereSmokeTests {

    @FunctionalInterface
    private interface LoadedIndexAssertion {
        void run(long indexPointer, Map<String, Object> queryParams) throws Exception;
    }

    @Test
    public void testCreateLoadQueryFree() throws Exception {
        runCreateLoadQueryFreeTest(
            2,
            new int[] { 10, 20, 30, 40 },
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
    public void testCreateLoadQueryFree_nonDefaultBuildParams() throws Exception {
        final int dim = 16;
        final int[] ids = new int[dim];
        final float[] vectors = new float[dim * dim];
        for (int doc = 0; doc < dim; doc++) {
            ids[doc] = doc;
            vectors[(doc * dim) + doc] = 1.0f;
        }

        final float[] queryVector = new float[dim];
        System.arraycopy(vectors, 7 * dim, queryVector, 0, dim);

        final Map<String, Object> buildParams = new HashMap<>();
        buildParams.put(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, "L2");
        buildParams.put(KNNConstants.DISKANN_MAX_DEGREE, 64);
        buildParams.put(KNNConstants.DISKANN_SEARCH_LIST_SIZE, 96);
        buildParams.put(KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB, 4.0);
        buildParams.put(KNNConstants.DISKANN_PQ_CODE_BUDGET_GB_RATIO, 0.125);
        buildParams.put(KNNConstants.DISKANN_SEARCH_CACHE_BUDGET_GB_RATIO, 0.0);
        buildParams.put(KNNConstants.DISKANN_DISK_PQ_DIMS, 8);
        buildParams.put(KNNConstants.DISKANN_NUM_BUILD_THREAD, 1);
        buildParams.put(KNNConstants.DISKANN_ACCELERATE_BUILD, false);

        withLoadedKnowhereIndex(dim, ids, vectors, buildParams, (indexPointer, queryParams) -> {
            final KNNQueryResult[] results = KnowhereService.queryIndex(indexPointer, queryVector, 1, queryParams, null, 0, null);
            assertNotNull(results);
            assertEquals(1, results.length);
            assertEquals(7, results[0].getId());
        });
    }

    @Test
    public void testJNIServiceQueryIndexWithBatchFilterAndParentIds() throws Exception {
        final int[] ids = new int[] { 0, 1, 3, 4 };
        final float[] vectors = new float[] {
            0.0f, 0.0f,
            1.0f, 1.0f,
            10.0f, 10.0f,
            11.0f, 11.0f
        };

        withLoadedKnowhereIndex(2, ids, vectors, 1.0, (indexPointer, queryParams) -> {
            final KNNQueryResult[] results = JNIService.queryIndex(
                indexPointer,
                new float[] { 0.0f, 0.0f },
                1,
                queryParams,
                KNNEngine.KNOWHERE,
                new long[] { 3L, 4L },
                FilterIdsSelector.FilterIdsSelectorType.BATCH.getValue(),
                new int[] { 2, 5 }
            );
            assertNotNull(results);
            assertEquals(1, results.length);
            assertEquals(3, results[0].getId());
        });
    }

    @Test
    public void testQueryIndexWithBatchFilter() throws Exception {
        final int[] ids = new int[] { 10, 20, 30, 40 };
        final float[] vectors = new float[] {
            0.0f, 0.0f,
            1.0f, 1.0f,
            10.0f, 10.0f,
            11.0f, 11.0f
        };

        withLoadedKnowhereIndex(2, ids, vectors, 1.0, (indexPointer, queryParams) -> {
            final KNNQueryResult[] results = KnowhereService.queryIndex(
                indexPointer,
                new float[] { 0.0f, 0.0f },
                1,
                queryParams,
                new long[] { 20L, 30L },
                FilterIdsSelector.FilterIdsSelectorType.BATCH.getValue(),
                null
            );
            assertNotNull(results);
            assertEquals(1, results.length);
            assertEquals(20, results[0].getId());
        });
    }

    @Test
    public void testQueryIndexWithBitmapFilter() throws Exception {
        final int[] ids = new int[] { 10, 20, 30, 40 };
        final float[] vectors = new float[] {
            0.0f, 0.0f,
            1.0f, 1.0f,
            10.0f, 10.0f,
            11.0f, 11.0f
        };

        withLoadedKnowhereIndex(2, ids, vectors, 1.0, (indexPointer, queryParams) -> {
            final KNNQueryResult[] results = KnowhereService.queryIndex(
                indexPointer,
                new float[] { 0.0f, 0.0f },
                1,
                queryParams,
                toBitmapFilter(20, 30),
                FilterIdsSelector.FilterIdsSelectorType.BITMAP.getValue(),
                null
            );
            assertNotNull(results);
            assertEquals(1, results.length);
            assertEquals(20, results[0].getId());
        });
    }

    @Test
    public void testQueryIndexWithParentIds() throws Exception {
        final int[] ids = new int[] { 0, 1, 3, 4 };
        final float[] vectors = new float[] {
            0.0f, 0.0f,
            1.0f, 1.0f,
            10.0f, 10.0f,
            11.0f, 11.0f
        };

        withLoadedKnowhereIndex(2, ids, vectors, 1.0, (indexPointer, queryParams) -> {
            final KNNQueryResult[] results = KnowhereService.queryIndex(
                indexPointer,
                new float[] { 0.0f, 0.0f },
                2,
                queryParams,
                null,
                FilterIdsSelector.FilterIdsSelectorType.BITMAP.getValue(),
                new int[] { 2, 5 }
            );
            assertNotNull(results);
            assertEquals(2, results.length);
            assertEquals(0, results[0].getId());
            assertEquals(3, results[1].getId());
        });
    }

    @Test
    public void testQueryIndexWithBatchFilterAndParentIds() throws Exception {
        final int[] ids = new int[] { 0, 1, 3, 4 };
        final float[] vectors = new float[] {
            0.0f, 0.0f,
            1.0f, 1.0f,
            10.0f, 10.0f,
            11.0f, 11.0f
        };

        withLoadedKnowhereIndex(2, ids, vectors, 1.0, (indexPointer, queryParams) -> {
            final KNNQueryResult[] results = KnowhereService.queryIndex(
                indexPointer,
                new float[] { 0.0f, 0.0f },
                1,
                queryParams,
                new long[] { 3L, 4L },
                FilterIdsSelector.FilterIdsSelectorType.BATCH.getValue(),
                new int[] { 2, 5 }
            );
            assertNotNull(results);
            assertEquals(1, results.length);
            assertEquals(3, results[0].getId());
        });
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

    @Test
    public void testJNIServiceLoadIndexFailsWhenManifestMissingDocIdsSidecar() throws Exception {
        final int dim = 2;
        final int[] ids = new int[] { 10, 20, 30, 40 };
        final float[] vectors = new float[] {
            0.0f, 0.0f,
            1.0f, 1.0f,
            10.0f, 10.0f,
            11.0f, 11.0f
        };

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
            final Map<String, Object> buildParams = defaultBuildParams(1.0);

            try (Directory directory = FSDirectory.open(tempDir);
                 IndexOutput manifestOutput = directory.createOutput(manifestFileName, IOContext.DEFAULT)) {
                IndexOutputWithBuffer output = new IndexOutputWithBuffer(manifestOutput, directory, IOContext.DEFAULT, manifestFileName);
                JNIService.createIndex(ids, address, dim, output, buildParams, KNNEngine.KNOWHERE);
                address = 0L;
            }

            final Path manifestPath = tempDir.resolve(manifestFileName);
            final String docIdsFileName = "smoke_diskann.diskann.docids.bin";
            final String docIdsEntry = "{\"name\":\"" + docIdsFileName + "\",\"required\":true}";
            final String manifest = Files.readString(manifestPath);
            assertTrue("Manifest should list the doc ids sidecar before mutation", manifest.contains(docIdsEntry));

            String mutatedManifest = manifest.replace("," + docIdsEntry, "").replace(docIdsEntry + ",", "");
            if (mutatedManifest.equals(manifest)) {
                fail("Failed to remove doc ids sidecar entry from Knowhere manifest");
            }
            Files.writeString(manifestPath, mutatedManifest);

            final Map<String, Object> queryParams = new HashMap<>();
            queryParams.put(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, "L2");
            queryParams.put(KNNConstants.DISKANN_SEARCH_LIST_SIZE, buildParams.get(KNNConstants.DISKANN_SEARCH_LIST_SIZE));

            try (Directory directory = FSDirectory.open(tempDir);
                 IndexInput manifestInput = directory.openInput(manifestFileName, IOContext.READONCE)) {
                final IndexInputWithBuffer readStream = new IndexInputWithBuffer(manifestInput, directory, IOContext.READONCE, manifestFileName);
                try {
                    JNIService.loadIndex(readStream, queryParams, KNNEngine.KNOWHERE);
                    fail("Expected Knowhere loadIndex to fail when doc ids sidecar is missing from the manifest");
                } catch (Exception e) {
                    assertTrue(e.getMessage().contains("Knowhere manifest is missing required doc ids sidecar"));
                    assertTrue(e.getMessage().contains(docIdsFileName));
                }
            }
        } finally {
            JNICommons.freeVectorData(address);
        }
    }

    private void runCreateLoadQueryFreeTest(int dim, int[] ids, float[] vectors, float[] queryVector, double buildDramBudgetGb)
        throws Exception {
        withLoadedKnowhereIndex(dim, ids, vectors, defaultBuildParams(buildDramBudgetGb), (indexPointer, queryParams) -> {
            final KNNQueryResult[] results = KnowhereService.queryIndex(indexPointer, queryVector, 1, queryParams, null, 0, null);
            assertNotNull(results);
            assertEquals(1, results.length);
            assertEquals(ids[0], results[0].getId());
        });
    }

    private void withLoadedKnowhereIndex(int dim, int[] ids, float[] vectors, double buildDramBudgetGb, LoadedIndexAssertion assertion)
        throws Exception {
        withLoadedKnowhereIndex(dim, ids, vectors, defaultBuildParams(buildDramBudgetGb), assertion);
    }

    private void withLoadedKnowhereIndex(
        int dim,
        int[] ids,
        float[] vectors,
        Map<String, Object> buildParams,
        LoadedIndexAssertion assertion
    ) throws Exception {
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

            try (Directory directory = FSDirectory.open(tempDir);
                 IndexOutput manifestOutput = directory.createOutput(manifestFileName, IOContext.DEFAULT)) {
                IndexOutputWithBuffer output = new IndexOutputWithBuffer(manifestOutput, directory, IOContext.DEFAULT, manifestFileName);
                JNIService.createIndex(ids, address, dim, output, buildParams, KNNEngine.KNOWHERE);
                address = 0L;
            }

            try (Stream<Path> files = Files.list(tempDir)) {
                assertTrue("Knowhere build did not create any index files", files.findAny().isPresent());
            }

            final Map<String, Object> queryParams = new HashMap<>();
            queryParams.put(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, "L2");
            queryParams.put(KNNConstants.DISKANN_SEARCH_LIST_SIZE, buildParams.getOrDefault(KNNConstants.DISKANN_SEARCH_LIST_SIZE, 128));

            try (Directory directory = FSDirectory.open(tempDir);
                 IndexInput manifestInput = directory.openInput(manifestFileName, IOContext.READONCE)) {
                IndexInputWithBuffer readStream = new IndexInputWithBuffer(manifestInput, directory, IOContext.READONCE, manifestFileName);
                long indexPointer = JNIService.loadIndex(readStream, queryParams, KNNEngine.KNOWHERE);
                assertTrue("Knowhere loadIndex returned null pointer", indexPointer != 0L);

                try {
                    assertion.run(indexPointer, queryParams);
                } finally {
                    JNIService.free(indexPointer, KNNEngine.KNOWHERE);
                }
            }
        } finally {
            JNICommons.freeVectorData(address);
        }
    }

    private Map<String, Object> defaultBuildParams(double buildDramBudgetGb) {
        final Map<String, Object> buildParams = new HashMap<>();
        buildParams.put(KNNConstants.METHOD_PARAMETER_SPACE_TYPE, "L2");
        buildParams.put(KNNConstants.DISKANN_MAX_DEGREE, 48);
        buildParams.put(KNNConstants.DISKANN_SEARCH_LIST_SIZE, 128);
        buildParams.put(KNNConstants.DISKANN_BUILD_DRAM_BUDGET_GB, buildDramBudgetGb);
        buildParams.put(KNNConstants.DISKANN_PQ_CODE_BUDGET_GB_RATIO, 0.125);
        buildParams.put(KNNConstants.DISKANN_SEARCH_CACHE_BUDGET_GB_RATIO, 0.0);
        buildParams.put(KNNConstants.DISKANN_DISK_PQ_DIMS, 0);
        buildParams.put(KNNConstants.DISKANN_ACCELERATE_BUILD, false);
        return buildParams;
    }

    private long[] toBitmapFilter(int... allowedDocIds) {
        int maxDocId = 0;
        for (int allowedDocId : allowedDocIds) {
            maxDocId = Math.max(maxDocId, allowedDocId);
        }
        long[] bitmap = new long[(maxDocId / Long.SIZE) + 1];
        for (int allowedDocId : allowedDocIds) {
            bitmap[allowedDocId / Long.SIZE] |= 1L << (allowedDocId % Long.SIZE);
        }
        return bitmap;
    }
}
