package dev.local.numencooking;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code cooking.kitchen}：农夫乐事的厨房现在在干什么。
 *
 * <p>从外面看，一口锅什么都看不出来：放着对的食物却没有火的锅，和再差五秒就出锅的锅是同一个
 * 方块、同样的九格；做好的菜和生材料躺在同一个物品栏里；错误的热源看起来和正确的热源一模一样。
 * 农夫乐事只把 {@code farmersdelight:heat_sources} 标签里的方块算作火源，所以架在营火上的锅
 * （厨房悄悄停摆最常见的原因）除非有人去读方块实体的 {@code isHeated()}，否则根本看不出来。
 *
 * <p>这里全是读操作：不塞东西、不拿出来、不点火、不催熟。
 */
public final class CookingApi {

    private static final int NEAR_DEFAULT_RADIUS = 16;
    private static final int NEAR_MAX_RADIUS = 48;
    private static final int MAX_POTS = 8;
    private static final int MAX_BOARDS = 6;
    private static final int MAX_SKILLETS = 6;
    private static final int MAX_STOVES = 6;

    /** Slot layout of a cooking pot, from the block entity's own public constants. */
    private static final int INGREDIENT_SLOTS = 6;

    private CookingApi() {
    }

    static void install(NumenApi numen) {
        numen.api("kitchen",
                "厨房：这口锅下面到底有没有火、里面放了什么、还差几秒；砧板上放着什么；煎锅里煎到哪一步了。",
                CookingApi.class);
    }

    /** One line for the boot log. */
    static String health() {
        return Bridge.ready()
                ? "农夫乐事的锅/炉灶/砧板/煎锅都能读"
                : "不可用：找不到 FarmersDelight 的方块实体";
    }

    // ---------------------------------------------------------------- options

    /** Options for both calls. */
    @Doc("要扫的半径；不写就是 16 格。")
    public record NearOpt(
            @Doc("半径（格），1-48。") @Omitted("16") Optional<Integer> radius) {
    }

    // ---------------------------------------------------------------- shapes

