/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.store;

import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.store.IOContext;
import org.opensearch.knn.common.exception.TerminalIOException;

import java.io.IOException;
import java.io.InputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wrapper around {@link IndexOutput} to perform writes in a buffered manner. This class is created per flush/merge, and may be used twice if
 * {@link org.opensearch.knn.index.codec.nativeindex.remote.RemoteIndexBuildStrategy} needs to fall back to a different build strategy.
 */
public class IndexOutputWithBuffer {
    private static final Pattern FS_PATH_PATTERN = Pattern.compile("path=\"([^\"]+)\"");
    // Underlying `IndexOutput` obtained from Lucene's Directory.
    private final IndexOutput indexOutput;
    // Optional Lucene context used by engines that manage multiple files.
    private final Directory directory;
    private final IOContext ioContext;
    private final String fileName;
    // Write buffer. Native engine will copy bytes into this buffer.
    // Allocating 64KB here since it show better performance in NMSLIB with the size. (We had slightly improvement in FAISS than having 4KB)
    // NMSLIB writes an adjacent list size first, then followed by serializing the list. Since we usually have more adjacent lists, having
    // 64KB to accumulate bytes as possible to reduce the times of calling `writeBytes`.
    private static final int CHUNK_SIZE = 64 * 1024;
    private final byte[] buffer;

    public IndexOutputWithBuffer(IndexOutput indexOutput) {
        this(indexOutput, null, null, null);
    }

    public IndexOutputWithBuffer(IndexOutput indexOutput, Directory directory, IOContext ioContext, String fileName) {
        this.indexOutput = indexOutput;
        this.directory = directory;
        this.ioContext = ioContext;
        this.fileName = fileName;
        this.buffer = new byte[CHUNK_SIZE];
    }

    // This method will be called in JNI layer which precisely knows
    // the amount of bytes need to be written.
    public void writeBytes(int length) {
        try {
            // Delegate Lucene `indexOuptut` to write bytes.
            indexOutput.writeBytes(buffer, 0, length);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Writes to the {@link IndexOutput} by buffering bytes into a new buffer of custom size.
     *
     * @param inputStream       The stream from which we are reading bytes to write
     * @throws IOException
     * @see IndexOutputWithBuffer#writeFromStreamWithBuffer(InputStream, byte[])
     */
    public void writeFromStreamWithBuffer(InputStream inputStream, int size) throws IOException {
        byte[] streamBuffer = new byte[size];
        writeFromStreamWithBuffer(inputStream, streamBuffer);
    }

    /**
     * Writes to the {@link IndexOutput} by buffering bytes with @param outputBuffer. This method allows
     * {@link org.opensearch.knn.index.codec.nativeindex.remote.RemoteIndexBuildStrategy} to provide a separate, larger buffer as that buffer is for buffering
     * bytes downloaded from the repository, so it may be more performant to use a larger buffer.
     * We do not change the size of the existing buffer in case a fallback to the existing build strategy is needed.
     *
     * @param inputStream       The stream from which we are reading bytes to write
     * @param outputBuffer      The buffer used to buffer bytes
     * @throws IOException
     * @see IndexOutputWithBuffer#writeFromStreamWithBuffer(InputStream, int)
     */
    private void writeFromStreamWithBuffer(InputStream inputStream, byte[] outputBuffer) throws IOException {
        int bytesRead = 0;
        // InputStream uses -1 indicates there are no more bytes to be read
        while (bytesRead != -1) {
            // Try to read enough bytes to fill the entire buffer. The actual amount read may be less.
            bytesRead = inputStream.read(outputBuffer, 0, outputBuffer.length);
            assert bytesRead <= outputBuffer.length;
            // However many bytes we read, write it to the IndexOutput if != -1
            if (bytesRead != -1) {
                try {
                    indexOutput.writeBytes(outputBuffer, 0, bytesRead);
                } catch (IOException e) {
                    throw new TerminalIOException("Failed to write to indexOutput", e);
                }
            }
        }
    }

    public String getFilePath() {
        Matcher matcher = FS_PATH_PATTERN.matcher(indexOutput.toString());
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public String toString() {
        return "{indexOutput=" + indexOutput + ", len(buffer)=" + buffer.length + "}";
    }
}
