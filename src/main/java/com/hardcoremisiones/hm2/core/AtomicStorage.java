package com.hardcoremisiones.hm2.core;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Writes a complete UTF-8 snapshot using same-directory atomic replacements.
 * A previous snapshot is copied to a staged backup and forced before replacement;
 * a failed backup publication must not overwrite the live snapshot. Callers serialize
 * writes for each aggregate. This does not promise power-loss durability of directory
 * entries on every filesystem (including Windows), nor multi-file atomicity.
 */
public final class AtomicStorage {
    private AtomicStorage() { }

    /** Persist a snapshot without falling back to a non-atomic live-file move.
     * @param target aggregate path; target and immediate parent must not be symlinks
     * @param payload full snapshot to publish as UTF-8
     * @throws IOException if staging, forcing, backup or replacement fails
     */
    public static void write(Path target, String payload) throws IOException {
        Path file = target.toAbsolutePath().normalize();
        Path parent = file.getParent();
        Files.createDirectories(parent);
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(parent))
            throw new IOException("Se rechazó enlace simbólico para estado crítico");
        Path temp = Files.createTempFile(parent, file.getFileName().toString() + ".", ".tmp");
        try {
            byte[] data = payload.getBytes(StandardCharsets.UTF_8);
            try (FileChannel ch = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) ch.write(buffer);
                ch.force(true);
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                Path bak = file.resolveSibling(file.getFileName() + ".bak");
                if (Files.isSymbolicLink(bak)) throw new IOException("Backup simbólico rechazado");
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || (Files.exists(bak, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(bak, LinkOption.NOFOLLOW_LINKS)))
                    throw new IOException("Estado o backup no es archivo regular");
                Path stagedBackup=Files.createTempFile(parent,file.getFileName().toString()+".bak.",".tmp");
                try {
                    Files.copy(file,stagedBackup,StandardCopyOption.REPLACE_EXISTING);
                    try(FileChannel ch=FileChannel.open(stagedBackup,StandardOpenOption.WRITE)){ch.force(true);}
                    Files.move(stagedBackup,bak,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
                } finally { Files.deleteIfExists(stagedBackup); }
            }
            // No degradar silenciosamente a MOVE no-atómico para un ledger crítico.
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
