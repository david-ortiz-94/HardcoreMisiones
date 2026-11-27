package com.hardcoremisiones.rewards.ledger;

import com.hardcoremisiones.rewards.model.RewardItem;

import com.hardcoremisiones.rewards.model.RewardPayment;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** W42 queue codec, shared by reserved and ambiguous payments. It neither delivers
 * rewards nor interprets administrative decisions. Unknown fields are rejected
 * before an aggregate can be saved with lost data.
 */
public final class RewardQueueCodec {
    private RewardQueueCodec() {}
    private static void fields(ConfigurationSection node,Set<String> allowed){
        if(!allowed.containsAll(node.getKeys(false)))throw new IllegalArgumentException("Campos W42 desconocidos: "+node.getCurrentPath());
    }
    /** Decode one queue into a caller-owned, initially empty or disjoint map.
     * The three payload fields written by HF5 (XP, item, quantity) are mandatory;
     * optional legacy prestige, trophy, benefit and extra fields retain defaults.
     * The caller must discard the temporary map on any decoding failure.
     * @param y validated YAML envelope
     * @param ruta historical queue path (pending or reconciliation)
     * @param salida temporary output map keyed by beneficiary
     * @throws IllegalArgumentException invalid payload, duplicate owner or queue shape
     */
    public static void leerCola(YamlConfiguration y, String ruta, Map<UUID,List<RewardPayment>> salida) {
        var seccion=y.getConfigurationSection(ruta);
        if (seccion==null) { if(y.contains(ruta))throw new IllegalArgumentException("Cola no es mapa: "+ruta);return; } // archivos rc3 anteriores no contienen conciliación
        Set<UUID> seenPlayers=new HashSet<>();
        for(String jugador:seccion.getKeys(false)) {
            UUID uid=RewardStateSchema.uuid(jugador);
            if(!seenPlayers.add(uid)||salida.containsKey(uid))throw new IllegalArgumentException("UUID W42 duplicado: "+jugador);
            var premios=seccion.getConfigurationSection(jugador);
            if(premios==null)throw new IllegalArgumentException("Cola de jugador inválida: "+jugador);
            List<RewardPayment> lista=new ArrayList<>();
            for(String clave:premios.getKeys(false)) {
                RewardStateSchema.reservationKey(clave);
                var p=premios.getConfigurationSection(clave);
                if(p==null)throw new IllegalArgumentException("Premio sin contenido: "+clave);
                fields(p,Set.of("cantidad","xp","item","prestigio","trofeo-w39","beneficio-w41","extras-w45"));
                for(String k:List.of("cantidad","xp"))if(!p.isInt(k))throw new IllegalArgumentException("Campo obligatorio W42 ausente/inválido: "+k);
                if(!p.isString("item"))throw new IllegalArgumentException("Material W42 ausente/inválido");
                for(String k:List.of("prestigio"))if(p.contains(k)&&!p.isInt(k))throw new IllegalArgumentException("Entero W42 inválido: "+k);
                for(String k:List.of("item","trofeo-w39","beneficio-w41"))if(p.contains(k)&&!p.isString(k))throw new IllegalArgumentException("Texto W42 inválido: "+k);
                int cantidad=p.getInt("cantidad",0), xp=p.getInt("xp",0);
                if(cantidad<0 || cantidad>64 || xp<0 || xp>1000)
                    throw new IllegalArgumentException("Cantidad de premio inválida: "+clave);
                Material material=Material.valueOf(p.getString("item"));
                if(!material.isItem())throw new IllegalArgumentException("Material de premio inválido: "+clave);
                int prestigio=p.getInt("prestigio",0);
                if(prestigio<0 || prestigio>8)throw new IllegalArgumentException("Prestigio de premio inválido");
                String trofeo=p.getString("trofeo-w39","");
                String beneficio=p.getString("beneficio-w41","");
                List<RewardItem> extras=new ArrayList<>();
                var se=p.getConfigurationSection("extras-w45");
                if(p.contains("extras-w45")&&se==null)throw new IllegalArgumentException("Extras W42 no es mapa");
                if(se!=null)for(int j=0;j<se.getKeys(false).size();j++) {
                    String rutaExtra="extras-w45."+j;
                    var extra=p.getConfigurationSection(rutaExtra);
                    if(extra==null||!extra.isString("item")||!extra.isInt("cantidad"))throw new IllegalArgumentException("Extra W42 inválido");
                    fields(extra,Set.of("item","cantidad"));
                    extras.add(new RewardItem(Material.valueOf(p.getString(rutaExtra+".item","")),
                        p.getInt(rutaExtra+".cantidad",-1)));
                }
                lista.add(new RewardPayment(clave,xp,material,cantidad,prestigio,trofeo,beneficio,extras));
            }
            if(!lista.isEmpty())salida.put(uid,lista);
        }
    }
    /** Encode a queue into a new aggregate snapshot without writing to disk.
     * @param y new snapshot owned by the aggregate
     * @param ruta historical queue path
     * @param cola validated payments by beneficiary
     */
    public static void escribirCola(YamlConfiguration y,String ruta,Map<UUID,List<RewardPayment>> cola) {
        cola.forEach((id,lista)->lista.forEach(p->{
            String base=ruta+"."+id+"."+p.clave();
            y.set(base+".xp",p.experiencia());y.set(base+".item",p.item().name());y.set(base+".cantidad",p.cantidad());
            if(p.prestigio()>0)y.set(base+".prestigio",p.prestigio());
            if(!p.trofeo().isEmpty())y.set(base+".trofeo-w39",p.trofeo());
            if(!p.beneficio().isEmpty())y.set(base+".beneficio-w41",p.beneficio());
            for(int j=0;j<p.extras().size();j++) {
                var extra=p.extras().get(j);
                y.set(base+".extras-w45."+j+".item",extra.material().name());
                y.set(base+".extras-w45."+j+".cantidad",extra.cantidad());
            }
        }));
    }
    /** Build a temporary read-only key index for cross-validation of the audit.
     * @param queue decoded payment rows by recipient
     * @return independent key index, without payment payloads
     */
    public static Map<UUID,Set<String>> keys(Map<UUID,List<RewardPayment>> queue){
        Map<UUID,Set<String>> result=new HashMap<>();
        queue.forEach((player,payments)->{
            Set<String> keys=new HashSet<>();
            for(RewardPayment payment:payments)keys.add(payment.clave());
            result.put(player,Set.copyOf(keys));
        });
        return Map.copyOf(result);
    }
    /** A payment must have a durable reservation and may be in only one queue per player.
     * @param reservations keys recorded in the same aggregate
     * @param pending decoded pending queue
     * @param ambiguous decoded reconciliation queue
     * @throws IllegalArgumentException unreserved or repeated beneficiary/key pair
     */
    public static void validate(Set<String> reservations,Map<UUID,List<RewardPayment>> pending,Map<UUID,List<RewardPayment>> ambiguous){
        Set<UUID> players=new HashSet<>(pending.keySet());players.addAll(ambiguous.keySet());
        for(UUID player:players){
            Set<String> seen=new HashSet<>();
            for(var queue:List.of(pending,ambiguous))for(RewardPayment p:queue.getOrDefault(player,List.of())){
                if(!reservations.contains(p.clave())||!seen.add(p.clave()))
                    throw new IllegalArgumentException("Premio sin reserva o duplicado: "+player+"/"+p.clave());
            }
        }
    }
}
