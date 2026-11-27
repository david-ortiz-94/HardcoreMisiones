package com.hardcoremisiones.bosses.persistence;

import com.hardcoremisiones.sessions.identity.HardcoreMpGameIdentity;
import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * W33: vínculo append-only de una sesión histórica F1 con la partida lógica HardcoreMP.
 * El archivo sidecar NO modifica el estado original W31. Si cambia la identidad,
 * no se sobreescribe: se retienen los premios y se exige conciliación posterior.
 */
public final class EncounterGameIdentityStore {
    public enum State { VERIFIED, UNBOUND, DIFFERENT, CORRUPT }
    public record Check(State state, String reason) {
        public boolean verified() { return state == State.VERIFIED; }
    }
    private final Path directory;

    public EncounterGameIdentityStore(Path pluginDataFolder) {
        this.directory = pluginDataFolder.resolve("identidades-f3");
    }
    public Path file(UUID legacySession) {
        return directory.resolve(legacySession + "-hcmp-f3-identity-v01.yml");
    }

    public Check verify(UUID legacySession, HardcoreMpGameIdentity.Identity now) {
        if (legacySession == null || now == null) return new Check(State.CORRUPT,"Identidad o sesión ausente");
        Path file = file(legacySession);
        try {
            if (Files.isSymbolicLink(directory) || Files.isSymbolicLink(file))
                return new Check(State.CORRUPT,"Ruta con enlace simbólico");
            if (!Files.exists(file,LinkOption.NOFOLLOW_LINKS))
                return new Check(State.UNBOUND,"Sin vínculo de partida F3; W31 no se acredita automáticamente");
            if (!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))
                return new Check(State.CORRUPT,"Vínculo de partida no es archivo regular");
            var y = SessionYaml.read(file.toFile());
            if (y.getInt("formato",-1)!=1 || !legacySession.toString().equals(y.getString("sesion-f1")))
                return new Check(State.CORRUPT,"Formato o sesión histórica inconsistentes");
            int reset = y.getInt("reset-id",-1);
            long created = y.getLong("create-time",0);
            String value = y.getString("game-instance-id","");
            if (reset<0 || created<=0 || !("hcmp:"+reset+":"+created).equals(value))
                return new Check(State.CORRUPT,"Identidad almacenada inconsistente");
            if (!now.value().equals(value))
                return new Check(State.DIFFERENT,"Reset de HardcoreMP detectado: revisión, NO premios");
            return new Check(State.VERIFIED,"Identidad de partida F3 confirmada");
        } catch (RuntimeException ex) {
            return new Check(State.CORRUPT,"Vínculo ilegible: "+ex.getClass().getSimpleName());
        }
    }

    /** Solo crear el primer vínculo para un encuentro NUEVO sin registros históricos
     * reales ni premios heredados. Los vínculos existentes son inmutables.
     */
    public Check bindFirst(UUID legacySession, HardcoreMpGameIdentity.Identity now, boolean hasLegacyRealState) {
        Check current = verify(legacySession, now);
        if (current.state()!=State.UNBOUND) return current;
        if (hasLegacyRealState) return new Check(State.UNBOUND,
            "W31 contiene encuentros/premios reales sin identidad lógica: revisión obligatoria");
        try {
            if (Files.isSymbolicLink(directory)) return new Check(State.CORRUPT,"Directorio inseguro");
            Files.createDirectories(directory);
            Path destination=file(legacySession);
            if (Files.exists(destination,LinkOption.NOFOLLOW_LINKS)) return verify(legacySession,now);
            YamlConfiguration y = new YamlConfiguration();
            y.set("formato",1);
            y.set("sesion-f1",legacySession.toString());
            y.set("game-instance-id",now.value());
            y.set("reset-id",now.resetId());
            y.set("create-time",now.createTime());
            AtomicStorage.write(destination,y.saveToString());
            return verify(legacySession,now);
        } catch (IOException | RuntimeException ex) {
            return new Check(State.CORRUPT,"No se pudo vincular partida: "+ex.getClass().getSimpleName());
        }
    }
}
