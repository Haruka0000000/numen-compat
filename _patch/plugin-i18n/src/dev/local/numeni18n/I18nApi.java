package dev.local.numeni18n;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code i18n.i18n}：把一个 id 翻成中文名，或者反过来说「这个整合包里哪些东西还没中文名」。
 *
 * <p>整个整合包的所有 {@code zh_cn.json} 被 {@link I18n} 汇成一张表（原版 + 所有模组
 * + {@code config/numen/lang/*.json} 本地语言包 + {@code config/numen/i18n_zh_cn.json} 手写覆盖）。
 * 这里的三个函数就是那张表的门：问名字、找漏翻、改完覆盖文件之后重读一遍。
 *
 * <p>全是读。除了 {@code reload()} 重读文件，不写任何东西。
 */
public final class I18nApi {

    /** 一次最多列多少个没翻的名字。 */
    static final int MISSING_MAX = 40;

    private I18nApi() {
    }

    static void install(NumenApi numen) {
        numen.api("i18n", "查 id 的中文名，和找出整合包里还没翻译的东西。", I18nApi.class);
    }

    /** 一个 id 的中文名。 */
    @Doc("一个 id 的中文名。")
    public record Word(
            @Doc("你问的那个 id，规范成 ns:path 之后的样子。") String id,
            @Doc("它在哪些注册表里：item / block / entity / effect / fluid / enchantment / attribute。") List<String> kinds,
            @Doc("最后用上的那条语言键，例如 item.minecraft.diamond_sword；空表示没找到。") String key,
            @Doc("中文名；found 为 false 时是空串。") String name,
            @Doc("有没有找到中文名。") boolean found,
            @Doc("这个中文名是从哪儿来的：原版 / 模组 / 手写覆盖；没找到就是空串。") String source,
            @Doc("一句话，直接念给主人听就行。") String line) {
    }

    /** 一批还没中文名的 id。 */
    @Doc("一个命名空间里还没中文名的那些东西。")
    public record Missing(
            @Doc("查的是哪个命名空间。") String namespace,
            @Doc("一共看了多少个 id。") int checked,
            @Doc("其中没有中文名的有多少个（可能比 rows 多）。") int missing,
            @Doc("列出来的前几个 id（最多 40 个）。") List<String> rows,
            @Doc("一句话总结。") String line) {
    }

    /** {@code i18n.missing} 的选项。 */
    @Doc("找漏翻时要看的范围。")
    public record MissingOpt(
            @Doc("只查这个命名空间，例如 farmersdelight；不写就查所有模组。") @Omitted("所有") Optional<String> namespace,
            @Doc("最多列几个，1–40。") @Omitted("20") Optional<Integer> limit) {
    }

    @Fn("问一个 id 的中文名：物品、方块、实体、状态效果、流体都能问。")
    @Example("print(i18n.i18n.name(\"minecraft:diamond_sword\").name)")
    @Example("print(i18n.i18n.name(\"farmersdelight:bacon\").line)")
    @Note("id 不用写命名空间，不写就是 minecraft。也可以直接给一条语言键（例如 item.minecraft.apple）。"
            + "found 为 false 不代表你写错了——很可能只是这个整合包没给它做中文翻译，"
            + "那把 id 照原样念出来就行，别自己编一个中文名。")
    public static Word name(ServerCall call, IdArg args) {
        String raw = args == null || args.id() == null ? "" : args.id().trim();
        if (raw.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "要问哪个 id 的中文名？给的是空",
                    "写 i18n.i18n.name(\"minecraft:diamond_sword\") 这样；方块、物品、实体、效果、流体都能问");
        }
        List<String> kinds = I18n.kindsOf(raw);
        String key = "";
        String hit = null;
        for (String candidate : I18n.keysFor(raw)) {
            String found = I18n.lookup(candidate);
            if (found != null) {
                key = candidate;
                hit = found;
                break;
            }
        }
        ResourceLocation rl = I18n.parse(raw);
        // 直接给语言键（带点、不带冒号，例如 tacz.gun.ak47.name）时原样回，不补 minecraft: 前缀
        boolean literalKey = !raw.contains(":") && raw.contains(".");
        String id = literalKey || rl == null ? raw : rl.toString();
        boolean found = hit != null;
        String source = found ? I18n.originOf(key) : "";
        String line;
        if (!found) {
            if (kinds.isEmpty()) {
                line = "没有叫 " + id + " 的东西：哪个注册表里都没有它，可能拼错了";
            } else {
                line = "注册表里有 " + id + "（" + String.join("、", kinds) + "），但这个整合包没给它做中文名";
            }
        } else if (kinds.size() > 1) {
            line = id + " 的中文名是「" + hit + "」（它在 " + String.join("、", kinds) + " 里都有，名字取自 "
                    + source + "）";
        } else {
            line = id + " 的中文名是「" + hit + "」（来自 " + source + "）";
        }
        return new Word(id, kinds, key, found ? hit : "", found, source, line);
    }

    @Fn("列出某个模组里还没有中文名的东西——整合包的翻译有漏时用它，照着补进 config/numen/i18n_zh_cn.json。")
    @Example("print(i18n.i18n.missing({namespace = \"farmersdelight\", limit = 10}).line)")
    @Note("只查物品和方块（这两类是她说得最多的）；同一个 id 既是方块又是物品时只算一次。"
            + "行里给的是完整 id，直接当覆盖文件的键用的话要加前缀："
            + "{ \"item.farmersdelight.xxx\": \"中文名\" }（方块用 block. 开头）。"
            + "补大片的漏翻别手写：客户端资源包里的 zh_cn.json 复制到 config/numen/lang/ 目录就会自动读进来。")
    public static Missing missing(ServerCall call, MissingOpt args) {
        String ns = args == null || args.namespace().isEmpty() ? "" : args.namespace().get().trim();
        int limit = 20;
        if (args != null && args.limit().isPresent()) {
            Integer want = args.limit().get();
            if (want != null) {
                limit = want;
            }
        }
        if (limit < 1 || limit > MISSING_MAX) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "limit 只收 1 到 " + MISSING_MAX + "，给的是 " + limit,
                    "写 i18n.i18n.missing({namespace = \"farmersdelight\", limit = 10})；不写就是 20");
        }
        List<String> rows = new ArrayList<>();
        int checked = 0;
        int missing = 0;
        boolean anyNs = ns.isEmpty();
        if (!anyNs) {
            ResourceLocation probe = I18n.parse(ns + ":probe");
            if (probe == null) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "命名空间写坏了：" + ns,
                        "只要模组 id 那一截，例如 farmersdelight、minecraft，不要带冒号");
            }
        }
        // 一个 id 只数一次：既是方块又是物品的东西（橱柜、篮子这类）会被两张注册表各列一遍，
        // 分开问的话「物品名没有、方块名有」就会报成缺中文名。这里交给 nameOf 一次问全。
        java.util.LinkedHashSet<ResourceLocation> all = new java.util.LinkedHashSet<>();
        all.addAll(ForgeRegistries.ITEMS.getKeys());
        all.addAll(ForgeRegistries.BLOCKS.getKeys());
        for (ResourceLocation key : all) {
            if (!anyNs && !key.toString().startsWith(ns + ":")) {
                continue;
            }
            checked++;
            if (I18n.nameOf(key.toString()) == null) {
                missing++;
                if (rows.size() < limit) {
                    rows.add(key.toString());
                }
            }
        }
        String where = anyNs ? "整个整合包" : "模组 " + ns;
        String line;
        if (checked == 0) {
            line = "没找到" + where + "的物品或方块——命名空间写对了吗？";
        } else if (missing == 0) {
            line = "看了 " + checked + " 个，" + where + "的东西全都有中文名，不用补";
        } else {
            line = "看了 " + checked + " 个，" + where + "里有 " + missing + " 个没有中文名（列了 "
                    + rows.size() + " 个）";
        }
        return new Missing(anyNs ? "" : ns, checked, missing, rows, line);
    }

    @Fn("重新读一遍翻译表：手改了 config/numen/i18n_zh_cn.json 之后用它生效。")
    @Example("print(i18n.i18n.reload().line)")
    @Note("会顺带重扫一遍 mods 目录里的语言文件和 config/numen/lang/ 下的本地语言包，几秒钟；"
            + "平时不用调，改完覆盖文件或往 lang 目录里丢了新翻译才需要。")
    public static Reloaded reload(ServerCall call) {
        I18n.reload();
        int n = I18n.size();
        return new Reloaded(n, "重新读了翻译表，现在一共 " + n + " 条");
    }

    /** {@code i18n.reload()} 的回执。 */
    @Doc("重读之后的条数。")
    public record Reloaded(
            @Doc("现在表里有多少条翻译。") int entries,
            @Doc("一句话总结。") String line) {
    }

    /** {@code i18n.name} 的参数。 */
    @Doc("要问名字的那个 id。")
    public record IdArg(
            @Doc("例如 minecraft:diamond_sword、farmersdelight:bacon、minecraft:poison。") String id) {
    }

    /** 只给别的插件用：不带 Lua 的类型检查，直接拿名字。 */
    static Set<String> knownKinds(String id) {
        return Set.copyOf(I18n.kindsOf(id));
    }
}
