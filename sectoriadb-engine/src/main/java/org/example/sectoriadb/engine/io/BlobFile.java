package org.example.sectoriadb.engine.io;

import java.nio.file.Path;

/**
 * A concrete {@code .raw} data file inside a pool.
 *
 * @param id   unique file id (for example a UUID or a sequence number)
 * @param path full physical path of the file on disk
 * @param size current file size in bytes
 */
public record BlobFile(
        String id,
        Path path,
        long size
) {}
