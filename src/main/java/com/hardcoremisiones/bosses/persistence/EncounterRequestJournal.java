package com.hardcoremisiones.bosses.persistence;

import com.hardcoremisiones.storage.SessionYaml;

import com.hardcorebosses.api.*;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Diario append-only del request canónico HB: se escribe ANTES de llamar a spawn.
 * Se conserva tras reinicios, sin reescribir YAML de F1/F2, y evita reutilizar
 * un encounter_id para otra solicitud. No es la prueba de muerte del proveedor.
 */
public final class EncounterRequestJournal {
    public record Entry(UUID encounterId, UUID legacySession,String bossId,String missionId,
        String gameId,UUID worldId,double x,double y,double z,float yaw,float pitch,
        Set<UUID> participants,int radius) {
        public Entry {
            Objects.requireNonNull(encounterId);Objects.requireNonNull(legacySession);
            Objects.requireNonNull(bossId);Objects.requireNonNull(missionId);
            Objects.requireNonNull(gameId);Objects.requireNonNull(worldId);
            Objects.requireNonNull(participants);
            if(!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(z)
                ||!Float.isFinite(yaw)||!Float.isFinite(pitch)
                ||participants.isEmpty()||participants.size()>3||participants.stream().anyMatch(Objects::isNull)
                ||radius<1||radius>128)throw new IllegalArgumentException("Solicitud HB no canónica");
            participants=Set.copyOf(participants);
        }
        public static Entry from(UUID legacy, String mission,EncounterRequest r) {
            SpawnLocation l=r.spawnLocation();
            if(r.origin()!=EncounterOrigin.MISSION||!r.originId().equals(mission))
                throw new IllegalArgumentException("Origen HB distinto de misión");
            return new Entry(r.encounterId(),legacy,r.bossId(),mission,r.gameInstanceId(),
                r.worldId(),l.x(),l.y(),l.z(),l.yaw(),l.pitch(),r.participants(),r.arenaRadiusBlocks());
        }
        /** Huella pública HB-RC2 de la solicitud original, reconstruida SIN requestedAt.
         * La huella local YAML es una protección adicional, no sustituye esta huella.
         */
        String providerCanonicalHash() {
            return new EncounterRequest(encounterId,bossId,gameId,worldId,
                new SpawnLocation(worldId,x,y,z,yaw,pitch),participants,
                EncounterOrigin.MISSION,missionId,radius,java.time.Instant.EPOCH)
                .canonicalRequestHash();
        }
        public String canonical(){
            return encounterId+"|"+legacySession+"|"+bossId+"|"+missionId+"|"+gameId+"|"+worldId
                +"|"+Double.toHexString(x)+"|"+Double.toHexString(y)+"|"+Double.toHexString(z)
                +"|"+Float.toHexString(yaw)+"|"+Float.toHexString(pitch)+"|"
                +participants.stream().map(UUID::toString).sorted().reduce((a,b)->a+","+b).orElseThrow()
                +"|"+radius;
        }
        public static Entry parse(String raw){
            String[] p=raw.split("\\|",-1);
            if(p.length!=13)throw new IllegalArgumentException("Formato canónico HB inválido");
            Set<UUID> members=new HashSet<>();
            for(String member:p[11].split(","))if(!members.add(UUID.fromString(member)))
                throw new IllegalArgumentException("Participante repetido");
            Entry value=new Entry(UUID.fromString(p[0]),UUID.fromString(p[1]),p[2],p[3],p[4],
                UUID.fromString(p[5]),Double.valueOf(p[6]),Double.valueOf(p[7]),Double.valueOf(p[8]),
                Float.valueOf(p[9]),Float.valueOf(p[10]),members,Integer.parseInt(p[12]));
            if(!value.canonical().equals(raw))throw new IllegalArgumentException("Huella no canónica");
            return value;
        }
    }
    private final Path dir;
    public EncounterRequestJournal(Path dataFolder){this.dir=dataFolder.resolve("solicitudes-f3-hb");}
    public Path path(UUID id){return dir.resolve(id+"-hb-request-v01.yml");}
    static String sha256(String raw){
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        }catch(NoSuchAlgorithmException ex){throw new IllegalStateException(ex);}
    }
    public Optional<Entry> read(UUID id){
        Path file=path(id);
        try{
            if(Files.isSymbolicLink(dir)||Files.isSymbolicLink(file)
                ||!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS))return Optional.empty();
            var y=SessionYaml.read(file.toFile());
            if(y.getInt("formato",-1)!=1||!id.toString().equals(y.getString("encounter-id")))
                return Optional.empty();
            String encoded=y.getString("canonical-base64",""),hash=y.getString("sha256","");
            String raw=new String(Base64.getDecoder().decode(encoded),StandardCharsets.UTF_8);
            if(!sha256(raw).equals(hash))return Optional.empty();
            Entry entry=Entry.parse(raw);
            return entry.encounterId().equals(id)?Optional.of(entry):Optional.empty();
        }catch(RuntimeException ex){return Optional.empty();}
    }
    public boolean persist(Entry entry){
        Path target=path(entry.encounterId());
        try{
            if(Files.isSymbolicLink(dir)||Files.isSymbolicLink(target))return false;
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS))
                return read(entry.encounterId()).map(entry::equals).orElse(false);
            Files.createDirectories(dir);
            if(Files.isSymbolicLink(dir))return false;
            var y=new YamlConfiguration();String value=entry.canonical();
            y.set("formato",1);y.set("encounter-id",entry.encounterId().toString());
            y.set("canonical-base64",Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
            y.set("sha256",sha256(value));
            AtomicStorage.write(target,y.saveToString());
            return read(entry.encounterId()).map(entry::equals).orElse(false);
        }catch(IOException|RuntimeException ex){return false;}
    }
    public boolean matches(UUID id,UUID legacySession,String boss,String mission,String game,UUID world,
        Set<UUID> participants){
        return read(id).filter(e->e.legacySession().equals(legacySession)
            &&e.bossId().equals(boss)&&e.missionId().equals(mission)
            &&e.gameId().equals(game)&&e.worldId().equals(world)
            &&e.participants().equals(participants)).isPresent();
    }
    /** No alcanza comparar IDs: cotejar la huella RC2 exacta con el request original.
     * Rechaza journal ausente, alterado, legado sin sidecar o snapshot incongruente.
     */
    public boolean matchesSnapshot(UUID id,UUID legacySession,String boss,String mission,
        String game,UUID world,Set<UUID> participants,EncounterSnapshot snapshot) {
        if(snapshot==null)return false;
        return read(id).filter(e->e.legacySession().equals(legacySession)
            &&e.bossId().equals(boss)&&e.missionId().equals(mission)
            &&e.gameId().equals(game)&&e.worldId().equals(world)
            &&e.participants().equals(participants)).map(entry -> entry.providerCanonicalHash()
            .equals(snapshot.canonicalRequestHash())
            && entry.radius()==snapshot.arenaRadiusBlocks()
            && entry.worldId().equals(snapshot.spawnLocation().worldId())
            && Double.doubleToLongBits(entry.x())==Double.doubleToLongBits(snapshot.spawnLocation().x())
            && Double.doubleToLongBits(entry.y())==Double.doubleToLongBits(snapshot.spawnLocation().y())
            && Double.doubleToLongBits(entry.z())==Double.doubleToLongBits(snapshot.spawnLocation().z())
            && Float.floatToIntBits(entry.yaw())==Float.floatToIntBits(snapshot.spawnLocation().yaw())
            && Float.floatToIntBits(entry.pitch())==Float.floatToIntBits(snapshot.spawnLocation().pitch())
        ).orElse(false);
    }
}
