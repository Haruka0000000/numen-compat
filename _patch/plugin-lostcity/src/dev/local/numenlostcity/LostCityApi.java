package dev.local.numenlostcity;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Job;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.TaskFactory;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.server.level.ServerLevel;

/**
 * Numen plugin for Lost Cities (and the building packs DeceasedCraft adds on top of it).
 *
 * <p>The engine's own {@code numen.locate.structure} only knows vanilla structure ids, and
 * {@code numen.scan.blocks} needs the exact block id of every chest-like block in a 430 mod pack.
 * This plugin answers the three questions a companion actually asks in a ruined city:
 * <ul>
 *   <li>什么楼 — the building under her feet, its floors, its cellars, which floor is ruined;</li>
 *   <li>哪有楼 — nearest hospital / military base / police station / store … by name;</li>
 *   <li>哪有箱子 — every container and mob spawner around her, found by block-entity type and item
 *       capability instead of a hard-coded id list.</li>
 * </ul>
 */
public final class LostCityApi {

    private LostCityApi() {}

    static void install(NumenApi numen) {
        numen.api("building",
                "失落的城市：我在哪栋楼里、这栋楼层层高、几层地下室、哪一层已经被废墟化",
                BuildingFn.class);
        numen.api("find",
                "失落的城市：找最近的医院/军营/警局/仓库/加油站等建筑，或列出这个整合包注册了哪些建筑类型",
                FindFn.class);
        numen.api("loot",
                "失落的城市：一次列出周围所有箱子、容器、机器和刷怪笼（不用先知道方块 id）",
                LootFn.class);
        numen.api("stairs",
                "失落的城市：楼梯/梯子/脚手架在哪——上楼走它，别去右键活板门（那多是护栏）",
                StairsFn.class);
        // reach 组在 ReachApi.install 里注册（一个组只能注册一次，重复注册会盖掉先注册的那份），
        // 所以这里必须真的调一次——上一轮只留了这句话、没调，整组 lostcity.reach 就消失了。
        ReachApi.install(numen);
        numen.api("multi",
                "失落的城市：跨区块大建筑（社区中心、赌场、学校……）横竖各跨几个区块——别自己数",
                MultiFn.class);
        Blueprints.init(numen);
        numen.api("blueprint",
                "失落的城市：把探过的楼记成蓝图，同一类型的楼下次照着走（省掉重新规划的时间）",
                Blueprints.BpFns.class);
        LearnApi.init(numen);
        numen.api("learn",
                "自己的经验库：把教训记下来、读回来，还能自己写一份技能（SKILL.md）固化工作流",
                LearnApi.SelfFns.class);
        registerSweepTask();
    }

    /** 只登记一次：热重载时插件会被重新构造，重复登记会抛。 */
    private static boolean sweepRegistered;

    private static synchronized void registerSweepTask() {
        if (sweepRegistered) {
            return;
        }
        sweepRegistered = true;
        try {
            TaskFactory.register(SweepRecord.class,
                    (bot, rec) -> new SweepTask(bot, (SweepRecord) rec));
        } catch (Throwable t) {
            sweepRegistered = false;
        }
    }

    // ---------------------------------------------------------------- result records

    @Doc("我在失落的城市里的位置与所在建筑的结构")
    public record At(
            @Doc("building = 在建筑里，street = 在城市街道上，wilderness = 城市外") String kind,
            @Doc("建筑类型名（资源包注册名，例如 building_office1a 或 multi_hospital_2）；街道/荒野为空") String name,
            @Doc("这栋楼的中文名，例如「加油站」「社区中心」；整合包没给名字时为空") String nameCn,
            @Doc("这栋楼的英文原名，例如 Gas Station；没有时为空") String nameEn,
            @Doc("建筑资源 id，例如 lostcities:building_office1a") String id,
            @Doc("城市风格名，例如 standard / wasteland") String style,
            @Doc("所在区块坐标") int chunkX,
            @Doc("所在区块坐标") int chunkZ,
            @Doc("这个区块中心的方块 X") int x,
            @Doc("这个区块中心的方块 Z") int z,
            @Doc("城市层号：0 是这一带的最低层，建筑里就是第一层的层号") int cityLevel,
            @Doc("第一层楼板的方块 Y（可走动的空腔在它 +1 处）") int groundY,
            @Doc("地上楼层数") int floors,
            @Doc("地下室层数") int cellars,
            @Doc("废墟化从第几层开始（地上，1 起）；-1 = 这栋楼没有被废墟化") int ruinLevel,
            @Doc("废墟开始那一层的绝对 Y（游戏里看到的那种七十多的坐标）；-1 = 没有被废墟化") int ruinY,
            @Doc("若是跨区块的大建筑，这里是它的资源 id，例如 lostcities:multi_hospital；普通建筑为空") String multi,
            @Doc("这栋大建筑横跨几个区块；普通建筑是 1") int multiW,
            @Doc("这栋大建筑竖跨几个区块；普通建筑是 1") int multiH,
            @Doc("我在它的网格里是第几列（0 起）；普通建筑是 0") int multiX,
            @Doc("我在它的网格里是第几行（0 起）；普通建筑是 0") int multiZ,
            @Doc("每一层楼内部可站立的 Y 坐标，从下到上") List<Integer> floorsY
    ) {}

    @Doc("按世界坐标查这个位置是什么建筑")
    public record Pos(
            @Doc("方块 X") int x,
            @Doc("方块 Z") int z
    ) {}

    @Doc("找建筑的参数")
    public record Find(
            @Doc("建筑名里包含这个词（英文、不区分大小写），例如 hospital / military / police / office / store / warehouse / gas / lab / school") @Omitted("所有建筑类型") Optional<String> match,
            @Doc("在半径多少个区块内找（1-24）") @Omitted("8 个区块") Optional<Integer> radiusChunks,
            @Doc("最多返回几个（1-32）") @Omitted("8 个") Optional<Integer> limit
    ) {}

    @Doc("按距离排好的一串建筑")
    public record Found(
            @Doc("一共查了多少个区块") int scanned,
            @Doc("匹配到的不同建筑类数量（同一栋楼的四个朝向已经合并成一条）") int total,
            @Doc("按距离从近到远，最多 limit 条") List<Hit> items
    ) {}