    /** One cooking pot and everything that decides whether food will ever come out of it. */
    @Doc("一口锅：下面有没有火、锅里放了什么、煮到几成、熟了没有。")
    public record Pot(
            @Doc("锅的坐标，形如 '320, 64, -1950'。") String pos,
            @Doc("离她多少格（直线距离，含上下）。") int distance,
            @Doc("比她高多少格（负数是比她低）；差得多就说明在另一层。") int height,
            @Doc("下面有没有火：只有炉灶、岩浆块、岩浆锅这类才算，营火不算。") boolean heated,
            @Doc("现在是不是正在煮（有火、材料凑成了菜谱、还没有成品挡着）。") boolean cooking,
            @Doc("煮到几成，0-100；没在煮就是 0。") int progress,
            @Doc("还差几秒出锅；没在煮就是 -1。") int seconds_left,
            @Doc("锅里的材料，用顿号连起来；空锅就是空字符串。") String ingredients,
            @Doc("已经做好了、正在锅里等着的菜；没有就是空字符串。") String meal,
            @Doc("出料格里装好的成品；没有就是空字符串。") String output,
            @Doc("容器格里放的碗/盘子；没有就是空字符串。") String container,
            @Doc("能不能装出来：模组要求锅里先放好碗，或者这道菜自己带着容器（比如西瓜片）。") boolean servable,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** One cutting board. */
    @Doc("一块砧板：上面放着什么、是不是正在刻东西。")
    public record Board(
            @Doc("砧板坐标。") String pos,
            @Doc("离她多少格（含上下）。") int distance,
            @Doc("比她高多少格（负数是比她低）。") int height,
            @Doc("砧板上放着的东西；空的就写「空」。") String item,
            @Doc("是不是正拿刀在刻（比如把南瓜刻成南瓜灯）。") boolean carving,
            @Doc("一句话结论。") String line) {
    }

    /** One stove: the six-slot surface a pot stands on and food can be laid on. */
    @Doc("一个炉灶：有没有点着、上面放着几样东西、哪几样好了。")
    public record Stove(
            @Doc("炉灶坐标。") String pos,
            @Doc("离她多少格（含上下）。") int distance,
            @Doc("比她高多少格（负数是比她低）。") int height,
            @Doc("是不是点着的（没点着的炉灶不给热，也不煎东西）。") boolean lit,
            @Doc("上面有几样东西正在加热。") int cooking,
            @Doc("最快的一样还要几秒熟；上面是空的或者没点着就是 -1。") int seconds_left,
            @Doc("上面每样东西，用顿号连起来；空的就是空字符串。") String items,
            @Doc("一句话结论。") String line) {
    }

    /** One skillet: the single-item frying pan that sits on a lit stove. */
    @Doc("一口煎锅：上面煎着什么、煎到几成、好了没有。")
    public record Skillet(
            @Doc("煎锅坐标。") String pos,
            @Doc("离她多少格（含上下）。") int distance,
            @Doc("比她高多少格（负数是比她低）。") int height,
            @Doc("煎着的东西；空锅是空字符串。") String item,
            @Doc("是不是正在煎（有东西、下面也点着火）。") boolean cooking,
            @Doc("煎到几成，0-100；没在煎就是 0。") int progress,
            @Doc("还差几秒好；没在煎就是 -1。") int seconds_left,
            @Doc("一句话结论。") String line) {
    }

    /** The whole kitchen at a glance. */
    @Doc("这一圈的厨房：几口锅、几个炉灶、几块砧板、几口煎锅，每口锅什么状态。")
    public record Kitchen(
            @Doc("扫的半径（格）。") int radius,
            @Doc("找到的锅数。") int pots,
            @Doc("找到的炉灶数（锅要站在点着的炉灶上才会热）。") int stoves,
            @Doc("其中几个炉灶是点着的。") int lit_stoves,
            @Doc("找到的砧板数。") int boards,
            @Doc("找到的煎锅数。") int skillets,
            @Doc("其中几口锅现在煮着东西。") int working,
            @Doc("其中几口锅下面没有火。") int cold,
            @Doc("半径里一共看了几个区块（0 表示一个都没加载，这时报 0 不代表没有）。") int chunks,
            @Doc("有东西但没列出来的条数（每种最多列几行）。") int hidden,
            @Doc("每口锅的详情，最多 8 条，按离她远近排。") List<Pot> pot_list,
            @Doc("每个炉灶的详情，最多 6 条。") List<Stove> stove_list,
            @Doc("每块砧板的详情，最多 6 条。") List<Board> board_list,
            @Doc("每口煎锅的详情，最多 6 条。") List<Skillet> skillet_list,
            @Doc("要提醒的话（区块没加载、东西被截断）；没有就是空字符串。") String note,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    // ---------------------------------------------------------------- calls

    @Fn("看她这一圈的厨房：有几口锅、哪口下面没火、哪口正在煮、还差几秒，炉灶点着没有，砧板和煎锅上又放着什么。")
    @Example("cooking.kitchen.near()")
    @Example("cooking.kitchen.near({radius = 24})")
    @Note("只读。默认半径 16 格，可写到 48；写 0、写负数、写超过 48 都会报 bad_argument，"
            + "不会偷偷改成别的数。只扫已加载的区块——chunks = 0 就表示半径里一个区块都没加载，"
            + "这时 pots = 0 不是真的没有锅。营火不算火源，锅里放着对的东西却一直不熟多半就是这个原因。")
    public static Kitchen near(ServerCall call, NearOpt args) {
        int radius = radiusOf(args, "cooking.kitchen.near()");
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();

        List<Pot> potList = new ArrayList<>();
        List<Stove> stoveList = new ArrayList<>();
        List<Board> boardList = new ArrayList<>();
        List<Skillet> skilletList = new ArrayList<>();
        int pots = 0;
        int stoves = 0;
        int litStoves = 0;
        int boards = 0;
        int skillets = 0;
        int working = 0;
        int cold = 0;
        Scan scan = around(level, her, radius);

        for (BlockEntity be : scan.found()) {
            Class<?> type = be.getClass();
            if (Bridge.isPot(type)) {
                pots++;
                Pot pot = readPot(be, her);
                if (pot.cooking()) {
                    working++;
                }
                if (!pot.heated()) {
                    cold++;
                }
                if (potList.size() < MAX_POTS) {
                    potList.add(pot);
                }
            } else if (Bridge.isBoard(type)) {
                boards++;
                if (boardList.size() < MAX_BOARDS) {
                    boardList.add(readBoard(be, her));
                }
            } else if (Bridge.isStove(type)) {
                stoves++;
                Stove stove = readStove(be, her);
                if (stove.lit()) {
                    litStoves++;
                }
                if (stoveList.size() < MAX_STOVES) {
                    stoveList.add(stove);
                }
            } else if (Bridge.isSkillet(type)) {
                skillets++;
                if (skilletList.size() < MAX_SKILLETS) {
                    skilletList.add(readSkillet(be, her));
                }
            }
        }

        potList.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        stoveList.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        boardList.sort((a, b) -> Integer.compare(a.distance(), b.distance()));
        skilletList.sort((a, b) -> Integer.compare(a.distance(), b.distance()));

        int hidden = (pots - potList.size()) + (stoves - stoveList.size())
                + (boards - boardList.size()) + (skillets - skilletList.size());

        StringBuilder note = new StringBuilder();
        if (scan.chunks() == 0) {
            note.append("半径里一个区块都没加载——先走过去，等区块出来了再查；这里报 0 不是真的没有");
        } else if (scan.unloaded() > 0) {
            note.append("有 ").append(scan.unloaded()).append(" 个区块没加载，那边的东西现在看不到");
        }
        if (hidden > 0) {
            if (note.length() > 0) {
                note.append("；");
            }
            note.append("另有 ").append(hidden).append(" 个没列出来（每种最多列几行）");
        }

        StringBuilder line = new StringBuilder("半径 ").append(radius).append(" 格里");
        if (pots == 0 && boards == 0 && skillets == 0 && stoves == 0) {
            line.append(scan.chunks() == 0 ? "一个区块都没加载，什么都看不到" : "没有找到任何锅、炉灶、砧板或煎锅");
        } else {
            line.append(' ').append(pots).append(" 口锅");
            if (pots > 0) {
                line.append("（").append(working).append(" 口正煮着");
                if (cold > 0) {
                    line.append("，").append(cold).append(" 口下面没火");
                }
                line.append("）");
                if (working == 0) {
                    line.append(cold == pots
                            ? "，全都没火，一口也没在煮"
                            : "，没有一口在煮（火还在，但材料没凑成菜谱或者锅是空的）");
                }
            }
            if (stoves > 0) {
                line.append("、").append(stoves).append(" 个炉灶（").append(litStoves).append(" 个点着）");
            }
            if (boards > 0) {
                line.append("、").append(boards).append(" 块砧板");
            }
            if (skillets > 0) {
                line.append("、").append(skillets).append(" 口煎锅");
            }
        }
        return new Kitchen(radius, pots, stoves, litStoves, boards, skillets, working, cold,
                scan.chunks(), hidden, potList, stoveList, boardList, skilletList, note.toString(),
                line.toString());
    }

    @Fn("看离她最近的那口锅：下面有没有火、里面放了什么、煮到几成、熟了没有。")
    @Example("cooking.kitchen.pot()")
    @Example("cooking.kitchen.pot({radius = 24})")
    @Note("只读。默认半径 16 格，可写到 48，越界报 bad_argument。附近一口锅都没有会报 not_found，"
            + "先用 cooking.kitchen.near() 看看这一圈到底有什么（注意 chunks = 0 时是没加载，不是没有）。")
    public static Pot pot(ServerCall call, NearOpt args) {
        int radius = radiusOf(args, "cooking.kitchen.pot()");
        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();

        BlockEntity nearest = null;
        double bestSq = Double.MAX_VALUE;
        for (BlockEntity be : around(level, her, radius).found()) {
            if (!Bridge.isPot(be.getClass())) {
                continue;
            }
            double sq = be.m_58899_().m_123331_(her);
            if (sq < bestSq) {
                bestSq = sq;
                nearest = be;
            }
        }
        if (nearest == null) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "半径 " + radius + " 格里没有锅",
                    "cooking.kitchen.pot({radius = 24})");
        }
        return readPot(nearest, her);
    }

