package dev.local.numenfluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandlerItem;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code fluid}: what is inside the tanks around her, and moving liquid in and out of them.
 *
 * <p>A pipe network, a refinery and a bucket all "have water"; only one of them is a tank she can
 * read. This group asks every block entity in range for the {@code FLUID_HANDLER} capability and
 * reports the tanks that actually answer, so "is there water/petrol/coolant here" stops being a
 * guess made from the block's sprite.
 *
 * <p>Reading needs nothing. Pouring a bucket in or bottling some out asks the owner first, because
 * that changes a tank somebody built.
 *
 * <p>Capabilities are taken through {@code ICapabilityProvider} rather than by reflecting over
 * {@code getDeclaredMethods()}: resolving a whole modded block-entity class pulls client-only types
 * (a screen class) into the dedicated server and Forge refuses to load them, which used to kill the
 * whole tick loop.
 */
public final class FluidApi {

    private static final int MAX_RADIUS = 48;
    private static final int MAX_ROWS = 12;
    private static final int MAX_FLUIDS = 6;
    private static final int MAX_IO = 64_000;
    private static final int DEFAULT_RADIUS = 16;

    private FluidApi() {
    }

    static void install(NumenApi numen) {
        numen.api("fluid", "流体：附近的储罐里有什么液体、有多少、还剩多少空位；也能把桶里的倒进储罐、把储罐里的装进桶。",
                FluidApi.class);
    }

    // ---------------------------------------------------------------- records

    /** Options for the radius-taking calls. */
    @Doc("扫描半径；不写就是 16 格。")
    public record RadiusOpt(
            @Doc("半径（格），1-48。") @Omitted("16") Optional<Integer> radius) {
    }

    /** One liquid inside one tank. */
    public record FluidView(
            @Doc("液体 id，如 minecraft:water") String id,
            @Doc("显示名") String name,
            @Doc("多少 mB") int amount) {
    }

    /** One block that stores liquid. */
    public record Tank(
            @Doc("方块 id") String block,
            @Doc("方块中文名") String name,
            @Doc("坐标") String pos,
            @Doc("里面装的液体（最多 6 条）") List<FluidView> fluids,
            @Doc("总容量 mB") int capacity,
            @Doc("现有总量 mB") int amount,
            @Doc("还剩多少空位 mB") int free,
            @Doc("充盈百分比 0-100") int percent,
            @Doc("距离（格）") int distance,
            @Doc("一句话") String line) {
    }

    /** Everything the scan found. */
    public record Fleet(
            @Doc("储罐数量") int containers,
            @Doc("总液体量 mB") int amount,
            @Doc("总容量 mB") int capacity,
            @Doc("总共还剩多少空位 mB") int free,
            @Doc("充盈百分比") int percent,
            @Doc("每个储罐的详情（按存量倒序，最多 12 个）") List<Tank> list,
            @Doc("一句话") String line) {
    }

    /** One liquid container in her own hands, read through {@code FLUID_HANDLER_ITEM}. */
    public record Carried(
            @Doc("哪只手：main 或 off") String hand,
            @Doc("物品 id") String id,
            @Doc("物品显示名") String name,
            @Doc("里面的液体 id；空容器为空字符串") String fluid,
            @Doc("里面的液体显示名；空容器为空字符串") String fluid_name,
            @Doc("容器容量 mB；不认识的容器为 -1") int capacity,
            @Doc("现有液体 mB") int amount,
            @Doc("还能装多少 mB") int free,
            @Doc("一句话") String line) {
    }

    /** Options for {@code fluid.item}: which hand. */
    @Doc("读她自己手上那个容器物品里的液体。")
    public record ItemOpt(
            @Doc("哪只手：main（默认）或 off。") @Omitted("main") Optional<String> hand) {
    }

    /** Options for {@code fluid.pour}. */
    @Doc("把身上装着液体的容器倒进身边的储罐。")
    public record PourOpt(
            @Doc("找储罐的半径（格），1-48，默认 16。") @Omitted("16") Optional<Integer> radius,
            @Doc("只倒这种液体（如 minecraft:water）；不写就是身上有什么倒什么。") @Omitted("手上有什么倒什么")
            Optional<String> id) {
    }

    /** Options for {@code fluid.bottle}. */
    @Doc("从身边的储罐里装液体进身上的空容器。")
    public record BottleOpt(
            @Doc("找储罐的半径（格），1-48，默认 16。") @Omitted("16") Optional<Integer> radius,
            @Doc("要装哪种液体（如 minecraft:water）；不写就是储罐里最多的那种。") @Omitted("储罐里最多的那种")
            Optional<String> id,
            @Doc("要装多少 mB，默认 1000；桶这种容器一次必须装满才会装，会自动取整。") @Omitted("1000")
            Optional<Integer> count) {
    }

