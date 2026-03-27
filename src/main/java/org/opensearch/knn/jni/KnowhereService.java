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

import org.opensearch.knn.common.KNNConstants;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.query.KNNQueryResult;
import org.opensearch.knn.index.store.IndexInputWithBuffer;
import org.opensearch.knn.index.store.IndexOutputWithBuffer;

import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Map;

/**
 * Service to interact with knowhere jni layer.
 */
class KnowhereService {

    static {
        AccessController.doPrivileged((PrivilegedAction<Void>) () -> {
            System.loadLibrary(KNNConstants.KNOWHERE_JNI_LIBRARY_NAME);
            initLibrary();
            KNNEngine.KNOWHERE.setInitialized(true);
            return null;
        });
    }

    /**
     * Initialize knowhere library
     */
    public static native void initLibrary();

    /**
     * Create a knowhere index and persist it through Lucene's Directory integration.
     *
     * @param ids ids of documents
     * @param vectorsAddress address of native memory where vectors are stored
     * @param dim dimension of the vector to be indexed
     * @param output index output wrapper that carries Lucene file-management context
     * @param parameters build parameters
     */
    public static native void createIndex(int[] ids, long vectorsAddress, int dim, IndexOutputWithBuffer output, Map<String, Object> parameters);

    /**
     * Load a knowhere index through Lucene's Directory integration.
     *
     * @param readStream input wrapper that carries Lucene file-management context
     * @param parameters load parameters (e.g., mmap)
     * @return pointer to the loaded index
     */
    public static native long loadIndex(IndexInputWithBuffer readStream, Map<String, Object> parameters);

    /**
     * Query a knowhere index.
     *
     * @param indexPointer pointer to index in memory
     * @param queryVector vector to be used for query
     * @param k neighbors to be returned
     * @param methodParameters query parameters
     * @return KNNQueryResult array of k neighbors
     */
    public static native KNNQueryResult[] queryIndex(
        long indexPointer,
        float[] queryVector,
        int k,
        Map<String, ?> methodParameters,
        long[] filteredIds,
        int filterIdsType,
        int[] parentIds
    );

    /**
     * Free native memory pointer
     *
     * @param indexPointer pointer to the index to be freed
     */
    public static native void free(long indexPointer);
}
