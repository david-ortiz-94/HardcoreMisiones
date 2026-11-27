package com.hardcoremisiones;

import com.hardcoremisiones.rewards.missions.MissionRewardFactory;
import com.hardcoremisiones.rewards.missions.MissionRewardCodec;

import com.hardcoremisiones.missions.model.MissionProfile;
import com.hardcoremisiones.missions.model.MissionDefinition;
import com.hardcoremisiones.missions.model.MissionType;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** Prueba FUENTE preparada para el gate final; no ejecutar en incrementos intermedios. */
public final class F2ContratosDeBalanceTest {
    public static void main(String[] args) {
        var archivoCatalogo=F2ContratosDeBalanceTest.class.getClassLoader().getResourceAsStream("catalogo.yml");
        var archivoPerfiles=F2ContratosDeBalanceTest.class.getClassLoader().getResourceAsStream("perfiles-f2.yml");
        if(archivoCatalogo==null || archivoPerfiles==null)throw new AssertionError("Faltan recursos F2");
        YamlConfiguration catalogo=YamlConfiguration.loadConfiguration(
            new InputStreamReader(archivoCatalogo,StandardCharsets.UTF_8));
        YamlConfiguration perfiles=YamlConfiguration.loadConfiguration(
            new InputStreamReader(archivoPerfiles,StandardCharsets.UTF_8));
        ConfigurationSection misiones=catalogo.getConfigurationSection("misiones");
        ConfigurationSection perfil=perfiles.getConfigurationSection("perfiles");
        assert misiones!=null && perfil!=null;
        assert misiones.getKeys(false).size()==100;
        assert misiones.getKeys(false).equals(perfil.getKeys(false));
        int individuales=0,grupales=0,reformas=0;
        List<MissionDefinition> disponibles=new ArrayList<>();
        Map<String,MissionProfile> metadatos=new HashMap<>();
        for(String id:misiones.getKeys(false)) {
            var d=misiones.getConfigurationSection(id);
            var p=perfil.getConfigurationSection(id);
            assert d!=null && p!=null;
            int etapa=d.getInt("etapa");
            assert etapa>=1 && etapa<=4;
            boolean equipo="grupal".equals(d.getString("categoria"));
            if(equipo)grupales++;else individuales++;
            if(p.contains("reforma"))reformas++;
            var m=new MissionDefinition(id,d.getString("nombre",""),
                MissionType.valueOf(d.getString("tipo","")),d.getString("recurso",""),
                d.getInt("cantidad",0),etapa,
                d.getInt("jugadores.uno",0),d.getInt("jugadores.dos",0),d.getInt("jugadores.tres",0),
                d.getInt("peso",0),d.getString("requisito",""),d.getString("unidad",""),
                d.getBoolean("todos_aportan",false),id.startsWith("esp_"));
            for(int n=1;n<=3;n++)assert m.objetivo(n)>0;
            var f=new MissionProfile(p.getInt("dificultad"),p.getInt("riesgo"),
                p.getInt("minutos-referencia"),p.getString("familia"),p.getString("clasificacion"));
            metadatos.put(id,f);disponibles.add(m);
            var plan=MissionRewardFactory.crear(m,f,m.objetivo(1),equipo,new YamlConfiguration());
            assert plan.opciones().size()==3;
            YamlConfiguration snapshot=new YamlConfiguration();
            MissionRewardCodec.escribir(snapshot,"contrato",plan);
            assert plan.equals(MissionRewardCodec.leer(snapshot,"contrato"));
        }
        assert individuales==49 && grupales==51 && reformas==5;
        var aleatorio=new Random(20261123);
        List<MissionDefinition> early=disponibles.stream().filter(m->m.minimaEtapa()==1).toList();
        MissionDefinition elegido=MissionProfile.elegir(early,metadatos,List.of(),"","",1,1,aleatorio);
        assert elegido!=null && elegido.minimaEtapa()==1;
        System.out.println("F2_CONTRATOS_LISTOS: 100 IDs, 5 reformas, 3 premios, snapshots");
    }
}
