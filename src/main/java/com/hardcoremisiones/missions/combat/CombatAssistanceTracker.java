package com.hardcoremisiones.missions.combat;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** Atribución ambiental opt-in F2: no premia invocaciones ni golpes antiguos. */
public final class CombatAssistanceTracker {
    private static final long VENTANA_NANOS = 5_000_000_000L;
    private static final int MAX_MOBS = 2000;
    private static final int DISTANCIA = 32;
    private record Golpe(long nano, double acumulado, UUID mundo) { }
    private final Map<UUID,Map<UUID,Golpe>> aportes = new LinkedHashMap<>();

    public void dano(EntityDamageByEntityEvent evento) {
        if (!(evento.getEntity() instanceof LivingEntity victima) || victima instanceof Player) return;
        Player atacante = jugador(evento.getDamager());
        if (atacante == null || !atacante.getWorld().equals(victima.getWorld())) return;
        double dano = evento.getFinalDamage();
        if(!Double.isFinite(dano) || dano <= 0) return;
        long ahora=System.nanoTime();
        Map<UUID,Golpe> registro=aportes.computeIfAbsent(victima.getUniqueId(),k->new HashMap<>());
        Golpe previo=registro.get(atacante.getUniqueId());
        double acumulado = previo!=null && ahora-previo.nano()<VENTANA_NANOS
            ? Math.min(1000,previo.acumulado()+dano):dano;
        registro.put(atacante.getUniqueId(),new Golpe(ahora,acumulado,victima.getWorld().getUID()));
        if(aportes.size()>MAX_MOBS)aportes.remove(aportes.keySet().iterator().next());
    }
    private static Player jugador(Entity atacante) {
        if(atacante instanceof Player p)return p;
        if(atacante instanceof Projectile proyectil && proyectil.getShooter() instanceof Player p)return p;
        return null;
    }
    /** Exactamente un ganador: el mayor aporte reciente; empate por UUID estable. */
    public Player atribuir(LivingEntity mob) {
        Map<UUID,Golpe> registro=aportes.remove(mob.getUniqueId());
        if(registro==null)return null;
        long ahora=System.nanoTime();
        var atributo=mob.getAttribute(Attribute.MAX_HEALTH);
        double maximo=atributo==null?20:Math.max(1,atributo.getValue());
        UUID mejor=null;double mayor=0;
        for(var e:registro.entrySet()) {
            Golpe g=e.getValue();
            Player p=Bukkit.getPlayer(e.getKey());
            if(p==null || !p.isOnline() || !p.getWorld().equals(mob.getWorld())
                || !g.mundo().equals(mob.getWorld().getUID())
                || ahora-g.nano()>VENTANA_NANOS || ahora<g.nano()
                || p.getLocation().distanceSquared(mob.getLocation())>DISTANCIA*DISTANCIA
                || g.acumulado()<maximo*0.20)continue;
            if(g.acumulado()>mayor || (g.acumulado()==mayor
                && (mejor==null || e.getKey().compareTo(mejor)<0))) {
                mejor=e.getKey();mayor=g.acumulado();
            }
        }
        return mejor==null?null:Bukkit.getPlayer(mejor);
    }
    public void olvidar(UUID mob) { aportes.remove(mob); }
    public void limpiar() {
        long ahora=System.nanoTime();
        aportes.values().forEach(m->m.entrySet().removeIf(e->ahora-e.getValue().nano()>VENTANA_NANOS));
        aportes.entrySet().removeIf(e->e.getValue().isEmpty());
    }
}
