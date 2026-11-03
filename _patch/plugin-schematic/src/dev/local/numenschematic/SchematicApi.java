package dev.local.numenschematic;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;

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

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code schematic}: read the Engineer's blueprint (Engineered Schematics) a companion carries
 * and turn it into a build list she can actually follow.
 *
 * <p>The mod's own renderer draws a ghost of the machine around the block the blueprint was
 * placed on. That ghost is computed from three things the server also has: the IE multiblock
 * template ({@code MultiblockHandler.getByUniqueName}), the item's rotation/mirror, and the
 * anchor position stored in the item's {@code settings} tag. This class runs the same
 * projection the client runs ({@code SchematicProjection.processAll}) and then compares every
 * ghost cell with the real world, so she gets "these 12 cells are still empty, start with the
 * one at 441,88,-562" instead of a picture she cannot see.
 *
 * <p>Everything here is a read: nothing is placed. Placement stays with {@code numen.use.block}
 * so the usual reach, item and permission checks apply.
 */
public final class SchematicApi {

    private static final int MAX_CELLS = 48;
    private static final String MB_HANDLER = "blusunrize.immersiveengineering.api.multiblocks.MultiblockHandler";
    private static final String MB_IFACE = "blusunrize.immersiveengineering.api.multiblocks.MultiblockHandler$IMultiblock";

    private SchematicApi() {
    }

    static void install(NumenApi numen) {
        numen.api("schematic",
                "图纸：小D 手上的工程师图纸投影落在哪、按图还差哪几块、从哪块开始放、搭的时候站哪、什么时候可以敲锤成型。",
                SchematicApi.class);
    }

    /** One line for the boot log: can we see IE's multiblock registry. */
    static String health() {
        return "reflection multiblock=" + (type(MB_HANDLER) != null) + "/" + (type(MB_IFACE) != null);
    }

    // ---------------------------------------------------------------- options

    /** Options for {@code schematic.cells}. */
    @Doc("列哪几格；不写就是全部还差的和最多 48 格明细。")
    public record CellsOpt(
            @Doc("最多列几格，1-96。") @Omitted("48") Optional<Integer> max,
            @Doc("只列还差的（true，默认）还是连已经放对的也列（false）。") @Omitted("true")
            Optional<Boolean> missing_only) {
    }

    // ---------------------------------------------------------------- shapes

    /** One grid position, in the same shape the engine's own calls take. */
    @Doc("一格坐标。")
    public record At(
            @Doc("x。") int x,
            @Doc("y。") int y,
            @Doc("z。") int z) {
    }

    /** One cell of the blueprint, compared with what is in the world right now. */
    @Doc("图纸上的一格：该放什么、现在是什么、对上没有。")
    public record Cell(
            @Doc("这一格在哪。") At at,
            @Doc("这一格该放的方块（中文名 / id）。") String want,
            @Doc("这一格现在是什么。") String have,
            @Doc("ok = 已经放对；missing = 还空着；wrong = 放的是别的东西；half = 种类对但朝向/形态不对。")
            String state) {
    }

    /** What the blueprint says, and how far along the build is. */
    @Doc("手上图纸的总账：哪座机器、投影锚点、还差几块、下一块放哪、站哪、能不能敲锤。")
    public record Plan(
            @Doc("图纸上的机器名。") String machine,
            @Doc("机器 id。") String id,
            @Doc("成型后占多大，形如 3x3x3。") String size,
            @Doc("图纸的朝向：无 / 顺时针90 / 180 / 逆时针90。") String rotation,
            @Doc("图纸是不是镜像的。") boolean mirrored,
            @Doc("投影的锚点（图纸记下的那一格）。") At anchor,
            @Doc("整座机器一共几格要放。") int blocks,
            @Doc("已经放对的格数。") int placed,
            @Doc("还空着的格数。") int missing,
            @Doc("放错的格数。") int wrong,
            @Doc("下一块该放哪；没有要放的就是空。") Optional<Cell> next,
            @Doc("敲锤的时候她得面朝哪边（北/东/南/西）——沉浸工程按她敲那一刻的朝向决定整座怎么转，朝错了敲不响。") String face,
            @Doc("敲锤的时候站在哪：结构外面、正对着那面，离两格。") Optional<At> frame,
            @Doc("拿工程师锤对哪一格右键；这是这台的触发块，敲别的方块没有反应。") Optional<At> hammer,
            @Doc("走过去的那一行照抄就行的调用（用 arrive = \"near\"，因为站位那格本身是空气，没法对着它点）。") String stand_call,
            @Doc("敲锤的那一行照抄就行的调用；先站好、面朝 face 再执行。") String knock_call,
            @Doc("搭的时候她该站哪（结构外面）；不写就是她现在已经站在外面了。") Optional<At> stand,
            @Doc("每一格都对上了没有——对上了就可以拿工程师锤敲一下成型。") boolean ready,
            @Doc("一句话结论。") String line) {
    }

