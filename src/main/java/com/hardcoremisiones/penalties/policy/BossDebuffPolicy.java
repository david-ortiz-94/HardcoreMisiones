package com.hardcoremisiones.penalties.policy;

import java.util.*;

/** RC5: reglas de debuff severo, comprobables sin cargar Bukkit.
 * Un fallo del proveedor no es una derrota; un plazo solo empieza en ACTIVE confirmado.
 */
public final class BossDebuffPolicy {
    public enum Origen { PENITENCIA, RETO_ESPECIAL }
    public enum Motivo { DERROTA_VERIFICADA, TIEMPO_AGOTADO }
    public record Efecto(String tipo, int nivel) {
        public Efecto {
            if (!Set.of("WEAKNESS", "SLOWNESS", "MINING_FATIGUE", "DARKNESS", "HUNGER", "NAUSEA")
                    .contains(tipo) || nivel < 1 || nivel > 2)
                throw new IllegalArgumentException("Efecto inseguro o nivel fuera de 1..2: " + tipo + ":" + nivel);
        }
        public String serializado(){return tipo+":"+nivel;}
    }
    public record Plan(Origen origen, Motivo motivo, int segundos, List<Efecto> efectos) {
        public Plan {
            Objects.requireNonNull(origen);Objects.requireNonNull(motivo);
            if (segundos < 60 || segundos > 7200 || efectos == null || efectos.isEmpty()
                    || efectos.size() > 4 || efectos.stream().map(Efecto::tipo).distinct().count() != efectos.size())
                throw new IllegalArgumentException("Plan de sanción inválido");
            efectos = List.copyOf(efectos);
        }
        public int prioridad(){return origen==Origen.PENITENCIA?2:1;}
    }
    /** Immutable penalty policy captured before a combat/debt is acquired.
     * Global OFF still pauses execution of existing debts; it does not rewrite
     * their duration, effects or eligibility. The ordinary hunt uses its own
     * trigger semantics (both terminal causes were eligible in HF5).
     */
    public record Rules(boolean enabled, boolean loss, boolean timeout, int minutes, List<Efecto> effects) {
        public Rules {
            effects=List.copyOf(effects);
            if(minutes<1||minutes>120)throw new IllegalArgumentException("Duración de sanción 1..120 minutos");
            new Plan(Origen.RETO_ESPECIAL,Motivo.DERROTA_VERIFICADA,minutes*60,effects);
        }
        /** @param reason verified terminal cause
         * @return whether the acquired contract admits this cause */
        public boolean allows(Motivo reason) {
            Objects.requireNonNull(reason);return enabled&&(reason==Motivo.DERROTA_VERIFICADA?loss:timeout);
        }
        /** @param origin source of the debt
         * @param reason verified terminal cause
         * @param tier frozen difficulty, 1..5
         * @return scaled immutable plan
         * @throws IllegalArgumentException if the cause is not enabled or tier is invalid */
        public Plan resolve(Origen origin,Motivo reason,int tier) {
            if(!allows(reason))throw new IllegalArgumentException("Causa fuera del contrato de sanción");
            return escalar(new Plan(origin,reason,minutes*60,effects),tier);
        }
    }
    public static List<Efecto> parsear(List<String> raws){
        if(raws==null||raws.isEmpty()||raws.size()>4)throw new IllegalArgumentException("Se requieren 1..4 efectos");
        List<Efecto> result=new ArrayList<>();
        Set<String> nombres=new HashSet<>();
        for(String raw:raws){
            if(raw==null||!raw.matches("[A-Z_]+:[12]"))throw new IllegalArgumentException("Efecto mal formado: "+raw);
            String[] pieces=raw.split(":");
            Efecto e=new Efecto(pieces[0],Integer.parseInt(pieces[1]));
            if(!nombres.add(e.tipo()))throw new IllegalArgumentException("Efecto repetido: "+e.tipo());
            result.add(e);
        }
        return List.copyOf(result);
    }
    public static Plan plan(Origen origin, Motivo motivo, int minutos,List<String> efectos){
        if(minutos<1||minutos>120)throw new IllegalArgumentException("duración 1..120 minutos");
        return new Plan(origin,motivo,minutos*60,parsear(efectos));
    }
    /** HF5: el perfil YAML anterior constituye el nivel II; escalar solamente
     * con un tier validado y congelado del encounter. Conservar efectos seguros
     * y límites RC5 (hasta cuatro efectos y amplificadores I/II).
     */
    public static Plan escalar(Plan base,int tier){
        Objects.requireNonNull(base);
        if(tier<1||tier>5)throw new IllegalArgumentException("tier 1..5");
        if(tier==2)return base;
        boolean pen=base.origen()==Origen.PENITENCIA;
        int[] cambios=pen?new int[]{-10,0,5,10,15}:new int[]{-5,0,2,5,10};
        int segundos=Math.clamp(base.segundos()+cambios[tier-1]*60,60,7200);
        int max=pen?new int[]{1,3,4,4,4}[tier-1]:new int[]{1,2,3,4,4}[tier-1];
        List<Efecto> items=new ArrayList<>();
        for(Efecto e:base.efectos())if(items.size()<max)items.add(e);
        for(String tipo:List.of("DARKNESS","HUNGER","NAUSEA","SLOWNESS")){
            if(items.size()>=max)break;
            if(items.stream().noneMatch(e->e.tipo().equals(tipo)))items.add(new Efecto(tipo,1));
        }
        if(tier>=4){
            for(int i=0;i<items.size();i++){
                var effect=items.get(i);
                if(effect.tipo().equals("WEAKNESS")||effect.tipo().equals("MINING_FATIGUE"))
                    items.set(i,new Efecto(effect.tipo(),2));
            }
        }
        return new Plan(base.origen(),base.motivo(),segundos,items);
    }
    public static boolean sancionablePenitencia(boolean seleccionada, boolean yaGanado,
        boolean derrotaVerificada, int segundosActivos, int limiteSegundos,
        boolean activarDerrota, boolean activarPlazo){
        return seleccionada && !yaGanado
            && ((activarDerrota&&derrotaVerificada)
                || (activarPlazo&&limiteSegundos>0&&segundosActivos>=limiteSegundos));
    }
    public static boolean sancionableF3(boolean proveedorReal,boolean reciboDurable,
        boolean derrotaVerificada,boolean cancelacionPorTiempo,boolean admin,
        boolean activarDerrota,boolean activarPlazo){
        return proveedorReal && reciboDurable && !admin
            && ((activarDerrota&&derrotaVerificada)||(activarPlazo&&cancelacionPorTiempo));
    }
    private BossDebuffPolicy(){}
}
