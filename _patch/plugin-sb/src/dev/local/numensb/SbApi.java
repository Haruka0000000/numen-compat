package dev.local.numensb;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Positional;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlockEntity;
import net.p3pp3rf1y.sophisticatedcore.upgrades.FilterLogic;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IFilteredUpgrade;
import net.p3pp3rf1y.sophisticatedcore.upgrades.IUpgradeWrapper;
import net.p3pp3rf1y.sophisticatedcore.upgrades.PrimaryMatch;
import net.p3pp3rf1y.sophisticatedcore.util.FilterItemStackHandler;
import java.lang.reflect.Method;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.registries.ForgeRegistries;

import net.p3pp3rf1y.sophisticatedbackpacks.api.CapabilityBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackItem;
import net.p3pp3rf1y.sophisticatedbackpacks.backpack.wrapper.IBackpackWrapper;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContainer;
import net.p3pp3rf1y.sophisticatedbackpacks.common.gui.BackpackContext;
import net.p3pp3rf1y.sophisticatedbackpacks.util.PlayerInventoryProvider;
import net.p3pp3rf1y.sophisticatedcore.inventory.InventoryHandler;
import net.p3pp3rf1y.sophisticatedcore.upgrades.UpgradeHandler;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * {@code sb.back}: 她身上背的精妙背包。
 *
 * <p>同伴是没有客户端的 {@code ServerPlayer}，所以模组自己的「按 B 打开背着的背包」永远弹不出来，
 * 旧办法（摘下来、拿在手上、用完再背回去）看一次要三个身体动作。背包的内容本来就在服务端，
 * {@link IBackpackWrapper#getInventoryHandler()} 就能读写，所以这些函数不弹窗口、不摘背包。
 *
 * <p>她身上可以有好几个背包：{@code sb.back.packs()} 把它们逐个列出来，其余函数用 {@code which}
 * （从 1 数起）点名要动哪一个，不写就是第一个。
 *
 * <p>{@code sb.back.open} 是例外：它打开真正的背包窗口（模组自己的标签页和合成升级要用它），
 * 之后交给 {@code numen.gui.*} 像别的窗口一样操作。
 *
 * <p>这个 jar 是对着 SRG 服务端 jar 编译的，所以原版方法在源码里写的是 SRG 名
 * （{@code m_41619_} 是 {@code isEmpty}），生产环境里跑的就是这个名字。
 */
public final class SbApi {

    static final String NAMESPACE = "sb";

    /** 一页最多列这么多行：背包最多几百格，再多也是废话。 */
    private static final int MAX_LINES = 256;

    private SbApi() {
    }

    static void install(NumenApi numen) {
        numen.api("back", "精妙背包：她背着的背包里有什么、还剩几格空位、装了什么升级；不用摘下来就能存取，"
                + "能给升级配白黑名单，能把背包放到地上再捡回来，也能打开它的真窗口。", SbApi.class);
    }

    // ------------------------------------------------------------------ 返回的形状

    /** 她背着的其中一个背包。 */
    public record Pack(
            @Doc("按 sb.back.packs() 列出的顺序，从 1 数起；别的函数用它当 which。") int index,
            @Doc("背包的名字，比如 钻石背包。") String name,
            @Doc("背在哪：背包里、副手里、护甲栏、饰品栏。") String where,
            @Doc("它的物品格总数。") int slots,
            @Doc("其中装了几格。") int used,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 她身上所有背包。 */
    public record Packs(
            @Doc("她背了几个精妙背包。") int count,
            @Doc("逐个列出，按这个顺序编号。") List<Pack> packs,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 其中一个背包的概况。 */
    public record Info(
            @Doc("她身上有没有精妙背包。") boolean found,
            @Doc("她一共背了几个。") int count,
            @Doc("正在看的这个叫什么名字。") Optional<String> name,
            @Doc("它背在哪。") Optional<String> where,
            @Doc("它的物品格总数。") int slots,
            @Doc("其中装了几格。") int used,
            @Doc("还剩几格空着。") int free,
            @Doc("它有几个升级位。") int upgrades,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 装了一样东西的一格。 */
    public record SlotLine(
            @Doc("它在背包里的格号。") int slot,
            @Doc("物品 id，比如 minecraft:iron_ingot。") String item,
            @Doc("它的中文名，比如 铁锭。") String name,
            @Doc("这一格里有几个。") int count) {
    }

    /** 背包里有什么。 */
    public record Contents(
            @Doc("逐格列出，只有装了东西的格子才在里面。") List<SlotLine> items,
            @Doc("装了东西的格子总数。") int filled,
            @Doc("这张表列了几行。") int shown,
            @Doc("装东西的格子超过一页，只列了前面这些。") boolean truncated,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 某样东西有多少。 */
    public record Count(
            @Doc("物品 id。") String id,
            @Doc("它的中文名。") String name,
            @Doc("背包里一共有几个。") int count,
            @Doc("背包里有没有这个东西。") boolean found,
            @Doc("它们分在几个格子里。") int slots,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 升级位里的一件升级。 */
    public record Upgrade(
            @Doc("它在升级位里的格号。") int slot,
            @Doc("物品 id。") String item,
            @Doc("它的中文名。") String name,
            @Doc("这一格里叠了几个。") int count) {
    }

    /** 背包上装了什么升级。 */
    public record Upgrades(
            @Doc("一共有几个升级位。") int slots,
            @Doc("装着的升级，逐格列出。") List<Upgrade> upgrades,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 一次取或放做了什么。 */
    public record Moved(
            @Doc("真的搬动了几件。") int moved,
            @Doc("这个背包里现在还剩几件那样东西。") int left,
            @Doc("为什么没搬完，一句中文；没出岔子就没有这个字段。") Optional<String> note,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 打开背包窗口的结果。 */
    public record Opened(
            @Doc("窗口是不是开着；用 numen.gui.view 看里面。") boolean opened,
            @Doc("窗口的名字。") String name,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 要动哪个背包。 */
    public record PickOpt(
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which) {
    }

    /** 问一样东西有多少。 */
    public record ByItem(
            @Doc("物品 id，比如 minecraft:bread。") String item,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which) {
    }

    /** 搬一样东西：多少件、哪个背包。 */
    public record MoveArg(
            @Doc("物品 id，比如 minecraft:bread。") String item,
            @Doc("搬几件；不写就是全部。") @Omitted("全部")
            @Positional Optional<Integer> count,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which) {
    }

    /** 不带参数：说的就是她背着的第一个背包。 */
    public record NoArgs() {
    }

    /** 身上的一个背包，和它所在的格子。 */
    record Carried(IBackpackWrapper wrapper, ItemStack stack, String handler, String identifier, int slot,
            int index) {
    }

    // ------------------------------------------------------------------ 找背包

    private static Optional<IBackpackWrapper> wrapperOf(ItemStack stack) {
        return ((ICapabilityProvider) (Object) stack)
                .getCapability(CapabilityBackpackWrapper.getCapabilityInstance()).resolve();
    }

    /** 她背着的全部背包，按模组自己的顺序（饰品栏 → 背包格 → 副手 → 护甲栏）。 */
    private static List<Carried> all(ServerPlayer player) {
        List<Carried> out = new ArrayList<>(2);
        PlayerInventoryProvider.get().runOnBackpacks(player, (backpack, handler, identifier, slot) -> {
            wrapperOf(backpack).ifPresent(wrapper ->
                    out.add(new Carried(wrapper, backpack, handler, identifier, slot, out.size() + 1)));
            return false; // 继续走，把后面的也收进来
        });
        return out;
    }

    private static Carried pick(ServerPlayer player, Optional<Integer> which) {
        List<Carried> list = all(player);
        if (list.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她身上没有精妙背包",
                    "把一个背包放进她的物品栏或者背在背上；sb.back.packs() 能看她在背什么。", null);
        }
        int want = which.map(n -> Math.max(1, n)).orElse(1);
        if (want > list.size()) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "她身上只有 " + list.size() + " 个精妙背包，没有第 " + want + " 个",
                    "which 从 1 数起；sb.back.packs() 把她的每个背包都列出来。", null);
        }
        return list.get(want - 1);
    }

    /**
     * 她身上有没有精妙背包（背着的、放在物品栏里的都算）。
     *
     * <p>给别的插件用的：搜刮任务要先知道「放背包」这一级到底存不存在，还是只能塞物品栏。
     * 不是 Lua 函数（没有 {@code @Fn}）。
     */
    public static boolean hasBackpack(ServerPlayer her) {
        return !all(her).isEmpty();
    }

    /**
     * 把一叠东西塞进她背着的精妙背包，返回塞不进去的剩余（塞完了就是空的）。
     *
     * <p>为什么从这一层进来、不走 {@code @Fn}：任务里没有 {@link ServerCall}，而
     * {@code sb.back.put} 是要 {@code ServerCall} 的。搜刮任务在物品栏满了以后要往背包里放东西，
     * 只能直接调这个方法。**不是 Lua 函数**（没有 {@code @Fn}，不进 API 面，也不改任何现有函数的签名）。
     *
     * <p>背包自己的过滤和升级照旧生效，所以它可能只收下一部分——剩下的原样返回，调用方自己决定继续找地方放。
     */
    public static ItemStack stash(ServerPlayer her, ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return stack;
        }
        if (stack.m_41720_() instanceof BackpackItem) {
            return stack; // 背包自己不往背包里塞
        }
        for (Carried c : all(her)) {
            stack = ItemHandlerHelper.insertItemStacked(c.wrapper().getInventoryForInputOutput(), stack, false);
            if (stack.m_41619_()) {
                return stack;
            }
        }
        return stack;
    }

    private static BackpackContext.Item contextOf(Carried c) {
        return new BackpackContext.Item(c.handler(), c.identifier(), c.slot());
    }

    private static String displayName(ServerPlayer player, Carried c) {
        String zh = label(c.stack());
        if (zh != null && !zh.isBlank()) {
            return zh;
        }
        try {
            return contextOf(c).getDisplayName(player).getString();
        } catch (Throwable t) {
            return "背包";
        }
    }

    /** main / offhand / armor / curio → 中文位置。 */
    private static String whereOf(String handler) {
        String h = handler == null ? "" : handler;
        if (h.startsWith("curio")) {
            return "饰品栏";
        }
        switch (h) {
            case "main":
                return "物品栏";
            case "offhand":
                return "副手里";
            case "armor":
                return "护甲栏";
            default:
                return h.isEmpty() ? "身上" : h;
        }
    }

    private static int usedOf(IItemHandler handler) {
        int used = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).m_41619_()) {
                used++;
            }
        }
        return used;
    }

    private static int countIn(IItemHandler handler, Item item) {
        int total = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.m_41619_() && stack.m_150930_(item)) {
                total += stack.m_41613_();
            }
        }
        return total;
    }

    private static int slotsHolding(IItemHandler handler, Item item) {
        int slots = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (!stack.m_41619_() && stack.m_150930_(item)) {
                slots++;
            }
        }
        return slots;
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return id == null ? "minecraft:air" : id.toString();
    }

    /**
     * 脚本写的物品 id 读成 Item。这个注册表对认不出的 id 会兜底给空气，所以先问在不在，
     * 不在就老实报「游戏里没有这个物品」，而不是让后面拿空气去算。
     */
    private static Item itemOf(String rawId) {
        String id = rawId == null ? "" : rawId.trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没写物品 id",
                    "写成物品的完整 id，比如 minecraft:iron_ingot。", null);
        }
        ResourceLocation key;
        try {
            key = id.indexOf(':') < 0 ? new ResourceLocation("minecraft", id) : new ResourceLocation(id);
        } catch (Throwable bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "物品 id 写得不对：" + id,
                    "域名和名字只能用字母数字和 _，中间一个冒号，比如 minecraft:iron_ingot。", null);
        }
        if (!ForgeRegistries.ITEMS.containsKey(key)) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "游戏里没有这个物品：" + id,
                    "写物品的完整 id，比如 minecraft:bread、sophisticatedbackpacks:pickup_upgrade；"
                            + "名字不确定就先看看她要找的是什么。", null);
        }
        return ForgeRegistries.ITEMS.getValue(key);
    }

    // ------------------------------------------------------------------ 函数

    @Fn("她背了几个精妙背包，每个叫什么、背在哪、装了几格。")
    @Example("local p = sb.back.packs()")
    @Example("for _, one in ipairs(sb.back.packs().packs) do print(one.index, one.name, one.used, one.slots) end")
    @Note("只读。其它函数里的 which 就是这里的 index，从 1 数起。")
    @SeeAlso({"sb.back.info", "sb.back.list", "sb.back.open"})
    public static Packs packs(ServerCall call, NoArgs args) {
        ServerPlayer her = call.her();
        List<Carried> list = all(her);
        List<Pack> out = new ArrayList<>(list.size());
        for (Carried c : list) {
            InventoryHandler handler = c.wrapper().getInventoryHandler();
            int used = usedOf(handler);
            String name = displayName(her, c);
            out.add(new Pack(c.index(), name, whereOf(c.handler()), handler.getSlots(), used,
                    "第 " + c.index() + " 个是" + name + "，在" + whereOf(c.handler()) + "，"
                            + handler.getSlots() + " 格里装了 " + used + " 格"));
        }
        String line = list.isEmpty()
                ? "她身上没有精妙背包"
                : "她身上有 " + list.size() + " 个精妙背包";
        return new Packs(list.size(), out, line);
    }

    @Fn("她背着的背包叫什么、背在哪、多满、有几个升级位。")
    @Example("local b = sb.back.info()")
    @Example("if b.found then print(b.name, b.used, b.free) end")
    @Note("只读，立刻就有。背包背在背上或者只是放在物品栏里都算。她身上有多个背包时用 which 点名。")
    @SeeAlso({"sb.back.packs", "sb.back.list", "sb.back.count", "sb.back.upgrades", "sb.back.take", "sb.back.put",
            "sb.back.open"})
    public static Info info(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        List<Carried> list = all(her);
        if (list.isEmpty()) {
            return new Info(false, 0, Optional.empty(), Optional.empty(), 0, 0, 0, 0, "她身上没有精妙背包");
        }
        Carried c = pick(her, args.which());
        InventoryHandler handler = c.wrapper().getInventoryHandler();
        int used = usedOf(handler);
        int free = handler.getSlots() - used;
        String name = displayName(her, c);
        int upgrades = upgradesOf(c).getSlots();
        String line = (list.size() > 1 ? "第 " + c.index() + " 个背包是" : "她背着") + name + "（在"
                + whereOf(c.handler()) + "），" + handler.getSlots() + " 格里装了 " + used + " 格、空了 " + free
                + " 格，有 " + upgrades + " 个升级位"
                + (list.size() > 1 ? "；她身上一共 " + list.size() + " 个背包" : "");
        return new Info(true, list.size(), Optional.of(name), Optional.of(whereOf(c.handler())),
                handler.getSlots(), used, free, upgrades, line);
    }

    @Fn("逐格列出背包里装着什么。")
    @Example("local c = sb.back.list()")
    @Example("for _, s in ipairs(sb.back.list().items) do print(s.slot, s.name, s.count) end")
    @Note("只读。只列装了东西的格子；装东西的格子超过 256 行时只列前 256 行，truncated 会说是真的截断了。")
    @SeeAlso({"sb.back.info", "sb.back.count", "sb.back.take", "sb.back.put"})
    public static Contents list(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        InventoryHandler handler = c.wrapper().getInventoryHandler();
        List<SlotLine> lines = new ArrayList<>();
        int filled = 0;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.m_41619_()) {
                continue;
            }
            filled++;
            if (lines.size() < MAX_LINES) {
                lines.add(new SlotLine(slot, idOf(stack), label(stack), stack.m_41613_()));
            }
        }
        boolean truncated = filled > lines.size();
        String line = displayName(her, c) + "：" + handler.getSlots() + " 格里装了 " + filled + " 格"
                + (truncated ? "，只列了前 " + lines.size() + " 行（其余没列）" : "");
        return new Contents(lines, filled, lines.size(), truncated, line);
    }

    @Fn("背包里一共有几个某种东西，分在几格里。")
    @Example("local n = sb.back.count(\"minecraft:bread\")")
    @Example("if sb.back.count(\"minecraft:torch\", {which = 2}).found then print(\"第二个背包里有火把\") end")
    @Note("只读。不用把整个背包列出来再自己数。")
    @SeeAlso({"sb.back.list", "sb.back.take", "sb.back.info"})
    public static Count count(ServerCall call, ByItem args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        InventoryHandler handler = c.wrapper().getInventoryHandler();
        Item item = itemOf(args.item());
        ItemStack proto = new ItemStack(item, 1);
        int total = countIn(handler, item);
        int slots = slotsHolding(handler, item);
        String name = label(proto);
        String line = total > 0
                ? displayName(her, c) + "里有 " + total + " 个" + name + "（分在 " + slots + " 格里）"
                : displayName(her, c) + "里没有" + name;
        return new Count(idOf(proto), name, total, total > 0, slots, line);
    }

    @Fn("背包上装着什么升级：拾取、磁铁、堆叠、合成……")
    @Example("local u = sb.back.upgrades()")
    @Example("for _, one in ipairs(sb.back.upgrades().upgrades) do print(one.slot, one.name) end")
    @Note("只读。它读的是升级位本身；升级自己的设置（磁铁范围、拾取过滤）要 sb.back.open 打开窗口才看得到。")
    @SeeAlso({"sb.back.info", "sb.back.open"})
    public static Upgrades upgrades(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = upgradesOf(c);
        List<Upgrade> out = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.m_41619_()) {
                continue;
            }
            out.add(new Upgrade(slot, idOf(stack), label(stack), stack.m_41613_()));
        }
        String name = displayName(her, c);
        String line = out.isEmpty()
                ? name + "的 " + handler.getSlots() + " 个升级位都空着"
                : name + "的 " + handler.getSlots() + " 个升级位里装了 " + out.size() + " 个升级";
        return new Upgrades(handler.getSlots(), out, line);
    }

    @Fn("把背包里的一种东西搬进她自己身上：全部，或者指定几件。")
    @Example("sb.back.take(\"minecraft:iron_ingot\", 16)")
    @Example("sb.back.take(\"minecraft:bread\")")
    @Example("sb.back.take(\"minecraft:torch\", 8, {which = 2}) -- 从第二个背包里拿")
    @Note("不用弹窗口，也不用摘背包。她身上放不下时会停下并在 note 里说；剩下的留在背包里。")
    @SeeAlso({"sb.back.put", "sb.back.count", "sb.back.list", "sb.back.open"})
    public static Moved take(ServerCall call, MoveArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        InventoryHandler handler = c.wrapper().getInventoryHandler();
        Item item = itemOf(args.item());
        String name = label(new ItemStack(item, 1));
        String bag = displayName(her, c);
        int before = countIn(handler, item);
        int want = args.count().map(n -> Math.max(0, n)).orElse(Integer.MAX_VALUE);
        if (want == 0) {
            return new Moved(0, before, Optional.of("要搬的件数是 0，什么都没动"), "件数是 0，" + bag + "里还是 "
                    + before + " 个" + name);
        }
        if (before == 0) {
            return new Moved(0, 0, Optional.of(bag + "里没有" + name), bag + "里没有" + name);
        }
        IItemHandler mine = new InvWrapper(her.m_150109_());
        int moved = 0;
        String note = null;
        for (int slot = 0; slot < handler.getSlots() && moved < want; slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.m_41619_() || !stack.m_150930_(item)) {
                continue;
            }
            ItemStack taken = handler.extractItem(slot, Math.min(stack.m_41613_(), want - moved), false);
            if (taken.m_41619_()) {
                continue;
            }
            ItemStack rest = ItemHandlerHelper.insertItemStacked(mine, taken, false);
            moved += taken.m_41613_() - rest.m_41613_();
            if (!rest.m_41619_()) {
                // 掏空的那一格是这些没塞进去的东西最安全的去处。
                handler.setStackInSlot(slot, rest);
                note = "她身上放不下了，没搬完的还在" + bag + "里";
                break;
            }
        }
        int left = countIn(handler, item);
        if (moved == 0 && note == null) {
            note = "一件都没能搬出来";
        }
        String line = "从" + bag + "里拿出 " + moved + " 个" + name
                + (left > 0 ? "，" + bag + "里还剩 " + left + " 个" : "，" + bag + "里已经没有了");
        return new Moved(moved, left, Optional.ofNullable(note), line);
    }

    @Fn("把她身上的一种东西放进背包：全部，或者指定几件。")
    @Example("sb.back.put(\"minecraft:cobblestone\", 64)")
    @Example("sb.back.put(\"minecraft:rotten_flesh\")")
    @Note("不用弹窗口，也不用摘背包。背包的过滤和格子规矩照旧生效，所以它可能只收下一部分，note 里会说。")
    @SeeAlso({"sb.back.take", "sb.back.count", "sb.back.list", "sb.back.open"})
    public static Moved put(ServerCall call, MoveArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        Item item = itemOf(args.item());
        String name = label(new ItemStack(item, 1));
        String bag = displayName(her, c);
        IItemHandler mine = new InvWrapper(her.m_150109_());
        IItemHandler into = c.wrapper().getInventoryForInputOutput();
        int have = countIn(mine, item);
        int want = args.count().map(n -> Math.max(0, n)).orElse(Integer.MAX_VALUE);
        if (want == 0) {
            return new Moved(0, countIn(c.wrapper().getInventoryHandler(), item),
                    Optional.of("要放的件数是 0，什么都没动"), "件数是 0，什么都没放进" + bag);
        }
        if (have == 0) {
            return new Moved(0, countIn(c.wrapper().getInventoryHandler(), item),
                    Optional.of("她身上没有" + name), "她身上没有" + name);
        }
        int moved = 0;
        String note = null;
        for (int slot = 0; slot < mine.getSlots() && moved < want; slot++) {
            ItemStack stack = mine.getStackInSlot(slot);
            if (stack.m_41619_() || !stack.m_150930_(item) || stack.m_41720_() instanceof BackpackItem) {
                continue;
            }
            if ("main".equals(c.handler()) && slot == c.slot()) {
                continue; // 背包自己那一格
            }
            ItemStack pulled = mine.extractItem(slot, Math.min(stack.m_41613_(), want - moved), false);
            if (pulled.m_41619_()) {
                continue;
            }
            ItemStack rest = ItemHandlerHelper.insertItemStacked(into, pulled, false);
            moved += pulled.m_41613_() - rest.m_41613_();
            if (!rest.m_41619_()) {
                ((IItemHandlerModifiable) mine).setStackInSlot(slot, rest);
                note = bag + "不肯收下剩下的（过滤，或者没有空格）";
                break;
            }
        }
        int left = countIn(c.wrapper().getInventoryHandler(), item);
        if (moved == 0 && note == null) {
            note = bag + "一件都没收下";
        }
        String line = "把 " + moved + " 个" + name + "放进" + bag
                + (left > 0 ? "，" + bag + "里现在有 " + left + " 个" : "");
        return new Moved(moved, left, Optional.ofNullable(note), line);
    }

    @Fn("打开背包真正的窗口，它的标签页和合成升级才好用；之后用 numen.gui 操作。")
    @Example("sb.back.open()")
    @Example("-- 然后：for _, s in ipairs(numen.gui.view().slots) do print(s.index, s.item) end")
    @Note("这就是玩家按 B 打开背包那一下。只是搬普通物品就用 sb.back.take / sb.back.put；"
            + "要合成、要翻升级自己的界面、要整理时才开窗口，看完用 numen.gui.close() 关上。")
    @SeeAlso({"numen.gui.view", "numen.gui.close", "sb.back.take", "sb.back.put", "sb.back.upgrades"})
    public static Opened open(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        BackpackContext.Item context = contextOf(c);
        String name = displayName(her, c);
        NetworkHooks.openScreen(her,
                new SimpleMenuProvider((windowId, inventory, player) -> new BackpackContainer(windowId, player,
                        context), Component.m_237113_(name)),
                buffer -> context.toBuffer(buffer));
        return new Opened(true, name, "打开了" + name + "的窗口");
    }

    // ------------------------------------------------------------------ 中文名

    /**
     * 翻译键 → 中文名，装着的模组翻译什么这里就有什么。一次服务器跑只读一遍：
     * {@code mods/*.jar!/assets/<ns>/lang/zh_cn.json} 全扫一遍。服务端自己的语言是英文，
     * 中文名只能从这里来。
     */
    /** 先碰一下物品栏，再取升级位。
     *  刚拿到手的背包还没有存储号（contentsUuid），这时 getUpgradeHandler()
     *  会给一个空壳（Noop，格数 0）；碰过物品栏存储就建出来了，升级位才有数。 */
    private static UpgradeHandler upgradesOf(Carried c) {
        c.wrapper().getInventoryHandler();
        return c.wrapper().getUpgradeHandler();
    }

    /** 中文名；这个整合包没有它的翻译时退回服务端给的英文名，再不行退回 id。 */
    private static String label(ItemStack stack) {
        return Zh.name(stack);
    }

    /** 去掉语言文件里的占位符与颜色码。 */
    private static String clean(String text) {
        return Zh.clean(text);
    }

    // ------------------------------------------------------------------ 升级位：装、卸、过滤表

    /** 升级位上那一件升级；空格子返回 null。 */
    private static ItemStack upgradeAt(UpgradeHandler handler, int slot) {
        if (slot < 0 || slot >= handler.getSlots()) {
            return null;
        }
        ItemStack stack = handler.getStackInSlot(slot);
        return stack.m_41619_() ? null : stack;
    }

    /**
     * 这个升级位的过滤表；没有过滤表（比如堆叠升级）就返回 null。
     *
     * <p>拾取、磁铁、虚空、喂食这些升级都是 {@code IFilteredUpgrade}，它们的过滤表自带存盘
     * （写入过滤格会顺手把 NBT 写回升级物品），所以这里拿到的就是活的表。
     */
    private static FilterLogic filterOf(UpgradeHandler handler, int slot) {
        if (upgradeAt(handler, slot) == null) {
            return null;
        }
        try {
            Map<Integer, IUpgradeWrapper> wrappers = handler.getSlotWrappers();
            IUpgradeWrapper wrapper = wrappers == null ? null : wrappers.get(slot);
            if (wrapper instanceof IFilteredUpgrade filtered) {
                return filtered.getFilterLogic();
            }
        } catch (Throwable ignored) {
            // 包装器还没建起来，就当这个格子没有过滤表。
        }
        return null;
    }

    /** 点名的升级位，或者第一个带过滤表的升级。 */
    private static int filterSlot(Carried c, Optional<Integer> asked) {
        UpgradeHandler handler = upgradesOf(c);
        int slots = handler.getSlots();
        String bag = "背包";
        if (asked.isPresent()) {
            int slot = asked.get();
            if (slot < 0 || slot >= slots) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "升级位只有 " + slots + " 个，没有第 " + slot + " 个",
                        "格号从 0 数起；sb.back.upgrades() 列出每个升级所在的格号。", null);
            }
            if (filterOf(handler, slot) == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "第 " + slot + " 个升级位上没有能设过滤的升级",
                        "只有拾取、磁铁、虚空、喂食这类带过滤表的升级才有白黑名单；sb.back.filter() 看哪些有。", null);
            }
            return slot;
        }
        for (int slot = 0; slot < slots; slot++) {
            if (filterOf(handler, slot) != null) {
                return slot;
            }
        }
        throw new ApiError(ErrorKind.NOT_FOUND, bag + "上没有能设过滤的升级",
                "先装一个拾取升级或磁铁升级：sb.back.upgrade_put(\"sophisticatedbackpacks:pickup_upgrade\")。", null);
    }

    private static boolean truthy(String raw) {
        String v = raw == null ? "" : raw.trim().toLowerCase();
        return v.equals("true") || v.equals("on") || v.equals("yes") || v.equals("1") || v.equals("开")
                || v.equals("是") || v.equals("匹配");
    }

    /** 从 TagKey 的 toString（{@code TagKey[minecraft:item / minecraft:logs]}）里取出名字。 */
    private static String tagName(Object key) {
        String text = String.valueOf(key);
        int slash = text.lastIndexOf('/');
        if (slash >= 0) {
            text = text.substring(slash + 1);
        }
        text = text.replace("]", "").trim();
        return text.endsWith(" ") ? text.trim() : text;
    }

    private static double dist2(BlockPos a, BlockPos b) {
        double dx = a.m_123341_() - b.m_123341_();
        double dy = a.m_123342_() - b.m_123342_();
        double dz = a.m_123343_() - b.m_123343_();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String coord(BlockPos pos) {
        return pos.m_123341_() + ", " + pos.m_123342_() + ", " + pos.m_123343_();
    }

    @Fn("把她身上的一块升级装进背包的升级位。")
    @Example("sb.back.upgrade_put(\"sophisticatedbackpacks:pickup_upgrade\")")
    @Example("sb.back.upgrade_put(\"sophisticatedbackpacks:magnet_upgrade\", {which = 2})")
    @Note("升级得先在她身上（物品栏里）。装进第一个空着、又肯收它的升级位；装完才从她身上扣。")
    @SeeAlso({"sb.back.upgrades", "sb.back.upgrade_take", "sb.back.filter"})
    public static Moved upgrade_put(ServerCall call, UpArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        Item item = itemOf(args.item());
        String name = label(new ItemStack(item, 1));
        String bag = displayName(her, c);
        UpgradeHandler handler = upgradesOf(c);
        Inventory mine = her.m_150109_();
        int from = -1;
        for (int i = 0; i < mine.m_6643_(); i++) {
            ItemStack stack = mine.m_8020_(i);
            if (!stack.m_41619_() && stack.m_150930_(item) && !(stack.m_41720_() instanceof BackpackItem)) {
                from = i;
                break;
            }
        }
        if (from < 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她身上没有" + name,
                    "先把升级放进她的物品栏，再 sb.back.upgrade_put 装上去。", null);
        }
        ItemStack one = mine.m_8020_(from).m_41777_();
        one.m_41764_(1);
        int target = -1;
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            if (!handler.getStackInSlot(slot).m_41619_()) {
                continue;
            }
            if (!handler.isItemValid(slot, one)) {
                continue;
            }
            target = slot;
            break;
        }
        if (target < 0) {
            throw new ApiError(ErrorKind.FAILED, bag + "的升级位满了，或者不收" + name,
                    "先把用不上的升级拿下来：sb.back.upgrade_take(0)。", null);
        }
        ItemStack rest = handler.insertItem(target, one, false);
        if (!rest.m_41619_()) {
            throw new ApiError(ErrorKind.FAILED, bag + "把" + name + "退回来了", "这个升级位不收它。", null);
        }
        ItemStack inHand = mine.m_8020_(from);
        int left = inHand.m_41613_() - 1;
        if (left <= 0) {
            mine.m_6836_(from, ItemStack.f_41583_);
        } else {
            ItemStack shrunk = inHand.m_41777_();
            shrunk.m_41764_(left);
            mine.m_6836_(from, shrunk);
        }
        return new Moved(1, countIn(handler, item), Optional.empty(),
                "把" + name + "装进了" + bag + "的第 " + target + " 个升级位");
    }

    @Fn("把背包升级位上的一块升级拿下来，放进她身上。")
    @Example("sb.back.upgrade_take(0)")
    @Example("sb.back.upgrade_take(1, {which = 2})")
    @Note("格号从 0 数起，sb.back.upgrades() 里每个升级的 slot 就是它。她身上放不下时会把升级还回原格。")
    @SeeAlso({"sb.back.upgrades", "sb.back.upgrade_put"})
    public static Moved upgrade_take(ServerCall call, SlotArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = upgradesOf(c);
        String bag = displayName(her, c);
        int slot = args.slot();
        ItemStack up = handler.extractItem(slot, 1, false);
        if (up.m_41619_()) {
            throw new ApiError(ErrorKind.NOT_FOUND, bag + "的第 " + slot + " 个升级位是空的",
                    "sb.back.upgrades() 列出装着升级的格号。", null);
        }
        // Inventory.add 会把这个栈清空（合并进已有的堆时），所以名字和原件都得先留一份。
        ItemStack keep = up.m_41777_();
        String name = label(keep);
        if (!her.m_150109_().m_36054_(up)) {
            handler.insertItem(slot, keep, false);
            throw new ApiError(ErrorKind.FAILED, "她身上放不下" + name,
                    "腾出一格再来；升级还留在原来的升级位里。", null);
        }
        return new Moved(1, 0, Optional.empty(),
                "把" + name + "从" + bag + "的第 " + slot + " 个升级位拿下来，放进她身上了");
    }

    @Fn("看背包上每个带过滤表的升级：是白名单还是黑名单、过滤了哪些东西、匹配什么。")
    @Example("for _, f in ipairs(sb.back.filter().filters) do print(f.slot, f.name, f.mode) end")
    @Example("local fs = sb.back.filter({which = 2}).filters")
    @Note("只读。拾取、磁铁、虚空、喂食这些升级的白黑名单都在这里；堆叠升级这类没有过滤表的不会出现。")
    @SeeAlso({"sb.back.upgrades", "sb.back.filter_add", "sb.back.filter_set"})
    public static Filters filter(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = upgradesOf(c);
        String bag = displayName(her, c);
        List<Filter> out = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            FilterLogic logic = filterOf(handler, slot);
            if (logic == null) {
                continue;
            }
            ItemStack up = upgradeAt(handler, slot);
            FilterItemStackHandler table = logic.getFilterHandler();
            List<FilterItem> items = new ArrayList<>();
            for (int i = 0; i < table.getSlots(); i++) {
                ItemStack stack = table.getStackInSlot(i);
                if (stack.m_41619_()) {
                    continue;
                }
                items.add(new FilterItem(i, idOf(stack), label(stack)));
            }
            List<String> tags = new ArrayList<>();
            try {
                for (TagKey<Item> key : logic.getTagKeys()) {
                    tags.add(tagName(key));
                }
            } catch (Throwable ignored) {
                // 老版本没有标签列表，空着就好。
            }
            boolean allow = logic.isAllowList();
            String primary = "item";
            try {
                PrimaryMatch match = logic.getPrimaryMatch();
                primary = match == null ? "item" : match.m_7912_();
            } catch (Throwable ignored) {
                // 没有主匹配就用 item。
            }
            String name = up == null ? "升级" : label(up);
            String line = bag + "的第 " + slot + " 个升级位（" + name + "）是"
                    + (allow ? "白名单：只收表里的东西" : "黑名单：只挡表里的东西")
                    + "，表里 " + items.size() + " 项东西"
                    + (tags.isEmpty() ? "" : "、" + tags.size() + " 个标签");
            out.add(new Filter(slot, up == null ? "minecraft:air" : idOf(up), name, allow ? "allow" : "deny",
                    table.getSlots(), items, tags, primary, logic.shouldMatchNbt(), logic.shouldMatchDurability(),
                    line));
        }
        String line = out.isEmpty()
                ? bag + "上没有能设过滤的升级"
                : bag + "上有 " + out.size() + " 个带过滤表的升级";
        return new Filters(out, line);
    }

    @Fn("把一样东西加进升级的过滤表（白名单就是「收它」，黑名单就是「挡它」）。")
    @Example("sb.back.filter_add(\"minecraft:rotten_flesh\")")
    @Example("sb.back.filter_add(\"minecraft:diamond\", {slot = 1})")
    @Note("只加一项，加到第一个空格子里；表满了会说。改白/黑用 sb.back.filter_set。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_remove", "sb.back.filter_set"})
    public static Moved filter_add(ServerCall call, FilterArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = upgradesOf(c);
        int slot = filterSlot(c, args.slot());
        FilterLogic logic = filterOf(handler, slot);
        FilterItemStackHandler table = logic.getFilterHandler();
        Item item = itemOf(args.item());
        ItemStack one = new ItemStack(item, 1);
        String name = label(one);
        for (int i = 0; i < table.getSlots(); i++) {
            ItemStack have = table.getStackInSlot(i);
            if (!have.m_41619_() && have.m_150930_(item)) {
                return new Moved(0, 0, Optional.of(name + "已经在过滤表里了"),
                        "过滤表里已经有" + name + "了，什么都没改");
            }
        }
        int free = -1;
        for (int i = 0; i < table.getSlots(); i++) {
            if (table.getStackInSlot(i).m_41619_()) {
                free = i;
                break;
            }
        }
        if (free < 0) {
            throw new ApiError(ErrorKind.FAILED, "过滤表的 " + table.getSlots() + " 格都满了",
                    "先用 sb.back.filter_remove(\"物品id\") 拿掉几项。", null);
        }
        // 过滤表自己的 insertItem 是空实现（只 return EMPTY），必须直接写格子。
        if (!table.isItemValid(free, one)) {
            throw new ApiError(ErrorKind.FAILED, "过滤表的这一格不收" + name, "试试别的格子或者别的物品。", null);
        }
        table.setStackInSlot(free, one);
        return new Moved(1, 0, Optional.empty(),
                "把" + name + "加进了第 " + slot + " 个升级的过滤表（第 " + free + " 格）");
    }

    @Fn("把一样东西从升级的过滤表里拿掉。")
    @Example("sb.back.filter_remove(\"minecraft:rotten_flesh\")")
    @Note("表里没有它就说没找到，什么都不改。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_add", "sb.back.filter_clear"})
    public static Moved filter_remove(ServerCall call, FilterArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = upgradesOf(c);
        int slot = filterSlot(c, args.slot());
        FilterLogic logic = filterOf(handler, slot);
        FilterItemStackHandler table = logic.getFilterHandler();
        Item item = itemOf(args.item());
        String name = label(new ItemStack(item, 1));
        int cleared = 0;
        for (int i = 0; i < table.getSlots(); i++) {
            ItemStack have = table.getStackInSlot(i);
            if (!have.m_41619_() && have.m_150930_(item)) {
                table.setStackInSlot(i, ItemStack.f_41583_);
                cleared++;
            }
        }
        if (cleared == 0) {
            return new Moved(0, 0, Optional.of(name + "不在过滤表里"), "过滤表里没有" + name + "，什么都没改");
        }
        return new Moved(cleared, 0, Optional.empty(),
                "把" + name + "从第 " + slot + " 个升级的过滤表里拿掉了");
    }

    @Fn("把升级的过滤表整个清空。")
    @Example("sb.back.filter_clear(0)")
    @Note("清空之后白名单会变成「什么都收」，黑名单会变成「什么都不挡」。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_add"})
    public static Moved filter_clear(ServerCall call, SlotArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = upgradesOf(c);
        int slot = filterSlot(c, Optional.of(args.slot()));
        FilterLogic logic = filterOf(handler, slot);
        FilterItemStackHandler table = logic.getFilterHandler();
        int cleared = 0;
        for (int i = 0; i < table.getSlots(); i++) {
            if (!table.getStackInSlot(i).m_41619_()) {
                table.setStackInSlot(i, ItemStack.f_41583_);
                cleared++;
            }
        }
        return new Moved(cleared, 0, Optional.empty(),
                "把第 " + slot + " 个升级的过滤表清空了（拿掉 " + cleared + " 项）");
    }

    @Fn("改升级的过滤设置：白名单还是黑名单、匹配什么、要不要看 NBT 和耐久。")
    @Example("sb.back.filter_set(\"mode\", \"allow\")")
    @Example("sb.back.filter_set(\"primary\", \"tags\")")
    @Example("sb.back.filter_set(\"nbt\", \"true\", {slot = 1})")
    @Note("key 只能是 mode（allow / deny）、primary（item / mod / tags）、nbt、durability、any_tag（true / false）。")
    @SeeAlso({"sb.back.filter", "sb.back.filter_add", "sb.back.filter_clear"})
    public static Moved filter_set(ServerCall call, SetArg args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        UpgradeHandler handler = upgradesOf(c);
        int slot = filterSlot(c, args.slot());
        FilterLogic logic = filterOf(handler, slot);
        String key = args.key() == null ? "" : args.key().trim().toLowerCase();
        String value = args.value() == null ? "" : args.value().trim();
        String what;
        switch (key) {
            case "mode":
            case "allow":
            case "list": {
                String v = value.toLowerCase();
                boolean allow;
                if (v.startsWith("allow") || v.startsWith("white") || v.equals("白") || v.equals("白名单")
                        || v.equals("收")) {
                    allow = true;
                } else if (v.startsWith("deny") || v.startsWith("block") || v.equals("黑") || v.equals("黑名单")
                        || v.equals("挡")) {
                    allow = false;
                } else {
                    throw new ApiError(ErrorKind.BAD_ARGUMENT, "mode 只认 allow 或 deny，写的是：" + value,
                            "allow = 白名单（只收表里的），deny = 黑名单（只挡表里的）。", null);
                }
                logic.setAllowList(allow);
                what = allow ? "白名单：只收表里的东西" : "黑名单：只挡表里的东西";
                break;
            }
            case "primary":
            case "match": {
                PrimaryMatch match = PrimaryMatch.fromName(value.toLowerCase());
                if (match == null) {
                    throw new ApiError(ErrorKind.BAD_ARGUMENT, "primary 只认 item、mod、tags，写的是：" + value,
                            "item 按单个物品比，mod 按模组比，tags 按标签比。", null);
                }
                logic.setPrimaryMatch(match);
                what = "按 " + match.m_7912_() + " 匹配";
                break;
            }
            case "nbt": {
                boolean on = truthy(value);
                logic.setMatchNbt(on);
                what = on ? "匹配时也看 NBT" : "匹配时不看 NBT";
                break;
            }
            case "durability": {
                boolean on = truthy(value);
                logic.setMatchDurability(on);
                what = on ? "匹配时也看耐久" : "匹配时不看耐久";
                break;
            }
            case "any_tag":
            case "tag": {
                boolean on = truthy(value);
                logic.setMatchAnyTag(on);
                what = on ? "任意一个标签对上就算" : "标签要全部对上";
                break;
            }
            default:
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "不认识的设置：" + key,
                        "key 只能是 mode、primary、nbt、durability、any_tag。", null);
        }
        return new Moved(1, 0, Optional.empty(),
                "第 " + slot + " 个升级的过滤设置改成：" + what);
    }

    @Fn("把背着的背包放到地上（变成方块），她身上会少一个背包。")
    @Example("sb.back.place()")
    @Example("sb.back.place({which = 2})")
    @Note("放在她面前那格的地上；面前没地方会说到处都不行。放下去之后背包里的东西原样在里面。")
    @SeeAlso({"sb.back.pickup", "sb.back.info"})
    public static Moved place(ServerCall call, PickOpt args) {
        ServerPlayer her = call.her();
        Carried c = pick(her, args.which());
        if (!(c.stack().m_41720_() instanceof BackpackItem backpack)) {
            throw new ApiError(ErrorKind.FAILED, "这一格拿的不是背包物品", "sb.back.packs() 看她在背什么。", null);
        }
        String bag = displayName(her, c);
        ServerLevel level = (ServerLevel) her.m_9236_();
        Direction facing = her.m_6350_();
        int dx = facing == Direction.EAST ? 1 : facing == Direction.WEST ? -1 : 0;
        int dz = facing == Direction.SOUTH ? 1 : facing == Direction.NORTH ? -1 : 0;
        if (dx == 0 && dz == 0) {
            dz = 1;
        }
        BlockPos at = her.m_20183_();
        int x = at.m_123341_();
        int y = at.m_123342_();
        int z = at.m_123343_();
        BlockPos spot = null;
        for (int step = 1; step <= 2 && spot == null; step++) {
            for (int up = 0; up <= 1 && spot == null; up++) {
                BlockPos floor = new BlockPos(x + dx * step, y + up - 1, z + dz * step);
                BlockPos target = new BlockPos(x + dx * step, y + up, z + dz * step);
                if (level.m_46749_(target) && level.m_8055_(target).m_60795_()
                        && !level.m_8055_(floor).m_60795_()) {
                    spot = target;
                }
            }
        }
        if (spot == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她面前没有能放背包的空地",
                    "往前走一步找块平地再放；或者先用 sb.back.open 打开窗口。", null);
        }
        BlockPos floor = new BlockPos(spot.m_123341_(), spot.m_123342_() - 1, spot.m_123343_());
        BlockHitResult hit = new BlockHitResult(
                new Vec3(spot.m_123341_() + 0.5, spot.m_123342_(), spot.m_123343_() + 0.5),
                Direction.UP, floor, false);
        BlockPlaceContext ctx = new BlockPlaceContext(her, InteractionHand.MAIN_HAND, c.stack(), hit);
        // 背包方块只有水平朝向，朝向 = 玩家朝向的反方向（和玩家拿在手上右键一致）。
        InteractionResult result = backpack.tryPlace(her, facing.m_122424_(), ctx);
        if (result != InteractionResult.SUCCESS) {
            throw new ApiError(ErrorKind.FAILED, "这个位置放不下" + bag,
                    "换个地方试，或者先清掉那格的东西。", null);
        }
        return new Moved(1, 0, Optional.empty(), "把" + bag + "放在了 " + coord(spot));
    }

    @Fn("把地上的背包捡回她身上。")
    @Example("sb.back.pickup()")
    @Example("sb.back.pickup({radius = 12})")
    @Note("这就是玩家蹲下右键那一下。它装了限制管理员拿取的无限升级时会拒绝。")
    @SeeAlso({"sb.back.place", "sb.back.packs"})
    public static Moved pickup(ServerCall call, RadiusOpt args) {
        ServerPlayer her = call.her();
        ServerLevel level = (ServerLevel) her.m_9236_();
        int radius = Math.min(16, Math.max(1, args.radius().orElse(6)));
        BlockPos at = her.m_20183_();
        int x = at.m_123341_();
        int y = at.m_123342_();
        int z = at.m_123343_();
        BlockPos found = null;
        BackpackBlockEntity target = null;
        double best = Double.MAX_VALUE;
        int cx0 = (x - radius) >> 4;
        int cx1 = (x + radius) >> 4;
        int cz0 = (z - radius) >> 4;
        int cz1 = (z + radius) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.m_46749_(new BlockPos(cx << 4, y, cz << 4))) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                    if (!(entry.getValue() instanceof BackpackBlockEntity candidate)) {
                        continue;
                    }
                    double d = dist2(entry.getKey(), at);
                    if (d > (double) radius * radius || d >= best) {
                        continue;
                    }
                    best = d;
                    found = entry.getKey();
                    target = candidate;
                }
            }
        }
        if (found == null || target == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她 " + radius + " 格内没有地上的背包",
                    "走过去再捡，或者把 radius 调大一点（最多 16）。", null);
        }
        if (!mayPickup(her, found)) {
            throw new ApiError(ErrorKind.DENIED, "这个地上的背包不让她捡",
                    "它装了限制管理员拿取的无限升级，只有它的主人能捡。", null);
        }
        ItemStack back;
        try {
            back = target.getBackpackWrapper().getBackpack().m_41777_();
        } catch (Throwable bad) {
            throw new ApiError(ErrorKind.FAILED, "这个方块里读不出背包", "别动它，先把它周围清理干净再试。", null);
        }
        if (back.m_41619_()) {
            throw new ApiError(ErrorKind.FAILED, "这个方块里没有背包", "那可能是个空壳方块。", null);
        }
        // Inventory.add 会把传入的栈清空（合并进已有的堆时），所以名字得先留一份。
        String name = label(back);
        if (!her.m_150109_().m_36054_(back)) {
            throw new ApiError(ErrorKind.FAILED, "她身上放不下这个背包", "腾出一格再来捡；背包还在地上。", null);
        }
        level.m_7471_(found, false);
        return new Moved(1, 0, Optional.empty(),
                "把地上的" + name + "捡回了身上（原来在 " + coord(found) + "）");
    }

    /**
     * 模组自己的「能不能捡」判断：装了限制管理员的无限升级就只有主人能捡。
     * 这个方法是 private static，所以反射调；拿不到就当能捡（和模组默认一致）。
     */
    private static boolean mayPickup(ServerPlayer player, BlockPos pos) {
        try {
            Method method = Class.forName("net.p3pp3rf1y.sophisticatedbackpacks.backpack.BackpackBlock")
                    .getDeclaredMethod("hasPermissionsToPickup", Player.class, BlockPos.class);
            method.setAccessible(true);
            Object answer = method.invoke(null, player, pos);
            return !(answer instanceof Boolean allowed) || allowed;
        } catch (Throwable ignored) {
            return true;
        }
    }

    // ------------------------------------------------------------------ 新形状

    /** 装一块升级。 */
    public record UpArg(
            @Doc("升级的物品 id，比如 sophisticatedbackpacks:pickup_upgrade。") String item,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which) {
    }

    /** 点一个升级位。 */
    public record SlotArg(
            @Doc("升级位的格号，从 0 数起；sb.back.upgrades() 里每个升级的 slot 就是它。") int slot,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which) {
    }

    /** 往过滤表里加一样东西。 */
    public record FilterArg(
            @Doc("要加进过滤表的物品 id。") String item,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which,
            @Doc("升级位的格号；不写就是第一个带过滤表的升级。") @Omitted("第一个带过滤表的升级")
            Optional<Integer> slot) {
    }

    /** 改升级的过滤设置。 */
    public record SetArg(
            @Doc("改哪一项：mode、primary、nbt、durability、any_tag。") String key,
            @Doc("改成什么。") String value,
            @Doc("第几个背包，从 1 数起；不写就是第一个。") @Omitted("1")
            Optional<Integer> which,
            @Doc("升级位的格号；不写就是第一个带过滤表的升级。") @Omitted("第一个带过滤表的升级")
            Optional<Integer> slot) {
    }

    /** 捡地上的背包时扫多大范围。 */
    public record RadiusOpt(
            @Doc("以她为中心扫几格；不写就是 6。") @Omitted("6")
            Optional<Integer> radius) {
    }

    /** 过滤表里的一项。 */
    public record FilterItem(
            @Doc("过滤表里的格号。") int slot,
            @Doc("物品 id。") String item,
            @Doc("它的中文名。") String name) {
    }

    /** 一个带过滤表的升级。 */
    public record Filter(
            @Doc("它在升级位里的格号。") int slot,
            @Doc("升级的物品 id。") String item,
            @Doc("升级的中文名。") String name,
            @Doc("allow = 白名单（只收表里的），deny = 黑名单（只挡表里的）。") String mode,
            @Doc("过滤表一共有几格。") int slots,
            @Doc("表里过滤了哪些东西。") List<FilterItem> items,
            @Doc("表里的标签，比如 minecraft:logs。") List<String> tags,
            @Doc("主匹配方式：item、mod 或 tags。") String primary,
            @Doc("比的时候要不要看 NBT。") boolean match_nbt,
            @Doc("比的时候要不要看耐久。") boolean match_durability,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

    /** 背包上所有带过滤表的升级。 */
    public record Filters(
            @Doc("逐个列出带过滤表的升级。") List<Filter> filters,
            @Doc("一句话，可以直接念给主人听。") String line) {
    }

}