    /** The full cell list, for when she needs to walk the grid. */
    @Doc("图纸的逐格清单：每格该放什么、现在是什么，按「先下后上、先远后近」排好。")
    public record Cells(
            @Doc("图纸上的机器名。") String machine,
            @Doc("成型后占多大。") String size,
            @Doc("整座机器一共几格要放。") int blocks,
            @Doc("已经放对的格数。") int placed,
            @Doc("还空着的格数。") int missing,
            @Doc("放错的格数。") int wrong,
            @Doc("明细（最多 max 格）。") List<Cell> cells,
            @Doc("一句话结论。") String line) {
    }

    // ---------------------------------------------------------------- functions

    @Fn("看她手上的图纸：是哪座机器、投影落在哪、还差几块、下一块该放哪、她该站哪、能不能敲锤了。")
    @Example("schematic.schematic.here()")
    @Note("只读。图纸在背包或副手都行；没有图纸、图纸还没放下、或者图上的机器 IE 不认识都会报错。")
    @Note("「下一块」按先下后上、同一层先远后近排——从结构外往回收，才不会被自己刚放的方块围住。")
    @Note("face 是硬条件：沉浸工程拿她敲锤那一刻面朝的方向决定整座怎么转；方向不对，材料一格不差也成型不了，而且什么都不提示。")
    @Note("ready = true 只说明每一格都对上了；成型还得她拿工程师锤、站到结构外、面朝 face 那一面，对 hammer 那一格右键。")
    @Note("stand_call 和 knock_call 是两行照抄就能用的调用：先 stand_call 走过去（站位那格是空气，所以用 arrive = \"near\"，对着空气点会报 nothing there to click），站好面朝 face，再 knock_call。")
    @SeeAlso({"schematic.schematic.cells", "numen.use.block", "numen.move.to", "power.power.sides"})
    public static Plan here(ServerCall call) {
        Build build = read(call);
        int placed = 0;
        int missing = 0;
        int wrong = 0;
        for (Ghost ghost : build.ghosts) {
            switch (ghost.verdict) {
                case "ok" -> placed++;
                case "missing" -> missing++;
                default -> wrong++;
            }
        }
        Cell next = build.firstPending();
        Optional<At> stand = standFor(build, call.her());
        Optional<At> frame = frameFor(build, levelOf(call));
        Optional<At> hammer = Optional.ofNullable(build.trigger).map(SchematicApi::at);
        String standCall = frame.map(w -> "numen.move.to({x = " + w.x() + ", y = " + w.y()
                + ", z = " + w.z() + "}, {arrive = \"near\"})").orElse("");
        String knockCall = hammer.map(w -> "numen.use.block({x = " + w.x() + ", y = " + w.y()
                + ", z = " + w.z() + "})").orElse("");
        String face = faceName(build.face);
        boolean ready = missing == 0 && wrong == 0;

        StringBuilder line = new StringBuilder();
        line.append("手上的图纸是「").append(build.machine).append("」").append(build.size)
                .append("，朝向").append(build.rotation).append(build.mirrored ? "、镜像" : "")
                .append("，投影锚点在 ").append(coord(build.anchor)).append("；一共 ")
                .append(build.ghosts.size()).append(" 格，已对上 ").append(placed);
        if (ready) {
            line.append("——每一格都对上了。成型还得她自己敲：");
            frame.ifPresent(where -> line.append("站到 ").append(coord(where)).append("、"));
            line.append("面朝").append(face).append("，");
            if (hammer.isPresent()) {
                line.append("拿工程师锤对 ").append(coord(hammer.get())).append(" 右键（那是这台的触发块）");
            } else {
                line.append("拿工程师锤对结构外表面任意一块右键");
            }
            line.append("。朝错了方向敲是不会有任何反应的");
        } else {
            line.append("，还空着 ").append(missing);
            if (wrong > 0) {
                line.append("，另有 ").append(wrong).append(" 格放错了东西");
            }
            if (next != null) {
                line.append("；下一块放 ").append(coord(next.at())).append(" 的 ").append(next.want());
            }
            frame.ifPresent(where -> line.append("；从 ").append(coord(where))
                    .append(" 这一侧往外放（敲锤时要面朝").append(face).append("）"));
            if (stand.isPresent()) {
                line.append("；她现在站在结构里，先挪到 ").append(coord(stand.get())).append(" 再放");
            }
        }
        return new Plan(build.machine, build.id, build.size, build.rotation, build.mirrored,
                at(build.anchor), build.ghosts.size(), placed, missing, wrong,
                Optional.ofNullable(next), face, frame, hammer, standCall, knockCall, stand, ready,
                line.toString());
    }

