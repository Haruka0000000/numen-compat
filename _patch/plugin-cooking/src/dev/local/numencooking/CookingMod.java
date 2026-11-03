package dev.local.numencooking;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for FarmersDelight kitchens.
 *
 * <p>Registers {@code cooking.kitchen.*}: whether a cooking pot is heated, what went into it, how
 * far the cooking got, and what a cutting board or a skillet is holding.
 *
 * <p>The reason this cannot be guessed from the block: a cold pot and a working pot are the same
 * block with the same contents, a finished meal and a raw ingredient sit in the same nine slots, and
 * a campfire under the pot looks exactly like a stove. FarmersDelight only accepts the
 * {@code farmersdelight:heat_sources} block tag, so the pack's most common mistake - a pot parked on
 * a campfire - is invisible from outside and produces a meal that simply never appears.
 */
public final class CookingMod {

    public static final String MODID = "numen_cooking";
    public static final String BUILD = "cooking3-servable";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public CookingMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasFD = ModList.get().isLoaded("farmersdelight");
        LOG.warn("[numen_cooking] constructed build={} numen={} farmersdelight={}", BUILD, hasNumen, hasFD);
        if (!hasNumen) {
            return;
        }
        if (!hasFD) {
            LOG.warn("[numen_cooking] farmersdelight not installed, staying quiet");
            return;
        }
        try {
            NumenPlugins.register("cooking", numen -> CookingApi.install(numen));
            LOG.warn("[numen_cooking] registered cooking.kitchen.* ({})", CookingApi.health());
        } catch (Throwable t) {
            LOG.error("[numen_cooking] registration failed", t);
        }
    }
}
