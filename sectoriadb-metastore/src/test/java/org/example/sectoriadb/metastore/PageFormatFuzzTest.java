package org.example.sectoriadb.metastore;

import com.code_intelligence.jazzer.junit.FuzzTest;

import java.nio.ByteBuffer;

/**
 * Coverage-guided fuzzing of what is read back from sectoria.db. A damaged or hostile page must be reported as
 * {@link CorruptedPageException} (or, for a meta page, be invalid / of another version): never an unchecked
 * index error, a negative allocation or a huge one. {@code mvn test} replays the corpus; {@code JAZZER_FUZZ=1} fuzzes.
 */
class PageFormatFuzzTest {

    @FuzzTest(maxDuration = "2m")
    void treePageDecode(byte[] data) {
        byte[] page = new byte[Math.max(Pager.HEADER, Math.min(data.length, 65536))];
        System.arraycopy(data, 0, page, 0, Math.min(data.length, page.length));
        try {
            Node.decode(7, page);
        } catch (CorruptedPageException expected) {
            // the contract: a page that does not decode is a corrupt page
        }
    }

    @FuzzTest(maxDuration = "1m")
    void metaPageParse(byte[] data) {
        byte[] h = new byte[MetaPage.META_SIZE];
        System.arraycopy(data, 0, h, 0, Math.min(data.length, h.length));
        if (data.length > 0 && data[0] % 2 == 0) {   // half of the inputs with a valid magic and checksum
            ByteBuffer.wrap(h).putLong(0, MetaPage.MAGIC);
            ByteBuffer.wrap(h).putInt(48, Pager.crc(h, 0, 48));
        }
        try {
            MetaPage.parse(h);
        } catch (UnsupportedFormatException expected) {
            // another format version
        }
    }
}
