package com.hardcoremisiones.hm2.core;

import java.util.BitSet;

/**
 * Posiciones de bloques no naturales dentro de un chunk. Los bytes se conservan
 * en el PersistentDataContainer DEL CHUNK, junto con los propios bloques.
 * No se archivan millones de posiciones en un YAML independiente.
 */
public final class ChunkMiningMarks {
    private ChunkMiningMarks() { }

    public static int index(int localX, int y, int localZ, int minY, int maxY) {
        if (localX < 0 || localX > 15 || localZ < 0 || localZ > 15 || y < minY || y >= maxY
                || maxY <= minY || (long)(maxY - minY) * 256 > 1_048_576)
            throw new IllegalArgumentException("Coordenadas fuera del chunk");
        return (y - minY) * 256 + localZ * 16 + localX;
    }

    public static boolean contains(byte[] bytes, int index) {
        if (index < 0) throw new IllegalArgumentException("Índice negativo");
        return BitSet.valueOf(bytes == null ? new byte[0] : bytes).get(index);
    }

    public static byte[] update(byte[] bytes, int index, boolean marked) {
        if (index < 0) throw new IllegalArgumentException("Índice negativo");
        BitSet bits = BitSet.valueOf(bytes == null ? new byte[0] : bytes);
        bits.set(index, marked);
        return bits.toByteArray();
    }
}
