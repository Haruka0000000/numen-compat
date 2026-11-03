package dev.local.numenvehicles;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * {@code vehicles}: the cars and planes standing around her, and getting in and out of one.
 *
 * <p>Immersive Vehicles (MTS) does not put its vehicles in the level as themselves. Every vehicle is
 * held by a vanilla stand-in entity ({@code mcinterface1201.BuilderEntityExisting}, one per
 * vehicle), and that stand-in keeps the real vehicle in a field called {@code entity}. The real
 * vehicle lives in a deep hierarchy whose public fields are the only stable surface
 * ({@code position}, {@code motion}, {@code rider}). So: find the stand-ins in the loaded world,
 * unwrap the vehicle, read it by reflection. Nothing here is compiled against the mod, so the jar
 * loads with or without it, and one renamed field downgrades a column instead of breaking the group.
 *
 * <p>{@code near}, {@code here} only read. {@code ride} and {@code leave} move her body: both go
 * through the permission layer first, the same way a player boarding a car has to pass the claim
 * check.
 */
public final class VehiclesApi {

    private static final int MAX_RADIUS = 96;
    private static final int MAX_ROWS = 10;
    /** How close she has to stand to a vehicle to get in. */
    private static final double BOARD_REACH = 6.0D;
    /** Vanilla stand-in entities that hold an MTS object; their field {@code entity} is the real one. */
    private static final String BUILDER_PACKAGE = "mcinterface1201.";
    /** Everything MTS that is not the MC-interface layer. */
    private static final String MTS_PACKAGE = "minecrafttransportsimulator.entities.";
    /** The class every car, plane and boat descends from; parts do not. */
    private static final String VEHICLE_CLASS =
            "minecrafttransportsimulator.entities.instances.AEntityVehicleC_Colliding";
    /** The wrapper MTS keeps around a {@code Player}; {@code getWrapperFor} is static. */
    private static final String WRAPPER_PLAYER_CLASS = "mcinterface1201.WrapperPlayer";
    /** What {@code setRider} takes as its first argument, on every entity and part. */
    private static final String IWRAPPER_ENTITY = "minecrafttransportsimulator.mcinterface.IWrapperEntity";

    private VehiclesApi() {
    }

    static void install(NumenApi numen) {
        numen.api("vehicles", "载具：附近有哪些车和飞机，停在哪、开多快、谁在上面；能上车、能下车。",
                VehiclesApi.class);
    }

    /**
     * Which parts of the vehicle hierarchy the reflection actually resolved, e.g.
     * {@code builder+vehicle+position}. Logged once at registration: a silently broken lookup would
     * otherwise read as "no vehicles nearby" instead of as a broken plugin.
     */
    static String reflectionState() {
        StringBuilder state = new StringBuilder();
        try {
            Class<?> builder = Class.forName(BUILDER_PACKAGE + "BuilderEntityExisting");
            if (fieldOf(builder, "entity", null) != null) {
                state.append("builder");
            }
        } catch (Throwable t) {
            return "builder-missing(" + t.getClass().getSimpleName() + ")";
        }
        try {
            Class<?> existing = Class.forName("minecrafttransportsimulator.entities.components.AEntityB_Existing");
            state.append(state.length() == 0 ? "" : "+");
            if (fieldOf(existing, "position", null) != null) {
                state.append("position");
            }
            if (fieldOf(existing, "motion", null) != null) {
                state.append(state.length() == 0 ? "" : "+").append("motion");
            }
            if (fieldOf(existing, "rider", null) != null) {
                state.append(state.length() == 0 ? "" : "+").append("rider");
            }
            if (Class.forName(VEHICLE_CLASS) != null) {
                state.append("+vehicles");
            }
        } catch (Throwable t) {
            state.append("hierarchy-missing(").append(t.getClass().getSimpleName()).append(")");
        }
        return state.length() == 0 ? "none" : state.toString();
    }

    // ---------------------------------------------------------------- shapes