    /** One finished pour/bottle. */
    public record Moved(
            @Doc("打算搬多少 mB") int asked,
            @Doc("实际搬了多少 mB") int moved,
            @Doc("液体 id") String fluid,
            @Doc("液体显示名") String name,
            @Doc("跟哪个储罐打交道，如 木桶（438, 77, -1804）") String tank,
            @Doc("一句话") String line) {
    }

    // ---------------------------------------------------------------- calls

    @Fn("扫她附近（已加载区块内）所有储罐：有几个、一共装了多少、还剩多少空位、分别是什么液体。")
    @Example("fluid.fluid.tanks()")
    @Example("fluid.fluid.tanks({radius = 32})")
    @Note("只读。默认半径 16 格，上限 48。只算已加载区块。机器里的内部储罐也算，但它们通常不能直接接管道。")
    public static Fleet tanks(ServerCall call, RadiusOpt args) {
        int radius = clampRadius(args == null ? DEFAULT_RADIUS : args.radius().orElse(DEFAULT_RADIUS));
        List<Tank> found = scan(call, radius);
        int amount = 0;
        int capacity = 0;
        for (Tank tank : found) {
            amount += Math.max(0, tank.amount());
            capacity += Math.max(0, tank.capacity());
        }
        int free = Math.max(0, capacity - amount);
        int percent = capacity <= 0 ? 0 : (int) Math.round(amount * 100.0 / capacity);
        found.sort(Comparator.comparingInt(Tank::amount).reversed());
        List<Tank> list = found.size() > MAX_ROWS ? new ArrayList<>(found.subList(0, MAX_ROWS)) : found;
        String line;
        if (found.isEmpty()) {
            line = "半径 " + radius + " 格内没扫到储罐（只算已加载区块；桶、水瓶那种是物品，不算储罐，"
                    + "它们用 fluid.fluid.item() 看）";
        } else {
            line = "半径 " + radius + " 格内 " + found.size() + " 个储罐，共 " + amount + " / " + capacity
                    + " mB（" + percent + "%），还能装 " + free + " mB" + headline(found);
        }
        return new Fleet(found.size(), amount, capacity, free, percent, list, line);
    }

