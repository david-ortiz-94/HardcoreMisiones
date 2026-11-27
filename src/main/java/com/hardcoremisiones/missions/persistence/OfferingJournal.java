package com.hardcoremisiones.missions.persistence;

import com.hardcoremisiones.storage.SessionYaml;

import com.hardcoremisiones.hm2.core.AtomicStorage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Comprobante durable de la interacción con el Sello del Juicio. El inventario
 * y el YAML de misiones no comparten una transacción: la interrupción queda
 * explícitamente EN REVISION; no se devuelven ni acreditan objetos a ciegas.
 */
public final class OfferingJournal {
    private record Pendiente(UUID id, UUID jugador, String mision, boolean grupal,
                             int cantidad, int progresoAnterior) { }
    private final File dataFolder;
    private final java.util.logging.Logger logger;
    private final java.util.function.Consumer<String> criticalFailure;
    private String sesion;
    private File archivo;
    private Pendiente pendiente;
    private final List<String> auditoria=new ArrayList<>();
    private boolean falloGuardado;

    public OfferingJournal(File dataFolder, java.util.logging.Logger logger, java.util.function.Consumer<String> criticalFailure) {
        this.dataFolder=java.util.Objects.requireNonNull(dataFolder);
        this.logger=java.util.Objects.requireNonNull(logger);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
    }

    public boolean cambiarSesion(String id) {
        try { if(id==null || !UUID.fromString(id).toString().equalsIgnoreCase(id))return false; }
        catch(IllegalArgumentException invalid){return false;}
        if (id.equals(sesion)) return true;
        File carpeta=new File(dataFolder,"sesiones");
        if (!carpeta.isDirectory() && !carpeta.mkdirs()) return false;
        File destino=new File(carpeta,id+"-ofrendas-f1-v01.yml");
        if(Files.isSymbolicLink(carpeta.toPath()) || Files.isSymbolicLink(destino.toPath()))
            return false;
        Pendiente nuevo=null;
        List<String> prev=new ArrayList<>();
        if (SessionYaml.exists(destino)) {
            try {
                YamlConfiguration y=SessionYaml.read(destino);
                if(!y.isInt("formato") || y.getInt("formato")!=1 || !y.isString("partida-id") || !id.equals(y.getString("partida-id"))) return false;
                if(y.contains("auditoria")) {
                    var rawAudit=y.getList("auditoria");
                    if(rawAudit==null || rawAudit.stream().anyMatch(v->!(v instanceof String)))return false;
                    prev.addAll(y.getStringList("auditoria"));
                }
                if (prev.size()>10000) return false;
                if(y.contains("pendiente")) {
                    if(!y.isConfigurationSection("pendiente") || !y.isString("pendiente.id")
                        || !y.isString("pendiente.jugador") || !y.isString("pendiente.mision")
                        || !y.isBoolean("pendiente.grupal") || !y.isInt("pendiente.cantidad")
                        || !y.isInt("pendiente.progreso-anterior"))return false;
                    String raw=y.getString("pendiente.id"), rawPlayer=y.getString("pendiente.jugador");
                    UUID tx=UUID.fromString(raw), player=UUID.fromString(rawPlayer);
                    if(!tx.toString().equalsIgnoreCase(raw)||!player.toString().equalsIgnoreCase(rawPlayer))return false;
                    String mision=y.getString("pendiente.mision","");
                    int n=y.getInt("pendiente.cantidad",0),before=y.getInt("pendiente.progreso-anterior",-1);
                    if(mision.isBlank() || mision.length()>128 || n<1 || n>4096 || before<0 || before>500000)
                        return false;
                    nuevo=new Pendiente(tx,player,mision,y.getBoolean("pendiente.grupal"),n,before);
                }
            } catch(RuntimeException ex) {
                logger.severe("Comprobante de ofrenda inválido: "+ex.getMessage());
                return false;
            }
        }
        sesion=id;archivo=destino;pendiente=nuevo;falloGuardado=false;
        auditoria.clear();auditoria.addAll(prev);
        if(pendiente!=null)logger.severe("F1: ofrenda en conciliación " + pendiente.id
                + ". /hm ofrendas revisar — NO acreditar automáticamente.");
        return true;
    }
    public boolean enRevision(){ return pendiente!=null || falloGuardado; }
    public String detalle() {
        if(falloGuardado)return "§cGuardado de comprobantes falló. Suspender ofrendas.";
        if(pendiente==null)return "§aSin ofrendas inciertas";
        return "§eComprobante="+pendiente.id+" §7jugador="+pendiente.jugador
            +" misión="+pendiente.mision+" grupal="+pendiente.grupal
            +" cantidad="+pendiente.cantidad+" progreso-anterior="+pendiente.progresoAnterior;
    }
    public UUID reservar(UUID jugador,String mision,boolean grupal,int cantidad,int progresoAnterior) {
        if(sesion==null || falloGuardado || pendiente!=null || jugador==null || mision==null || mision.isBlank() || mision.length()>128
            || cantidad<1 || cantidad>4096 || progresoAnterior<0 || progresoAnterior>500000)return null;
        Pendiente nuevo=new Pendiente(UUID.randomUUID(),jugador,mision,grupal,cantidad,progresoAnterior);
        pendiente=nuevo;
        if (guardar()) return nuevo.id;
        falloGuardado=true;
        return null;
    }
    public boolean terminar(UUID id,boolean confirmado,String administrador) {
        if(falloGuardado || pendiente==null || !pendiente.id.equals(id))return false;
        if(administrador!=null && administrador.isBlank())return false;
        Pendiente anterior=pendiente;
        String evento=Instant.now()+" tx="+id+" resultado="+(confirmado?"CONFIRMADO":"ABORTADO")
            +" operador="+(administrador==null?"SISTEMA":administrador);
        pendiente=null;
        auditoria.add(evento);
        String retirado=auditoria.size()>1000 ? auditoria.remove(0) : null;
        if(guardar())return true;
        auditoria.removeLast();
        if(retirado!=null)auditoria.addFirst(retirado);
        pendiente=anterior;
        falloGuardado=true;
        return false;
    }
    public boolean cerrarPorAdministrador(String operador) {
        if(operador==null || operador.isBlank() || pendiente==null || falloGuardado)return false;
        return terminar(pendiente.id,false,operador);
    }
    private boolean guardar(){
        if(archivo==null)return false;
        YamlConfiguration y=new YamlConfiguration();
        y.set("formato",1);y.set("partida-id",sesion);
        y.set("auditoria",new ArrayList<>(auditoria));
        if(pendiente!=null){
            y.set("pendiente.id",pendiente.id.toString());
            y.set("pendiente.jugador",pendiente.jugador.toString());
            y.set("pendiente.mision",pendiente.mision);
            y.set("pendiente.grupal",pendiente.grupal);
            y.set("pendiente.cantidad",pendiente.cantidad);
            y.set("pendiente.progreso-anterior",pendiente.progresoAnterior);
        }
        try {AtomicStorage.write(archivo.toPath(),y.saveToString());return true;}
        catch(IOException | RuntimeException ex){
            falloGuardado = true;
            logger.log(java.util.logging.Level.SEVERE,"F1: no se guardó el comprobante",ex);
            criticalFailure.accept("comprobantes de ofrenda");
            return false;
        }
    }
}
