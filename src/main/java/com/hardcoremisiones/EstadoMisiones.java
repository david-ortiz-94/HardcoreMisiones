package com.hardcoremisiones;

import com.hardcoremisiones.missions.group.GroupBalancePolicy;

import com.hardcoremisiones.rewards.model.MissionRewardContract;
import com.hardcoremisiones.rewards.missions.MissionRewardFactory;
import com.hardcoremisiones.missions.persistence.FrozenMissionCodec;

import com.hardcoremisiones.missions.catalog.MissionCatalog;
import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import com.hardcoremisiones.rewards.policy.RewardSelectionMode;
import com.hardcoremisiones.storage.SessionYaml;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Material;
import com.hardcoremisiones.hm2.core.TeamContributionPolicy;
import com.hardcoremisiones.hm2.core.AtomicStorage;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

/** Misiones asociadas exclusivamente al identificador del mundo HardcoreMP. */
final class EstadoMisiones {
    static final class Progreso {
        final MissionDefinition mision;
        final int objetivo;
        int actual;
        final String nombre;
        int participantesRequeridos;
        final Map<UUID,Integer> aportes = new LinkedHashMap<>();
        // Contrato F2: se congela con el objetivo al asignar. NULL = legado F1.
        MissionProfile perfil;
        MissionRewardContract.Plan plan;
        com.hardcoremisiones.rewards.hunts.HuntRewardPolicy huntPolicy;
        // Null en misiones antiguas: preservar la regla histórica; no migrar ofertas vivas.
        RewardSelectionMode.Modo modalidadPremio;
        int diaModalidad;
        int ticketModalidad=-1;
        int probManualModalidad=-1;
        String balanceGrupal=""; // descripción congelada solo para asignaciones RC3
        final Set<UUID> relevados = new java.util.HashSet<>();
        Progreso(MissionDefinition mision, int objetivo, int actual, String nombre) {
            this.mision=mision; this.objetivo=objetivo; this.actual=Math.max(0,Math.min(actual,objetivo)); this.nombre=nombre;
        }
        boolean completa() {
            long aportantes=aportes.keySet().stream().filter(id->!relevados.contains(id)).count();
            return actual>=objetivo && (!mision.todosAportan() || participantesRequeridos<=1
                || aportantes>=participantesRequeridos);
        }
        int faltan() { return Math.max(0,objetivo-actual); }
        int puedeAportar(UUID id) {
            if (completa() || relevados.contains(id)) return 0;
            int activos=(int)aportes.keySet().stream().filter(uid->!relevados.contains(uid)).count();
            return TeamContributionPolicy.maxAdditional(objetivo, actual, participantesRequeridos,
                mision.todosAportan(), aportes.getOrDefault(id, 0), activos, aportes.containsKey(id));
        }
        Progreso copiar() {
            Progreso p=new Progreso(mision,objetivo,actual,nombre);
            p.participantesRequeridos=participantesRequeridos; p.aportes.putAll(aportes);
            p.perfil=perfil; p.plan=plan; p.huntPolicy=huntPolicy; p.relevados.addAll(relevados);
            p.modalidadPremio=modalidadPremio; p.diaModalidad=diaModalidad;
            p.ticketModalidad=ticketModalidad; p.probManualModalidad=probManualModalidad;
            p.balanceGrupal=balanceGrupal;
            return p;
        }
    }
    private final java.io.File dataFolder;
    private final java.util.logging.Logger logger;
    private final java.util.function.Supplier<org.bukkit.configuration.file.FileConfiguration> config;
    private final java.util.function.Consumer<String> criticalFailure;
    private String sesion;
    private File archivo;
    private int etapa=1;
    private int diaAsignado; // 0 para rc3 sin marcador; a partir de F1 detecta transición parcial
    private Progreso grupal;
    private Map<UUID,Progreso> personales=new HashMap<>();
    private String anteriorGrupal="";
    private Map<UUID,String> anterioresPersonales=new HashMap<>();
    private List<String> recientesGrupo=new ArrayList<>();
    private Map<UUID,List<String>> recientesPersonales=new HashMap<>();
    private final Map<UUID,Double> distanciaFraccion=new HashMap<>();
    private List<String> auditoriaRelevos=new ArrayList<>();

