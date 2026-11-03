package dev.local.numencorpse;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 尸体模组的 Numen 插件。
 *
 * <p>注册 {@code corpse.*}：地上的尸体里还剩什么（含饰品掉进去的隐藏桶），
 * 以及一个走模组「全部转移」那条路把尸体掏空的调用。
 *
 * <p>整块逻辑都躲在 {@code ModList.get().isLoaded("numen")} 和 {@code isLoaded("corpse")} 后面，
 * 少了任何一个这个 jar 都什么也不做。
 */
public final class CorpseMod {

    public static final String MODID = "numen_corpse";
    public static final String BUILD = "c2-honest";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public CorpseMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasCorpse = ModList.get().isLoaded("corpse");
        LOG.warn("[numen_corpse] constructed build={} numen={} corpse={}", BUILD, hasNumen, hasCorpse);
        if (!hasNumen || !hasCorpse) {
            return;
        }
        try {
            NumenPlugins.register("corpse", numen -> CorpseApi.install(numen));
            LOG.warn("[numen_corpse] registered corpse.*");
        } catch (Throwable t) {
            LOG.error("[numen_corpse] registration failed", t);
        }
    }
}
