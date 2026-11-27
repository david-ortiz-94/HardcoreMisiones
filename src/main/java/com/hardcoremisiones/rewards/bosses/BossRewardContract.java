package com.hardcoremisiones.rewards.bosses;

import com.hardcoremisiones.bosses.policy.BossLootComponentsPolicy;
import com.hardcoremisiones.rewards.encounters.EncounterEconomy;
import com.hardcoremisiones.rewards.model.RewardItem;
import org.bukkit.Material;
import java.util.*;

/** Immutable reward contracts shared by selection, journal and ledger adapters.
 * A right identifies a single player and terminal encounter. State transitions
 * return a replacement record; only the owning repository may publish it.
 * Enum values and payment keys are persisted contracts, not display labels.
 */
public final class BossRewardContract {
    private BossRewardContract() {}
    public enum Origen { F3, PENITENCIA }
    public record Reglas(BossLootComponentsPolicy.Config politica, boolean penitencias,
                  int minutosEleccion, Map<String,Integer> nivelPenitencia, EncounterEconomy.Policy economia) {
        public Reglas(BossLootComponentsPolicy.Config politica, boolean penitencias,
               int minutosEleccion, Map<String,Integer> nivelPenitencia) {
            this(politica,penitencias,minutosEleccion,nivelPenitencia,null);
        }
        public Reglas {
            Objects.requireNonNull(politica);
            nivelPenitencia=Map.copyOf(nivelPenitencia);
            if(minutosEleccion<1||minutosEleccion>1440)throw new IllegalArgumentException("elección 1..1440 minutos");
            for(var e:nivelPenitencia.entrySet())
                if(!com.hardcoremisiones.f3.api.BossProvider.validId(e.getKey())||e.getValue()<1||e.getValue()>5)
                    throw new IllegalArgumentException("nivel de penitencia inválido: "+e.getKey());
        }
        public Reglas congelar(){return new Reglas(new BossLootComponentsPolicy.Config(
            politica.habilitada(),politica.segundo(),politica.tercero(),
            politica.botinPenitencia(),politica.segundoPenitencia()),penitencias,minutosEleccion,nivelPenitencia,economia);}
    }
    public record Paquete(Material material,int cantidad,List<RewardItem> extras) {
        public Paquete {
            Objects.requireNonNull(material);extras=List.copyOf(extras);
            if(cantidad<1||cantidad>64 || extras.size()>2 || !material.isItem()
                ||material==Material.NETHERITE_INGOT||material==Material.TOTEM_OF_UNDYING
                ||material==Material.ELYTRA||material==Material.POTION)
                throw new IllegalArgumentException("paquete RC8D inválido");
            Set<Material> items=new HashSet<>();items.add(material);
            for(var e:extras)if(!items.add(e.material()))throw new IllegalArgumentException("material duplicado");
            if(cantidad+extras.stream().mapToInt(RewardItem::cantidad).sum()>48)
                throw new IllegalArgumentException("lote excede 48 unidades");
        }
    }
    /** Tres alternativas por beneficiario; las alternativas se congelan en el diario. */
    public record Oferta(Paquete principal,Paquete segundo,Paquete tercero){
        public Oferta {Objects.requireNonNull(principal);Objects.requireNonNull(segundo);Objects.requireNonNull(tercero);}
    }
    public record Derecho(String clave,Origen origen,String origenId,UUID jugador,UUID encuentro,
                   UUID terminal,String bossId,int dia,int nivel,int miembros,
                   boolean principal,boolean segundo,boolean tercero,
                   int probPrincipal,int ticketPrincipal,int probSegundo,int ticketSegundo,
                   int probTercero,int ticketTercero,List<Oferta> opciones,
                   int seleccion,long creado,long vencimiento,int registradoMask) {
        public Derecho {
            if(clave==null||clave.isBlank()||origenId==null||origenId.isBlank()
                ||jugador==null||encuentro==null||terminal==null
                ||!com.hardcoremisiones.f3.api.BossProvider.validId(bossId)
                ||dia<1||nivel<1||nivel>5||miembros<1||miembros>3
                ||opciones==null||opciones.size()!=3||seleccion<0||seleccion>3
                ||creado<1||vencimiento<creado||registradoMask<0||registradoMask>7)
                throw new IllegalArgumentException("derecho RC8D inválido");
            opciones=List.copyOf(opciones);
            if(origen==Origen.F3&&!principal)throw new IllegalArgumentException("F3 sin principal garantizado");
            if(origen==Origen.PENITENCIA&&tercero)throw new IllegalArgumentException("3er botín penitencia prohibido");
        }
        public Derecho seleccionar(int n){return new Derecho(clave,origen,origenId,jugador,encuentro,
            terminal,bossId,dia,nivel,miembros,principal,segundo,tercero,probPrincipal,
            ticketPrincipal,probSegundo,ticketSegundo,probTercero,ticketTercero,
            opciones,n,creado,vencimiento,registradoMask);}
        public Derecho registrado(int bit){return new Derecho(clave,origen,origenId,jugador,encuentro,
            terminal,bossId,dia,nivel,miembros,principal,segundo,tercero,probPrincipal,
            ticketPrincipal,probSegundo,ticketSegundo,probTercero,ticketTercero,
            opciones,seleccion,creado,vencimiento,registradoMask|bit);}
        public boolean tieneExtras(){return (origen==Origen.F3 ? segundo||tercero : principal||segundo);}
        public boolean extrasPendientes(){
            int required=origen==Origen.F3?(segundo?1:0)|(tercero?2:0):(principal?1:0)|(segundo?2:0);
            return (registradoMask&required)!=required;
        }
        public boolean necesitaEleccion(){return origen==Origen.F3||tieneExtras();}
        public String claveW42(int componente){
            // No compartir la clave W42 del premio garantizado B-f3: del legado.
            return "B8D-"+origen.name()+"-"+origenId.replace(':','_')+"-"+jugador+"-"+componente;
        }
    }
}
