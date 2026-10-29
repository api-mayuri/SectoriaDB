package org.example.sectoriadb.metastore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A metadata file of another format version is refused with the reason, never treated as corrupt or empty. */
class FormatVersionTest {

    @TempDir
    Path dir;

    @Test
    void aFileOfANewerFormatIsRefusedAndLeftAsItIs() throws Exception {
        Path file = dir.resolve("d.db");
        try (MetaStore s = MetaStore.open(file, MetaStoreOptions.defaults().pageSize(512).fsync(false))) {
            s.writeVoid(tx -> tx.tree("t").put(new byte[]{1}, new byte[]{2}));
        }
        // rewrite both meta pages as a future version would: same magic, version 2, valid checksum
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            for (long slot = 0; slot < 2; slot++) {
                ByteBuffer p = ByteBuffer.allocate(512);
                ch.read(p, slot * 512);
                byte[] page = p.array();
                ByteBuffer.wrap(page).putInt(8, 2);
                ByteBuffer.wrap(page).putInt(48, Pager.crc(page, 0, 48));
                ch.write(ByteBuffer.wrap(page), slot * 512);
            }
        }
        long size = Files.size(file);
        UnsupportedFormatException e = assertThrows(UnsupportedFormatException.class,
                () -> MetaStore.open(file, MetaStoreOptions.defaults().fsync(false)));
        assertEquals(2, e.found());
        assertEquals(MetaPage.FORMAT_VERSION, e.supported());
        assertEquals(size, Files.size(file), "not truncated, not re-initialised");
    }
}
