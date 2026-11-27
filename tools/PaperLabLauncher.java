package hm2.lab;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
/** Runs after the server has loaded all worlds and shuts down the disposable lab. */
public final class PaperLabLauncher extends JavaPlugin {
    @Override public void onEnable(){
        Bukkit.getScheduler().runTaskLater(this,()->{
            try {
                for(String required:System.getProperty("hm2.lab.requirePlugins", "").split(",")) {
                    if(required.isBlank())continue;
                    if(!Bukkit.getPluginManager().isPluginEnabled(required))throw new IllegalStateException("Required plugin disabled: "+required);
                    getLogger().info("HM2_PAPER_CONTRACT REQUIRED_PLUGIN_ENABLED "+required);
                }
                var plugin=Bukkit.getPluginManager().getPlugin("HardcoreMisiones");
                if(plugin==null||!plugin.isEnabled())throw new IllegalStateException("HardcoreMisiones not enabled");
                var harness=Class.forName("com.hardcoremisiones.PaperContractHarness",true,plugin.getClass().getClassLoader());
                harness.getMethod("prepare").invoke(null);
                Bukkit.getScheduler().runTaskLater(this,()->{
                    try { harness.getMethod("run").invoke(null); }
                    catch(Throwable error){getLogger().log(java.util.logging.Level.SEVERE,"HM2_PAPER_CONTRACT HARNESS_FAILURE",error);}
                    finally { Bukkit.shutdown(); }
                },20L);
            }catch(Throwable error){getLogger().log(java.util.logging.Level.SEVERE,"HM2_PAPER_CONTRACT HARNESS_FAILURE",error);Bukkit.shutdown();}
        },20L);
    }
}
