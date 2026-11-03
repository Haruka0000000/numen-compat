package dev.local.numencorpse;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.ServerCall;

import de.maxhenkel.corpse.entities.CorpseEntity;
import de.maxhenkel.corpse.gui.Guis;
import de.maxhenkel.corpse.gui.ITransferrable;

import net.minecraft.core.NonNullList;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code corpse}：地上那些尸体。
 *
 * <p>尸体模组把东西分成四个桶：窗口里看得到的 36 格背包、4 格盔甲、副手一格，外加一个隐藏的
 * 「附加物品」桶。饰品（curio / 挂坠这类）塞不进窗口的格子，所以死的时候会被追加到那个隐藏桶里，
 * 只有点开第二个窗口才看得到。一个只把看得见的格子掏空就走人的同伴，正好会把那些东西留下——
 * 这就是她的饰品栏总是空着的原因。
 *
 * <p>{@code corpse.near} 在她离开之前就把隐藏桶摊开给她看；{@code corpse.loot} 走模组自己的
 * 「全部转移」路径，一直掏到什么都不剩或者背包塞满为止。
 *
 * <p>两个接口都是<b>只读世界、只动她自己的背包</b>：不会偷别人的东西，也不会动别人的尸体——
 * 只捡范围内的尸体实体，谁死的一样对待（这就是「帮你捡尸体」的意思）。
 *
 * <p>要注意的一点：<b>她自己不会有尸体</b>。她的血量被模组层保护着，{@code /kill} 和
 * {@code /damage} 都打不死她，死了也不会生成尸体实体。所以这组接口的用途只有两个：
 * 帮你把你自己的尸体捡回来，以及顺手看看别人尸体里有什么。
 *
 * <p>本 jar 是照着 SRG 服务端 jar 编译的，所以这里原版方法用的是 SRG 名
 * （{@code m_41619_} 就是 {@code isEmpty}），生产环境里跑的就是这个。
 */
public final class CorpseApi {

    static final String NAMESPACE = "corpse";

    /** 名字列多了只是噪声：总数已经说明里面有多少。 */
    private static final int MAX_SAMPLE = 6;

    private static final double NEAR_DEFAULT_RADIUS = 16.0D;

    private static final double LOOT_DEFAULT_RADIUS = 8.0D;

    /** 半径上限：再远就看不见尸体实体了（区块不加载，实体等于不存在）。 */
    private static final double MAX_RADIUS = 48.0D;

    private CorpseApi() {
    }

    static void install(NumenApi numen) {
        numen.api("corpse", "你附近地上的那些尸体：里面还剩什么（包括饰品掉进去的那个隐藏桶），"
                + "以及一个把尸体掏空的调用。", CorpseApi.class);
    }

    /** 看多远，单位是格；corpse.near 默认 16，corpse.loot 默认 8。 */
    public record NearArgs(
            @Omitted("不写就按 corpse.near 的 16 格、corpse.loot 的 8 格算") Optional<Double> radius) {
    }

    /** 一具尸体。 */
    public record Here(
            @Doc("离你多远，单位格。") double distance,
            @Doc("它所在的方块坐标。") int x,
            @Doc("它所在的方块坐标。") int y,
            @Doc("它所在的方块坐标。") int z,
            @Doc("这是谁的尸体（死的人的名字）；旧版尸体可能是空字符串。") String owner,
            @Doc("死了多久，单位秒；读不出来是 -1。") long age_seconds,
            @Doc("36 格背包里还剩几堆。") int main,
            @Doc("四格盔甲里还剩几堆。") int armor,
            @Doc("副手格里还剩几堆。") int offhand,
            @Doc("隐藏的附加桶里还剩几堆——饰品、挂坠这类东西都掉进这里。") int extra,
            @Doc("上面四项加起来。") int total,
            @Doc("里面还有什么，最多列几样，好让你判断值不值得跑一趟；同名物品跨桶合并计数。")
            List<String> sample) {
    }

    /** 范围内的全部尸体，最近的排前面。 */
    public record Near(
            @Doc("范围里有几具尸体。") int count,
            @Doc("每具尸体一条，最近的排前面。") List<Here> items,
            @Doc("没有尸体时的解释：可能是范围内真的没有，也可能是那边区块没加载。") String note) {
    }

    /** 一次掏尸体掏出了什么。 */
    public record Looted(
            @Doc("这次调用从尸体里搬走了几堆。") int moved,
            @Doc("里面还剩几堆。") int left,
            @Doc("只有一点都不剩了才是 true。") boolean emptied,
            @Doc("剩下的怎么处理，有剩的时候才给。") Optional<String> note) {
    }

