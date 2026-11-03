package dev.local.numenthreat;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen 插件，暴露 {@code threat.*}：朝她走过来的那只是什么做的。
 *
 * <p>除了原版和 numen 什么都不需要。DeceasedCraft 在这个整合包里叠了好几个僵尸改造模组
 * （Zombie Extreme、Undead Revamp、Deceased Beast），每一个都带「同一副外形、三倍血、还带甲」
 * 的变体；读属性表能一次覆盖全部，而且整合包以后再加模组也不用改这里——模组的数值就是游戏自己的
 * 数值，不是谁维护的一张表。
 *
 * <p>全是读：不写、不指定目标、不驯服、不伤害任何东西。
 */
public final class ThreatMod {

    public static final String MODID = "numen_threat";
    public static final String BUILD = "threat2-live-zh";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public ThreatMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        LOG.warn("[numen_threat] constructed build={} numen={}", BUILD, hasNumen);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("threat", numen -> ThreatApi.install(numen));
            LOG.warn("[numen_threat] registered threat.threat.* ({})", ThreatApi.health());
        } catch (Throwable t) {
            LOG.error("[numen_threat] registration failed", t);
        }
    }
}
