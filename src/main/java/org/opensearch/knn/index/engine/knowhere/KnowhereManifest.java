/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.engine.knowhere;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.opensearch.common.collect.Tuple;
import org.opensearch.common.xcontent.XContentHelper;
import org.opensearch.core.common.bytes.BytesReference;
import org.opensearch.core.xcontent.MediaType;
import org.opensearch.core.xcontent.MediaTypeRegistry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class KnowhereManifest {
    private static final String FILES_FIELD = "files";
    private static final String NAME_FIELD = "name";

    private KnowhereManifest() {}

    public static List<String> readDiskannFiles(Directory directory, String manifestFileName) throws IOException {
        try (IndexInput input = directory.openInput(manifestFileName, IOContext.READONCE)) {
            int length = Math.toIntExact(input.length());
            byte[] bytes = new byte[length];
            input.readBytes(bytes, 0, length);
            Tuple<? extends MediaType, Map<String, Object>> mapTuple = XContentHelper.convertToMap(
                BytesReference.fromByteBuffer(ByteBuffer.wrap(bytes)),
                true,
                MediaTypeRegistry.JSON
            );
            Object filesObject = mapTuple.v2().get(FILES_FIELD);
            if (!(filesObject instanceof List<?> files)) {
                throw new IllegalStateException("Invalid knowhere manifest: missing files array");
            }
            List<String> fileNames = new ArrayList<>(files.size());
            for (Object entry : files) {
                if (!(entry instanceof Map<?, ?> entryMap)) {
                    throw new IllegalStateException("Invalid knowhere manifest: file entry is not an object");
                }
                Object nameObject = entryMap.get(NAME_FIELD);
                if (!(nameObject instanceof String name)) {
                    throw new IllegalStateException("Invalid knowhere manifest: file entry missing name");
                }
                fileNames.add(name);
            }
            return fileNames;
        }
    }
}
