package dev.local.numensb;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for Sophisticated Backpacks.
 *
 * <p>Registers {@code sb.back.*}: read the carried backpack, take items out and put them in without
 * taking it off, and open its real window when a tab or the crafting upgrade is needed (see
 * {@link SbApi}).
 *
 * <p>Everything lives behind {@code ModList.get().isLoaded("numen")} and
 * {@code isLoaded("sophisticatedbackpacks")}, so the jar is inert without either of them.
 */
public final class SbMod {

    public static final String MODID = "numen_sb";
    public static final String BUILD = "s4-shared-zh";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public SbMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasBackpacks = ModList.get().isLoaded("sophisticatedbackpacks");
        LOG.warn("[numen_sb] constructed build={} numen={} backpacks={}", BUILD, hasNumen, hasBackpacks);
        if (!hasNumen || !hasBackpacks) {
            return;
        }
        try {
            NumenPlugins.register("sb", numen -> SbApi.install(numen));
            LOG.warn("[numen_sb] registered sb.back.*");
        } catch (Throwable t) {
            LOG.error("[numen_sb] registration failed", t);
        }
    }
}
