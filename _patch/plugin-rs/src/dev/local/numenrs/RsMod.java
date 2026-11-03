package dev.local.numenrs;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for Refined Storage.
 *
 * <p>Registers {@code rs.*}: whether a network is near her, where its controller is, how much power
 * and how many kinds of goods it holds, what a search turns up, and what is still being auto-crafted.
 * She reads the shelf; she never takes from it, so the pack's stock cannot drift because of her.
 *
 * <p>Everything lives behind {@code ModList.get().isLoaded("numen")} and
 * {@code isLoaded("refinedstorage")}, so the jar is inert without either of them.
 */
public final class RsMod {

    public static final String MODID = "numen_rs";
    public static final String BUILD = "rs5-visible";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public RsMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasRs = ModList.get().isLoaded("refinedstorage");
        LOG.warn("[numen_rs] constructed build={} numen={} refinedstorage={}", BUILD, hasNumen, hasRs);
        if (!hasNumen || !hasRs) {
            return;
        }
        try {
            Show.install();
            NumenPlugins.register("rs", numen -> RsApi.install(numen));
            LOG.warn("[numen_rs] registered rs.*");
        } catch (Throwable t) {
            LOG.error("[numen_rs] registration failed", t);
        }
    }
}
