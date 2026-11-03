package dev.local.numenlostcity;

import com.dwinovo.numen.pathing.api.NavRequest;
import com.dwinovo.numen.pathing.api.NavStatus;
import com.dwinovo.numen.pathing.api.Navigation;
import com.dwinovo.numen.pathing.api.Navigator;
import com.dwinovo.numen.pathing.api.Ports;
import com.dwinovo.numen.pathing.body.Effector;
import com.dwinovo.numen.pathing.body.Snapshots;
import com.dwinovo.numen.pathing.plan.Materials;
import com.dwinovo.numen.pathing.plan.TerrainPolicy;
import com.dwinovo.numen.pathing.plan.Threats;
import com.dwinovo.numen.pathing.search.Goal;
import com.dwinovo.numen.pathing.search.Goals;
import com.dwinovo.numen.pathing.spec.RouteSpec;
import com.dwinovo.numen.pathing.world.BodyStats;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;

/**
 * 一段「走到某个方块跟前」的路。
 *
 * <p>这一层只干一件事：调用引擎自带的站位算法 {@link Goals#use}，得到一个「站在这儿能看见并够得到
 * 那一格」的目标，然后交给 {@link Navigator} 去走。走的时候不许她挖、不许她放方块
 * （{@code dig(false)} + {@code place(false)}），因为搜刮箱子不需要改地形，改了反而会把房子拆坏。
 *
 * <p>用法：{@link #start(BlockPos)} 给目标，之后每个服务端刻调一次 {@link #tick()}，直到
 * {@link State#ARRIVED} 或 {@link State#FAILED}。一段路只能走一次，换箱子就重新 {@code new}。
 */
final class SweepNav {

    /** 一段路的状态。只有 ARRIVED 和 FAILED 是终点。 */
    enum State { RUNNING, ARRIVED, FAILED }

    /** 一个什么都不做的「手脚」。搜索引擎在 {@code dig(false)} 下不会真的调它。 */
    private static final Effector NOOP = new Effector() {
        @Override
        public Strike dig(BlockHitResult hit) {
            return Strike.RECOVERING;
        }

        @Override
        public Use use(BlockHitResult hit) {
            return Use.NOTHING;
        }

        @Override
        public void release() {
            // 什么都不做
        }
    };

    private final NumenPlayer her;
    private Navigation nav;
    private String why = "";

    SweepNav(NumenPlayer her) {
        this.her = her;
    }

    /**
     * 开始走向某一格。
     *
     * @return false = 这一格根本去不了（四周堵死），调用方应该跳过它换下一个
     */
    boolean start(BlockPos pos) {
        BodyStats body = Snapshots.stats(her);
        Goals.Use use = Goals.use(her.m_9236_(), body, pos);
        if (use.sealed()) {
            why = "四周都被挡住了，站不到能打开它的地方";
            return false;
        }
        Goal goal = use;
        RouteSpec spec = RouteSpec.defaults().edit()
                .dig(false)
                .place(false)
                .build();
        Ports ports = new Ports(NOOP, TerrainPolicy.ALLOW_ALL, Materials.NONE, Threats.NONE);
        this.nav = Navigator.of(her, ports).drive(NavRequest.to(goal, spec));
        return true;
    }

    /** 推进一刻。 */
    State tick() {
        if (nav == null) {
            why = "没有走路任务";
            return State.FAILED;
        }
        NavStatus st = nav.tick();
        if (st.state() == NavStatus.State.ARRIVED) {
            return State.ARRIVED;
        }
        if (st.state() == NavStatus.State.RUNNING) {
            return State.RUNNING;
        }
        why = describe(st);
        return State.FAILED;
    }

    /** 放弃这段路（换目标或收工时都要调，不然她会继续走）。 */
    void stop() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
    }

    /** 上一次失败的中文原因。 */
    String why() {
        return why;
    }

    /** 把引擎给的失败分支翻成一句中文。 */
    private static String describe(NavStatus st) {
        String outcome = String.valueOf(st.outcome());
        if (outcome.contains("NoRoute")) {
            return "找不到过去的路";
        }
        if (outcome.contains("Blocked")) {
            return "路被堵死了";
        }
        if (outcome.contains("Unloaded")) {
            return "那边区块还没加载出来";
        }
        if (outcome.contains("NoLineOfSight")) {
            return "站到了但看不见那一格";
        }
        if (outcome.contains("Denied")) {
            return "规则不允许我过去";
        }
        if (outcome.contains("Stranded")) {
            return "走一半卡住了";
        }
        if (outcome.contains("OutOfBudget")) {
            return "路太长，算不过来了";
        }
        return "走不过去（" + outcome + "）";
    }
}