    @Doc("一栋建筑")
    public record Hit(
            @Doc("建筑类型名") String name,
            @Doc("这栋楼的中文名，例如「加油站」；整合包没给名字时为空") String nameCn,
            @Doc("这栋楼的英文原名") String nameEn,
            @Doc("建筑资源 id") String id,
            @Doc("区块坐标") int chunkX,
            @Doc("区块坐标") int chunkZ,
            @Doc("区块中心的方块 X") int x,
            @Doc("区块中心的方块 Z") int z,
            @Doc("离我多少格") double distance,
            @Doc("地上楼层数") int floors,
            @Doc("跨区块大建筑的 id；普通建筑为空") String multi,
            @Doc("这栋大建筑横跨几个区块；普通建筑是 1") int multiW,
            @Doc("这栋大建筑竖跨几个区块；普通建筑是 1") int multiH
    ) {}

    @Doc("失落的城市里注册的全部「跨区块大建筑」及各自尺寸")
    public record Multis(
            @Doc("数量") int count,
            @Doc("按区块数从大到小") List<MultiItem> items
    ) {}

    @Doc("一栋跨区块大建筑")
    public record MultiItem(
            @Doc("简名，例如 multi_communitycenter") String name,
            @Doc("中文名，例如「社区中心」；整合包没给名字时为空") String nameCn,
            @Doc("资源 id，例如 deceasedcraft:multi_communitycenter") String id,
            @Doc("横跨几个区块") int dimX,
            @Doc("竖跨几个区块") int dimZ,
            @Doc("一共几个区块 = dimX × dimZ") int chunks
    ) {}

    @Doc("按名字查一栋跨区块大建筑的尺寸")
    public record MultiQuery(
            @Doc("名字里包含这个词（英文、不区分大小写），例如 community / casino / school / hospital") String match
    ) {}

    @Doc("一栋跨区块大建筑的尺寸")
    public record MultiSize(
            @Doc("找到了吗") boolean found,
            @Doc("简名") String name,
            @Doc("中文名，例如「社区中心」；没有时为空") String nameCn,
            @Doc("横跨几个区块") int dimX,
            @Doc("竖跨几个区块") int dimZ,
            @Doc("一共几个区块 = dimX × dimZ") int chunks
    ) {}

    @Doc("这个整合包注册的建筑类型")
    public record Types(
            @Doc("数量") int count,
            @Doc("类型名，从短到长排（短的通常是通用类）") List<String> names
    ) {}

    @Doc("找建筑类型名的过滤条件")
    public record TypeFilter(
            @Doc("只列名字里包含这个词的类型") @Omitted("列出全部") Optional<String> match
    ) {}

    @Doc("搜刮参数")
    public record Look(
            @Doc("水平半径（格，4-64）") @Omitted("24 格") Optional<Integer> radius,
            @Doc("中心高度（方块 Y）") @Omitted("用我现在的 Y") Optional<Integer> y,
            @Doc("上下各扫多少格（0-64）") @Omitted("32 格：一栋楼常常跨好几层，扫太浅就漏箱子") Optional<Integer> dy,
            @Doc("true = 只要还没被搜过的（空容器不返回）") @Omitted("false，空的也返回并标出来") Optional<Boolean> unlooted,
            @Doc("true = 连隔壁楼的容器一起返回（默认只给脚下这一栋：半径一扫会把整条街的楼混进来）")
            @Omitted("false，只看自己这栋楼") Optional<Boolean> all_plots
    ) {}

    @Doc("周围的容器与刷怪笼")
    public record Loot(
            @Doc("扫了几个已加载的区块（没加载的区块不算，所以别在没去过的地方查）") int chunks,
            @Doc("容器，按距离从近到远") List<Box> containers,
            @Doc("刷怪笼，按距离从近到远") List<Spawn> spawners,
            @Doc("容器太多被截断了吗（只留最近的 60 个）") boolean truncated,
            @Doc("我脚下这栋楼的地皮（chunk:x,z / multi:建筑id:原点 / wild=城外）；返回的容器都属于它") String scope,
            @Doc("被范围约束滤掉的隔壁楼容器数量（>0 说明旁边还有楼，别顺手跑过去）") int otherPlots,
            @Doc("补充说明；只有「一个区块都没加载」这种要紧情况才有字") @Omitted("空，一切正常") String note
    ) {}

    @Doc("一个容器")
    public record Box(
            @Doc("方块 X") int x,
            @Doc("方块 Y") int y,
            @Doc("方块 Z") int z,
            @Doc("方块实体 id，例如 minecraft:chest / minecraft:barrel / minecraft:furnace") String block,
            @Doc("方块中文名，例如「箱子」「木桶」；读不到时为空") String name,
            @Doc("true = 已经被搜过了（空的）") boolean empty,
            @Doc("true = Lootr 的箱子：每人独立库存，empty 不可靠，打开只有占位符就跳过") boolean lootr,
            @Doc("槽位数") int slots,
            @Doc("它属于哪块地皮（chunk:x,z / multi:建筑id:原点 / wild=城外）；同一串=同一栋楼") String site,
            @Doc("离我多少格") double distance
    ) {}

    @Doc("一个刷怪笼")
    public record Spawn(
            @Doc("方块 X") int x,
            @Doc("方块 Y") int y,
            @Doc("方块 Z") int z,
            @Doc("刷什么怪，例如 minecraft:zombie；读不到为空") String mob,
            @Doc("生物中文名，例如「僵尸」；读不到为空") String mobName,
            @Doc("生效半径：玩家进这个范围它才开始刷怪（默认 16）") int range,
            @Doc("现在它是不是正在生效（已经有人在范围里）——是的话先别开箱，先挖了它") boolean active,
            @Doc("属于哪栋楼（用于过滤隔壁楼的笼子）；空白表示不知道") @Omitted("空") String site
    ) {}

    @Doc("搜刮路线参数")
    public record PlanArgs(
            @Doc("水平半径（格，4-64）") @Omitted("24 格") Optional<Integer> radius,
            @Doc("上下各扫多少格（0-64；整栋楼就设 40 以上）") @Omitted("24 格") Optional<Integer> dy,
            @Doc("true = 已经搜过的箱子不排进路线") @Omitted("false，空的也排进去让你确认") Optional<Boolean> skipEmpty
    ) {}

