package com.hardcoremisiones.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Reject duplicate mapping keys and non-mapping configuration roots before Paper loads them.
 * This reader is for editable configuration files, not Bukkit object serialization.
 */
public final class StrictYaml {
    private static final int MAX_BYTES = 1_048_576;
    private StrictYaml() {}
    /** Read a bounded regular configuration file once and validate its exact text.
     * @param file configuration path, not a symlink
     * @return original text, including comments
     * @throws IOException unreadable, oversized, malformed or duplicate-key YAML
     */
    public static String read(Path file) throws IOException {
        return readBounded(file,MAX_BYTES);
    }
    /** Read a plain mapping journal with an explicit aggregate-specific size limit.
     * @param file path of the regular plain YAML file
     * @param maxBytes positive upper bound for bytes and parser code points
     * @return validated source text
     * @throws IOException invalid or unreadable source
     */
    public static String readBounded(Path file,int maxBytes)throws IOException {
        if(maxBytes<1)throw new IllegalArgumentException("Positive YAML limit required");
        if (Files.isSymbolicLink(file)||Files.isSymbolicLink(file.toAbsolutePath().getParent())||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Not a regular configuration file: "+file);
        if(Files.size(file)>maxBytes)throw new IOException("YAML exceeds "+maxBytes+" bytes");
        // Bound allocation even if an administrator replaces or grows the file
        // between the size check and the read. Never follow a final symlink.
        byte[] bytes;
        try(var input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)) {
            bytes=input.readNBytes(maxBytes);
            if(input.read()!=-1)throw new IOException("YAML exceeds "+maxBytes+" bytes");
        }
        String text=StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        validate(text,maxBytes);return text;
    }
    /** Validate mapping shape and duplicate keys, without rewriting the source.
     * @param text exact YAML text to be parsed by the consumer
     * @throws IOException syntax, duplicate key, unsafe tag or nonmapping root
     */
    public static void validate(String text) throws IOException {
        validate(text,MAX_BYTES);
    }
    private static void validate(String text,int maxBytes)throws IOException {
        var options=new LoaderOptions();options.setAllowDuplicateKeys(false);
        options.setCodePointLimit(maxBytes);options.setMaxAliasesForCollections(20);
        try {
            Object root=new Yaml(new SafeConstructor(options)).load(text);
            if(!(root instanceof Map<?,?>))throw new IOException("YAML root must be a map");
        }catch(RuntimeException invalid){throw new IOException("Invalid YAML: "+invalid.getMessage(),invalid);}
    }
}
