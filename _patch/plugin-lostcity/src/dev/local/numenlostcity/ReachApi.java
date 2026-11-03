package dev.local.numenlostcity;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Which side of a container can a body stand on, and which side did the last one of this block type
 * work from.
 *
 * <p>Walking to a container is the engine's own job: {@code numen.move.to(容器格, {arrive = "use"})}
 * plans the stand cell from which one face of that block is visible and clickable. What the engine
 * does not answer is "is this container openable at all" — six blocked faces make the walk a wasted
 * trip — and it forgets, per block type, which face worked last time. Those two answers are what this
 * group adds: {@code plan} says openable-or-not plus the exact face, {@code ok} remembers the face by
 * block id. There is no one-call {@code open}: walking and clicking belong to the engine
 * ({@code numen.move.to} + {@code numen.use.block}), so the chain is written out in the example.
 */
public final class ReachApi {

    private ReachApi() {}

    private static Path cacheFile;
    private static Map<String, String> cache;

    static void install(NumenApi numen) {
        cacheFile = numen.configDir().resolve("reach-cache.json");
        numen.api("reach",
                "开容器前先问该站哪一面：只给一个能站人的面，开成功一次就按方块类型记住，下次不用再试",
                ReachFn.class);
    }

    // 位置必须装在一个 record 里（引擎只认 record 做参数），里面的 BlockPos 才吃 {x=…, y=…, z=…}。
    // 三个必填 int 的写法按位置传，文档里印的 {x = …, y = …, z = …} 会报 "argument 'y' is missing"。
    @Doc("一个方块")
    public record Cell(@Doc("方块的位置：{x = …, y = …, z = …}，或者 numen.scan.blocks 交回的那个方块") BlockPos cell) {}

    @Doc("该站哪一面")
    public record Spot(
            @Doc("false = 六个面都被挡着，这一格现在开不了（换一侧或先挖开）") boolean ok,
            @Doc("引擎算出来的站位 X，只是信息；走路别拿它当目标——把容器那格交给 numen.move.to(容器, {arrive = \"use\"})") int x,
            @Doc("站位 Y（脚的位置）") int y,
            @Doc("站位 Z") int z,
            @Doc("朝哪一面：north/south/east/west/up/down") String face,
            @Doc("true = 这类方块以前成功过，直接照这个面站，不要再试别的面") boolean known,
            @Doc("所有能站人的面（一般只有一个）") List<String> exposed,
            @Doc("提示；没有可站的面时说明怎么办") Optional<String> note
    ) {}

    @Doc("记住这次成功的那一面")
    public record Ok(
            @Doc("true = 记住了，下次同类方块直接用") boolean learnt,
            @Doc("记下的面：north/south/east/west/up/down") String face,
            @Doc("现在一共有几种方块记住了面") int cached
    ) {}

    public static final class ReachFn {

        @Fn("问这个容器开得了吗、该站哪一面——六个面全挡着就先别走过去")
        @Example("if lostcity.reach.plan({x = 512, y = 64, z = -640}).ok then\n  numen.move.to({x = 512, y = 64, z = -640}, {arrive = 'use'})\n  if numen.use.block({x = 512, y = 64, z = -640}).menu then\n    lostcity.reach.ok({x = 512, y = 64, z = -640})\n  end\nend")
        @Note("只读，不改世界；known = true 时不要再绕圈试别的面")
        @Note("走路用 arrive = 'use'，目标是容器那一格（不是 s.x/s.y/s.z 那个站位），也不能配 range——配了会报 bad_argument，一步都走不了")
        @Note("没有「一步做完」的函数：上面那条例子就是全部步骤（问面 → move.to → use.block → ok），照抄即可")
        @Note("六个面都被挡着时 ok = false，说明这格现在开不了：换一侧、挖开一格，或者放弃这个容器")
        @SeeAlso({"numen.move.to", "numen.use.block", "numen.gui.view", "lostcity.loot.containers"})
        public static Spot plan(ServerCall call, Cell pos) {
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            return spot(level, pos.cell(), her.m_20185_(), her.m_20186_(), her.m_20189_());
        }

        @Fn("刚成功开了一个容器——把用的那一面按方块类型记下来，下一个一样的容器直接站这一面")
        @Example("lostcity.reach.ok({x = 512, y = 64, z = -640})")
        @Note("只在真的打开成功（界面里出现过东西）之后调；没打开就别记，免得记错面")
        @SeeAlso({"reach.plan"})
        public static Ok ok(ServerCall call, Cell pos) {
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            Direction face = nearestFace(pos.cell(), her.m_20185_(), her.m_20186_(), her.m_20189_());
            Map<String, String> map = cache();
            map.put(blockId(level, pos.cell()), face.m_7912_());
            save(map);
            return new Ok(true, face.m_7912_(), map.size());
        }
    }