    @Doc("在远处预先侦察一栋楼（区块已加载才有内容）")
    public record PreviewArgs(
            @Doc("目标方块 X") int x,
            @Doc("目标方块 Z") int z,
            @Doc("水平半径（格，4-64）") @Omitted("24 格") Optional<Integer> radius,
            @Doc("上下各扫多少格（0-64）") @Omitted("24 格") Optional<Integer> dy
    ) {}

    @Doc("按高度切一段来要（大建筑一次给不完时用这个，一段一段往上要）")
    public record RangeArgs(
            @Doc("从多高开始（方块 Y）") @Omitted("从这栋楼最低的那个容器开始（= 由下往上从头搜）") Optional<Integer> from_y,
            @Doc("这一段有多高（格，4-96；按高度切，不按楼里的楼层切——楼里到底几层是数不准的）")
            @Omitted("16 格") Optional<Integer> height,
            @Doc("水平半径（格，4-64）") @Omitted("24 格") Optional<Integer> radius
    ) {}

    @Doc("按数量翻页来要（从下往上排好，一页一页给，保证不漏）")
    public record PageArgs(
            @Doc("第几页（1 起）") @Omitted("1") Optional<Integer> page,
            @Doc("一页几个（1-60）") @Omitted("30") Optional<Integer> per,
            @Doc("水平半径（格，4-64）") @Omitted("24 格") Optional<Integer> radius
    ) {}

    @Doc("整片搜刮的参数")
    public record SweepArgs(
            @Doc("水平半径（格，4-64）") @Omitted("24 格") Optional<Integer> radius,
            @Doc("刷怪笼怎么处理：reach 进 16 格才挖 / leave 永远绕开 / ignore 完全不管")
            @Omitted("reach") Optional<String> spawners
    ) {}

    @Doc("一次整片搜刮的成果")
    public record Swept(
            @Doc("done 全搜完了 / packed 物品栏和背包都满了 / stopped 被中途叫停") String stopped,
            @Doc("一句中文总结，可以直接念") String note,
            @Doc("这一片是哪块地皮") String scope,
            @Doc("排队准备搜的箱子数") int planned,
            @Doc("真正打开过的箱子数（含空的）") int opened,
            @Doc("打开以后发现是空的") int empty,
            @Doc("搬走的堆数") int took,
            @Doc("其中塞进精妙背包的堆数") int stashed,
            @Doc("放弃的箱子数（去不了、够不着）") int skipped,
            @Doc("挖掉的刷怪笼数") int spawnersBroken,
            @Doc("绕开没挖的刷怪笼数") int spawnersLeft,
            @Doc("一路上遇到的麻烦，一句一条") List<String> problems
    ) {}

    @Doc("某一段高度里的全部容器（从下往上、同高度先近）")
    public record Slice(
            @Doc("这一段从哪一格开始（含）") int fromY,
            @Doc("这一段到哪一格结束（不含，下同）") int toY,
            @Doc("这段里有几个容器") int count,
            @Doc("容器，按高度从下往上排；同一高度先近后远") List<Box> containers,
            @Doc("这段里的刷怪笼：**先把它们挖掉再开箱**") List<Spawn> spawners,
            @Doc("这栋楼里最低的容器在哪一格（不够就是还没有容器）") int lowestY,
            @Doc("这栋楼里最高的容器在哪一格") int highestY,
            @Doc("上面还有东西吗（有就把 from_y 改成 next_y 再要一次）") boolean moreUp,
            @Doc("下一段从哪开始：more_up 为 true 时填它；false 时是 0") int nextY,
            @Doc("这栋楼的地皮") String scope,
            @Doc("补充说明；这段没东西、区块没加载时会写在这里") @Omitted("空，一切正常") String note
    ) {}

    @Doc("一页容器（全部容器按高度从下往上排，按数量切开）")
    public record Page(
            @Doc("这是第几页") int page,
            @Doc("一共几页") int pages,
            @Doc("这栋楼一共几个容器") int total,
            @Doc("这一页有几个") int count,
            @Doc("这一页的容器，按高度从下往上、同高度先近") List<Box> containers,
            @Doc("这一页的高度范围里的刷怪笼") List<Spawn> spawners,
            @Doc("这一页最低那格的高度") int yFrom,
            @Doc("这一页最高那格的高度") int yTo,
            @Doc("下一页是第几页；0 = 没有了") int nextPage,
            @Doc("这栋楼的地皮") String scope,
            @Doc("补充说明；翻过头、这栋楼没容器、区块没加载时会写在这里") @Omitted("空，一切正常") String note
    ) {}

    @Doc("从下往上排好的搜刮路线")
    public record Plan(
            @Doc("按高度分成几段（相差 2 格以内算同一段；不是楼的层数——楼里有几层是数不准的）") int bandCount,
            @Doc("要去的容器总数") int count,
            @Doc("按这个顺序走的总路程（格，直线估算，不含绕墙上下楼）") double distance,
            @Doc("容器太多被截断了吗（只算最近的 60 个）") boolean truncated,
            @Doc("这条路线所属的地皮（一栋楼一块）；同一栋楼才会排进来") String scope,
            @Doc("因为不在同一栋楼而被排除的容器数（说明隔壁楼还有东西）") int skipped,
            @Doc("这栋楼的布局以前记过蓝图吗（记过就可以直接照蓝图走，先信它再微调）") boolean cached,
            @Doc("此刻你 12 格内的敌对生物数量（大于 0 就先打，别急着开箱）") int hostiles,
            @Doc("这栋楼的刷怪笼，按「高度从下到上、同高度先近」排好：**进楼先照这个清单挖掉**（笼子 16 格内点到就生效）") List<Spawn> clearFirst,
            @Doc("按高度分好的段，从最下面一段开始") List<Band> bands,
            @Doc("按顺序要去的容器（先下后上，同高度先近的）") List<Step> route,
            @Doc("这条路线附近的刷怪笼") List<Spawn> spawners,
            @Doc("补充说明；没扫到东西、区块没加载这类情况会写在这里") @Omitted("空，一切正常") String note
    ) {}

    @Doc("按高度分出的一段")
    public record Band(
            @Doc("段序号，1 = 最下面一段") int index,
            @Doc("这一段代表的高度（方块 Y）") int y,
            @Doc("这段里有几个容器") int containers,
            @Doc("其中几个还没搜过") int unlooted,
            @Doc("这段里有几个刷怪笼") int spawners,
            @Doc("在这段里要走多少格") double distance
    ) {}

