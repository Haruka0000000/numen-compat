package dev.local.numenrs;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.ServerCall;

import com.refinedmods.refinedstorage.api.IRSAPI;
import com.refinedmods.refinedstorage.api.autocrafting.ICraftingManager;
import com.refinedmods.refinedstorage.api.autocrafting.preview.ICraftingPreviewElement;
import com.refinedmods.refinedstorage.api.autocrafting.task.CalculationResultType;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICalculationResult;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingRequestInfo;
import com.refinedmods.refinedstorage.api.autocrafting.task.ICraftingTask;
import com.refinedmods.refinedstorage.RS;
import com.refinedmods.refinedstorage.api.network.INetwork;
import com.refinedmods.refinedstorage.api.network.IWirelessTransmitter;
import com.refinedmods.refinedstorage.api.network.NetworkType;
import com.refinedmods.refinedstorage.api.network.node.INetworkNode;
import com.refinedmods.refinedstorage.api.network.INetworkNodeGraphEntry;
import com.refinedmods.refinedstorage.item.NetworkItem;
import com.refinedmods.refinedstorage.api.network.security.ISecurityManager;
import com.refinedmods.refinedstorage.api.network.security.Permission;
import com.refinedmods.refinedstorage.api.storage.IStorage;
import com.refinedmods.refinedstorage.api.storage.disk.IStorageDisk;
import com.refinedmods.refinedstorage.api.util.IStackList;
import com.refinedmods.refinedstorage.api.util.StackListEntry;
import com.refinedmods.refinedstorage.apiimpl.API;
import com.refinedmods.refinedstorage.apiimpl.autocrafting.preview.ItemCraftingPreviewElement;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import java.util.function.Predicate;

import com.refinedmods.refinedstorage.RSItems;
import com.refinedmods.refinedstorage.api.network.grid.GridType;
import com.refinedmods.refinedstorage.apiimpl.network.node.GridNetworkNode;
import com.refinedmods.refinedstorage.blockentity.FluidInterfaceBlockEntity;
import com.refinedmods.refinedstorage.blockentity.InterfaceBlockEntity;
import com.refinedmods.refinedstorage.blockentity.grid.GridBlockEntity;

