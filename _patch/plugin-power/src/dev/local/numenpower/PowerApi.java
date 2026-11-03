package dev.local.numenpower;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
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
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code power}: how much energy the machines around her hold.
 *
 * <p>Written against the Forge energy capability rather than any single mod's classes, so one group
 * answers for Immersive Engineering capacitors, Extreme Reactors, thermal machines and every other
 * block that exposes {@link IEnergyStorage}. A block that keeps its energy somewhere else (Create's
 * stress, Mekanism's own units) simply is not counted here.
 *
 * <p>Reading is the default: the capability is only asked for its numbers, so a capacitor cannot be
 * charged or drained by her looking at it, and no block is ever asked to do work. The one call that
 * does change the world is {@code set_side} — it flips a machine's per-face input/output setting the
 * same way a wrench click does, and it goes through the permission layer first.
 */
public final class PowerApi {

    private static final int MAX_ROWS = 12;
    private static final int MAX_RADIUS = 48;
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> GET_CAPABILITY = new java.util.HashMap<>();

    private PowerApi() {
    }

    static void install(NumenApi numen) {
        numen.api("power", "电力：附近的机器、电容里还有多少电，够不够开工。", PowerApi.class);
    }

    /**
     * Whether the reflective capability lookup resolves at all on this server. Logged once at
     * registration, because a silently missing lookup would read as "nothing here has energy".
     */
    static boolean capabilityLookupReady() {
        return methodFor(net.minecraft.world.level.block.entity.BlockEntity.class) != null;
    }

    // ---------------------------------------------------------------- shapes

    /** One block that stores or moves Forge energy. */
    @Doc("一台带能量的机器或电容。")
    public record Machine(
            @Doc("方块坐标，形如 '320, 64, -1950'。") String pos,
            @Doc("方块 id。") String id,
            @Doc("当前存了多少 FE。") int energy,
            @Doc("最多能存多少 FE。") int capacity,
            @Doc("充满百分比，0-100。") int percent,
            @Doc("它能不能往外送电（是电源或电容）。") boolean output,
            @Doc("它能不能吃电（是被供电的机器）。") boolean input,
            @Doc("离她多少格。") int distance,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Everything energy-shaped around her, summed up. */
    @Doc("附近的电量总账。")
    public record Grid(
            @Doc("扫到几台带能量的方块。") int machines,
            @Doc("它们的存电总和（FE）。") long energy,
            @Doc("它们的容量总和（FE）。") long capacity,
            @Doc("总充满百分比，0-100。") int percent,
            @Doc("其中能在往外送电的台数。") int outputting,
            @Doc("最多 12 台明细，按存电量从多到少。") List<Machine> list,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Options for the radius-taking calls. */
    @Doc("扫描半径；不写就是 16 格。")
    public record RadiusOpt(
            @Doc("半径（格），1-48。") @Omitted("16") Optional<Integer> radius) {
    }

    /** Which block to look at. */
    @Doc("要操作的那台机器：写全坐标；不写就取她身边最近的那台带能量的机器。")
    public record Spot(
            @Doc("方块的 x。") Optional<Integer> x,
            @Doc("方块的 y。") Optional<Integer> y,
            @Doc("方块的 z。") Optional<Integer> z) {
    }

    /** One face's setting. */
    @Doc("一个面的状态。")
    public record Face(
            @Doc("哪个面：down 下 / up 上 / north 北 / south 南 / west 西 / east 东。") String face,
            @Doc("这个面现在是什么：input 输入、output 输出、none 无连接。") String config,
            @Doc("中文说法，可以直接念给她主人。") String text) {
    }

    /** Six faces of one machine. */
    @Doc("一台机器的六个面分别是什么。")
    public record Sides(
            @Doc("方块坐标，形如 '320, 64, -1950'。") String pos,
            @Doc("方块 id。") String id,
            @Doc("几个面是输入。") int inputs,
            @Doc("几个面是输出。") int outputs,
            @Doc("六个面，按 下、上、北、南、西、东 排。") List<Face> list,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** A face and what it should become. */
    @Doc("把哪一面的设置改成什么。")
    public record SideSet(
            @Doc("哪个面：down / up / north / south / west / east（也认 上/下/北/南/西/东）。") String face,
            @Doc("改成什么：input 输入、output 输出、none 断开。") String to,
            @Doc("方块的 x；不写就取她身边最近的那台机器。") Optional<Integer> x,
            @Doc("方块的 y。") Optional<Integer> y,
            @Doc("方块的 z。") Optional<Integer> z) {
    }

    // ---------------------------------------------------------------- calls

    @Fn("扫她附近（已加载区块内）所有带能量的机器：有几台、存了多少、够不够用。")
    @Example("power.power.machines()")
    @Note("只读。默认半径 16 格，可写 {radius = 32}，上限 48。只算已加载区块，走不到的地方不算。")
    public static Grid machines(ServerCall call, RadiusOpt args) {
        int radius = clampRadius(args == null ? 16 : args.radius().orElse(16));
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        List<Machine> found = new ArrayList<>();
        for (LevelChunk chunk : chunks(level, her, radius)) {
            for (java.util.Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                BlockPos pos = entry.getKey();
                if (Math.abs(pos.m_123341_() - her.m_123341_()) > radius
                        || Math.abs(pos.m_123343_() - her.m_123343_()) > radius
                        || Math.abs(pos.m_123342_() - her.m_123342_()) > radius) {
                    continue;
                }
                IEnergyStorage storage = energyOf(entry.getValue());
                if (storage == null) {
                    continue;
                }
                found.add(machine(level, pos, storage, her));
            }
        }
        long energy = 0;
        long capacity = 0;
        int outputting = 0;
        for (Machine m : found) {
            energy += Math.max(0, m.energy());
            capacity += Math.max(0, m.capacity());
            if (m.output() && m.energy() > 0) {
                outputting++;
            }
        }
        int percent = capacity <= 0 ? 0 : (int) Math.round(energy * 100.0 / capacity);
        found.sort(Comparator.comparingInt(Machine::energy).reversed());
        List<Machine> list = found.size() > MAX_ROWS ? new ArrayList<>(found.subList(0, MAX_ROWS)) : found;
        String line;
        if (found.isEmpty()) {
            line = "半径 " + radius + " 格内没扫到带能量的机器（只算已加载区块；机械动力靠应力，不算在这里）";
        } else {
            line = "半径 " + radius + " 格内 " + found.size() + " 台带能量的机器，共 " + energy + " / "
                    + capacity + " FE（" + percent + "%）"
                    + (outputting > 0 ? "，" + outputting + " 台在往外送电" : "，没有一台在往外送电");
        }
        return new Grid(found.size(), energy, capacity, percent, outputting, list, line);
    }

    @Fn("她身边最近的那台带能量的机器，看它还有多少电。")
    @Example("power.power.here()")
    @Note("只读。扫 16 格内，按距离取最近的一台；附近一台都没有会报错——就先 power.power.machines() 看一眼。")
    public static Machine here(ServerCall call, RadiusOpt args) {
        int radius = clampRadius(args == null ? 16 : args.radius().orElse(16));
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        Machine best = null;
        for (LevelChunk chunk : chunks(level, her, radius)) {
            for (java.util.Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                BlockPos pos = entry.getKey();
                if (Math.abs(pos.m_123341_() - her.m_123341_()) > radius
                        || Math.abs(pos.m_123343_() - her.m_123343_()) > radius
                        || Math.abs(pos.m_123342_() - her.m_123342_()) > radius) {
                    continue;
                }
                IEnergyStorage storage = energyOf(entry.getValue());
                if (storage == null) {
                    continue;
                }
                Machine candidate = machine(level, pos, storage, her);
                if (best == null || candidate.distance() < best.distance()) {
                    best = candidate;
                }
            }
        }
        if (best == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这一带没有带能量的机器",
                    "半径 " + radius + " 格内没有电容/发电机/用电机器，或者都在未加载区块里", null);
        }
        return best;
    }

    @Fn("看一台机器六个面各是什么：哪面进电、哪面出电、哪些空着。")
    @Example("power.power.sides({x = 320, y = 64, z = -1950})")
    @Note("只读。沉浸工程（IE）的机器和电容每一面都有自己的一道开关（输入/输出/无连接），这一条读的就是那六道；"
            + "不写坐标时取她身边最近的那台带能量的机器。")
    @Note("别家的机器没有这种面开关，会报 failed——那种机器靠能力接口决定进不进电，看她 power.power.here() 的电量就够。")
    public static Sides sides(ServerCall call, Spot args) {
        return sidesAt(levelOf(call), spot(call, args), call);
    }

    @Fn("把一台机器的某一面改成输入、输出或断开——等于她拿工程师锤敲那一下。")
    @Example("power.power.set_side(\"up\", \"output\", {x = 320, y = 64, z = -1950})")
    @Note("会先问主人（和多数的右键一样算「动方块」），而且要站在 6 格以内。")
    @Note("改完把那台机器六个面返回来看；原来就是目标状态时不会多敲。")
    public static Pending<Sides> set_side(ServerCall call, SideSet args) {
        ServerLevel level = levelOf(call);
        BlockPos pos = spot(call, call.her().m_20183_(), args.x(), args.y(), args.z());
        BlockState state = level.m_8055_(pos);
        BlockEntity be = level.m_7702_(pos);
        if (be == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "那格没有机器",
                    "坐标是不是写到别的格了？用 power.power.machines() 看一遍机器都在哪", null);
        }
        Direction face = directionOf(args.face());
        String target = configNameOf(args.to());
        if (toggleSideOf(be) == null) {
            throw new ApiError(ErrorKind.FAILED, blockName(level, pos) + " 没有输入输出面开关",
                    "只有沉浸工程的机器和电容有这种开关；别家的机器靠能力接口进电，不用改面", null);
        }
        double distance = Math.sqrt(pos.m_123331_(call.her().m_20183_()));
        if (distance > REACH) {
            throw new ApiError(ErrorKind.OUT_OF_REACH,
                    "离得太远（" + String.format(Locale.ROOT, "%.1f", distance) + " 格，能敲的是 "
                            + REACH + " 格内）",
                    "先 numen.move.to({x = " + pos.m_123341_() + ", y = " + pos.m_123342_()
                            + ", z = " + pos.m_123343_() + "}) 走过去，再敲", null);
        }
        return call.authorize(Action.useBlock(pos, state)).then(ok -> {
            for (int i = 0; i < 3; i++) {
                if (target.equals(configAt(be, face))) {
                    break;
                }
                if (!toggleSide(be, face, call.her())) {
                    break;
                }
            }
            String got = configAt(be, face);
            if (!target.equals(got)) {
                throw new ApiError(ErrorKind.FAILED,
                        "那一面没改成 " + target + "（现在是 " + got + "）",
                        "这台机器的面开关可能不是三态循环，别硬敲；把坐标和现在的状态报给主人", null);
            }
            return sidesAt(level, pos, call);
        });
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

    /**
     * The block's energy storage, if it exposes one on any face.
     *
     * <p>{@code BlockEntity#getCapability} is a Forge patch method: it exists at runtime but not in
     * the SRG server jar this plugin compiles against, so it has to be reached by name. The lookup is
     * cached per block-entity class, and a block that answers nothing simply has no energy for us.
     */
    private static IEnergyStorage energyOf(BlockEntity be) {
        if (be == null) {
            return null;
        }
        IEnergyStorage storage = energyOn(be, null);
        if (storage != null) {
            return storage;
        }
        for (Direction side : Direction.values()) {
            storage = energyOn(be, side);
            if (storage != null) {
                return storage;
            }
        }
        return null;
    }

    private static IEnergyStorage energyOn(BlockEntity be, Direction side) {
        Object raw = capabilityOf(be, side);
        if (!(raw instanceof net.minecraftforge.common.util.LazyOptional<?> lazy)) {
            return null;
        }
        Object value = lazy.resolve().orElse(null);
        return value instanceof IEnergyStorage storage ? storage : null;
    }

    private static Object capabilityOf(BlockEntity be, Direction side) {
        java.lang.reflect.Method method = methodFor(be.getClass());
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(be, ForgeCapabilities.ENERGY, side);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** The patched {@code getCapability(Capability, Direction)} method of this block-entity type. */
    private static java.lang.reflect.Method methodFor(Class<?> type) {
        java.lang.reflect.Method method = GET_CAPABILITY.get(type);
        if (method != null) {
            return method;
        }
        for (Class<?> search = type; search != null && search != Object.class; search = search.getSuperclass()) {
            for (java.lang.reflect.Method candidate : search.getDeclaredMethods()) {
                if (!candidate.getName().equals("getCapability") || candidate.getParameterCount() != 2) {
                    continue;
                }
                if (candidate.getParameterTypes()[0] != net.minecraftforge.common.capabilities.Capability.class) {
                    continue;
                }
                try {
                    candidate.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                GET_CAPABILITY.put(type, candidate);
                return candidate;
            }
        }
        // Fall back to the public lookup, which also finds an inherited interface default.
        try {
            java.lang.reflect.Method found = type.getMethod("getCapability",
                    net.minecraftforge.common.capabilities.Capability.class, Direction.class);
            found.setAccessible(true);
            GET_CAPABILITY.put(type, found);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Machine machine(Level level, BlockPos pos, IEnergyStorage storage, BlockPos her) {
        BlockState state = level.m_8055_(pos);
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        int energy = storage.getEnergyStored();
        int capacity = storage.getMaxEnergyStored();
        int percent = capacity <= 0 ? 0 : (int) Math.round(energy * 100.0 / capacity);
        boolean output = storage.canExtract();
        boolean input = storage.canReceive();
        int distance = (int) Math.round(Math.sqrt(pos.m_123331_(her)));
        String what = id == null ? "机器" : id.m_135815_();
        String line;
        if (energy <= 0) {
            line = what + "（" + coord(pos) + "）是空的，0 / " + capacity + " FE";
        } else if (capacity > 0 && percent <= 10) {
            line = what + "（" + coord(pos) + "）快见底了，" + energy + " / " + capacity + " FE（" + percent + "%）";
        } else {
            line = what + "（" + coord(pos) + "）" + energy + " / " + capacity + " FE（" + percent + "%）";
        }
        return new Machine(coord(pos), id == null ? "?" : id.toString(), energy, capacity, percent,
                output, input, distance, line);
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

    private static String coord(BlockPos pos) {
        return pos.m_123341_() + ", " + pos.m_123342_() + ", " + pos.m_123343_();
    }

    // ---------------------------------------------------------------- 面开关（沉浸工程）

    private static final int REACH = 6;
    private static final Direction[] FACES = { Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST };
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> SIDE_GET = new java.util.HashMap<>();
    private static final java.util.Map<Class<?>, java.lang.reflect.Method> SIDE_TOGGLE = new java.util.HashMap<>();
    private static final java.util.Set<Class<?>> NO_GET = new java.util.HashSet<>();
    private static final java.util.Set<Class<?>> NO_TOGGLE = new java.util.HashSet<>();

    private static BlockPos spot(ServerCall call, Spot args) {
        BlockPos her = call.her().m_20183_();
        if (args == null) {
            return nearestPos(call);
        }
        return spot(call, her, args.x(), args.y(), args.z());
    }

    private static BlockPos spot(ServerCall call, BlockPos her,
            Optional<Integer> x, Optional<Integer> y, Optional<Integer> z) {
        int given = (x.isPresent() ? 1 : 0) + (y.isPresent() ? 1 : 0) + (z.isPresent() ? 1 : 0);
        if (given == 0) {
            return nearestPos(call);
        }
        if (given != 3) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "坐标要写全三个数",
                    "x、y、z 缺一个都到不了那格；不写坐标就是取她身边最近的那台机器", null);
        }
        return new BlockPos(x.get(), y.get(), z.get());
    }

    private static BlockPos nearestPos(ServerCall call) {
        int radius = 16;
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        BlockPos best = null;
        double nearest = Double.MAX_VALUE;
        for (LevelChunk chunk : chunks(level, her, radius)) {
            for (java.util.Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                BlockPos pos = entry.getKey();
                if (Math.abs(pos.m_123341_() - her.m_123341_()) > radius
                        || Math.abs(pos.m_123343_() - her.m_123343_()) > radius
                        || Math.abs(pos.m_123342_() - her.m_123342_()) > radius) {
                    continue;
                }
                if (energyOf(entry.getValue()) == null) {
                    continue;
                }
                double d = pos.m_123331_(her);
                if (d < nearest) {
                    nearest = d;
                    best = pos;
                }
            }
        }
        if (best == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这一带没有带能量的机器",
                    "半径 " + radius + " 格内没有电容/发电机/用电机器；把坐标写全也行", null);
        }
        return best;
    }

    private static Sides sidesAt(ServerLevel level, BlockPos pos, ServerCall call) {
        BlockEntity be = level.m_7702_(pos);
        if (be == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "那格没有机器",
                    "坐标是不是写到别的格了？用 power.power.machines() 看一遍机器都在哪", null);
        }
        if (sideGetterOf(be) == null || toggleSideOf(be) == null) {
            throw new ApiError(ErrorKind.FAILED, blockName(level, pos) + " 没有输入输出面开关",
                    "只有沉浸工程的机器和电容有这种开关；别家的机器靠能力接口进电，不用改面", null);
        }
        List<Face> faces = new ArrayList<>();
        List<String> ins = new ArrayList<>();
        List<String> outs = new ArrayList<>();
        int none = 0;
        for (Direction face : FACES) {
            String config = configAt(be, face);
            String name = faceName(face);
            if ("input".equals(config)) {
                ins.add(name);
            } else if ("output".equals(config)) {
                outs.add(name);
            } else {
                none++;
            }
            faces.add(new Face(name, config, faceText(config)));
        }
        StringBuilder line = new StringBuilder(blockName(level, pos)).append("：");
        line.append(ins.isEmpty() ? "没有面在进电" : "进电 " + String.join("、", ins));
        line.append("；").append(outs.isEmpty() ? "没有面在出电" : "出电 " + String.join("、", outs));
        line.append("；其余 ").append(none).append(" 面断开");
        return new Sides(coord(pos), idOf(level, pos), ins.size(), outs.size(), faces, line.toString());
    }

    private static String configAt(BlockEntity be, Direction face) {
        java.lang.reflect.Method getter = sideGetterOf(be);
        if (getter == null) {
            return null;
        }
        try {
            Object value = getter.invoke(be, face);
            return value == null ? "none" : String.valueOf(value).toLowerCase(Locale.ROOT);
        } catch (ReflectiveOperationException e) {
            throw new ApiError(ErrorKind.FAILED, "读不出那一面的设置",
                    "这台机器的面开关读的时候抛了异常：" + e.getClass().getSimpleName(), null);
        }
    }

    private static boolean toggleSide(BlockEntity be, Direction face, Player her) {
        java.lang.reflect.Method toggle = toggleSideOf(be);
        try {
            Object result = toggle.invoke(be, face, her);
            return !(result instanceof Boolean) || ((Boolean) result).booleanValue();
        } catch (ReflectiveOperationException e) {
            throw new ApiError(ErrorKind.FAILED, "敲那一面的时候出错了",
                    "这台机器的面开关拒绝这次改动：" + e.getClass().getSimpleName()
                            + "；把坐标报给主人", null);
        }
    }

    private static java.lang.reflect.Method sideGetterOf(BlockEntity be) {
        return find(be, "getSideConfig", SIDE_GET, NO_GET, Direction.class);
    }

    private static java.lang.reflect.Method toggleSideOf(BlockEntity be) {
        return find(be, "toggleSide", SIDE_TOGGLE, NO_TOGGLE, Direction.class, Player.class);
    }

    /**
     * Walk up from the block entity looking for {@code name}; caches both hits and misses, so the
     * hundreds of lookups in a scan cost one reflection call each.
     */
    private static java.lang.reflect.Method find(BlockEntity be, String name,
            java.util.Map<Class<?>, java.lang.reflect.Method> cache, java.util.Set<Class<?>> missing,
            Class<?>... params) {
        Class<?> type = be.getClass();
        if (cache.containsKey(type)) {
            return cache.get(type);
        }
        if (missing.contains(type)) {
            return null;
        }
        for (Class<?> c = type; c != null && c != BlockEntity.class; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Method found = c.getDeclaredMethod(name, params);
                found.setAccessible(true);
                cache.put(type, found);
                return found;
            } catch (NoSuchMethodException ignored) {
                // keep walking up the hierarchy
            }
        }
        missing.add(type);
        return null;
    }

    private static Direction directionOf(String face) {
        if (face != null) {
            switch (face.trim().toLowerCase(Locale.ROOT)) {
                case "down": case "下": return Direction.DOWN;
                case "up": case "上": return Direction.UP;
                case "north": case "北": return Direction.NORTH;
                case "south": case "南": return Direction.SOUTH;
                case "west": case "西": return Direction.WEST;
                case "east": case "东": return Direction.EAST;
                default: break;
            }
        }
        throw new ApiError(ErrorKind.BAD_ARGUMENT, "没有叫 " + face + " 的面",
                "六个面是 down 下 / up 上 / north 北 / south 南 / west 西 / east 东", null);
    }

    private static String configNameOf(String to) {
        if (to != null) {
            switch (to.trim().toLowerCase(Locale.ROOT)) {
                case "input": case "进": case "进电": return "input";
                case "output": case "出": case "出电": return "output";
                case "none": case "empty": case "off": case "断开": case "关": return "none";
                default: break;
            }
        }
        throw new ApiError(ErrorKind.BAD_ARGUMENT, "没有叫 " + to + " 的接法",
                "能写的是 input 输入 / output 输出 / none 断开", null);
    }

    private static String faceName(Direction face) {
        switch (face) {
            case DOWN: return "下";
            case UP: return "上";
            case NORTH: return "北";
            case SOUTH: return "南";
            case WEST: return "西";
            case EAST: return "东";
            default: return face.toString().toLowerCase(Locale.ROOT);
        }
    }

    private static String faceText(String config) {
        if ("input".equals(config)) {
            return "输入";
        }
        if ("output".equals(config)) {
            return "输出";
        }
        return "断开";
    }

    private static String idOf(ServerLevel level, BlockPos pos) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.m_8055_(pos).m_60734_());
        return id == null ? "?" : id.toString();
    }

    private static String blockName(ServerLevel level, BlockPos pos) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(level.m_8055_(pos).m_60734_());
        return id == null ? "这台机器" : id.m_135815_();
    }
}
