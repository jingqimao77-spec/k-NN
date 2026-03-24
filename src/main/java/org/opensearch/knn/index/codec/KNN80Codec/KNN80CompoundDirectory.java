/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.knn.index.codec.KNN80Codec;

import lombok.Getter;
import org.apache.lucene.codecs.CompoundDirectory;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.opensearch.knn.index.engine.KNNEngine;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

public class KNN80CompoundDirectory extends CompoundDirectory {

    @Getter
    private CompoundDirectory delegate;
    @Getter
    private Directory dir;

    public KNN80CompoundDirectory(CompoundDirectory delegate, Directory dir) {
        this.delegate = delegate;
        this.dir = dir;
    }

    @Override
    public void checkIntegrity() throws IOException {
        delegate.checkIntegrity();
    }

    @Override
    public String[] listAll() throws IOException {
        Set<String> files = new LinkedHashSet<>(Arrays.asList(delegate.listAll()));
        files.addAll(Arrays.asList(dir.listAll()));
        return files.toArray(String[]::new);
    }

    @Override
    public long fileLength(String name) throws IOException {
        try {
            return delegate.fileLength(name);
        } catch (IOException delegateException) {
            try {
                return dir.fileLength(name);
            } catch (IOException ignored) {
                throw delegateException;
            }
        }
    }

    @Override
    public IndexInput openInput(String name, IOContext context) throws IOException {
        if (KNNEngine.getEnginesThatCreateCustomSegmentFiles().stream().anyMatch(engine -> name.endsWith(engine.getCompoundExtension()))) {
            return dir.openInput(name, context);
        }
        try {
            return delegate.openInput(name, context);
        } catch (IOException delegateException) {
            try {
                return dir.openInput(name, context);
            } catch (IOException ignored) {
                throw delegateException;
            }
        }
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public Set<String> getPendingDeletions() throws IOException {
        return delegate.getPendingDeletions();
    }

}