    // ---------------------------------------------------------------- readers

    private static Pot readPot(BlockEntity be, BlockPos her) {
        boolean heated = Bridge.bool(be, "isHeated");
        Object inv = Bridge.call(be, "getInventory");
        int cookTime = Bridge.intField(be, "cookTime");
        int cookTotal = Bridge.intField(be, "cookTimeTotal");

        String meal = itemText(Bridge.stack(be, "getMeal"));
        String output = itemText(slot(inv, 8));
        String container = itemText(slot(inv, 7));

        List<String> parts = new ArrayList<>();
        for (int i = 0; i < INGREDIENT_SLOTS; i++) {
            String text = itemText(slot(inv, i));
            if (!text.isEmpty()) {
                parts.add(text);
            }
        }
        String ingredients = String.join("、", parts);

        boolean cooking = heated && cookTotal > 0 && cookTime > 0;
        int progress = cookTotal > 0 ? clamp(cookTime * 100 / cookTotal, 0, 100) : 0;
        int secondsLeft = cooking && cookTotal > 0 && cookTime < cookTotal
                ? (cookTotal - cookTime + 19) / 20
                : -1;

        String pos = posOf(be.m_58899_());
        int distance = distance(be, her);

        int height = height(be, her);
        // 成品可能在两处：槽 6 的「展示格」（meal，还没装走时）和槽 8 的「出料格」（output，
        // 模组把成品推出来、并且把碗吃进 Container 标签之后就在这儿）。只看槽 6 会把
        // 「甜菜汤已经在出料格里等着拿」错报成「装不出来」。
        boolean served = !output.isEmpty();
        boolean servable = served
                || (!meal.isEmpty() && (!container.isEmpty() || hasRemainingItem(Bridge.stack(be, "getMeal"))));
        String where = height == 0 ? "" : (height > 0 ? "（在你上面 " + height + " 格）" : "（在你下面 " + (-height) + " 格）");

        String line;
        if (!heated) {
            if (ingredients.isEmpty() && meal.isEmpty() && output.isEmpty()) {
                line = "锅是空的，而且下面也没有火（火源只认炉灶、岩浆块这类，营火不算）";
            } else {
                line = "锅下面没有火，所以" + (ingredients.isEmpty() ? "它不会开始煮" : "里面的 " + ingredients + " 永远不会熟")
                        + "；火源只认炉灶、岩浆块、岩浆锅这些，营火不算";
            }
        } else if (cooking) {
            line = "锅正开着火在煮" + (ingredients.isEmpty() ? "" : "（里面有 " + ingredients + "）")
                    + "，" + progress + "%，还差 " + secondsLeft + " 秒出锅";
            if (!container.isEmpty()) {
                line = line + "，容器格里备着 " + container;
            } else {
                line = line + "，容器格里没放碗";
            }
        } else if (!meal.isEmpty()) {
            line = "锅里有做好的 " + meal;
            if (!ingredients.isEmpty()) {
                line = line + "，另外还放着 " + ingredients;
            }
            if (!servable) {
                line = line + "；装不出来——锅里没放碗，这道菜也不自带容器，往容器格里放个碗再来拿";
            } else if (!container.isEmpty()) {
                line = line + "；容器格里有 " + container + "，可以直接盛";
            } else {
                line = line + "，等着盛出来";
            }
        } else if (!output.isEmpty()) {
            line = "有做好的 " + output + " 在出料格里，直接拿就行";
        } else if (ingredients.isEmpty()) {
            line = "锅下面有火，但锅是空的，什么都没放";
        } else {
            line = "锅下面有火，里面放了 " + ingredients + "，但凑不成任何菜谱，所以一直不会熟";
        }
        if (!where.isEmpty()) {
            line = line + where;
        }

        return new Pot(pos, distance, height, heated, cooking, progress, secondsLeft,
                ingredients, meal, output, container, servable, line);
    }

