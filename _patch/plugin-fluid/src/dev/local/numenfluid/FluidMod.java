package dev.local.numenfluid;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin exposing {@code fluid.*}: what is actually inside the tanks around her.
 *
 * <p>Needs nothing but Forge and numen: it reads the {@code FLUID_HANDLER} capability, which is the
 * common surface every fluid-storing machine in the pack speaks — Create's fluid tanks and pipes,
 * Immersive Engineering's tanks and refinery, and any other mod that registers the capability.
 *
 * <p>Everything here is a read. Nothing is drained, filled or piped.
 */
public final class FluidMod {

    public static final String MODID = "numen_fluid";
    public static final String BUILD = "fluid3-visible";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public FluidMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        LOG.warn("[numen_fluid] constructed build={} numen={}", BUILD, hasNumen);
        if (!hasNumen) {
            return;
        }
        try {
            Show.install();
            NumenPlugins.register("fluid", FluidApi::install);
            LOG.warn("[numen_fluid] registered fluid.*");
        } catch (Throwable t) {
            LOG.error("[numen_fluid] registration failed", t);
        }
    }
}
