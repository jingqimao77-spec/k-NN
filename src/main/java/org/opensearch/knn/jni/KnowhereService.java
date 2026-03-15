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
     * Create an index for knowhere engine.
     *
     * @param ids ids of documents
     * @param vectorsAddress address of native memory where vectors are stored
     * @param dim dimension of the vector to be indexed
     * @param indexPath path to save the index (for DiskANN, this is the prefix)
     * @param parameters build parameters
     */
    public static native void createIndex(int[] ids, long vectorsAddress, int dim, String indexPath, Map<String, Object> parameters);

    /**
     * Load an index into memory.
     *
     * @param indexPath path to index file or prefix
     * @param parameters load parameters (e.g., mmap)
     * @return pointer to the loaded index
     */
    public static native long loadIndex(String indexPath, Map<String, Object> parameters);

    /**
     * Query a knowhere index.
     *
     * @param indexPointer pointer to index in memory
     * @param queryVector vector to be used for query
     * @param k neighbors to be returned
     * @param methodParameters query parameters
     * @return KNNQueryResult array of k neighbors
     */
    public static native KNNQueryResult[] queryIndex(long indexPointer, float[] queryVector, int k, Map<String, ?> methodParameters);

    /**
     * Free native memory pointer
     *
     * @param indexPointer pointer to the index to be freed
     */
    public static native void free(long indexPointer);
}
