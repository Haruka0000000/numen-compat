package dev.local.numenskills;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen plugin exposing {@code skills.*}: Pufferfish Skills levels, progress and unspent points,
 * for the companion's own body only.
 *
 * <p>Compiled against the mod's own {@code SkillsAPI}, so the numbers match its screen exactly
 * instead of being re-derived from saved data.
 *
 * <p>Everything here is a read. No point is granted, no skill unlocked, no screen opened.
 */
public final class SkillsMod {

    public static final String MODID = "numen_skills";
    public static final String BUILD = "skills2-self";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public SkillsMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasSkills = ModList.get().isLoaded("puffish_skills");
        LOG.warn("[numen_skills] constructed build={} numen={} puffish_skills={}", BUILD, hasNumen, hasSkills);
        if (!hasNumen || !hasSkills) {
            return;
        }
        try {
            NumenPlugins.register("skills", SkillsApi::install);
            LOG.warn("[numen_skills] registered skills.*");
        } catch (Throwable t) {
            LOG.error("[numen_skills] registration failed", t);
        }
    }
}
