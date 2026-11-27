package com.hardcoremisiones.bosses.integration;

import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.f3.api.BossProvider;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Proveedor de integración SIN entidades, combate ni premios.
 * Nunca se registra por sí mismo: requiere 3 barreras de laboratorio explícitas.
 * Sus recibos se etiquetan SIMULATED en el orquestador de manera irrevocable.
 */
public final class SimulatedBossProvider implements BossProvider {
    public static final String JVM_GATE = "hm2.f3.lab-only";
    public static final String JVM_VALUE = "I_ACCEPT_NO_REAL_BOSSES";
    public static final String CONFIG_GATE = "jefes.simulacion-laboratorio";
    private record Entry(String boss,UUID encounter,UUID session,List<UUID> members,
                         State state,UUID host,Receipt receipt) { }
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final File file;
    private final Map<UUID,Entry> entries = new HashMap<>();
    private boolean connected=true, registerIds=true, rejectNext, throwNext, active;

    public SimulatedBossProvider(org.bukkit.plugin.java.JavaPlugin plugin) {
        this.plugin=plugin;
        file=new File(plugin.getDataFolder(),"laboratorio-f3/encuentros-simulados-v1.yml");
        load();
    }
    public static boolean labAllowed(org.bukkit.plugin.java.JavaPlugin p) {
        return p.getConfig().getBoolean(CONFIG_GATE,false)
            && JVM_VALUE.equals(System.getProperty(JVM_GATE,""));
    }
    public void activate(){ if(!labAllowed(plugin))throw new IllegalStateException("Doble autorización de laboratorio ausente"); active=true; }
    public void deactivate(){active=false;}
    public boolean activated(){return active;}
    private void available() {
        if(!active || !labAllowed(plugin))throw new IllegalStateException("Simulador desactivado");
        if(!connected)throw new IllegalStateException("Enlace simulado interrumpido");
    }
    @Override public int apiVersion(){return API_VERSION;}
    @Override public Set<String> registeredIds(){
        available();
        return registerIds?Set.of("hm2:guardian_ceniza","hm2:centinela_abismo","hm2:arquitecta_ecos"):Set.of();
    }
    @Override public Snapshot spawn(Request request){
        available();
        Entry saved=entries.get(request.encounterId());
        if(saved!=null){
            if(!same(saved,request))throw new IllegalStateException("encounterId reutilizado con petición distinta");
            return snapshot(saved);
        }
        if(throwNext){throwNext=false;throw new IllegalStateException("Fallo de invocación simulado");}
        if(rejectNext){rejectNext=false;return new Snapshot(request.bossId(),request.encounterId(),
            request.sessionId(),request.participants(),State.FAILED,null,0,0);}
        if(!registeredIds().contains(request.bossId()))return new Snapshot(request.bossId(),
            request.encounterId(),request.sessionId(),request.participants(),State.MISSING,null,0,0);
        Entry e=new Entry(request.bossId(),request.encounterId(),request.sessionId(),
            request.participants(),State.PREPARING,UUID.randomUUID(),null);
        entries.put(e.encounter(),e);save();return snapshot(e);
    }
    @Override public Snapshot find(UUID id){available();Entry e=entries.get(id);return e==null?null:snapshot(e);}
    @Override public Receipt result(UUID id){available();Entry e=entries.get(id);return e==null?null:e.receipt();}
    @Override public boolean pause(UUID id){
        available();Entry e=entries.get(id);if(e==null)return false;
        if(e.state()==State.PREPARING||e.state()==State.ACTIVE){change(e,State.PAUSED,e.receipt());return true;}
        return e.state()==State.PAUSED;
    }
    @Override public boolean resume(UUID id){
        available();Entry e=entries.get(id);if(e==null)return false;
        if(e.state()==State.PAUSED||e.state()==State.PREPARING){change(e,State.ACTIVE,e.receipt());return true;}
        return e.state()==State.ACTIVE;
    }
    @Override public boolean cancel(UUID id){
        available();Entry e=entries.get(id);if(e==null)return false;
        if(e.receipt()!=null)return e.receipt().outcome()==Outcome.CANCELLED;
        change(e,State.CANCELLED,new Receipt(e.boss(),e.encounter(),e.session(),e.members(),
            Outcome.CANCELLED,UUID.randomUUID(),System.currentTimeMillis(),true));return true;
    }
    private boolean same(Entry e,Request r){
        return e.boss().equals(r.bossId())&&e.session().equals(r.sessionId())
            &&Set.copyOf(e.members()).equals(Set.copyOf(r.participants()));
    }
    private Snapshot snapshot(Entry e){return new Snapshot(e.boss(),e.encounter(),e.session(),
        e.members(),e.state(),e.host(),e.state()==State.ACTIVE?1:0,e.state()==State.ACTIVE?100:0);}
    private void change(Entry e,State s,Receipt r){
        entries.put(e.encounter(),new Entry(e.boss(),e.encounter(),e.session(),e.members(),s,e.host(),r));save();
    }
    /** Sólo comandos administrativos explícitos; terminal inmutable, duplicados sin efecto. */
    public String inject(String event,UUID encounterId){
        if(!active||!labAllowed(plugin))return "Simulador no autorizado";
        switch(event){
            case "offline" -> {connected=false;return "Enlace simulado caído";}
            case "online" -> {connected=true;return "Enlace simulado restablecido";}
            case "unregistered" -> {registerIds=false;return "Registro de jefes simulado vacío";}
            case "registered" -> {registerIds=true;return "Registro de jefes simulado restaurado";}
            case "reject-next" -> {rejectNext=true;return "Siguiente invocación: RECHAZADA";}
            case "throw-next" -> {throwNext=true;return "Siguiente invocación: ERROR";}
            case "restart" -> {load();return "Recuperación en memoria desde diario simulado; no re-spawn";}
            default -> { }
        }
        Entry e=entries.get(encounterId);
        if(e==null)return "Sin encounterId en el simulador";
        if(event.equals("duplicate")||event.equals("late"))
            return "Mismo estado/resultado original; no se fabricó nuevo receipt";
        if(e.receipt()!=null)return "Encuentro terminal inmutable: resultado repetido sin modificaciones";
        switch(event){
            case "preparing" -> change(e,State.PREPARING,null);
            case "active" -> change(e,State.ACTIVE,null);
            case "pause", "disconnect" -> change(e,State.PAUSED,null);
            case "missing" -> change(e,State.MISSING,null);
            case "unknown" -> change(e,State.UNKNOWN,null);
            case "failed" -> change(e,State.FAILED,null);
            case "victory","defeat","cancel" -> {
                Outcome outcome=switch(event){case "victory"->Outcome.VICTORY;
                    case "defeat"->Outcome.DEFEAT; default->Outcome.CANCELLED;};
                State state=switch(outcome){case VICTORY->State.DEFEATED;
                    case DEFEAT->State.LOST;case CANCELLED->State.CANCELLED;case INTERRUPTED->State.INTERRUPTED;};
                change(e,state,new Receipt(e.boss(),e.encounter(),e.session(),e.members(),
                    outcome,UUID.randomUUID(),System.currentTimeMillis(),true));
            }
            default -> {return "Evento desconocido";}
        }
        return "Evento simulado "+event+" registrado; recompensas reales siempre prohibidas";
    }
    private void load(){
        if(!SessionYaml.exists(file))return;
        var y=SessionYaml.read(file);
        if(y.getInt("formato",-1)!=1)throw new IllegalStateException("Diario de laboratorio F3 inválido");
        Map<UUID,Entry> restored=new HashMap<>();
        var all=y.getConfigurationSection("encuentros");
        if(all!=null)for(String key:all.getKeys(false)){
            UUID id=UUID.fromString(key),sid=UUID.fromString(y.getString("encuentros."+key+".sesion",""));
            String boss=y.getString("encuentros."+key+".jefe","");
            List<UUID> members=new ArrayList<>();
            for(String raw:y.getStringList("encuentros."+key+".miembros"))members.add(UUID.fromString(raw));
            if(!BossProvider.validId(boss)||!BossProvider.validMembers(members))throw new IllegalStateException("Simulación corrupta");
            String path="encuentros."+key;
            State state=State.valueOf(y.getString(path+".estado","UNKNOWN"));
            if(state==State.ACTIVE||state==State.PREPARING)state=State.PAUSED;
            UUID host=uuidOrNull(y.getString(path+".entidad",""));
            Receipt receipt=null;
            if(y.contains(path+".resultado"))receipt=new Receipt(boss,id,sid,members,
                Outcome.valueOf(y.getString(path+".resultado")),
                UUID.fromString(y.getString(path+".recibo-id")),y.getLong(path+".fecha"),true);
            restored.put(id,new Entry(boss,id,sid,List.copyOf(members),state,host,receipt));
        }
        entries.clear();entries.putAll(restored);save();
    }
    private static UUID uuidOrNull(String raw){return raw==null||raw.isBlank()?null:UUID.fromString(raw);}
    private void save(){
        var y=new YamlConfiguration();y.set("formato",1);
        for(Entry e:entries.values()){
            String p="encuentros."+e.encounter();
            y.set(p+".jefe",e.boss());y.set(p+".sesion",e.session().toString());
            y.set(p+".miembros",e.members().stream().map(UUID::toString).toList());
            y.set(p+".estado",e.state().name());y.set(p+".entidad",e.host()==null?"":e.host().toString());
            if(e.receipt()!=null){y.set(p+".resultado",e.receipt().outcome().name());
                y.set(p+".recibo-id",e.receipt().resultId().toString());
                y.set(p+".fecha",e.receipt().decidedAtMillis());}
        }
        try{AtomicStorage.write(file.toPath(),y.saveToString());}
        catch(IOException|RuntimeException ex){throw new IllegalStateException("Fallo diario del simulador F3",ex);}
    }
}