    @Fn("把图纸上的每一格都列出来：该放什么、现在是什么、对上没有，按「先下后上、先远后近」排好。")
    @Example("schematic.schematic.cells()")
    @Example("schematic.schematic.cells({max = 96, missing_only = false})")
    @Note("只读。默认只列还差的和放错的，最多 48 条；把 missing_only 写成 false 连已经放对的也列。")
    @Note("她走路够不着的地方照样列出来——搭的时候用 numen.move.to(..., {arrive = \"use\"}) 挪过去再放。")
    @SeeAlso({"schematic.schematic.here", "numen.use.block", "numen.move.to"})
    public static Cells cells(ServerCall call, CellsOpt args) {
        int max = clamp(args == null || args.max().isEmpty() ? MAX_CELLS : args.max().get(), 1, 96);
        boolean missingOnly = args == null || args.missing_only().isEmpty() || args.missing_only().get();
        Build build = read(call);

        int placed = 0;
        int missing = 0;
        int wrong = 0;
        List<Cell> rows = new ArrayList<>();
        for (Ghost ghost : build.ghosts) {
            boolean ok = "ok".equals(ghost.verdict);
            if (ok) {
                placed++;
            } else if ("missing".equals(ghost.verdict)) {
                missing++;
            } else {
                wrong++;
            }
            if (ok && missingOnly) {
                continue;
            }
            if (rows.size() >= max) {
                continue;
            }
            rows.add(new Cell(at(ghost.at), ghost.want, ghost.have, ghost.verdict));
        }

        String line;
        if (missing == 0 && wrong == 0) {
            line = "「" + build.machine + "」" + build.ghosts.size() + " 格全部对上了——可以敲锤成型";
        } else {
            line = "「" + build.machine + "」" + build.ghosts.size() + " 格：对上 " + placed
                    + "，空着 " + missing + (wrong > 0 ? "，放错 " + wrong : "");
            if (rows.size() >= max) {
                line = line + "（明细只列了前 " + max + " 条，要看全就把 max 调大）";
            }
        }
        return new Cells(build.machine, build.size, build.ghosts.size(), placed, missing, wrong, rows, line);
    }

    // ---------------------------------------------------------------- reading the blueprint

    /** Everything one blueprint read produces. */
    private static final class Build {
        final String machine;
        final String id;
        final String size;
        final String rotation;
        final boolean mirrored;
        final BlockPos anchor;
        final List<Ghost> ghosts;
        /** Where IE starts laying the template out: the block the ghost is centred on. */
        final BlockPos origin;
        /** The one cell the engineer's hammer has to be used on. */
        final BlockPos trigger;
        /** The way she has to be looking when she swings it. */
        final Direction face;

        Build(String machine, String id, String size, String rotation, boolean mirrored,
              BlockPos anchor, List<Ghost> ghosts, BlockPos origin, BlockPos trigger, Direction face) {
            this.machine = machine;
            this.id = id;
            this.size = size;
            this.rotation = rotation;
            this.mirrored = mirrored;
            this.anchor = anchor;
            this.ghosts = ghosts;
            this.origin = origin;
            this.trigger = trigger;
            this.face = face;
        }

        /** Build order: lowest layer first, and within a layer the cell farthest from her first. */
        Cell firstPending() {
            Ghost best = null;
            for (Ghost ghost : ghosts) {
                if ("ok".equals(ghost.verdict)) {
                    continue;
                }
                if (best == null || BEFORE.compare(ghost, best) < 0) {
                    best = ghost;
                }
            }
            return best == null ? null : new Cell(at(best.at), best.want, best.have, best.verdict);
        }
    }

