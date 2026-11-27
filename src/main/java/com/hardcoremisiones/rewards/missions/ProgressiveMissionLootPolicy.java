package com.hardcoremisiones.rewards.missions;

import com.hardcoremisiones.rewards.model.MissionRewardContract;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;

import com.hardcoremisiones.rewards.model.RewardItem;
import com.hardcoremisiones.rewards.model.RewardMaterialPolicy;

import com.hardcoremisiones.rewards.benefits.TemporaryBenefitCatalog;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeSet;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.ConfigurationSection;

/** W45: tres LOTES independientes; sorteos reproducibles, con pesos, cupos y resultados
 * escritos en el contrato durable de la misión. No reabre sorteos por /reload.
 * Las probabilidades dependen de día lógico, dificultad, riesgo y tamaño de equipo.
 */
public final class ProgressiveMissionLootPolicy {
    private ProgressiveMissionLootPolicy() { }
    public enum Rareza { BASICO, MEJORADO, RARO, EXCEPCIONAL }
    private static final String CONFIG="recompensas.botin-progresivo.probabilidades";
    private static final List<String> TRAMOS=List.of("dias-1-3","dias-4-8","dias-9-15","dias-16-24","dia-25-mas");
    private static final Rules DEFAULTS=new Rules(List.of(List.of(100,0,0,0),List.of(35,65,0,0),
        List.of(15,50,35,0),List.of(8,32,45,15),List.of(5,25,45,25)),
        List.of(40,55,70,80,90),List.of(0,10,25,35,45),List.of(0,18,30,40,50));
    /** Validated probability snapshot for generating new offers; never used to decode an old offer. */
    public record Rules(List<List<Integer>> rarity,List<Integer> second,List<Integer> third,List<Integer> blessing) {
        public Rules {
            rarity=rarity.stream().map(List::copyOf).toList();
            second=List.copyOf(second);third=List.copyOf(third);blessing=List.copyOf(blessing);
            if(rarity.size()!=5)throw new IllegalArgumentException("W45: cinco tramos requeridos");
            for(int i=0;i<5;i++){
                var row=rarity.get(i);int allowed=Math.min(4,i+1);
                if(row.size()!=4||row.stream().anyMatch(v->v<0||v>100)||row.stream().mapToInt(Integer::intValue).sum()!=100
                    ||row.subList(allowed,4).stream().anyMatch(v->v!=0))throw new IllegalArgumentException("W45: rareza inválida o bloqueada en "+TRAMOS.get(i));
            }
            validate(second,90);validate(third,65);validate(blessing,100);
            if(third.getFirst()!=0||blessing.getFirst()!=0)throw new IllegalArgumentException("W45: tercer material/bendición bloqueados días 1–3");
        }
        private static void validate(List<Integer> values,int max){
            if(values.size()!=5||values.stream().anyMatch(v->v<0||v>max))throw new IllegalArgumentException("W45: cinco porcentajes 0.."+max+" requeridos");
        }
    }
    /** Reads the same policy for validation, generation and probability display. */
    public static Rules read(ConfigurationSection cfg){
        if(!cfg.contains(CONFIG))return DEFAULTS;
        var node=cfg.getConfigurationSection(CONFIG);
        if(node==null||!node.getKeys(false).equals(Set.of("rareza","segundo","tercero","bendicion")))throw new IllegalArgumentException("W45: sección de probabilidades inválida");
        var rows=node.getConfigurationSection("rareza");
        if(rows==null||!rows.getKeys(false).equals(Set.copyOf(TRAMOS)))throw new IllegalArgumentException("W45: tramos de rareza inválidos");
        return new Rules(TRAMOS.stream().map(k->integers(rows,k)).toList(),integers(node,"segundo"),integers(node,"tercero"),integers(node,"bendicion"));
    }
    private static List<Integer> integers(ConfigurationSection cfg,String path){
        var list=cfg.getList(path);if(list==null)throw new IllegalArgumentException("W45: lista ausente "+path);
        return list.stream().map(v->{if(!(v instanceof Integer n))throw new IllegalArgumentException("W45: entero requerido "+path);return n;}).toList();
    }
    private static int tramo(int dia){return dia<=3?0:dia<=8?1:dia<=15?2:dia<=24?3:4;}
    public static int[] probabilidades(int dia) { return probabilidades(dia,1,1,false); }
    public static int[] probabilidades(int dia,int dificultad,int riesgo,boolean equipo) {return probabilidades(dia,dificultad,riesgo,equipo,DEFAULTS);}
    public static int[] probabilidades(int dia,int dificultad,int riesgo,boolean equipo,Rules rules) {
        int stage=tramo(dia),highest=Math.min(3,stage);
        int[] p=rules.rarity().get(stage).stream().mapToInt(Integer::intValue).toArray();
        int impulso=Math.min(20,Math.max(0,dificultad-1)*3+Math.max(0,riesgo-1)*2+(equipo?3:0));
        // Move points directly to the highest unlocked rarity, not just to Mejorado.
        for(int i=0;i<highest&&impulso>0;i++){
            int moved=Math.min(p[i],impulso);p[i]-=moved;p[highest]+=moved;impulso-=moved;
        }
        return p;
    }
    public static int proximoDesbloqueo(int dia) {return dia<4?4:dia<9?9:dia<16?16:-1;}
    public static String probabilidadesTexto(int dia) {return probabilidadesTexto(dia,1,1,false);}
    public static String probabilidadesTexto(int dia,int dificultad,int riesgo,boolean equipo) {return probabilidadesTexto(dia,dificultad,riesgo,equipo,DEFAULTS);}
    public static String probabilidadesTexto(int dia,int dificultad,int riesgo,boolean equipo,Rules rules) {
        int[] p=probabilidades(dia,dificultad,riesgo,equipo,rules);
        return "Día "+Math.max(1,dia)+" · Dif. "+dificultad+"/5 Riesgo "+riesgo+"/4"
            +" · Básico "+p[0]+"% Mejorado "+p[1]+"% Raro "+p[2]+"% Excepcional "+p[3]+"%";
    }
    public static int probabilidadComplemento(int dia,int dificultad,int riesgo,boolean equipo){return probabilidadComplemento(dia,dificultad,riesgo,equipo,DEFAULTS);}
    public static int probabilidadComplemento(int dia,int dificultad,int riesgo,boolean equipo,Rules rules) {
        return Math.min(90,rules.second().get(tramo(dia))+Math.max(0,dificultad-1)*5+Math.max(0,riesgo-1)*3+(equipo?5:0));
    }
    public static int probabilidadTercerItem(int dia,int dificultad,int riesgo,boolean equipo){return probabilidadTercerItem(dia,dificultad,riesgo,equipo,DEFAULTS);}
    public static int probabilidadTercerItem(int dia,int dificultad,int riesgo,boolean equipo,Rules rules) {
        if(dia<4||rules.third().get(tramo(dia))==0)return 0;
        return Math.min(65,rules.third().get(tramo(dia))+Math.max(0,dificultad-1)*3+Math.max(0,riesgo-1)*2+(equipo?4:0));
    }
    private static Random estable(String seed) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(seed.getBytes(StandardCharsets.UTF_8));
            return new Random(ByteBuffer.wrap(digest).getLong());
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }
    public static String huella(String auditoria) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(auditoria.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest).substring(0,16);
        } catch(NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public static int indiceAutomatico(String sesion,String clave,String jugador,int cantidad) {
        if(cantidad<2||cantidad>3)throw new IllegalArgumentException("Cartas inválidas");
        return estable("W45|AUTO|"+sesion+"|"+clave+"|"+jugador).nextInt(cantidad);
    }
    public static int peso(Material material) { return RewardMaterialPolicy.progressiveWeight(material); }
    public static int coste(MissionRewardContract.Opcion option) { return MissionRewardContract.progressiveCost(option); }
    private static final Material[] COMIDA_1={Material.COOKED_BEEF,Material.BREAD};
    private static final Material[] COMIDA_2={Material.GOLDEN_CARROT,Material.COOKED_BEEF,Material.BREAD};
    private static final Material[] COMIDA_3={Material.GOLDEN_CARROT,Material.GOLDEN_APPLE};
    private static final Material[] PREP_1={Material.TORCH,Material.ARROW,Material.OAK_LOG,Material.COAL,Material.STRING,Material.LEATHER};
    private static final Material[] PREP_2={Material.IRON_INGOT,Material.LAPIS_LAZULI,Material.ARROW,Material.SHIELD,Material.BUCKET};
    private static final Material[] PREP_3={Material.SHIELD,Material.IRON_INGOT,Material.SPECTRAL_ARROW,Material.GOLD_INGOT};
    private static final Material[] PREP_4={Material.GOLD_INGOT,Material.IRON_INGOT,Material.SHIELD,Material.EXPERIENCE_BOTTLE};
    private static final Material[] HALL_1={Material.COAL,Material.LEATHER,Material.STRING,Material.IRON_INGOT,Material.ARROW};
    private static final Material[] HALL_2={Material.IRON_INGOT,Material.GOLDEN_CARROT,Material.LAPIS_LAZULI,Material.GOLD_INGOT,Material.EXPERIENCE_BOTTLE};
    private static final Material[] HALL_3={Material.EXPERIENCE_BOTTLE,Material.GOLD_INGOT,Material.SHIELD,Material.IRON_INGOT,Material.SPECTRAL_ARROW};
    private static final Material[] HALL_4={Material.GOLDEN_APPLE,Material.SHIELD,Material.GOLD_INGOT,Material.EXPERIENCE_BOTTLE};
    // HF4. Política editorial: en etapas avanzadas una grupal difícil
    // no debe resolver Supervivencia con pan, ni Hallazgo con papel.
    public static boolean grupoAltoRiesgo(int dia,int dificultad,int riesgo,boolean equipo){
        return equipo && dia>=9 && dificultad>=4 && riesgo>=3;
    }
    private static final Material[] ALTO_SUPERVIVENCIA={Material.GOLDEN_CARROT,Material.COOKED_BEEF};
    private static final Material[] ALTO_PREPARACION={Material.IRON_INGOT,Material.SHIELD,
        Material.LAPIS_LAZULI,Material.GOLD_INGOT,Material.SPECTRAL_ARROW};
    private static final Material[] ALTO_PROGRESION={Material.IRON_INGOT,Material.EXPERIENCE_BOTTLE,
        Material.LAPIS_LAZULI,Material.GOLD_INGOT,Material.GOLDEN_CARROT};
    public static Material[] poolAltoRiesgo(int carta,Rareza rareza){
        if(carta==0) return rareza.ordinal()>=2?COMIDA_3:ALTO_SUPERVIVENCIA;
        if(carta==1) return rareza.ordinal()>=2?pool(1,rareza):ALTO_PREPARACION;
        return rareza.ordinal()>=2?pool(2,rareza):ALTO_PROGRESION;
    }
    private static Material[] pool(int carta,Rareza rareza) {
        if(carta==0)return rareza.ordinal()>=2?COMIDA_3:rareza==Rareza.MEJORADO?COMIDA_2:COMIDA_1;
        if(carta==1)return switch(rareza) {
            case BASICO -> PREP_1; case MEJORADO -> PREP_2;
            case RARO -> PREP_3; case EXCEPCIONAL -> PREP_4;
        };
        return switch(rareza) {
            case BASICO -> HALL_1; case MEJORADO -> HALL_2;
            case RARO -> HALL_3; case EXCEPCIONAL -> HALL_4;
        };
    }
    private static int maximo(Material m) {
        return switch(m) {
            case GOLDEN_APPLE,SHIELD,BUCKET -> 1;
            case GOLD_INGOT,EXPERIENCE_BOTTLE -> 2;
            case IRON_INGOT,GOLDEN_CARROT,AMETHYST_SHARD,LAPIS_LAZULI,SPECTRAL_ARROW -> 5;
            case COAL,COPPER_INGOT,STRING,LEATHER,REDSTONE -> 10;
            default -> 16;
        };
    }
    private static Material[] complementos(int dia,int dificultad) {
        if(dia>=16) return new Material[]{Material.ARROW,Material.COOKED_BEEF,Material.GOLDEN_CARROT,
            Material.IRON_INGOT,Material.LAPIS_LAZULI,Material.EXPERIENCE_BOTTLE,Material.GOLD_INGOT,Material.COAL};
        if(dia>=9)return new Material[]{Material.ARROW,Material.COAL,Material.COOKED_BEEF,
            Material.TORCH,Material.GOLDEN_CARROT,Material.IRON_INGOT,Material.LAPIS_LAZULI};
        if(dia>=4)return new Material[]{Material.ARROW,Material.COAL,Material.COOKED_BEEF,
            Material.TORCH,Material.IRON_INGOT,Material.STRING};
        return new Material[]{Material.ARROW,Material.COAL,Material.BREAD,Material.TORCH,
            Material.OAK_LOG,Material.FLINT};
    }
    private record Sorteo(Rareza rareza,int ticket,int[] pesos) { }
    private static Sorteo rareza(Random r,int dia,int dificultad,int riesgo,boolean equipo,Rules rules) {
        int[] pesos=probabilidades(dia,dificultad,riesgo,equipo,rules);
        int ticket=r.nextInt(100),n=ticket;
        for(int i=0;i<4;i++) {n-=pesos[i];if(n<0)return new Sorteo(Rareza.values()[i],ticket,pesos);}
        throw new IllegalStateException("Pesos inconsistentes");
    }
    private static MissionRewardContract.Opcion carta(Random r,int numero,Sorteo sorteo,int dia,
            int dificultad,int riesgo,boolean equipo,int presupuesto,int xp,Set<Material> principalesPrevios,Rules rules) {
        String nombre=numero==0?"Supervivencia":numero==1?"Preparación":"Hallazgo";
        boolean altoRiesgo=grupoAltoRiesgo(dia,dificultad,riesgo,equipo);
        // Upgrade de calidad, NO de presupuesto. Se congela una sola vez en W45.
        Rareza inicial=altoRiesgo && sorteo.rareza()==Rareza.BASICO
            ?Rareza.MEJORADO : sorteo.rareza();
        int costoXp=(xp+3)/4,disponible=presupuesto-costoXp;
        Material elegido=null;Rareza efectiva=null;int elegibles=0,ticketItem=-1;
        for(int nivel=inicial.ordinal();nivel>=0 && elegido==null;nivel--) {
            Material[] candidatos=altoRiesgo
                ?poolAltoRiesgo(numero,Rareza.values()[nivel])
                :pool(numero,Rareza.values()[nivel]);
            List<Material> viables=new ArrayList<>();
            for(Material m:candidatos) if(peso(m)<=disponible && !principalesPrevios.contains(m))viables.add(m);
            if(!viables.isEmpty()) {
                elegibles=viables.size();ticketItem=r.nextInt(elegibles);
                elegido=viables.get(ticketItem);efectiva=Rareza.values()[nivel];
            }
        }
        if(elegido==null)throw new IllegalArgumentException("Presupuesto sin objeto W45");
        // Reservar una parte razonable del presupuesto para el complemento,
        // sin castigar a quien solo recibe el artículo principal.
        int cuotaPrincipal=Math.max(peso(elegido), disponible*60/100);
        int cantidad=Math.max(1,Math.min(maximo(elegido),cuotaPrincipal/peso(elegido)));
        int resto=disponible-cantidad*peso(elegido);
        int probSegundo=probabilidadComplemento(dia,dificultad,riesgo,equipo,rules);
        // Una misión exigente ofrece al menos una carta orientada a un lote.
        // Esta garantía se audita como probabilidad efectiva, no como sorteo ficticio.
        boolean cartaLoteGarantizado=numero==1 && (dificultad>=3 || riesgo>=3);
        if(cartaLoteGarantizado)probSegundo=100;
        int probTercero=probabilidadTercerItem(dia,dificultad,riesgo,equipo,rules);
        int rollSegundo=r.nextInt(100),rollTercero=-1;
        List<RewardItem> extras=new ArrayList<>();
        String extraLog="";
        if(rollSegundo<probSegundo) {
            Material[] candidatos=complementos(dia,dificultad);
            List<Material> viables=new ArrayList<>();
            for(Material m:candidatos) if(m!=elegido && peso(m)<=resto)viables.add(m);
            if(!viables.isEmpty()) {
                int ticket=r.nextInt(viables.size());Material m=viables.get(ticket);
                if(probTercero>0)rollTercero=r.nextInt(100);
                Material elegidoFinal=elegido;
                int reserva=0;
                if(rollTercero>=0&&rollTercero<probTercero){
                    int min=Arrays.stream(candidatos).filter(x->x!=elegidoFinal && x!=m).mapToInt(ProgressiveMissionLootPolicy::peso).min().orElse(0);
                    if(min>0&&resto>=peso(m)+min)reserva=min;
                }
                int n=Math.min(Math.min(maximo(m),4),(resto-reserva)/peso(m));
                extras.add(new RewardItem(m,n));resto-=n*peso(m);
                extraLog=" extra1="+m+"x"+n+" ticketItem="+ticket+"/"+viables.size();
                if(probTercero>0) {
                    if(rollTercero<probTercero) {
                        viables.clear();
                        for(Material m2:candidatos) if(m2!=elegido && m2!=m && peso(m2)<=resto)viables.add(m2);
                        if(!viables.isEmpty()) {
                            int t=r.nextInt(viables.size());Material m2=viables.get(t);
                            int n2=Math.min(Math.min(maximo(m2),3),resto/peso(m2));
                            extras.add(new RewardItem(m2,n2));resto-=n2*peso(m2);
                            extraLog+=" extra2="+m2+"x"+n2+" ticketItem="+t+"/"+viables.size();
                        } else extraLog+=" extra2=sin-presupuesto";
                    }
                }
            } else extraLog=" extra1=sin-presupuesto";
        }
        // Prestigio NO es experiencia: es un punto cosmético para rangos/emblemas.
        // En la tercera carta, algunas misiones de mayor esfuerzo lo incluyen sin
        // borrar los materiales del paquete ni saltarse el presupuesto.
        int probPrestigio=numero==2 && dia>=4 && (dificultad>=3 || riesgo>=3)
            ?Math.min(55,10+Math.min(24,dia)+Math.max(0,dificultad-2)*3+(equipo?4:0)):0;
        int ticketPrestigio=r.nextInt(100);
        int prestigio=ticketPrestigio<probPrestigio && resto>=8
            ?Math.min(dia>=16 && dificultad>=4?2:1,resto/8):0;
        // No penalizar con menos XP una carta menos generosa en materiales.
        // Todos los valores siguen dentro del presupuesto y tope diarios F2.
        int xpFinal=Math.min(32,xp+Math.min(8,Math.max(0,resto-prestigio*8)/6));
        // Log expresamente de probabilidades PREVIAS, no confundir intentos con concesiones.
        String auditoria="dia="+dia+" dificultad="+dificultad+"/5 riesgo="+riesgo+"/4 equipo="+equipo
            +" pesos="+Arrays.toString(sorteo.pesos())+" ticketRareza="+sorteo.ticket()+"/100"
            +" rarezaSorteada="+sorteo.rareza()+" rarezaAplicada="+efectiva
            +" material="+elegido+" ticketItem="+ticketItem+"/"+elegibles
            +" probMaterialCondicional=1/"+elegibles
            +" probSegundo="+probSegundo+"% ticketSegundo="+rollSegundo+"/100"
            +" segundoGarantizado="+cartaLoteGarantizado
            +" probTercero="+probTercero+"% ticketTercero="+rollTercero+"/100"
            +" probPrestigio="+probPrestigio+"% ticketPrestigio="+ticketPrestigio+"/100"
            +" prestigioReal="+prestigio+" resultadoItems="+(1+extras.size())+" xpCompensada="+xpFinal
            +" principalesPrevios="+new TreeSet<>(principalesPrevios.stream().map(Material::name).toList())
            +" diversidad=sin-repetir-principal"
            +" politicaHF4AltoRiesgo="+altoRiesgo+" politicaW45=2"+extraLog;
        return new MissionRewardContract.Opcion(nombre+" · "+efectiva.name(),elegido,cantidad,xpFinal,prestigio,"",extras,auditoria);
    }
    public static MissionRewardContract.Plan crear(MissionDefinition m,MissionProfile p,int objetivo,boolean equipo,
            FileConfiguration cfg,String sesion,int dia,String identidad) {
        if(sesion==null||sesion.isBlank()||identidad==null||identidad.isBlank()||dia<1)
            throw new IllegalArgumentException("Identidad de sorteo ausente");
        Rules rules=read(cfg);
        Random r=estable("W45|PLAN|"+sesion+"|"+dia+"|"+identidad+"|"+m.id()+"|"+equipo);
        int bonus=Math.clamp(cfg.getInt("recompensas.bonos-por-tipo."+m.tipo().name().toLowerCase(),0),-6,10);
        int presupuesto=Math.clamp(22+p.dificultad()*8+p.riesgo()*6+(equipo?8:0)
            +(m.especial()?4:0)+Math.min(12,Math.max(0,objetivo-1)*2)
            +Math.min(14,Math.max(0,dia-1)/2)+bonus,24,95);
        int xpBase=Math.clamp(7+presupuesto/4,9,32);
        Set<Material> principales=new HashSet<>();
        var primera=carta(r,0,rareza(r,dia,p.dificultad(),p.riesgo(),equipo,rules),dia,
            p.dificultad(),p.riesgo(),equipo,presupuesto,xpBase,Set.copyOf(principales),rules);
        principales.add(primera.material());
        var segunda=carta(r,1,rareza(r,dia,p.dificultad(),p.riesgo(),equipo,rules),dia,
            p.dificultad(),p.riesgo(),equipo,presupuesto,Math.max(6,xpBase-2),Set.copyOf(principales),rules);
        principales.add(segunda.material());
        var tercera=carta(r,2,rareza(r,dia,p.dificultad(),p.riesgo(),equipo,rules),dia,
            p.dificultad(),p.riesgo(),equipo,presupuesto,Math.max(5,xpBase-5),Set.copyOf(principales),rules);
        // W41 opcional: no excede el presupuesto ni fabrica beneficios fuera de cupo.
        int pBendicion=rules.blessing().get(tramo(dia));
        int ticketBendicion=r.nextInt(100);
        if(cfg.getBoolean("recompensas.beneficios-temporales.habilitados",false)&&dia>=4&&p.dificultad()>=2
            &&ticketBendicion<pBendicion) {
            String[] bonos=dia>=9
                ?new String[]{"E_SPEED","E_NIGHT_VISION","E_RESISTANCE","E_REGENERATION","P_SPEED","P_NIGHT_VISION","P_REGENERATION"}
                :new String[]{"E_SPEED","E_NIGHT_VISION","P_SPEED"};
            int ti=r.nextInt(bonos.length);
            TemporaryBenefitCatalog.Bono b=TemporaryBenefitCatalog.obtener(bonos[ti]);
            int xp=Math.max(0,Math.min(12,4*(presupuesto-b.coste())));
            if(b.coste()<=presupuesto)
                tercera=new MissionRewardContract.Opcion("Bendición · "+(dia>=9?"Raro":"Mejorado"),
                    b.pocion()?Material.POTION:Material.PAPER,b.pocion()?1:0,xp,0,bonos[ti],List.of(),
                    "dia="+dia+" dificultad="+p.dificultad()+" riesgo="+p.riesgo()
                        +" probBendicion="+pBendicion+"% ticket="+ticketBendicion
                        +" bono="+bonos[ti]+" probBono=1/"+bonos.length+" politicaW45=2");
        }
        var primarios=new HashSet<Material>();
        for(var opcion:List.of(primera,segunda,tercera))
            if(!primarios.add(opcion.material()))
                throw new IllegalArgumentException("W45/RC3: material principal duplicado " + m.id());
        for(var carta:List.of(primera,segunda,tercera))
            if(coste(carta)>presupuesto)
                throw new IllegalArgumentException("W45 exceso presupuesto mision="+m.id()+" dia="+dia
                    +" presupuesto="+presupuesto+" calculado="+coste(carta)+" tarjeta="+carta);
        return new MissionRewardContract.Plan("w45-v1",presupuesto,List.of(primera,segunda,tercera));
    }
}