    private static Board readBoard(BlockEntity be, BlockPos her) {
        String item = itemText(Bridge.stack(be, "getStoredItem"));
        boolean carving = Bridge.bool(be, "isItemCarvingBoard");
        String pos = posOf(be.m_58899_());
        int distance = distance(be, her);
        int height = height(be, her);

        String line;
        if (item.isEmpty()) {
            line = carving ? "砧板上有活儿正在刻，但读不到东西" : "砧板是空的";
        } else if (carving) {
            line = "砧板上是 " + item + "，正在拿刀刻（不是在切菜）";
        } else {
            line = "砧板上放着 " + item + "，还没有动刀";
        }
        return new Board(pos, distance, height, item.isEmpty() ? "空" : item, carving,
                line + at(height));
    }

    private static Stove readStove(BlockEntity be, BlockPos her) {
        boolean lit = Bridge.lit(be);
        Object inv = Bridge.call(be, "getItems");
        int[] progress = Bridge.intArrayField(be, "cookingProgress");
        int[] total = Bridge.intArrayField(be, "cookingTime");

        List<String> parts = new ArrayList<>();
        int cooking = 0;
        int soonest = -1;
        int slots = inv == null ? 0 : toInt(Bridge.call(inv, "getSlots"), 0);
        for (int i = 0; i < slots; i++) {
            String text = itemText(slot(inv, i));
            if (text.isEmpty()) {
                continue;
            }
            parts.add(text);
            int done = progress != null && i < progress.length ? progress[i] : 0;
            int need = total != null && i < total.length ? total[i] : 0;
            if (need > 0 && done > 0) {
                cooking++;
                int left = Math.max(1, (need - done + 19) / 20);
                if (soonest < 0 || left < soonest) {
                    soonest = left;
                }
            } else if (done == 0 && need > 0 && lit) {
                // 已经放上去、还没开始算进度的一格
                cooking++;
            }
        }

        String pos = posOf(be.m_58899_());
        int distance = distance(be, her);
        int height = height(be, her);
        String items = String.join("、", parts);

        String line;
        if (!lit) {
            line = items.isEmpty()
                    ? "炉灶没点着，上面也是空的"
                    : "炉灶没点着，上面放着的 " + items + " 永远不会熟";
        } else if (items.isEmpty()) {
            line = "炉灶点着了，上面什么都没放";
        } else if (soonest > 0) {
            line = "炉灶点着了，正在加热：" + items + "，最快还要 " + soonest
                    + " 秒；熟透了 FarmersDelight 会直接把它弹到地上，所以别走开";
        } else {
            line = "炉灶点着了，上面放着 " + items + "，还没开始算熟（要再过一拍）";
        }
        return new Stove(pos, distance, height, lit, cooking, soonest, items, line + at(height));
    }

