package dev.local.numenitems;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code items}: what is actually in a hand, on a body, in a bag or in a box.
 *
 * <p>Reading an item id is not the same as reading the item: two diamond swords share an id and
 * differ in enchantments, durability and whatever a mod stuffed in their NBT. This group reads all
 * of that for <em>her own</em> hands, armour, curio slots and 36-slot backpack, plus the nearest
 * vanilla container and her ender chest. Modded backpacks are {@code sb}'s job, not this one's.
 *
 * <p>Everything here is about her own body on purpose: she does not read other players, so no call
 * here can fail because a different player happens to be offline.
 *
 * <p>Reading only. Nothing is moved, swapped, equipped, repaired or dropped.
 */
public final class ItemsApi {

    private static final int MAX_ENCHANTS = 8;
    private static final int MAX_TAG_KEYS = 8;
    /** How many worn curios to describe before the report turns into noise. */
    private static final int MAX_CURIOS = 16;

    private static final int MAX_BAG_ROWS = 24;
    /** How many distinct things to list for a chest before the report turns into noise. */
    private static final int MAX_BOX_ROWS = 24;
    /** Default and maximum scan radius for {@code chest}, in blocks. */
    private static final int CHEST_RADIUS = 6;
    private static final int MAX_CHEST_RADIUS = 32;

    /** Curios slot ids → the Chinese she speaks; unknown ids stay English. */
    private static final Map<String, String> CURIO_NAMES = Map.ofEntries(
            Map.entry("ring", "戒指"),
            Map.entry("necklace", "项链"),
            Map.entry("belt", "腰带"),
            Map.entry("bracelet", "手镯"),
            Map.entry("hand", "手"),
            Map.entry("hands", "手"),
            Map.entry("head", "头饰"),
            Map.entry("charm", "护符"),
            Map.entry("back", "披风"),
            Map.entry("body", "贴身"),
            Map.entry("feet", "脚饰"),
            Map.entry("curio", "饰品"),
            Map.entry("extra", "额外"),
            Map.entry("faceslot", "面饰"),
            Map.entry("shoulder", "肩饰"),
            Map.entry("trinkets", "小饰品"),
            Map.entry("ammo_box", "弹药盒"),
            Map.entry("meter", "计量表"),
            Map.entry("horde_calendar", "尸潮日历"),
            Map.entry("cosmetic", "外观"));

    /** The four armour slots, head first, with the Chinese names she speaks. */
    private static final List<EquipmentSlot> EQUIPMENT = List.of(
            EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET);
    private static final List<String> SLOTS = List.of("头盔", "胸甲", "护腿", "靴子");

    private ItemsApi() {
    }

    static void install(NumenApi numen) {
        numen.api("items", "物品：手里拿的是什么、身上穿了什么、饰品栏戴着什么、背包和身边的箱子里有什么、什么附魔、还剩多少耐久。",
                ItemsApi.class);
    }

    // ---------------------------------------------------------------- shapes