    // ---------------------------------------------------------------- geometry

    private static Spot spot(ServerLevel level, BlockPos at, double px, double py, double pz) {
        String block = blockId(level, at);
        String remembered = cache().get(block);

        List<Direction> exposed = new ArrayList<>();
        for (Direction d : Direction.values()) {
            if (canStand(level, at.m_121945_(d))) {
                exposed.add(d);
            }
        }
        List<String> names = new ArrayList<>();
        for (Direction d : exposed) {
            names.add(d.m_7912_());
        }
        if (exposed.isEmpty()) {
            return new Spot(false, 0, 0, 0, "", false, names, Optional.of(
                    "六个面都被挡着：从另一侧找开口、挖开一格再开，或放弃这个容器——别绕着它转圈"));
        }

        Direction pick = null;
        boolean known = false;
        if (remembered != null) {
            for (Direction d : exposed) {
                if (d.m_7912_().equals(remembered)) {
                    pick = d;
                    known = true;
                    break;
                }
            }
        }
        if (pick == null) {
            double best = Double.MAX_VALUE;
            for (Direction d : exposed) {
                BlockPos stand = at.m_121945_(d);
                double dx = px - (stand.m_123341_() + 0.5D);
                double dy = py - stand.m_123342_();
                double dz = pz - (stand.m_123343_() + 0.5D);
                double dist = dx * dx + dy * dy + dz * dz;
                if (dist < best) {
                    best = dist;
                    pick = d;
                }
            }
        }
        if (pick == null) {
            return new Spot(false, 0, 0, 0, "", false, names, Optional.of("找不到能站的面"));
        }
        BlockPos stand = at.m_121945_(pick);
        return new Spot(true, stand.m_123341_(), stand.m_123342_(), stand.m_123343_(),
                pick.m_7912_(), known, names, Optional.empty());
    }

    private static boolean canStand(ServerLevel level, BlockPos stand) {
        return passable(level, stand) && passable(level, stand.m_6630_(1));
    }

    private static boolean passable(ServerLevel level, BlockPos pos) {
        BlockState state = level.m_8055_(pos);
        if (state.m_60795_()) {
            return true;
        }
        try {
            return state.m_60812_(level, pos).m_83281_();
        } catch (Throwable t) {
            return false;
        }
    }

    private static Direction nearestFace(BlockPos at, double px, double py, double pz) {
        double dx = px - (at.m_123341_() + 0.5D);
        double dy = py - (at.m_123342_() + 0.5D);
        double dz = pz - (at.m_123343_() + 0.5D);
        double ax = Math.abs(dx);
        double az = Math.abs(dz);
        if (Math.abs(dy) > Math.max(ax, az)) {
            return dy >= 0 ? Direction.UP : Direction.DOWN;
        }
        if (ax >= az) {
            return dx >= 0 ? Direction.EAST : Direction.WEST;
        }
        return dz >= 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static String blockId(ServerLevel level, BlockPos at) {
        try {
            return String.valueOf(ForgeRegistries.BLOCKS.getKey(level.m_8055_(at).m_60734_()));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static ServerLevel level(NumenPlayer her) {
        if (her.m_9236_() instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        throw new ApiError(ErrorKind.FAILED, "她不在一个服务端世界里", null);
    }

    // ---------------------------------------------------------------- cache

    private static Map<String, String> cache() {
        if (cache != null) {
            return cache;
        }
        Map<String, String> map = new LinkedHashMap<>();
        try {
            if (cacheFile != null && Files.isRegularFile(cacheFile)) {
                JsonElement root = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8));
                if (root.isJsonObject()) {
                    for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                        map.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (Throwable ignored) {
            // A broken cache only costs one extra probe.
        }
        cache = map;
        return cache;
    }

    private static void save(Map<String, String> map) {
        try {
            if (cacheFile == null) {
                return;
            }
            JsonObject root = new JsonObject();
            for (Map.Entry<String, String> e : map.entrySet()) {
                root.addProperty(e.getKey(), e.getValue());
            }
            Files.createDirectories(cacheFile.getParent());
            Files.writeString(cacheFile, root.toString(), StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            // Remembering is a convenience; failing to write must never break opening a container.
        }
    }
}
