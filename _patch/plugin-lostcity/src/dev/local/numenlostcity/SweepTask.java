package dev.local.numenlostcity;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.permission.Permission;
import com.dwinovo.numen.permission.Verdict;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.items.ItemHandlerHelper;
import net.minecraftforge.items.wrapper.InvWrapper;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 把一整片失落的城市里的箱子挨个搜刮干净。
 *
 * <p>这是一件「在她自己身体里跑」的活：一次调用就派下去，之后每个服务端刻引擎自己调
 * {@link #tick}，她一格一格搬、一个箱子一个箱子开，全部做完才统一回报。这样才能绕开脚本那边
 * 每次执行最多 200 次调用的闸门——搜 30 个箱子要好几百次调用，写成脚本必然半路被掐断。
 *
 * <p>主循环四个阶段：
 * <ol>
 *   <li>{@code PICK} —— 从排队里取下一个箱子，先「打开」它；空的就丢掉换下一个；</li>
 *   <li>{@code WALK} —— 走到能开它的位置。路上进了刷怪笼的触发范围就先停下来处理笼子；</li>
 *   <li>{@code TAKE} —— 每刻搬两格，搬不下的先塞精妙背包，还塞不下就收工回报；</li>
 *   <li>{@code BREAK} —— 逐刻挖刷怪笼（不是瞬间挖掉，主人看得见进度）。</li>
 * </ol>
 *
 * <p>关于「打开」这一步：失落的城市里箱子的东西只挂在战利品表上（生成时只写
 * {@code setLootTable}），真正把东西算出来是 {@code RandomizableContainerBlockEntity.unpackLootTable}
 * 干的。她那具身体没有客户端、开不出给谁看的窗口，所以这里直接调那个方法——在游戏规则上就等于
 * 「这个箱子被打开过一次」，东西会照常刷出来，锁箱插件也照常参与。
 */
final class SweepTask implements Task {

    /** 刷怪笼的玩家触发半径：进到这个范围它才开始刷怪。 */
    private static final int SPAWNER_TRIGGER = 16;

    /** 每刻最多搬几格。刻意放慢，让主人看得见东西在动。 */
    private static final int SLOTS_PER_TICK = 2;

    /** 单个箱子最多耗多久（刻），超了就放弃换下一个。 */
    private static final int BOX_TIMEOUT = 600;

    /** 同一个刷怪笼最多挖多久（刻），超了就放弃。 */
    private static final int BREAK_TIMEOUT = 200;

    /** 伸手够得着的距离（平方，4 格的平方）。够得着就不寻路了，直接开。 */
    private static final double REACH_SQR = 16.0;

    /** 收工的行账也写一份到服务端日志：主人不在对话里的时候，事后能核对这一趟到底做了什么。 */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger("numen_lostcity");

    private enum Phase { PICK, WALK, TAKE, BREAK, FINISHED }

    private final NumenPlayer her;
    private final SweepRecord rec;
    private final List<LostCityApi.Box> queue;
    private final List<LostCityApi.Spawn> spawners;
    private final ServerLevel level;

    /** {@code spawners} 参数的取值：reach（进范围就挖）/ leave（永远绕开）/ ignore（完全不管）。 */
    private final String spawnerMode;

    private int index = -1;
    private Phase phase = Phase.PICK;
    private SweepNav nav;
    private BlockPos target;
    private Container container;
    private int slot;
    private int waited;

    private BlockPos breaking;
    private int breakTicks;
    private final Set<Long> spawnersDone = new HashSet<>();

    /** plugin-sb 那边的塞背包方法，运行时反射拿；没有就一直是 null。 */
    private static Method stashMethod;
    private static boolean stashLooked;

    SweepTask(NumenPlayer her, SweepRecord rec) {
        this.her = her;
        this.rec = rec;
        this.queue = new ArrayList<>(rec.queue);
        this.spawners = rec.spawners == null ? List.of() : new ArrayList<>(rec.spawners);
        this.level = (ServerLevel) her.m_9236_();
        this.spawnerMode = rec.spawnerMode == null ? "reach" : rec.spawnerMode;
    }

    @Override
    public String name() {
        return "搜刮失落的城市";
    }

    @Override
    public boolean canRun(NumenPlayer bot) {
        return bot != null && bot.m_6084_();
    }

    @Override
    public TaskState tick(NumenPlayer bot) {
        return switch (phase) {
            case PICK -> pick();
            case WALK -> walk();
            case TAKE -> take();
            case BREAK -> digSpawner();
            case FINISHED -> TaskState.SUCCESS;
        };
    }

    /** 取下一个箱子并「打开」它。 */
    private TaskState pick() {
        while (true) {
            index++;
            if (index >= queue.size()) {
                return finish("done", "这一片搜完了");
            }
            LostCityApi.Box box = queue.get(index);
            BlockPos pos = new BlockPos(box.x(), box.y(), box.z());

            BlockEntity be = level.m_7702_(pos);
            if (!(be instanceof Container found)) {
                rec.skipped++;
                rec.problem("(" + where(pos) + ") 不是能打开的容器，跳过");
                continue;
            }

            // 打开：战利品表在这一步才真正算出东西来
            if (be instanceof RandomizableContainerBlockEntity randomizable) {
                randomizable.m_59640_(her);
            }
            rec.opened++;

            if (found.m_7983_()) {
                rec.empty++;
                continue;
            }

            this.target = pos;
            this.container = found;
            this.slot = 0;
            this.waited = 0;

            // 本来就站在伸手够得着的地方（4 格内），不用再寻路。
            // 缘由：失落的城市里柜子多半嵌在操作台里，寻路算出来的「能站人的格子」常常站不住，
            // 明明一伸手就能开，硬要她先走过去反而会白绕一圈、报「走一半卡住了」。
            if (her.m_20182_().m_82557_(Vec3.m_82512_(pos)) <= REACH_SQR) {
                phase = Phase.TAKE;
                return TaskState.RUNNING;
            }

            this.nav = new SweepNav(her);
            if (!nav.start(pos)) {
                rec.skipped++;
                rec.problem("去不了 (" + where(pos) + ")：" + nav.why());
                continue;
            }
            phase = Phase.WALK;
            return TaskState.RUNNING;
        }
    }

    /** 走向当前箱子，路上顺手处理刷怪笼。 */
    private TaskState walk() {
        waited++;
        if (waited > BOX_TIMEOUT) {
            nav.stop();
            rec.skipped++;
            rec.problem("走到一半超时了，放弃 (" + where(target) + ")");
            phase = Phase.PICK;
            return TaskState.RUNNING;
        }

        if (!"ignore".equals(spawnerMode)) {
            BlockPos spawner = spawnerInReach();
            if (spawner != null) {
                nav.stop();
                this.breaking = spawner;
                this.breakTicks = 0;
                phase = Phase.BREAK;
                return TaskState.RUNNING;
            }
        }

        SweepNav.State state = nav.tick();
        if (state == SweepNav.State.ARRIVED) {
            phase = Phase.TAKE;
            return TaskState.RUNNING;
        }
        if (state == SweepNav.State.FAILED) {
            nav.stop();
            rec.skipped++;
            rec.problem("到不了 (" + where(target) + ")：" + nav.why());
            phase = Phase.PICK;
        }
        return TaskState.RUNNING;
    }

    /** 搬东西。每刻只搬两格，搬不下的先塞背包。 */
    private TaskState take() {
        if (container == null) {
            phase = Phase.PICK;
            return TaskState.RUNNING;
        }
        int slots = container.m_6643_();
        int moved = 0;
        while (moved < SLOTS_PER_TICK && slot < slots) {
            if (container.m_8020_(slot).m_41613_() <= 0) {
                slot++;
                continue;
            }
            ItemStack stack = container.m_8016_(slot);
            ItemStack left = give(stack);
            if (left.m_41613_() > 0) {
                container.m_6836_(slot, left);
                return finish("packed", "物品栏和背包都塞满了，剩下 " + remainingBoxes() + " 个箱子没搜");
            }
            rec.took++;
            moved++;
            slot++;
        }

        if (slot >= slots) {
            container.m_6596_();
            container = null;
            ScanCache.invalidate(rec.scope);
            phase = Phase.PICK;
        }
        return TaskState.RUNNING;
    }

    /** 逐刻挖刷怪笼。 */
    private TaskState digSpawner() {
        if (breaking == null) {
            return resumeWalk();
        }
        BlockPos pos = breaking;
        if (!isSpawner(pos)) {
            spawnersDone.add(pos.m_121878_());
            rec.spawnersBroken++;
            breaking = null;
            return resumeWalk();
        }

        BlockState state = level.m_8055_(pos);
        float hardness = state.m_60800_(level, pos);
        if (hardness < 0) {
            rec.problem("这个刷怪笼挖不动");
            spawnersDone.add(pos.m_121878_());
            breaking = null;
            return resumeWalk();
        }
        int need = Math.max(4, (int) (hardness * 30));
        breakTicks++;
        if (breakTicks >= need) {
            level.m_7471_(pos, true);
            spawnersDone.add(pos.m_121878_());
            rec.spawnersBroken++;
            breaking = null;
            return resumeWalk();
        }
        if (breakTicks >= BREAK_TIMEOUT) {
            rec.spawnersLeft++;
            rec.problem("刷怪笼挖了 " + BREAK_TIMEOUT + " 刻还没挖开，放弃了");
            spawnersDone.add(pos.m_121878_());
            breaking = null;
            return resumeWalk();
        }
        return TaskState.RUNNING;
    }

    /** 挖完笼子接着去开箱子。 */
    private TaskState resumeWalk() {
        if (target == null) {
            phase = Phase.PICK;
            return TaskState.RUNNING;
        }
        this.nav = new SweepNav(her);
        if (!nav.start(target)) {
            rec.skipped++;
            rec.problem("去不了 (" + where(target) + ")：" + nav.why());
            phase = Phase.PICK;
            return TaskState.RUNNING;
        }
        phase = Phase.WALK;
        return TaskState.RUNNING;
    }

    /** 最近的一个没处理过、又在触发范围内的刷怪笼；没有就 null。 */
    private BlockPos spawnerInReach() {
        Vec3 me = her.m_20182_();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (LostCityApi.Spawn spawn : spawners) {
            BlockPos pos = new BlockPos(spawn.x(), spawn.y(), spawn.z());
            long key = pos.m_121878_();
            if (spawnersDone.contains(key)) {
                continue;
            }
            double dx = me.f_82479_ - (pos.m_123341_() + 0.5);
            double dy = me.f_82480_ - (pos.m_123342_() + 0.5);
            double dz = me.f_82481_ - (pos.m_123343_() + 0.5);
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > SPAWNER_TRIGGER || dist >= bestDist) {
                continue;
            }
            if ("leave".equals(spawnerMode)) {
                // 绕开也要记账，回报里的「绕开数」才是真数
                spawnersDone.add(key);
                rec.spawnersLeft++;
                continue;
            }
            if (!mayBreak(pos)) {
                spawnersDone.add(key);
                rec.spawnersLeft++;
                rec.problem("主人没让我挖 (" + where(pos) + ") 的刷怪笼，绕开了");
                continue;
            }
            best = pos;
            bestDist = dist;
        }
        return best;
    }

    /** 问规则能不能挖这一格。任务里没有 ServerCall，只能用同步判据。 */
    private boolean mayBreak(BlockPos pos) {
        try {
            BlockState state = level.m_8055_(pos);
            Verdict verdict = Permission.judge(her, Action.breakBlock(pos, state));
            return verdict.allowed();
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean isSpawner(BlockPos pos) {
        return level.m_7702_(pos) instanceof SpawnerBlockEntity;
    }

    private int remainingBoxes() {
        return Math.max(0, queue.size() - index - 1);
    }

    /** 把一叠东西给她：先塞物品栏，塞不下进精妙背包，还塞不下就原样还回去。 */
    private ItemStack give(ItemStack stack) {
        if (stack.m_41613_() <= 0) {
            return ItemStack.f_41583_;
        }
        ItemStack left = ItemHandlerHelper.insertItemStacked(new InvWrapper(her.m_150109_()), stack, false);
        if (left.m_41613_() <= 0) {
            return ItemStack.f_41583_;
        }
        ItemStack still = stash(left);
        if (still.m_41613_() < left.m_41613_()) {
            rec.stashed++;
        }
        return still;
    }

    /** 往她背着的精妙背包里塞。装不了就原样返回。 */
    private ItemStack stash(ItemStack stack) {
        Method method = stashMethod();
        if (method == null) {
            return stack;
        }
        try {
            Object out = method.invoke(null, her, stack);
            return out instanceof ItemStack result ? result : stack;
        } catch (Throwable t) {
            return stack;
        }
    }

    /** 反射拿 plugin-sb 的 {@code SbApi.stash}：两个插件分开编译，只能运行时找。 */
    private static synchronized Method stashMethod() {
        if (!stashLooked) {
            stashLooked = true;
            try {
                Class<?> api = Class.forName("dev.local.numensb.SbApi");
                stashMethod = api.getMethod("stash", net.minecraft.server.level.ServerPlayer.class, ItemStack.class);
            } catch (Throwable t) {
                stashMethod = null;
            }
        }
        return stashMethod;
    }

    private static String where(BlockPos pos) {
        return pos == null ? "那里" : pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_();
    }

    /** 收工。 */
    private TaskState finish(String stopped, String note) {
        rec.stopped = stopped;
        rec.note = note;
        ScanCache.invalidate(rec.scope);
        phase = Phase.FINISHED;
        LOGGER.info("[lostcity] sweep done: stopped={} planned={} opened={} took={} stashed={} empty={}"
                        + " skipped={} spawners={}/{} problems={} note={}",
                rec.stopped, rec.planned, rec.opened, rec.took, rec.stashed, rec.empty,
                rec.skipped, rec.spawnersBroken, rec.spawnersLeft, rec.problems.size(), rec.note);
        return TaskState.SUCCESS;
    }

    /**
     * 引擎按「停一下」叫停时只松身体，不拆任务。
     *
     * <p>她被僵尸追、被叫去睡觉，这类事优先级比搜刮高：引擎把身体拿走交给反射链，搜索路线的计划仍然
     * 留在 {@link #nav} 里，等身体拿回来接着走。所以这条路上不能停寻路、不能改账本、不能清缓存——
     * 一改就等于把已经搜过的箱子重搜一遍、把没搜的漏掉。
     */
    @Override
    public void stop(NumenPlayer bot, Task.StopReason reason) {
        if (reason == Task.StopReason.PREEMPTED) {
            bot.controls().releaseAll();
            return;
        }
        if (nav != null) {
            nav.stop();
        }
        rec.stopped = "stopped";
        rec.note = "被叫停了：" + reason;
        ScanCache.invalidate(rec.scope);
        LOGGER.info("[lostcity] sweep stopped: reason={} opened={} took={} stashed={}",
                reason, rec.opened, rec.took, rec.stashed);
    }

    @Override
    public TaskResult result(TaskState state) {
        String text = rec.note.isEmpty() ? "搜刮结束" : rec.note;
        LostCityApi.Swept swept = new LostCityApi.Swept(rec.stopped, text, rec.scope, rec.planned,
                rec.opened, rec.empty, rec.took, rec.stashed, rec.skipped, rec.spawnersBroken,
                rec.spawnersLeft, List.copyOf(rec.problems));
        return TaskResult.ok(text, swept);
    }
}
