package com.hardcoremisiones.penalties.persistence;

import com.hardcoremisiones.penalties.policy.WaveDebt;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;

/** Reads and writes the pending part of the historical wave aggregate. Unknown
 * or malformed rows fail closed: omitting one could forgive a durable penalty.
 * The caller controls atomic file replacement and publishes only a validated queue.
 */
public final class WaveDebtCodec {
    private static final String QUEUE="penitencias-pendientes", REGISTERED="penitencias-registradas";
    private WaveDebtCodec() {}

    /** An immutable snapshot of the queue and the full idempotency history.
     * @param pending FIFO order of the pending obligations
     * @param registered all obligations ever registered in this game
     */
    public record Snapshot(List<WaveDebt> pending, Set<String> registered) {
        /** Rejects a queue that cannot be reconciled with its registration history.
         * @throws IllegalArgumentException duplicate or unregistered pending debt
         */
        public Snapshot {
            pending=List.copyOf(pending); registered=Set.copyOf(registered);
            var seen=new HashSet<String>();
            for(var debt:pending)if(!registered.contains(debt.key())||!seen.add(debt.key()))
                throw new IllegalArgumentException("Deuda ausente de historial o repetida: "+debt.key());
        }
    }

    /** Decode the legacy numbered rows; reject gaps, non-string UUIDs, or inconsistent keys.
     * @param y parsed session document; not mutated
     * @return immutable, validated queue and history (empty only when absent)
     * @throws IllegalArgumentException malformed or inconsistent persisted state
     */
    public static Snapshot read(ConfigurationSection y) {
        var registered=new LinkedHashSet<String>();
        if(y.contains(REGISTERED)){
            if(!y.isList(REGISTERED))throw new IllegalArgumentException("Historial de oleadas no es lista");
            for(Object value:Objects.requireNonNull(y.getList(REGISTERED))) {
                if(!(value instanceof String key) || !validKey(key) || !registered.add(key))
                    throw new IllegalArgumentException("Clave histórica de oleada inválida o duplicada");
            }
        }
        var debts=new ArrayList<WaveDebt>();
        if(y.contains(QUEUE)){
            var section=y.getConfigurationSection(QUEUE);
            if(section==null)throw new IllegalArgumentException("Cola de oleadas no es mapa");
            for(int i=0;i<section.getKeys(false).size();i++){
                var row=section.getConfigurationSection(Integer.toString(i));
                if(row==null||!row.getKeys(false).equals(Set.of("clave","jornada","grupal","objetivos"))
                    ||!row.isString("clave")||!row.isInt("jornada")||!row.isBoolean("grupal")||!row.isList("objetivos"))
                    throw new IllegalArgumentException("Fila de oleada inválida: "+i);
                var ids=new ArrayList<UUID>();
                for(Object value:Objects.requireNonNull(row.getList("objetivos"))){
                    if(!(value instanceof String raw))throw new IllegalArgumentException("UUID de oleada inválido");
                    UUID id=UUID.fromString(raw);
                    if(!id.toString().equalsIgnoreCase(raw))throw new IllegalArgumentException("UUID de oleada no canónico");
                    ids.add(id);
                }
                debts.add(new WaveDebt(row.getString("clave"),row.getInt("jornada"),row.getBoolean("grupal"),ids));
            }
        }
        return new Snapshot(debts,registered);
    }

    /** Replace only the queue fields, preserving HF5 names and row ordering.
     * @param y mutable output document owned by caller
     * @param snapshot validated pending obligations and history
     */
    public static void write(ConfigurationSection y,Snapshot snapshot){
        y.set(QUEUE,null);
        y.set(REGISTERED,snapshot.registered().stream().sorted().toList());
        for(int i=0;i<snapshot.pending().size();i++){
            var debt=snapshot.pending().get(i); String prefix=QUEUE+"."+i+".";
            y.set(prefix+"clave",debt.key()); y.set(prefix+"jornada",debt.day());
            y.set(prefix+"grupal",debt.group()); y.set(prefix+"objetivos",debt.targets().stream().map(UUID::toString).toList());
        }
    }
    private static boolean validKey(String key){
        if(key.matches("G-[1-9][0-9]*"))return true;
        if(!key.matches("I-[1-9][0-9]*-[0-9a-fA-F-]{36}"))return false;
        String raw=key.substring(key.indexOf('-',2)+1);
        try{return UUID.fromString(raw).toString().equalsIgnoreCase(raw);}catch(IllegalArgumentException ex){return false;}
    }
}
