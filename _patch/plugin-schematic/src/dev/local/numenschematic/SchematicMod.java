package dev.local.numenschematic;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code schematic}: read the Engineered Schematics blueprint a companion is carrying.
 *
 * <p>A blueprint that has been put down carries everything needed to know where the machine
 * goes: which IE multiblock it is, the anchor block it was placed on, and how it was rotated
 * and mirrored. The client draws that as a ghost; the server can compute exactly the same
 * cells, which is what {@link SchematicApi} does.
 *
 * <p>Everything lives behind {@code ModList.get().isLoaded("numen")},
 * {@code isLoaded("immersiveengineering")} and {@code isLoaded("engineered_schematics")}.
 */
public final class SchematicMod {

    public static final String MODID = "numen_schematic";
    public static final String BUILD = "schematic2-facing";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public SchematicMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasIe = ModList.get().isLoaded("immersiveengineering");
        boolean hasEs = ModList.get().isLoaded("engineered_schematics");
        LOG.warn("[numen_schematic] constructed build={} numen={} immersiveengineering={} engineered_schematics={}",
                BUILD, hasNumen, hasIe, hasEs);
        if (!hasNumen || !hasIe || !hasEs) {
            return;
        }
        try {
            NumenPlugins.register("schematic", numen -> SchematicApi.install(numen));
            LOG.warn("[numen_schematic] registered schematic.* ({})", SchematicApi.health());
        } catch (Throwable t) {
            LOG.error("[numen_schematic] registration failed", t);
        }
    }
}
