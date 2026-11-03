package dev.local.numencompat;

import com.dwinovo.numen.api.NumenPlugins;

import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 全部兼容插件合成的一个 mod:一个 jar、模组列表里一条。
 *
 * <p>每个插件原本是独立 jar,各自一个 {@code @Mod} 入口、各自在构造函数里判 {@code isLoaded}
 * 再 {@code NumenPlugins.register(...)}。这里把那 20 个入口当普通类 new 出来——判依赖、注册组名、
 * 出错只记日志,逻辑一个字没抄,只是搬到了同一个 jar 里。入口类本身已经去掉了 {@code @Mod} 注解,
 * 否则同一个 jar 里会有 20 个找不到元数据的匿名 mod。
 *
 * <p>一处失败不连累别人:每个插件单独 try/catch。缺某个模组时它自己 return,这里看到的只是一条
 * "没注册"的日志——与合并前分开装 20 个 jar 时的表现一致。
 */
@Mod(CompatMod.MODID)
public final class CompatMod {

    public static final String MODID = "numen_compat";
    public static final String BUILD = "merge1";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public CompatMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        LOG.warn("[numen_compat] constructed build={} numen={}", BUILD, hasNumen);
        if (!hasNumen) {
            return;
        }
        List<String> registered = new ArrayList<>();
        for (String[] each : ENTRIES) {
            String name = each[0];
            try {
                entry(each[1]).get();
                registered.add(name);
            } catch (Throwable t) {
                // 一个插件炸了不该带走其余 19 个:与分开装机时"只少一个 jar"等价。
                LOG.error("[numen_compat] {} failed to register", name, t);
            }
        }
        LOG.warn("[numen_compat] {} plugins constructed: {}", registered.size(), registered);
    }

    /**
     * 名字只用于日志。顺序与原来分开装时 forge 的构造顺序无关,插件之间互不依赖。
     */
    private static final String[][] ENTRIES = {
            {"items", "dev.local.numenitems.ItemsMod"},
            {"power", "dev.local.numenpower.PowerMod"},
            {"schematic", "dev.local.numenschematic.SchematicMod"},
            {"vehicles", "dev.local.numenvehicles.VehiclesMod"},
            {"rs", "dev.local.numenrs.RsMod"},
            {"sb", "dev.local.numensb.SbMod"},
            {"fluid", "dev.local.numenfluid.FluidMod"},
            {"tacz", "dev.local.numentacz.TaczMod"},
            {"lso", "dev.local.numenlso.LsoMod"},
            {"stats", "dev.local.numenstats.StatsMod"},
            {"effects", "dev.local.numeneffects.EffectsMod"},
            {"skills", "dev.local.numenskills.SkillsMod"},
            {"threat", "dev.local.numenthreat.ThreatMod"},
            {"hordes", "dev.local.numenhordes.HordesMod"},
            {"lostcity", "dev.local.numenlostcity.LostCityMod"},
            {"i18n", "dev.local.numeni18n.I18nMod"},
            {"parcool", "dev.local.numenparcool.ParCoolMod"},
            {"corpse", "dev.local.numencorpse.CorpseMod"},
            {"cooking", "dev.local.numencooking.CookingMod"},
            {"pingwheel", "dev.local.numenpingwheel.PingMod"},
    };

    /**
     * 按类名建一个入口。
     *
     * <p>用反射而不是直接 {@code new}:直接写就得把 20 个类都 imports 一遍,而 {@code javac} 会因此
     * 在编译期就去解析它们引用到的模组类型——一个没装的模组会让整个 jar 编译失败。反射把这份
     * 依赖推迟到运行期:缺模组的插件在它自己的构造里 {@code return},不会波及其余。
     */
    private static Supplier<Object> entry(String className) {
        return () -> {
            try {
                return Class.forName(className).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot construct " + className, e);
            }
        };
    }
}