    @Fn("列出你附近的尸体，以及每具里面还剩什么，包括饰品掉进去的那个隐藏桶。最近的排前面。")
    @Example("corpse.corpse.near()")
    @Example("corpse.corpse.near({radius = 24})")
    @Note("离开一具尸体之前问一次、掏完再问一次：隐藏桶不显示在窗口里，"
            + "所以看起来空了的尸体可能还藏着你自己的饰品。")
    @Note("她自己不会有尸体（模组层保护着，杀不掉），所以这组接口是帮你捡你自己的尸体用的。")
    public static Near near(ServerCall call, NearArgs args) {
        ServerPlayer bot = call.her();
        double radius = radiusOf(args, NEAR_DEFAULT_RADIUS, "corpse.corpse.near()");
        List<CorpseEntity> corpses = find(bot, radius);
        corpses.sort(Comparator.comparingDouble(c -> distance(bot, c)));
        List<Here> out = new ArrayList<>(corpses.size());
        for (CorpseEntity corpse : corpses) {
            out.add(describe(bot, corpse));
        }
        String note = "";
        if (out.isEmpty()) {
            note = "半径 " + fmt(radius) + " 格里没找到尸体。实体只存在于已加载的区块里，"
                    + "所以如果那边是很久没去的地方，可能尸体在、但区块没加载、她就看不见。";
        }
        return new Near(out.size(), out, note);
    }

