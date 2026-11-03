package dev.local.numenparcool;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Numen 插件，暴露两组函数：
 *
 * <ul>
 *   <li>{@code parcool.parcool.*}——看：主人的气力、ParCool 动作能不能用、她面前的墙；</li>
 *   <li>{@code parcool.parkour.*}——做：蹬墙跳、翻越、滑铲、翻滚、抓边上墙（自己按物理算，不经过 ParCool）。</li>
 * </ul>
 *
 * <p>ParCool 是软依赖：没装也照样注册命名空间，只是 {@code parcool} 那一组每个函数都回答「读不到」，
 * 而 {@code parkour} 那一组照常能用——它只依赖原版物理。这比整个插件消失、让脚本里那句
 * parcool.parkour.vault() 变成「未知函数」要好查得多。
 *
 * <p>构造里不做重活：不碰气力、不碰服主限制（那两样要等存档读进来才可信），注册完就走。
 */
public final class ParCoolMod {

    public static final String MODID = "numen_parcool";
    public static final String BUILD = "parcool3-parkour";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public ParCoolMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasParCool = ModList.get().isLoaded("parcool");
        LOG.warn("[numen_parcool] constructed build={} numen={} parcool={}", BUILD, hasNumen, hasParCool);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("parcool", numen -> ParCoolApi.install(numen));
            NumenPlugins.register("parkour", numen -> ParkourApi.install(numen));
            Motion.register();
            LOG.warn("[numen_parcool] registered parcool.parcool.* and parkour.parkour.* (parcool={})", hasParCool);
        } catch (Throwable t) {
            LOG.error("[numen_parcool] registration failed", t);
        }
    }
}
