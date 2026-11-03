package dev.local.numenlso;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for Legendary Survival Overhaul.
 *
 * <p>The old mod registered {@code /botvitals} / {@code /botlimbs} / {@code /botlimbuse} /
 * {@code /botlimbinv} commands and drove them through {@code numen.mc.run}. That is backwards:
 * numen wants a plugin to register Lua APIs the model calls directly. So this mod only does one
 * thing in its constructor — {@code NumenPlugins.register("lso", numen -> numen.api(...))} — and
 * the model gets {@code lso.body.vitals()}, {@code lso.body.limbs()}, {@code lso.body.items()},
 * {@code lso.body.heal(...)} and {@code lso.body.malus()}.
 *
 * <p>The {@code isLoaded("numen")} guard keeps the jar from crashing a server that does not have
 * numen; the api classes are only resolved once that branch runs.
 *
 * <p>It logs, because a silent plugin is an unverifiable plugin: a registration that never ran and
 * a registration that worked look exactly the same in the server log otherwise.
 */
public final class LsoMod {

    public static final String MODID = "numen_lso";
    public static final String BUILD = "p4-visible";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public LsoMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasLso = ModList.get().isLoaded("legendarysurvivaloverhaul");
        LOG.warn("[numen_lso] constructed build={} numen={} lso={}", BUILD, hasNumen, hasLso);
        if (!hasNumen) {
            LOG.warn("[numen_lso] numen is not installed, nothing to extend");
            return;
        }
        Show.install();
        try {
            NumenPlugins.register("lso", numen -> LsoApi.install(numen));
            LOG.warn("[numen_lso] registered lso.body.* with numen");
        } catch (Throwable t) {
            LOG.error("[numen_lso] registration failed", t);
        }
    }
}
