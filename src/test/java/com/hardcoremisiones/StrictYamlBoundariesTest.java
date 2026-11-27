package com.hardcoremisiones;

import com.hardcoremisiones.config.StrictYaml;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Standalone boundaries of the shared administrative and persisted YAML reader. */
public final class StrictYamlBoundariesTest {
    @FunctionalInterface private interface Read { void run() throws IOException; }
    private static void rejects(Read read) throws IOException {
        try { read.run(); } catch(IOException expected) { return; }
        throw new AssertionError("Malformed input accepted");
    }
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("hm2-yaml-limit");
        Path file=dir.resolve("state.yml");
        try {
            byte[] exact="a: 1\n".getBytes(StandardCharsets.UTF_8);
            Files.write(file,exact);
            if(!StrictYaml.readBounded(file,exact.length).equals("a: 1\n"))throw new AssertionError("Exact limit rejected");
            rejects(()->StrictYaml.readBounded(file,exact.length-1));
            Files.write(file,new byte[]{(byte)0xc3,0x28});rejects(()->StrictYaml.readBounded(file,100));
            Files.writeString(file,"a: 1\na: 2\n");rejects(()->StrictYaml.readBounded(file,100));
            Files.writeString(file,"a: 1\n");
            Path link=dir.resolve("link.yml");
            try { Files.createSymbolicLink(link,file); }
            catch(UnsupportedOperationException ex) { System.out.println("Symlink scenario not supported by filesystem"); }
            if(Files.isSymbolicLink(link))rejects(()->StrictYaml.readBounded(link,100));
            Files.deleteIfExists(link);
            rejects(()->StrictYaml.readBounded(dir,100));
            System.out.println("PASS: exact size, excess, invalid UTF-8, duplicate keys, nonregular path; symlink checked where supported");
        } finally {Files.deleteIfExists(file);Files.deleteIfExists(dir);}
    }
}
