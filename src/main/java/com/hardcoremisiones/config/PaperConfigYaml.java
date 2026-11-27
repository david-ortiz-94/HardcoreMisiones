package com.hardcoremisiones.config;

import com.hardcoremisiones.config.StrictYaml;
import java.io.File;
import java.io.IOException;
import org.bukkit.configuration.file.YamlConfiguration;

/** Paper translation of a validated editable configuration; never writes the file. */
public final class PaperConfigYaml {
    private PaperConfigYaml() {}
    public static YamlConfiguration read(File file) throws IOException {
        var yaml=new YamlConfiguration();
        try{yaml.loadFromString(StrictYaml.read(file.toPath()));
            ProductYamlSchema.validate(file.getName(),yaml);
        }
        catch(Exception invalid){throw new IOException("Invalid configuration "+file.getName(),invalid);}
        return yaml;
    }
    public static YamlConfiguration require(File file){
        try{return read(file);}catch(IOException invalid){throw new IllegalStateException(invalid.getMessage(),invalid);}
    }
}
