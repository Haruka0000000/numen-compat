package dev.local.numentacz;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.task.BrainChains;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for TaCZ (Timeless and Classics Zero).
 *
 * <p>Registers two things:
 * <ul>
 *   <li>{@code tacz.gun.*} — the Lua API the model calls to inspect, equip, fire and reload the gun
 *       in its hand (see {@link TaczApi}).</li>
 *   <li>{@link GunDefenseChain} — a body chain at priority 28 (above melee self-defense 30, below
 *       flee 25) that automatically shoots nearby hostiles while a gun is held.</li>
 * </ul>
 *
 * <p>Both live behind {@code ModList.get().isLoaded("numen")}, so the jar is inert without numen.
 */
public final class TaczMod {

    public static final String MODID = "numen_tacz";
    public static final String BUILD = "p8-hit-stance";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public TaczMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasTacz = ModList.get().isLoaded("tacz");
        LOG.warn("[numen_tacz] constructed build={} numen={} tacz={}", BUILD, hasNumen, hasTacz);
        if (!hasNumen || !hasTacz) {
            return;
        }
        try {
            NumenPlugins.register("tacz", numen -> TaczApi.install(numen));
            HitLog.register();
            // Gun self-defense: 25 (flee) > 28 (gun) > 30 (melee defense).
            BrainChains.register(28, GunDefenseChain::new);
            LOG.warn("[numen_tacz] registered tacz.gun.* and the gun_defense chain (order 28)");
        } catch (Throwable t) {
            LOG.error("[numen_tacz] registration failed", t);
        }
    }
}
