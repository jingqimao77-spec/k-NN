/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.store;

import lombok.NonNull;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IOContext;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This class contains a Lucene's IndexInput with a reader buffer.
 * A Java reference of this class will be passed to native engines, then 'copyBytes' method will be
 * called by native engine via JNI API.
 * Therefore, this class servers as a read layer in native engines to read the bytes it wants.
 */
public class IndexInputWithBuffer {
    private static final Pattern FS_PATH_PATTERN = Pattern.compile("path=\"([^\"]+)\"");
    private final IndexInput indexInput;
    private final Directory directory;
    private final IOContext ioContext;
    private final String fileName;
    private final long contentLength;
    // 64K buffer.
    private final byte[] buffer = new byte[64 * 1024];

    public IndexInputWithBuffer(@NonNull IndexInput indexInput) {
        this(indexInput, null, null, null);
    }

    public IndexInputWithBuffer(@NonNull IndexInput indexInput, Directory directory, IOContext ioContext, String fileName) {
        this.indexInput = indexInput;
        this.directory = directory;
        this.ioContext = ioContext;
        this.fileName = fileName;
        this.contentLength = indexInput.length();
    }

    /**
     * This method will be invoked in native engines via JNI API.
     * Then it will call IndexInput to read required bytes then copy them into a read buffer.
     *
     * @param nbytes Desired number of bytes to be read.
     * @return The number of read bytes in a buffer.
     * @throws IOException
     */
    private int copyBytes(long nbytes) throws IOException {
        final int readBytes = (int) Math.min(nbytes, buffer.length);
        indexInput.readBytes(buffer, 0, readBytes);
        return readBytes;
    }

    private long remainingBytes() {
        return contentLength - indexInput.getFilePointer();
    }

    public String getFilePath() {
        Matcher matcher = FS_PATH_PATTERN.matcher(indexInput.toString());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public String toString() {
        return "{indexInput=" + indexInput + ", len(buffer)=" + buffer.length + "}";
    }
}