    EstadoMisiones(java.io.File dataFolder, java.util.logging.Logger logger,
                  java.util.function.Supplier<org.bukkit.configuration.file.FileConfiguration> config,
                  java.util.function.Consumer<String> criticalFailure) {
        this.dataFolder=java.util.Objects.requireNonNull(dataFolder);
        this.logger=java.util.Objects.requireNonNull(logger);
        this.config=java.util.Objects.requireNonNull(config);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
    }
    boolean cambiarSesion(String id) {
        if (id==null || !id.matches("[0-9a-fA-F-]{36}")) return false;
        if (id.equals(sesion)) return true;
        File carpeta=new File(dataFolder,"sesiones");
        if (!carpeta.isDirectory() && !carpeta.mkdirs()) return false;
        File destino=new File(carpeta,id+"-misiones-v03.yml"); // mantener archivos existentes
        int nuevaEtapa=1, nuevoDia=0;
        Progreso grupo=null;
        Map<UUID,Progreso> individuos=new HashMap<>();
        String ultimoGrupo="";
        Map<UUID,String> ultimos=new HashMap<>();
        List<String> historicoGrupo=new ArrayList<>();
        List<String> relevosRecuperados=new ArrayList<>();
        Map<UUID,List<String>> historicoPersonales=new HashMap<>();
        if (SessionYaml.exists(destino)) {
            try {
                YamlConfiguration y=SessionYaml.read(destino);
                if (!id.equals(y.getString("partida-id",""))) return false;
                nuevaEtapa=Math.max(1,Math.min(4,y.getInt("etapa-pruebas",1)));
                nuevoDia=y.getInt("dia-asignado",0);
                if(nuevoDia<0 || nuevoDia>1000000) return false;
                ultimoGrupo=y.getString("anterior-grupal","");
                relevosRecuperados.addAll(y.getStringList("auditoria-relevos-f2"));
                historicoGrupo.addAll(y.getStringList("recientes-grupo"));
                if (historicoGrupo.isEmpty() && !ultimoGrupo.isBlank()) historicoGrupo.add(ultimoGrupo);
                grupo=leerProgreso(y,"grupal","Equipo");
                var seccion=y.getConfigurationSection("individuales");
                if (seccion!=null) for (String clave:seccion.getKeys(false)) {
                    UUID uid=UUID.fromString(clave);
                    Progreso p=leerProgreso(y,"individuales."+clave,y.getString("individuales."+clave+".nombre",clave));
                    if (p!=null) individuos.put(uid,p);
                }
                var anteriores=y.getConfigurationSection("anteriores");
                if (anteriores!=null) for (String clave:anteriores.getKeys(false))
                    ultimos.put(UUID.fromString(clave),anteriores.getString(clave,""));
                var recientes=y.getConfigurationSection("recientes-individuales");
                if (recientes!=null) for (String clave:recientes.getKeys(false))
                    historicoPersonales.put(UUID.fromString(clave),new ArrayList<>(recientes.getStringList(clave)));
                for (var entry:ultimos.entrySet())
                    historicoPersonales.putIfAbsent(entry.getKey(),new ArrayList<>(List.of(entry.getValue())));
            } catch (RuntimeException ex) {
                logger.log(java.util.logging.Level.SEVERE,"No puedo recuperar misiones de partida: "+id,ex);
                return false;
            }
        }
        if (grupo!=null && (!individuos.keySet().containsAll(grupo.aportes.keySet())
            || !individuos.keySet().containsAll(grupo.relevados))) {
            logger.severe("Aportes grupales ajenos a participantes; se suspende recuperación.");
            return false;
        }
        sesion=id; archivo=destino; etapa=nuevaEtapa; diaAsignado=nuevoDia; grupal=grupo; personales=individuos;
        anteriorGrupal=ultimoGrupo; anterioresPersonales=ultimos;
        recientesGrupo=historicoGrupo; recientesPersonales=historicoPersonales;
        auditoriaRelevos=relevosRecuperados;
        distanciaFraccion.clear();
        return true;
    }
    private Progreso leerProgreso(YamlConfiguration y,String base,String nombre) {
        if(!y.contains(base))return null;
        if(!y.isConfigurationSection(base)||!y.isString(base+".id"))
            throw new IllegalArgumentException("Misión sin ID: "+base);
        String id=y.getString(base+".id");
        if(id.isBlank())throw new IllegalArgumentException("Misión sin ID: "+base);
        int objetivo=y.getInt(base+".objetivo",0);
        if(!y.isInt(base+".objetivo")||objetivo<1||objetivo>500000)
            throw new IllegalArgumentException("Objetivo inválido: "+id);
        var frozen=FrozenMissionCodec.read(y,base+".contrato-f2",id);
        MissionDefinition m=frozen==null?MissionCatalog.buscar(id):frozen.definition();
        if(m==null)throw new IllegalArgumentException("La misión histórica sin contrato "+id+" fue eliminada de catalogo.yml");
        int actual=y.getInt(base+".progreso",0);
        if(y.contains(base+".progreso")&&!y.isInt(base+".progreso")||actual<0||actual>objetivo)
            throw new IllegalArgumentException("Progreso fuera del contrato: "+id);
        Progreso p=new Progreso(m,objetivo,actual,nombre);
        p.balanceGrupal=y.getString(base+".balance-grupal-rc3","");
        if(frozen!=null){p.perfil=frozen.profile();p.plan=frozen.plan();}
        if(y.contains(base+".dia-caceria")){if(m.tipo()!=MissionType.CAZA_JEFE||!y.isInt(base+".dia-caceria")||y.getInt(base+".dia-caceria")<1)throw new IllegalArgumentException("Día de cacería inválido");p.diaModalidad=y.getInt(base+".dia-caceria");}
        if(m.tipo()==MissionType.CAZA_JEFE)p.huntPolicy=com.hardcoremisiones.rewards.hunts.HuntRewardYaml.read(y,base+".politica-caceria");
        else if(y.contains(base+".politica-caceria"))throw new IllegalArgumentException("Política de cacería en misión normal");
        if(y.contains(base+".modalidad-recompensa.modo",true)) {
            p.modalidadPremio=RewardSelectionMode.Modo.valueOf(y.getString(base+".modalidad-recompensa.modo",""));
            p.diaModalidad=y.getInt(base+".modalidad-recompensa.dia-asignado",-1);
            p.ticketModalidad=y.getInt(base+".modalidad-recompensa.ticket",-1);
            p.probManualModalidad=y.getInt(base+".modalidad-recompensa.probabilidad-manual",-1);
            if(p.ticketModalidad<0 || p.ticketModalidad>99
                || p.probManualModalidad<0 || p.probManualModalidad>100)
                throw new IllegalArgumentException("Sorteo de modalidad incompleto o adulterado");
            if((p.ticketModalidad<p.probManualModalidad)!=(p.modalidadPremio==RewardSelectionMode.Modo.MANUAL))
                throw new IllegalArgumentException("Modalidad no coincide con su sorteo guardado");
            if(p.plan==null || !"w45-v1".equals(p.plan.version()) || p.diaModalidad<1
                || p.diaModalidad>1000000)
                throw new IllegalArgumentException("Modalidad de recompensa incompatible o inválida");
        }
        p.participantesRequeridos=y.getInt(base+".participantes-requeridos",0);
        var ap=y.getConfigurationSection(base+".aportes");
        if (ap!=null) for(String clave:ap.getKeys(false)) {
            int aporte=ap.getInt(clave,0);
            if(aporte<1 || aporte>objetivo) throw new IllegalArgumentException("Aporte inválido: "+id);
            p.aportes.put(UUID.fromString(clave),aporte);
        }
        for(String rel:y.getStringList(base+".relevados-f2")) p.relevados.add(UUID.fromString(rel));
        if (p.relevados.size()>Math.max(0,3-p.participantesRequeridos))
            throw new IllegalArgumentException("Relevos grupales inconsistentes: "+id);
        if (p.aportes.values().stream().mapToLong(Integer::longValue).sum()>p.actual)
            throw new IllegalArgumentException("Aportes mayores que progreso: "+id);
        return p;
    }
    boolean estaLista(){ return sesion!=null && archivo!=null; }
    String idSesion(){ return sesion; }
    int etapa(){ return etapa; }
    int diaAsignado(){ return diaAsignado; }
    Progreso grupo(){ return grupal; }
    Progreso personal(UUID id){ return personales.get(id); }
    /** Relevo administrativo documentado: no elimina el UUID ni su progreso personal. */
    boolean relevarAusente(UUID id,String administrador) {
        if (id==null || administrador==null || administrador.isBlank() || grupal==null
            || !grupal.mision.todosAportan() || !personales.containsKey(id)
            || grupal.relevados.contains(id) || grupal.aportes.containsKey(id)
            || grupal.participantesRequeridos<=1 || grupal.completa())return false;
        Progreso anterior=grupal.copiar();
        String acta=java.time.Instant.now()+" administrador="+administrador+" releva="+id
            +" mision="+grupal.mision.id();
        grupal.relevados.add(id); grupal.participantesRequeridos--;
        auditoriaRelevos.add(acta);
        if (guardar()) return true;
        auditoriaRelevos.remove(acta);grupal=anterior;return false;
    }
    Progreso personalPorNombre(String nombre) {
        for (Progreso p:personales.values()) if (p.nombre.equalsIgnoreCase(nombre)) return p;
        return null;
    }
    List<String> avisosModalidad(UUID id, int diaActual) {
        Progreso individual=personal(id), grupo=grupal;
        List<String> lineas=new ArrayList<>();
        lineas.add("§6☾ Día "+Math.max(1,diaActual)+" — Recompensas de tus misiones:");
        lineas.add(mensajeModalidad("Individual",individual,false));
        lineas.add(mensajeModalidad("Grupal",grupo,true));
        lineas.add("§7Clic derecho al Sello: ver paquetes, plazos y selección.");
        return List.copyOf(lineas);
    }
    private static String mensajeModalidad(String tipo,Progreso p,boolean grupal) {
        if(p==null)return "§7"+tipo+": sin misión asignada";
        if(p.modalidadPremio==null)return "§e"+tipo+": reglas de elección anteriores (contrato conservado)";
        String detalle=p.modalidadPremio==RewardSelectionMode.Modo.MANUAL
            ? "§aMANUAL §7— elegí un paquete entre tres"
            : "§dALEATORIO §7— una de tres cartas se sortea al completar";
        return "§6"+tipo+": "+detalle+(grupal?" §8(vigente hasta renovar grupal)":"");
    }
        Set<UUID> participantesIds(){ return Set.copyOf(personales.keySet()); }
    UUID participantePorNombre(String nombre) {
        for(var e:personales.entrySet()) if(e.getValue().nombre.equalsIgnoreCase(nombre))return e.getKey();
        return null;
    }