    @Doc("路线上的一个容器")
    public record Step(
            @Doc("第几个去（从 1 开始）") int order,
            @Doc("方块 X") int x,
            @Doc("方块 Y") int y,
            @Doc("方块 Z") int z,
            @Doc("方块实体 id") String block,
            @Doc("true = 空的（已经搜过）") boolean empty,
            @Doc("true = Lootr 箱子，空与否不可靠") boolean lootr,
            @Doc("从上一步走到这里多少格") double fromPrev
    ) {}

    @Doc("离你最近的一段楼梯/梯子")
    public record Stairs(
            @Doc("找到了吗；false 时 x/y/z 就是你自己的位置") boolean found,
            @Doc("起点那一格（楼梯脚下）X") int x,
            @Doc("起点那一格的 Y") int y,
            @Doc("起点那一格的 Z") int z,
            @Doc("这一段最高能到哪一格的 Y") int topY,
            @Doc("这一段一共有几块楼梯/梯子") int blocks,
            @Doc("true = 梯子或脚手架（对着爬），false = 台阶（走上去）") boolean climb,
            @Doc("离你多少格") double distance,
            @Doc("下一步怎么走（照抄即可）") String note,
            @Doc("附近最近的活板门/栅栏门坐标 \"x,y,z\"；装饰物，**绝对不要右键**，空=附近没有") String avoid) {}

    // ---------------------------------------------------------------- building

    public static final class BuildingFn {

        @Fn("我在哪：是建筑还是街道、建筑名、地上几层地下室几层、每层楼的 Y、这栋楼从哪层开始是废墟")
        @Example("local me = lostcity.building.here()\nprint(me.kind, me.name, me.floors, me.cellars)")
        @Example("local me = lostcity.building.here()\nfor i, y in ipairs(me.floors_y) do print(i, y) end")
        @Example("local me = lostcity.building.here()\nif me.ruin_level >= 0 then print('从第 ' .. me.ruin_level .. ' 层开始塌了') end")
        @Note("只读，不动世界，不需要问我")
        @SeeAlso({"lostcity.find.buildings", "lostcity.loot.containers"})
        public static At here(ServerCall call) {
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            return CityBridge.here(level, her.m_20185_(), her.m_20189_());
        }

        @Fn("查指定坐标是什么建筑（看地图、看标点时用）")
        @Example("print(lostcity.building.at(120, -300).name)")
        @Note("只读，不动世界")
        public static At at(ServerCall call, Pos args) {
            return CityBridge.at(level(call.her()), args.x(), args.z());
        }
    }

    // ---------------------------------------------------------------- find

    public static final class FindFn {

        @Fn("在附近找某种建筑（医院、军营、警局、仓库、加油站、实验室……），返回最近的那栋在哪")
        @Example("local r = lostcity.find.buildings{match = 'hospital'}\nfor _, b in ipairs(r.items) do print(b.name, b.x, b.z, b.distance) end")
        @Example("local r = lostcity.find.buildings{match = 'military', radius_chunks = 16, limit = 3}\nprint(r.total, '栋，最近的在', r.items[1].x, r.items[1].z)")
        @Note("只读；查的是区块数据，不会生成地形，但半径越大越慢（默认 8 个区块）")
        @SeeAlso({"lostcity.building.here", "numen.move.to"})
        public static Found buildings(ServerCall call, Find args) {
            int radius = bounded("radius_chunks", args.radiusChunks().orElse(8), 1, 24);
            int limit = bounded("limit", args.limit().orElse(8), 1, 32);
            NumenPlayer her = call.her();
            return CityBridge.find(level(her), her.m_20185_(), her.m_20189_(),
                    args.match().orElse(""), radius, limit);
        }

        @Fn("列出这个整合包注册了哪些建筑类型（先看有什么，再去 find.buildings 找）")
        @Example("local t = lostcity.find.types{match = 'hospital'}\nprint(t.count)\nfor i, n in ipairs(t.names) do print(i, n) end")
        @Note("只读；一次返回全部类型名，长名字排后面")
        @SeeAlso({"lostcity.find.buildings"})
        public static Types types(ServerCall call, TypeFilter args) {
            return CityBridge.types(level(call.her()), args.match().orElse(""));
        }
    }

    // ---------------------------------------------------------------- multi

    /** {@code lostcity.multi.*} — 跨区块大建筑的尺寸。 */
    public static final class MultiFn {

        @Fn("列出全部跨区块大建筑各自横竖跨几个区块（社区中心、赌场、学校这种一栋占好几块地的楼）")
        @Example("local r = lostcity.multi.list{match = 'community'}\nfor _, m in ipairs(r.items) do print(m.name, m.dim_x .. 'x' .. m.dim_z, m.chunks .. ' 个区块') end")
        @Note("只读；被主人问「这栋楼有几个区块」时用这个查，不要自己数，也不要拿 find.buildings 的条数当区块数")
        @SeeAlso({"lostcity.multi.size", "lostcity.building.here"})
        public static Multis list(ServerCall call, TypeFilter args) {
            return CityBridge.multis(level(call.her()), args.match().orElse(""));
        }

        @Fn("查一栋跨区块大建筑横竖跨几个区块、一共几块（给名字的一部分就行）")
        @Example("local m = lostcity.multi.size('community')\nif m.found then print(m.name, m.dim_x, m.dim_z, m.chunks) end")
        @Note("只读；匹配不到时 found = false，这时先用 lostcity.multi.list 看有哪些名字")
        @SeeAlso({"lostcity.multi.list", "lostcity.building.here"})
        public static MultiSize size(ServerCall call, MultiQuery args) {
            return CityBridge.multiSize(level(call.her()), args.match());
        }
    }

    // ---------------------------------------------------------------- stairs

    /** {@code lostcity.stairs.*} */
    public static final class StairsFn {

        private StairsFn() {}

