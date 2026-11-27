package com.hardcoremisiones.hm2.core;
public final class ChunkMiningMarksTest {
    private static int n;
    private static void check(boolean yes) { if (!yes) throw new AssertionError("Chunk marks test " + n); n++; }
    public static void main(String[] args) {
        int a=ChunkMiningMarks.index(0,-64,0,-64,320);
        int b=ChunkMiningMarks.index(15,319,15,-64,320);
        check(a==0);check(b==98303);
        byte[] data=ChunkMiningMarks.update(null,a,true);
        check(ChunkMiningMarks.contains(data,a)); check(!ChunkMiningMarks.contains(data,b));
        data=ChunkMiningMarks.update(data,b,true);
        check(ChunkMiningMarks.contains(data,a));check(ChunkMiningMarks.contains(data,b));
        byte[] before=data.clone();data=ChunkMiningMarks.update(data,a,false);
        check(!ChunkMiningMarks.contains(data,a));check(ChunkMiningMarks.contains(data,b));
        check(before.length>=1);
        data=ChunkMiningMarks.update(data,b,false);check(data.length==0);
        try {ChunkMiningMarks.index(16,0,0,0,320);throw new AssertionError();}
        catch(IllegalArgumentException expected){n++;}
        try {ChunkMiningMarks.index(1,320,1,-64,320);throw new AssertionError();}
        catch(IllegalArgumentException expected){n++;}
        System.out.println("F1_CHUNK_MARKS_PASS="+n);
    }
}
