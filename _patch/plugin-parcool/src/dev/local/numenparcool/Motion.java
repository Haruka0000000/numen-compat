package dev.local.numenparcool;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;

/**
 * 她的跑酷动作：每刻给一次速度，交给寻路模块的物理步进往前走。
 *
 * <p>为什么这样做：她的身体没有客户端，原版把「按键 → 输入 → travel」那一段拆给了客户端，
 * 引擎用 {@code Physics.step} 在服务端补上同一趟（{@code NumenPlayer.tick} 里 {@code super.tick()}
 * 之后调）。所以只要在 {@code PlayerTickEvent} 的 END 阶段改 {@code deltaMovement}，下一刻的
 * {@code travel} 就会带着这个速度走——和真玩家被炸飞、被击退是同一条路，落点由原版碰撞算，
 * 不会穿墙、不会卡进方块。
 *
 * <p>每个动作都是一个有寿命的 {@link Job}：动作开始注册，每刻被调用一次，返回 false 或到寿命就结束，
 * 结束时把起点、落点、几刻、结果那句话记进 {@link #last}，脚本可以回头问「刚才那次跳成了没有」。
 */
final class Motion {

    /** 原版重力，每刻 0.08。算起跳速度要用它。 */
    static final double GRAVITY = 0.08;

    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, ParkourApi.Move> LAST = new HashMap<>();
    private static boolean registered;

    /** 每一刻要做的事：返回 false 表示这一趟做完了。 */
    interface Step {
        boolean tick(NumenPlayer her, int age, Job job);
    }

    /** 一次正在进行的动作。 */
    static final class Job {
        final NumenPlayer her;
        final String action;
        final int total;
        final Step step;
        final double fx;
        final double fy;
        final double fz;
        int age;
        String note = "";

        Job(NumenPlayer her, String action, int total, Step step) {
            this.her = her;
            this.action = action;
            this.total = total;
            this.step = step;
            this.fx = her.m_20185_();
            this.fy = her.m_20186_();
            this.fz = her.m_20189_();
        }
    }

    private Motion() {
    }

    /** 挂监听，只挂一次。 */
    static void register() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.addListener(Motion::onPlayerTick);
    }

    static boolean busy(NumenPlayer her) {
        return JOBS.containsKey(her.m_20148_());
    }

    static void cancel(NumenPlayer her) {
        Job job = JOBS.remove(her.m_20148_());
        if (job != null) {
            upright(her);
        }
    }

    /** 起一个动作。同一个人身上只会有一个，起新的就把旧的停掉。 */
    static void begin(NumenPlayer her, String action, int total, Step step) {
        register();
        cancel(her);
        JOBS.put(her.m_20148_(), new Job(her, action, total, step));
    }

    static ParkourApi.Move last(NumenPlayer her) {
        return LAST.get(her.m_20148_());
    }

    // ------------------------------------------------------------ 速度读写
    //
    // 注意：这里不能写 v.x / v.y / v.z —— 一进 SRG 反混淆，Vec3 的三个字段被改名成
    // f_82479_ / f_82480_ / f_82481_，连方法（add / scale / reverse）也是。所以自己只在
    // 构造函数那里碰 Vec3，其余全部按三个 double 走。

    /** 直接定速度（会覆盖重力给的那一份，下一刻 travel 再往下算）。 */
    static void set(NumenPlayer her, double x, double y, double z) {
        her.m_20256_(new Vec3(x, y, z));
    }

    /** 加一点速度，不抹掉已有的。 */
    static void push(NumenPlayer her, double x, double y, double z) {
        Vec3 v = her.m_20184_();
        her.m_20256_(new Vec3(v.f_82479_ + x, v.f_82480_ + y, v.f_82481_ + z));
    }

    static double vx(Entity e) {
        return e.m_20184_().f_82479_;
    }

    static double vy(Entity e) {
        return e.m_20184_().f_82480_;
    }

    static double vz(Entity e) {
        return e.m_20184_().f_82481_;
    }

    /** 摆姿势并让碰撞箱跟着变（蹲下 1.5 格、游泳 0.6 格，原版靠这一步改尺寸）。 */
    static void pose(NumenPlayer her, Pose pose) {
        if (her.m_20089_() != pose) {
            her.m_20124_(pose);
            her.m_6210_();
        }
    }

    static void upright(NumenPlayer her) {
        pose(her, Pose.STANDING);
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!(event.player instanceof NumenPlayer her)) {
            return;
        }
        UUID id = her.m_20148_();
        Job job = JOBS.get(id);
        if (job == null) {
            return;
        }
        try {
            job.age++;
            boolean keep = job.step.tick(her, job.age, job);
            if (!keep || job.age >= job.total) {
                JOBS.remove(id);
                upright(her);
                LAST.put(id, ParkourApi.Move.done(job.action, job.fx, job.fy, job.fz,
                        her.m_20185_(), her.m_20186_(), her.m_20189_(), job.age, job.note));
            }
        } catch (Throwable t) {
            // 一次动作算错不该把她的身体卡在半路
            JOBS.remove(id);
            upright(her);
            LAST.put(id, ParkourApi.Move.broken(job.action, t.getClass().getSimpleName()));
        }
    }
}
