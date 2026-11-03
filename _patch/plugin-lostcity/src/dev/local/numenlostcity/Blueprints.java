package dev.local.numenlostcity;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.server.level.ServerLevel;

/**
 * Remembers the layout of a building so the second visit does not need a fresh survey.
 *
 * <p>Lost Cities repeats a handful of building types all over the city, so the expensive part of
 * looting is not walking, it is *working out which floor has what*. A blueprint is that work saved
 * to disk: container and spawner offsets relative to the building's own chunk corner, which is
 * stable for every copy of the same building.
 */
public final class Blueprints {

    private static Path dir;
    private static final float[] NOTHING = new float[0];

    private Blueprints() {
    }

    static void init(NumenApi numen) {
        try {
            dir = numen.configDir().resolve("lostcity").resolve("blueprints");
            Files.createDirectories(dir);
        } catch (Throwable t) {
            dir = null;
        }
    }

    static boolean has(String scope) {
        if (dir == null || scope == null || scope.equals("wild")) {
            return false;
        }
        return Files.isRegularFile(file(scope));
    }

    private static Path file(String name) {
        return dir.resolve(safe(name) + ".txt");
    }

    static String safe(String raw) {
        StringBuilder b = new StringBuilder();
        for (char c : raw.toLowerCase().toCharArray()) {
            b.append((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' ? c : '_');
        }
        String s = b.toString();
        return s.length() > 90 ? s.substring(0, 90) : s;
    }

    /** Building id at a position, or the site key when it has no id. */
    static String nameAt(ServerLevel level, double px, double pz) {
        try {
            int cx = ((int) Math.floor(px)) >> 4;
            int cz = ((int) Math.floor(pz)) >> 4;
            LostCityApi.At at = CityBridge.here(level, px, pz);
            if (at != null && at.id() != null && !at.id().isEmpty()) {
                return at.id();
            }
            return CityBridge.site(CityBridge.info(level), cx, cz);
        } catch (Throwable t) {
            return null;
        }
    }

    private static int cornerOf(double coord) {
        return ((int) Math.floor(coord)) & ~15;
    }

    @Doc("一份建筑蓝图")
    public record Blueprint(
            @Doc("找到了吗") boolean found,
            @Doc("蓝图名（建筑的资源 id，例如 lostcities:building_office1a）") String name,
            @Doc("这份蓝图被用过几次") int times,
            @Doc("记下来的容器数") int containers,
            @Doc("记下来的刷怪笼数") int spawners,
            @Doc("下一步怎么办（照抄即可）") String note,
            @Doc("按楼层从下往上的容器坐标（已经换算成这栋楼里的绝对坐标）") List<LostCityApi.Step> route,
            @Doc("刷怪笼坐标，先挖这些") List<LostCityApi.Spawn> clearFirst
    ) {}

    @Doc("蓝图参数")
    public record BpArgs(
            @Doc("蓝图名；不写就用你脚下这栋楼的建筑 id") @Omitted("用当前建筑") Optional<String> name
    ) {}

    @Doc("有哪些蓝图")
    public record BpList(
            @Doc("几份") int count,
            @Doc("名字，带容器数，例如 lostcities:building_office1a (6)") List<String> items
    ) {}

    public static final class BpFns {

        private BpFns() {
        }

        @Fn("把这栋楼的布局记成蓝图（每栋楼第一次探的时候调一次；同类型建筑以后就不用再探了）")
        @Example("local b = lostcity.blueprint.save()\nprint(b.name, b.containers, '个容器', b.spawners, '个笼子')")
        @Note("只读世界、只写一份文本文件；不搬东西")
        @Note("蓝图记的是「相对这栋楼自己的区块角」的坐标，所以同一类型建筑的每一份都能用")
        @SeeAlso({"lostcity.blueprint.get", "lostcity.loot.plan"})
        public static Blueprint save(ServerCall call, BpArgs args) {
            if (dir == null) {
                throw new ApiError(ErrorKind.FAILED, "蓝图存不进去：写不了 config/numen/lostcity/blueprints",
                        "告诉主人这个目录不可写（插件没有权限），她这次只能临场扫");
            }
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            double px = her.m_20185_();
            double py = her.m_20186_();
            double pz = her.m_20189_();
            LostCityApi.At at = CityBridge.here(level, px, pz);
            if (at == null || at.id().isEmpty()) {
                throw new ApiError(ErrorKind.NOT_FOUND,
                        "她站的这块不是一栋楼（这里是" + (at == null ? "读不到" : at.kind()) + "），记了也用不上",
                        "先走进建筑里，用 lostcity.building.here() 看到 kind = building、name 不是空，再记");
            }
            String name = args.name().orElseGet(() -> at.id());
            String scope = CityBridge.site(CityBridge.info(level),
                    ((int) Math.floor(px)) >> 4, ((int) Math.floor(pz)) >> 4);
            // 一栋楼 = 一块地皮，而且分地皮必须发生在「只留最近 60 个」之前：以前这里传 null、事后再
            // 按 site 过滤，隔壁楼离得近的箱子会先把她这栋的挤出去，记下来的蓝图是缺的。
            // 扫描半径按这栋楼的实际大小取（以前写死 40，5x5 的大楼侧翼根本扫不到）。
            int plot = Math.max(40, CityBridge.plotRadius(level, px, pz));
            LostCityApi.Loot scan = CityBridge.loot(level, px, py, pz, plot, null, 64, false, scope);
            // 区块没加载时什么都看不见：这时候写出来的是空壳，下次照着走会以为这栋楼没箱子。
            if (scan.chunks() == 0) {
                throw new ApiError(ErrorKind.FAILED,
                        "这栋楼周围的区块还没加载，现在记只会记成一份空蓝图（0 个容器）",
                        "先在里面走一圈让区块出来，用 lostcity.loot.containers 看到 chunks 大于 0 再记");
            }
            if (!scan.scope().isEmpty()) {
                scope = scan.scope();
            }
            // 坐标按这栋楼自己的原点量：同一类型的每一份副本都能用。以前的 cornerOf(px) 是「她站的
            // 那个区块的角」，跨区块大建筑里换个区块站着，整份蓝图就整体偏移。
            int[] anchor = CityBridge.anchor(level, px, pz);
            int ox = anchor[0];
            int oy = anchor[1] + 1;
            int oz = anchor[2];
            StringBuilder out = new StringBuilder();
            out.append("# ").append(name).append('\n');
            out.append("# anchor ").append(ox).append(' ').append(oy).append(' ').append(oz).append('\n');
            // 先按「楼层从下往上、同层先近」排序：写进文件的和回给她的路线用同一个顺序。
            List<LostCityApi.Box> recorded = new ArrayList<>(scan.containers());
            recorded.sort(Comparator.comparingInt(LostCityApi.Box::y)
                    .thenComparingDouble(b -> Math.sqrt(
                            (b.x() + 0.5 - px) * (b.x() + 0.5 - px) + (b.z() + 0.5 - pz) * (b.z() + 0.5 - pz))));
            int boxes = 0;
            for (LostCityApi.Box b : recorded) {
                out.append('C').append(' ')
                        .append(b.x() - ox).append(' ').append(b.y() - oy).append(' ').append(b.z() - oz)
                        .append(' ').append(b.block() == null ? "?" : b.block()).append('\n');
                boxes++;
            }
            List<LostCityApi.Spawn> recordedSpawners = new ArrayList<>(scan.spawners());
            recordedSpawners.sort(Comparator.comparingInt(LostCityApi.Spawn::y)
                    .thenComparingDouble(s -> Math.sqrt(
                            (s.x() + 0.5 - px) * (s.x() + 0.5 - px) + (s.z() + 0.5 - pz) * (s.z() + 0.5 - pz))));
            int spawners = 0;
            for (LostCityApi.Spawn s : recordedSpawners) {
                out.append('S').append(' ')
                        .append(s.x() - ox).append(' ').append(s.y() - oy).append(' ').append(s.z() - oz)
                        .append(' ').append(s.mob() == null ? "?" : s.mob()).append('\n');
                spawners++;
            }
            try {
                Files.writeString(file(name), out.toString(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new ApiError(ErrorKind.FAILED, "蓝图写盘失败：" + e.getMessage(),
                        "告诉主人这件事，别自己重试第二遍", e.getMessage());
            }
            // 刚记完就把路线一起给她：以前这里回的是两个空表，而 route 的文档写的是「按楼层从下往上的
            // 容器坐标」——记完还得再 get() 一次才有坐标，白跑一趟。
            List<LostCityApi.Step> route = new ArrayList<>();
            double rx = px;
            double ry = py;
            double rz = pz;
            int order = 0;
            for (LostCityApi.Box b : recorded) {
                double dx = rx - b.x();
                double dyy = ry - b.y();
                double dz = rz - b.z();
                double d = Math.sqrt(dx * dx + dyy * dyy + dz * dz);
                route.add(new LostCityApi.Step(++order, b.x(), b.y(), b.z(), b.block(), b.empty(), b.lootr(),
                        Math.round(d * 10.0) / 10.0));
                rx = b.x();
                ry = b.y();
                rz = b.z();
            }
            return new Blueprint(true, name, 1, boxes, spawners,
                    "记下了。以后进同一类型的楼，先调 lostcity.blueprint.get 直接拿坐标，不用重新扫；"
                            + "这里的 route 就是刚记下来的顺序（先下后上）。",
                    route, recordedSpawners);
        }

        @Fn("拿一份蓝图：同一类型的楼走进去就能直接按坐标搜，不用重新规划")
        @Example("local b = lostcity.blueprint.get()\nif b.found then\n  for _, s in ipairs(b.route) do print(s.order, s.x, s.y, s.z) end\nend")
        @Note("名字不写就用你脚下这栋楼；换成别的楼就把名字写上")
        @Note("蓝图是上次的布局，箱子的空/满它不知道——到了还是要用 loot.containers 或直接开")
        @SeeAlso({"lostcity.blueprint.save", "lostcity.loot.plan", "numen.use.block"})
        public static Blueprint get(ServerCall call, BpArgs args) {
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            double px = her.m_20185_();
            double py = her.m_20186_();
            double pz = her.m_20189_();
            String name = args.name().orElseGet(() -> nameAt(level, px, pz));
            if (name == null || dir == null) {
                return miss("这里没有对应的蓝图；先在这栋楼里调一次 lostcity.blueprint.save");
            }
            Path f = file(name);
            if (!Files.isRegularFile(f)) {
                return miss("没记过 " + name + "；在里面调一次 lostcity.blueprint.save 就有了");
            }
            List<String> lines;
            try {
                lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            } catch (IOException e) {
                return miss("读不了 " + name + "：" + e.getMessage());
            }
            int[] anchor = CityBridge.anchor(level, px, pz);
            int ox = anchor[0];
            int oy = anchor[1] + 1;
            int oz = anchor[2];
            List<LostCityApi.Step> route = new ArrayList<>();
            List<LostCityApi.Spawn> spawn = new ArrayList<>();
            int order = 0;
            for (String line : lines) {
                if (line.length() < 6 || line.charAt(0) == '#') {
                    continue;
                }
                String[] f2 = line.trim().split(" ");
                if (f2.length < 4) {
                    continue;
                }
                int bx;
                int by;
                int bz;
                try {
                    bx = Integer.parseInt(f2[1]) + ox;
                    by = Integer.parseInt(f2[2]) + oy;
                    bz = Integer.parseInt(f2[3]) + oz;
                } catch (NumberFormatException e) {
                    continue;
                }
                if (f2[0].equals("C")) {
                    route.add(new LostCityApi.Step(++order, bx, by, bz, f2.length > 4 ? f2[4] : "?", false, false, 0.0));
                } else if (f2[0].equals("S")) {
                    spawn.add(new LostCityApi.Spawn(bx, by, bz, f2.length > 4 ? f2[4] : "",
                            CityBridge.mobName(f2.length > 4 ? f2[4] : ""), 16, false, ""));
                }
            }
            int times = readTimes(name) + 1;
            writeTimes(name, times);
            return new Blueprint(true, name, times, route.size(), spawn.size(),
                    "这是上次记的布局，直接按 route 的 order 走；每站先用 loot.containers 看一眼实际空满",
                    route, spawn);
        }

        @Fn("我已经记住哪些楼的布局了")
        @Example("local l = lostcity.blueprint.list()\nfor _, n in ipairs(l.items) do print(n) end")
        @Note("只读")
        public static BpList list(ServerCall call) {
            List<String> items = new ArrayList<>();
            if (dir != null) {
                try (var stream = Files.list(dir)) {
                    stream.filter(p -> {
                        String n = p.getFileName().toString();
                        return n.endsWith(".txt") && !n.endsWith("__times.txt");
                    }).forEach(p -> {
                        String fn = p.getFileName().toString();
                        items.add(fn.substring(0, fn.length() - 4) + " (" + countLines(p) + ")");
                    });
                } catch (Throwable ignored) {
                    // unreadable directory: report what we have
                }
            }
            items.sort(Comparator.naturalOrder());
            return new BpList(items.size(), items);
        }
    }

    private static Blueprint miss(String note) {
        return new Blueprint(false, "", 0, 0, 0, note, List.of(), List.of());
    }

    private static int countLines(Path p) {
        try {
            int n = 0;
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                if (!line.isEmpty() && line.charAt(0) == 'C') {
                    n++;
                }
            }
            return n;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int readTimes(String name) {
        Path f = file(name + "__times");
        try {
            if (Files.isRegularFile(f)) {
                return Integer.parseInt(Files.readString(f, StandardCharsets.UTF_8).trim());
            }
        } catch (Throwable ignored) {
            // first use
        }
        return 0;
    }

    private static void writeTimes(String name, int times) {
        try {
            Path f = file(name + "__times");
            Files.writeString(f, Integer.toString(times), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // usage counter is a nicety
        }
    }

    private static ServerLevel level(NumenPlayer her) {
        return (ServerLevel) her.m_9236_();
    }
}
