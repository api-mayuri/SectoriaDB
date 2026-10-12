package org.example.sectoriadb;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.example.sectoriadb.model.BlobFile;
import org.example.sectoriadb.service.impl.FileChannelStorageIOEngine;
import org.example.sectoriadb.service.impl.SmallObjectBlob;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A thread interrupted inside channel IO closes the shared FileChannel; the engines must reopen it and carry on. */
@Timeout(60)
class InterruptSurvivalTest {
    @TempDir
    Path tmp;

    /** Runs the body on a thread whose interrupt flag is set; reports the failure and whether the flag survived. */
    static void runInterrupted(ThrowingRunnable body, AtomicReference<Throwable> failure, AtomicBoolean flagKept) throws Exception {
        Thread t = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                body.run();
            } catch (Throwable e) {
                failure.set(e);
            }
            flagKept.set(Thread.currentThread().isInterrupted());
        });
        t.start();
        t.join();
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    @RepeatedTest(5)
    void ioEngineReopensTheChannelAfterAnInterruptedOperation() throws Exception {
        BlobFile bf = new BlobFile("x", tmp.resolve("x.raw"), 0);
        byte[] data = new byte[4096];
        new Random(3).nextBytes(data);
        try (FileChannelStorageIOEngine e = new FileChannelStorageIOEngine(true)) {
            e.writeChunk(bf, 0, ByteBuffer.wrap(data));

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean flagKept = new AtomicBoolean();
            runInterrupted(() -> {
                e.writeChunk(bf, 4096, ByteBuffer.wrap(data));
                e.force(bf);
                assertArrayEquals(data, e.readChunk(bf, 4096, 4096).array());
            }, failure, flagKept);
            assertNull(failure.get());
            assertTrue(flagKept.get(), "the caller's interrupt is preserved");

            // other threads keep working with the (re-opened) channel
            assertArrayEquals(data, e.readChunk(bf, 0, 4096).array());
            e.writeChunk(bf, 8192, ByteBuffer.wrap(data));
            e.force(bf);
            assertArrayEquals(data, e.readChunk(bf, 8192, 4096).array());
        }
    }

    @Test
    void ioEngineStaysClosedAfterClose() throws Exception {
        BlobFile bf = new BlobFile("y", tmp.resolve("y.raw"), 0);
        FileChannelStorageIOEngine e = new FileChannelStorageIOEngine(false);
        e.writeChunk(bf, 0, ByteBuffer.wrap(new byte[16]));
        e.close();
        org.junit.jupiter.api.Assertions.assertThrows(java.nio.channels.ClosedChannelException.class,
                () -> e.readChunk(bf, 0, 16));
    }

    @RepeatedTest(5)
    void smallObjectBlobReopensTheChannelAfterAnInterruptedAppend() throws Exception {
        Path p = tmp.resolve("s.sob");
        SmallObjectBlob.create(p);
        byte[] a = new byte[300];
        byte[] b = new byte[500];
        new Random(4).nextBytes(a);
        new Random(5).nextBytes(b);
        try (SmallObjectBlob blob = SmallObjectBlob.open("t", p, true, 1L << 30)) {
            SmallObjectBlob.Location la = blob.append(a, 0);

            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean flagKept = new AtomicBoolean();
            SmallObjectBlob.Location[] lb = new SmallObjectBlob.Location[1];
            runInterrupted(() -> {
                lb[0] = blob.append(b, 1);
                assertArrayEquals(b, blob.read(lb[0].offset(), lb[0].length(), lb[0].crc32c()));
            }, failure, flagKept);
            assertNull(failure.get());
            assertTrue(flagKept.get());

            assertArrayEquals(a, blob.read(la.offset(), la.length(), la.crc32c()));
            assertEquals(true, blob.markDeleted(la.offset()));
            SmallObjectBlob.Location lc = blob.append(a, 2);
            assertArrayEquals(a, blob.read(lc.offset(), lc.length(), lc.crc32c()));
        }
        try (SmallObjectBlob again = SmallObjectBlob.open("t", p, true, 1L << 30)) {
            assertEquals(2, again.stats().liveRecords());
        }
    }
}
