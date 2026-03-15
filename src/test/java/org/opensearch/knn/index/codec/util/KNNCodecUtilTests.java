/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.codec.util;

import junit.framework.TestCase;
import lombok.SneakyThrows;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.junit.Assert;
import org.opensearch.knn.index.VectorDataType;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.vectorvalues.KNNVectorValues;
import org.opensearch.knn.index.vectorvalues.KNNVectorValuesFactory;
import org.opensearch.knn.index.vectorvalues.TestVectorValues;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.opensearch.knn.index.codec.util.KNNCodecUtil.calculateArraySize;
import static org.opensearch.knn.index.codec.util.KNNCodecUtil.initializeVectorValues;

public class KNNCodecUtilTests extends TestCase {

    public void testCalculateArraySize() {
        int numVectors = 4;
        int vectorLength = 10;

        // Float data type
        VectorDataType vectorDataType = VectorDataType.FLOAT;
        assertEquals(160, calculateArraySize(numVectors, vectorLength, vectorDataType));

        // Byte data type
        vectorDataType = VectorDataType.BYTE;
        assertEquals(40, calculateArraySize(numVectors, vectorLength, vectorDataType));

        // Binary data type
        vectorDataType = VectorDataType.BINARY;
        assertEquals(40, calculateArraySize(numVectors, vectorLength, vectorDataType));
    }

    public void testGetKNNEngines() {
        SegmentInfo segmentInfo = mock(SegmentInfo.class);
        Directory directory = mock(Directory.class);
        KNNEngine knnEngine = KNNEngine.FAISS;
        Set<String> SEGMENT_MULTI_FIELD_FILES_FAISS = Set.of("_0.cfe", "_0_2011_long_target_field.faissc", "_0_2011_target_field.faissc");
        when(segmentInfo.getUseCompoundFile()).thenReturn(true);
        when(segmentInfo.files()).thenReturn(SEGMENT_MULTI_FIELD_FILES_FAISS);
        List<String> engineFiles = KNNCodecUtil.getEngineFiles(knnEngine.getExtension(), "target_field", segmentInfo, directory);
        assertEquals(engineFiles.size(), 2);
        assertTrue(engineFiles.get(0).equals("_0_2011_target_field.faissc"));
    }

    public void testGetKNNEnginesNonCompound() {
        SegmentInfo segmentInfo = mock(SegmentInfo.class);
        Directory directory = mock(Directory.class);
        KNNEngine knnEngine = KNNEngine.FAISS;
        Set<String> segmentFiles = Set.of("_0.cfe", "_0_2011_target_field.faiss", "_0_2011_long_target_field.faiss");
        when(segmentInfo.getUseCompoundFile()).thenReturn(false);
        when(segmentInfo.files()).thenReturn(segmentFiles);
        List<String> engineFiles = KNNCodecUtil.getEngineFiles(knnEngine.getExtension(), "target_field", segmentInfo, directory);
        assertEquals(2, engineFiles.size());
        assertTrue(engineFiles.contains("_0_2011_target_field.faiss"));
    }

    public void testGetKNNEnginesKnowhereManifestNonCompound() throws Exception {
        Directory directory = new ByteBuffersDirectory();
        String manifestFile = "_0_10_my_field.knowhere";
        String diskannFile = "_0_10_my_field.diskann_pq_pivots.bin";
        writeManifest(directory, manifestFile, diskannFile);

        SegmentInfo segmentInfo = mock(SegmentInfo.class);
        when(segmentInfo.getUseCompoundFile()).thenReturn(false);
        when(segmentInfo.files()).thenReturn(Set.of(manifestFile));

        List<String> engineFiles = KNNCodecUtil.getEngineFiles(KNNEngine.KNOWHERE.getExtension(), "my_field", segmentInfo, directory);
        assertEquals(List.of(diskannFile), engineFiles);
    }

    public void testGetKNNEnginesKnowhereManifestCompound() throws Exception {
        Directory directory = new ByteBuffersDirectory();
        String manifestFile = "_0_10_my_field.knowherec";
        String diskannFile = "_0_10_my_field.diskann_pq_pivots.bin";
        writeManifest(directory, manifestFile, diskannFile);

        SegmentInfo segmentInfo = mock(SegmentInfo.class);
        when(segmentInfo.getUseCompoundFile()).thenReturn(true);
        when(segmentInfo.files()).thenReturn(Set.of(manifestFile));

        List<String> engineFiles = KNNCodecUtil.getEngineFiles(KNNEngine.KNOWHERE.getExtension(), "my_field", segmentInfo, directory);
        assertEquals(List.of(diskannFile + "c"), engineFiles);
    }

    private void writeManifest(Directory directory, String manifestFileName, String diskannFileName) throws Exception {
        String json = "{"
            + "\"version\":1,"
            + "\"engine\":\"knowhere\","
            + "\"method\":\"diskann\","
            + "\"index_prefix\":\"_0_10_my_field.diskann\","
            + "\"files\":[{\"name\":\"" + diskannFileName + "\",\"required\":true}]"
            + "}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        try (IndexOutput output = directory.createOutput(manifestFileName, IOContext.DEFAULT)) {
            output.writeBytes(bytes, 0, bytes.length);
        }
    }

    @SneakyThrows
    public void testInitializeVectorValues_whenValidVectorValues_thenSuccess() {
        // Give
        final List<float[]> floatArray = List.of(new float[] { 1, 2 }, new float[] { 2, 3 });
        final int dimension = floatArray.get(0).length;
        final TestVectorValues.PreDefinedFloatVectorValues randomVectorValues = new TestVectorValues.PreDefinedFloatVectorValues(
            floatArray
        );
        final KNNVectorValues<float[]> knnVectorValues = KNNVectorValuesFactory.getVectorValues(VectorDataType.FLOAT, randomVectorValues);

        // When
        initializeVectorValues(knnVectorValues);

        // Then
        Assert.assertNotEquals(-1, knnVectorValues.docId());
        Assert.assertArrayEquals(floatArray.get(0), knnVectorValues.getVector(), 0.001f);
        assertEquals(dimension, knnVectorValues.dimension());
    }

    @SneakyThrows
    public void testInitializeVectorValues_whenNoDocs_thenSuccess() {
        // Give
        final List<float[]> floatArray = Collections.emptyList();
        final TestVectorValues.PreDefinedFloatVectorValues randomVectorValues = new TestVectorValues.PreDefinedFloatVectorValues(
            floatArray
        );
        final KNNVectorValues<float[]> knnVectorValues = KNNVectorValuesFactory.getVectorValues(VectorDataType.FLOAT, randomVectorValues);

        // When
        initializeVectorValues(knnVectorValues);
        // Then
        Assert.assertEquals(DocIdSetIterator.NO_MORE_DOCS, knnVectorValues.docId());
    }
}
