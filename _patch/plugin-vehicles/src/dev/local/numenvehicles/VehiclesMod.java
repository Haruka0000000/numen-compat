package dev.local.numenvehicles;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin exposing {@code vehicles.*}: the cars and planes standing around her.
 *
 * <p>The vehicle mod is optional and is never compiled against: {@link VehiclesApi} reads its entity
 * hierarchy through reflection, so the jar loads with or without Immersive Vehicles installed. The
 * registration log line states which fields resolved, so an update that renames them shows up there
 * instead of as a silent empty scan.
 *
 * <p>The scanning calls only read. {@code ride} and {@code leave} move her body, through the
 * permission layer; nothing is started, fuelled or driven.
 */
public final class VehiclesMod {

    public static final String MODID = "numen_vehicles";
    public static final String BUILD = "vehicles4-canonical";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public VehiclesMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasVehicles = ModList.get().isLoaded("mts");
        LOG.warn("[numen_vehicles] constructed build={} numen={} mts={}", BUILD, hasNumen, hasVehicles);
        if (!hasNumen) {
            return;
        }
        if (!hasVehicles) {
            LOG.warn("[numen_vehicles] Immersive Vehicles (mts) not installed, vehicles.* not registered");
            return;
        }
        try {
            NumenPlugins.register("vehicles", VehiclesApi::install);
            LOG.warn("[numen_vehicles] registered vehicles.* (reflection ready={})",
                    VehiclesApi.reflectionState());
        } catch (Throwable t) {
            LOG.error("[numen_vehicles] registration failed", t);
        }
    }
}