    /** One held item, described. */
    @Doc("一件手里的物品。")
    public record ItemView(
            @Doc("物品 id，比如 'minecraft:diamond_sword'。") String id,
            @Doc("显示名（可能被改名过）。") String name,
            @Doc("一堆几个。") int count,
            @Doc("有没有耐久条；false 表示这东西不会坏。") boolean damageable,
            @Doc("还剩多少耐久；不会坏的东西是 -1。") int durability,
            @Doc("耐久上限；不会坏的东西是 -1。") int durability_max,
            @Doc("还剩百分之几；不会坏的东西是 -1。") int percent,
            @Doc("附魔列表，形如 'sharpness 5'；附魔书读的是书里存的那条。") List<String> enchants,
            @Doc("身上挂了哪些自定义数据（最多 8 个键名）。") List<String> tags,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Options naming the hand. */
    @Doc("哪只手。")
    public record HandOpt(
            @Doc("'main' 或 'off'；不写就是主手。") Optional<String> hand) {
    }

    /** Options for reading the nearest container. */
    @Doc("往多大范围里找箱子。")
    public record ChestOpt(
            @Doc("扫描半径（格），1-" + MAX_CHEST_RADIUS + "；不写就是 " + CHEST_RADIUS + "。") Optional<Integer> radius) {
    }

    /** Her own four armour pieces. */
    @Doc("她自己身上穿的护甲。")
    public record Worn(
            @Doc("穿着的护甲，由头到脚；空槽不在这个表里。") List<ItemView> pieces,
            @Doc("空着的槽位名，形如 ['胸甲', '护腿']。") List<String> empty,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** Her own curio slots. */
    @Doc("她自己身上戴的饰品。")
    public record Curio(
            @Doc("戴着的饰品，和 slots 一一对应。") List<ItemView> items,
            @Doc("每件饰品所在的槽位名，形如 ['戒指', '项链']。") List<String> slots,
            @Doc("整个空着的槽位名，形如 ['腰带', '戒指×2']。") List<String> empty,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** What is in her own main backpack. */
    @Doc("她自己的背包里有什么。")
    public record Bag(
            @Doc("装了几格（36 格里非空的数量）。") int used,
            @Doc("空着几格。") int empty,
            @Doc("一共几种不同的物品。") int kinds,
            @Doc("装着的物品，数量多的在前，最多 24 条。") List<ItemView> items,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** What is in a box: the nearest container, or her ender chest. */
    @Doc("一个箱子里的东西。")
    public record Box(
            @Doc("是什么箱子，比如 'minecraft:chest'；末影箱固定是 'minecraft:ender_chest'。") String block,
            @Doc("坐标，形如 '120, 64, -330'；末影箱是 '她身上'。") String pos,
            @Doc("离她几格；末影箱是 0。") int distance,
            @Doc("一共多少格。") int slots,
            @Doc("装了几格。") int used,
            @Doc("一共几种不同的物品。") int kinds,
            @Doc("装着的物品，数量多的在前，最多 24 条。") List<ItemView> items,
            @Doc("这个半径里另外还有几个容器；末影箱永远是 0。") int others,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    // ---------------------------------------------------------------- calls

    @Fn("她自己手里拿的是什么：物品、附魔、耐久。")
    @Example("items.items.held()")
    @Example("items.items.held({hand = \"off\"})")
    @Note("只读。空手会直接说「空手」，不是报错。")
    public static ItemView held(ServerCall call, HandOpt args) {
        String hand = args == null ? "main" : args.hand().orElse("main").trim().toLowerCase();
        ItemStack stack = "off".equals(hand) || "副手".equals(hand)
                ? call.her().m_21206_()
                : call.her().m_21205_();
        return describe(stack, "你");
    }

    @Fn("她自己身上穿了什么：头盔、胸甲、护腿、靴子，各带附魔和耐久。")
    @Example("items.items.armor()")
    @Note("只读。空槽记在 empty 里，不是报错；护甲和手里拿的是两回事，别混着说。")
    public static Worn armor(ServerCall call) {
        LivingEntity body = call.her();
        String who = "你";
        List<ItemView> pieces = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        for (int i = 0; i < SLOTS.size(); i++) {
            String slot = SLOTS.get(i);
            ItemStack stack = body.m_6844_(EQUIPMENT.get(i));
            if (stack == null || stack.m_41619_()) {
                empty.add(slot);
                continue;
            }
            pieces.add(describe(stack, who, "的" + slot + "是 "));
        }
        if (pieces.isEmpty()) {
            return new Worn(pieces, empty, who + "身上什么护甲都没穿");
        }
        StringBuilder line = new StringBuilder();
        line.append(who).append("穿着 ").append(pieces.size()).append(" 件护甲：");
        for (int i = 0; i < pieces.size(); i++) {
            if (i > 0) {
                line.append("；");
            }
            ItemView v = pieces.get(i);
            line.append(v.name());
            if (v.percent() >= 0) {
                line.append("（耐久 ").append(v.percent()).append("%）");
            }
            if (!v.enchants().isEmpty()) {
                line.append("（").append(String.join("、", v.enchants())).append("）");
            }
        }
        if (!empty.isEmpty()) {
            line.append("。空槽：").append(String.join("、", empty));
        }
        return new Worn(pieces, empty, line.toString());
    }

    @Fn("她自己身上戴的饰品：戒指、项链、腰带、小饰品这些，各带附魔和耐久，按佩戴顺序排。")
    @Example("items.items.curio()")
    @Note("只读。只读正式栏位，不含纯外观位。饰品和护甲是两个栏位，别混着说；认不出来的槽位名原样给英文 id。")
    @Note("多格的槽位在 empty 里写成「戒指×2」，一眼能看出还有几个空位。")
    public static Curio curio(ServerCall call) {
        LivingEntity body = call.her();
        String who = "你";

        List<ItemView> items = new ArrayList<>();
        List<String> slots = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        for (CurioBridge.Slot slot : CurioBridge.slots(body)) {
            String slotName = CURIO_NAMES.getOrDefault(slot.id(), slot.id());
            boolean worn = false;
            for (int i = 0; i < slot.size() && items.size() < MAX_CURIOS; i++) {
                ItemStack stack = CurioBridge.at(slot.stacks(), i);
                if (stack == null || stack.m_41619_()) {
                    continue;
                }
                items.add(describe(stack, who, "的" + slotName + "是 "));
                slots.add(slotName);
                worn = true;
            }
            if (!worn && slot.size() > 0) {
                empty.add(slot.size() > 1 ? slotName + "×" + slot.size() : slotName);
            }
        }

        if (items.isEmpty()) {
            return new Curio(items, slots, empty, who + "身上一件饰品都没戴");
        }
        StringBuilder line = new StringBuilder();
        line.append(who).append("戴着 ").append(items.size()).append(" 件饰品：");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                line.append("；");
            }
            line.append(items.get(i).line());
        }
        if (!empty.isEmpty()) {
            line.append("。空着：").append(String.join("、", empty));
        }
        return new Curio(items, slots, empty, line.toString());
    }

    @Fn("她自己背包里装了什么：36 格逐一列，附魔、耐久、自定义数据都带着。")
    @Example("items.items.bag()")
    @Note("只读。只看主背包 36 格；护甲看 items.armor，饰品看 items.curio，手里拿的看 items.held。")
    @Note("数量多的排前面，最多 24 条；被截掉的部分用 line 里的「还有 N 条没列」提醒。模组背包不归这里，看 sb。")
    public static Bag bag(ServerCall call) {
        NumenPlayer her = call.her();
        String who = "你";
        NonNullList<ItemStack> main = her.m_150109_().f_35974_;

        List<ItemView> rows = new ArrayList<>();
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        int used = 0;
        for (ItemStack stack : main) {
            if (stack == null || stack.m_41619_()) {
                continue;
            }
            used++;
            String id = idOf(stack);
            samples.putIfAbsent(id, stack);
            totals.merge(id, Integer.valueOf(stack.m_41613_()), Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            rows.add(describe(samples.get(entry.getKey()), who, "的背包里有 ", entry.getValue().intValue()));
        }

        int empty = main.size() - used;
        rows.sort(Comparator.comparingInt(ItemView::count).reversed()
                .thenComparing(ItemView::id));
        int shown = Math.min(rows.size(), MAX_BAG_ROWS);
        List<ItemView> kept = new ArrayList<>(rows.subList(0, shown));

        if (kept.isEmpty()) {
            return new Bag(0, empty, 0, kept, who + "的背包是空的（" + main.size() + " 格全空）");
        }

        StringBuilder line = new StringBuilder();
        line.append(who).append("的背包：").append(main.size()).append(" 格里装了 ").append(used)
                .append(" 格、").append(totals.size()).append(" 种东西");
        line.append("；最多的是 ").append(topOf(totals));
        if (rows.size() > shown) {
            line.append("；还有 ").append(rows.size() - shown).append(" 条没列");
        }
        return new Bag(used, empty, totals.size(), kept, line.toString());
    }

    @Fn("离她最近的一个箱子/桶/漏斗里装了什么：坐标、距离、容量、每种东西多少个。")
    @Example("items.items.chest()")
    @Example("items.items.chest({radius = 12})")
    @Note("只读。只扫她周围已加载的区块，不会为了读箱子去加载新区块。")
    @Note("优先报最近一个非空的容器；半径里全是空容器时才报最近的那个空的。模组机器的内部库存不归这里。")
    public static Box chest(ServerCall call, ChestOpt args) {
        int radius = args == null ? CHEST_RADIUS : args.radius().orElse(CHEST_RADIUS);
        radius = Math.max(1, Math.min(radius, MAX_CHEST_RADIUS));

        ServerLevel level = levelOf(call);
        BlockPos her = call.her().m_20183_();

        Container nearest = null;
        BlockPos nearestPos = null;
        double nearestDist = Double.MAX_VALUE;
        int found = 0;
        for (LevelChunk chunk : chunks(level, her, radius)) {
            for (Map.Entry<BlockPos, BlockEntity> entry : chunk.m_62954_().entrySet()) {
                BlockPos pos = entry.getKey();
                if (Math.abs(pos.m_123341_() - her.m_123341_()) > radius
                        || Math.abs(pos.m_123343_() - her.m_123343_()) > radius
                        || Math.abs(pos.m_123342_() - her.m_123342_()) > radius) {
                    continue;
                }
                if (!(entry.getValue() instanceof Container container)) {
                    continue;
                }
                found++;
                boolean empty = container.m_7983_();
                double dist = distance(her, pos);
                // a non-empty box anywhere in range beats an empty one closer by
                boolean better;
                if (nearest == null) {
                    better = true;
                } else if (nearest.m_7983_() && !empty) {
                    better = true;
                } else {
                    better = nearest.m_7983_() == empty && dist < nearestDist;
                }
                if (better) {
                    nearest = container;
                    nearestPos = pos;
                    nearestDist = dist;
                }
            }
        }

        if (nearest == null || nearestPos == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "身边没有箱子",
                    "半径 " + radius + " 格内没扫到箱子、桶、漏斗这类容器；走远一点再用 items.items.chest({radius = 12})。",
                    null);
        }

        String block = "minecraft:chest";
        try {
            net.minecraft.resources.ResourceLocation key =
                    ForgeRegistries.BLOCKS.getKey(level.m_8055_(nearestPos).m_60734_());
            if (key != null) {
                block = key.toString();
            }
        } catch (Throwable ignored) {
            // a container whose block state will not answer: keep the generic id
        }
        int dist = (int) Math.round(nearestDist);
        String pos = coord(nearestPos);
        int slots = nearest.m_6643_();

        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        int used = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = nearest.m_8020_(i);
            if (stack == null || stack.m_41619_()) {
                continue;
            }
            used++;
            String id = idOf(stack);
            samples.putIfAbsent(id, stack);
            totals.merge(id, Integer.valueOf(stack.m_41613_()), Integer::sum);
        }

        List<ItemView> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            ItemStack sample = samples.get(entry.getKey());
            rows.add(describe(sample, "这个箱子", "里装着 ", entry.getValue().intValue()));
        }
        rows.sort(Comparator.comparingInt(ItemView::count).reversed()
                .thenComparing(ItemView::id));
        int shown = Math.min(rows.size(), MAX_BOX_ROWS);
        List<ItemView> kept = new ArrayList<>(rows.subList(0, shown));

        String where = pos + "，离你 " + dist + " 格";
        String line;
        if (used == 0) {
            line = "最近的箱子在 " + where + "，是空的（" + slots + " 格全空）";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("最近的箱子在 ").append(where).append("：").append(slots).append(" 格里装了 ")
                    .append(used).append(" 格、").append(totals.size()).append(" 种东西；最多的是 ")
                    .append(topOf(totals));
            if (rows.size() > shown) {
                sb.append("；还有 ").append(rows.size() - shown).append(" 种没列");
            }
            line = sb.toString();
        }
        return new Box(block, pos, dist, slots, used, totals.size(), kept,
                Math.max(0, found - 1), line);
    }

    @Fn("她自己的末影箱里装了什么：27 格，跨维度跟着她走。")
    @Example("items.items.ender()")
    @Note("只读。末影箱是玩家自带的 27 格，不用身边有方块；和 items.items.bag 是两个不同的箱子。")
    public static Box ender(ServerCall call) {
        NumenPlayer her = call.her();
        Container ender = her.m_36327_();
        int slots = ender.m_6643_();

        Map<String, ItemStack> samples = new LinkedHashMap<>();
        Map<String, Integer> totals = new LinkedHashMap<>();
        int used = 0;
        for (int i = 0; i < slots; i++) {
            ItemStack stack = ender.m_8020_(i);
            if (stack == null || stack.m_41619_()) {
                continue;
            }
            used++;
            String id = idOf(stack);
            samples.putIfAbsent(id, stack);
            totals.merge(id, Integer.valueOf(stack.m_41613_()), Integer::sum);
        }

        List<ItemView> rows = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : totals.entrySet()) {
            ItemStack sample = samples.get(entry.getKey());
            rows.add(describe(sample, "这个末影箱", "里装着 ", entry.getValue().intValue()));
        }
        rows.sort(Comparator.comparingInt(ItemView::count).reversed()
                .thenComparing(ItemView::id));
        int shown = Math.min(rows.size(), MAX_BOX_ROWS);
        List<ItemView> kept = new ArrayList<>(rows.subList(0, shown));

        String line;
        if (used == 0) {
            line = "她的末影箱是空的（" + slots + " 格全空）";
        } else {
            StringBuilder sb = new StringBuilder();
            sb.append("她的末影箱：").append(slots).append(" 格里装了 ").append(used).append(" 格、")
                    .append(totals.size()).append(" 种东西；最多的是 ").append(topOf(totals));
            if (rows.size() > shown) {
                sb.append("；还有 ").append(rows.size() - shown).append(" 种没列");
            }
            line = sb.toString();
        }
        return new Box("minecraft:ender_chest", "她身上", 0, slots, used, totals.size(), kept, 0, line);
    }

