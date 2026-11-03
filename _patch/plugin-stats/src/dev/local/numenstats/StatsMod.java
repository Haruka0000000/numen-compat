package dev.local.numenstats;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen 插件，暴露 {@code stats.*}：一具身体现在真正生效的属性。
 *
 * <p>除了原版和 numen 什么都不需要：属性是原版概念，模组加的属性也走同一张表，
 * 所以一组一个函数就覆盖了护甲、药水、饰品的加成，不用点名任何模组。
 *
 * <p>全是读。不改属性、不施加效果、不删任何东西。
 */
public final class StatsMod {

    public static final String MODID = "numen_stats";
    public static final String BUILD = "stats2-live";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public StatsMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        LOG.warn("[numen_stats] constructed build={} numen={}", BUILD, hasNumen);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("stats", StatsApi::install);
            LOG.warn("[numen_stats] registered stats.*");
        } catch (Throwable t) {
            LOG.error("[numen_stats] registration failed", t);
        }
    }
}