    @Fn("她身边最近的那个储罐，看它装的是什么、还有多少、还能装多少。")
    @Example("fluid.fluid.here()")
    @Note("只读。扫 16 格内，按距离取最近的一个；附近一个都没有会报错——就先 fluid.fluid.tanks() 看一眼。")
    public static Tank here(ServerCall call, RadiusOpt args) {
        int radius = clampRadius(args == null ? DEFAULT_RADIUS : args.radius().orElse(DEFAULT_RADIUS));
        Found found = nearest(call, radius);
        if (found == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这一带没有储罐",
                    "半径 " + radius + " 格内没有流体容器，或者都在未加载区块里；桶里的液体用 fluid.fluid.item() 看，"
                            + "身边哪里有储罐用 fluid.fluid.tanks() 扫。",
                    null);
        }
        return found.tank();
    }

    @Fn("读她自己手上那个容器物品里装了什么液体——水桶、岩浆桶、模组的便携储罐都算。")
    @Example("fluid.fluid.item()")
    @Example("fluid.fluid.item({hand = \"off\"})")
    @Note("只读她自己，不读别人的手。默认主手，写 {hand = \"off\"} 看副手。不是容器的物品（铁镐、面包）会报 not found。")
    public static Carried item(ServerCall call, ItemOpt args) {
        boolean off = args != null && args.hand().map(h -> h.equalsIgnoreCase("off")
                || h.equalsIgnoreCase("offhand")).orElse(false);
        String hand = off ? "off" : "main";
        ItemStack stack = off ? call.her().m_21206_() : call.her().m_21205_();
        if (stack == null || stack.m_41619_()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "手上是空的",
                    "那只手没有物品；要看罐子用 fluid.fluid.here()，要看手里的东西用 items.items.held()。", null);
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        String itemId = key(id);
        String name = Zh.name(stack);
        IFluidHandlerItem handler = containerOf(stack);
        if (handler == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, name + " 不是液体容器",
                    "它是手里那个物品，但没有流体能力；桶、罐子这类才有。", null);
        }
        int capacity = 0;
        int amount = 0;
        String fluidId = "";
        String fluidName = "";
        try {
            int tanks = handler.getTanks();
            for (int i = 0; i < tanks; i++) {
                capacity += Math.max(0, handler.getTankCapacity(i));
                FluidStack in = handler.getFluidInTank(i);
                if (in == null || in.isEmpty() || in.getAmount() <= 0) {
                    continue;
                }
                amount += in.getAmount();
                if (fluidId.isEmpty()) {
                    FluidView view = fluidView(in);
                    fluidId = view.id();
                    fluidName = view.name();
                }
            }
        } catch (Throwable ignored) {
            capacity = -1;
        }
        int free = capacity <= 0 ? 0 : Math.max(0, capacity - amount);
        String line;
        if (amount <= 0) {
            line = name + "是空的" + (capacity > 0 ? "（能装 " + capacity + " mB）" : "");
        } else {
            line = name + "里装着" + fluidName + " " + amount
                    + (capacity > 0 ? " / " + capacity + " mB（" + Math.round(amount * 100.0 / capacity) + "%）"
                            : " mB")
                    + (free > 0 ? "，还能装 " + free + " mB" : "");
        }
        return new Carried(hand, itemId, name, fluidId, fluidName, capacity, amount, free, line);
    }

    @Fn("把身上装着液体的容器（水桶、岩浆桶、模组的罐子）倒进身边最近的储罐。")
    @Example("fluid.fluid.pour()")
    @Example("fluid.fluid.pour({id = \"minecraft:water\"})")
    @Note("会先问主人。倒的是她自己物品栏里的容器，一次最多 64 桶的量。储罐满了就只倒装得下的那部分，"
            + "剩下的留在容器里。空出来的容器（比如空桶）会放回物品栏。")
    public static Pending<Moved> pour(ServerCall call, PourOpt args) {
        int radius = clampRadius(args == null ? DEFAULT_RADIUS : args.radius().orElse(DEFAULT_RADIUS));
        String want = args == null || args.id().isEmpty() ? "" : args.id().orElse("").trim();
        Found found = nearest(call, radius);
        if (found == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这一带没有储罐",
                    "半径 " + radius + " 格内没有能接液体的方块；先 fluid.fluid.tanks() 扫一下，或者走近一点。", null);
        }
        IFluidHandler tank = found.handler();
        NumenPlayer her = call.her();
        Inventory bag = her.m_150109_();
        List<Integer> slots = new ArrayList<>();
        int carried = 0;
        boolean stackNeedsSlot = false;
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (stack == null || stack.m_41619_()) {
                continue;
            }
            ItemStack probe = stack.m_41777_();
            probe.m_41764_(1);
            IFluidHandlerItem handler = containerOf(probe);
            if (handler == null) {
                continue;
            }
            FluidStack inside = contentsOf(handler);
            if (inside == null || inside.isEmpty()) {
                continue;
            }
            if (!want.isEmpty() && !key(ForgeRegistries.FLUIDS.getKey(inside.getFluid())).equals(want)) {
                continue;
            }
            slots.add(i);
            carried += inside.getAmount();
            if (stack.m_41613_() > 1) {
                stackNeedsSlot = true;
            }
        }
        if (slots.isEmpty()) {
            throw new ApiError(ErrorKind.NO_MATERIAL,
                    want.isEmpty() ? "身上没有装着液体的容器" : "身上没有装着 " + fluidLabel(want) + " 的容器",
                    "先在物品栏里放几个装着液体的桶；要看手上那一个用 fluid.fluid.item()。", null);
        }
        if (stackNeedsSlot && !hasFreeSlot(bag)) {
            throw new ApiError(ErrorKind.FAILED, "物品栏满了，腾不出空位放倒空之后的容器",
                    "倒完水之后桶会变成空桶，需要一个空格；先丢掉点东西再倒。", null);
        }
        BlockPos pos = found.pos();
        BlockState state = found.state();
        String where = found.tank().name() + "（" + coord(pos) + "）";
        int asked = Math.min(carried, MAX_IO);
        return call.authorize(Action.useBlock(pos, state)).then(ignored -> {
            int moved = 0;
            String fluidId = "";
            String fluidName = "";
            for (int i : slots) {
                if (moved >= MAX_IO) {
                    break;
                }
                ItemStack stack = bag.m_8020_(i);
                if (stack == null || stack.m_41619_()) {
                    continue;
                }
                int count = stack.m_41613_();
                ItemStack one = stack.m_41777_();
                one.m_41764_(1);
                IFluidHandlerItem handler = containerOf(one);
                if (handler == null) {
                    continue;
                }
                FluidStack inside = contentsOf(handler);
                if (inside == null || inside.isEmpty()) {
                    continue;
                }
                if (!want.isEmpty() && !key(ForgeRegistries.FLUIDS.getKey(inside.getFluid())).equals(want)) {
                    continue;
                }
                int offer = Math.min(inside.getAmount(), MAX_IO - moved);
                if (offer <= 0) {
                    break;
                }
                int accepted = tank.fill(new FluidStack(inside.getFluid(), offer), IFluidHandler.FluidAction.SIMULATE);
                if (accepted <= 0) {
                    continue;
                }
                FluidStack drained = handler.drain(new FluidStack(inside.getFluid(), accepted),
                        IFluidHandler.FluidAction.EXECUTE);
                if (drained == null || drained.isEmpty()) {
                    continue;
                }
                int got = tank.fill(drained, IFluidHandler.FluidAction.EXECUTE);
                if (got <= 0) {
                    continue;
                }
                moved += got;
                if (fluidId.isEmpty()) {
                    fluidId = key(ForgeRegistries.FLUIDS.getKey(drained.getFluid()));
                    fluidName = fluidView(drained).name();
                }
                ItemStack left = handler.getContainer();
                if (left == null) {
                    left = new ItemStack(one.m_41720_(), 1);
                }
                if (count <= 1) {
                    bag.m_6836_(i, left);
                } else {
                    bag.m_6836_(i, new ItemStack(stack.m_41720_(), count - 1));
                    if (!bag.m_36054_(left)) {
                        bag.m_6836_(i, new ItemStack(stack.m_41720_(), count));
                        tank.drain(new FluidStack(drained.getFluid(), got), IFluidHandler.FluidAction.EXECUTE);
                        moved -= got;
                        throw new ApiError(ErrorKind.FAILED, "倒完了，但空容器塞不回物品栏",
                                "液体已经倒回去了；先腾一个格子，再试一次。", null);
                    }
                }
            }
            if (moved <= 0) {
                throw new ApiError(ErrorKind.FAILED, "一点也没倒进去",
                        where + "已经满了，或者只收别的液体；用 fluid.fluid.here() 看看它还剩多少空位。", null);
            }
            String line = "把 " + moved + " mB 的 " + fluidName + " 从容器倒进了" + where
                    + (moved < asked ? "（只倒得下 " + moved + "，剩下的还在容器里）" : "");
            Show.swing(her);
            Show.sound(her, "minecraft:item.bucket.empty", 1.0f, 1.0f);
            return new Moved(asked, moved, fluidId, fluidName, where, line);
        });
    }

    @Fn("从身边最近的储罐里装液体进身上的空容器——空桶会自动装满整桶。")
    @Example("fluid.fluid.bottle()")
    @Example("fluid.fluid.bottle({id = \"minecraft:water\", count = 1000})")
    @Note("会先问主人。不写 id 就装储罐里最多的那种液体。桶这类容器一次必须装满才会装，"
            + "所以写 count = 500 也会自动凑够一整桶；装多少 mB 看回执里的 moved。")
    public static Pending<Moved> bottle(ServerCall call, BottleOpt args) {
        int radius = clampRadius(args == null ? DEFAULT_RADIUS : args.radius().orElse(DEFAULT_RADIUS));
        String want = args == null || args.id().isEmpty() ? "" : args.id().orElse("").trim();
        int count = clamp(args == null ? 1000 : args.count().orElse(1000), 1, MAX_IO);
        Fluid asked = want.isEmpty() ? null : fluidOf(want);
        // 要装的储罐必须是「真的装着这种液体」的那个：身边常有空罐子，最近的那个未必有货。
        Found found = hasFluid(call, radius, asked);
        if (found == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL,
                    asked == null ? "这一带的储罐都是空的" : "半径 " + radius + " 格内没有装着 " + Zh.fluid(asked) + " 的储罐",
                    "先 fluid.fluid.tanks() 看看哪个罐里有货，或者先 fluid.fluid.pour() 倒点进去。", null);
        }
        IFluidHandler tank = found.handler();
        Fluid fluid = asked == null ? mostIn(tank) : asked;
        if (fluid == null) {
            IFluidHandler real = nodeTank(found.be());
            if (real != null) {
                tank = real;
                fluid = mostIn(tank);
            }
        }
        if (fluid == null) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    want.isEmpty() ? "这个储罐里是空的" : "没有这种液体： " + want,
                    want.isEmpty()
                            ? "空储罐装不出东西；先往里倒点（fluid.fluid.pour()），或者换个储罐。"
                            : "id 写成 minecraft:water 这样；液体 id 从 fluid.fluid.tanks() 的回执里抄。",
                    null);
        }
        int stored = amountOf(tank, fluid);
        if (stored <= 0 || drains(tank, fluid, 1) <= 0) {
            IFluidHandler real = nodeTank(found.be());
            if (real != null && amountOf(real, fluid) > 0 && drains(real, fluid, 1) > 0) {
                tank = real;
                stored = amountOf(tank, fluid);
            }
        }
        if (stored <= 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "储罐里没有 " + Zh.fluid(fluid),
                    "这个储罐里一滴都没有；先 fluid.fluid.pour() 倒点进去。", null);
        }
        NumenPlayer her = call.her();
        Inventory bag = her.m_150109_();
        int slot = -1;
        int room = 0;
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (stack == null || stack.m_41619_()) {
                continue;
            }
            ItemStack probe = stack.m_41777_();
            probe.m_41764_(1);
            IFluidHandlerItem handler = containerOf(probe);
            if (handler == null) {
                continue;
            }
            int probeRoom = fillable(handler, fluid, count, stored);
            if (probeRoom > 0) {
                slot = i;
                room = probeRoom;
                break;
            }
        }
        if (slot < 0) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "身上没有能装 " + Zh.fluid(fluid) + " 的空容器",
                    "带几个空桶、玻璃瓶或者模组的空罐子；装不下时也要先腾出空间——一次至少要能装 "
                            + count + " mB。", null);
        }
        BlockPos pos = found.pos();
        BlockState state = found.state();
        String where = found.tank().name() + "（" + coord(pos) + "）";
        int ask = Math.min(room, stored);
        final int target = slot;
        final IFluidHandler source = tank;
        final int available = stored;
        final Fluid wanted = fluid;
        return call.authorize(Action.take(pos, state, null)).then(ignored -> {
            ItemStack stack = bag.m_8020_(target);
            if (stack == null || stack.m_41619_()) {
                throw new ApiError(ErrorKind.FAILED, "装到一半容器不见了", "再试一次。", null);
            }
            int have = stack.m_41613_();
            ItemStack one = stack.m_41777_();
            one.m_41764_(1);
            IFluidHandlerItem handler = containerOf(one);
            if (handler == null) {
                throw new ApiError(ErrorKind.FAILED, "这个容器突然不是液体容器了", "再试一次。", null);
            }
            int want2 = fillable(handler, wanted, count, available);
            int take = Math.min(want2, available);
            if (take <= 0) {
                throw new ApiError(ErrorKind.FAILED, "容器装不下了", "先腾出空间，或者换个更大的容器。", null);
            }
            FluidStack drained = source.drain(new FluidStack(wanted, take), IFluidHandler.FluidAction.EXECUTE);
            if (drained == null || drained.isEmpty()) {
                throw new ApiError(ErrorKind.NO_MATERIAL, "储罐里没有 " + Zh.fluid(wanted) + " 了",
                        "刚才被别的机器抽走了；再试一次。", null);
            }
            int filled = handler.fill(drained, IFluidHandler.FluidAction.EXECUTE);
            if (filled < drained.getAmount()) {
                source.fill(new FluidStack(drained.getFluid(), drained.getAmount() - filled),
                        IFluidHandler.FluidAction.EXECUTE);
            }
            if (filled <= 0) {
                throw new ApiError(ErrorKind.FAILED, "一滴也没装进容器", "这个容器不收这种液体。", null);
            }
            ItemStack left = handler.getContainer();
            if (left == null) {
                left = new ItemStack(one.m_41720_(), 1);
            }
            if (have <= 1) {
                bag.m_6836_(target, left);
            } else {
                bag.m_6836_(target, new ItemStack(stack.m_41720_(), have - 1));
                if (!bag.m_36054_(left)) {
                    bag.m_6836_(target, new ItemStack(stack.m_41720_(), have));
                    source.fill(new FluidStack(drained.getFluid(), filled), IFluidHandler.FluidAction.EXECUTE);
                    throw new ApiError(ErrorKind.FAILED, "物品栏满了，装好的液体又倒回去了",
                            "空容器需要一个格子；先腾出一个空格再装。", null);
                }
            }
            String name = fluidView(drained).name();
            String line = "从" + where + "装了 " + filled + " mB 的 " + name + " 进容器"
                    + (filled < ask ? "（它只剩这么多了）" : "");
            Show.swing(her);
            Show.sound(her, "minecraft:item.bucket.fill", 1.0f, 1.0f);
            return new Moved(ask, filled, key(ForgeRegistries.FLUIDS.getKey(drained.getFluid())), name, where, line);
        });
    }

    // ---------------------------------------------------------------- plumbing

    /** The nearest tank, with everything the pour/bottle calls need. */
    private record Found(BlockPos pos, BlockState state, Tank tank, IFluidHandler handler, BlockEntity be) {
    }

    private static List<Tank> scan(ServerCall call, int radius) {
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        List<Tank> found = new ArrayList<>();
        for (LevelChunk chunk : chunks(level, her, radius)) {
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                BlockPos pos = entry.getKey();
                if (Math.abs(pos.m_123341_() - her.m_123341_()) > radius
                        || Math.abs(pos.m_123343_() - her.m_123343_()) > radius
                        || Math.abs(pos.m_123342_() - her.m_123342_()) > radius) {
                    continue;
                }
                IFluidHandler handler = handlerOf(entry.getValue());
                if (handler == null) {
                    continue;
                }
                Tank tank = tank(level, pos, handler, her);
                if (tank != null) {
                    found.add(tank);
                }
            }
        }
        return found;
    }

    private static Found nearest(ServerCall call, int radius) {
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        Found best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (LevelChunk chunk : chunks(level, her, radius)) {
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                BlockPos pos = entry.getKey();
                int distance = (int) Math.round(Math.sqrt(Math.max(0, pos.m_123331_(her))));
                if (distance > radius || distance >= bestDistance) {
                    continue;
                }
                IFluidHandler handler = handlerOf(entry.getValue());
                if (handler == null) {
                    continue;
                }
                Tank tank = tank(level, pos, handler, her);
                if (tank == null) {
                    continue;
                }
                best = new Found(pos, level.m_8055_(pos), tank, handler, entry.getValue());
                bestDistance = distance;
            }
        }
        return best;
    }

    /**
     * RS 的流体面板给的是 ProxyFluidHandler：水填得进去，但 capability 的 drain 读不回来
     * （真罐子在网络节点的 tankIn 里）。这里把真罐子捞出来当取液源。
     */
    private static IFluidHandler nodeTank(BlockEntity be) {
        if (be == null) {
            return null;
        }
        try {
            Object node = be.getClass().getMethod("getNode").invoke(be);
            Object tank = node == null ? null : node.getClass().getMethod("getTankIn").invoke(node);
            return tank instanceof IFluidHandler handler ? handler : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 试着抽出 n mB（模拟），用来判断这个 handler 到底能不能出液。 */
    private static int drains(IFluidHandler handler, Fluid fluid, int amount) {
        if (handler == null || fluid == null) {
            return 0;
        }
        try {
            FluidStack got = handler.drain(new FluidStack(fluid, amount), IFluidHandler.FluidAction.SIMULATE);
            return got == null || got.isEmpty() ? 0 : got.getAmount();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /** 最近的、装着这种液体的储罐；fluid 为 null 时取最近的「装着任意液体」的。 */
    private static Found hasFluid(ServerCall call, int radius, Fluid fluid) {
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        Found best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (LevelChunk chunk : chunks(level, her, radius)) {
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                BlockPos pos = entry.getKey();
                int distance = (int) Math.round(Math.sqrt(Math.max(0, pos.m_123331_(her))));
                if (distance > radius || distance >= bestDistance) {
                    continue;
                }
                IFluidHandler handler = handlerOf(entry.getValue());
                if (handler == null) {
                    continue;
                }
                if (fluid != null ? amountOf(handler, fluid) <= 0 : mostIn(handler) == null) {
                    continue;
                }
                Tank tank = tank(level, pos, handler, her);
                if (tank == null) {
                    continue;
                }
                best = new Found(pos, level.m_8055_(pos), tank, handler, entry.getValue());
                bestDistance = distance;
            }
        }
        return best;
    }

    private static ServerLevel levelOf(ServerCall call) {
        Level level = call.her().m_9236_();
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new ApiError(ErrorKind.FAILED, "这里不是服务端世界", "只在服务端调用", null);
        }
        return serverLevel;
    }

    private static int clampRadius(int radius) {
        if (radius < 1) {
            return 1;
        }
        return Math.min(radius, MAX_RADIUS);
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) {
            return min;
        }
        return Math.min(value, max);
    }

    /**
     * The block's fluid handler, if it exposes one on any face.
     *
     * <p>Asked through {@link ICapabilityProvider} on purpose: reflecting over
     * {@code getDeclaredMethods()} resolves every method signature of the class, and one modded
     * block entity in this pack has a signature mentioning a client-only screen type — resolving it
     * on a dedicated server throws and takes the server down with it.
     */
    private static IFluidHandler handlerOf(BlockEntity be) {
        if (be == null) {
            return null;
        }
        IFluidHandler handler = handlerOn(be, null);
        if (handler != null) {
            return handler;
        }
        for (Direction side : Direction.values()) {
            handler = handlerOn(be, side);
            if (handler != null) {
                return handler;
            }
        }
        return null;
    }

    private static IFluidHandler handlerOn(BlockEntity be, Direction side) {
        try {
            return ((ICapabilityProvider) be).getCapability(ForgeCapabilities.FLUID_HANDLER, side).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The container capability of a held item, or {@code null} for things that are not containers. */
    private static IFluidHandlerItem containerOf(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return null;
        }
        try {
            return ((ICapabilityProvider) (Object) stack)
                    .getCapability(ForgeCapabilities.FLUID_HANDLER_ITEM, null)
                    .orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * What the container holds, as one stack: the first liquid that has any amount.
     *
     * <p>Multi-tank containers (a jetpack, a portable tank) report their first non-empty tank; the
     * point of this group is "is there liquid in it, and which", not a full inventory listing.
     */
    private static FluidStack contentsOf(IFluidHandlerItem handler) {
        int tanks;
        try {
            tanks = handler.getTanks();
        } catch (Throwable ignored) {
            return null;
        }
        for (int i = 0; i < tanks; i++) {
            FluidStack in;
            try {
                in = handler.getFluidInTank(i);
            } catch (Throwable ignored) {
                continue;
            }
            if (in != null && !in.isEmpty() && in.getAmount() > 0) {
                return in;
            }
        }
        return null;
    }

    /**
     * How much this container will accept right now, allowing for containers that only fill whole.
     *
     * <p>Vanilla's bucket wrapper refuses anything under 1000 mB, so asking for 500 mB of water gets
     * zero room; trying a whole bucket afterwards tells us the real floor instead of pretending the
     * container is full.
     */
    private static int fillable(IFluidHandlerItem handler, Fluid fluid, int want, int stored) {
        int ask = Math.min(Math.max(0, want), Math.max(0, stored));
        if (ask <= 0) {
            return 0;
        }
        int room;
        try {
            room = handler.fill(new FluidStack(fluid, ask), IFluidHandler.FluidAction.SIMULATE);
        } catch (Throwable ignored) {
            return 0;
        }
        if (room > 0) {
            return room;
        }
        int bucket = Math.min(1000, Math.max(0, stored));
        if (bucket <= ask) {
            return 0;
        }
        try {
            return handler.fill(new FluidStack(fluid, bucket), IFluidHandler.FluidAction.SIMULATE);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static boolean hasFreeSlot(Inventory bag) {
        for (int i = 0; i < bag.m_6643_(); i++) {
            ItemStack stack = bag.m_8020_(i);
            if (stack == null || stack.m_41619_()) {
                return true;
            }
        }
        return false;
    }

    private static int amountOf(IFluidHandler handler, Fluid fluid) {
        int total = 0;
        int tanks;
        try {
            tanks = handler.getTanks();
        } catch (Throwable ignored) {
            return 0;
        }
        for (int i = 0; i < tanks; i++) {
            FluidStack in;
            try {
                in = handler.getFluidInTank(i);
            } catch (Throwable ignored) {
                continue;
            }
            if (in != null && !in.isEmpty() && in.getAmount() > 0 && in.getFluid() == fluid) {
                total += in.getAmount();
            }
        }
        return total;
    }

    /** The most plentiful liquid in a tank, for a bottle call that did not name one. */
    private static Fluid mostIn(IFluidHandler handler) {
        Fluid best = null;
        int bestAmount = 0;
        int tanks;
        try {
            tanks = handler.getTanks();
        } catch (Throwable ignored) {
            return null;
        }
        for (int i = 0; i < tanks; i++) {
            FluidStack in;
            try {
                in = handler.getFluidInTank(i);
            } catch (Throwable ignored) {
                continue;
            }
            if (in != null && !in.isEmpty() && in.getAmount() > bestAmount) {
                best = in.getFluid();
                bestAmount = in.getAmount();
            }
        }
        return best;
    }

    /** One block's tanks, or {@code null} when every tank in it is empty and has no capacity. */
    private static Tank tank(Level level, BlockPos pos, IFluidHandler handler, BlockPos her) {
        int tanks;
        try {
            tanks = handler.getTanks();
        } catch (Throwable t) {
            return null;
        }
        List<FluidView> fluids = new ArrayList<>();
        int capacity = 0;
        int amount = 0;
        for (int i = 0; i < tanks; i++) {
            int tankCapacity;
            FluidStack stack;
            try {
                tankCapacity = handler.getTankCapacity(i);
                stack = handler.getFluidInTank(i);
            } catch (Throwable t) {
                continue;
            }
            if (tankCapacity > 0) {
                capacity += tankCapacity;
            }
            if (stack == null || stack.isEmpty() || stack.getAmount() <= 0) {
                continue;
            }
            amount += stack.getAmount();
            if (fluids.size() < MAX_FLUIDS) {
                fluids.add(fluidView(stack));
            }
        }
        if (capacity <= 0 && fluids.isEmpty()) {
            return null;
        }
        BlockState state = level.m_8055_(pos);
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        String name = Zh.block(state.m_60734_());
        int free = Math.max(0, capacity - amount);
        int percent = capacity <= 0 ? 0 : (int) Math.round(amount * 100.0 / capacity);
        int distance = (int) Math.round(Math.sqrt(Math.max(0, pos.m_123331_(her))));
        return new Tank(key(id), name, coord(pos), fluids, capacity, amount, free, percent,
                distance, line(name, pos, fluids, amount, capacity, free, percent));
    }

    private static FluidView fluidView(FluidStack stack) {
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(stack.getFluid());
        return new FluidView(key(id), Zh.fluid(stack.getFluid()), stack.getAmount());
    }

    private static String line(String what, BlockPos pos, List<FluidView> fluids, int amount, int capacity,
            int free, int percent) {
        String where = what + "（" + coord(pos) + "）";
        if (fluids.isEmpty()) {
            return where + "是空的，0 / " + capacity + " mB";
        }
        StringBuilder out = new StringBuilder(where);
        if (percent <= 10) {
            out.append("快见底了，");
        } else if (percent >= 100) {
            out.append("是满的，");
        }
        out.append(contents(fluids)).append("，").append(amount).append(" / ").append(capacity)
                .append(" mB（").append(percent).append("%），还能装 ").append(free).append(" mB");
        return out.toString();
    }

    private static String contents(List<FluidView> fluids) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fluids.size(); i++) {
            if (i > 0) {
                out.append("、");
            }
            FluidView view = fluids.get(i);
            out.append(view.name()).append(" ").append(view.amount());
        }
        return out.toString();
    }

    /** The single most plentiful liquid across everything found, for the summary line. */
    private static String headline(List<Tank> found) {
        Map<String, Integer> byId = new LinkedHashMap<>();
        Map<String, String> names = new java.util.HashMap<>();
        for (Tank tank : found) {
            for (FluidView view : tank.fluids()) {
                byId.merge(view.id(), view.amount(), Integer::sum);
                names.put(view.id(), view.name());
            }
        }
        if (byId.isEmpty()) {
            return "，全是空的";
        }
        String top = null;
        int best = 0;
        for (Map.Entry<String, Integer> entry : byId.entrySet()) {
            if (entry.getValue() > best) {
                best = entry.getValue();
                top = entry.getKey();
            }
        }
        return "，最多的是" + names.get(top) + " " + best + " mB";
    }

    /** Loaded chunks around her, never loading new ones. */
    private static List<LevelChunk> chunks(ServerLevel level, BlockPos her, int radius) {
        int minChunkX = (her.m_123341_() - radius) >> 4;
        int maxChunkX = (her.m_123341_() + radius) >> 4;
        int minChunkZ = (her.m_123343_() - radius) >> 4;
        int maxChunkZ = (her.m_123343_() + radius) >> 4;
        List<LevelChunk> out = new ArrayList<>();
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                BlockPos probe = new BlockPos(cx << 4, her.m_123342_(), cz << 4);
                if (!level.m_46749_(probe)) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                if (chunk != null) {
                    out.add(chunk);
                }
            }
        }
        return out;
    }

    private static Fluid fluidOf(String id) {
        ResourceLocation key;
        try {
            key = new ResourceLocation(id);
        } catch (Throwable bad) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "流体 id 写得不对：" + id,
                    "写成 minecraft:water 这样。", null);
        }
        if (!ForgeRegistries.FLUIDS.containsKey(key)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "游戏里没有这种流体：" + id,
                    "id 从 fluid.fluid.tanks() 的回执里抄。", null);
        }
        return ForgeRegistries.FLUIDS.getValue(key);
    }

    private static String fluidLabel(String id) {
        try {
            return Zh.fluid(fluidOf(id));
        } catch (Throwable ignored) {
            return id;
        }
    }

    private static String key(ResourceLocation id) {
        return id == null ? "?" : id.toString();
    }

    private static String coord(BlockPos pos) {
        return pos.m_123341_() + ", " + pos.m_123342_() + ", " + pos.m_123343_();
    }
}
