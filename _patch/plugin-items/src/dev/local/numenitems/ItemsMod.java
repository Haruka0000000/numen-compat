package dev.local.numenitems;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin exposing {@code items.*}: what is actually in a hand.
 *
 * <p>Needs nothing but vanilla and numen. Enchantments and modded NBT are read straight off the
 * stack, so this works for TaCZ guns, Sophisticated backpacks and plain vanilla gear alike.
 *
 * <p>Everything here is a read. Nothing is moved, equipped, repaired or dropped.
 */
public final class ItemsMod {

    public static final String MODID = "numen_items";
    public static final String BUILD = "items1-read";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public ItemsMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        LOG.warn("[numen_items] constructed build={} numen={}", BUILD, hasNumen);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("items", ItemsApi::install);
            LOG.warn("[numen_items] registered items.*");
        } catch (Throwable t) {
            LOG.error("[numen_items] registration failed", t);
        }
    }
}