    private static List<String> historial(List<String> prev,String id) {
        List<String> lista=new ArrayList<>(prev);
        if (id!=null && !id.isBlank()) lista.add(0,id);
        if (lista.size()>3) return new ArrayList<>(lista.subList(0,3));
        return lista;
    }
    boolean renovarDia(List<Player> jugadores,boolean renovarGrupo,int nuevoDia,boolean diaDeTregua) {
        if (!estaLista() || personales.isEmpty()) return false;
        Progreso grupoAnterior=grupal==null?null:grupal.copiar();
        Map<UUID,Progreso> antiguos=copiarPersonales(personales);
        Map<UUID,String> ultimos=new HashMap<>(anterioresPersonales);
        String previoGrupo=anteriorGrupal;
        List<String> prevGrupo=new ArrayList<>(recientesGrupo);
        Map<UUID,List<String>> prevInd=copiarHistorial(recientesPersonales);
        int viejaEtapa=etapa, viejoDia=diaAsignado;
        etapa=Math.max(etapa, nuevoDia<=3?1:nuevoDia<=9?2:nuevoDia<=19?3:4);
        diaAsignado=nuevoDia;
        try {
            if (renovarGrupo || grupal==null) asignarGrupal(jugadores, antiguos.size());
            for(var e:personales.entrySet()) {
                anterioresPersonales.put(e.getKey(),e.getValue().mision.id());
                recientesPersonales.put(e.getKey(),historial(recientesPersonales.getOrDefault(e.getKey(),List.of()),e.getValue().mision.id()));
            }
            personales.clear();
            List<String> elegidas=new ArrayList<>();
            // Los UUID guardados son el grupo del ciclo; NO basar el nuevo reparto
            // en la lista de jugadores conectados (puede estar vacía).
            List<UUID> ids=new ArrayList<>(antiguos.keySet());
            ids.sort(java.util.Comparator.comparing(id -> antiguos.get(id).nombre));
            for (UUID id:ids) {
                MissionDefinition m;
                if (diaDeTregua) m=MissionCatalog.buscar("esp_i04");
                else {
                    List<String> evitar=new ArrayList<>(elegidas);
                    evitar.addAll(recientesPersonales.getOrDefault(id,List.of()));
                    m=seleccionar(jugadores,false,antiguos.size(),id,evitar,
                        grupal==null?"":grupal.mision.recurso(),
                        grupal==null?"":MissionCatalog.familia(grupal.mision),etapa,nuevoDia);
                }
                if (m==null) throw new IllegalStateException("Catálogo sin misiones individuales para etapa "+etapa);
                Progreso anterior=antiguos.get(id);
                personales.put(id,nuevoProgreso(m,m.cantidad(),
                    (diaDeTregua || m.tipo()==MissionType.TREGUA)?m.cantidad():0,anterior.nombre,false,id));
                elegidas.add(m.id());
            }
            if (guardar()) {
                if(renovarGrupo || grupoAnterior==null) logOfertasW45(grupal,"EQUIPO");
                personales.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e->logOfertasW45(e.getValue(),e.getKey().toString()));
                return true;
            }
        } catch(RuntimeException ex) { logger.severe("Fallo al renovar misiones: "+ex.getMessage()); }
        grupal=grupoAnterior; personales=antiguos; anterioresPersonales=ultimos; anteriorGrupal=previoGrupo;
        recientesGrupo=prevGrupo; recientesPersonales=prevInd; etapa=viejaEtapa; diaAsignado=viejoDia;
        return false;
    }
    private MissionDefinition seleccionar(List<Player> jugadores,boolean grupo,int roster,UUID owner,List<String> recientes,String evitar,String familia,int etapa,int dia){
        var hunt=com.hardcoremisiones.missions.catalog.HuntAssignmentPolicy.select(config.get(),dia,etapa,grupo,jugadores,roster,owner,recientes,evitar,java.util.concurrent.ThreadLocalRandom.current());
        if(hunt!=null)return hunt;
        return MissionCatalog.elegirF2(MissionCatalog.elegibles(etapa,jugadores,grupo),recientes,evitar,familia,etapa,dia);
    }
    private void asignarGrupal(List<Player> jugadores) { asignarGrupal(jugadores, jugadores.size()); }
    private void asignarGrupal(List<Player> jugadores, int participantesTotales) {
        if (grupal!=null) {
            anteriorGrupal=grupal.mision.id();
            recientesGrupo=historial(recientesGrupo,anteriorGrupal);
        }
        var m=seleccionar(jugadores,true,participantesTotales,null,recientesGrupo,
            "","",etapa,Math.max(1,diaAsignado));
        if (m==null) throw new IllegalStateException("Catálogo sin misiones grupales etapa "+etapa);
        int equipo=Math.clamp(participantesTotales,1,3);
        MissionProfile perfil=MissionCatalog.f2Activo()&&m.tipo()!=MissionType.CAZA_JEFE?MissionCatalog.perfil(m):null;
        GroupBalancePolicy.Resultado ajuste=perfil==null?null:
            GroupBalancePolicy.calcular(m,perfil,equipo,Math.max(1,diaAsignado),config.get());
        int objetivo=ajuste==null?m.objetivo(equipo):ajuste.objetivo();
        grupal=nuevoProgreso(m,objetivo,0,"Equipo",true,null);
        grupal.participantesRequeridos=participantesTotales;
        if(ajuste!=null)grupal.balanceGrupal=ajuste.descripcion();
    }
    boolean renovarDia(List<Player> jugadores,boolean renovarGrupo) {
        return renovarDia(jugadores,renovarGrupo,1,false);
    }
    boolean completo() {
        return grupal!=null && grupal.completa() && !personales.isEmpty()
            && personales.values().stream().allMatch(Progreso::completa);
    }
    boolean cambiarEtapa(int nueva) {
        if (nueva<1||nueva>4||!estaLista()) return false;
        int antes=etapa; etapa=nueva;
        if (guardar()) return true;
        etapa=antes; return false;
    }
    boolean iniciar(List<Player> jugadores,boolean rondaNueva) {
        if (!estaLista() || jugadores.isEmpty() || (rondaNueva && grupal!=null && !completo())) return false;
        Progreso oldG=grupal==null?null:grupal.copiar(); Map<UUID,Progreso> oldP=copiarPersonales(personales);
        var oldI=copiarHistorial(recientesPersonales); var oldGR=new ArrayList<>(recientesGrupo);
        int oldDia=diaAsignado;
        if (diaAsignado==0) diaAsignado=1;
        if (rondaNueva) {
            for (var e:personales.entrySet()) recientesPersonales.put(e.getKey(),historial(recientesPersonales.getOrDefault(e.getKey(),List.of()),e.getValue().mision.id()));
            personales.clear();
        }
        if (grupal==null || rondaNueva) asignarGrupal(jugadores);
        List<String> usadas=new ArrayList<>();
        for (Progreso p:personales.values()) usadas.add(p.mision.id());
        for (Player jugador:jugadores) {
            if(personales.containsKey(jugador.getUniqueId())) continue;
            List<String> excluidas=new ArrayList<>(usadas);
            excluidas.addAll(recientesPersonales.getOrDefault(jugador.getUniqueId(),List.of()));
            MissionDefinition m=seleccionar(jugadores,false,(int)java.util.stream.Stream.concat(personales.keySet().stream(),jugadores.stream().map(Player::getUniqueId)).distinct().count(),jugador.getUniqueId(),excluidas,
                grupal.mision.recurso(),MissionCatalog.familia(grupal.mision),etapa,Math.max(1,diaAsignado));
            if (m==null) { personales=oldP; grupal=oldG; diaAsignado=oldDia; return false; }
            personales.put(jugador.getUniqueId(),nuevoProgreso(m,m.cantidad(),
                m.tipo()==MissionType.TREGUA?m.cantidad():0,jugador.getName(),false,jugador.getUniqueId()));
            usadas.add(m.id());
        }
        if (guardar()) {
            if(oldG==null || rondaNueva)logOfertasW45(grupal,"EQUIPO");
            personales.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e->{
                if(!oldP.containsKey(e.getKey()) || rondaNueva)
                    logOfertasW45(e.getValue(),e.getKey().toString());
            });
            return true;
        }
        grupal=oldG;personales=oldP;recientesGrupo=oldGR;recientesPersonales=oldI;diaAsignado=oldDia;
        return false;
    }

    /** Audita solo después del checkpoint durable; cada línea explica el peso y el
     * ticket de la carta generada. No se registran sorteos fallidos como ofertas válidas. */
    private void logOfertasW45(Progreso progreso,String destinatario) {
        if(progreso==null)return;
        if("EQUIPO".equals(destinatario) && !progreso.balanceGrupal.isBlank())
            logger.info("[HM2_BALANCE_GRUPAL_RC3] partida="+sesion
                +" dia="+diaAsignado+" mision="+progreso.mision.id()
                +" participantes="+progreso.participantesRequeridos
                +" dificultad="+progreso.perfil.dificultad()+" riesgo="+progreso.perfil.riesgo()
                +" "+progreso.balanceGrupal+" objetivo_congelado=true");
        if(progreso.plan==null || !"w45-v1".equals(progreso.plan.version()))return;
        if(progreso.modalidadPremio!=null) logger.info(
            "[HM2_MODALIDAD_PREMIO] evento=ASIGNADA partida="+sesion
            +" dia="+progreso.diaModalidad+" mision="+progreso.mision.id()
            +" destinatario="+destinatario+" modo="+progreso.modalidadPremio
            +" probabilidad_manual="+progreso.probManualModalidad+"%"
            +" probabilidad_aleatoria="+(100-progreso.probManualModalidad)+"%"
            +" ticket="+progreso.ticketModalidad+"/100"
            +" modo_congelado=true");
        for(int i=0;i<progreso.plan.opciones().size();i++) {
            var o=progreso.plan.opciones().get(i);
            String lote=o.cantidad()+"x "+o.material();
            for(var x:o.extras())lote+=" + "+x.cantidad()+"x "+x.material();
            if(!o.beneficio().isEmpty())lote+=" beneficio="+o.beneficio();
            logger.info("[HM2_LOOT_W45] evento=OFERTA_GUARDADA partida="+sesion
                +" dia="+diaAsignado+" mision="+progreso.mision.id()
                +" destinatario="+destinatario+" opcion="+(i+1)
                +" presupuesto="+progreso.plan.presupuesto()+" lote="+lote
                +" XP="+o.xp()+" prestigio="+o.prestigio()+" sorteo={"+o.sorteo()+"}");
        }
    }
    private Progreso nuevoProgreso(MissionDefinition m,int objetivo,int actual,String nombre,boolean equipo,UUID jugador) {
        Progreso p=new Progreso(m,objetivo,actual,nombre);
        if(m.tipo()==MissionType.CAZA_JEFE){
            p.perfil=com.hardcoremisiones.missions.catalog.HuntAssignmentPolicy.profile(m,config.get());p.diaModalidad=Math.max(1,diaAsignado);
            p.huntPolicy=com.hardcoremisiones.rewards.hunts.HuntRewardYaml.read(config.get(),"misiones.cacerias-hf5.politica-premios");
        }else if(MissionCatalog.f2Activo()) {
            p.perfil=MissionCatalog.perfil(m);
            p.plan=MissionRewardFactory.crear(m,p.perfil,objetivo,equipo,config.get(),
                sesion,Math.max(1,diaAsignado),equipo?"GRUPO":jugador.toString());
            if("w45-v1".equals(p.plan.version()) && config.get().getBoolean(
                    "recompensas.modalidades-por-mision.habilitadas",true)) {
                int porcentaje=Math.clamp(config.get().getInt(
                    "recompensas.modalidades-por-mision.probabilidad-manual",50),0,100);
                p.diaModalidad=Math.max(1,diaAsignado);
                p.probManualModalidad=porcentaje;
                p.ticketModalidad=RewardSelectionMode.ticket(sesion,p.diaModalidad,m.id(),
                    equipo?"EQUIPO":jugador.toString());
                p.modalidadPremio=p.ticketModalidad<porcentaje
                    ?RewardSelectionMode.Modo.MANUAL:RewardSelectionMode.Modo.SORTEO;
            }
        }
        return p;
    }
    boolean entregar(UUID id, boolean grupo,int cantidad) {
        // F1: solo los UUID registrados pueden contribuir al objetivo grupal.
        if (grupo && !personales.containsKey(id)) return false;
        Progreso p=grupo?grupal:personales.get(id);
        if (p==null || p.mision.tipo()!=MissionType.ENTREGA || cantidad<1 || cantidad>p.puedeAportar(id)) return false;
        p.actual+=cantidad;
        if (grupo) p.aportes.merge(id,cantidad,Integer::sum);
        if(guardar()) return true;
        p.actual-=cantidad;
        if(grupo) { int v=p.aportes.getOrDefault(id,0)-cantidad; if(v<=0)p.aportes.remove(id);else p.aportes.put(id,v); }
        return false;
    }
    /** Una acción real puede acreditar individual y grupal simultáneamente.
     * Se escribe UN checkpoint para ambas; si el disco falla, ambas retroceden.
     * No acreditar a quien no pertenezca a la partida activa.
     */
    private boolean sumarAccion(UUID id, int cantidad, boolean contarPersonal, boolean contarGrupo) {
        if (id == null || cantidad < 1 || !personales.containsKey(id)) return false;
        Progreso personal = personales.get(id);
        Progreso grupo = grupal;
        int nPersonal = contarPersonal && personal != null && !personal.completa()
            ? Math.min(cantidad, personal.puedeAportar(id)) : 0;
        int nGrupo = contarGrupo && grupo != null && !grupo.completa()
            ? Math.min(cantidad, grupo.puedeAportar(id)) : 0;
        if (nPersonal <= 0 && nGrupo <= 0) return false;
        int previoAporte = grupo == null ? 0 : grupo.aportes.getOrDefault(id, 0);
        boolean guardado = false;
        try {
            if (nPersonal > 0) personal.actual += nPersonal;
            if (nGrupo > 0) {
                grupo.actual += nGrupo;
                grupo.aportes.put(id, previoAporte + nGrupo);
            }
            guardado = guardar();
            return guardado;
        } finally {
            if (!guardado) {
                if (nPersonal > 0) personal.actual -= nPersonal;
                if (nGrupo > 0) {
                    grupo.actual -= nGrupo;
                    if (previoAporte == 0) grupo.aportes.remove(id);
                    else grupo.aportes.put(id, previoAporte);
                }
            }
        }
    }
    /** HF5: únicamente WON durable + daño del propietario pueden llegar aquí.
     * Este método no consulta entidades ni puede fabricar victorias por nombres.
     */
    boolean contarJefeVerificado(UUID dueño,String boss,boolean equipo){
        if(dueño==null||boss==null||boss.isBlank())return false;
        Progreso personal=personales.get(dueño);
        boolean individual=!equipo && personal!=null && !personal.completa()
            && personal.mision.tipo()==MissionType.CAZA_JEFE
            && personal.mision.recurso().equals(boss);
        boolean grupalElegible=equipo && grupal!=null && !grupal.completa()
            && grupal.mision.tipo()==MissionType.CAZA_JEFE
            && grupal.mision.recurso().equals(boss)&&personales.containsKey(dueño);
        return sumarAccion(dueño,1,individual,grupalElegible);
    }
    boolean contarCaza(UUID id, EntityType tipo) {
        Progreso p = personales.get(id);
        return sumarAccion(id, 1, p != null && p.mision.admite(tipo),
            grupal != null && grupal.mision.admite(tipo));
    }
    boolean contarMineria(UUID id, Material bloque) {
        Progreso p = personales.get(id);
        return sumarAccion(id, 1, p != null && p.mision.admiteBloque(bloque),
            grupal != null && grupal.mision.admiteBloque(bloque));
    }
    boolean contarFabricacion(UUID id, Material resultado, int cantidad) {
        Progreso p = personales.get(id);
        return sumarAccion(id, cantidad, p != null && p.mision.admiteFabricacion(resultado),
            grupal != null && grupal.mision.admiteFabricacion(resultado));
    }
    boolean contarEncantamiento(UUID id) {
        Progreso p = personales.get(id);
        return sumarAccion(id, 1, p != null && p.mision.tipo() == MissionType.ENCANTAR,
            grupal != null && grupal.mision.tipo() == MissionType.ENCANTAR);
    }
    boolean contarDistancia(UUID id, double distancia) {
        Progreso p = personales.get(id);
        boolean cuentaPersonal = p != null && p.mision.tipo() == MissionType.RECORRER && !p.completa();
        boolean cuentaGrupo = grupal != null && grupal.mision.tipo() == MissionType.RECORRER && !grupal.completa();
        if ((!cuentaPersonal && !cuentaGrupo) || !personales.containsKey(id)) return false;
        // El muestreador acepta desplazamientos acumulados de hasta 24 bloques
        // entre ticks válidos. Rechazarlos aquí haría perder progreso legítimo
        // ante lag o intervalos largos; el filtro anti-teleport ya se ejecutó.
        if (!Double.isFinite(distancia) || distancia <= 0 || distancia > 24) return false;
        double anterior = distanciaFraccion.getOrDefault(id, 0.0);
        double acumulado = anterior + distancia;
        int bloques = ((int) acumulado / 5) * 5;
        if (bloques == 0) {
            distanciaFraccion.put(id, acumulado);
            return false;
        }
        // Si el checkpoint de las misiones falla, no consumir los metros
        // pendientes: el próximo evento podrá volver a registrar el tramo.
        if (!sumarAccion(id, bloques, cuentaPersonal, cuentaGrupo)) {
            distanciaFraccion.put(id, acumulado); // retener tramo sin acreditar para reintento
            return false;
        }
        distanciaFraccion.put(id, acumulado - bloques);
        return true;
    }
    private static Map<UUID,Progreso> copiarPersonales(Map<UUID,Progreso> original) {
        Map<UUID,Progreso> out=new HashMap<>();
        original.forEach((id,p)->out.put(id,p.copiar()));return out;
    }
    private static Map<UUID,List<String>> copiarHistorial(Map<UUID,List<String>> original) {
        Map<UUID,List<String>> out=new HashMap<>();
        original.forEach((id,list)->out.put(id,new ArrayList<>(list)));return out;
    }
    private static void escribir(YamlConfiguration y,String base,Progreso p) {
        if(p==null)return;
        y.set(base+".id",p.mision.id());y.set(base+".objetivo",p.objetivo);
        y.set(base+".progreso",p.actual);y.set(base+".nombre",p.nombre);
        if(!p.balanceGrupal.isBlank())y.set(base+".balance-grupal-rc3",p.balanceGrupal);
        y.set(base+".participantes-requeridos",p.participantesRequeridos);
        p.aportes.forEach((id,n)->y.set(base+".aportes."+id,n));
        if(!p.relevados.isEmpty())y.set(base+".relevados-f2",p.relevados.stream().map(UUID::toString).sorted().toList());
        if(p.modalidadPremio!=null) {
            y.set(base+".modalidad-recompensa.modo",p.modalidadPremio.name());
            y.set(base+".modalidad-recompensa.dia-asignado",p.diaModalidad);
            y.set(base+".modalidad-recompensa.ticket",p.ticketModalidad);
            y.set(base+".modalidad-recompensa.probabilidad-manual",p.probManualModalidad);
        }
        if(p.mision.tipo()==MissionType.CAZA_JEFE&&p.diaModalidad>0)y.set(base+".dia-caceria",p.diaModalidad);
        if(p.huntPolicy!=null)com.hardcoremisiones.rewards.hunts.HuntRewardYaml.write(y,base+".politica-caceria",p.huntPolicy);
        if (p.perfil!=null && (p.plan!=null||p.mision.tipo()==MissionType.CAZA_JEFE))
            FrozenMissionCodec.write(y,base+".contrato-f2",new FrozenMissionCodec.Frozen(p.mision,p.perfil,p.plan));
    }
    private boolean guardar() {
        if(!estaLista())return false;
        var y=new YamlConfiguration();
        y.set("formato",7);y.set("partida-id",sesion);y.set("etapa-pruebas",etapa);
        if(diaAsignado>0)y.set("dia-asignado",diaAsignado);
        y.set("anterior-grupal",anteriorGrupal);y.set("recientes-grupo",recientesGrupo);
        y.set("auditoria-relevos-f2",new ArrayList<>(auditoriaRelevos));
        escribir(y,"grupal",grupal);
        personales.forEach((id,p)->escribir(y,"individuales."+id,p));
        anterioresPersonales.forEach((id,v)->y.set("anteriores."+id,v));
        recientesPersonales.forEach((id,v)->y.set("recientes-individuales."+id,v));
        try {
            AtomicStorage.write(archivo.toPath(),y.saveToString());
            return true;
        }catch(IOException | RuntimeException e){
            logger.log(java.util.logging.Level.SEVERE,"Fallo guardando misiones: "+sesion,e);
            criticalFailure.accept("misiones");
            return false;
        }
    }
}
