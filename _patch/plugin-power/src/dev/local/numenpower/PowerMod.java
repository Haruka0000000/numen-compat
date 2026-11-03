package dev.local.numenpower;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for Forge energy.
 *
 * <p>Registers {@code power.*}: which blocks around her hold energy, how much, and whether any of
 * them is still pushing power out. It works through the Forge capability, not through a mod, so the
 * same group answers for Immersive Engineering, Extreme Reactors and everything else that ticks with
 * FE — and for nothing that does not.
 *
 * <p>Immersive Engineering's machines also have a per-face switch (input / output / none) that no
 * capability exposes. {@code power.power.sides} and {@code power.power.set_side} reach it by name
 * through reflection, so this jar still compiles and loads with IE absent.
 */
public final class PowerMod {

    public static final String MODID = "numen_power";
    public static final String BUILD = "power2-sides";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public PowerMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasIe = ModList.get().isLoaded("immersiveengineering");
        LOG.warn("[numen_power] constructed build={} numen={} immersiveengineering={}", BUILD, hasNumen, hasIe);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("power", numen -> PowerApi.install(numen));
            LOG.warn("[numen_power] registered power.* (capability lookup ready={})",
                    PowerApi.capabilityLookupReady());
        } catch (Throwable t) {
            LOG.error("[numen_power] registration failed", t);
        }
    }
}
