package dev.local.numeneffects;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin for potion effects.
 *
 * <p>Registers {@code effects.*}: which effects a body carries, how long each has left, and whether
 * they are working for or against her, plus a {@code <effects>} body-state line for burning and
 * blindness - the two states that quietly make every other reading wrong.
 *
 * <p>Vanilla only: {@code MobEffectInstance} and the effect registry ship with Minecraft, and modded
 * effects register into the same registry, so there is nothing to guard beyond {@code numen}
 * itself.
 */
public final class EffectsMod {

    public static final String MODID = "numen_effects";
    public static final String BUILD = "effects2-zh-query";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public EffectsMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        LOG.warn("[numen_effects] constructed build={} numen={}", BUILD, hasNumen);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("effects", numen -> EffectsApi.install(numen));
            LOG.warn("[numen_effects] registered effects.* (+ body_state <effects>) ({})",
                    EffectsApi.health());
        } catch (Throwable t) {
            LOG.error("[numen_effects] registration failed", t);
        }
    }
}
