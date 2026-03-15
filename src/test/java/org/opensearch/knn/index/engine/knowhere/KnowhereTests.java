/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.engine.knowhere;

import org.opensearch.Version;
import org.opensearch.common.ValidationException;
import org.opensearch.common.xcontent.XContentFactory;
import org.opensearch.core.xcontent.XContentBuilder;
import org.opensearch.knn.KNNTestCase;
import org.opensearch.knn.index.SpaceType;
import org.opensearch.knn.index.VectorDataType;
import org.opensearch.knn.index.engine.KNNEngine;
import org.opensearch.knn.index.engine.KNNMethodConfigContext;
import org.opensearch.knn.index.engine.KNNMethodContext;
import org.opensearch.knn.index.mapper.Mode;

import java.io.IOException;
import java.util.Map;

import static org.opensearch.knn.common.KNNConstants.DISKANN_MAX_DEGREE;
import static org.opensearch.knn.common.KNNConstants.DISKANN_SEARCH_LIST_SIZE;
import static org.opensearch.knn.common.KNNConstants.METHOD_DISKANN;
import static org.opensearch.knn.common.KNNConstants.METHOD_PARAMETER_SPACE_TYPE;
import static org.opensearch.knn.common.KNNConstants.NAME;
import static org.opensearch.knn.common.KNNConstants.PARAMETERS;

public class KnowhereTests extends KNNTestCase {

    public void testDiskANNMethodValidationValid() throws IOException {
        KNNMethodConfigContext knnMethodConfigContext = KNNMethodConfigContext.builder()
            .versionCreated(Version.CURRENT)
            .dimension(10)
            .vectorDataType(VectorDataType.FLOAT)
            .build();

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .field(NAME, METHOD_DISKANN)
            .field(METHOD_PARAMETER_SPACE_TYPE, SpaceType.L2.getValue())
            .startObject(PARAMETERS)
            .field(DISKANN_MAX_DEGREE, 64)
            .field(DISKANN_SEARCH_LIST_SIZE, 128)
            .endObject()
            .endObject();
        Map<String, Object> in = xContentBuilderToMap(xContentBuilder);
        KNNMethodContext knnMethodContext = KNNMethodContext.parse(in);
        assertNull(KNNEngine.KNOWHERE.validateMethod(knnMethodContext, knnMethodConfigContext));
    }

    public void testDiskANNMethodValidationInvalidParam() throws IOException {
        KNNMethodConfigContext knnMethodConfigContext = KNNMethodConfigContext.builder()
            .versionCreated(Version.CURRENT)
            .dimension(10)
            .vectorDataType(VectorDataType.FLOAT)
            .build();

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .field(NAME, METHOD_DISKANN)
            .startObject(PARAMETERS)
            .field("invalid", 10)
            .endObject()
            .endObject();
        Map<String, Object> in = xContentBuilderToMap(xContentBuilder);
        KNNMethodContext knnMethodContext = KNNMethodContext.parse(in);
        knnMethodContext.setSpaceType(SpaceType.L2);
        assertNotNull(KNNEngine.KNOWHERE.validateMethod(knnMethodContext, knnMethodConfigContext));
    }

    public void testDiskANNMethodValidationInvalidValue() throws IOException {
        KNNMethodConfigContext knnMethodConfigContext = KNNMethodConfigContext.builder()
            .versionCreated(Version.CURRENT)
            .dimension(10)
            .vectorDataType(VectorDataType.FLOAT)
            .build();

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .field(NAME, METHOD_DISKANN)
            .startObject(PARAMETERS)
            .field(DISKANN_MAX_DEGREE, -1)
            .endObject()
            .endObject();
        Map<String, Object> in = xContentBuilderToMap(xContentBuilder);
        KNNMethodContext knnMethodContext = KNNMethodContext.parse(in);
        knnMethodContext.setSpaceType(SpaceType.L2);
        assertNotNull(KNNEngine.KNOWHERE.validateMethod(knnMethodContext, knnMethodConfigContext));
    }

    public void testDiskANNMethodValidationUnsupportedSpace() throws IOException {
        KNNMethodConfigContext knnMethodConfigContext = KNNMethodConfigContext.builder()
            .versionCreated(Version.CURRENT)
            .dimension(10)
            .vectorDataType(VectorDataType.FLOAT)
            .build();

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .field(NAME, METHOD_DISKANN)
            .field(METHOD_PARAMETER_SPACE_TYPE, SpaceType.LINF.getValue())
            .endObject();
        Map<String, Object> in = xContentBuilderToMap(xContentBuilder);
        KNNMethodContext knnMethodContext = KNNMethodContext.parse(in);
        assertNotNull(KNNEngine.KNOWHERE.validateMethod(knnMethodContext, knnMethodConfigContext));
    }

    public void testDiskANNMethodValidationUnsupportedVectorDataType() throws IOException {
        KNNMethodConfigContext knnMethodConfigContext = KNNMethodConfigContext.builder()
            .versionCreated(Version.CURRENT)
            .dimension(10)
            .vectorDataType(VectorDataType.BYTE)
            .build();

        XContentBuilder xContentBuilder = XContentFactory.jsonBuilder()
            .startObject()
            .field(NAME, METHOD_DISKANN)
            .field(METHOD_PARAMETER_SPACE_TYPE, SpaceType.L2.getValue())
            .endObject();
        Map<String, Object> in = xContentBuilderToMap(xContentBuilder);
        KNNMethodContext knnMethodContext = KNNMethodContext.parse(in);
        ValidationException validationException = KNNEngine.KNOWHERE.validateMethod(knnMethodContext, knnMethodConfigContext);
        assertNotNull(validationException);
    }

    public void testResolveMethodRejectsInMemoryMode() {
        KNNMethodConfigContext knnMethodConfigContext = KNNMethodConfigContext.builder()
            .versionCreated(Version.CURRENT)
            .dimension(10)
            .vectorDataType(VectorDataType.FLOAT)
            .mode(Mode.IN_MEMORY)
            .build();

        ValidationException ex = expectThrows(
            ValidationException.class,
            () -> KNNEngine.KNOWHERE.resolveMethod(null, knnMethodConfigContext, false, SpaceType.L2)
        );
        assertTrue(ex.getMessage().contains("Knowhere engine only supports on_disk mode"));
    }
}