    /** The frying pan: one supported stack, cooked by the lit stove under it. */
    private static Skillet readSkillet(BlockEntity be, BlockPos her) {
        String item = itemText(Bridge.call(be, "getStoredStack"));
        boolean heated = Bridge.bool(be, "isHeated");
        boolean cooking = heated && !item.isEmpty();
        int cookTime = Bridge.intField(be, "cookingTime");
        int cookTotal = Bridge.intField(be, "cookingTimeTotal");
        int progress = cooking && cookTotal > 0 ? clamp(cookTime * 100 / cookTotal, 0, 100) : 0;
        int secondsLeft = cooking && cookTotal > 0 && cookTime < cookTotal
                ? (cookTotal - cookTime + 19) / 20
                : -1;

        String pos = posOf(be.m_58899_());
        int distance = distance(be, her);
        int height = height(be, her);

        String line;
        if (item.isEmpty()) {
            line = heated
                    ? "煎锅是空的，下面点着火，可以放东西进去煎"
                    : "煎锅是空的，下面也不是点着的炉灶";
        } else if (!heated) {
            line = "煎锅里有 " + item + "，但下面没火，煎不了";
        } else {
            line = "煎锅里正煎着 " + item + "，" + progress + "%，还差 " + secondsLeft + " 秒";
        }
        return new Skillet(pos, distance, height, item, cooking, progress, secondsLeft,
                line + at(height));
    }

    // ---------------------------------------------------------------- scan

    /** 扫到的方块实体，外加「看了几个区块」这层信息（区块没加载时 0 条不代表没有）。 */
    private record Scan(List<BlockEntity> found, int chunks, int unloaded) {
    }

