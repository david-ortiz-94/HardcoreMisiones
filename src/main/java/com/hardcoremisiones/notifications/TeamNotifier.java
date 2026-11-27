package com.hardcoremisiones.notifications;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/** Contrato explícito LAN: misiones y premios propios PRIVADOS;
 *  jornadas, penitencias (incluidas individuales), oleadas y jefes PÚBLICOS.
 *  Nunca concede objetos ni modifica el progreso. El servidor/console mantiene auditoría.
 */
public final class TeamNotifier {
    public enum Alcance { PERSONAL, EQUIPO, ENCUENTRO }
    private TeamNotifier() { }
    public static Set<UUID> destinatarios(Alcance alcance, UUID titular, Set<UUID> presentes, Set<UUID> encuentro) {
        Objects.requireNonNull(alcance);
        Objects.requireNonNull(presentes);
        Set<UUID> ids = new LinkedHashSet<>(presentes);
        ids.remove(null);
        return switch (alcance) {
            case PERSONAL -> titular != null && ids.contains(titular) ? Set.of(titular) : Set.of();
            case EQUIPO -> Set.copyOf(ids);
            case ENCUENTRO -> {
                if(encuentro == null) yield Set.of();
                ids.retainAll(encuentro);
                yield Set.copyOf(ids);
            }
        };
    }
    public static void equipo(Set<UUID> registrados,String mensaje){enviar(Alcance.EQUIPO,null,registrados,null,mensaje);}
    public static void personal(UUID titular,String mensaje){if(titular!=null)enviar(Alcance.PERSONAL,titular,Set.of(titular),null,mensaje);}
    public static void enviar(Alcance alcance, UUID titular, Set<UUID> presentes, Set<UUID> encuentro, String mensaje) {
        if (mensaje==null || mensaje.isBlank()) return;
        for (UUID id:destinatarios(alcance,titular,presentes,encuentro)) {
            Player jugador=Bukkit.getPlayer(id);
            if(jugador!=null && jugador.isOnline()) jugador.sendMessage(mensaje);
        }
    }
}
