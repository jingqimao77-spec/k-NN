/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.codec.KNN80Codec;

import org.apache.lucene.codecs.Codec;
import org.apache.lucene.codecs.CompoundDirectory;
import org.apache.lucene.codecs.CompoundFormat;
import org.apache.lucene.index.SegmentInfo;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexOutput;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.opensearch.common.util.set.Sets;
import org.opensearch.knn.KNNTestCase;
import org.opensearch.knn.index.codec.KNNCodecTestUtil;
import org.opensearch.knn.index.codec.KNNCodecVersion;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.codec.util.KNNCodecUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class KNN80CompoundFormatTests extends KNNTestCase {

    private static Directory directory;
    private static Codec codec;

    @BeforeClass
    public static void setStaticVariables() {
        directory = newFSDirectory(createTempDir());
        codec = KNNCodecVersion.CURRENT_DEFAULT;
    }

    @AfterClass
    public static void closeStaticVariables() throws IOException {
        directory.close();
    }

    public void testGetCompoundReader() throws IOException {
        CompoundDirectory dir = mock(CompoundDirectory.class);
        CompoundFormat delegate = mock(CompoundFormat.class);
        when(delegate.getCompoundReader(null, null)).thenReturn(dir);
        KNN80CompoundFormat knn80CompoundFormat = new KNN80CompoundFormat(delegate);
        CompoundDirectory knnDir = knn80CompoundFormat.getCompoundReader(null, null);
        assertTrue(knnDir instanceof KNN80CompoundDirectory);
        assertEquals(dir, ((KNN80CompoundDirectory) knnDir).getDelegate());
    }

    public void testWrite() throws IOException {
        // Check that all normal engine files correctly get set to compound extension files after write
        String segmentName = "_test";

        Set<String> segmentFiles = Sets.newHashSet(
            String.format("%s_nmslib1%s", segmentName, KNNEngine.NMSLIB.getExtension()),
            String.format("%s_nmslib2%s", segmentName, KNNEngine.NMSLIB.getExtension()),
            String.format("%s_nmslib3%s", segmentName, KNNEngine.NMSLIB.getExtension()),
            String.format("%s_faiss1%s", segmentName, KNNEngine.FAISS.getExtension()),
            String.format("%s_faiss2%s", segmentName, KNNEngine.FAISS.getExtension()),
            String.format("%s_faiss3%s", segmentName, KNNEngine.FAISS.getExtension())
        );

        SegmentInfo segmentInfo = KNNCodecTestUtil.segmentInfoBuilder()
            .directory(directory)
            .segmentName(segmentName)
            .docsInSegment(segmentFiles.size())
            .codec(codec)
            .build();

        for (String name : segmentFiles) {
            IndexOutput indexOutput = directory.createOutput(name, IOContext.DEFAULT);
            indexOutput.close();
        }
        segmentInfo.setFiles(segmentFiles);

        CompoundFormat delegate = mock(CompoundFormat.class);
        doNothing().when(delegate).write(directory, segmentInfo, IOContext.DEFAULT);

        KNN80CompoundFormat knn80CompoundFormat = new KNN80CompoundFormat(delegate);
        knn80CompoundFormat.write(directory, segmentInfo, IOContext.DEFAULT);

        assertTrue(segmentInfo.files().isEmpty());

        Arrays.stream(directory.listAll()).forEach(filename -> {
            try {
                directory.deleteFile(filename);
            } catch (IOException e) {
                fail(String.format("Failed to delete: %s", filename));
            }
        });
    }

    public void testWriteKnowhereCopiesDiskannFiles() throws IOException {
        String segmentName = "_test";
        String fieldName = "field";

        String manifestFile = KNNCodecUtil.buildEngineFileName(
            segmentName,
            KNNEngine.KNOWHERE.getVersion(),
            fieldName,
            KNNEngine.KNOWHERE.getExtension()
        );
        String diskannFile = String.format("%s_%s_%s.diskann_pq_pivots.bin", segmentName, KNNEngine.KNOWHERE.getVersion(), fieldName);
        String manifestJson = "{"
            + "\"version\":1,"
            + "\"engine\":\"knowhere\","
            + "\"method\":\"diskann\","
            + "\"index_prefix\":\"" + segmentName + "_" + KNNEngine.KNOWHERE.getVersion() + "_" + fieldName + ".diskann\","
            + "\"files\":[{\"name\":\"" + diskannFile + "\",\"required\":true}]"
            + "}";

        SegmentInfo segmentInfo = KNNCodecTestUtil.segmentInfoBuilder()
            .directory(directory)
            .segmentName(segmentName)
            .docsInSegment(1)
            .codec(codec)
            .build();

        try (IndexOutput manifestOutput = directory.createOutput(manifestFile, IOContext.DEFAULT)) {
            byte[] bytes = manifestJson.getBytes(StandardCharsets.UTF_8);
            manifestOutput.writeBytes(bytes, 0, bytes.length);
        }
        IndexOutput diskannOutput = directory.createOutput(diskannFile, IOContext.DEFAULT);
        diskannOutput.close();

        Set<String> segmentFiles = Set.of(manifestFile, diskannFile);
        segmentInfo.setFiles(segmentFiles);

        CompoundFormat delegate = mock(CompoundFormat.class);
        doNothing().when(delegate).write(directory, segmentInfo, IOContext.DEFAULT);

        KNN80CompoundFormat knn80CompoundFormat = new KNN80CompoundFormat(delegate);
        knn80CompoundFormat.write(directory, segmentInfo, IOContext.DEFAULT);

        assertTrue(Arrays.asList(directory.listAll()).contains(manifestFile + "c"));
        assertTrue(Arrays.asList(directory.listAll()).contains(diskannFile + "c"));

        Arrays.stream(directory.listAll()).forEach(filename -> {
            try {
                directory.deleteFile(filename);
            } catch (IOException e) {
                fail(String.format("Failed to delete: %s", filename));
            }
        });
    }

}
