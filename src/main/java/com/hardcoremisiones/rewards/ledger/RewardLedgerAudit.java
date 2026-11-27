package com.hardcoremisiones.rewards.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/** Diario de solo lectura; se persiste en la MISMA escritura atómica que el ledger.
 * Nunca sustituye pendientes/conciliacion, ni prueba por sí solo que el cliente vio el item.
 */
public final class RewardLedgerAudit {
    public enum Tipo { ELEGIDA, RESERVADA, EN_CONCILIACION, ENTREGADA, REABIERTA, CONFIRMADA_MANUAL }
    public record Evento(long secuencia, String instante, UUID jugador, String clave, Tipo tipo, String detalle) {
        public Evento {
            if (secuencia<1 || jugador==null || clave==null || clave.isBlank()
                || instante==null || tipo==null || detalle==null) throw new IllegalArgumentException("Evento inválido");
            if (clave.length()>128 || detalle.length()>512 || instante.length()>40 ||
                clave.contains("\n") || detalle.contains("\n") || detalle.contains("\r"))
                throw new IllegalArgumentException("Evento inseguro");
            Instant.parse(instante);
        }
        public String texto() {
            return "#"+secuencia+" "+instante+" "+tipo+" ["+clave+"] "+detalle;
        }
    }
    private final List<Evento> eventos=new ArrayList<>();
    private final Map<UUID,List<Evento>> byPlayer=new HashMap<>();
    private long proxima=1;
    private void append(Evento event){
        eventos.add(event);
        byPlayer.computeIfAbsent(event.jugador(),ignored->new ArrayList<>()).add(event);
    }
    public Evento agregar(UUID jugador, String clave, Tipo tipo, String detalle) {
        Evento evento=new Evento(proxima, Instant.now().toString(),jugador,clave,tipo,detalle);
        proxima++;
        append(evento);
        return evento;
    }
    public void deshacer(Evento e) {
        if(!eventos.isEmpty() && eventos.getLast()==e) {
            eventos.removeLast();proxima=e.secuencia();
            var personal=byPlayer.get(e.jugador());personal.removeLast();
            if(personal.isEmpty())byPlayer.remove(e.jugador());
        }
    }
    public List<Evento> ultimos(UUID jugador, int limite) {
        return pagina(jugador,0,limite);
    }
    /** Number of events for this beneficiary only, without scanning the ledger. */
    public int cantidad(UUID jugador){return byPlayer.getOrDefault(jugador,List.of()).size();}
    /** Newest first; a bounded read over the existing per-player index. */
    public List<Evento> pagina(UUID jugador,int offset,int limite){
        if(offset<0)throw new IllegalArgumentException("Offset negativo");
        List<Evento> resultado=new ArrayList<>();
        var personal=byPlayer.getOrDefault(jugador,List.of());
        if(offset>=personal.size())return List.of();
        for(int i=personal.size()-1-offset;i>=0 && resultado.size()<Math.clamp(limite,1,30);i--)resultado.add(personal.get(i));
        return List.copyOf(resultado);
    }
    boolean hayDatos(){return !eventos.isEmpty();}
    /** Check the last known payment state against the durable queues. Legacy
     * reservations without a payment event are intentionally allowed; absence
     * of audit history never proves delivery. ELEGIDA is not a payment state.
     * @param reservations stable reservation keys, including completed payments
     * @param pending pending keys indexed by recipient
     * @param ambiguous ambiguous keys indexed by recipient
     * @throws IllegalArgumentException if an audited payment is inconsistent
     * with the reservation or could be delivered twice
     */
    public void validateAgainst(Set<String> reservations,Map<UUID,Set<String>> pending,Map<UUID,Set<String>> ambiguous){
        for(var entry:byPlayer.entrySet()){
            UUID player=entry.getKey();
            var known=new java.util.HashSet<String>();
            var personal=entry.getValue();
            Set<String> reservedForPlayer=pending.getOrDefault(player,Set.of());
            Set<String> uncertainForPlayer=ambiguous.getOrDefault(player,Set.of());
            for(int i=personal.size()-1;i>=0;i--){
                Evento event=personal.get(i);
                if(event.tipo()==Tipo.ELEGIDA||!known.add(event.clave()))continue;
                boolean reserved=reservedForPlayer.contains(event.clave());
                boolean uncertain=uncertainForPlayer.contains(event.clave());
                boolean valid=reservations.contains(event.clave())&&switch(event.tipo()){
                    case RESERVADA,REABIERTA -> reserved&&!uncertain;
                    case EN_CONCILIACION -> uncertain&&!reserved;
                    case ENTREGADA,CONFIRMADA_MANUAL -> !reserved&&!uncertain;
                    case ELEGIDA -> throw new AssertionError("Selection is not a payment state");
                };
                if(!valid)throw new IllegalArgumentException("Auditoría/cola W42 contradictorias: "+player+"/"+event.clave()+"/"+event.tipo());
            }
        }
    }
    public void escribir(YamlConfiguration y) {
        if(eventos.size()>100_000)throw new IllegalStateException("Límite del diario W42; requiere mantenimiento supervisado");
        y.set("auditoria-w42.version",1);
        y.set("auditoria-w42.total",eventos.size());
        for(int i=0;i<eventos.size();i++) {
            Evento e=eventos.get(i);String ruta="auditoria-w42.eventos."+i;
            y.set(ruta+".secuencia",e.secuencia());y.set(ruta+".instante",e.instante());
            y.set(ruta+".jugador",e.jugador().toString());y.set(ruta+".clave",e.clave());
            y.set(ruta+".tipo",e.tipo().name());y.set(ruta+".detalle",e.detalle());
        }
    }
    public static RewardLedgerAudit leer(YamlConfiguration y) {
        RewardLedgerAudit diario=new RewardLedgerAudit();
        if(!y.contains("auditoria-w42"))return diario; // partidas anteriores a W42
        var envelope=y.getConfigurationSection("auditoria-w42");
        if(envelope==null||!Set.of("version","total","eventos").containsAll(envelope.getKeys(false))
            ||!envelope.isInt("version")||!envelope.isInt("total"))throw new IllegalArgumentException("Cabecera de auditoría inválida");
        if(y.getInt("auditoria-w42.version",-1)!=1)throw new IllegalArgumentException("Versión de auditoría desconocida");
        int total=y.getInt("auditoria-w42.total",-1);
        if(total<0 || total>100_000)throw new IllegalArgumentException("Cantidad de eventos inválida");
        var seccion=y.getConfigurationSection("auditoria-w42.eventos");
        if((total>0&&seccion==null)||(envelope.contains("eventos")&&seccion==null)||(seccion!=null&&seccion.getKeys(false).size()!=total))
            throw new IllegalArgumentException("Auditoría incompleta");
        long anterior=0;
        for(int i=0;i<total;i++) {
            String ruta="auditoria-w42.eventos."+i;
            var row=y.getConfigurationSection(ruta);
            if(row==null||!row.getKeys(false).equals(Set.of("secuencia","instante","jugador","clave","tipo","detalle"))
                ||!(row.isLong("secuencia")||row.isInt("secuencia")))throw new IllegalArgumentException("Evento de auditoría inválido: "+i);
            for(String field:Set.of("instante","jugador","clave","tipo","detalle"))if(!row.isString(field))throw new IllegalArgumentException("Campo de auditoría inválido: "+field);
            Evento e=new Evento(y.getLong(ruta+".secuencia",-1),y.getString(ruta+".instante"),
                RewardStateSchema.uuid(y.getString(ruta+".jugador","")),y.getString(ruta+".clave"),
                Tipo.valueOf(y.getString(ruta+".tipo","")),y.getString(ruta+".detalle"));
            if(e.secuencia()<=anterior||e.secuencia()==Long.MAX_VALUE)throw new IllegalArgumentException("Secuencia de auditoría regresiva");
            anterior=e.secuencia();diario.append(e);
        }
        diario.proxima=anterior+1;
        return diario;
    }
}