    /** Order used for both the list and "the next block": y first, then far-to-near. */
    private static final Comparator<Ghost> BEFORE = Comparator
            .comparingInt((Ghost ghost) -> ghost.at.m_123342_())
            .thenComparingDouble(ghost -> -ghost.reach);

    /** One ghost cell with the world already checked against it. */
    private static final class Ghost {
        final BlockPos at;
        final String want;
        final String have;
        final String verdict;
        final double reach;

        Ghost(BlockPos at, String want, String have, String verdict, double reach) {
            this.at = at;
            this.want = want;
            this.have = have;
            this.verdict = verdict;
            this.reach = reach;
        }
    }

    private static Build read(ServerCall call) {
        ServerLevel level = levelOf(call);
        NumenPlayer her = call.her();
        ItemStack blueprint = blueprintOf(her);
        CompoundTag settings = settingsOf(blueprint);
        String id = settings.m_128461_("multiblock");
        if (id.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这张图纸还没选机器",
                    "图纸要先在工作台上选好要建的机器（或者用别的图纸），再来读投影", null);
        }
        if (!settings.m_128471_("placed")) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这张图纸还没放下",
                    "图纸要先放下、把投影摆到位置（右键移动、Shift+右键转），放下之后才有投影可读", null);
        }
        BlockPos anchor = readPos(settings);
        if (anchor == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "图纸上没有锚点坐标",
                    "把图纸拿在手上重新放下一次（在要建的位置右键），让它记下锚点", null);
        }
        Rotation rotation = Rotation.values()[clamp(settings.m_128451_("rotation"), 0, 3)];
        boolean mirrored = settings.m_128471_("mirror");

        Object multiblock = multiblock(id);
        Vec3i size = sizeOf(multiblock, level);
        String machine = displayName(multiblock, id);

        Projection projection = project(level, multiblock, rotation, mirrored, anchor, her);
        List<Ghost> ghosts = projection.ghosts;
        if (ghosts.isEmpty()) {
            throw new ApiError(ErrorKind.NOT_FOUND, "「" + machine + "」的模板是空的",
                    "IE 或图纸模组这次没给出模板；换一张图纸或重启服务器再看", null);
        }
        return new Build(machine, id,
                size.m_123341_() + "x" + size.m_123342_() + "x" + size.m_123343_(),
                rotationName(rotation), mirrored, anchor, ghosts,
                projection.origin, projection.trigger, facingOf(rotation));
    }

    /** The blueprint: prefer the off hand, then the main inventory. */
    private static ItemStack blueprintOf(NumenPlayer her) {
        List<ItemStack> candidates = new ArrayList<>();
        for (ItemStack held : her.m_150109_().f_35976_) {
            candidates.add(held);
        }
        for (ItemStack stack : her.m_150109_().f_35974_) {
            candidates.add(stack);
        }
        for (ItemStack stack : candidates) {
            if (stack == null || stack.m_41619_()) {
                continue;
            }
            CompoundTag tag = stack.m_41783_();
            if (tag != null && tag.m_128441_("settings")) {
                return stack;
            }
        }
        throw new ApiError(ErrorKind.NOT_FOUND, "她身上没有工程师图纸",
                "把图纸放到她副手或者背包里，再来问一次", null);
    }

    private static CompoundTag settingsOf(ItemStack blueprint) {
        CompoundTag tag = blueprint.m_41783_();
        if (tag == null || !tag.m_128441_("settings")) {
            throw new ApiError(ErrorKind.NOT_FOUND, "这张图纸上没有投影数据",
                    "换一张图纸；如果所有图纸都这样，是图纸模组的版本对不上", null);
        }
        return tag.m_128469_("settings");
    }

    private static BlockPos readPos(CompoundTag settings) {
        if (!settings.m_128441_("pos")) {
            return null;
        }
        CompoundTag pos = settings.m_128469_("pos");
        if (!pos.m_128441_("x")) {
            return null;
        }
        return new BlockPos(pos.m_128451_("x"), pos.m_128451_("y"), pos.m_128451_("z"));
    }

    /**
     * Work out every cell of the machine, exactly where the client draws the ghost.
     *
     * <p>The ghost is not drawn from ES's {@code SchematicProjection} on the server: that class
     * builds an IE {@code TemplateWorld}, and its creator is only installed on the client, so
     * constructing it here throws. The maths behind it is small and public though, so it is done
     * here instead: rotate/mirror each template cell with the vanilla
     * {@code StructureTemplate.calculateRelativePosition}, subtract the same offset ES subtracts
     * (per rotation, plus half the footprint so the ghost sits centred on the anchor), and add the
     * anchor.
     *
     * <p>The anchor is the {@code pos} stored on the item, unchanged: ES's own renderer draws the
     * ghost at {@code settings.getPos().offset(info.tPos)}, so "the corner the template is placed
     * from" and "the block the blueprint was placed on" are the same point.
     */
    private static Projection project(ServerLevel level, Object multiblock, Rotation rotation,
                                      boolean mirrored, BlockPos anchor, NumenPlayer her) {
        Vec3i size = sizeOf(multiblock, level);
        Mirror mirror = mirrored ? Mirror.FRONT_BACK : Mirror.NONE;
        StructurePlaceSettings settings = new StructurePlaceSettings()
                .m_74377_(mirror)
                .m_74379_(rotation);
        BlockPos shift = shift(rotation, mirrored, size);

        List<BlockPos> cells = new ArrayList<>();
        List<BlockState> wants = new ArrayList<>();
        for (StructureBlockInfo block : template(level, multiblock)) {
            cells.add(StructureTemplate.m_74563_(settings, block.f_74675_()).m_121996_(shift));
            wants.add(block.f_74676_());
        }
        List<Ghost> ghosts = new ArrayList<>();
        if (cells.isEmpty()) {
            return new Projection(ghosts, anchor, null);
        }

        BlockPos origin = anchor;
        BlockPos herAt = her.m_20183_();
        for (int i = 0; i < cells.size(); i++) {
            BlockPos at = origin.m_121955_(cells.get(i));
            BlockState want = applyTo(wants.get(i), mirror, rotation);
            BlockState have = level.m_8055_(at);
            if (matchesLikeIE(want, have, level, at)) {
                ghosts.add(new Ghost(at, name(want), name(have), "ok", at.m_123331_(herAt)));
            } else if (have.m_60734_() == want.m_60734_()) {
                ghosts.add(new Ghost(at, name(want), name(have), "half", at.m_123331_(herAt)));
            } else if (have.m_60795_()) {
                ghosts.add(new Ghost(at, name(want), "空气", "missing", at.m_123331_(herAt)));
            } else {
                ghosts.add(new Ghost(at, name(want), name(have), "wrong", at.m_123331_(herAt)));
            }
        }
        BlockPos triggerOffset = triggerOffsetOf(multiblock);
        BlockPos trigger = triggerOffset == null ? null
                : origin.m_121955_(StructureTemplate.m_74563_(settings, triggerOffset).m_121996_(shift));
        return new Projection(ghosts, origin, trigger);
    }

    /**
     * The state IE expects at one cell: the template state mirrored, then rotated - exactly the
     * {@code BlockState.in.mirror(m).rotate(r)} that {@code TemplateMultiblock.applyToState} does
     * before handing it to {@code BlockMatcher}.
     *
     * <p>Both calls are found by signature (one parameter, returns a {@code BlockState}) rather
     * than by SRG name, so a Minecraft update that renames them cannot break this quietly.
     */
    private static BlockState applyTo(BlockState state, Mirror mirror, Rotation rotation) {
        BlockState out = state;
        if (mirror != Mirror.NONE) {
            out = bySignature(out, Mirror.class, mirror);
        }
        if (rotation != Rotation.NONE) {
            out = bySignature(out, Rotation.class, rotation);
        }
        return out;
    }

    private static BlockState bySignature(BlockState state, Class<?> param, Object arg) {
        try {
            for (Method m : BlockState.class.getMethods()) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length == 1 && ps[0] == param && m.getReturnType() == BlockState.class) {
                    Object out = m.invoke(state, arg);
                    return out instanceof BlockState block ? block : state;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fall through: an unnamed state would misreport one cell, not break the page
        }
        return state;
    }

    /**
     * Would IE accept {@code have} where the template says {@code want}?
     *
     * <p>Delegated to Immersive Engineering's own {@code BlockMatcher.matches(...)}, so the
     * tolerances stay IE's problem rather than ours: it allows extra fence connections, ignores
     * hopper facing and waterlogging, and accepts any block carrying the same metal tag
     * (storage / sheetmetal / treated wood / scaffolding / steel or aluminium fences). A machine
     * IE would refuse must not be reported as "可以敲了".
     *
     * <p>Falls back to a plain state comparison if the class is missing, so the checker still
     * works if the mod is not installed.
     */
    private static boolean matchesLikeIE(BlockState want, BlockState have, ServerLevel level,
                                         BlockPos at) {
        Method matcher = MATCHER;
        if (matcher == null && !MATCHER_MISSING) {
            matcher = matcher();
        }
        if (matcher != null) {
            try {
                Object result = matcher.invoke(null, want, have, level, at);
                Object allow = result == null ? null
                        : result.getClass().getMethod("isAllow").invoke(result);
                if (Boolean.TRUE.equals(allow)) {
                    return true;
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // fall through to the plain comparison
            }
        }
        return have.equals(want);
    }

    private static Method matcher() {
        try {
            Class<?> matcher = Class.forName("blusunrize.immersiveengineering.api.multiblocks.BlockMatcher");
            MATCHER = matcher.getMethod("matches", BlockState.class, BlockState.class, Level.class,
                    BlockPos.class);
            return MATCHER;
        } catch (ReflectiveOperationException | RuntimeException e) {
            MATCHER_MISSING = true;
            return null;
        }
    }

    private static Method MATCHER;
    private static boolean MATCHER_MISSING;

    /** The template cells of a multiblock, air removed (that is the set the ghost draws). */
    private static List<StructureBlockInfo> template(ServerLevel level, Object multiblock) {
        List<StructureBlockInfo> cells = new ArrayList<>();
        try {
            Object raw = multiblock.getClass().getMethod("getStructure", Level.class).invoke(multiblock, level);
            if (!(raw instanceof List<?> list)) {
                return cells;
            }
            for (Object cell : list) {
                if (cell instanceof StructureBlockInfo block && !block.f_74676_().m_60795_()) {
                    cells.add(block);
                }
            }
        } catch (ReflectiveOperationException e) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读不出这座机器的模板：" + reasons(e),
                    "把这张报错发给我；大概是沉浸工程的接口改了", null);
        }
        return cells;
    }

    /**
     * The offset ES subtracts from every rotated cell, copied from its {@code updateData}:
     * one shift per rotation/mirror pair, then half the footprint on x and z so the ghost ends up
     * centred on the anchor instead of hanging off one corner.
     */
    private static BlockPos shift(Rotation rotation, boolean mirrored, Vec3i size) {
        int x = 0;
        int z = 0;
        switch (rotation) {
            case CLOCKWISE_90 -> {
                x = 1 - size.m_123343_();
                z = mirrored ? 1 - size.m_123341_() : 0;
            }
            case CLOCKWISE_180 -> {
                x = mirrored ? 0 : 1 - size.m_123341_();
                z = 1 - size.m_123343_();
            }
            case COUNTERCLOCKWISE_90 -> {
                x = 0;
                z = mirrored ? 0 : 1 - size.m_123341_();
            }
            default -> {
                x = mirrored ? 1 - size.m_123341_() : 0;
                z = 0;
            }
        }
        int halfX = (rotation.ordinal() % 2 == 0 ? size.m_123341_() : size.m_123343_()) / 2;
        int halfZ = (rotation.ordinal() % 2 == 0 ? size.m_123343_() : size.m_123341_()) / 2;
        return new BlockPos(x + halfX, 0, z + halfZ);
    }

    /**
     * Which way she has to be looking when she hammers.
     *
     * <p>IE works it out backwards from the click: {@code HammerItem.onItemUseFirst} takes the
     * clicked face (or, on the top/bottom faces, her yaw) as the direction pointing at her, and
     * {@code TemplateMultiblock.createStructure} rotates the template by
     * {@code getRotationBetweenFacings(NORTH, direction.getOpposite())} - that is, the template's
     * north ends up along the way she is looking. So the blueprint's rotation and her facing are
     * the same number, just north-relative; this is that mapping unchanged.
     */
    private static Direction facingOf(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> Direction.EAST;
            case CLOCKWISE_180 -> Direction.SOUTH;
            case COUNTERCLOCKWISE_90 -> Direction.WEST;
            default -> Direction.NORTH;
        };
    }

    /** Room to stand: the cell and the one above it both empty. */
    private static boolean free(ServerLevel level, BlockPos pos) {
        return level.m_8055_(pos).m_60795_() && level.m_8055_(pos.m_6630_(1)).m_60795_();
    }

    /** One axis-parallel step, as a vector, without going through Direction's SRG names. */
    private static Vec3i step(Direction face, int n) {
        return switch (face) {
            case NORTH -> new Vec3i(0, 0, -n);
            case SOUTH -> new Vec3i(0, 0, n);
            case WEST -> new Vec3i(-n, 0, 0);
            case EAST -> new Vec3i(n, 0, 0);
            case UP -> new Vec3i(0, n, 0);
            case DOWN -> new Vec3i(0, -n, 0);
        };
    }

    private static String faceName(Direction face) {
        return switch (face) {
            case EAST -> "东";
            case SOUTH -> "南";
            case WEST -> "西";
            case NORTH -> "北";
            case UP -> "上";
            case DOWN -> "下";
        };
    }

    /**
     * Where to stand so that the machine is straight in front of her and the trigger is in reach.
     *
     * <p>Two blocks out from the trigger cell itself, on the far side from the machine: standing
     * there, the ray to the trigger comes in over its own front face, so the clicked face is the
     * one IE turns into the rotation above. Standing off to one side instead turns the whole
     * machine by 90 degrees and the hammer does nothing.
     */
    private static Optional<At> frameFor(Build build, ServerLevel level) {
        if (build.trigger != null && build.face != null) {
            BlockPos out = build.trigger.m_121955_(step(build.face.m_122424_(), 2));
            for (int dy = 0; dy <= 3; dy++) {
                BlockPos spot = out.m_6630_(dy);
                if (free(level, spot)) {
                    return Optional.of(at(spot));
                }
            }
            return Optional.of(at(out));
        }
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Ghost ghost : build.ghosts) {
            minX = Math.min(minX, ghost.at.m_123341_());
            maxX = Math.max(maxX, ghost.at.m_123341_());
            minY = Math.min(minY, ghost.at.m_123342_());
            minZ = Math.min(minZ, ghost.at.m_123343_());
            maxZ = Math.max(maxZ, ghost.at.m_123343_());
        }
        int x = (minX + maxX) / 2;
        int z = (minZ + maxZ) / 2;
        BlockPos where = switch (build.face) {
            case NORTH -> new BlockPos(x, minY, minZ - 2);
            case SOUTH -> new BlockPos(x, minY, maxZ + 2);
            case WEST -> new BlockPos(minX - 2, minY, z);
            case EAST -> new BlockPos(maxX + 2, minY, z);
            default -> null;
        };
        return Optional.ofNullable(where).map(SchematicApi::at);
    }

    /** The offset of the trigger block inside the template, straight off the IE multiblock. */
    private static BlockPos triggerOffsetOf(Object multiblock) {
        Class<?> iface = type(MB_IFACE);
        if (iface == null) {
            return null;
        }
        try {
            Object value = iface.getMethod("getTriggerOffset").invoke(multiblock);
            return value instanceof BlockPos pos ? pos : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /** The ghost cells plus where the formation would start from. */
    private static final class Projection {
        final List<Ghost> ghosts;
        final BlockPos origin;
        final BlockPos trigger;

        Projection(List<Ghost> ghosts, BlockPos origin, BlockPos trigger) {
            this.ghosts = ghosts;
            this.origin = origin;
            this.trigger = trigger;
        }
    }

    /** Where she should stand: outside the footprint, two blocks off the nearest face. */
    private static Optional<At> standFor(Build build, NumenPlayer her) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (Ghost ghost : build.ghosts) {
            minX = Math.min(minX, ghost.at.m_123341_());
            maxX = Math.max(maxX, ghost.at.m_123341_());
            minY = Math.min(minY, ghost.at.m_123342_());
            maxY = Math.max(maxY, ghost.at.m_123342_());
            minZ = Math.min(minZ, ghost.at.m_123343_());
            maxZ = Math.max(maxZ, ghost.at.m_123343_());
        }
        BlockPos at = her.m_20183_();
        boolean inside = at.m_123341_() >= minX && at.m_123341_() <= maxX
                && at.m_123343_() >= minZ && at.m_123343_() <= maxZ
                && at.m_123342_() >= minY && at.m_123342_() <= maxY + 1;
        if (!inside) {
            return Optional.empty();
        }
        // Leave through the nearest face, whichever of the four is closest.
        int west = at.m_123341_() - minX;
        int east = maxX - at.m_123341_();
        int north = at.m_123343_() - minZ;
        int south = maxZ - at.m_123343_();
        int least = Math.min(Math.min(west, east), Math.min(north, south));
        if (least == west) {
            return Optional.of(at(new BlockPos(minX - 2, at.m_123342_(), at.m_123343_())));
        }
        if (least == east) {
            return Optional.of(at(new BlockPos(maxX + 2, at.m_123342_(), at.m_123343_())));
        }
        if (least == north) {
            return Optional.of(at(new BlockPos(at.m_123341_(), at.m_123342_(), minZ - 2)));
        }
        return Optional.of(at(new BlockPos(at.m_123341_(), at.m_123342_(), maxZ + 2)));
    }

    // ---------------------------------------------------------------- IE reflection

    private static Object multiblock(String id) {
        Class<?> handler = type(MB_HANDLER);
        if (handler == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "沉浸工程的 MultiblockHandler 找不到",
                    "确认 immersiveengineering 装着", null);
        }
        try {
            Object found = handler.getMethod("getByUniqueName", ResourceLocation.class)
                    .invoke(null, new ResourceLocation(id));
            if (found == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "图纸上的机器「" + id + "」IE 不认识",
                        "这张图纸可能来自另一个整合包；换一张本包认的图纸", null);
            }
            return found;
        } catch (ReflectiveOperationException e) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读 IE 的多方块表失败：" + e,
                    "把这张报错发给我", null);
        }
    }

    private static Vec3i sizeOf(Object multiblock, ServerLevel level) {
        try {
            return (Vec3i) multiblock.getClass().getMethod("getSize", Level.class)
                    .invoke(multiblock, level);
        } catch (ReflectiveOperationException e) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读不出这座机器的尺寸：" + e,
                    "把这张报错发给我", null);
        }
    }

    private static String displayName(Object multiblock, String id) {
        try {
            Object name = multiblock.getClass().getMethod("getDisplayName").invoke(multiblock);
            if (name instanceof net.minecraft.network.chat.Component component) {
                String text = component.getString();
                if (!text.isBlank() && !text.equals(id)) {
                    return text;
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // Name is a nicety; the id is enough to keep going.
        }
        return id;
    }

    // ---------------------------------------------------------------- small stuff

    /** Flatten a reflection failure into one readable line: the wrapper hides the real cause. */
    private static String reasons(Throwable start) {
        StringBuilder sb = new StringBuilder();
        Throwable current = start;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (depth > 0) {
                sb.append(" <- ");
            }
            sb.append(current.getClass().getSimpleName()).append(": ").append(current.getMessage());
            Throwable next = current.getCause();
            if (next == null && current instanceof java.lang.reflect.InvocationTargetException) {
                next = ((java.lang.reflect.InvocationTargetException) current).getTargetException();
            }
            current = next;
        }
        return sb.toString();
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }

    private static ServerLevel levelOf(ServerCall call) {
        if (!(call.her().m_9236_() instanceof ServerLevel level)) {
            throw new ApiError(ErrorKind.NOT_FOUND, "她现在不在服务端世界里",
                    "这个接口只能在服务端世界读；等她在世界里再问", null);
        }
        return level;
    }

    private static String name(BlockState state) {
        ResourceLocation key = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
        String text = state.m_60734_().m_49954_().getString();
        String path = key == null ? "?" : key.toString();
        return text.equals(path) ? path : text + " (" + path + ")";
    }

    private static String rotationName(Rotation rotation) {
        return switch (rotation) {
            case NONE -> "无";
            case CLOCKWISE_90 -> "顺时针90";
            case CLOCKWISE_180 -> "180";
            case COUNTERCLOCKWISE_90 -> "逆时针90";
        };
    }

    private static At at(BlockPos pos) {
        return new At(pos.m_123341_(), pos.m_123342_(), pos.m_123343_());
    }

    private static String coord(At at) {
        return at.x() + "," + at.y() + "," + at.z();
    }

    private static String coord(BlockPos pos) {
        return pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