        @Fn("离我最近的楼梯/梯子在哪：上楼走它，别去右键活板门（那多是护栏，翻开就卡住）")
        @Example("local s = lostcity.stairs.nearest{radius = 16}")
        @Note("只在已加载的区块里找；找不到时返回 found = false，这时用 numen.route.plan 规划到上一层")
        @SeeAlso({"lostcity.building.here", "numen.move.to"})
        public static Stairs nearest(ServerCall her, Args args) {
            ServerLevel level = (ServerLevel) her.her().m_9236_();
            int radius = bounded("radius", args.radius().orElse(16), 3, 64);
            int dy = bounded("dy", args.dy().orElse(6), 2, 32);
            // 这是一次逐方针方块读取：radius=64/dy=32 就是 129*129*65 ≈ 108 万次。E5 的底线是单次
            // 调用 2 毫秒，实机上这个量级要几十毫秒，所以超了直接报错让她调小，而不是让服务器卡一下。
            long reads = (long) (2 * radius + 1) * (2 * radius + 1) * (2 * dy + 1);
            if (reads > 250_000L) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT,
                        "找楼梯的范围太大了：radius=" + radius + "、dy=" + dy + " 要读 " + reads + " 个方块",
                        "把 radius 或 dy 调小（例如 radius = 16, dy = 6 只读一万多个方块）；实在要走远就先走过去再查");
            }
            return CityBridge.stairs(level, her.her().m_20185_(), her.her().m_20186_(), her.her().m_20189_(), radius, dy);
        }

        @Doc("找楼梯的范围")
        public record Args(
                @Doc("水平半径（默认 16，最大 64）") @Omitted("16") Optional<Integer> radius,
                @Doc("上下找多少格（默认 6）") @Omitted("6") Optional<Integer> dy) {}
    }

    // ---------------------------------------------------------------- loot

    public static final class LootFn {

        @Fn("一次列出周围所有箱子/容器/机器/刷怪笼的坐标，空的容器也标出来（已经搜过就不用去了）")
        @Example("local l = lostcity.loot.containers{radius = 24}\nfor _, c in ipairs(l.containers) do print(c.block, c.x, c.y, c.z, c.empty and '空的' or '没搜过') end\nfor _, s in ipairs(l.spawners) do print('刷怪笼', s.mob, s.x, s.y, s.z) end")
        @Example("local l = lostcity.loot.containers{radius = 16, unlooted = true}\nprint('还有 ' .. #l.containers .. ' 个没搜过的容器')")
        @Example("local me = lostcity.building.here()\nlocal l = lostcity.loot.containers{y = me.floors_y[1], dy = 3, radius = 20}\nprint('一楼有 ' .. #l.containers .. ' 个容器')")
        @Note("只读，不打开、不搬东西；只扫已加载区块，所以先走过去再查")
        @Note("容器是靠方块实体类型和物品能力找的，任何模组的箱子/桶/熔炉/机器都算，不用先知道 id")
        @SeeAlso({"lostcity.building.here", "numen.use.block", "numen.gui.view"})
        public static Loot containers(ServerCall call, Look args) {
            int radius = bounded("radius", args.radius().orElse(24), 4, 64);
            int dy = bounded("dy", args.dy().orElse(32), 0, 64);
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            // 只要她脚下这一块地皮：隔壁楼长得一模一样，混进来她就会跑去搜别人家。
            // 分地皮、截断、扫描范围全部交给 CityBridge.loot 按这栋楼的真实大小算——以前这里写死
            // Math.max(radius, 72)，于是 radius 传 8 和传 64 拿到的容器完全是同一批。
            String here = null;
            if (!args.all_plots().orElse(false)) {
                here = CityBridge.site(CityBridge.info(level),
                        ((int) Math.floor(her.m_20185_())) >> 4, ((int) Math.floor(her.m_20189_())) >> 4);
            }
            return CityBridge.loot(level, her.m_20185_(), her.m_20186_(), her.m_20189_(),
                    radius, args.y().orElse(null), dy, args.unlooted().orElse(false), here);
        }

        /** 从下往上：先按高度，再按远近。这是「由下往上遍历」的唯一排序口径。 */
        private static final Comparator<Box> BOTTOM_UP = Comparator
                .comparingInt(Box::y)
                .thenComparingDouble(Box::distance);

        /** 整栋楼一次扫完、不截断：分段与分页的正确性都建立在「先拿全量再切」上。 */
        private static Loot scanAll(ServerLevel level, double px, double py, double pz, int radius) {
            String here = CityBridge.site(CityBridge.info(level),
                    ((int) Math.floor(px)) >> 4, ((int) Math.floor(pz)) >> 4);
            return CityBridge.loot(level, px, py, pz,
                    Math.max(radius, CityBridge.plotRadius(level, px, pz)), null, 64, false, here,
                    Integer.MAX_VALUE);
        }

        @Fn("只要某一段高度里的容器（从下往上、一段一段要，大建筑一次给不完时用）")
        @Example("local s = lostcity.loot.range{}\nprint(s.from_y .. '~' .. s.to_y, s.count .. ' 个箱子')\nif s.more_up then print('上面还有，下一段从 ' .. s.next_y .. ' 开始') end")
        @Example("local s = lostcity.loot.range{from_y = 80, height = 8}\nfor _, c in ipairs(s.containers) do print(c.y, c.block, c.x, c.z) end")
        @Note("**按高度切，不按楼层切**：楼里到底有几层是数不准的（模组报的层数和箱子的实际高度对不上），"
                + "所以这里只认方块高度，别把它当成「第几层」")
        @Note("容器按高度从下往上排，同一高度先近后远；lowest_y / highest_y 是整栋楼的真实范围")
        @Note("这一段不截断——有几个给几个；一段搬不完就把 height 调小再要")
        @Note("只读，不打开、不搬东西；只算已加载区块")
        @SeeAlso({"lostcity.loot.page", "lostcity.loot.plan", "numen.move.to"})
        public static Slice range(ServerCall call, RangeArgs args) {
            int height = bounded("height", args.height().orElse(16), 4, 96);
            int radius = bounded("radius", args.radius().orElse(24), 4, 64);
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            double px = her.m_20185_();
            double py = her.m_20186_();
            double pz = her.m_20189_();
            Loot all = scanAll(level, px, py, pz, radius);
            int lowest = 0;
            int highest = 0;
            if (!all.containers().isEmpty()) {
                lowest = Integer.MAX_VALUE;
                highest = Integer.MIN_VALUE;
                for (Box b : all.containers()) {
                    lowest = Math.min(lowest, b.y());
                    highest = Math.max(highest, b.y());
                }
            }
            boolean any = !all.containers().isEmpty();
            // 默认从这栋楼最低的那个箱子开始往上切：她要求「由下往上遍历」，而站在楼中间时
            // 以脚下为起点会把下面几层直接漏掉（实测站在 y=75、箱子在 67–88，默认就少了一半）。
            int fromY = bounded("from_y", args.from_y().orElse(any ? lowest : (int) Math.floor(py)), -64, 320);
            int toY = fromY + height;
            List<Box> mine = new ArrayList<>();
            for (Box b : all.containers()) {
                if (b.y() >= fromY && b.y() < toY) {
                    mine.add(b);
                }
            }
            mine.sort(BOTTOM_UP);
            List<Spawn> mineSpawners = new ArrayList<>();
            for (Spawn s : all.spawners()) {
                if (s.y() >= fromY && s.y() < toY) {
                    mineSpawners.add(s);
                }
            }
            boolean moreUp = any && highest >= toY;
            String note = "";
            if (!any) {
                note = all.note().isEmpty() ? "这栋楼里一个容器都没有" : all.note();
            } else if (mine.isEmpty()) {
                note = "这一段没有容器——把 from_y 改成 next_y 接着往上要";
            } else if (mine.size() > 30) {
                note = "这一段有 " + mine.size() + " 个容器，一趟可能搬不完；嫌多就把 height 调小分段要";
            }
            return new Slice(fromY, toY, mine.size(), mine, mineSpawners, lowest, highest,
                    moreUp, moreUp ? toY : 0, all.scope(), note);
        }

        @Fn("把整栋楼的容器按高度从下往上排好，一页一页给（保证不漏，next_page 是 0 就搜完了）")
        @Example("local p = lostcity.loot.page{}\nprint('第 ' .. p.page .. '/' .. p.pages .. ' 页，共 ' .. p.total .. ' 个')\nfor _, c in ipairs(p.containers) do print(c.y, c.block, c.x, c.z) end")
        @Example("local p = lostcity.loot.page{page = 2, per = 20}\nif p.next_page > 0 then print('下一页是 ' .. p.next_page) end")
        @Note("排序是「高度从下往上、同一高度先近后远」——照这个顺序走就是由下往上扫，不会来回折返")
        @Note("页是按数量切的，一页 per 个（默认 30）；下一页接着上一页，不会跳过任何一个")
        @Note("y_from / y_to 是这一页涉及的高度范围，用来判断还要不要继续往上爬")
        @Note("只读，不打开、不搬东西；只算已加载区块")
        @SeeAlso({"lostcity.loot.range", "lostcity.loot.plan", "numen.move.to"})
        public static Page page(ServerCall call, PageArgs args) {
            int per = bounded("per", args.per().orElse(30), 1, 60);
            int radius = bounded("radius", args.radius().orElse(24), 4, 64);
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            double px = her.m_20185_();
            double py = her.m_20186_();
            double pz = her.m_20189_();
            Loot all = scanAll(level, px, py, pz, radius);
            List<Box> sorted = new ArrayList<>(all.containers());
            sorted.sort(BOTTOM_UP);
            int total = sorted.size();
            int want = bounded("page", args.page().orElse(1), 1, 9999);
            if (total == 0) {
                throw new ApiError(ErrorKind.NOT_FOUND,
                        "这栋楼里一个容器都没有" + (all.note().isEmpty() ? "" : "（" + all.note() + "）"),
                        "先走进楼里让区块加载出来；如果确实没有箱子，用 lostcity.loot.containers 确认一下");
            }
            int pages = (total + per - 1) / per;
            if (want > pages) {
                throw new ApiError(ErrorKind.NOT_FOUND,
                        "这栋楼一共 " + total + " 个容器、分成 " + pages + " 页，没有第 " + want + " 页",
                        "从第 1 页开始要，或者把 per 调大一点");
            }
            int from = (want - 1) * per;
            int to = Math.min(total, from + per);
            List<Box> mine = new ArrayList<>(sorted.subList(from, to));
            int yFrom = mine.get(0).y();
            int yTo = mine.get(mine.size() - 1).y();
            List<Spawn> mineSpawners = new ArrayList<>();
            for (Spawn s : all.spawners()) {
                if (s.y() >= yFrom && s.y() <= yTo) {
                    mineSpawners.add(s);
                }
            }
            return new Page(want, pages, total, mine.size(), mine, mineSpawners, yFrom, yTo,
                    want < pages ? want + 1 : 0, all.scope(), "");
        }

        @Fn("把这一片楼的箱子全部搜刮干净：一次调用派下去，她自己走、自己开、自己搬，做完统一回报")
        @Example("local s = lostcity.loot.sweep{radius = 24}\nprint(s.note, '搬走', s.took, '堆，塞背包', s.stashed, '堆')")
        @Example("local s = lostcity.loot.sweep{radius = 32, spawners = 'leave'}\nprint(s.stopped, s.opened, '个箱子')")
        @Note("会真的动世界：开箱子、拿走里面的东西。物品栏装满了自动塞进她背着的精妙背包，背包也满了就停下，stopped 是 packed")
        @Note("只搜你脚下这栋楼（一块地皮），隔壁楼不碰；已经空掉的箱子直接跳过")
        @Note("搜的途中可以随时 numen.task.stop 叫停，已经搬走的不会白搬")
        @Note("spawners 三种取值：reach 进 16 格才处理（默认）、leave 永远绕开、ignore 完全不管")
        @Note("这是唯一能一次搜完一大片的办法：脚本每次执行最多 200 次调用，一个一个开箱必然半路被掐断")
        @SeeAlso({"lostcity.loot.page", "lostcity.loot.plan", "numen.task.stop", "numen.gui.view"})
        public static Job<Swept> sweep(ServerCall call, SweepArgs args) {
            int radius = bounded("radius", args.radius().orElse(24), 4, 64);
            String mode = args.spawners().orElse("reach");
            if (!"reach".equals(mode) && !"leave".equals(mode) && !"ignore".equals(mode)) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT,
                        "spawners 只能是 reach、leave、ignore 三选一，收到的是 '" + mode + "'",
                        "不想碰刷怪笼就写 spawners = 'leave'；拼错的值不会被当成「不挖」");
            }
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            double px = her.m_20185_();
            double py = her.m_20186_();
            double pz = her.m_20189_();
            String scope;
            try {
                scope = CityBridge.siteAt(level, (int) Math.floor(px), (int) Math.floor(pz));
            } catch (Throwable t) {
                scope = null;
            }
            // 只看没被搜过的：已经空掉的箱子不用再走过去一趟
            // 范围按这栋楼的真实大小放宽（和 page/range 一致），否则站在大楼角上会漏掉远处的箱子
            Loot all = CityBridge.loot(level, px, py, pz,
                    Math.max(radius, CityBridge.plotRadius(level, px, pz)), null, 64, true, scope,
                    Integer.MAX_VALUE);
            List<Box> boxes = new ArrayList<>();
            for (Box box : all.containers()) {
                if (!box.empty()) {
                    boxes.add(box);
                }
            }
            // 由下往上、同高度先近后远：和 page/range 同一口径，不让她在楼层间来回爬
            boxes.sort(BOTTOM_UP);
            if (boxes.isEmpty()) {
                throw new ApiError(ErrorKind.NOT_FOUND,
                        "这一片没有还能搜的箱子" + (all.note().isEmpty() ? "" : "（" + all.note() + "）"),
                        "用 lostcity.loot.page 看看是不是都被搜过了，或者换个地方、把 radius 调大");
            }
            long now = level.m_46467_();
            SweepRecord rec = new SweepRecord(call, now + 72_000L);
            rec.scope = scope == null ? all.scope() : scope;
            rec.planned = boxes.size();
            rec.queue = boxes;
            rec.spawners = all.spawners();
            rec.spawnerMode = mode;
            return Job.of(rec);
        }

        @Fn("把周围容器从下往上排成一条搜刮路线：按高度分几段、每段几个箱子（几个还没搜）、按什么顺序走、一共多远")
        @Example("local p = lostcity.loot.plan{radius = 24, dy = 40}\nprint(p.band_count, '段', p.count, '个容器', p.distance, '格')\nfor _, f in ipairs(p.bands) do print('第' .. f.index .. '段 y=' .. f.y, f.containers .. '个(没搜' .. f.unlooted .. ')', '刷怪笼' .. f.spawners) end")
        @Example("local p = lostcity.loot.plan{radius = 20, dy = 40}\nfor _, s in ipairs(p.route) do print(s.order, s.block, s.x, s.y, s.z, s.empty and '空的' or '没搜过', s.from_prev) end")
        @Note("只读，不打开、不搬东西；只算已加载区块，所以先进楼再查")
        @Note("**只排你脚下这栋楼（一块地皮）的容器**；隔壁楼即使长得一模一样也不会混进来，被排除的数量在 skipped 里")
        @Note("站在街上是按最近那个箱子所在的那栋楼算路线")
        @Note("路线是先下后上、同高度先近的贪心顺序；距离是直线估算，实际上楼要绕楼梯")
        @Note("Lootr 的箱子每人独立库存，empty 不可靠——打开只有占位符就直接去下一个")
        @SeeAlso({"lostcity.loot.containers", "numen.move.to", "numen.use.block", "numen.gui.view"})
        public static Plan plan(ServerCall call, PlanArgs args) {
            int radius = bounded("radius", args.radius().orElse(24), 4, 64);
            int dy = bounded("dy", args.dy().orElse(24), 0, 64);
            NumenPlayer her = call.her();
            ServerLevel level = level(her);
            return buildPlan(level, her.m_20185_(), her.m_20186_(), her.m_20189_(), radius, dy,
                    args.skipEmpty().orElse(false));
        }

        @Fn("在远处预先侦察一栋楼：给我目标坐标，趁走路的时候把高度分段、容器、刷怪笼、路线都排出来")
        @Example("local p = lostcity.loot.preview(512, -640)\nprint(p.scope, p.count, ' 个容器', p.hostiles, ' 只怪')")
        @Example("local p = lostcity.loot.preview(512, -640, {radius = 32, dy = 48})\nfor _, f in ipairs(p.bands) do print(f.index, f.y, f.containers) end")
        @Note("只读；只能看见已经加载的区块——离得太远会返回 chunks=0，那就再走近点")
        @Note("这是唯一推荐在赶路时调的侦察函数：先用它拿路线，走到楼下再调一次 plan 校正")
        @SeeAlso({"lostcity.loot.plan", "lostcity.find.buildings", "numen.move.to"})
        public static Plan preview(ServerCall call, PreviewArgs args) {
            ServerLevel level = level(call.her());
            int radius = bounded("radius", args.radius().orElse(24), 4, 64);
            int dy = bounded("dy", args.dy().orElse(48), 24, 64);
            // 以前把扫描中心 Y 写死成 0.5，等于只在 y∈[-23,25] 里找箱子，而城市楼板在 y≈63 以上——
            // 所以远处侦察几乎永远是 0 个容器。改成先问这栋楼的地面高度，再按楼层数放宽上下范围。
            int centerY = 64;
            int floors = 1;
            try {
                At at = CityBridge.here(level, args.x(), args.z());
                centerY = at.groundY() + 1;
                floors = Math.max(1, at.floors());
            } catch (Throwable ignored) {
                // 没有城市数据：保留默认值，让 note 去说明为什么是空
            }
            // span 是我们按楼层数自己算的，不是她填的值：楼很高时会超过 64，这里收到上限就完事，
            // 不该因为她没写 dy 反而报 bad_argument。
            int span = Math.min(64, Math.max(dy, 6 * floors + 8));
            return buildPlan(level, args.x() + 0.5, centerY + 0.5, args.z() + 0.5, radius, span, false);
        }

        private static Plan buildPlan(ServerLevel level, double px, double py, double pz,
                                      int radius, int dy, boolean skipEmpty) {
            // 一栋楼 = 一块地皮。隔壁楼长得一模一样，混进来就会出现「在这栋开的箱子，下一个跑到隔壁」。
            // 扫描半径按这栋楼的实际大小取，不再写死 72：3x3 的楼侧翼离她脚下 40 格，只按 radius 扫会少一半。
            String scope = CityBridge.site(CityBridge.info(level),
                    ((int) Math.floor(px)) >> 4, ((int) Math.floor(pz)) >> 4);
            int sweep = Math.max(radius, CityBridge.plotRadius(level, px, pz));
            // onlySite 传进去，让分地皮发生在「按距离只留最近 60 个」之前——顺序反了，
            // 隔壁楼的箱子会把这栋挤出去，而这正是 loot() 自己的注释警告过的错法。
            Loot scan = CityBridge.loot(level, px, py, pz, sweep, null, dy, skipEmpty, scope,
                    CityBridge.MAX_CONTAINERS);
            List<Box> mine = scan.containers();
            int skipped = scan.otherPlots();
            if (!scan.scope().isEmpty()) {
                // 站在街上/桥下：loot() 会退而采用最近那个箱子所在的地皮，跟着它走。
                scope = scan.scope();
            }

            TreeMap<Integer, List<Box>> byY = new TreeMap<>();
            for (Box b : mine) {
                byY.computeIfAbsent(b.y(), k -> new ArrayList<>()).add(b);
            }
            // 段用「聚类」算：相差 2 格以内算同一段（半砖、台阶、下沉半格的房间都在同一段里）。
            // 以前是把每个容器 Y 直接当一「层」、再叠加模组的 floors_y——一栋楼被拆成 14 层，
            // 而模组自报 3 层、箱子实际散在 7 个高度，「几层」这个数根本对不上，所以现在只叫「段」。
            TreeSet<Integer> levels = new TreeSet<>();
            for (Integer y : byY.keySet()) {
                addBand(levels, y);
            }
            try {
                At me = CityBridge.here(level, px, pz);
                if (me != null && me.floorsY() != null) {
                    for (Integer floorY : me.floorsY()) {
                        if (floorY != null && Math.abs(floorY + 1 - py) <= dy) {
                            addBand(levels, floorY + 1);   // walkable cavity sits one above the floor slab
                        }
                    }
                }
            } catch (Throwable ignored) {
                // Lost Cities absent or no chunk info: fall back to container levels only.
            }

            List<Band> bands = new ArrayList<>();
            List<Step> route = new ArrayList<>();
            double cx = px;
            double cy = py;
            double cz = pz;
            double total = 0.0;
            int order = 0;
            int index = 0;
            for (Integer floorLevel : levels) {
                List<Box> pool = new ArrayList<>();
                for (Box b : mine) {
                    Integer band = levels.floor(b.y());
                    if (band != null && band.intValue() == floorLevel.intValue()) {
                        pool.add(b);
                    }
                }
                int unlooted = 0;
                for (Box b : pool) {
                    if (!b.empty()) {
                        unlooted++;
                    }
                }
                int onThisFloor = pool.size();
                int spawners = 0;
                for (Spawn spawn : scan.spawners()) {
                    // 刷怪笼不总坐在楼层 Y 上（半砖、台阶、下沉的房间），要归到它下面最近的那层。
                    // 以前用 == 严格相等，漂一点的笼子既不进任何一层、又只出现在 clear_first 里，
                    // 「这层有几个刷怪笼」就少算了。
                    Integer spawnerFloor = levels.floor(spawn.y());
                    if (spawnerFloor == null) {
                        spawnerFloor = levels.first();
                    }
                    if (spawnerFloor.intValue() == floorLevel.intValue()) {
                        spawners++;
                    }
                }
                double floorDistance = 0.0;
                while (!pool.isEmpty()) {
                    int best = 0;
                    double bestDistance = Double.MAX_VALUE;
                    for (int i = 0; i < pool.size(); i++) {
                        Box b = pool.get(i);
                        double d = dist(cx, cy, cz, b.x(), b.y(), b.z());
                        if (d < bestDistance) {
                            bestDistance = d;
                            best = i;
                        }
                    }
                    Box b = pool.remove(best);
                    route.add(new Step(++order, b.x(), b.y(), b.z(), b.block(), b.empty(), b.lootr(), round(bestDistance)));
                    floorDistance += bestDistance;
                    total += bestDistance;
                    cx = b.x();
                    cy = b.y();
                    cz = b.z();
                }
                bands.add(new Band(++index, floorLevel, onThisFloor,
                        unlooted, spawners, round(floorDistance)));
            }
            List<Spawn> clearFirst = new ArrayList<>(scan.spawners());
            clearFirst.sort(Comparator
                    .comparingInt(Spawn::y)
                    .thenComparingDouble(spawn -> dist(px, py, pz, spawn.x(), spawn.y(), spawn.z())));
            int hostiles = 0;
            try {
                AABB around = new AABB(px - 12, py - 6, pz - 12, px + 12, py + 12, pz + 12);
                for (Entity e : level.m_45976_(Entity.class, around)) {
                    if (e instanceof Enemy || e instanceof Monster) {
                        hostiles++;
                    }
                }
            } catch (Throwable ignored) {
                // Monster/Enemy absent: treat as no hostiles.
            }
            boolean cached = Blueprints.has(scope);
            String note = scan.note();
            if (note.isEmpty() && route.isEmpty()) {
                note = "这栋楼里没扫到容器（只有已加载区块才算）：先上楼看看，或者换个楼层再查";
            }
            return new Plan(bands.size(), route.size(), round(total), scan.truncated(),
                    scope, skipped, cached, hostiles, clearFirst, bands, route, scan.spawners(), note);
        }

        /** 把 y 归进已有的层，或新开一层（相差 2 格以内算同一层）。 */
        private static void addBand(TreeSet<Integer> levels, int y) {
            for (int band : levels) {
                if (Math.abs(band - y) <= 2) {
                    return;
                }
            }
            levels.add(y);
        }

        private static double dist(double ax, double ay, double az, double bx, double by, double bz) {
            double dx = ax - bx;
            double dyy = ay - by;
            double dz = az - bz;
            return Math.sqrt(dx * dx + dyy * dyy + dz * dz);
        }

        private static double round(double d) {
            return Math.round(d * 10.0) / 10.0;
        }
    }

    // ---------------------------------------------------------------- shared

    private static ServerLevel level(NumenPlayer her) {
        if (her.m_9236_() instanceof ServerLevel serverLevel) {
            return serverLevel;
        }
        throw new ApiError(ErrorKind.FAILED, "她不在一个服务端世界里", null);
    }

    /**
     * 数字参数的边界检查。
     *
     * <p>以前是 clamp：传 0 当成 4、传 1000 当成 64、传 999 当成 10，一律静默改数。她永远不知道自己
     * 写的值没生效，看到的数字也不是她要的——这正是「参数越界要报 bad_argument，不许静默夹取」那条规矩。
     */
    static int bounded(String what, int v, int lo, int hi) {
        if (v < lo || v > hi) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    what + " 只能在 " + lo + " 到 " + hi + " 之间，你给的是 " + v,
                    "把 " + what + " 改成 " + lo + ".." + hi + " 里的数再调一次；不写这个参数就用默认值");
        }
        return v;
    }
}
