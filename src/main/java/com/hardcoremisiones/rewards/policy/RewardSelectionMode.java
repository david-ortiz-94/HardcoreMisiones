package com.hardcoremisiones.rewards.policy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Política inmutable de adjudicación por misión (no cambia al abrir el Sello).
 * Individual: una decisión por jugador; grupal: una decisión para todo el equipo.
 * La decisión se guarda con el contrato de la MISIÓN, no en config.yml.
 */
public final class RewardSelectionMode {
    public enum Modo { MANUAL, SORTEO }
    private RewardSelectionMode() { }
    public static Modo decidir(String partida, int diaAsignado, String mision, String destinatario,
                        int probabilidadManual) {
        if (partida==null || partida.isBlank() || mision==null || mision.isBlank()
            || destinatario==null || destinatario.isBlank() || diaAsignado<1
            || probabilidadManual<0 || probabilidadManual>100)
            throw new IllegalArgumentException("Semilla o probabilidad de modalidad inválida");
        return ticket(partida,diaAsignado,mision,destinatario)<probabilidadManual
            ?Modo.MANUAL:Modo.SORTEO;
    }
    public static int ticket(String partida,int diaAsignado,String mision,String destinatario) {
        if(partida==null||partida.isBlank()||mision==null||mision.isBlank()
            ||destinatario==null||destinatario.isBlank()||diaAsignado<1)
            throw new IllegalArgumentException("Semilla inválida");
        try {
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(
                ("HM2|MODALIDAD|V1|"+partida+"|"+diaAsignado+"|"+mision+"|"+destinatario)
                    .getBytes(StandardCharsets.UTF_8));
            return Math.floorMod(ByteBuffer.wrap(hash).getInt(),100);
        } catch(NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    static String texto(Modo modo) { return modo==Modo.MANUAL?"Elección manual": "Sorteo automático"; }
}