    /** Every block entity in the loaded chunks within {@code radius} of {@code center}. */
    private static Scan around(ServerLevel level, BlockPos center, int radius) {
        List<BlockEntity> out = new ArrayList<>();
        int chunks = 0;
        int unloaded = 0;
        int minChunkX = (center.m_123341_() - radius) >> 4;
        int maxChunkX = (center.m_123341_() + radius) >> 4;
        int minChunkZ = (center.m_123343_() - radius) >> 4;
        int maxChunkZ = (center.m_123343_() + radius) >> 4;
        long limitSq = (long) radius * radius;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                if (!level.m_46749_(new BlockPos(cx << 4, center.m_123342_(), cz << 4))) {
                    unloaded++;
                    continue;
                }
                chunks++;
                LevelChunk chunk = level.m_6325_(cx, cz);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.m_62954_().values()) {
                    if (be == null) {
                        continue;
                    }
                    if (be.m_58899_().m_123331_(center) > limitSq) {
                        continue;
                    }
                    out.add(be);
                }
            }
        }
        out.sort((a, b) -> Double.compare(a.m_58899_().m_123331_(center), b.m_58899_().m_123331_(center)));
        return new Scan(out, chunks, unloaded);
    }

    // ---------------------------------------------------------------- helpers

    private static Object slot(Object handler, int index) {
        if (handler == null) {
            return null;
        }
        int slots = toInt(Bridge.call(handler, "getSlots"), 0);
        if (index < 0 || index >= slots) {
            return null;
        }
        return Bridge.call(handler, "getStackInSlot", index);
    }

    /** A readable name for a stack: Chinese name when the table has one, otherwise the id. */
    private static String itemText(Object raw) {
        if (!(raw instanceof ItemStack stack) || stack.m_41619_()) {
            return "";
        }
        String id;
        try {
            id = BuiltInRegistries.f_257033_.m_7981_(stack.m_41720_()).toString();
        } catch (Throwable t) {
            return "某样东西";
        }
        String shown = Zh.name(stack);
        if (shown.isEmpty()) {
            shown = id.substring(id.indexOf(':') + 1);
        }
        int count = stack.m_41613_();
        return count > 1 ? shown + "×" + count : shown;
    }

    private static String posOf(BlockPos pos) {
        return pos.m_123341_() + ", " + pos.m_123342_() + ", " + pos.m_123343_();
    }

    private static int distance(BlockEntity be, BlockPos her) {
        return (int) Math.round(Math.sqrt(be.m_58899_().m_123331_(her)));
    }

    /** 比她高多少格（负数是比她低）。差得多就说明在另一层，不是「附近」。 */
    private static int height(BlockEntity be, BlockPos her) {
        return be.m_58899_().m_123342_() - her.m_123342_();
    }

    /** 拼在句子尾巴上的高度差说明。 */
    private static String at(int height) {
        if (height >= 3) {
            return "（在你上面 " + height + " 格）";
        }
        if (height <= -3) {
            return "（在你下面 " + (-height) + " 格）";
        }
        return "";
    }

    /** 这道菜自己带不带容器（模组判据之一，另一种是锅里放了空碗）。 */
    private static boolean hasRemainingItem(Object raw) {
        if (!(raw instanceof ItemStack stack) || stack.m_41619_()) {
            return false;
        }
        Object rest = Bridge.call(stack, "m_41777_");
        return rest instanceof ItemStack left && !left.m_41619_();
    }

    /** 半径：越界报 bad_argument，不再静默改成别的数。 */
    private static int radiusOf(NearOpt args, String callName) {
        if (args == null || args.radius().isEmpty()) {
            return NEAR_DEFAULT_RADIUS;
        }
        int want = args.radius().get();
        if (want < 1 || want > NEAR_MAX_RADIUS) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "半径只收 1 到 " + NEAR_MAX_RADIUS + " 格，给的是 " + want,
                    "写 " + callName + "({radius = 16})；不写就是 " + NEAR_DEFAULT_RADIUS + " 格");
        }
        return want;
    }

    private static ServerLevel levelOf(ServerCall call) {
        if (!(call.her().m_9236_() instanceof ServerLevel level)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她现在不在服务端世界里",
                    "这个接口只能在服务端世界读；等她在世界里再问", null);
        }
        return level;
    }

    private static int clamp(int value, int low, int high) {
        return Math.max(low, Math.min(high, value));
    }

    private static int toInt(Object value, int fallback) {
        return value instanceof Integer i ? i : fallback;
    }


    // ---------------------------------------------------------------- reflection bridge

    /**
     * Every FarmersDelight call this plugin makes, resolved once by name.
     *
     * <p>农夫乐事不是编译期依赖，所以这里一个直接引用都不能有。方块实体类名照抄是因为模组没做
     * 重映射；进度那几个数只存在于私字段里——锅是 {@code cookTime}/{@code cookTimeTotal}，
     * 煎锅是 {@code cookingTime}/{@code cookingTimeTotal}，炉灶在抽象父类上的
     * {@code cookingProgress}/{@code cookingTime} 两个 int 数组（0-3 号位对应四个格子）。
     */
    private static final class Bridge {

        private static final String PKG = "vectorwing.farmersdelight.common.block.entity.";
        private static final Class<?> POT = load(PKG + "CookingPotBlockEntity");
        private static final Class<?> BOARD = load(PKG + "CuttingBoardBlockEntity");
        private static final Class<?> STOVE = load(PKG + "StoveBlockEntity");
        private static final Class<?> SKILLET = load(PKG + "SkilletBlockEntity");

        private static final Map<String, Method> METHODS = new java.util.concurrent.ConcurrentHashMap<>();
        private static final Map<String, Field> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

        private Bridge() {
        }

        private static Class<?> load(String name) {
            try {
                return Class.forName(name);
            } catch (Throwable t) {
                return null;
            }
        }

        static boolean ready() {
            return POT != null && BOARD != null && STOVE != null && SKILLET != null;
        }

        static boolean isPot(Class<?> type) {
            return POT != null && POT.isAssignableFrom(type);
        }

        static boolean isBoard(Class<?> type) {
            return BOARD != null && BOARD.isAssignableFrom(type);
        }

        static boolean isStove(Class<?> type) {
            return STOVE != null && STOVE.isAssignableFrom(type);
        }

        static boolean isSkillet(Class<?> type) {
            return SKILLET != null && SKILLET.isAssignableFrom(type);
        }

        /**
         * Whether the block's own {@code lit} state is true. Stoves keep their light in the block
         * state rather than the block entity, so this is the only place the answer exists.
         */
        static boolean lit(BlockEntity be) {
            try {
                Object state = call(be, "m_58900_");
                if (state == null) {
                    return false;
                }
                Object properties = call(state, "m_61147_");
                if (!(properties instanceof Iterable<?> iterable)) {
                    return false;
                }
                for (Object property : iterable) {
                    if (!"lit".equals(call(property, "m_61708_"))) {
                        continue;
                    }
                    return Boolean.TRUE.equals(call(state, "m_61143_", property));
                }
            } catch (Throwable ignored) {
                // a missing method just means "we cannot tell"; report it as unlit
            }
            return false;
        }

        static Object call(Object target, String name, Object... args) {
            if (target == null) {
                return null;
            }
            String key = target.getClass().getName() + "#" + name + "/" + args.length;
            Method method = METHODS.get(key);
            if (method == null) {
                method = find(target.getClass(), name, args.length);
                if (method == null) {
                    return null;
                }
                method.setAccessible(true);
                METHODS.put(key, method);
            }
            try {
                return method.invoke(target, args);
            } catch (Throwable t) {
                return null;
            }
        }

        private static Method find(Class<?> type, String name, int arity) {
            for (Class<?> c = type; c != null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals(name) && m.getParameterCount() == arity) {
                        return m;
                    }
                }
            }
            return null;
        }

        static boolean bool(Object target, String name) {
            return Boolean.TRUE.equals(call(target, name));
        }

        /** A named stack getter; an empty stack when the method is missing. */
        static Object stack(Object target, String name) {
            return call(target, name);
        }

        static int intField(Object target, String name) {
            Object value = field(target, name);
            return value instanceof Integer i ? i : 0;
        }

        static int[] intArrayField(Object target, String name) {
            Object value = field(target, name);
            return value instanceof int[] array ? array : null;
        }

        private static Object field(Object target, String name) {
            if (target == null) {
                return null;
            }
            String key = target.getClass().getName() + "#" + name;
            Field field = FIELDS.get(key);
            if (field == null) {
                for (Class<?> c = target.getClass(); c != null && field == null; c = c.getSuperclass()) {
                    try {
                        field = c.getDeclaredField(name);
                    } catch (NoSuchFieldException ignored) {
                        // keep walking up
                    }
                }
                if (field == null) {
                    return null;
                }
                field.setAccessible(true);
                FIELDS.put(key, field);
            }
            try {
                return field.get(target);
            } catch (Throwable t) {
                return null;
            }
        }
    }
}
