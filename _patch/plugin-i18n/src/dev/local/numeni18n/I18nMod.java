package dev.local.numeni18n;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen 插件，暴露 {@code i18n.*}：整个整合包的中文名总表。
 *
 * <p>以前每个插件自己打包一份 183KB 的原版名字表、各自扫一遍 mods 目录，20 个插件就是 20 份；
 * 现在只有这里一份，别的插件要名字就反射调 {@link I18n#lookup(String)}——少装本插件时它们
 * 退回 id 或英文名，不会崩（这也是它不做成「被别的插件依赖的类」的原因，见 {@code _patch/gen_zh.py}）。
 *
 * <p>只读。
 */
public final class I18nMod {

    public static final String MODID = "numen_i18n";
    public static final String BUILD = "i18n2-langdir";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public I18nMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        LOG.warn("[numen_i18n] constructed build={} numen={}", BUILD, hasNumen);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("i18n", I18nApi::install);
            LOG.warn("[numen_i18n] registered i18n.*");
        } catch (Throwable t) {
            LOG.error("[numen_i18n] registration failed", t);
        }
    }
}
