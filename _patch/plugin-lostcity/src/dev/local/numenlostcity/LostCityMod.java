package dev.local.numenlostcity;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 言出法随插件：失落的城市。
 *
 * <p>注册 {@code lostcity.building.*} / {@code find.*} / {@code loot.*} / {@code stairs.*} /
 * {@code reach.*} / {@code multi.*} / {@code blueprint.*} 与 {@code lostcity.learn.*}，让她不用记方块 id
 * 就能读懂脚下的楼、找最近的医院军营、把周围的箱子与刷怪笼列出来。没有 numen 或没有失落城市时它是死的。
 */
public final class LostCityMod {

    public static final String MODID = "numen_lostcity";
    public static final String BUILD = "c6-sweep-fix";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public LostCityMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasLostCities = ModList.get().isLoaded("lostcities");
        LOG.warn("[numen_lostcity] constructed build={} numen={} lostcities={}", BUILD, hasNumen, hasLostCities);
        if (!hasNumen || !hasLostCities) {
            return;
        }
        try {
            NumenPlugins.register("lostcity", numen -> LostCityApi.install(numen));
            LOG.warn("[numen_lostcity] registered lostcity.* (building/find/loot[+sweep]/stairs/reach/multi/blueprint/learn)");
        } catch (Throwable t) {
            LOG.error("[numen_lostcity] registration failed, the Lua API is NOT available", t);
        }
    }
}
