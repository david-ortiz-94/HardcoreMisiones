package com.hardcoremisiones;

import java.lang.reflect.InvocationTargetException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

/** Test-only entry point, loaded by the lab launcher from the tested plugin class loader. */
public final class PaperContractHarness {
    public static void prepare() throws Exception {
        var plugin=JavaPlugin.getPlugin(HardcoreMisiones.class);
        if(plugin.getDescription().getVersion().startsWith("2.1"))
            Class.forName("com.hardcoremisiones.CleanPaperContractsTest").getDeclaredMethod("preparePhysicalWorld",HardcoreMisiones.class).invoke(null,plugin);
    }
    public static void run() {
        HardcoreMisiones plugin=JavaPlugin.getPlugin(HardcoreMisiones.class);
        if(!plugin.isEnabled())throw new IllegalStateException("Tested plugin not enabled");
        String session=Bukkit.getWorld("hcmp_game").getUID().toString();
        Map<String,Checked> suites=new LinkedHashMap<>();
        suites.put("F2ContratosDeBalanceTest",()->F2ContratosDeBalanceTest.main(new String[0]));
        suites.put("F4RC8DLotesFisicosTest",()->F4RC8DLotesFisicosTest.main(new String[0]));
        suites.put("F4RC8IRewardDiversityTest",()->F4RC8IRewardDiversityTest.main(new String[0]));
        suites.put("F4RC8JHF4PrivacidadBalanceTest",()->F4RC8JHF4PrivacidadBalanceTest.main(new String[0]));
        suites.put("F4RC8JHF4W45AltoRiesgoTest",()->F4RC8JHF4W45AltoRiesgoTest.main(new String[0]));
        suites.put("F4RC8JHF5BossRarityDistributionTest",()->F4RC8JHF5BossRarityDistributionTest.main(new String[0]));
        suites.put("F4W36BalanceContractTest",()->F4W36BalanceContractTest.main(new String[0]));
        suites.put("F4W36RewardCapTest",()->F4W36RewardCapTest.main(new String[0]));
        suites.put("F4W40RewardsContractTest",F4W40RewardsContractTest::run);
        suites.put("F4ModalidadesPremiosTest",F4ModalidadesPremiosTest::run);
        suites.put("F4RC3DiversityPaperTest",F4RC3DiversityPaperTest::run);
        suites.put("F4RC5PaperLedgerTest",()->F4RC5PaperLedgerTest.run(plugin,session));
        suites.put("F4RC8CDiarioLabTest",()->F4RC8CDiarioLabTest.run(plugin));
        suites.put("F4RC8DPaperLedgerTest",()->F4RC8DPaperLedgerTest.run(plugin));
        suites.put("F4W41BenefitsContractTest",F4W41BenefitsContractTest::run);
        suites.put("F4W45ProgressiveLootTest",F4W45ProgressiveLootTest::run);
        if(plugin.getDescription().getVersion().startsWith("2.1"))
            suites.put("CleanPaperContractsTest",()->{
                var m=Class.forName("com.hardcoremisiones.CleanPaperContractsTest").getDeclaredMethod("run",HardcoreMisiones.class);
                m.invoke(null,plugin);
            });
        if(plugin.getDescription().getVersion().startsWith("2.1"))
            suites.put("PaymentPersistencePaperTest",()->Class.forName("com.hardcoremisiones.PaymentPersistencePaperTest").getDeclaredMethod("main",String[].class).invoke(null,(Object)new String[0]));
        if(plugin.getDescription().getVersion().startsWith("2.1"))
            suites.put("HuntAwardPlannerTest",()->Class.forName("com.hardcoremisiones.HuntAwardPlannerTest").getDeclaredMethod("main",String[].class).invoke(null,(Object)new String[0]));
        if(plugin.getDescription().getVersion().startsWith("2.1"))
            suites.put("PenanceAndWavePaperTest",()->Class.forName("com.hardcoremisiones.PenanceAndWavePaperTest").getDeclaredMethod("run",HardcoreMisiones.class).invoke(null,plugin));
        suites.put("CorrectiveBlockPaperTest",()->CorrectiveBlockPaperTest.main(new String[0]));
        for(var entry:suites.entrySet()){
            try{entry.getValue().run();plugin.getLogger().info("HM2_PAPER_CONTRACT PASS "+entry.getKey());}
            catch(Throwable error){plugin.getLogger().log(java.util.logging.Level.SEVERE,"HM2_PAPER_CONTRACT FAIL "+entry.getKey(),error);}
        }
        plugin.getLogger().info("HM2_PAPER_CONTRACT END "+suites.size());
    }
    @FunctionalInterface private interface Checked {void run() throws Exception;}
}
