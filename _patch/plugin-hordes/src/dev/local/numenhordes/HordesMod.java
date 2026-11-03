package dev.local.numenhordes;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for The Hordes.
 *
 * <p>注册 {@code horde.*}：哪一夜尸潮会来、现在是不是正在打、还要打多久、谁在被追，以及她自己和
 * 主人身上有没有感染。数字都来自模组自己的存档与效果，不是靠时间猜的。
 *
 * <p>这里全是读：不开、不停、不引潮汐，也不加感染、不治病。
 */
public final class HordesMod {

    public static final String MODID = "numen_hordes";
    public static final String BUILD = "h2-live-zh";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public HordesMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasHordes = ModList.get().isLoaded("hordes");
        LOG.warn("[numen_hordes] constructed build={} numen={} hordes={}", BUILD, hasNumen, hasHordes);
        if (!hasNumen || !hasHordes) {
            return;
        }
        try {
            NumenPlugins.register("horde", numen -> {
                HordeApi.install(numen);
                // Tonight's horde rides along in her body state, so she does not have to spend a turn
                // asking whether it is safe to go up.
                try {
                    numen.contributeBodyState(HordeApi::stateLine);
                } catch (Throwable ignored) {
                    // fragment unavailable: horde.horde.state still answers
                }
            });
            LOG.warn("[numen_hordes] registered horde.horde.* (capability lookup ready={})",
                    HordeApi.capabilityLookupReady());
        } catch (Throwable t) {
            LOG.error("[numen_hordes] registration failed", t);
        }
    }
}