    /** One vehicle as it stands right now. */
    @Doc("一辆车或一架飞机。")
    public record Vehicle(
            @Doc("载具名字；读不到真名时是它的内部名。") String name,
            @Doc("坐标，形如 '320, 64, -1950'。") String pos,
            @Doc("速度（格/秒），停着就是 0。") double speed,
            @Doc("车上坐了几个人。") int riders,
            @Doc("这辆车一共几个座位。") int seats,
            @Doc("还空着几个座位。") int free,
            @Doc("谁在开；没人就是空字符串。") String driver,
            @Doc("离她多少格。") int distance,
            @Doc("她是不是就坐在这一辆上。") boolean aboard,
            @Doc("车锁着没有（MTS 的防盗锁）；锁着就上不去，要让玩家先解锁。") boolean locked,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Every vehicle in range. */
    @Doc("附近的载具总览。")
    public record Fleet(
            @Doc("扫到几辆。") int count,
            @Doc("最多 10 辆，由近到远。") List<Vehicle> list,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Where she ended up after getting in. */
    @Doc("上车的结果。")
    public record Ride(
            @Doc("上了哪一辆。") String name,
            @Doc("坐在哪，比如 '副驾驶座'。") String at,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Where she was, after getting out. */
    @Doc("下车的结果。")
    public record Left(
            @Doc("刚才坐的是哪一辆。") String name,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Options for the scanning calls. */
    @Doc("扫描半径。")
    public record RadiusOpt(
            @Doc("半径（格），1-96；不写就是 48。") Optional<Integer> radius) {
    }

    // ---------------------------------------------------------------- calls

    @Fn("扫她附近的载具（沉浸载具 MTS 的车、飞机、船）：几辆、在哪、多快、谁在上面。")
    @Example("vehicles.vehicles.near()")
    @Example("vehicles.vehicles.near({radius = 96})")
    @Note("只读。默认半径 48 格，上限 96。只扫已加载区块；车停得远到没加载时扫不到。"
            + "上车要用 vehicles.vehicles.ride()，别用右键点车。")
    public static Fleet near(ServerCall call, RadiusOpt args) {
        int radius = clamp(args == null ? 48 : args.radius().orElse(48), MAX_RADIUS);
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        Object herWrapper = wrapperOf(call.her());
        Object riding = ridingOf(herWrapper);

        List<Vehicle> found = new ArrayList<>();
        for (Found f : scan(level, her, radius)) {
            Vehicle row = row(f, her, riding);
            if (row != null) {
                found.add(row);
            }
        }
        found.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        if (found.size() > MAX_ROWS) {
            found = new ArrayList<>(found.subList(0, MAX_ROWS));
        }

        int moving = 0;
        for (Vehicle v : found) {
            if (v.speed() > 0.5D) {
                moving++;
            }
        }
        String line;
        if (found.isEmpty()) {
            line = "附近 " + radius + " 格内没有载具";
        } else {
            line = "附近有 " + found.size() + " 辆载具，其中 " + moving + " 辆在动";
        }
        return new Fleet(found.size(), found, line);
    }

    @Fn("她身边最近的那一辆载具，含能不能上车。")
    @Example("vehicles.vehicles.here()")
    @Note("只读。默认找 32 格内最近的一辆；附近没有就报找不到。")
    public static Vehicle here(ServerCall call) {
        Nearest target = nearest(call, 32);
        return row(target.found(), call.her().m_20183_(), ridingOf(wrapperOf(call.her())));
    }

    @Fn("上车：走到最近的一辆车旁边，直接坐进一个空座位。别用右键点车——那样只会挂在车壳上，坐不进座位。")
    @Example("vehicles.vehicles.ride()")
    @Example("vehicles.vehicles.ride({radius = 8})")
    @Note("会动她的身体，会先过权限层（和玩家上车一样受领地保护）。要在车 6 格内；"
            + "已经在车上就先 leave。车锁着（MTS 的防盗锁）会上不去，要玩家先解锁。"
            + "上车后她自己不会开，开车要人来。")
    public static Pending<Ride> ride(ServerCall call, RadiusOpt args) {
        int radius = clamp(args == null ? 8 : args.radius().orElse(8), MAX_RADIUS);
        Object herWrapper = wrapperOf(call.her());
        Object riding = ridingOf(herWrapper);
        if (riding != null) {
            throw new ApiError(ErrorKind.FAILED, "她已经在车上了",
                    "先用 vehicles.vehicles.leave() 下车，再上别的车。", null);
        }
        Nearest target = nearest(call, radius);
        if (target.distance() > BOARD_REACH) {
            throw new ApiError(ErrorKind.OUT_OF_REACH,
                    "最近的车在 " + round(target.distance()) + " 格外（要多近才能上："
                            + round(BOARD_REACH) + " 格）",
                    "先走到车旁边：numen.move.to({x=…, y=…, z=…}, {arrive = \"use\"})。", null);
        }
        String name = nameOf(target.vehicle());
        return call.authorize(Action.useEntity(target.builder())).then(ignored -> {
            // A left-over vanilla link (she right-clicked the stand-in earlier) keeps her glued to
            // the car's origin and MTS never learns about it, so the seat stays "free". Step off
            // that first, otherwise the mount below is a no-op against a stale vehicle.
            if (call.her().m_20202_() != null) {
                call.her().m_8127_();
            }
            if (Boolean.TRUE.equals(lockedOf(target.vehicle()))) {
                throw new ApiError(ErrorKind.FAILED, name + " 锁着，上不去",
                        "这是 MTS 的防盗锁：让玩家右键解开车锁，之后她就能上。", null);
            }
            List<Object> seats = seatsOf(target.vehicle());
            Object seat = freeSeat(target.vehicle());
            if (seats.isEmpty()) {
                // A bare vehicle (spawned or given without seat parts) has nothing to sit on. Mounting
                // the body instead would only glue her to the shell, which is exactly the bug this
                // call exists to avoid, so say what is actually missing.
                throw new ApiError(ErrorKind.FAILED, name + " 没有装座椅，只是个车壳",
                        "让玩家给它装上座椅部件（车里按 G 打开零件面板，或拿座椅右键车身），"
                                + "装好后再叫她上车。", null);
            }
            if (seat == null) {
                throw new ApiError(ErrorKind.FAILED,
                        name + " 的座位都坐满了（一共 " + seats.size() + " 个）",
                        "让她等车上的人先下来，或者换一辆：vehicles.vehicles.near()。", null);
            }
            // The seat's own setRider is what a player's right-click runs: it registers her as the
            // seat's rider and, through her wrapper, spawns the linked seat and starts the ride.
            // Doing only half of that (wrapper.setRiding) depends on the next tick to finish it.
            seatOn(seat, herWrapper);
            if (ridingOf(herWrapper) == null) {
                mount(herWrapper, seat);
            }
            if (ridingOf(herWrapper) == null) {
                throw new ApiError(ErrorKind.FAILED, "没能上这辆车",
                        "座位可能被封了，或者车正被拆；让她站到车旁边再试一次。", null);
            }
            return new Ride(name, "座位上", "她上了" + name + "，坐在座位上；她不认路，开车要人来");
        });
    }

    @Fn("下车：从她现在坐着的载具上下来，站到车旁边。")
    @Example("vehicles.vehicles.leave()")
    @Note("会动她的身体，会先过权限层。她本来就没在车上时报错。"
            + "如果之前被右键挂在车壳上（不是座位），这个也会把她放下来。")
    public static Pending<Left> leave(ServerCall call) {
        ServerLevel level = levelOf(call);
        Object herWrapper = wrapperOf(call.her());
        Object riding = ridingOf(herWrapper);
        if (riding == null) {
            // glued to a stand-in instead of a seat: stepping off is not a world change
            if (call.her().m_20202_() != null) {
                call.her().m_8127_();
                return Pending.of(new Left("载具", "她下车了，站在车旁边"));
            }
            throw new ApiError(ErrorKind.FAILED, "她本来就没在车上", "不用下车。", null);
        }
        Object vehicle = vehicleOf(riding);
        Entity builder = builderFor(level, call.her().m_20183_(), vehicle);
        String name = nameOf(vehicle);
        if (builder == null) {
            // no stand-in to ask about: stepping off is not a world change, so just step off
            unseat(riding, herWrapper);
            return Pending.of(new Left(name, "她下车了，站在车旁边"));
        }
        return call.authorize(Action.useEntity(builder)).then(ignored -> {
            unseat(riding, herWrapper);
            if (ridingOf(herWrapper) != null || call.her().m_20202_() != null) {
                throw new ApiError(ErrorKind.FAILED, "没能下车",
                        "她可能被座位卡住了；再调一次，或者让玩家把她抱下来。", null);
            }
            return new Left(name, "她下车了，站在车旁边");
        });
    }

    // ---------------------------------------------------------------- finding

    /** A vanilla stand-in entity and the MTS vehicle it holds. */
    private record Found(Entity builder, Object vehicle) {
    }

    /** The closest vehicle plus its distance, so callers can compare against a reach limit. */
    private record Nearest(Found found, double distance) {

        Object vehicle() {
            return found.vehicle();
        }

        Entity builder() {
            return found.builder();
        }
    }

    private static Nearest nearest(ServerCall call, int radius) {
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();
        Found best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Found f : scan(level, her, clamp(radius, MAX_RADIUS))) {
            double d = distance(f.vehicle(), her);
            if (d < bestDistance) {
                bestDistance = d;
                best = f;
            }
        }
        if (best == null) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "附近 " + clamp(radius, MAX_RADIUS) + " 格内没有载具",
                    "带她走近一点，或者用 vehicles.vehicles.near({radius = 96}) 先看一眼。", null);
        }
        return new Nearest(best, bestDistance);
    }

    /** Every MTS vehicle whose stand-in sits inside the box around her. */
    private static List<Found> scan(ServerLevel level, BlockPos her, int radius) {
        AABB area = new AABB(her.m_123341_() - radius, her.m_123342_() - radius, her.m_123343_() - radius,
                her.m_123341_() + radius, her.m_123342_() + radius, her.m_123343_() + radius);
        List<Found> out = new ArrayList<>();
        for (Entity entity : level.m_45976_(Entity.class, area)) {
            if (entity == null || !entity.getClass().getName().startsWith(BUILDER_PACKAGE)) {
                continue;
            }
            Object vehicle = value(entity, "entity");
            if (vehicle != null && isVehicle(vehicle)) {
                out.add(new Found(entity, vehicle));
            }
        }
        return out;
    }

    private static boolean isVehicle(Object vehicle) {
        for (Class<?> c = vehicle.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            String name = c.getName();
            if (name.equals(VEHICLE_CLASS)) {
                return true;
            }
            if (!name.startsWith(MTS_PACKAGE)) {
                return false;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- reading

    private static Vehicle row(Found f, BlockPos her, Object riding) {
        Object vehicle = f.vehicle();
        double x = her.m_123341_();
        double y = her.m_123342_();
        double z = her.m_123343_();
        Object point = value(vehicle, "position");
        if (point != null) {
            Double px = asDouble(value(point, "x"));
            Double py = asDouble(value(point, "y"));
            Double pz = asDouble(value(point, "z"));
            if (px != null && py != null && pz != null) {
                x = px;
                y = py;
                z = pz;
            }
        }

        double speed = speedOf(vehicle);
        List<String> crew = crewOf(vehicle);
        int riders = crew.size();
        List<Object> seats = seatsOf(vehicle);
        int free = 0;
        for (Object seat : seats) {
            if (value(seat, "rider") == null) {
                free++;
            }
        }
        String driver = crew.isEmpty() ? "" : crew.get(0);
        int distance = (int) Math.round(Math.sqrt(
                (x - her.m_123341_()) * (x - her.m_123341_())
                        + (y - her.m_123342_()) * (y - her.m_123342_())
                        + (z - her.m_123343_()) * (z - her.m_123343_())));
        String name = nameOf(vehicle);
        boolean aboard = riding != null && riding == vehicle || (riding != null && vehicleOf(riding) == vehicle);

        String state = speed > 0.5D ? "在动，" + round(speed) + " 格/秒" : "停着";
        String who = riders == 0
                ? "上面没人"
                : (driver.isEmpty() ? riders + " 个人在上面" : driver + " 在开");
        boolean locked = Boolean.TRUE.equals(lockedOf(vehicle));
        String line = name + "：" + state + "，" + who + "，离 " + distance + " 格";
        if (aboard) {
            line = line + "（她就坐在这辆上）";
        }
        if (locked) {
            line = line + "；车锁着，上不去（要让玩家先解锁）";
        }
        return new Vehicle(name, fmt(x) + ", " + fmt(y) + ", " + fmt(z), speed, riders, seats.size(), free,
                driver, distance, aboard, locked, line);
    }

    /** Speed in blocks per second, measured from the vehicle's motion so the unit is unambiguous. */
    private static double speedOf(Object vehicle) {
        Object motion = value(vehicle, "motion");
        if (motion == null) {
            return 0.0D;
        }
        Double mx = asDouble(value(motion, "x"));
        Double my = asDouble(value(motion, "y"));
        Double mz = asDouble(value(motion, "z"));
        if (mx == null && my == null && mz == null) {
            return 0.0D;
        }
        double dx = mx == null ? 0.0D : mx;
        double dy = my == null ? 0.0D : my;
        double dz = mz == null ? 0.0D : mz;
        // MTS keeps motion in blocks per tick, same as vanilla; 20 ticks is one second.
        return Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 20.0D * 100.0D) / 100.0D;
    }

    /** Names of whoever sits in this vehicle, driver first. */
    private static List<String> crewOf(Object vehicle) {
        List<String> crew = new ArrayList<>();
        for (Object part : partsOf(vehicle)) {
            if (!isSeat(part)) {
                continue;
            }
            String who = nameOfRider(value(part, "rider"));
            if (!who.isEmpty()) {
                crew.add(who);
            }
        }
        if (crew.isEmpty()) {
            // vehicles without parts (boats, some trailers) keep the rider on themselves
            String who = nameOfRider(value(vehicle, "rider"));
            if (!who.isEmpty()) {
                crew.add(who);
            }
        }
        return crew;
    }

    /** Every seat part on this vehicle, in slot order (driver's seat first). */
    private static List<Object> seatsOf(Object vehicle) {
        List<Object> seats = new ArrayList<>();
        for (Object part : partsOf(vehicle)) {
            if (isSeat(part)) {
                seats.add(part);
            }
        }
        return seats;
    }

    /** A seat part with nobody in it, or null when every seat is taken (or there are none). */
    private static Object freeSeat(Object vehicle) {
        for (Object seat : seatsOf(vehicle)) {
            if (value(seat, "rider") == null) {
                return seat;
            }
        }
        return null;
    }

    private static List<Object> partsOf(Object vehicle) {
        Object parts = value(vehicle, "parts");
        List<Object> out = new ArrayList<>();
        if (parts instanceof Iterable<?> iterable) {
            for (Object part : iterable) {
                if (part != null) {
                    out.add(part);
                }
            }
        }
        return out;
    }

    private static boolean isSeat(Object part) {
        return part.getClass().getName().endsWith(".PartSeat");
    }

    private static String nameOfRider(Object rider) {
        if (rider == null) {
            return "";
        }
        try {
            Method getName = rider.getClass().getMethod("getName");
            Object name = getName.invoke(rider);
            return name instanceof String s ? s : "";
        } catch (Throwable t) {
            return "";
        }
    }

    private static String nameOf(Object vehicle) {
        if (vehicle == null) {
            return "载具";
        }
        Object definition = value(vehicle, "definition");
        if (definition != null) {
            Object pack = value(definition, "packID");
            Object system = value(definition, "systemName");
            if (system instanceof String systemName && !systemName.isEmpty()) {
                String packName = pack instanceof String p ? p : "";
                return packName.isEmpty() ? systemName : packName + ":" + systemName;
            }
        }
        String simple = vehicle.getClass().getSimpleName();
        return simple.isEmpty() ? "载具" : simple;
    }

    private static double distance(Object vehicle, BlockPos her) {
        Object point = value(vehicle, "position");
        if (point == null) {
            return Double.MAX_VALUE;
        }
        Double x = asDouble(value(point, "x"));
        Double y = asDouble(value(point, "y"));
        Double z = asDouble(value(point, "z"));
        if (x == null || y == null || z == null) {
            return Double.MAX_VALUE;
        }
        double dx = x - her.m_123341_();
        double dy = y - her.m_123342_();
        double dz = z - her.m_123343_();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---------------------------------------------------------------- riding

    /** MTS' wrapper around a player: {@code WrapperPlayer.getWrapperFor(Player)}. */
    private static Object wrapperOf(net.minecraft.world.entity.player.Player player) {
        try {
            Class<?> type = Class.forName(WRAPPER_PLAYER_CLASS);
            Method get = type.getMethod("getWrapperFor", net.minecraft.world.entity.player.Player.class);
            return get.invoke(null, player);
        } catch (Throwable t) {
            throw new ApiError(ErrorKind.FAILED,
                    "读不到她身上的载具接口：" + shortReason(t),
                    "沉浸载具的接口改名了，得改这个插件。", null);
        }
    }

    /** What she is sitting on right now, or null. It may be a seat part rather than the vehicle. */
    private static Object ridingOf(Object wrapper) {
        if (wrapper == null) {
            return null;
        }
        try {
            Method get = wrapper.getClass().getMethod("getEntityRiding");
            return get.invoke(wrapper);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Seat parts point at their vehicle ({@code vehicleOn}); parts point at {@code masterEntity}. */
    private static Object vehicleOf(Object riding) {
        if (riding == null) {
            return null;
        }
        for (String field : new String[] { "vehicleOn", "masterEntity", "entityOn" }) {
            Object owner = value(riding, field);
            if (owner != null) {
                return owner;
            }
        }
        return riding;
    }

    /**
     * The vanilla stand-in entity that carries this vehicle, so the permission layer can be asked
     * about the claim it stands in. She is sitting on it, so it is within a couple of blocks.
     */
    private static Entity builderFor(ServerLevel level, BlockPos her, Object vehicle) {
        AABB area = new AABB(her.m_123341_() - 2.0D, her.m_123342_() - 2.0D, her.m_123343_() - 2.0D,
                her.m_123341_() + 2.0D, her.m_123342_() + 2.0D, her.m_123343_() + 2.0D);
        for (Entity entity : level.m_45976_(Entity.class, area)) {
            if (entity == null || !entity.getClass().getName().startsWith(BUILDER_PACKAGE)) {
                continue;
            }
            Object held = value(entity, "entity");
            if (held != null && (held == vehicle || vehicleOf(held) == vehicle)) {
                return entity;
            }
        }
        return null;
    }

    private static void mount(Object wrapper, Object target) {
        try {
            Method set = wrapper.getClass().getMethod("setRiding",
                    Class.forName("minecrafttransportsimulator.entities.components.AEntityB_Existing"));
            set.invoke(wrapper, target);
        } catch (Throwable t) {
            throw new ApiError(ErrorKind.FAILED, "上/下车失败了：" + shortReason(t),
                    "沉浸载具的骑乘接口改名了，得改这个插件。", null);
        }
    }

    /**
     * Seat her the way a player's right-click does: the seat takes her as its rider and, through
     * her wrapper, spawns the linked seat entity and starts the vanilla ride. Returns whether the
     * seat accepted her (it refuses when somebody else is already in it).
     */
    private static boolean seatOn(Object seat, Object wrapper) {
        try {
            Class<?> iface = Class.forName(IWRAPPER_ENTITY);
            Method set = seat.getClass().getMethod("setRider", iface, boolean.class);
            return Boolean.TRUE.equals(set.invoke(seat, wrapper, Boolean.TRUE));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Step her off a seat (or a vehicle) the way MTS does, with the plain wrapper as fallback. */
    private static void unseat(Object riding, Object wrapper) {
        try {
            Method remove = riding.getClass().getMethod("removeRider");
            remove.invoke(riding);
        } catch (Throwable ignored) {
            // older/newer MTS: fall through to the wrapper half
        }
        if (ridingOf(wrapper) != null) {
            mount(wrapper, null);
        }
    }

    /** MTS' anti-theft lock on a vehicle; null when this vehicle has no lock variable. */
    private static Boolean lockedOf(Object vehicle) {
        Object locked = value(vehicle, "lockedVar");
        if (locked == null) {
            return null;
        }
        Object active = value(locked, "isActive");
        return active instanceof Boolean flag ? flag : null;
    }

    // ---------------------------------------------------------------- plumbing

    private static ServerLevel levelOf(ServerCall call) {
        if (call.her().m_9236_() instanceof ServerLevel level) {
            return level;
        }
        throw new ApiError(ErrorKind.FAILED, "这里不是服务端世界",
                "载具只有服务端能读；她在服务端，正常不会遇到。", null);
    }

    private static int clamp(int radius, int max) {
        return Math.max(1, Math.min(max, radius));
    }

    private static long round(double value) {
        return Math.round(value);
    }

    private static String fmt(double value) {
        return String.valueOf(Math.round(value));
    }

    private static String shortReason(Throwable t) {
        Throwable cause = t;
        for (int depth = 0; depth < 6 && cause.getCause() != null; depth++) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        String type = cause.getClass().getSimpleName();
        return message == null || message.isEmpty() ? type : type + ": " + message;
    }

    /** Reads a public (or declared) field by name from the class hierarchy. */
    private static Object value(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Field field = fieldOf(target.getClass(), fieldName, null);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field fieldOf(Class<?> type, String fieldName, Class<?> fieldType) {
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field field = c.getDeclaredField(fieldName);
                if (fieldType != null && !fieldType.isAssignableFrom(field.getType())) {
                    continue;
                }
                if (!field.isAccessible()) {
                    field.setAccessible(true);
                }
                return field;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static Double asDouble(Object raw) {
        if (raw instanceof Number number) {
            return number.doubleValue();
        }
        return null;
    }
}
