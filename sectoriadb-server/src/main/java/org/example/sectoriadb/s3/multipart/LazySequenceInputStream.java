package org.example.sectoriadb.s3.multipart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Concatenation of part files; each file is opened only when the previous one is exhausted (one descriptor at a time). */
final class LazySequenceInputStream extends InputStream {
    private final List<Path> files;
    private int next = 0;
    private InputStream current = null;

    LazySequenceInputStream(List<Path> files) {
        this.files = files;
    }

    private boolean advance() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
        if (next >= files.size()) return false;
        current = Files.newInputStream(files.get(next++));
        return true;
    }

    @Override
    public int read() throws IOException {
        while (true) {
            if (current == null && !advance()) return -1;
            int b = current.read();
            if (b >= 0) return b;
            if (!advance()) return -1;
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len == 0) return 0;
        while (true) {
            if (current == null && !advance()) return -1;
            int n = current.read(b, off, len);
            if (n > 0) return n;
            if (!advance()) return -1;
        }
    }

    @Override
    public void close() throws IOException {
        if (current != null) {
            current.close();
            current = null;
        }
        next = files.size();
    }
}