    // ---------------------------------------------------------------- guts

    private static ServerLevel levelOf(ServerCall call) {
        Level level = call.her().m_9236_();
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new ApiError(ErrorKind.FAILED, "这里不是服务端世界",
                    "物品只有服务端能读；她在服务端，正常不会遇到。", null);
        }
        return serverLevel;
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

    private static double distance(BlockPos a, BlockPos b) {
        double dx = a.m_123341_() - b.m_123341_();
        double dy = a.m_123342_() - b.m_123342_();
        double dz = a.m_123343_() - b.m_123343_();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static String coord(BlockPos pos) {
        return pos.m_123341_() + ", " + pos.m_123342_() + ", " + pos.m_123343_();
    }

    private static String idOf(ItemStack stack) {
        return String.valueOf(BuiltInRegistries.f_257033_.m_7981_(stack.m_41720_()));
    }

    /** {@code id×count、id×count、id×count} for the three biggest piles. */
    private static String topOf(Map<String, Integer> totals) {
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(totals.entrySet());
        ranked.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed()
                .thenComparing(Map.Entry::getKey));
        List<String> top = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : ranked) {
            if (top.size() >= 3) {
                break;
            }
            top.add(entry.getKey() + "×" + entry.getValue());
        }
        return String.join("、", top);
    }

    private static ItemView describe(ItemStack stack, String who) {
        return describe(stack, who, "手里是 ");
    }

    private static ItemView describe(ItemStack stack, String who, String placement) {
        return describe(stack, who, placement, stack == null ? 0 : stack.m_41613_());
    }

    /** Same, with the pile size overridden — used when several slots of one thing are summed. */
    private static ItemView describe(ItemStack stack, String who, String placement, int count) {
        if (stack == null || stack.m_41619_()) {
            return new ItemView("", "", 0, false, -1, -1, -1, List.of(), List.of(), who + "是空手");
        }
        String id = idOf(stack);
        // 走 Zh（中文名总表）：表里没有才退回游戏给的显示名
        String name = Zh.name(stack);
        if (count <= 0) {
            count = stack.m_41613_();
        }

        // m_41763_ = isDamageableItem; m_41768_ is isDamaged and would hide a pristine tool's durability
        boolean damageable = stack.m_41763_();
        int max = damageable ? stack.m_41776_() : -1;
        int left = damageable ? Math.max(0, max - stack.m_41773_()) : -1;
        int percent = damageable && max > 0 ? (int) Math.round(left * 100.0D / max) : -1;

        List<String> enchants = readEnchants(stack);
        List<String> tags = readTagKeys(stack);

        StringBuilder line = new StringBuilder();
        line.append(who).append(placement).append(name).append("（").append(id).append("）");
        if (count > 1) {
            line.append("，").append(count).append(" 个");
        }
        if (!enchants.isEmpty()) {
            line.append("，附魔 ").append(String.join("、", enchants));
        }
        if (damageable) {
            line.append("，耐久 ").append(left).append("/").append(max).append("（").append(percent).append("%）");
        }
        return new ItemView(id, name, count, damageable, left, max, percent, enchants, tags, line.toString());
    }

    /**
     * Enchantments on the item, plus the ones stored in a book.
     *
     * <p>An enchanted book keeps nothing under {@code Enchantments}: everything lives in
     * {@code StoredEnchantments}, so a reader that only knows the first key says a sharpness V book
     * has no enchantments at all.
     */
    private static List<String> readEnchants(ItemStack stack) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            CompoundTag tag = stack.m_41783_();
            if (tag == null) {
                return out;
            }
            readEnchantList(tag, "Enchantments", out, seen);
            readEnchantList(tag, "StoredEnchantments", out, seen);
        } catch (Throwable ignored) {
            // a modded stack with a broken tag: report no enchantments rather than failing the read
        }
        return out;
    }

    private static void readEnchantList(CompoundTag tag, String key, List<String> out, Set<String> seen) {
        if (!tag.m_128441_(key)) {
            return;
        }
        Tag raw = tag.m_128423_(key);
        if (!(raw instanceof ListTag list)) {
            return;
        }
        for (int i = 0; i < list.size() && out.size() < MAX_ENCHANTS; i++) {
            CompoundTag entry = list.m_128728_(i);
            if (entry == null) {
                continue;
            }
            String id = entry.m_128461_("id");
            int level = entry.m_128454_("lvl") == 0L ? 1 : (int) entry.m_128454_("lvl");
            String shortId = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            String rendered = shortId + " " + level;
            if (seen.add(rendered)) {
                out.add(rendered);
            }
        }
    }

    private static List<String> readTagKeys(ItemStack stack) {
        List<String> out = new ArrayList<>();
        try {
            CompoundTag tag = stack.m_41783_();
            if (tag == null) {
                return out;
            }
            for (String key : tag.m_128431_()) {
                if (out.size() >= MAX_TAG_KEYS) {
                    break;
                }
                if ("Enchantments".equals(key) || "StoredEnchantments".equals(key)
                        || "display".equals(key) || "Damage".equals(key)) {
                    continue;
                }
                out.add(key);
            }
        } catch (Throwable ignored) {
            // older stack without a key set: report none
        }
        return out;
    }

    // ---------------------------------------------------------------- curios

    /**
     * Reads the Curios accessory slots without depending on Curios at compile time.
     *
     * <p>Everything here is reflective on purpose: {@code items.*} must keep loading on a server
     * where Curios is not installed, and must not break when a future Curios renames an internal
     * class. The lookups that matter are {@code CuriosApi.getCuriosInventory(LivingEntity)},
     * {@code CuriosApi.getSlot(String)}, {@code ICuriosItemHandler.getCurios()},
     * {@code ICurioStacksHandler.getStacks()} and {@code IDynamicStackHandler.getStackInSlot(int)} —
     * all verified with javap against curios-forge-5.14.1+1.20.1.jar.
     *
     * <p>Slots are sorted by Curios' own {@code order}, which is also the order the engine walks when
     * {@code numen.gear.wear} picks a free slot — so the report reads in wearing order.
     */
    private static final class CurioBridge {

        private static final String API = "top.theillusivec4.curios.api.CuriosApi";
        private static final String ITEM_HANDLER =
                "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";
        private static final String STACKS_HANDLER =
                "top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler";
        private static final String STACKS =
                "top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler";
        private static final String SLOT_TYPE = "top.theillusivec4.curios.api.type.ISlotType";

        private static boolean ready;
        private static Method getInventory;
        private static Method getCurios;
        private static Method getIdentifier;
        private static Method getStacks;
        private static Method getSlots;
        private static Method getStackInSlot;
        private static Method resolveLazy;
        private static Method getSlotType;
        private static Method slotOrder;

        private CurioBridge() {
        }

        /** One curio slot: its id, Curios order, how many places it has, and its stacks. */
        private record Slot(String id, int order, int size, Object stacks) {
        }

        private static synchronized boolean init() {
            if (ready) {
                return true;
            }
            try {
                Class<?> api = Class.forName(API);
                Class<?> itemHandler = Class.forName(ITEM_HANDLER);
                Class<?> stacksHandler = Class.forName(STACKS_HANDLER);
                Class<?> stacks = Class.forName(STACKS);
                Class<?> slotType = Class.forName(SLOT_TYPE);
                getInventory = api.getMethod("getCuriosInventory", LivingEntity.class);
                getCurios = itemHandler.getMethod("getCurios");
                getIdentifier = stacksHandler.getMethod("getIdentifier");
                getStacks = stacksHandler.getMethod("getStacks");
                getSlots = stacks.getMethod("getSlots");
                getStackInSlot = stacks.getMethod("getStackInSlot", int.class);
                resolveLazy = Class.forName("net.minecraftforge.common.util.LazyOptional")
                        .getMethod("resolve");
                getSlotType = api.getMethod("getSlot", String.class);
                slotOrder = slotType.getMethod("getOrder");
                ready = true;
                return true;
            } catch (Throwable missing) {
                return false;
            }
        }

        /** Her curio slots, in wearing order. */
        static List<Slot> slots(LivingEntity body) {
            if (!init()) {
                throw new ApiError(ErrorKind.FAILED, "这台服务器没装饰品模组（Curios）",
                        "没有饰品栏可读；说明要说的东西不在这台服务器上，别硬猜。", null);
            }
            List<Slot> out = new ArrayList<>();
            Object handler = handler(body);
            if (handler == null) {
                return out;
            }
            try {
                Object raw = getCurios.invoke(handler);
                if (!(raw instanceof Map<?, ?> map)) {
                    return out;
                }
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getKey() == null || entry.getValue() == null) {
                        continue;
                    }
                    Object stacks = getStacks.invoke(entry.getValue());
                    if (stacks == null) {
                        continue;
                    }
                    String id = String.valueOf(getIdentifier.invoke(entry.getValue()));
                    out.add(new Slot(id, orderOf(id), count(stacks), stacks));
                }
            } catch (Throwable broken) {
                // a half-initialised capability: report no slots rather than failing the read
                return List.of();
            }
            out.sort(Comparator.comparingInt(Slot::order).thenComparing(Slot::id));
            return out;
        }

        /** Curios orders this slot after every other one when it is not registered yet. */
        private static int orderOf(String id) {
            try {
                Object optional = getSlotType.invoke(null, id);
                if (optional instanceof Optional<?> opt && opt.isPresent()) {
                    Object order = slotOrder.invoke(opt.get());
                    return order instanceof Integer i ? i : Integer.MAX_VALUE;
                }
            } catch (Throwable broken) {
                // a slot that predates a full Curios init: sort it last
            }
            return Integer.MAX_VALUE;
        }

        /** How many slots that handler holds; 0 when the read fails. */
        static int count(Object stacks) {
            try {
                Object n = getSlots.invoke(stacks);
                return n instanceof Integer i ? Math.max(0, i) : 0;
            } catch (Throwable broken) {
                return 0;
            }
        }

        /** The stack in one slot, or an empty stack when the read fails. */
        static ItemStack at(Object stacks, int index) {
            try {
                Object stack = getStackInSlot.invoke(stacks, index);
                return stack instanceof ItemStack item ? item : ItemStack.f_41583_;
            } catch (Throwable broken) {
                return ItemStack.f_41583_;
            }
        }

        private static Object handler(LivingEntity body) {
            try {
                Object lazy = getInventory.invoke(null, body);
                if (lazy == null) {
                    return null;
                }
                Object resolved = resolveLazy.invoke(lazy);
                return resolved instanceof Optional<?> optional ? optional.orElse(null) : null;
            } catch (Throwable broken) {
                // a body without the capability, e.g. a mob Curios never attached to
                return null;
            }
        }
    }
}