import net.minecraft.core.Direction;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.item.crafting.BlastingRecipe;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.item.crafting.SmokingRecipe;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.IItemHandlerModifiable;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code rs}: read and work a Refined Storage network.
 *
 * <p>A network is a public shelf with a door on it: what a working companion needs is
 * "is there a controller near me, does it have power, is there 64 iron in it, is something
 * still being auto-crafted — and can I take what the owner asked for".
 *
 * <p>Two rules this class never breaks:
 * <ul>
 *   <li><b>Nothing moves without the owner's nod.</b> Every call that changes the stock
 *       ({@link #take}, {@link #store}, {@link #craft}, {@link #cancel}) goes through
 *       {@link ServerCall#authorize} — the same popup that guards breaking blocks.</li>
 *   <li><b>A network with a security card is not hers.</b> RS networks carry an
 *       {@code ISecurityManager}; a carded network only answers its owner, and if that owner
 *       is offline this class refuses outright instead of guessing. Reads included.</li>
 * </ul>
 *
 * <p>Networks come from {@code IRSAPI#getNetworkManager(ServerLevel)}. That manager keeps a
 * network registered while its chunks are unloaded, where every node reads as "stopped, no
 * controller" — so the list is filtered to chunks that are actually loaded before anything is
 * said about a network's health.
 *
 * <p>Chinese item names are not invented here: the server's own language is English, so names
 * are read out of the installed mods' {@code assets/<ns>/lang/zh_cn.json} once per server run
 * (cached; ~40k keys in this pack) and matched on the name. Without a hit the English name is
 * shown.
 *
 * <p>The "is this network carded" probe reads two private fields of
 * {@code apiimpl.network.security.SecurityManager} ({@code globalCard}, {@code cards}) — RS
 * exposes no public "is locked" method. Reverse-engineered from refinedstorage-1.12.4.jar.
 */
public final class RsApi {

    private static final int MAX_NETWORKS = 16;
    private static final int MAX_HITS = 20;
    private static final int HERE_RADIUS = 96;
    /** One call may move at most this many items: a full inventory is not one order. */
    private static final int MAX_COUNT = 1024;
    private static final long STATE_CACHE_MS = 20_000L;
    private static final int STATE_MIN_PERCENT = 5;
    private static final int MAX_STATE_BODIES = 32;
    /** How close a machine has to be for her to work it: an arm's length, not a walk. */
    private static final double BLOCK_REACH = 6.0;
    /** Fluids are counted in mB; one call may move this much. */
    private static final int MAX_FLUID = 64_000;

    // The four terminals she can work, plus the crafting monitor. A wired terminal has to be
    // within {@link #BLOCK_REACH}; a wireless one only has to be in her bag and in range of a
    // wireless transmitter that is wired to the same network — that is exactly the rule RS
    // applies to a real player (apiimpl.network.item.NetworkItemManager#open).
    private static final int T_ITEMS = 0;
    private static final int T_FLUID = 1;
    private static final int T_CRAFT = 2;
    private static final int T_PATTERN = 3;
    private static final int T_MONITOR = 4;

    private static final String WIRELESS_ITEMS = "refinedstorage:wireless_grid";
    private static final String WIRELESS_FLUID = "refinedstorage:wireless_fluid_grid";
    private static final String WIRELESS_CRAFT = "refinedstorageaddons:wireless_crafting_grid";
    private static final String WIRELESS_MONITOR = "refinedstorage:wireless_crafting_monitor";

    private static final Map<UUID, Snapshot> STATE = new ConcurrentHashMap<>();

    private RsApi() {
    }

    static void install(NumenApi numen) {
        numen.api("rs", "存储网络：附近有没有 RS 控制器、里面有什么、还剩多少电、在自动合什么，"
                + "以及从里面取东西、把东西存进去、下合成单。", RsApi.class);
        numen.contributeBodyState(RsApi::stateLine);
    }

    // ------------------------------------------------------------------ body state

    private record Snapshot(long stamp, String text) {
    }

    /**
     * What {@code numen.contributeBodyState} appends: one warning while the nearest network is
     * nearly out of power. Cached per body ({@link #STATE}) — the state block is assembled far
     * more often than a battery drains, and two companions must not read each other's cache.
     */
    static String stateLine(NumenPlayer her) {
        if (her == null) {
            return "";
        }
        try {
            UUID key = her.m_20148_();
            long now = System.currentTimeMillis();
            Snapshot cached = STATE.get(key);
            if (cached != null && now - cached.stamp() < STATE_CACHE_MS) {
                return cached.text();
            }
            String text = powerLine(her);
            if (STATE.size() > MAX_STATE_BODIES) {
                STATE.clear();
            }
            STATE.put(key, new Snapshot(now, text));
            return text;
        } catch (Throwable t) {
            return "";
        }
    }

    private static String powerLine(NumenPlayer her) {
        if (!(her.m_9236_() instanceof ServerLevel level)) {
            return "";
        }
        INetwork net = nearestTo(level, her);
        if (net == null) {
            return "";
        }
        long energy;
        long max;
        try {
            IEnergyStorage storage = net.getEnergyStorage();
            if (storage == null) {
                return "";
            }
            energy = storage.getEnergyStored();
            max = storage.getMaxEnergyStored();
        } catch (Throwable t) {
            return "";
        }
        if (max <= 0 || energy * 100L >= max * STATE_MIN_PERCENT) {
            return "";
        }
        int tasks = 0;
        try {
            ICraftingManager manager = net.getCraftingManager();
            tasks = manager == null ? 0 : manager.getTasks().size();
        } catch (Throwable ignored) {
            tasks = 0;
        }
        return "<rs>警告：最近的精炼存储网络（" + coord(net.getPosition()) + "）只剩 "
                + (energy * 100L / max) + "% 电量"
                + (tasks > 0 ? "，" + tasks + " 个自动合成任务正要卡住" : "")
                + "。要它干活先让它通电，别对着没电的机器空等。</rs>\n";
    }

    // ------------------------------------------------------------------ networks

    @Fn("把这一层世界里已经加载的 Refined Storage 网络列出来：在哪、离她多远、通没通电、装了多少东西。")
    @Example("rs.rs.networks()")
    @Note("只列区块加载着的网络——远处的网络在区块卸载后读不出真实状态，列出来只会误导。"
            + "最多 16 条，按距离排序。locked 为 true 表示这个网络挂了安全卡，她动不了。")
    public static Networks networks(ServerCall call) {
        ServerLevel level = level(call);
        List<INetwork> nets = all(level);
        List<Net> out = new ArrayList<>();
        int shown = 0;
        for (INetwork net : nets) {
            if (shown >= MAX_NETWORKS) {
                break;
            }
            Net one = describe(call, level, net);
            if (one != null) {
                out.add(one);
                shown++;
            }
        }
        out.sort((a, b) -> Long.compare(a.distance(), b.distance()));
        String line;
        if (out.isEmpty()) {
            line = "这一层世界里没有加载着的 RS 网络";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("附近有 ").append(out.size()).append(" 个 RS 网络：");
            for (int i = 0; i < out.size(); i++) {
                sb.append(i == 0 ? "\n" : "\n").append(out.get(i).line());
            }
            line = sb.toString();
        }
        return new Networks(out, line);
    }

    @Fn("最近的那个网络（或者用 net 点名的那个）现在什么状态。")
    @Example("rs.rs.here()")
    @Example("rs.rs.here({net = \"438, 77, -1804\"})")
    @Note("不写 net 就是最近那个，超过 96 格算够不着。想看远处的网络先用 networks() 拿到坐标。")
    public static Net here(ServerCall call, NetOpt args) {
        INetwork net = pick(call, args == null ? null : args.net().orElse(null));
        guard(call, net, Permission.BUILD);
        Net one = describe(call, level(call), net);
        if (one == null) {
            throw new ApiError(ErrorKind.FAILED, "这个网络读不出来", "换个网络试试，或者过一会儿再看。");
        }
        return one;
    }

    // ------------------------------------------------------------------ count / find

    @Fn("网络里有多少某个东西：物品或流体都行。")
    @Example("rs.rs.count(\"minecraft:iron_ingot\")")
    @Example("rs.rs.count(\"minecraft:water\", {net = \"438, 77, -1804\"})")
    @Note("先按物品找，找不到再按流体找；id 写成 minecraft:iron_ingot 这样。")
    public static Count count(ServerCall call, CountOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说数什么", "写成 rs.rs.count(\"minecraft:iron_ingot\")。");
        }
        INetwork net = pick(call, args == null ? null : args.net().orElse(null));
        guard(call, net, Permission.BUILD);
        ServerLevel level = level(call);
        String where = coord(net.getPosition());

        Item item = itemFor(id);
        if (item != null) {
            ItemStack proto = new ItemStack(item);
            int n = countOf(net, proto);
            String name = label(proto);
            return new Count(id, name, n, "item", n > 0, where,
                    n > 0 ? "网络里有 " + n + " 个 " + name + "（" + id + "）"
                            : "网络里没有 " + name + "（" + id + "）");
        }
        net.minecraft.world.level.material.Fluid fluid = fluidFor(id);
        if (fluid != null) {
            FluidStack proto = new FluidStack(fluid, 1);
            int n = countFluidOf(net, proto);
            String name = label(fluid);
            return new Count(id, name, n, "fluid", n > 0, where,
                    n > 0 ? "网络里有 " + n + " mB 的 " + name + "（" + id + "）"
                            : "网络里没有 " + name + "（" + id + "）");
        }
        throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品也没有这个流体：" + id,
                "id 写成 minecraft:iron_ingot / minecraft:water 这样：命名空间:名字。");
    }

    @Fn("在网络里按名字找东西——中文名、英文名、id 片段都认。")
    @Example("rs.rs.find(\"铁\")")
    @Example("rs.rs.find(\"ingot\", {limit = 10})")
    @Example("rs.rs.find(\"water\", {kind = \"fluid\"})")
    @Note("kind 写 item 或 fluid 只找一种，不写两种都找。中文名：原版的那份随插件打包，模组的从 mods 目录下每个 jar 的"
            + " zh_cn 语言文件里现读；没写中文的老模组只有英文名和 id 能搜到。")
    public static Found find(ServerCall call, QueryOpt args) {
        String query = args == null || args.query() == null ? "" : args.query().trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说搜什么",
                    "写成 rs.rs.find(\"铁\")，中文名和 id 片段都认。");
        }
        int limit = args == null ? 10 : clamp(args.limit().orElse(10), 1, MAX_HITS);
        String kind = args == null ? "" : args.kind().orElse("").trim().toLowerCase(Locale.ROOT);
        INetwork net = pick(call, args == null ? null : args.net().orElse(null));
        guard(call, net, Permission.BUILD);

        List<Hit> hits = new ArrayList<>();
        if (!kind.equals("fluid")) {
            for (Map.Entry<ItemStack, Integer> e : itemEntries(net)) {
                ItemStack stack = e.getKey();
                int n = e.getValue();
                if (!matches(stack, query)) {
                    continue;
                }
                hits.add(new Hit(idOf(stack), label(stack), n, "item"));
            }
        }
        if (!kind.equals("item")) {
            for (Map.Entry<FluidStack, Integer> e : fluidEntries(net)) {
                FluidStack stack = e.getKey();
                int n = e.getValue();
                if (!matches(stack, query)) {
                    continue;
                }
                hits.add(new Hit(fluidIdOf(stack), label(stack.getFluid()), n, "fluid"));
            }
        }
        hits.sort((a, b) -> Integer.compare(b.count(), a.count()));
        List<Hit> out = new ArrayList<>();
        for (Hit hit : hits) {
            if (out.size() >= limit) {
                break;
            }
            out.add(hit);
        }
        String line;
        if (out.isEmpty()) {
            line = "网络里没有名字带「" + query + "」的东西";
        } else {
            StringBuilder sb = new StringBuilder("找到 ").append(hits.size()).append(" 种");
            if (hits.size() > out.size()) {
                sb.append("（只列前 ").append(out.size()).append(" 种）");
            }
            for (Hit hit : out) {
                sb.append("\n").append(hit.name()).append("（").append(hit.id()).append("）：")
                        .append(hit.count()).append(hit.kind().equals("fluid") ? " mB" : " 个");
            }
            line = sb.toString();
        }
        return new Found(out, hits.size(), line);
    }

    // ------------------------------------------------------------------ crafting

    @Fn("这个网络正在自动合成什么：几单、每单要什么、到百分之几。")
    @Example("rs.rs.crafting()")
    @Note("每条带一个任务编号，取消时用它。想在下单前先看缺什么料，用 plan()。")
    public static Crafts crafting(ServerCall call, NetOpt args) {
        INetwork net = pick(call, args == null ? null : args.net().orElse(null));
        guard(call, net, Permission.BUILD);
        ICraftingManager manager = net.getCraftingManager();
        List<Craft> out = new ArrayList<>();
        if (manager != null) {
            for (ICraftingTask task : manager.getTasks()) {
                if (task == null || out.size() >= MAX_HITS) {
                    continue;
                }
                String what = "";
                String name = "";
                try {
                    ICraftingRequestInfo info = task.getRequested();
                    if (info != null && info.getItem() != null && !info.getItem().m_41619_()) {
                        what = idOf(info.getItem());
                        name = label(info.getItem());
                    } else if (info != null && info.getFluid() != null && !info.getFluid().isEmpty()) {
                        what = fluidIdOf(info.getFluid());
                        name = label(info.getFluid().getFluid());
                    }
                } catch (Throwable ignored) {
                    what = "";
                }
                int quantity = 1;
                int percent = 0;
                try {
                    quantity = task.getQuantity();
                    percent = task.getCompletionPercentage();
                } catch (Throwable ignored) {
                    percent = 0;
                }
                String id = "";
                try {
                    UUID uuid = task.getId();
                    id = uuid == null ? "" : uuid.toString();
                } catch (Throwable ignored) {
                    id = "";
                }
                String line = "在合 " + quantity + " 个 " + name + "（" + what + "），到 " + percent + "%";
                out.add(new Craft(id, what, name, quantity, percent, coord(net.getPosition()), line));
            }
        }
        String line;
        if (out.isEmpty()) {
            line = "这个网络没有在自动合成的东西";
        } else {
            StringBuilder sb = new StringBuilder("这个网络在合 ").append(out.size()).append(" 单：");
            for (Craft one : out) {
                sb.append("\n").append(one.line()).append("，任务编号 ").append(one.id());
            }
            line = sb.toString();
        }
        return new Crafts(out, line);
    }

    @Fn("下单前先算一遍：合这个要什么料、哪些料缺。")
    @Example("rs.rs.plan(\"minecraft:iron_block\", {count = 4})")
    @Note("缺料清单来自 RS 自己的合成计划（preview 元素里标了「缺」的那些）；"
            + "craftable 为 false 时别下单，先把料补上。")
    public static Plan plan(ServerCall call, PlanOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说算哪个东西",
                    "写成 rs.rs.plan(\"minecraft:iron_block\", {count = 4})。");
        }
        int count = clamp(args == null ? 1 : args.count().orElse(1), 1, MAX_COUNT);
        INetwork net = pick(call, args == null ? null : args.net().orElse(null));
        guard(call, net, Permission.AUTOCRAFTING);
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 minecraft:iron_block 这样；流体这版还不能下单。");
        }
        return planOf(net, new ItemStack(item), count);
    }

    @Fn("下一单自动合成：先问主人，点了头才真的开始。")
    @Example("rs.rs.craft(\"minecraft:iron_block\", {count = 4})")
    @Note("会用网络里现有的料和样板；下不了单会明确说为什么（没样板、缺料、样板成环、太复杂）。")
    public static Pending<Plan> craft(ServerCall call, PlanOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说合什么",
                    "写成 rs.rs.craft(\"minecraft:iron_block\", {count = 4})。");
        }
        int count = clamp(args == null ? 1 : args.count().orElse(1), 1, MAX_COUNT);
        ServerLevel level = level(call);
        Access via = access(call, T_CRAFT);
        INetwork net = via.net();
        guard(call, net, Permission.AUTOCRAFTING);
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 minecraft:iron_block 这样；流体这版还不能下单。");
        }
        ItemStack proto = new ItemStack(item);
        Plan preview = planOf(net, proto, count);
        if (!preview.craftable()) {
            ErrorKind kind = switch (preview.type()) {
                case "no_pattern" -> ErrorKind.NO_PATH;
                case "too_complex", "recursive" -> ErrorKind.LIMIT;
                default -> ErrorKind.NO_MATERIAL;
            };
            String hint = switch (preview.type()) {
                case "no_pattern" -> "先在合成界面里做一个样板、放进样板供应器，或者用 plan() 确认要哪种样板。";
                case "too_complex" -> "这单的链条太长/太绕，RS 自己放弃了；拆成几步下。";
                case "recursive" -> "样板之间成了环，RS 不肯算；把环上的一环去掉再试。";
                default -> "先用 rs.rs.count() 看缺的料有多少，补上再下单；或者 plan() 先算一遍。";
            };
            return Pending.failed(new ApiError(kind, preview.line(), hint));
        }
        BlockPos pos = via.pos() != null ? via.pos() : call.her().m_20183_();
        return call.authorize(Action.take(pos, level.m_8055_(pos), item)).then(ignored -> {
            ICraftingManager manager = net.getCraftingManager();
            if (manager == null) {
                throw new ApiError(ErrorKind.FAILED, "这个网络没有合成管理器", "接上合成监视器/样板再试。");
            }
            ICraftingTask task = manager.request(null, proto, count);
            if (task == null) {
                throw new ApiError(ErrorKind.FAILED, "单子没下成",
                        "RS 拒了这次请求：可能刚好被限流，或者样板被抽走。再试一次看看。");
            }
            pay(via, T_CRAFT);
            return new Plan(preview.what(), preview.name(), count, true, "ok",
                    preview.need(), preview.missing(),
                    "通过" + via.via() + "下了一单：" + count + " 个 " + preview.name() + "（"
                            + preview.what() + "），任务编号 " + task.getId());
        });
    }

    @Fn("取消一单自动合成。")
    @Example("rs.rs.cancel(\"6f7c2a8e-1f0e-4f4e-9d33-0b1a2c3d4e5f\")")
    @Note("编号从 crafting() 拿，是那串带横线的 36 位。取消会问主人。")
    public static Pending<Move> cancel(ServerCall call, CancelOpt args) {
        String task = args == null || args.task() == null ? "" : args.task().trim();
        if (task.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没给任务编号",
                    "先 rs.rs.crafting() 拿编号，再 rs.rs.cancel({task = \"那个编号\"})。");
        }
        UUID uuid;
        try {
            uuid = UUID.fromString(task);
        } catch (IllegalArgumentException e) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "任务编号读不懂：" + task,
                    "编号是 crafting() 里那串带横线的 36 位，例如 6f7c2a8e-1f0e-4f4e-9d33-0b1a2c3d4e5f。");
        }
        ServerLevel level = level(call);
        Access via = access(call, T_CRAFT);
        INetwork net = via.net();
        guard(call, net, Permission.AUTOCRAFTING);
        ICraftingManager manager = net.getCraftingManager();
        ICraftingTask found = null;
        if (manager != null) {
            try {
                found = manager.getTask(uuid);
            } catch (Throwable ignored) {
                found = null;
            }
        }
        if (found == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这个网络里没有编号 " + task + " 的合成任务",
                    "先 rs.rs.crafting() 看现在有哪几单。");
        }
        BlockPos pos = via.pos() != null ? via.pos() : call.her().m_20183_();
        String what = "";
        try {
            ICraftingRequestInfo info = found.getRequested();
            if (info != null && info.getItem() != null) {
                what = label(info.getItem());
            }
        } catch (Throwable ignored) {
            what = "";
        }
        String name = what;
        return call.authorize(Action.take(pos, level.m_8055_(pos), null)).then(ignored -> {
            manager.cancel(uuid);
            pay(via, T_CRAFT);
            return new Move("", name, 1, 1, coord(pos), "取消了合成 " + name + " 的那一单");
        });
    }

    // ------------------------------------------------------------------ terminals
    //
    // She has no mouse, so every RS gesture here is the one a player makes with a terminal:
    // items in and out through the item grid, fluids through the fluid grid, recipes from the
    // crafting grid, and patterns written at the pattern grid. Whatever she is not standing at,
    // she can reach with the matching wireless terminal — but only if she really carries it,
    // it is bound to a network, a wireless transmitter reaches her, and the terminal has charge.

    @Fn("在她身边的合成终端上手动合一样东西：从网络里扣料，把成品交给她。")
    @Example("rs.rs.craft_grid(\"minecraft:torch\", {count = 16})")
    @Note("只做工作台配方（合成终端里本来也烧不了东西）；count 是要合几份。"
            + "材料按配方从网络里扣，缺料会说是缺哪样。会先问主人，一次最多 " + MAX_COUNT + " 份。")
    public static Pending<Move> craft_grid(ServerCall call, MoveOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说合什么",
                    "写成 rs.rs.craft_grid(\"minecraft:torch\", {count = 16})。");
        }
        int batches = clamp(args == null ? 1 : args.count().orElse(1), 1, MAX_COUNT);
        ServerLevel level = level(call);
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 minecraft:torch 这样。");
        }
        Access via = access(call, T_CRAFT);
        INetwork net = via.net();
        guard(call, net, Permission.EXTRACT);
        ItemStack proto = new ItemStack(item);
        String name = label(proto);
        NumenPlayer her = call.her();
        Inventory bag = her.m_150109_();
        RecipeManager manager = level.m_7654_().m_129894_();
        RegistryAccess access = level.m_9598_();
        CraftingRecipe recipe = null;
        for (CraftingRecipe one : manager.m_44013_(RecipeType.f_44107_)) {
            if (produces(one, access, item)) {
                recipe = one;
                break;
            }
        }
        if (recipe == null) {
            throw new ApiError(ErrorKind.NO_PATH, "工作台配方里没有 " + name,
                    "合成终端只摆工作台配方；烧炼那种要用 rs.rs.pattern() 写样板，再交给自动合成。");
        }
        ItemStack out;
        try {
            out = recipe.m_8043_(access);
        } catch (Throwable broken) {
            out = null;
        }
        int per = out == null || out.m_41619_() ? 1 : out.m_41613_();
        Map<Item, Integer> need = new LinkedHashMap<>();
        List<Ingredient> slots;
        try {
            slots = recipe.m_7527_();
        } catch (Throwable broken) {
            slots = List.of();
        }
        for (Ingredient slot : slots) {
            Item one = pick(slot);
            if (one != null) {
                need.merge(one, batches, Integer::sum);
            }
        }
        String missing = "";
        for (Map.Entry<Item, Integer> want : need.entrySet()) {
            ItemStack look = new ItemStack(want.getKey(), 1);
            int have = countOf(net, look);
            if (have < want.getValue()) {
                missing += (missing.isEmpty() ? "" : "、") + label(look) + " 差 "
                        + (want.getValue() - have) + " 个";
            }
        }
        if (!missing.isEmpty()) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "网络里的料不够：" + missing,
                    "先用 rs.rs.count() 看看料有多少，补上料再合；或者用 rs.rs.craft() 让它自己合成原料。");
        }
        int total = per * batches;
        BlockPos anchor = via.pos() != null ? via.pos() : her.m_20183_();
        return call.authorize(Action.take(anchor, level.m_8055_(anchor), item)).then(ignored -> {
            List<ItemStack> taken = new ArrayList<>();
            for (Map.Entry<Item, Integer> want : need.entrySet()) {
                ItemStack got = net.extractItem(new ItemStack(want.getKey(), 1), want.getValue(),
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                int have = got == null ? 0 : got.m_41613_();
                if (have < want.getValue()) {
                    if (have > 0) {
                        taken.add(got);
                    }
                    for (ItemStack back : taken) {
                        net.insertItem(back, back.m_41613_(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    }
                    throw new ApiError(ErrorKind.NO_MATERIAL,
                            "拿料的时候少了 " + label(new ItemStack(want.getKey(), 1)),
                            "刚才被别的机器抽走了；再试一次。");
                }
                taken.add(got);
            }
            int left = total;
            while (left > 0) {
                int chunk = Math.min(left, 64);
                if (!bag.m_36054_(new ItemStack(item, chunk))) {
                    for (ItemStack back : taken) {
                        net.insertItem(back, back.m_41613_(),
                                com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    }
                    throw new ApiError(ErrorKind.FAILED, "背包塞不下 " + total + " 个 " + name,
                            "料已经放回网络了；先腾出背包空格再合。");
                }
                left -= chunk;
            }
            pay(via, T_CRAFT);
            String line = "在" + via.via() + "上合了 " + batches + " 份 " + name + "（" + id + "），共 "
                    + total + " 个进了背包";
            return new Move(id, name, batches, batches, coord(net.getPosition()), line);
        });
    }


    @Fn("把背包里的东西递进精致存储——身边 6 格内的物品终端，或者她背包里的无线物品终端。")
    @Example("rs.rs.store(\"minecraft:cobblestone\", {count = 64})")
    @Note("先找物品终端，没有再找无线物品终端；两样都不在时退回 6 格内的「接口」方块。"
            + "会先问主人。一次最多 " + MAX_COUNT + " 个。")
    public static Pending<Move> store(ServerCall call, MoveOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说存什么",
                    "写成 rs.rs.store(\"minecraft:cobblestone\", {count = 64})。");
        }
        int want = clamp(args == null ? 1 : args.count().orElse(1), 1, MAX_COUNT);
        ServerLevel level = level(call);
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 minecraft:cobblestone 这样。");
        }
        ItemStack proto = new ItemStack(item);
        String name = label(proto);
        NumenPlayer her = call.her();
        Inventory bag = her.m_150109_();
        int carried = countIn(bag, item);
        if (carried <= 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "身上没有 " + name + "（" + id + "）",
                    "先看看背包里到底有什么；或者是 id 写错了。");
        }
        Access via = wired(call, T_ITEMS);
        String why = null;
        if (via == null) {
            try {
                via = wireless(call, T_ITEMS);
            } catch (ApiError e) {
                via = null;
                why = e.getMessage();
            }
        }
        guard(call, via == null ? pick(call, args == null ? null : args.net().orElse(null)) : via.net(),
                Permission.INSERT);
        int n = Math.min(carried, want);
        BlockPos iface = via != null ? null
                : nearestBlock(level, her, be -> be instanceof InterfaceBlockEntity, BLOCK_REACH);
        if (via == null && iface == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "身边没有能收东西的终端，也没有接口",
                    "三条路选一条：在离她 6 格以内放一台物品终端；把无线物品终端放进她背包"
                            + "（先绑定、网络要有无线发射器、终端要有电）；或者放一个「接口」方块。"
                            + (why == null ? "" : "无线那台试过了：" + why));
        }
        Access through = via;
        BlockPos pos = iface;
        return call.authorize(Action.drop(item)).then(ignored -> {
            int moving = n;
            int moved = 0;
            if (through != null) {
                INetwork net = through.net();
                for (int i = 0; i < bag.m_6643_() && moving > 0; i++) {
                    ItemStack stack = bag.m_8020_(i);
                    if (stack.m_41619_() || stack.m_41720_() != item) {
                        continue;
                    }
                    int give = Math.min(stack.m_41613_(), moving);
                    ItemStack left = net.insertItem(new ItemStack(item, give), give,
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    int stuck = left == null || left.m_41619_() ? 0 : left.m_41613_();
                    int gone = give - stuck;
                    if (gone > 0) {
                        bag.m_6836_(i, new ItemStack(item, Math.max(0, stack.m_41613_() - gone)));
                        moved += gone;
                        moving -= gone;
                    }
                }
                if (moved <= 0) {
                    throw new ApiError(ErrorKind.FAILED, "网络没收下 " + name,
                            "磁盘可能满了，或者这台终端没有存入权限；先 rs.rs.networks() 看看还剩多少容量。");
                }
                pay(through, T_ITEMS);
                String line = "通过" + through.via() + "把背包里的 " + moved + " 个 " + name
                        + "（" + id + "）递进了网络" + (moved < want ? "，只要了这么多" : "");
                return new Move(id, name, want, moved, coord(through.net().getPosition()), line);
            }
            BlockEntity block = level.m_7702_(pos);
            IItemHandler handler = block == null ? null : itemHandler(block);
            if (handler == null) {
                throw new ApiError(ErrorKind.FAILED, "接口没接上",
                        "方块可能刚被挖掉，或者这块区块没加载；再试一次。");
            }
            for (int i = 0; i < bag.m_6643_() && moving > 0; i++) {
                ItemStack stack = bag.m_8020_(i);
                if (stack.m_41619_() || stack.m_41720_() != item) {
                    continue;
                }
                int give = Math.min(stack.m_41613_(), moving);
                int left = insertInto(handler, item, give);
                int gone = give - left;
                if (gone > 0) {
                    bag.m_6836_(i, new ItemStack(item, Math.max(0, stack.m_41613_() - gone)));
                    moved += gone;
                    moving -= gone;
                }
            }
            if (moved <= 0) {
                throw new ApiError(ErrorKind.FAILED, "接口不收 " + name,
                        "接口的输入格可能满了；等一下再试，或者先把接口里的东西清掉。");
            }
            String line = "把背包里的 " + moved + " 个 " + name + "（" + id + "）递进了接口"
                    + (moved < want ? "，只要了这么多" : "");
            return new Move(id, name, want, moved, coord(pos), line);
        });
    }

    @Fn("从网络里取出东西，放进她的背包——用物品终端，或者她背包里的无线物品终端。")
    @Example("rs.rs.take(\"minecraft:iron_ingot\", {count = 16})")
    @Note("要站在物品终端 6 格以内，或者带着无线物品终端（已绑定、网络有无线发射器、终端有电）。"
            + "会先问主人。一次最多 " + MAX_COUNT + " 个。")
    public static Pending<Move> take(ServerCall call, MoveOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说取什么",
                    "写成 rs.rs.take(\"minecraft:iron_ingot\", {count = 16})。");
        }
        int want = clamp(args == null ? 1 : args.count().orElse(1), 1, MAX_COUNT);
        ServerLevel level = level(call);
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 minecraft:iron_ingot 这样；取流体用 rs.rs.bottle。");
        }
        Access via = access(call, T_ITEMS);
        INetwork net = via.net();
        guard(call, net, Permission.EXTRACT);
        ItemStack proto = new ItemStack(item);
        String name = label(proto);
        NumenPlayer her = call.her();
        int have = countOf(net, proto);
        if (have <= 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "网络里没有 " + name + "（" + id + "）",
                    "先 rs.rs.find() 看看网络里都有什么。");
        }
        int n = Math.min(have, want);
        String where = coord(net.getPosition());
        BlockPos anchor = via.pos() != null ? via.pos() : her.m_20183_();
        BlockState state = level.m_8055_(anchor);
        return call.authorize(Action.take(anchor, state, item)).then(ignored -> {
            ItemStack taken = net.extractItem(proto, n, com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            int got = taken == null ? 0 : taken.m_41613_();
            if (got <= 0) {
                throw new ApiError(ErrorKind.FAILED, "网络没给出来 " + name,
                        "可能刚好被别的机器拿走了；再试一次。");
            }
            Inventory bag = her.m_150109_();
            if (!bag.m_36054_(taken)) {
                net.insertItem(taken, got, com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                throw new ApiError(ErrorKind.FAILED, "背包塞不下 " + name + "，东西已经放回网络",
                        "先腾出背包空格再取。");
            }
            pay(via, T_ITEMS);
            String line = "通过" + via.via() + "从网络里取了 " + got + " 个 " + name + "（" + id + "）放进背包";
            return new Move(id, name, want, got, where, line);
        });
    }

    // ------------------------------------------------------------------ fluids in and out

    @Fn("把背包里装着流体的容器（水桶、岩浆桶…）倒进精致存储——用流体终端，流体以流体的形式进网络。")
    @Example("rs.rs.pour({id = \"minecraft:water\"})")
    @Note("先找流体终端（有线优先，没有再找无线流体终端）；两样都不在时退回 6 格内的「流体接口」。"
            + "id 不写就把包里所有带流体的容器都倒空；倒完的空桶还给她。会先问主人。")
    public static Pending<Move> pour(ServerCall call, PourOpt args) {
        ServerLevel level = level(call);
        String id = args == null || args.id() == null || args.id().isEmpty() ? "" : args.id().get().trim();
        Fluid only = null;
        if (!id.isEmpty()) {
            only = fluidFor(id);
            if (only == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "没有这个流体：" + id,
                        "流体 id 写成 minecraft:water 这样；不写就是把包里所有流体都倒进去。");
            }
        }
        NumenPlayer her = call.her();
        Inventory bag = her.m_150109_();
        Access via = wired(call, T_FLUID);
        String why = null;
        if (via == null) {
            try {
                via = wireless(call, T_FLUID);
            } catch (ApiError e) {
                via = null;
                why = e.getMessage();
            }
        }
        guard(call, via == null ? pick(call, args == null ? null : args.net().orElse(null)) : via.net(),
                Permission.INSERT);
        Fluid want = only;
        Item sample = firstFluidContainer(bag, want);
        if (sample == null) {
            String what = want == null ? "能倒的容器" : "装着 " + label(want) + " 的容器";
            throw new ApiError(ErrorKind.NO_MATERIAL, "身上没有" + what,
                    "带几个装着流体的桶过来；已经空了的容器没什么可倒。");
        }
        BlockPos iface = via == null
                ? nearestBlock(level, her, be -> be instanceof FluidInterfaceBlockEntity, BLOCK_REACH)
                : null;
        if (via == null && iface == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "身边没有流体终端，也没有流体接口",
                    "三条路选一条：在离她 6 格以内放一台流体终端；把无线流体终端放进她背包"
                            + "（先绑定、网络要有无线发射器、终端要有电）；或者放一个「流体接口」方块。"
                            + (why == null ? "" : "无线那台试过了：" + why));
        }
        Access through = via;
        BlockPos pos = iface;
        com.dwinovo.numen.permission.Action deed = through != null
                ? Action.drop(sample)
                : Action.useBlock(pos, level.m_8055_(pos));
        return call.authorize(deed).then(ignored -> {
            Inventory pack = her.m_150109_();
            int moved = 0;
            String name = "";
            Fluid seen = null;
            IFluidHandler tank = null;
            if (through == null) {
                BlockEntity block = level.m_7702_(pos);
                tank = block == null ? null : fluidHandler(block);
                if (tank == null) {
                    throw new ApiError(ErrorKind.FAILED, "流体接口没接上",
                            "方块可能刚被挖掉，或者这块区块没加载；再试一次。");
                }
            }
            INetwork net = through == null ? null : through.net();
            for (int i = 0; i < pack.m_6643_(); i++) {
                ItemStack stack = pack.m_8020_(i);
                if (stack.m_41619_()) {
                    continue;
                }
                ItemStack one = stack.m_41777_();
                one.m_41764_(1);
                IFluidHandlerItem probe = ((ICapabilityProvider) (Object) one)
                        .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
                if (probe == null) {
                    continue;
                }
                FluidStack sim = probe.drain(Integer.MAX_VALUE, IFluidHandler.FluidAction.SIMULATE);
                if (sim.isEmpty() || (want != null && sim.getFluid() != want)) {
                    continue;
                }
                int use = splitOne(pack, i);
                if (use < 0) {
                    continue;
                }
                ItemStack single = pack.m_8020_(use);
                IFluidHandlerItem item = ((ICapabilityProvider) (Object) single)
                        .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
                if (item == null) {
                    continue;
                }
                int accepted;
                if (net != null) {
                    FluidStack refused = net.insertFluid(sim, sim.getAmount(),
                            com.refinedmods.refinedstorage.api.util.Action.SIMULATE);
                    int stuck = refused == null || refused.isEmpty() ? 0 : refused.getAmount();
                    accepted = sim.getAmount() - stuck;
                } else {
                    accepted = tank.fill(sim, IFluidHandler.FluidAction.SIMULATE);
                }
                if (accepted <= 0) {
                    continue;
                }
                FluidStack real = item.drain(accepted, IFluidHandler.FluidAction.EXECUTE);
                int filled;
                if (net != null) {
                    FluidStack refused = net.insertFluid(real, real.getAmount(),
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                    int stuck = refused == null || refused.isEmpty() ? 0 : refused.getAmount();
                    filled = real.getAmount() - stuck;
                } else {
                    filled = tank.fill(real, IFluidHandler.FluidAction.EXECUTE);
                }
                if (filled <= 0) {
                    continue;
                }
                pack.m_6836_(use, item.getContainer());
                moved += filled;
                if (name.isEmpty()) {
                    name = label(real.getFluid());
                }
                seen = real.getFluid();
            }
            if (moved <= 0) {
                throw new ApiError(ErrorKind.FAILED, "一点也没倒进去",
                        "网络可能没有流体存储（接一台流体磁盘机），或者接口的罐子满了。");
            }
            String line;
            if (through != null) {
                pay(through, T_FLUID);
                int inNetwork = countFluidOf(through.net(), new FluidStack(seen == null ? want : seen, 1));
                line = "通过" + through.via() + "把 " + moved + " mB 的 " + name + " 倒进了网络"
                        + (inNetwork > 0 ? "（网络里现在有 " + inNetwork + " mB）"
                                : "；网络里没有流体存储，接一台流体磁盘机它才会留下");
            } else {
                int inNetwork = countFluidOf(pick(call, args == null ? null : args.net().orElse(null)),
                        new FluidStack(seen == null ? want : seen, 1));
                line = "把 " + moved + " mB 的 " + name + " 从容器倒进了流体接口"
                        + (inNetwork > 0 ? "，进了网络（网络里现在有 " + inNetwork + " mB）"
                                : "；它留在接口的罐子里（网络里没有流体存储，接一台流体磁盘机它就会流进网络）");
            }
            String where = coord(through != null ? through.net().getPosition() : pos);
            Show.swing(her);
            Show.sound(her, "minecraft:item.bucket.empty", 1.0f, 1.0f);
            return new Move(id, name, moved, moved, where, line);
        });
    }

    @Fn("从网络里取流体，装进她背包里的空容器（空桶、空瓶之类）——用流体终端，或者无线流体终端。")
    @Example("rs.rs.bottle(\"minecraft:water\", {count = 1000})")
    @Note("先找流体终端，没有再找无线流体终端；两样都不在时退回 6 格内流体接口的罐子。"
            + "容器不够就装现有的那些。会先问主人。数量按 mB 算（一桶是 1000）。")
    public static Pending<Move> bottle(ServerCall call, BottleOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说取哪个流体",
                    "写成 rs.rs.bottle(\"minecraft:water\", {count = 1000})。");
        }
        int want = clamp(args == null ? 1000 : args.count().orElse(1000), 1, MAX_FLUID);
        ServerLevel level = level(call);
        Fluid fluid = fluidFor(id);
        if (fluid == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个流体：" + id,
                    "流体 id 写成 minecraft:water 这样。");
        }
        String name = label(fluid);
        NumenPlayer her = call.her();
        Access via = wired(call, T_FLUID);
        String why = null;
        if (via == null) {
            try {
                via = wireless(call, T_FLUID);
            } catch (ApiError e) {
                via = null;
                why = e.getMessage();
            }
        }
        INetwork net = via == null ? pick(call, args == null ? null : args.net().orElse(null)) : via.net();
        guard(call, net, Permission.EXTRACT);
        BlockPos pos = via == null
                ? nearestBlock(level, her, be -> be instanceof FluidInterfaceBlockEntity, BLOCK_REACH)
                : null;
        if (via == null && pos == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "身边没有流体终端，也没有流体接口",
                    "三条路选一条：在离她 6 格以内放一台流体终端；把无线流体终端放进她背包"
                            + "（先绑定、网络要有无线发射器、终端要有电）；或者放一个「流体接口」方块。"
                            + (why == null ? "" : "无线那台试过了：" + why));
        }
        BlockEntity tankBlock = pos == null ? null : level.m_7702_(pos);
        IFluidHandler inTank = tankBlock == null ? null : fluidInNode(tankBlock);
        IFluidHandler capTank = tankBlock == null ? null : fluidHandler(tankBlock);
        int stored = Math.max(amountOf(inTank, fluid), amountOf(capTank, fluid));
        int have = Math.max(stored, countFluidOf(net, new FluidStack(fluid, 1)));
        if (have <= 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "网络和流体终端里都没有 " + name + "（" + id + "）",
                    "先用 rs.rs.find(\"" + name + "\", {kind = \"fluid\"}) 看看有什么流体。");
        }
        int floor = fillFloor(her.m_150109_(), fluid, want);
        if (floor > have) {
            throw new ApiError(ErrorKind.NO_MATERIAL,
                    "只有 " + have + " mB 的 " + name + "，装不满一件容器（这件容器一次至少要 " + floor + " mB）",
                    "多攒一点再装，或者把 count 写大一些（一桶是 1000 mB）。");
        }
        int n = Math.min(have, Math.max(want, floor));
        Access through = via;
        String where = coord(via != null ? net.getPosition() : pos);
        BlockPos anchor = via == null ? pos
                : via.pos() != null ? via.pos() : her.m_20183_();
        com.dwinovo.numen.permission.Action deed = via != null
                ? Action.take(anchor, level.m_8055_(anchor), null)
                : Action.useBlock(pos, level.m_8055_(pos));
        return call.authorize(deed).then(ignored -> {
            FluidStack taken = null;
            for (IFluidHandler source : new IFluidHandler[] { inTank, capTank }) {
                if (source == null) {
                    continue;
                }
                FluidStack one = source.drain(new FluidStack(fluid, n), IFluidHandler.FluidAction.EXECUTE);
                if (one != null && !one.isEmpty() && one.getAmount() > 0) {
                    taken = one;
                    break;
                }
            }
            boolean fromTank = taken != null && !taken.isEmpty() && taken.getAmount() > 0;
            if (!fromTank) {
                taken = net.extractFluid(new FluidStack(fluid, n), n,
                        com.refinedmods.refinedstorage.api.util.Action.PERFORM);
            }
            int got = taken == null || taken.isEmpty() ? 0 : taken.getAmount();
            if (got <= 0) {
                throw new ApiError(ErrorKind.FAILED, "没取出来 " + name,
                        "可能刚被别的机器拿走了；再试一次。");
            }
            Inventory bag = her.m_150109_();
            int filled = 0;
            int leftover = got;
            int carriers = 0;
            int capped = 0;
            int bestRoom = 0;
            StringBuilder notes = new StringBuilder();
            for (int i = 0; i < bag.m_6643_() && leftover > 0; i++) {
                ItemStack stack = bag.m_8020_(i);
                if (stack.m_41619_()) {
                    continue;
                }
                if (((ICapabilityProvider) (Object) stack)
                        .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null) == null) {
                    continue;
                }
                carriers++;
                ItemStack one = stack.m_41777_();
                one.m_41764_(1);
                IFluidHandlerItem probe = ((ICapabilityProvider) (Object) one)
                        .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
                int room = probe == null ? 0
                        : probe.fill(new FluidStack(fluid, leftover), IFluidHandler.FluidAction.SIMULATE);
                if (room <= 0) {
                    capped++;
                    if (notes.length() < 160) {
                        notes.append(idOf(stack)).append(" ");
                    }
                    continue;
                }
                bestRoom = Math.max(bestRoom, room);
                int use = splitOne(bag, i);
                if (use < 0) {
                    continue;
                }
                ItemStack single = bag.m_8020_(use);
                IFluidHandlerItem item = ((ICapabilityProvider) (Object) single)
                        .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
                int put = item == null ? 0
                        : item.fill(new FluidStack(fluid, leftover), IFluidHandler.FluidAction.EXECUTE);
                if (put <= 0) {
                    continue;
                }
                bag.m_6836_(use, item.getContainer());
                filled += put;
                leftover -= put;
            }
            if (leftover > 0) {
                if (fromTank && inTank != null) {
                    inTank.fill(new FluidStack(fluid, leftover), IFluidHandler.FluidAction.EXECUTE);
                } else {
                    net.insertFluid(new FluidStack(fluid, leftover), leftover,
                            com.refinedmods.refinedstorage.api.util.Action.PERFORM);
                }
            }
            if (filled <= 0) {
                String reason;
                if (carriers == 0) {
                    reason = "背包里没有一件带流体能力的容器";
                } else if (capped > 0) {
                    reason = "背包里 " + carriers + " 件流体容器都装不下 " + name + "（它们已经装了别的东西或者已经满了）：" + notes.toString().trim();
                } else {
                    reason = "背包里 " + carriers + " 件流体容器都没接住（最大能装 " + bestRoom + " mB）";
                }
                throw new ApiError(ErrorKind.NO_MATERIAL, "身上没有能装 " + name + " 的空容器：" + reason,
                        "带几个空桶（或者对应的空容器）过来；流体已经倒回去了。");
            }
            String line = (fromTank ? "从流体接口的罐子里"
                    : through != null ? "通过" + through.via() : "从网络里")
                    + "装了 " + filled + " mB 的 " + name + " 进容器"
                    + (leftover > 0 ? "，还有 " + leftover + " mB 没容器装、倒回去了" : "");
            if (through != null) {
                pay(through, T_FLUID);
            }
            Show.swing(her);
            Show.sound(her, "minecraft:item.bucket.fill", 1.0f, 1.0f);
            return new Move(id, name, got, filled, where, line);
        });
    }

    // ------------------------------------------------------------------ patterns

    @Fn("照着游戏里的配方，在身边的样板终端里写一张样板——相当于替她按下那个「写入」按钮。")
    @Example("rs.rs.pattern(\"minecraft:iron_ingot\")")
    @Note("先找合成配方，找不到再试熔炉/高炉/烟熏（那几种写成加工样板）。"
            + "会用掉一张空白样板（refinedstorage:pattern）；终端要在 6 格以内。会先问主人。")
    public static Pending<Pattern> pattern(ServerCall call, PatternOpt args) {
        String id = args == null || args.id() == null ? "" : args.id().trim();
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说给哪个东西写样板",
                    "写成 rs.rs.pattern(\"minecraft:iron_ingot\")。");
        }
        Item item = itemFor(id);
        if (item == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有这个物品：" + id,
                    "id 写成 minecraft:iron_ingot 这样。");
        }
        ServerLevel level = level(call);
        NumenPlayer her = call.her();
        BlockPos pos = nearestBlock(level, her,
                be -> be instanceof GridBlockEntity grid && patternNode(grid) != null, BLOCK_REACH);
        if (pos == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "身边 6 格内没有样板终端",
                    "在离她 6 格以内放一个精致存储的「样板终端」，样板才有地方写。");
        }
        RecipeManager manager = level.m_7654_().m_129894_();
        RegistryAccess access = level.m_9598_();
        Recipe<?> recipe = recipeFor(manager, access, item);
        String name = label(new ItemStack(item));
        if (recipe == null) {
            throw new ApiError(ErrorKind.NO_PATH, "游戏里没有做 " + name + " 的配方",
                    "原版配方表里找不到产出它的合成/烧炼配方；这样东西大概只能靠战利品或别的机器得到。");
        }
        String kind = recipe instanceof CraftingRecipe ? "crafting" : cookingKind(recipe);
        String source = String.valueOf(recipe.m_6423_());
        Inventory bag = her.m_150109_();
        BlockState state = level.m_8055_(pos);
        return call.authorize(Action.useBlock(pos, state)).then(ignored -> {
            GridBlockEntity grid = level.m_7702_(pos) instanceof GridBlockEntity found ? found : null;
            GridNetworkNode node = grid == null ? null : patternNode(grid);
            if (node == null) {
                throw new ApiError(ErrorKind.FAILED, "样板终端没接上",
                        "方块可能刚被挖掉，或者这块区块没加载；再试一次。");
            }
            node.clearMatrix();
            boolean processing = !"crafting".equals(kind);
            node.setProcessingPattern(processing);
            if (processing) {
                IItemHandlerModifiable matrix = node.getProcessingMatrix();
                List<Ingredient> inputs = recipe.m_7527_();
                for (int i = 0; i < inputs.size() && i < 81; i++) {
                    Item one = pick(inputs.get(i));
                    if (one != null) {
                        matrix.setStackInSlot(i, new ItemStack(one));
                    }
                }
                matrix.setStackInSlot(81, recipe.m_8043_(access));
            } else {
                node.setExactPattern(false);
                CraftingContainer matrix = node.getCraftingMatrix();
                if (recipe instanceof ShapedRecipe shaped) {
                    int width = shaped.m_44220_();
                    int height = shaped.m_44221_();
                    List<Ingredient> inputs = shaped.m_7527_();
                    for (int row = 0; row < height; row++) {
                        for (int col = 0; col < width; col++) {
                            int at = row * width + col;
                            if (at >= inputs.size()) {
                                continue;
                            }
                            Item one = pick(inputs.get(at));
                            if (one != null) {
                                matrix.m_6836_(row * 3 + col, new ItemStack(one));
                            }
                        }
                    }
                } else {
                    List<Ingredient> inputs = recipe.m_7527_();
                    for (int i = 0; i < inputs.size() && i < 9; i++) {
                        Item one = pick(inputs.get(i));
                        if (one != null) {
                            matrix.m_6836_(i, new ItemStack(one));
                        }
                    }
                }
                node.onCraftingMatrixChanged();
            }
            IItemHandler patterns = node.getPatterns();
            ItemStack leftover = patterns.getStackInSlot(1);
            if (!leftover.m_41619_()) {
                ItemStack old = patterns.extractItem(1, 1, false);
                if (!old.m_41619_() && !bag.m_36054_(old)) {
                    patterns.insertItem(1, old, false);
                    throw new ApiError(ErrorKind.FAILED, "背包满了，终端里那张样板腾不出来",
                            "上次写好的样板还占着输出格，腾出一格再试。");
                }
            }
            if (patterns.getStackInSlot(0).m_41619_()) {
                ItemStack blank = takeOne(bag, RSItems.PATTERN.get());
                if (blank.m_41619_()) {
                    throw new ApiError(ErrorKind.NO_MATERIAL, "包里没有空白样板",
                            "先拿几张空白样板（refinedstorage:pattern）在包里，才写得出来。");
                }
                patterns.insertItem(0, blank, false);
            }
            if (!node.canCreatePattern()) {
                throw new ApiError(ErrorKind.FAILED, "样板终端不肯写这一张",
                        "配方没摆完整（有的配方要专用机器或特殊材料），或者样板格已经占满了。");
            }
            node.onCreatePattern();
            ItemStack written = patterns.getStackInSlot(1);
            if (written.m_41619_()) {
                throw new ApiError(ErrorKind.FAILED, "样板没写出来",
                        "终端没给出结果；看看终端里是不是缺空白样板，或者配方格被占了。");
            }
            ItemStack out = patterns.extractItem(1, 1, false);
            if (!bag.m_36054_(out)) {
                patterns.insertItem(1, out, false);
                throw new ApiError(ErrorKind.FAILED, "背包满了，样板先留在终端里",
                        "腾出一格再试——这一张已经写好了，就在终端右侧的输出格里。");
            }
            String line = "在身边的样板终端里写好了 " + name + "（" + id + "）的" + kindName(kind)
                    + "，照的是配方 " + source + "，样板已经放进背包";
            return new Pattern(id, name, kind, source, line);
        });
    }

    // ------------------------------------------------------------------ block helpers

    private static boolean rsBlock(BlockEntity block) {
        return block.getClass().getName().startsWith("com.refinedmods.refinedstorage.");
    }

    // ------------------------------------------------------------------ terminals

    /**
     * One way to reach a network with a terminal: either the terminal block she is standing at,
     * or the wireless terminal in her bag. {@code pos} is null for the wireless case; {@code item}
     * is null for the wired one.
     */
    private record Access(INetwork net, String via, BlockPos pos, ItemStack item) {
        boolean wireless() {
            return item != null;
        }
    }

    /**
     * How she gets at a network: the terminal block she is standing at, or the wireless terminal
     * in her bag. Wired wins, because standing at a machine is the plainest reading of "she used
     * it"; wireless is the fallback and is held to RS's own rules: bound to a network, a wireless
     * transmitter in range, and enough charge left in the item.
     */
    private static Access access(ServerCall call, int kind) {
        Access wired = wired(call, kind);
        if (wired != null) {
            return wired;
        }
        Access wireless = wireless(call, kind);
        if (wireless != null) {
            return wireless;
        }
        throw noTerminal(kind);
    }

    /** The terminal block of that kind within reach, wired to a live network; null when none. */
    private static Access wired(ServerCall call, int kind) {
        ServerLevel level = level(call);
        BlockPos pos = nearestBlock(level, call.her(), be -> terminalFor(be, kind), BLOCK_REACH);
        if (pos == null) {
            return null;
        }
        INetwork net = networkOf(level, pos);
        return net == null ? null : new Access(net, terminalName(kind), pos, null);
    }

    /** The wireless terminal of that kind in her bag, ready to use; null when she carries none. */
    private static Access wireless(ServerCall call, int kind) {
        NumenPlayer her = call.her();
        ItemStack item = wirelessIn(her, kind);
        if (item == null) {
            return null;
        }
        INetwork net = boundNetwork(call, item, kind);
        inRange(level(call), her, net, kind);
        charge(kind, item);
        return new Access(net, wirelessName(kind), null, item);
    }

    private static ApiError noTerminal(int kind) {
        String wired = terminalName(kind);
        String wireless = wirelessName(kind);
        boolean hasWireless = wirelessId(kind) != null;
        return new ApiError(ErrorKind.NOT_FOUND,
                "身边 6 格内没有" + wired + "，手里也没有" + wireless,
                hasWireless
                        ? "两条路选一条：在离她 6 格以内放一台" + wired + "；"
                                + "或者把" + wireless + "放进她背包（先用它右键一次网络方块完成绑定，"
                                + "网络里要有无线发射器，终端里要有电）。"
                        : "这套整合包里的" + wired + "只能有线用（精致存储没有出无线版），"
                                + "在离她 6 格以内放一台" + wired + "。");
    }

    /** Is this block entity the terminal of that kind? */
    private static boolean terminalFor(BlockEntity block, int kind) {
        try {
            if (block instanceof GridBlockEntity grid) {
                GridNetworkNode node = grid.getNode();
                if (node == null) {
                    return false;
                }
                GridType type = node.getGridType();
                if (type == null) {
                    return false;
                }
                return switch (kind) {
                    case T_ITEMS -> type == GridType.NORMAL || type == GridType.CRAFTING;
                    case T_FLUID -> type == GridType.FLUID;
                    case T_CRAFT -> type == GridType.CRAFTING;
                    case T_PATTERN -> type == GridType.PATTERN;
                    default -> false;
                };
            }
            if (kind == T_MONITOR) {
                return block.getClass().getName()
                        .startsWith("com.refinedmods.refinedstorage.blockentity.craftingmonitor.");
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String terminalName(int kind) {
        return switch (kind) {
            case T_ITEMS -> "物品终端";
            case T_FLUID -> "流体终端";
            case T_CRAFT -> "合成终端";
            case T_PATTERN -> "样板终端";
            default -> "合成监控器";
        };
    }

    private static String wirelessName(int kind) {
        return switch (kind) {
            case T_ITEMS -> "无线物品终端";
            case T_FLUID -> "无线流体终端";
            case T_CRAFT -> "无线合成终端";
            case T_MONITOR -> "无线合成监控器";
            default -> "无线样板终端";
        };
    }

    private static String wirelessId(int kind) {
        return switch (kind) {
            case T_ITEMS -> WIRELESS_ITEMS;
            case T_FLUID -> WIRELESS_FLUID;
            case T_CRAFT -> WIRELESS_CRAFT;
            case T_MONITOR -> WIRELESS_MONITOR;
            default -> null;
        };
    }

    /** The wireless terminal of that kind in her bag, or null. */
    private static ItemStack wirelessIn(NumenPlayer her, int kind) {
        String id = wirelessId(kind);
        if (id == null) {
            return null;
        }
        Inventory bag = her.m_150109_();
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (stack == null || stack.m_41619_()) {
                continue;
            }
            if (id.equals(idOf(stack))) {
                return stack;
            }
        }
        return null;
    }

    /**
     * The network a wireless terminal is bound to. RS remembers it in the item's NBT
     * ({@code NodeX/NodeY/NodeZ/Dimension}) when the player right-clicks a network block with it;
     * {@code NetworkItem#applyNetwork} is the same lookup RS itself does before opening a grid.
     */
    private static INetwork boundNetwork(ServerCall call, ItemStack stack, int kind) {
        if (!(stack.m_41720_() instanceof NetworkItem item)) {
            throw new ApiError(ErrorKind.FAILED, wirelessName(kind) + "认不出来",
                    "这不是精致存储的无线终端；换一台真的。");
        }
        if (!NetworkItem.isValid(stack)) {
            throw new ApiError(ErrorKind.FAILED, "这台" + wirelessName(kind) + "还没连上网络",
                    "拿它对准网络里的任意方块右键一次，它就记住那个网络了；之后才能无线用。");
        }
        AtomicReference<INetwork> found = new AtomicReference<>();
        item.applyNetwork(level(call).m_7654_(), stack, found::set, message -> {
        });
        INetwork net = found.get();
        if (net == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "无线终端记住的那个网络现在不在了",
                    "网络方块可能被挖了、或者那块区块没加载；用同一个网络里的方块重新绑定一次。");
        }
        return net;
    }

    @Fn("调试用：把最近那个精致存储网络里的节点一个个列出来，无线发射器额外标出范围和位置。")
    @Example("rs.rs.nodes()")
    @Note("查「为什么无线终端说没有发射器」时用；正常玩法用不到。")
    public static Nodes nodes(ServerCall call) {
        INetwork net = pick(call, null);
        List<String> names = new ArrayList<>();
        for (INetworkNodeGraphEntry entry : net.getNodeGraph().all()) {
            INetworkNode node = entry == null ? null : entry.getNode();
            if (node == null) {
                continue;
            }
            String name = node.getClass().getName();
            int dot = name.lastIndexOf('.');
            name = dot < 0 ? name : name.substring(dot + 1);
            if (node instanceof IWirelessTransmitter tx) {
                name = name + " [transmitter " + tx.getDimension() + " @" + coord(tx.getOrigin())
                        + " range " + tx.getRange() + "]";
            }
            names.add(name);
        }
        return new Nodes(names.size(), names, "网络里 " + names.size() + " 个节点");
    }

    public record Nodes(int count, List<String> names, String line) {
    }

    /** RS's own rule: a transmitter wired to this network, in this dimension, close enough. */
    private static void inRange(ServerLevel level, NumenPlayer her, INetwork net, int kind) {
        BlockPos at = her.m_20183_();
        double best = -1;
        int bestRange = 0;
        try {
            for (INetworkNodeGraphEntry entry : net.getNodeGraph().all()) {
                INetworkNode node = entry == null ? null : entry.getNode();
                if (!(node instanceof IWirelessTransmitter tx)) {
                    continue;
                }
                if (!String.valueOf(tx.getDimension()).equals(String.valueOf(level.m_46472_()))) {
                    continue;
                }
                BlockPos origin = tx.getOrigin();
                if (origin == null) {
                    continue;
                }
                double far = Math.sqrt(dist2(origin, at));
                if (far <= tx.getRange()) {
                    return;
                }
                if (best < 0 || far < best) {
                    best = far;
                    bestRange = tx.getRange();
                }
            }
        } catch (Throwable ignored) {
            return;
        }
        throw new ApiError(ErrorKind.OUT_OF_REACH, best < 0
                ? "这个网络上没有无线发射器，" + wirelessName(kind) + "用不了"
                : wirelessName(kind) + "够不着：离最近的发射器 " + Math.round(best) + " 格，它只管 "
                        + bestRange + " 格",
                "走进发射器范围里（或者回到终端旁边）；也可以给网络接一台无线发射器、装范围升级。");
    }

    /** Enough charge left for one operation, or a plain refusal. */
    private static void charge(int kind, ItemStack stack) {
        int[] cfg = wirelessEnergy(kind);
        if (cfg[0] == 0) {
            return;
        }
        IEnergyStorage fe = energyOf(stack);
        if (fe == null) {
            return;
        }
        int stored = fe.getEnergyStored();
        if (stored < Math.max(1, cfg[1])) {
            throw new ApiError(ErrorKind.NO_MATERIAL,
                    "这台" + wirelessName(kind) + "没电了（" + stored + "/" + cfg[2] + " FE）",
                    "把它放进充能器或能量方块里充一下；或者走过去用有线终端。");
        }
    }

    /** Pay for one operation out of the terminal's own buffer (RS bills the item, not the network). */
    private static void pay(Access access, int kind) {
        if (access == null || !access.wireless()) {
            return;
        }
        int[] cfg = wirelessEnergy(kind);
        if (cfg[0] == 0 || cfg[1] <= 0) {
            return;
        }
        IEnergyStorage fe = energyOf(access.item());
        if (fe != null) {
            fe.extractEnergy(cfg[1], false);
        }
    }

    private static IEnergyStorage energyOf(ItemStack stack) {
        return ((ICapabilityProvider) (Object) stack)
                .getCapability(ForgeCapabilities.ENERGY).orElse(null);
    }

    /**
     * {does the terminal use energy, cost per operation, capacity} from RS's own server config.
     * Read reflectively so the plugin keeps loading when RS Addons is not installed.
     */
    private static int[] wirelessEnergy(int kind) {
        try {
            Object config;
            String group;
            String cost;
            switch (kind) {
                case T_ITEMS -> {
                    config = RS.SERVER_CONFIG;
                    group = "getWirelessGrid";
                    cost = "getExtractUsage";
                }
                case T_FLUID -> {
                    config = RS.SERVER_CONFIG;
                    group = "getWirelessFluidGrid";
                    cost = "getExtractUsage";
                }
                case T_MONITOR -> {
                    config = RS.SERVER_CONFIG;
                    group = "getWirelessCraftingMonitor";
                    cost = "getCancelUsage";
                }
                case T_CRAFT -> {
                    Class<?> addons = Class.forName("com.refinedmods.refinedstorageaddons.RSAddons");
                    config = addons.getField("SERVER_CONFIG").get(null);
                    group = "getWirelessCraftingGrid";
                    cost = "getCraftUsage";
                }
                default -> {
                    return new int[] {0, 0, 0};
                }
            }
            Object section = config.getClass().getMethod(group).invoke(config);
            boolean use = (boolean) section.getClass().getMethod("getUseEnergy").invoke(section);
            int capacity = (int) section.getClass().getMethod("getCapacity").invoke(section);
            int usage = (int) section.getClass().getMethod(cost).invoke(section);
            return new int[] {use ? 1 : 0, Math.max(0, usage), capacity};
        } catch (Throwable ignored) {
            return new int[] {1, 1, 0};
        }
    }

    private static double dist2(BlockPos a, BlockPos b) {
        double dx = a.m_123341_() - b.m_123341_();
        double dy = a.m_123342_() - b.m_123342_();
        double dz = a.m_123343_() - b.m_123343_();
        return dx * dx + dy * dy + dz * dz;
    }

    /** The network a machine at that position is wired to, or null. */
    private static INetwork networkOf(ServerLevel level, BlockPos pos) {
        BlockEntity block = level.m_7702_(pos);
        if (block == null) {
            return null;
        }
        try {
            INetworkNode node = com.refinedmods.refinedstorage.util.NetworkUtils.getNodeFromBlockEntity(block);
            return node == null ? null
                    : com.refinedmods.refinedstorage.util.NetworkUtils.getNetworkFromNode(node);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Nearest block entity within reach that matches, or null. Only loaded chunks are looked at. */
    private static BlockPos nearestBlock(ServerLevel level, NumenPlayer her, Predicate<BlockEntity> want,
            double reach) {
        BlockPos center = her.m_20183_();
        int radius = (int) Math.ceil(reach);
        int minX = (center.m_123341_() - radius) >> 4;
        int maxX = (center.m_123341_() + radius) >> 4;
        int minZ = (center.m_123343_() - radius) >> 4;
        int maxZ = (center.m_123343_() + radius) >> 4;
        BlockPos best = null;
        double bestDistance = reach;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                if (!level.m_46749_(new BlockPos(cx << 4, center.m_123342_(), cz << 4))) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (Map.Entry<BlockPos, BlockEntity> each : chunk.m_62954_().entrySet()) {
                    if (!want.test(each.getValue())) {
                        continue;
                    }
                    double d = distance(each.getKey(), her);
                    if (d <= bestDistance) {
                        bestDistance = d;
                        best = each.getKey();
                    }
                }
            }
        }
        return best;
    }

    private static IItemHandler itemHandler(BlockEntity block) {
        ICapabilityProvider provider = (ICapabilityProvider) block;
        LazyOptional<IItemHandler> optional = provider.getCapability(ForgeCapabilities.ITEM_HANDLER, null);
        if (optional.isPresent()) {
            return optional.orElse(null);
        }
        for (Direction side : Direction.values()) {
            optional = provider.getCapability(ForgeCapabilities.ITEM_HANDLER, side);
            if (optional.isPresent()) {
                return optional.orElse(null);
            }
        }
        return null;
    }

    private static IFluidHandler fluidHandler(BlockEntity block) {
        ICapabilityProvider provider = (ICapabilityProvider) block;
        LazyOptional<IFluidHandler> optional = provider.getCapability(ForgeCapabilities.FLUID_HANDLER, null);
        if (optional.isPresent()) {
            return optional.orElse(null);
        }
        for (Direction side : Direction.values()) {
            optional = provider.getCapability(ForgeCapabilities.FLUID_HANDLER, side);
            if (optional.isPresent()) {
                return optional.orElse(null);
            }
        }
        return null;
    }

    /** 流体接口真正存东西的罐子（capability 给的代理口只往里灌，读不出来）。 */
    private static IFluidHandler fluidInNode(BlockEntity block) {
        try {
            Object node = block.getClass().getMethod("getNode").invoke(block);
            if (node == null) {
                return null;
            }
            Object tank = node.getClass().getMethod("getTankIn").invoke(node);
            return tank instanceof IFluidHandler handler ? handler : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int amountOf(IFluidHandler handler, Fluid fluid) {
        if (handler == null) {
            return 0;
        }
        try {
            FluidStack stack = handler.getFluidInTank(0);
            return stack != null && !stack.isEmpty() && stack.getFluid() == fluid ? stack.getAmount() : 0;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int insertInto(IItemHandler handler, Item item, int count) {        int left = count;
        for (int i = 0; i < handler.getSlots() && left > 0; i++) {
            ItemStack rest = handler.insertItem(i, new ItemStack(item, left), false);
            left = rest == null || rest.m_41619_() ? 0 : rest.m_41613_();
        }
        return left;
    }

    private static GridNetworkNode patternNode(GridBlockEntity grid) {
        GridNetworkNode node = grid.getNode();
        if (node == null || node.getGridType() != GridType.PATTERN) {
            return null;
        }
        return node;
    }

    private static Recipe<?> recipeFor(RecipeManager manager, RegistryAccess access, Item target) {
        for (CraftingRecipe one : manager.m_44013_(RecipeType.f_44107_)) {
            if (produces(one, access, target)) {
                return one;
            }
        }
        for (SmeltingRecipe one : manager.m_44013_(RecipeType.f_44108_)) {
            if (produces(one, access, target)) {
                return one;
            }
        }
        for (BlastingRecipe one : manager.m_44013_(RecipeType.f_44109_)) {
            if (produces(one, access, target)) {
                return one;
            }
        }
        for (SmokingRecipe one : manager.m_44013_(RecipeType.f_44110_)) {
            if (produces(one, access, target)) {
                return one;
            }
        }
        return null;
    }

    private static boolean produces(Recipe<?> recipe, RegistryAccess access, Item target) {
        try {
            ItemStack out = recipe.m_8043_(access);
            return out != null && !out.m_41619_() && out.m_41720_() == target;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Item pick(Ingredient ingredient) {
        for (ItemStack one : ingredient.m_43908_()) {
            if (!one.m_41619_()) {
                return one.m_41720_();
            }
        }
        return null;
    }

    private static String cookingKind(Recipe<?> recipe) {
        if (recipe instanceof SmeltingRecipe) {
            return "smelting";
        }
        if (recipe instanceof BlastingRecipe) {
            return "blasting";
        }
        if (recipe instanceof SmokingRecipe) {
            return "smoking";
        }
        return "processing";
    }

    private static String kindName(String kind) {
        return switch (kind) {
            case "crafting" -> "合成样板";
            case "smelting" -> "熔炉加工样板";
            case "blasting" -> "高炉加工样板";
            case "smoking" -> "烟熏加工样板";
            default -> "加工样板";
        };
    }

    private static int countIn(Inventory bag, Item item) {
        int total = 0;
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (!stack.m_41619_() && stack.m_41720_() == item) {
                total += stack.m_41613_();
            }
        }
        return total;
    }

    private static ItemStack takeOne(Inventory bag, Item item) {
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (stack.m_41619_() || stack.m_41720_() != item) {
                continue;
            }
            bag.m_6836_(i, new ItemStack(item, Math.max(0, stack.m_41613_() - 1)));
            return new ItemStack(item, 1);
        }
        return new ItemStack(item, 0);
    }

    private static int freeSlot(Inventory bag) {
        for (int i = 0; i < bag.m_6643_(); i++) {
            if (bag.m_8020_(i).m_41619_()) {
                return i;
            }
        }
        return -1;
    }

    /** The first item in her bag that holds a fluid (the one she asked for, when she named one). */
    private static Item firstFluidContainer(Inventory bag, Fluid want) {
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (stack.m_41619_()) {
                continue;
            }
            IFluidHandlerItem item = ((ICapabilityProvider) (Object) (stack.m_41777_()))
                    .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
            if (item == null) {
                continue;
            }
            FluidStack held;
            try {
                held = item.getFluidInTank(0);
            } catch (Throwable ignored) {
                continue;
            }
            if (held == null || held.isEmpty() || held.getAmount() <= 0) {
                continue;
            }
            if (want != null && held.getFluid() != want) {
                continue;
            }
            return stack.m_41720_();
        }
        return null;
    }

    /** Make slot {@code slot} hold exactly one of its item so it can be swapped for a container. */
    /**
     * How much fluid some container in her bag insists on before it takes anything: a plain bucket only
     * fills when it is offered a whole 1000 mB, while a tank takes whatever fits.
     */
    private static int fillFloor(Inventory bag, Fluid fluid, int want) {
        int floor = Integer.MAX_VALUE;
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (stack.m_41619_()) {
                continue;
            }
            IFluidHandlerItem item = ((ICapabilityProvider) (Object) stack)
                    .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM).orElse(null);
            if (item == null) {
                continue;
            }
            if (!item.getFluidInTank(0).isEmpty()) {
                continue;
            }
            int room = item.fill(new FluidStack(fluid, want), IFluidHandler.FluidAction.SIMULATE);
            if (room > 0 && want > 0) {
                return want;
            }
            room = item.fill(new FluidStack(fluid, 1000), IFluidHandler.FluidAction.SIMULATE);
            if (room > 0) {
                floor = Math.min(floor, 1000);
            }
        }
        return floor == Integer.MAX_VALUE ? want : floor;
    }

    private static int splitOne(Inventory bag, int slot) {
        ItemStack stack = bag.m_8020_(slot);
        if (stack.m_41619_()) {
            return -1;
        }
        if (stack.m_41613_() <= 1) {
            return slot;
        }
        int free = freeSlot(bag);
        if (free < 0) {
            return -1;
        }
        bag.m_6836_(free, new ItemStack(stack.m_41720_(), 1));
        bag.m_6836_(slot, new ItemStack(stack.m_41720_(), stack.m_41613_() - 1));
        return free;
    }

    // ------------------------------------------------------------------ plan helper

    private static Plan planOf(INetwork net, ItemStack proto, int count) {
        String id = idOf(proto);
        String name = label(proto);
        ICraftingManager manager = net.getCraftingManager();
        if (manager == null) {
            throw new ApiError(ErrorKind.FAILED, "这个网络没有合成管理器", "接上合成监视器/样板再试。");
        }
        ICalculationResult result;
        try {
            result = manager.create(proto, count);
        } catch (Throwable t) {
            throw new ApiError(ErrorKind.FAILED, "合成计划算不出来：" + t.getClass().getSimpleName(),
                    "样板可能正在被改；过一会儿再算。");
        }
        if (result == null) {
            throw new ApiError(ErrorKind.FAILED, "合成计划算不出来", "过一会儿再算一次。");
        }
        CalculationResultType type = CalculationResultType.TOO_COMPLEX;
        try {
            CalculationResultType got = result.getType();
            if (got != null) {
                type = got;
            }
        } catch (Throwable ignored) {
            type = CalculationResultType.TOO_COMPLEX;
        }
        List<Hit> need = new ArrayList<>();
        List<Hit> missing = new ArrayList<>();
        try {
            List<ICraftingPreviewElement> elements = result.getPreviewElements();
            if (elements != null) {
                for (ICraftingPreviewElement element : elements) {
                    if (need.size() >= MAX_HITS && missing.size() >= MAX_HITS) {
                        break;
                    }
                    if (!(element instanceof ItemCraftingPreviewElement item)) {
                        continue;
                    }
                    ItemStack stack = item.getStack();
                    if (stack == null || stack.m_41619_()) {
                        continue;
                    }
                    Hit hit = new Hit(idOf(stack), label(stack), stack.m_41613_(), "item");
                    if (item.doesDisableTaskStarting()) {
                        if (missing.size() < MAX_HITS) {
                            missing.add(hit);
                        }
                    } else if (need.size() < MAX_HITS) {
                        need.add(hit);
                    }
                }
            }
        } catch (Throwable ignored) {
            need.clear();
            missing.clear();
        }
        boolean craftable = type == CalculationResultType.OK;
        StringBuilder sb = new StringBuilder();
        sb.append("合 ").append(count).append(" 个 ").append(name)
                .append("（").append(id).append("）：");
        switch (type) {
            case OK -> sb.append("能下（料齐，样板也在）");
            case MISSING -> sb.append("下不了，缺料");
            case NO_PATTERN -> sb.append("下不了，网络里没有能合成它的样板");
            case RECURSIVE -> sb.append("下不了，样板互相引用成环了");
            case TOO_COMPLEX -> sb.append("下不了，这单太复杂（超过服务器的合成深度上限）");
            default -> sb.append("下不了：").append(type);
        }
        if (!missing.isEmpty()) {
            sb.append("。缺：");
            for (int i = 0; i < missing.size(); i++) {
                Hit hit = missing.get(i);
                if (i > 0) {
                    sb.append("、");
                }
                sb.append(hit.name()).append("×").append(hit.count());
            }
        }
        return new Plan(id, name, count, craftable, type.name().toLowerCase(Locale.ROOT), need, missing, sb.toString());
    }

    // ------------------------------------------------------------------ picking a network

    private static INetwork pick(ServerCall call, String named) {
        ServerLevel level = level(call);
        List<INetwork> nets = all(level);
        if (named != null && !named.isBlank()) {
            BlockPos want = parsePos(named);
            for (INetwork net : nets) {
                if (net.getPosition().equals(want)) {
                    return net;
                }
            }
            throw new ApiError(ErrorKind.NOT_FOUND, "没有网络在 " + coord(want),
                    "先 rs.rs.networks() 看有哪些；网络只有在区块加载着的时候才读得到。");
        }
        INetwork best = null;
        double bestDistance = Double.MAX_VALUE;
        for (INetwork net : nets) {
            double d = distance(net.getPosition(), call.her());
            if (d < bestDistance) {
                bestDistance = d;
                best = net;
            }
        }
        if (best == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这一带没有 Refined Storage 网络",
                    "控制器得在加载着的区块里：走过去，或者先 rs.rs.networks() 看远处的在哪。");
        }
        if (bestDistance > HERE_RADIUS) {
            throw new ApiError(ErrorKind.OUT_OF_REACH,
                    "最近的 RS 网络离她 " + Math.round(bestDistance) + " 格，够不着",
                    "走过去，或者用 net = \"x, y, z\" 点名要哪个网络。");
        }
        return best;
    }

    private static BlockPos parsePos(String text) {
        String[] parts = text.replace("[", " ").replace("]", " ")
                .replace("(", " ").replace(")", " ")
                .split("[,;\\s]+");
        List<Integer> numbers = new ArrayList<>();
        for (String part : parts) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            try {
                numbers.add(Integer.parseInt(token));
            } catch (NumberFormatException e) {
                continue;
            }
        }
        if (numbers.size() < 3) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "网络坐标读不懂：" + text,
                    "写成 net = \"438, 77, -1804\"：三个整数，逗号分开。");
        }
        return new BlockPos(numbers.get(0), numbers.get(1), numbers.get(2));
    }

    private static Net describe(ServerCall call, ServerLevel level, INetwork net) {
        BlockPos pos = net.getPosition();
        double distance = distance(pos, call.her());
        int nodes = 0;
        long energy = 0;
        long energyMax = -1;
        int itemKinds = 0;
        long itemStored = 0;
        long itemCapacity = -1;
        int fluidKinds = 0;
        int crafting = 0;
        boolean running = false;
        boolean creative = false;
        boolean locked = locked(net);
        try {
            nodes = net.getNodeGraph().all().size();
        } catch (Throwable ignored) {
            nodes = 0;
        }
        try {
            IEnergyStorage storage = net.getEnergyStorage();
            if (storage != null) {
                energy = storage.getEnergyStored();
                energyMax = storage.getMaxEnergyStored();
            }
        } catch (Throwable ignored) {
            energyMax = -1;
        }
        try {
            IStackList<ItemStack> list = net.getItemStorageCache().getList();
            itemKinds = list == null ? 0 : list.getStacks().size();
            if (list != null) {
                for (StackListEntry<ItemStack> entry : list.getStacks()) {
                    ItemStack stack = entry.getStack();
                    if (stack != null && !stack.m_41619_()) {
                        itemStored += stack.m_41613_();
                    }
                }
            }
        } catch (Throwable ignored) {
            itemKinds = 0;
        }
        try {
            long capacity = 0;
            boolean known = true;
            List<IStorage<ItemStack>> storages = net.getItemStorageCache().getStorages();
            if (storages != null) {
                for (IStorage<ItemStack> storage : storages) {
                    if (storage instanceof IStorageDisk<?> disk) {
                        capacity += disk.getCapacity();
                    } else {
                        known = false;
                    }
                }
                itemCapacity = known ? capacity : -1;
            }
        } catch (Throwable ignored) {
            itemCapacity = -1;
        }
        try {
            IStackList<FluidStack> list = net.getFluidStorageCache().getList();
            fluidKinds = list == null ? 0 : list.getStacks().size();
        } catch (Throwable ignored) {
            fluidKinds = 0;
        }
        try {
            ICraftingManager manager = net.getCraftingManager();
            crafting = manager == null ? 0 : manager.getTasks().size();
        } catch (Throwable ignored) {
            crafting = 0;
        }
        try {
            running = net.canRun();
        } catch (Throwable ignored) {
            running = false;
        }
        try {
            creative = net.getType() == NetworkType.CREATIVE;
        } catch (Throwable ignored) {
            creative = false;
        }
        StringBuilder sb = new StringBuilder();
        if (!running) {
            sb.append("网络停了（没电或没控制器）");
        } else {
            sb.append("网络在运行");
        }
        sb.append("，位置 ").append(coord(pos)).append("，离她 ").append(Math.round(distance)).append(" 格");
        sb.append("，存着 ").append(itemKinds).append(" 种 ").append(itemStored).append(" 件物品");
        if (itemCapacity > 0) {
            sb.append("（容量 ").append(itemCapacity).append("，用了 ")
                    .append(itemStored * 100L / itemCapacity).append("%）");
        } else if (itemCapacity < 0) {
            sb.append("（容量算不出来：网络里除了磁碟还有别的存储）");
        }
        if (fluidKinds > 0) {
            sb.append("，").append(fluidKinds).append(" 种流体");
        }
        if (crafting > 0) {
            sb.append("，").append(crafting).append(" 单在合");
        }
        if (energyMax > 0) {
            sb.append("，电量 ").append(energy * 100L / energyMax).append("%");
        } else if (creative) {
            sb.append("，创造供电");
        }
        if (locked) {
            sb.append("，挂了安全卡（只认主人）");
        }
        return new Net(coord(pos), Math.round(distance), nodes, locked, energy, energyMax,
                itemKinds, itemStored, itemCapacity, fluidKinds, crafting, running, creative, sb.toString());
    }

    // ------------------------------------------------------------------ security

    /**
     * True when the network carries security cards — RS has no public "is locked" method, so the
     * two private fields of {@code SecurityManager} are read. Reverse-engineered.
     */
    private static boolean locked(INetwork net) {
        try {
            ISecurityManager manager = net.getSecurityManager();
            if (manager == null) {
                return false;
            }
            Class<?> type = manager.getClass();
            Field global = field(type, "globalCard");
            if (global != null) {
                Object card = global.get(manager);
                if (card != null) {
                    return true;
                }
            }
            Field cards = field(type, "cards");
            if (cards != null) {
                Object map = cards.get(manager);
                if (map instanceof Map<?, ?> m && !m.isEmpty()) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Refuses when the network is carded and its owner cannot answer for it. Reads land here too:
     * what is on someone else's locked shelf is not hers to inventory.
     */
    private static void guard(ServerCall call, INetwork net, Permission permission) {
        if (!locked(net)) {
            return;
        }
        ServerPlayer owner;
        try {
            owner = call.her().resolveOwnerPlayer();
        } catch (Throwable t) {
            owner = null;
        }
        if (owner == null) {
            throw new ApiError(ErrorKind.DENIED, "这个网络挂了安全卡，而主人不在线",
                    "加锁的网络只认它的主人和主人授过权的人；等主人上线，或者用不加锁的网络。");
        }
        boolean ok;
        try {
            ok = net.getSecurityManager().hasPermission(permission, owner);
        } catch (Throwable t) {
            ok = false;
        }
        if (!ok) {
            throw new ApiError(ErrorKind.DENIED,
                    "这个网络的安全卡没给她「" + permissionName(permission) + "」的权限",
                    "让主人在安全卡里给她这个权限，或者换个不加锁的网络。");
        }
    }

    private static String permissionName(Permission permission) {
        return switch (permission) {
            case INSERT -> "存入";
            case EXTRACT -> "取出";
            case AUTOCRAFTING -> "下单合成";
            case MODIFY -> "改装设备";
            case BUILD -> "查看";
            case SECURITY -> "改安全卡";
        };
    }

    // ------------------------------------------------------------------ network access

    private static IRSAPI api() {
        return API.instance();
    }

    /**
     * Every network in this level whose position is in a loaded chunk. The manager keeps a
     * network registered after its chunks unload, where every node reads as stopped — filtering
     * here is what keeps the plugin from calling a working network "dead".
     */
    private static List<INetwork> all(ServerLevel level) {
        List<INetwork> out = new ArrayList<>();
        for (INetwork net : api().getNetworkManager(level).all()) {
            if (net == null || net.getLevel() != level) {
                continue;
            }
            try {
                if (!level.m_46749_(net.getPosition())) {
                    continue;
                }
            } catch (Throwable ignored) {
                continue;
            }
            out.add(net);
        }
        return out;
    }

    private static INetwork nearestTo(ServerLevel level, NumenPlayer her) {
        INetwork best = null;
        double bestDistance = Double.MAX_VALUE;
        for (INetwork net : all(level)) {
            double d = distance(net.getPosition(), her);
            if (d < bestDistance) {
                bestDistance = d;
                best = net;
            }
        }
        return bestDistance > (double) HERE_RADIUS * HERE_RADIUS ? null : best;
    }

    private static ServerLevel level(ServerCall call) {
        if (!(call.her().m_9236_() instanceof ServerLevel level)) {
            throw new ApiError(ErrorKind.FAILED, "她不在服务端世界里", "过一会儿再试。");
        }
        return level;
    }

    private static double distance(BlockPos pos, NumenPlayer her) {
        if (her == null) {
            return Double.MAX_VALUE;
        }
        double dx = pos.m_123341_() + 0.5 - her.m_20185_();
        double dy = pos.m_123342_() + 0.5 - her.m_20186_();
        double dz = pos.m_123343_() + 0.5 - her.m_20189_();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String coord(BlockPos pos) {
        return pos.m_123341_() + ", " + pos.m_123342_() + ", " + pos.m_123343_();
    }

    // ------------------------------------------------------------------ stack helpers

    private static Item itemFor(String id) {
        ResourceLocation rl = resourceLocation(id);
        if (rl == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "这个 id 读不懂：" + id,
                    "写成 命名空间:名字，比如 minecraft:iron_ingot。");
        }
        // 注册表对不认识的 id 会回默认值（air），所以先问在不在
        if (!ForgeRegistries.ITEMS.containsKey(rl)) {
            return null;
        }
        return ForgeRegistries.ITEMS.getValue(rl);
    }

    private static net.minecraft.world.level.material.Fluid fluidFor(String id) {
        ResourceLocation rl = resourceLocation(id);
        if (rl == null) {
            return null;
        }
        if (!ForgeRegistries.FLUIDS.containsKey(rl)) {
            return null;
        }
        return ForgeRegistries.FLUIDS.getValue(rl);
    }

    private static ResourceLocation resourceLocation(String id) {
        try {
            return new ResourceLocation(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation rl = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return rl == null ? "minecraft:air" : rl.toString();
    }

    private static String fluidIdOf(FluidStack stack) {
        ResourceLocation rl = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        return rl == null ? "minecraft:empty" : rl.toString();
    }

    /** Chinese name when the pack has one, else the server's English name. */
    private static String label(ItemStack stack) {
        return Zh.name(stack);
    }

    private static String label(net.minecraft.world.level.material.Fluid fluid) {
        return Zh.fluid(fluid);
    }

    private static String clean(String text) {
        return Zh.clean(text);
    }

    private static boolean matches(ItemStack stack, String query) {
        if (idOf(stack).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        if (label(stack).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        try {
            return stack.m_41786_().getString().toLowerCase(Locale.ROOT).contains(query);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean matches(FluidStack stack, String query) {
        if (fluidIdOf(stack).toLowerCase(Locale.ROOT).contains(query)) {
            return true;
        }
        return label(stack.getFluid()).toLowerCase(Locale.ROOT).contains(query);
    }

    private static int countOf(INetwork net, ItemStack proto) {
        try {
            IStackList<ItemStack> list = net.getItemStorageCache().getList();
            return list == null ? 0 : list.getCount(proto);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int countFluidOf(INetwork net, FluidStack proto) {
        try {
            IStackList<FluidStack> list = net.getFluidStorageCache().getList();
            return list == null ? 0 : list.getCount(proto);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static List<Map.Entry<ItemStack, Integer>> itemEntries(INetwork net) {
        List<Map.Entry<ItemStack, Integer>> out = new ArrayList<>();
        try {
            IStackList<ItemStack> list = net.getItemStorageCache().getList();
            if (list == null) {
                return out;
            }
            for (StackListEntry<ItemStack> entry : list.getStacks()) {
                ItemStack stack = entry.getStack();
                if (stack == null || stack.m_41619_()) {
                    continue;
                }
                out.add(Map.entry(stack, stack.m_41613_()));
            }
        } catch (Throwable ignored) {
            out.clear();
        }
        return out;
    }

    private static List<Map.Entry<FluidStack, Integer>> fluidEntries(INetwork net) {
        List<Map.Entry<FluidStack, Integer>> out = new ArrayList<>();
        try {
            IStackList<FluidStack> list = net.getFluidStorageCache().getList();
            if (list == null) {
                return out;
            }
            for (StackListEntry<FluidStack> entry : list.getStacks()) {
                FluidStack stack = entry.getStack();
                if (stack == null || stack.isEmpty()) {
                    continue;
                }
                out.add(Map.entry(stack, stack.getAmount()));
            }
        } catch (Throwable ignored) {
            out.clear();
        }
        return out;
    }

    private static int clamp(int value, int low, int high) {
        if (value < low) {
            return low;
        }
        return Math.min(value, high);
    }

    // ------------------------------------------------------------------ results

    /** One network. {@code distance} is in blocks, rounded. */
    public record Net(
            @Doc("控制器所在的坐标，也能拿去当 net 参数点名。") String pos,
            @Doc("离她多少格。") long distance,
            @Doc("网络里挂了几个节点（控制器、磁盘驱动器、线缆都算）。") int nodes,
            @Doc("这个网络挂没挂安全卡：挂了就只认主人。") boolean locked,
            @Doc("已存电量；没装能量存储时是 0。") long energy,
            @Doc("电量上限；-1 表示这台网络根本没有能量存储。") long energy_max,
            @Doc("存着几种物品。") int item_kinds,
            @Doc("一共存着多少件物品。") long item_stored,
            @Doc("能装多少件；-1 表示算不出来（网络里有不是磁碟的存储）。") long item_capacity,
            @Doc("存着几种流体。") int fluid_kinds,
            @Doc("有几单在自动合成。") int crafting,
            @Doc("现在能不能干活（有电、有控制器）。") boolean running,
            @Doc("是不是创造模式网络（不耗电、无限存）。") boolean creative,
            @Doc("一句话总结。") String line) {
    }

    public record Networks(
            @Doc("读到的网络。") List<Net> nets,
            @Doc("一句话总结。") String line) {
    }

    /** One line of a search result or a bill of materials. */
    public record Hit(
            @Doc("物品或流体的 id。") String id,
            @Doc("名字：有中文名就是中文名。") String name,
            @Doc("几个（流体是 mB）。") int count,
            @Doc("item 还是 fluid。") String kind) {
    }

    public record Found(
            @Doc("找到的东西，按数量从多到少。") List<Hit> hits,
            @Doc("一共找到几种（可能比列出来的多）。") int total,
            @Doc("一句话总结。") String line) {
    }

    public record Count(
            @Doc("查的东西的 id。") String id,
            @Doc("名字。") String name,
            @Doc("有几个（流体是 mB）。") int count,
            @Doc("item 还是 fluid。") String kind,
            @Doc("网络里到底有没有。") boolean found,
            @Doc("查的是哪个网络。") String net,
            @Doc("一句话总结。") String line) {
    }

    /** One running crafting task. */
    public record Craft(
            @Doc("任务编号，取消时用它。") String id,
            @Doc("在合什么的 id。") String what,
            @Doc("在合什么的名字。") String name,
            @Doc("合多少个。") int quantity,
            @Doc("到百分之几。") int percent,
            @Doc("在哪个网络上。") String net,
            @Doc("一句话总结。") String line) {
    }

    public record Crafts(
            @Doc("在合的单子。") List<Craft> tasks,
            @Doc("一句话总结。") String line) {
    }

    /** A crafting plan (or the receipt of one that was just placed). */
    public record Plan(
            @Doc("要合什么（id）。") String what,
            @Doc("要合什么（名字）。") String name,
            @Doc("合多少个。") int count,
            @Doc("现在能不能下单。") boolean craftable,
            @Doc("RS 给的结论：ok / missing / no_pattern / recursive / too_complex。") String type,
            @Doc("要用到的料。") List<Hit> need,
            @Doc("缺的料。") List<Hit> missing,
            @Doc("一句话总结。") String line) {
    }

    /** The receipt of something that actually moved. */
    public record Move(
            @Doc("动的是什么东西（id）；取消合成时是空串。") String id,
            @Doc("名字。") String name,
            @Doc("本来要动多少。") int asked,
            @Doc("实际动了多少。") int moved,
            @Doc("在哪个网络上。") String net,
            @Doc("一句话总结。") String line) {
    }

    // ------------------------------------------------------------------ arguments

    public record NetOpt(
            @Doc("哪个网络，写成 \"x, y, z\"；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record CountOpt(
            @Doc("数什么：物品 id 或流体 id，比如 minecraft:iron_ingot、minecraft:water。") String id,
            @Doc("哪个网络；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record QueryOpt(
            @Doc("搜什么：中文名、英文名或 id 片段。") String query,
            @Doc("只找 item 或只找 fluid，不写两种都找。") @Omitted("两种都找") Optional<String> kind,
            @Doc("最多几条，1-20。") @Omitted("10") Optional<Integer> limit,
            @Doc("哪个网络；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record PlanOpt(
            @Doc("合什么：物品 id。") String id,
            @Doc("合多少个，1-1024。") @Omitted("1") Optional<Integer> count,
            @Doc("哪个网络；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record MoveOpt(
            @Doc("哪个东西：物品 id。") String id,
            @Doc("要动多少个，1-1024。") @Omitted("1") Optional<Integer> count,
            @Doc("哪个网络；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record CancelOpt(
            @Doc("任务编号，从 crafting() 拿。") String task,
            @Doc("哪个网络；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record PourOpt(
            @Doc("哪个流体，比如 minecraft:water；不写就是包里所有能倒的。") @Omitted("全部")
            Optional<String> id,
            @Doc("要动多少（mB，一桶是 1000）；不写就尽量。") @Omitted("尽量")
            Optional<Integer> count,
            @Doc("哪个网络；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record BottleOpt(
            @Doc("哪个流体，比如 minecraft:water。") String id,
            @Doc("要装多少（mB，一桶是 1000）；不写就是 1000。") @Omitted("1000")
            Optional<Integer> count,
            @Doc("哪个网络；不写就是最近那个。") @Omitted("最近那个") Optional<String> net) {
    }

    public record Pattern(
            @Doc("样板写的是哪个东西（物品 id）。") String id,
            @Doc("名字。") String name,
            @Doc("crafting / smelting / blasting / smoking，决定这是合成样板还是加工样板。") String kind,
            @Doc("照着写的那条配方的 id。") String source,
            @Doc("一句话总结。") String line) {
    }

    public record PatternOpt(
            @Doc("给哪个东西写样板：物品 id，比如 minecraft:iron_ingot。") String id) {
    }
}
