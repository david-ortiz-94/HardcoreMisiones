package com.hardcoremisiones;

import com.hardcoremisiones.config.PaperConfigYaml;

import com.hardcoremisiones.rewards.model.RewardItem;
import com.hardcoremisiones.rewards.bosses.BossRewardContract;
import com.hardcoremisiones.rewards.bosses.BossLootJournal;
import com.hardcoremisiones.rewards.hunts.BossLootPolicy;

import com.hardcoremisiones.rewards.bosses.BossRewardContract.Reglas;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Paquete;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Oferta;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Derecho;
import com.hardcoremisiones.rewards.bosses.BossRewardContract.Origen;

import com.hardcoremisiones.rewards.encounters.EncounterEconomyYaml;
import com.hardcoremisiones.bosses.policy.BossCatalogPolicy;
import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;

import com.hardcoremisiones.rewards.encounters.EncounterEconomy;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** Derechos adicionales RC8-D, cobrados exclusivamente por el ledger W42.
 * No se invocan mobs y ningún callback de muerte entrega items directamente.
 * Una reserva W42 puede repetirse de forma segura con la MISMA clave si el
 * acuse RC8-D falló después de una reserva exitosa: W42 es idempotente.
 */
final class BossRewardService {
    private static final String LOG="[HM2_BOTIN_RC8D]";
    private final org.bukkit.plugin.java.JavaPlugin plugin;
    private final java.util.function.BooleanSupplier operationsAllowed;
    private final java.util.function.Consumer<String> criticalFailure;
    private final java.util.function.BooleanSupplier offeringReview;
    private final Recompensas ledger;
    private final Map<String,Derecho> derechos=new LinkedHashMap<>();
    private UUID partida;
    private File diario;
    private boolean error;
    private Reglas actual=new Reglas(BossLootComponentsPolicy.Config.inicial(),false,60,Map.of());
    BossRewardService(org.bukkit.plugin.java.JavaPlugin plugin,
            java.util.function.BooleanSupplier operationsAllowed,
            java.util.function.Consumer<String> criticalFailure,
            java.util.function.BooleanSupplier offeringReview,
            Recompensas ledger) {
        this.plugin=java.util.Objects.requireNonNull(plugin);
        this.operationsAllowed=java.util.Objects.requireNonNull(operationsAllowed);
        this.criticalFailure=java.util.Objects.requireNonNull(criticalFailure);
        this.offeringReview=java.util.Objects.requireNonNull(offeringReview);
        this.ledger=java.util.Objects.requireNonNull(ledger);

        if(!new File(plugin.getDataFolder(),"lotes-jefes.yml").isFile())
            plugin.saveResource("lotes-jefes.yml",false);
        try{recargarReglas();}catch(IOException ex){error=true;
            plugin.getLogger().severe(LOG+" REGLA_INVALIDA: "+ex.getMessage());}
    }
    boolean bloqueado(){return error;}
    Reglas reglas(){return actual.congelar();}
    boolean habilitadosParaNuevosF3(){return !error&&actual.politica().habilitada();}
    Reglas reglasParaPenitencia(String bossId){
        return !error&&actual.politica().habilitada()&&actual.penitencias()
            &&actual.nivelPenitencia().containsKey(bossId) ? actual.congelar():null;
    }
    String recargarReglas()throws IOException {
        var file=new File(plugin.getDataFolder(),"lotes-jefes.yml");
        if(Files.isSymbolicLink(file.toPath())||!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS)
            ||Files.size(file.toPath())>131072)throw new IOException("lotes-jefes.yml ausente/ inválido");
        var y=PaperConfigYaml.read(file);
        Reglas nueva=leerConfiguracionAdministrativa(y);
        if(!y.contains("lotes.economia-f3"))
            plugin.getLogger().info(LOG+" MIGRACION_ECONOMIA_F3 defaults para contratos nuevos; porcentajes explícitos conservados");
        if(y.getInt("lotes.economia-f3.version")==2)
            plugin.getLogger().info(LOG+" MIGRACION_ECONOMIA_F3 v2->v3 solo nuevas admisiones; tiers conservados");
        actual=nueva; // swap atómico en el hilo principal, no alterar derechos existentes
        plugin.getLogger().info(LOG+" REGLAS_CARGADAS activo="+nueva.politica().habilitada()
            +" penitencias="+nueva.penitencias());
        return "Lotes RC8D "+(nueva.politica().habilitada()?"ON":"OFF")
            +" · penitencia "+(nueva.penitencias()?"ON":"OFF")
            +" · encuentros ya iniciados mantienen su política";
    }
    /** Perfil editorial de botín: NO se infiere de la dificultad publicada por el proveedor. */
    Integer nivelPenitenciaAprobado(String id){return actual.nivelPenitencia().get(id);}

    /**
     * Resolves the editable policy for future encounters only. HF5 omitted the economy
     * section; use the approved 2.1 defaults for that omission without editing the file
     * or replacing explicitly configured probabilities. Never call this for a saved intent.
     * @param y validated administrative definition
     * @return immutable rules for new contracts
     * @throws IOException invalid explicit policy
     */
    static Reglas leerConfiguracionAdministrativa(YamlConfiguration y)throws IOException{
        Reglas raw=leer(y);
        var economy=raw.economia();
        // Only the administrative admission boundary migrates v2. Saved intents
        // use leer() and continue to reproduce the original algorithm.
        if(economy!=null && economy.version()==1)return raw;
        if(economy==null)economy=EncounterEconomy.defaults();
        else if(economy.version()==2)economy=new EncounterEconomy.Policy(economy.tiers(),3);
        return new Reglas(raw.politica(),raw.penitencias(),raw.minutosEleccion(),raw.nivelPenitencia(),economy);
    }

    /** Historical decoder: absence remains absence when replaying a previously frozen policy. */
    static Reglas leer(YamlConfiguration y)throws IOException{
        if(y.getInt("formato",-1)!=1||!y.isBoolean("lotes.habilitados")
            ||!y.isBoolean("lotes.penitencias-habilitadas")||!y.isInt("lotes.eleccion-minutos"))
            throw new IOException("formato o opciones obligatorias inválidas");
        Map<String,Integer> map=new LinkedHashMap<>();
        var section=y.getConfigurationSection("lotes.niveles-penitencia");
        if(section!=null)for(String key:section.getKeys(false)){
            if(!section.isInt(key)||map.putIfAbsent(key,section.getInt(key))!=null)
                throw new IOException("nivel inválido: "+key);
        }
        if(map.size()>256)throw new IOException("demasiados perfiles");
        try{
            return new Reglas(new BossLootComponentsPolicy.Config(
                y.getBoolean("lotes.habilitados"),porcentajes(y,"lotes.segundo-mision"),
                porcentajes(y,"lotes.tercero-mision"),porcentajes(y,"lotes.botin-penitencia"),
                porcentajes(y,"lotes.segundo-penitencia")),
                y.getBoolean("lotes.penitencias-habilitadas"),y.getInt("lotes.eleccion-minutos"),map,EncounterEconomyYaml.read(y,"lotes.economia-f3"));
        }catch(IllegalArgumentException ex){throw new IOException("política RC8D inválida: "+ex.getMessage(),ex);}
    }
    private static int[] porcentajes(YamlConfiguration y,String path)throws IOException{
        if(!y.isList(path)||y.getList(path).size()!=5)throw new IOException(path+" requiere 5 enteros");
        int[] out=new int[5];for(int i=0;i<5;i++){
            Object n=y.getList(path).get(i);
            if(!(n instanceof Integer value)||value<0||value>100)throw new IOException(path+" índice "+i);
            out[i]=value;
        }return out;
    }
    boolean cambiarSesion(String raw){
        UUID id;
        try{id=UUID.fromString(raw);}catch(RuntimeException ex){return false;}
        if(id.equals(partida))return !error;
        if(error)return false;
        var folder=new File(plugin.getDataFolder(),"sesiones");
        if(!folder.isDirectory()&&!folder.mkdirs())return false;
        File destino=new File(folder,id+"-lotes-jefes-rc8d.yml");
        Map<String,Derecho> loaded;
        try { loaded=BossLootJournal.load(destino.toPath(),id); }
        catch(IOException|RuntimeException ex){plugin.getLogger().log(java.util.logging.Level.SEVERE,LOG+" DIARIO_CORRUPTO",ex);return false;}
        partida=id;diario=destino;derechos.clear();derechos.putAll(loaded);return true;
    }
    private boolean guardar(){
        if(partida==null||diario==null||error)return false;
        try{BossLootJournal.save(diario.toPath(),partida,derechos);return true;}
        catch(IOException|RuntimeException ex){plugin.getLogger().log(java.util.logging.Level.SEVERE,LOG+" GUARDADO_FALLIDO",ex);return false;}
    }
    /** Replay F3/PENITENCIA: mismo WON, independientemente del día de reconciliación.
     * No sustituye campos históricos ni permite aceptar otro encounter/receipt/boss.
     */
    static boolean mismaVictoria(UUID anteriorEncuentro,UUID anteriorTerminal,String anteriorBoss,
                                UUID encuentro,UUID terminal,String boss){
        return anteriorEncuentro!=null&&anteriorTerminal!=null&&anteriorBoss!=null
            &&anteriorEncuentro.equals(encuentro)&&anteriorTerminal.equals(terminal)
            &&anteriorBoss.equals(boss);
    }
    /** Plan exacto se registra ANTES de mostrar opciones o reservar en W42. */
    private boolean registrar(Origen origen,String source,UUID encounter,UUID terminal,String boss,
                             int day,int nivel,Set<UUID> members,UUID player,Reglas congelada){
        if(error||partida==null||encounter==null||terminal==null||player==null
            ||members==null||members.isEmpty()||members.size()>3||!members.contains(player)
            ||nivel<1||nivel>5||congelada==null||!congelada.politica().habilitada())return false;
        String key=origen+":"+source+":"+player;
        Derecho old=derechos.get(key);
        if(old!=null){
            // El día lógico recibido es el día ACTUAL al conciliar tras un reinicio,
            // no necesariamente el día histórico de la victoria. No modificar nunca
            // el derecho ni sus ofertas/selección/acuse al reproducir el mismo WON.
            // La identidad inmutable se limita al encuentro, terminal y boss real.
            if(!mismaVictoria(old.encuentro(),old.terminal(),old.bossId(),encounter,terminal,boss)
                ||old.nivel()!=nivel||old.miembros()!=members.size()){
                plugin.getLogger().severe(LOG+" CONFLICTO_DERECHO "+key
                    +" encuentro_igual="+old.encuentro().equals(encounter)
                    +" terminal_igual="+old.terminal().equals(terminal)
                    +" jefe_igual="+old.bossId().equals(boss));
                return false;
            }
            return true;
        }
        if(derechos.size()>=2048)return false;
        var ctx=new BossLootComponentsPolicy.Contexto(
            origen==Origen.F3?BossLootComponentsPolicy.Origen.MISION:
                BossLootComponentsPolicy.Origen.PENITENCIA,
            BossLootComponentsPolicy.Estado.WON,true,true,encounter,terminal,
            BossCatalogPolicy.Dificultad.values()[nivel-1],false);
        // Freeze the roll even if the first attempt cannot persist the right.
        // The legacy F3 seed is untouched; penance has a separate seed domain.
        var drawRandom=BossLootPolicy.aleatorio(encounter,player,source,
            origen==Origen.F3?"DERECHO_COMPONENTES":"PENANCE_COMPONENTS_V1",0,0);
        var result=BossLootComponentsPolicy.decidir(ctx,congelada.politica(),drawRandom);
        if(!result.elegible())return false;
        List<Oferta> alternativas=new ArrayList<>();
        for(int i=0;i<3;i++){
            Set<Paquete> usados=new LinkedHashSet<>();
            if(origen==Origen.F3)for(Derecho previo:derechos.values())
                if(previo.origen()==Origen.F3 && previo.jugador().equals(player)
                    && previo.bossId().equals(boss))
                    usados.add(previo.opciones().get(i).principal());
            if(origen==Origen.F3 && congelada.economia()!=null){
                var style=EncounterEconomy.Style.values()[i];
                var policy=congelada.economia();
                // Stable context: day is not part of the seed; persisted encounter admission
                // policy must not be replaced by the currently loaded YAML.
                var principal=EncounterEconomy.draw(policy,nivel,day,members.size(),encounter,player,
                    source+"|"+boss,style,EncounterEconomy.Component.PRINCIPAL);
                var segundo=EncounterEconomy.draw(policy,nivel,day,members.size(),encounter,player,
                    source+"|"+boss,style,EncounterEconomy.Component.SECOND);
                var tercero=EncounterEconomy.draw(policy,nivel,day,members.size(),encounter,player,
                    source+"|"+boss,style,EncounterEconomy.Component.THIRD);
                alternativas.add(new Oferta(paquete(principal),paquete(segundo),paquete(tercero)));
                plugin.getLogger().info(LOG+" ECONOMIA_F3_V"+policy.version()+" fuente="+source+" jugador="+player
                    +" carta="+(i+1)+" rareza="+principal.rarity()+" ticket="+principal.ticket());
            }else if(origen==Origen.F3){
                // HF5: cada UUID tira SUS tres cartas y SU rareza; nunca se
                // clona el paquete de otro participante ni de otra misión.
                var principal=BossLootPolicy.sortear(nivel,i,encounter,player,
                    source+"|"+boss,i,0,usados);
                // Extras independientes y con rareza propia; las tiradas de
                // aparición (p2/p3) siguen congeladas en PoliticaLotesRC8C.
                var segundo=BossLootPolicy.sortear(nivel,(i+1)%3,encounter,player,
                    source+"|"+boss+"|component:2",i,0,Set.of());
                var tercero=BossLootPolicy.sortear(nivel,(i+2)%3,encounter,player,
                    source+"|"+boss+"|component:3",i,0,Set.of());
                Paquete main=principal.paquete();
                if(nivel==1&&main.material()==Material.AMETHYST_SHARD)
                    main=new Paquete(Material.LAPIS_LAZULI,main.cantidad(),List.of());
                alternativas.add(new Oferta(main,segundo.paquete(),tercero.paquete()));
                plugin.getLogger().info(LOG+" RAREZA_CONGELADA fuente="+source
                    +" jugador="+player+" carta="+(i+1)+" rareza="+principal.rareza()
                    +" ticket="+principal.ticket()+" pesos="+Arrays.toString(principal.pesos()));
            }else alternativas.add(ofertaContextual(i,nivel,members.size(),origen,
                source,boss,encounter,usados));
        }
        long now=System.currentTimeMillis();
        var receipt=new Derecho(key,origen,source,player,encounter,terminal,boss,day,nivel,members.size(),
            result.principal(),result.segundo(),result.tercero(),result.probPrincipal(),
            result.ticketPrincipal(),result.probSegundo(),result.ticketSegundo(),
            result.probTercero(),result.ticketTercero(),alternativas,0,now,
            now+congelada.minutosEleccion()*60_000L,0);
        derechos.put(key,receipt);
        if(!guardar()){derechos.remove(key);error=true;criticalFailure.accept("lotes RC8D");return false;}
        plugin.getLogger().info(LOG+" DERECHO_CONGELADO partida="+partida+" fuente="+source
            +" jugador="+player+" encuentro="+encounter+" terminal="+terminal
            +" premios="+result.cantidadComponentes()+" extraF3="+(origen==Origen.F3)
            +" p2="+result.probSegundo()+" t2="+result.ticketSegundo()
            +" p3="+result.probTercero()+" t3="+result.ticketTercero());
        var online=Bukkit.getPlayer(player);
        if(online!=null&&receipt.necesitaEleccion())online.sendMessage(
            "§6✦ Tenés botines de jefe para elegir. §eAbrí el Sello del Juicio > Recompensas de jefes.");
        return true;
    }
    /** Convert the pure policy output at the Paper boundary; no payment here. */
    static Paquete paquete(EncounterEconomy.Draw draw){
        var first=draw.stacks().getFirst();
        var extras=draw.stacks().stream().skip(1).map(v->new RewardItem(
            Material.valueOf(v.item().name()),v.quantity())).toList();
        return new Paquete(Material.valueOf(first.item().name()),first.quantity(),extras);
    }
    /** All rights for this exact victory already exist; no RNG or provider lookup is needed. */
    boolean tieneDerechosF3(String mission,UUID encounter,UUID terminal,String boss,int tier,Set<UUID> players){
        if(error||players.isEmpty())return false;
        for(UUID player:players){
            Derecho d=derechos.get(Origen.F3+":"+mission+":"+player);
            if(d==null||!mismaVictoria(d.encuentro(),d.terminal(),d.bossId(),encounter,terminal,boss)
                ||d.nivel()!=tier||d.miembros()!=players.size())return false;
        }
        return true;
    }
    boolean registrarF3(String mission,UUID encounter,UUID terminal,String boss,int day,int tier,
                        Set<UUID> members,Reglas congelada){
        if(!mission.matches("f3:[a-z0-9_]+")||tier<1||tier>5)return false;
        for(UUID id:members.stream().sorted().toList())
            if(!registrar(Origen.F3,mission,encounter,terminal,boss,day,tier,members,id,congelada))return false;
        return true;
    }
    boolean registrarPenitencia(int dia,String originId,UUID encounter,UUID terminal,
                               String boss,Set<UUID> miembros,Reglas congelada){
        if(congelada==null||!congelada.penitencias())return true;
        Integer nivel=congelada.nivelPenitencia().get(boss);
        if(nivel==null)return true; // sin clasificación: cero botín y sin inventarlo
        // Un solo derecho adicional por deuda (no uno por jugador): evita explotar
        // la escala de un grupo de 3. Beneficiario estable tras reinicios.
        if(dia<1||originId==null||originId.isBlank()||encounter==null||terminal==null
            ||miembros==null||miembros.isEmpty()||miembros.size()>3||miembros.stream().anyMatch(Objects::isNull))return false;
        // A previous debt owns its original beneficiary. Never select a second
        // beneficiary when replay is attempted with different membership/day.
        for(Derecho existing:derechos.values())if(existing.origen()==Origen.PENITENCIA&&existing.origenId().equals(originId)){
            return !error && mismaVictoria(existing.encuentro(),existing.terminal(),existing.bossId(),encounter,terminal,boss)
                && existing.nivel()==nivel && existing.miembros()==miembros.size() && miembros.contains(existing.jugador());
        }
        List<UUID> players=miembros.stream().sorted().toList();
        UUID destinatario=players.get(Math.floorMod(dia,players.size()));
        return registrar(Origen.PENITENCIA,originId,encounter,terminal,boss,dia,nivel,
            miembros,destinatario,congelada);
    }
    record ResultadoPenitencia(UUID beneficiario,int nivel,boolean conBotin){ }
    ResultadoPenitencia resultadoPenitencia(String originId,Set<UUID> players){
        if(players==null||players.isEmpty())return null;
        for(Derecho d:derechos.values())if(d.origen()==Origen.PENITENCIA
            &&d.origenId().equals(originId)&&players.contains(d.jugador())){
            // Nunca activar la bendición opcional antes de reservar el botín W42.
            // El beneficiario puede tardar en elegir o tener pagos pausados.
            if(d.principal()&&(d.registradoMask()&1)==0)return null;
            return new ResultadoPenitencia(d.jugador(),d.nivel(),d.principal());
        }
        return null;
    }
    /** Reparto LAN 1–3: un grupo mayor jamás pierde cantidad agregada del kit base. */
    static int scale(int base,int members){
        if(members<1||members>3||base<1)throw new IllegalArgumentException("escala");
        // Mantener la cantidad TOTAL nunca menor al aumentar de 1 a 2 a 3.
        // ceil(base / members) solo no basta (base=3 daría 3,4,3).
        int duo=(base+1)/2;
        return members==1?base:members==2?duo:(duo*2+2)/3;
    }
    /** Kit con 1-3 materiales distintos y un tope de unidades por persona. */
    static Oferta oferta(int estilo,int tier,int members,Origen origen){
        if(estilo<0||estilo>2||tier<1||tier>5||members<1||members>3)throw new IllegalArgumentException("oferta");
        int n=scale(4+tier,members);
        if(origen==Origen.PENITENCIA){n=Math.max(1,Math.min(4,n/2));}
        Paquete main=switch(estilo){
            case 0 -> new Paquete(Material.COOKED_BEEF,Math.min(8,n),List.of());
            case 1 -> new Paquete(Material.ARROW,Math.min(12,n*2),List.of());
            default -> new Paquete(Material.IRON_INGOT,Math.min(6,n),List.of());
        };
        // RC8-I: materiales principales DIFERENTES en cada componente de una carta.
        // Derechos antiguos usan sus ofertas congeladas; nunca se recalculan.
        return switch(estilo){
            case 0 -> new Oferta(main,new Paquete(tier>=3?Material.GOLDEN_CARROT:Material.BREAD,
                tier>=3?Math.min(3,n):n,
                origen==Origen.F3&&tier>=3?List.of(new RewardItem(Material.BREAD,Math.min(4,n))):List.of()),
                new Paquete(tier>=3&&origen==Origen.F3?Material.GOLDEN_APPLE:
                    tier>=3?Material.HONEY_BOTTLE:Material.GOLDEN_CARROT,
                    origen==Origen.F3&&tier>=3?1:Math.min(2,n),List.of()));
            case 1 -> new Oferta(main,new Paquete(tier>=2?Material.SPECTRAL_ARROW:Material.SHIELD,
                tier>=2?Math.min(20,n*2):1,
                origen==Origen.F3&&tier>=3?List.of(new RewardItem(Material.SHIELD,1)):List.of()),
                new Paquete(tier>=2?Material.IRON_INGOT:Material.SPECTRAL_ARROW,
                    tier>=2?Math.min(6,n):Math.min(12,n*2),List.of()));
            default -> new Oferta(main,new Paquete(tier>=3?Material.AMETHYST_SHARD:
                tier>=2?Material.LAPIS_LAZULI:Material.COPPER_INGOT,Math.min(8,n),
                origen==Origen.F3&&tier>=2?List.of(new RewardItem(Material.REDSTONE,Math.min(6,n))):List.of()),
                new Paquete(Material.GOLD_INGOT,Math.min(5,n),List.of()));
        };
    }
    /** HF4: diversidad controlada para DERECHOS NUEVOS F3 solamente.
     * Las ofertas del diario antiguo no se tocan. Los kits penitenciarios son
     * los originales, para que el castigo no se convierta en granja de premios.
     * No usa hora ni RNG no persistido: mismo contrato + mismos previos => mismo kit.
     */
    static Oferta ofertaContextual(int estilo,int tier,int members,Origen origen,
            String mission,String boss,UUID encuentro,Set<Paquete> anteriores){
        Oferta original=oferta(estilo,tier,members,origen);
        if(origen!=Origen.F3)return original;
        if(mission==null||!mission.matches("f3:[a-z0-9_]+")||boss==null||encuentro==null
            ||anteriores==null)throw new IllegalArgumentException("Contexto de F3 incompleto");
        int n=scale(4+tier,members);
        // Patrón editorial por misión: dos misiones contra el mismo boss ya no
        // entregan automáticamente 6 hierros en Progresión. El UUID únicamente
        // desempata misiones del mismo tipo; no se vuelve a tirar en un restart.
        int preferido=mission.startsWith("f3:vigilia_")?1
            :mission.startsWith("f3:caceria_")?0
            :Math.floorMod((mission+"|"+boss).hashCode(),4);
        Paquete elegido=null;
        int indice=preferido;
        for(int intento=0;intento<4;intento++){
            int k=(preferido+intento)%4;
            Paquete candidato=opcionPrincipalHF4(estilo,tier,n,k);
            if(!anteriores.contains(candidato)){elegido=candidato;indice=k;break;}
        }
        if(elegido==null){
            indice=Math.floorMod((mission+"|"+boss+"|"+encuentro).hashCode(),4);
            elegido=opcionPrincipalHF4(estilo,tier,n,indice);
        }
        Paquete segundo=original.segundo(),tercero=original.tercero();
        if(estilo==0){
            if(elegido.material()==segundo.material())segundo=new Paquete(Material.BREAD,Math.min(6,n),List.of());
            if(elegido.material()==tercero.material())
                tercero=new Paquete(elegido.material()==Material.GOLDEN_APPLE
                    ?Material.GOLDEN_CARROT:Material.GOLDEN_APPLE,1,List.of());
            if(segundo.material()==tercero.material())segundo=new Paquete(Material.BREAD,Math.min(6,n),List.of());
        }
        if(estilo==1){
            if(elegido.material()==segundo.material())
                segundo=new Paquete(elegido.material()==Material.SHIELD?Material.ARROW:Material.SHIELD,
                    elegido.material()==Material.SHIELD?Math.min(10,n*2):1,List.of());
            if(elegido.material()==tercero.material())
                tercero=new Paquete(Material.GOLD_INGOT,Math.min(2,n),List.of());
            if(segundo.material()==tercero.material())
                segundo=new Paquete(Material.COAL,Math.min(4,n),List.of());
        }
        if(estilo==2){
            if(elegido.material()==segundo.material())
                segundo=new Paquete(elegido.material()==Material.AMETHYST_SHARD
                    ?Material.LAPIS_LAZULI:Material.AMETHYST_SHARD,Math.min(6,n),
                    List.of(new RewardItem(Material.REDSTONE,Math.min(4,n))));
            if(elegido.material()==tercero.material())
                tercero=new Paquete(Material.IRON_INGOT,Math.min(6,n),List.of());
            if(segundo.material()==tercero.material())
                segundo=new Paquete(Material.COPPER_INGOT,Math.min(6,n),List.of());
        }
        if(elegido.material()==segundo.material() || elegido.material()==tercero.material()
            || segundo.material()==tercero.material())
            throw new IllegalStateException("HF4 materiales de carta F3 repetidos");
        return new Oferta(elegido,segundo,tercero);
    }
    private static Paquete opcionPrincipalHF4(int estilo,int tier,int n,int variante){
        if(estilo==0)return switch(variante){
            case 0 -> new Paquete(Material.COOKED_BEEF,Math.min(8,n),List.of());
            case 1 -> new Paquete(Material.GOLDEN_CARROT,Math.min(5,n),List.of());
            case 2 -> new Paquete(Material.COOKED_BEEF,Math.min(8,n),
                List.of(new RewardItem(Material.GOLDEN_CARROT,Math.min(2,n))));
            default -> new Paquete(tier>=3?Material.GOLDEN_APPLE:Material.GOLDEN_CARROT,
                tier>=3?1:Math.min(4,n),List.of());
        };
        if(estilo==1)return switch(variante){
            case 0 -> new Paquete(Material.ARROW,Math.min(12,n*2),List.of());
            case 1 -> new Paquete(Material.SPECTRAL_ARROW,Math.min(6,n),List.of());
            case 2 -> new Paquete(Material.SHIELD,1,List.of());
            default -> new Paquete(Material.ARROW,Math.min(10,n*2),
                List.of(new RewardItem(Material.COAL,Math.min(4,n))));
        };
        return switch(variante){
            case 0 -> new Paquete(Material.IRON_INGOT,Math.min(6,n),List.of());
            case 1 -> new Paquete(Material.GOLD_INGOT,Math.min(3,Math.max(1,n/2)),
                List.of(new RewardItem(Material.COPPER_INGOT,Math.min(3,n))));
            case 2 -> new Paquete(Material.AMETHYST_SHARD,Math.min(6,n),
                List.of(new RewardItem(Material.REDSTONE,Math.min(4,n))));
            default -> new Paquete(Material.LAPIS_LAZULI,Math.min(6,n),
                List.of(new RewardItem(Material.IRON_INGOT,Math.min(2,Math.max(1,n/3)))));
        };
    }
    /** Derecho pendiente más antiguo; la GUI no altera el diario ni inventa premios. */
    Derecho siguientePendiente(UUID jugador){
        if(error||partida==null||jugador==null)return null;
        return derechos.values().stream().filter(d->d.jugador().equals(jugador)
            &&d.necesitaEleccion()&&d.seleccion()==0)
            .min(Comparator.comparingLong(Derecho::creado)).orElse(null);
    }
    List<String> listar(UUID jugador){
        List<String> out=new ArrayList<>();
        for(var d:derechos.values())if(d.jugador().equals(jugador)){
            String extra=d.origen()==Origen.F3?((d.segundo()?1:0)+(d.tercero()?1:0))+" extras F3"
                :((d.principal()?1:0)+(d.segundo()?1:0))+" premios penitencia";
            out.add("§6"+d.origenId()+" §7· "+extra
                +" · opción="+(d.seleccion()==0?"SIN ELEGIR":d.seleccion())
                +" · registrados W42="+Integer.bitCount(d.registradoMask()));
            if(d.necesitaEleccion()&&d.seleccion()==0){
                for(int i=0;i<3;i++){
                    Oferta o=d.opciones().get(i);
                    String descripcion="§7Carta "+(i+1)+" "
                        +(i==0?"Supervivencia":i==1?"Preparación":"Progresión")
                        +" §fPrincipal: "+mostrar(componente(d.origen(),o,1));
                    if(d.origen()==Origen.F3&&d.segundo()||d.origen()==Origen.PENITENCIA&&d.segundo())
                        descripcion+=" · segundo: "+mostrar(componente(d.origen(),o,2));
                    if(d.origen()==Origen.F3&&d.tercero())
                        descripcion+=" · tercero: "+mostrar(componente(d.origen(),o,3));
                    out.add(descripcion);
                }
                out.add("§eElegir: /hm jefes elegir 1|2|3 (sin cambiar premios W42 ya pagados)");
            }
        }
        if(out.isEmpty())out.add("§7Sin lotes RC8D de jefe en tu partida.");
        return out;
    }
    /** Componente físico que corresponde a cada tirada. Una penitencia empieza
     * por su paquete PRINCIPAL, no por el paquete extra de misión.
     */
    static Paquete componente(Origen origen,Oferta oferta,int indice){
        return switch(origen){
            case F3 -> switch(indice){
                case 1 -> oferta.principal();
                case 2 -> oferta.segundo();
                case 3 -> oferta.tercero();
                default -> throw new IllegalArgumentException("componente F3");
            };
            case PENITENCIA -> switch(indice){
                case 1 -> oferta.principal();
                case 2 -> oferta.segundo();
                default -> throw new IllegalArgumentException("componente penitencia");
            };
        };
    }
    private static String mostrar(Paquete p){
        String m=p.cantidad()+"x "+p.material().name();
        for(var e:p.extras())m+=" + "+e.cantidad()+"x "+e.material().name();
        return m;
    }
    String elegir(UUID player,int option){
        if(error||partida==null||option<1||option>3||!operationsAllowed.getAsBoolean())
            return "§cNo disponible o número fuera de 1..3";
        Derecho d=derechos.values().stream().filter(x->x.jugador().equals(player)
            &&x.necesitaEleccion()&&x.seleccion()==0).min(Comparator.comparingLong(Derecho::creado)).orElse(null);
        if(d==null)return "§7No hay lotes sin elegir";
        var selected=d.seleccionar(option);derechos.put(d.clave(),selected);
        if(!guardar()){derechos.put(d.clave(),d);error=true;
            criticalFailure.accept("lotes RC8D selección");return "§cNo se pudo guardar selección";}
        plugin.getLogger().info(LOG+" ELECCION jugador="+player+" fuente="+d.origenId()+" carta="+option);
        return "§aCarta "+option+" guardada. Tu lote se reserva en W42 en el siguiente tick.";
    }
    void tick(){
        if(error||partida==null||derechos.isEmpty()||!operationsAllowed.getAsBoolean()
            ||offeringReview.getAsBoolean()||!ledger.habilitadas())return;
        long now=System.currentTimeMillis();
        for(var old:derechos.values().stream().filter(d->d.necesitaEleccion() &&
            (d.seleccion()==0?now>=d.vencimiento():d.extrasPendientes())).toList()){
            Derecho d=old;
            if(d.seleccion()==0){
                if(now<d.vencimiento())continue;
                d=d.seleccionar(1);derechos.put(d.clave(),d);
                if(!guardar()){derechos.put(d.clave(),old);error=true;
                    criticalFailure.accept("lotes RC8D autoelección");return;}
                plugin.getLogger().info(LOG+" AUTOELECCION fuente="+d.origenId()+" jugador="+d.jugador());
            }
            Oferta offer=d.opciones().get(d.seleccion()-1);
            if(d.origen()==Origen.F3&&(d.registradoMask()&4)==0)continue;
            // W42 tiene su propio diario/cola/conciliación. Registrar no equivale a
            // ENTREGADA: aquí el bit significa únicamente RESERVADA en W42.
            if((d.origen()==Origen.PENITENCIA&&d.principal()) ||
                (d.origen()==Origen.F3&&d.segundo())){
                if((d.registradoMask()&1)==0){
                    if(!registrarComponente(d,d.origen()==Origen.F3?2:1,
                        componente(d.origen(),offer,d.origen()==Origen.F3?2:1)))return;
                    if(!marcar(d,1))return;
                    d=derechos.get(d.clave());
                }
            }
            if(d.origen()==Origen.PENITENCIA?d.segundo():d.tercero()){
                if((d.registradoMask()&2)==0){
                    if(!registrarComponente(d,d.origen()==Origen.F3?3:2,
                        componente(d.origen(),offer,d.origen()==Origen.F3?3:2)))return;
                    if(!marcar(d,2))return;
                }
            }
        }
    }
    /** F3 entrega su premio principal mediante B-f3: legado, sin segunda vía.
     * Este método devuelve los kits elegidos para la reserva única del grupo.
     */
    Map<UUID,Paquete> principalesF3(String mission,UUID encounter,Set<UUID> players){
        Map<UUID,Paquete> out=new LinkedHashMap<>();
        for(UUID id:players){
            var d=derechos.get(Origen.F3+":"+mission+":"+id);
            if(d==null||!d.encuentro().equals(encounter)||d.seleccion()<1)return Map.of();
            out.put(id,d.opciones().get(d.seleccion()-1).principal());
        }
        return out.size()==players.size()?Map.copyOf(out):Map.of();
    }
    boolean acusePrincipalF3(String mission,UUID encounter,Set<UUID> players){
        for(UUID id:players){
            String key=Origen.F3+":"+mission+":"+id;
            var d=derechos.get(key);
            if(d==null||!d.encuentro().equals(encounter))return false;
            if((d.registradoMask()&4)!=0)continue;
            if(!marcar(d,4))return false;
        }
        return true;
    }
    private boolean registrarComponente(Derecho d,int component,Paquete p){
        return ledger.registrarLoteExtraJefeRC8D(d.claveW42(component),d.jugador(),
            p.material(),p.cantidad(),p.extras(),d.dia());
    }
    private boolean marcar(Derecho d,int bit){
        Derecho updated=d.registrado(bit);
        derechos.put(d.clave(),updated);
        if(!guardar()){derechos.put(d.clave(),d);error=true;
            criticalFailure.accept("lotes RC8D acuse W42");return false;}
        return true;
    }
    String estado(){
        long pending=derechos.values().stream().filter(d->d.necesitaEleccion()&&d.seleccion()==0).count();
        long enCola=derechos.values().stream().filter(d->d.tieneExtras()&&d.seleccion()>0
            &&d.extrasPendientes()).count();
        return "§7Lotes RC8D: "+(actual.politica().habilitada()?"ON":"OFF")
            +" · derechos="+derechos.size()+" · elecciones pendientes="+pending
            +" · registros pendientes="+enCola;
    }
}