    @Fn("把最近的尸体掏空，走的是模组的「全部转移」按钮那条路：隐藏桶也会清掉，"
            + "报告还剩什么。")
    @Example("corpse.corpse.loot()")
    @Example("corpse.corpse.loot({radius = 4})")
    @Note("要站到尸体 8 格以内。掏完还有剩，多半是她背包满了：先放下点东西再调一次。"
            + "掏完窗口会自己关掉，不用再关。")
    public static Looted loot(ServerCall call, NearArgs args) {
        ServerPlayer bot = call.her();
        double radius = radiusOf(args, LOOT_DEFAULT_RADIUS, "corpse.corpse.loot()");
        CorpseEntity corpse = find(bot, radius).stream()
                .min(Comparator.comparingDouble(c -> distance(bot, c)))
                .orElse(null);
        if (corpse == null) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "半径 " + fmt(radius) + " 格里没有尸体",
                    "先走到尸体旁边，或者调 corpse.corpse.near() 看看周围有哪几具",
                    null);
        }
        if (corpse.isEmpty()) {
            return new Looted(0, 0, true, Optional.of("这具尸体本来就是空的"));
        }

        int before = count(corpse);
        boolean opened = false;

        opened |= drain(bot, corpse, true);
        for (int pass = 0; pass < 3 && !corpse.isEmpty(); pass++) {
            opened |= drain(bot, corpse, false);
        }

        int after = count(corpse);
        int moved = before - after;
        if (!opened) {
            close(bot);
            throw new ApiError(ErrorKind.FAILED,
                    "尸体窗口没能打开（" + before + " 堆还留在里面，一堆都没搬出来）",
                    "如果她正开着别的界面（背包、工作台、箱子），先 numen.gui.close() 关掉再试一次",
                    null);
        }
        Optional<String> note;
        if (after == 0) {
            note = Optional.empty();
        } else {
            note = Optional.of("还剩 " + after + " 堆没搬出来——她的背包多半满了，"
                    + "腾出空位再调一次 corpse.corpse.loot()");
        }
        return new Looted(moved, after, after == 0, note);
    }

    /**
     * 打开一层尸体窗口、跑一次模组的转移、再把窗口关掉。
     *
     * <p>窗口必须关：不关的话那层菜单会一直挂在她身上，之后她再跟别的东西交互就会错位
     * （以前实测过 {@code numen.gui.view()} 还会报 {@code CorpseInventoryContainer}）。
     *
     * @return 这次到底有没有拿到可转移的菜单；false 表示窗口没开成
     */
    private static boolean drain(ServerPlayer bot, CorpseEntity corpse, boolean inventory) {
        if (inventory) {
            Guis.openCorpseGUI(bot, corpse, true, false);
        } else {
            Guis.openAdditionalItems(bot, corpse, true, false);
        }
        boolean ok = transfer(bot);
        close(bot);
        return ok;
    }

    /** 模组自己的「全部转移」路径，对着她现在开着的那个尸体窗口跑。 */
    private static boolean transfer(ServerPlayer bot) {
        AbstractContainerMenu menu = bot.f_36096_;
        if (menu instanceof ITransferrable transferrable) {
            transferrable.transferItems();
            return true;
        }
        return false;
    }

    /** 把她身上挂着的容器关掉（ServerPlayer 重写过，是 public 的）。 */
    private static void close(ServerPlayer bot) {
        bot.m_6915_();
    }

    private static List<CorpseEntity> find(ServerPlayer bot, double radius) {
        Vec3 pos = bot.m_20182_();
        AABB box = new AABB(pos.f_82479_ - radius, pos.f_82480_ - radius, pos.f_82481_ - radius,
                pos.f_82479_ + radius, pos.f_82480_ + radius, pos.f_82481_ + radius);
        List<CorpseEntity> out = new ArrayList<>();
        for (Entity entity : bot.m_284548_().m_6249_(bot, box,
                e -> e instanceof CorpseEntity)) {
            out.add((CorpseEntity) entity);
        }
        return out;
    }

    private static Here describe(ServerPlayer bot, CorpseEntity corpse) {
        var death = corpse.getDeath();
        NonNullList<ItemStack> mainBucket = death.getMainInventory();
        NonNullList<ItemStack> armorBucket = death.getArmorInventory();
        NonNullList<ItemStack> offhandBucket = death.getOffHandInventory();
        NonNullList<ItemStack> extraBucket = death.getAdditionalItems();

        int main = countStacks(mainBucket);
        int armor = countStacks(armorBucket);
        int offhand = countStacks(offhandBucket);
        int extra = countStacks(extraBucket);

        Vec3 pos = corpse.m_20182_();
        String owner = death.getPlayerName() == null ? "" : death.getPlayerName();
        long age = death.getTimestamp() > 0L
                ? Math.max(0L, (System.currentTimeMillis() - death.getTimestamp()) / 1000L)
                : -1L;

        return new Here(Math.round(distance(bot, corpse) * 10.0D) / 10.0D,
                (int) Math.floor(pos.f_82479_), (int) Math.floor(pos.f_82480_), (int) Math.floor(pos.f_82481_),
                owner, age,
                main, armor, offhand, extra, main + armor + offhand + extra,
                sample(extraBucket, armorBucket, offhandBucket, mainBucket));
    }

    /**
     * 挑几样东西列出来。
     *
     * <p>四个桶都要看：以前只扫「隐藏桶 + 主背包」，于是最值钱的盔甲和副手永远不出现在清单里，
     * 看着像具空尸体，跑过去才发现里面躺着钻石头盔。
     *
     * <p>同名物品跨桶合并计数：以前只在单个桶里累加，主背包 2+1 个面包、隐藏桶 4 个，
     * 会报成「面包 x4」和「面包 x3」两条，加起来才对。
     */
    private static List<String> sample(NonNullList<ItemStack>... buckets) {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (NonNullList<ItemStack> bucket : buckets) {
            for (ItemStack stack : bucket) {
                if (stack.m_41619_()) {
                    continue;
                }
                totals.merge(Zh.name(stack), (long) stack.m_41613_(), Long::sum);
            }
        }
        List<String> out = new ArrayList<>(Math.min(totals.size(), MAX_SAMPLE));
        for (Map.Entry<String, Long> entry : totals.entrySet()) {
            if (out.size() >= MAX_SAMPLE) {
                break;
            }
            out.add(entry.getKey() + " x" + entry.getValue());
        }
        return out;
    }

    private static int count(CorpseEntity corpse) {
        return countStacks(corpse.getDeath().getMainInventory())
                + countStacks(corpse.getDeath().getArmorInventory())
                + countStacks(corpse.getDeath().getOffHandInventory())
                + countStacks(corpse.getDeath().getAdditionalItems());
    }

    private static int countStacks(NonNullList<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) {
            if (!stack.m_41619_()) {
                count++;
            }
        }
        return count;
    }

    private static double distance(ServerPlayer bot, Entity entity) {
        Vec3 a = bot.m_20182_();
        Vec3 b = entity.m_20182_();
        double dx = a.f_82479_ - b.f_82479_;
        double dy = a.f_82480_ - b.f_82480_;
        double dz = a.f_82481_ - b.f_82481_;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 半径：越界报 bad_argument，不再静默改成别的数。 */
    private static double radiusOf(NearArgs args, double fallback, String callName) {
        if (args == null || args.radius().isEmpty()) {
            return fallback;
        }
        double want = args.radius().get();
        if (!(want >= 1.0D) || want > MAX_RADIUS) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "半径只收 1 到 " + (int) MAX_RADIUS + " 格，给的是 " + fmt(want),
                    "写 " + callName + "({radius = 16})；不写就是 " + fmt(fallback) + " 格",
                    null);
        }
        return want;
    }

    private static String fmt(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }
}
