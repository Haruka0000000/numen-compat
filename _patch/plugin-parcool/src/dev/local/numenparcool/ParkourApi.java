package dev.local.numenparcool;

import java.util.Locale;
import java.util.Optional;

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
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.Level;

/**
 * {@code parkour}：她自己会做的跑酷动作。
 *
 * <p>ParCool 的动作是客户端按出来的，服务端只能看不能替她按（详见 {@link ParCoolApi}），所以这一组
 * 不碰 ParCool：蹬墙跳、翻越、滑铲、翻滚、抓边上墙，都是按原版物理自己算——给一刻速度、摆个姿势，
 * 剩下的交给她的身体（碰撞、重力、摔伤都在服务端照常算）。
 *
 * <p>先问 {@code around} 再动手：它回答她面前有没有墙、墙多高、能不能翻、脚边落差多少。动作发起后
 * 立刻回执「已经开始」，几刻后想知道成没成，问 {@code last} 或再问一次 {@code around}。
 *
 * <p>坐标一律按三个 double 传，不碰 {@code Vec3.x/y/z}——SRG 下那三个字段叫
 * {@code f_82479_/f_82480_/f_82481_}，写 {@code v.x} 编译不过。
 */
public final class ParkourApi {

    /** 一次翻越/蹬墙的水平速度。原版疾跑约 0.13，0.4 左右是明显的飞跃。 */
    private static final double LEAP = 0.38;
    /** 手能摸到墙的距离（从身体中心算）。 */
    private static final double REACH = 1.35;

    private ParkourApi() {
    }

    static void install(NumenApi numen) {
        numen.api("parkour",
                "她自己会做的跑酷动作：先问 around 看面前有没有墙、墙多高、能不能翻，再用 wall_jump 蹬墙跳、"
                        + "vault 翻矮墙、slide 滑铲、roll 翻滚闪避、climb 抓住边爬上去；last 回看上一次的结果。",
                ParkourApi.class);
    }

    // ---------------------------------------------------------------- 回执形状

    /** 一个格子。 */
    @Doc("一个格坐标。")
    public record Point(
            @Doc("格坐标 x。") int x,
            @Doc("格坐标 y。") int y,
            @Doc("格坐标 z。") int z) {

        static Point of(double x, double y, double z) {
            return new Point((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        }
    }

    /** 一次跑酷动作的结果。 */
    @Doc("一次跑酷动作的结果。")
    public record Move(
            @Doc("动作：wall_jump / vault / slide / roll / climb。") String action,
            @Doc("做成了没有。刚发起时是 true 但 done=false（表示已经开始）。") boolean ok,
            @Doc("这一趟结束了没有。false 表示还在飞。") boolean done,
            @Doc("起点。") Point from,
            @Doc("落点（还在飞时是此刻的位置）。") Point to,
            @Doc("从起点到落点挪了几格，一位小数。") double distance,
            @Doc("花了几刻（20 刻 = 1 秒）。") int ticks,
            @Doc("一句话，可以直接念给主人。") String line) {

        static Move started(String action, double x, double y, double z, double expect, String line) {
            Point p = Point.of(x, y, z);
            return new Move(action, true, false, p, p, expect, 0, line);
        }

        static Move done(String action, double fx, double fy, double fz,
                double tx, double ty, double tz, int ticks, String note) {
            double dx = tx - fx;
            double dy = ty - fy;
            double dz = tz - fz;
            double d = Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz) * 10) / 10.0;
            String tail = note == null || note.isEmpty() ? "" : "，" + note;
            return new Move(action, true, true, Point.of(fx, fy, fz), Point.of(tx, ty, tz), d, ticks,
                    "做完了（" + ticks + " 刻，挪了 " + d + " 格）" + tail);
        }

        static Move broken(String action, String why) {
            return new Move(action, false, true, new Point(0, 0, 0), new Point(0, 0, 0), 0, 0,
                    "这一趟没算完（" + why + "）——她可能还停在半路上，先用 parkour.parkour.around() 看一眼");
        }

        static Move none() {
            return new Move("", false, true, new Point(0, 0, 0), new Point(0, 0, 0), 0, 0,
                    "还没做过跑酷动作");
        }
    }

    /** 她身边的地形。 */
    @Doc("她身边的地形：面前有没有墙、墙多高、能不能翻或爬上去、脚边落差多少。动手之前先问这个。")
    public record Around(
            @Doc("面前一格内有墙。") boolean wall,
            @Doc("墙在哪边：前面/后面/左边/右边；没有墙是空串。") String side,
            @Doc("墙离身体中心几格，一位小数；没有墙是 -1。") double distance,
            @Doc("这堵墙从她脚下算起有几格高（0 表示没有墙）。") int height,
            @Doc("墙顶那一层能不能站人（翻上去/爬上去的落点）。") boolean top_standable,
            @Doc("能不能直接翻过去（墙 1-2 格高、顶上有落脚的地方）。") boolean vaultable,
            @Doc("能不能抓着边爬上去（墙 1-3 格高、顶上有落脚的地方）。") boolean climbable,
            @Doc("脚下悬空几格（0 = 站在地上）。") int drop,
            @Doc("现在是站着的（不是在飞、不是在水里）。") boolean on_ground,
            @Doc("在水里。") boolean in_water,
            @Doc("正在做一个跑酷动作。") boolean busy,
            @Doc("一句话，可以直接念给主人。") String line) {
    }

    // ---------------------------------------------------------------- 问地形

    @Fn("她身边的地形：面前有没有墙、多高、能不能翻过去或爬上去、脚边离下方地面多少格。动手之前先问这个。")
    @Example("local a = parkour.parkour.around()")
    @Example("if a.wall then print(a.line) end")
    @Note("只认眼前 1.3 格内、有碰撞体积的方块（草、火把、告示牌不算墙）。落差最多数 8 格，再深就报 8。")
    @SeeAlso({"parkour.parkour.wall_jump", "parkour.parkour.vault", "parkour.parkour.climb"})
    public static Around around(ServerCall call) {
        NumenPlayer her = her(call);
        Level level = her.m_9236_();
        int feet = (int) Math.floor(her.m_20186_());
        Side side = nearestWall(level, her, feet);
        int drop = dropBelow(level, her, feet);

        boolean wall = side != null;
        int height = wall ? side.height : 0;
        boolean top = wall && standable(level, side.x, feet + height, side.z);
        boolean vaultable = wall && height <= 2 && top;
        boolean climbable = wall && height <= 3 && top;

        String line;
        if (!wall) {
            line = "她身边一格内没有墙——蹬墙跳和翻越都用不上；" + (drop > 0
                    ? "脚下悬空 " + drop + " 格，注意摔伤（3 格以上会疼）"
                    : "脚下是实地");
        } else {
            StringBuilder b = new StringBuilder();
            b.append("她").append(side.zh).append("有堵 ").append(height).append(" 格高的墙，离 ")
                    .append(fmt(side.distance)).append(" 格");
            if (vaultable) {
                b.append("，能翻过去");
            } else if (climbable) {
                b.append("，比翻越高一点，能抓着边爬上去");
            } else if (top) {
                b.append("，太高了，翻不过也爬不上（她最多 3 格）");
            } else {
                b.append("，墙顶上没地方站人，翻上去也待不住");
            }
            if (drop > 0) {
                b.append("；脚下悬空 ").append(drop).append(" 格");
            }
            line = b.toString();
        }
        return new Around(wall, side == null ? "" : side.zh, side == null ? -1 : fmt(side.distance),
                height, top, vaultable, climbable, drop, her.m_20096_(), her.m_20069_(),
                Motion.busy(her), line);
    }

    // ---------------------------------------------------------------- 五个动作

    @Fn("蹬墙跳：面前一堵墙，朝反方向蹬出去并抬高一点。")
    @Example("parkour.parkour.wall_jump()")
    @Note("墙要在面前 1.3 格内、至少 1 格高。没墙会 failed 并说清哪里不对——先 around。")
    @SeeAlso({"parkour.parkour.around", "parkour.parkour.last"})
    public static Move wall_jump(ServerCall call) {
        NumenPlayer her = her(call);
        Level level = her.m_9236_();
        double fx = her.m_20185_();
        double fy = her.m_20186_();
        double fz = her.m_20189_();
        int feet = (int) Math.floor(fy);
        Side side = nearestWall(level, her, feet);
        if (side == null) {
            throw new ApiError(ErrorKind.FAILED, "她身边 1.3 格内没有墙，蹬不了",
                    "先用 parkour.parkour.around() 看她面前有没有墙；没有就走过去（numen.move.to）");
        }
        if (side.height < 1) {
            throw new ApiError(ErrorKind.FAILED, "那面墙不到 1 格高，蹬上去反而会掉下来",
                    "矮墙直接 parkour.parkour.vault() 翻过去");
        }
        // 朝离开墙的方向蹬：横向 0.45，向上 0.5（比普通跳高约一倍）
        double awayX = -side.nx;
        double awayZ = -side.nz;
        // 让她面朝蹬出去的方向，落地后接着往前走不会先转身
        double yaw = Math.toDegrees(Math.atan2(awayZ, awayX)) - 90.0;
        her.m_146922_((float) yaw);
        Motion.set(her, awayX * 0.45, 0.50, awayZ * 0.45);
        her.f_19791_ = 0.0f;   // 蹬墙不带摔落距离

        Motion.begin(her, "wall_jump", 12, (h, age, job) -> {
            if (age <= 2) {
                // 头两刻再补一点向外的速度，别被原版摩擦一下吃掉
                Motion.push(h, awayX * 0.06, 0.0, awayZ * 0.06);
                job.note = "从墙上蹬开了";
            }
            // 飞出去以后等她落地再收账：第二刻就收的话 last 只剩一格，看不出落到哪
            return !(age >= 4 && h.m_20096_());
        });
        return Move.started("wall_jump", fx, fy, fz, 3.0,
                "朝" + opposite(side.zh) + "蹬出去了（向上 0.5，横向 0.45）——几刻后问 parkour.parkour.last() 看落到哪");
    }

    @Fn("翻越：面前 1-2 格高的墙/栅栏，跳过去落在另一边。")
    @Example("parkour.parkour.vault()")
    @Note("墙顶要能站、另一边要空。1 格和 2 格都行；3 格以上用 climb。")
    @SeeAlso({"parkour.parkour.around", "parkour.parkour.climb"})
    public static Move vault(ServerCall call) {
        NumenPlayer her = her(call);
        Level level = her.m_9236_();
        double fx = her.m_20185_();
        double fy = her.m_20186_();
        double fz = her.m_20189_();
        int feet = (int) Math.floor(fy);
        Side side = nearestWall(level, her, feet);
        if (side == null) {
            throw new ApiError(ErrorKind.FAILED, "她面前没有墙，没东西可翻",
                    "先 parkour.parkour.around()；没有墙就走过去（numen.move.to）");
        }
        if (side.height > 2) {
            throw new ApiError(ErrorKind.FAILED, "这堵墙 " + side.height + " 格高，翻不过去",
                    "3 格以内用 parkour.parkour.climb()，再高就绕路或垫方块（numen.build.place）");
        }
        if (!standable(level, side.x, feet + side.height, side.z)) {
            throw new ApiError(ErrorKind.FAILED, "墙顶上站不了人，翻上去会掉回来",
                    "换一边翻，或者用 numen.move.to 绕过去");
        }
        double up = Math.min(0.62, Math.sqrt(2 * Motion.GRAVITY * Math.max(0.6, side.height + 0.35)));
        Motion.set(her, side.nx * LEAP, up, side.nz * LEAP);
        her.f_19791_ = 0.0f;
        Motion.begin(her, "vault", 24, (h, age, job) -> {
            if (h.m_20096_() && age > 3) {
                // 用「沿墙方向挪了几格」判断翻过去没有——墙那一边的地面和这边一样高，
                // 只看 y 会把成功的翻越误报成「落回原地」
                double along = (h.m_20185_() - fx) * side.nx + (h.m_20189_() - fz) * side.nz;
                job.note = along > side.distance + 0.4 ? "落在墙那一边了" : "没翻过去，落回了原处";
                return false;
            }
            // 每刻把水平速度补满：贴着墙那一瞬原版会把撞墙方向的速度清零，
            // 只推前两刻的话升到墙头时已经没有向前的速度，越不过去。
            Motion.set(h, side.nx * LEAP, Motion.vy(h), side.nz * LEAP);
            return true;
        });
        return Move.started("vault", fx, fy, fz, side.distance + 1.5,
                "朝前跳出去翻这堵 " + side.height + " 格高的墙——几刻后问 parkour.parkour.last() 看落在哪");
    }

    @Fn("滑铲：贴着地往前滑一段，蹲低身子（能钻过 1.5 格高的缝）。")
    @Example("parkour.parkour.slide()")
    @Note("要有速度才滑得动：先 numen.move.go 走起来，或者从坡上下去。滑的时候自动蹲下，结束站起来。")
    @SeeAlso({"parkour.parkour.roll", "parkour.parkour.around"})
    public static Move slide(ServerCall call) {
        NumenPlayer her = her(call);
        double fx = her.m_20185_();
        double fy = her.m_20186_();
        double fz = her.m_20189_();
        if (!her.m_20096_()) {
            throw new ApiError(ErrorKind.FAILED, "她现在不在地上，滑不了",
                    "落地以后再 parkour.parkour.slide()；空中想要位移用 parkour.parkour.roll()");
        }
        double[] fwd = facing(her);
        Motion.pose(her, Pose.CROUCHING);
        Motion.set(her, fwd[0] * 0.58, 0.0, fwd[1] * 0.58);
        Motion.begin(her, "slide", 14, (h, age, job) -> {
            Motion.pose(h, Pose.CROUCHING);
            // 摆姿势那一拍 refreshDimensions 会让 onGround 短暂变 false，头三刻不算「飞起来」
            if (age >= 12 || (age > 3 && !h.m_20096_())) {
                job.note = age >= 12 ? "滑到头了" : "滑到半空就停了";
                return false;
            }
            // 每刻补一点，抵掉地面摩擦
            double keep = 0.58 * Math.pow(0.82, age);
            Motion.set(h, fwd[0] * keep, Motion.vy(h), fwd[1] * keep);
            return true;
        });
        return Move.started("slide", fx, fy, fz, 3.0, "蹲下往前滑出去了——滑完站起来，路上 1.5 格高的缝能钻过去");
    }

    /** 翻滚的方向。 */
    @Doc("往哪边滚。")
    public record RollOpt(
            @Doc("滚的方向：不写是朝她面朝的方向滚；`back` 往后滚；`left` / `right` 左右滚。")
            @Omitted("forward") Optional<String> toward,
            @Doc("翻滚时给几刻无敌，0-20，默认 10（半秒）。")
            @Omitted("10") Optional<Integer> iframe) {
    }

    @Fn("翻滚闪避：朝一个方向快速滚出去，滚的时候有半秒无敌，能躲开一次爆炸或一刀。")
    @Example("parkour.parkour.roll()")
    @Example("parkour.parkour.roll({toward = 'back', iframe = 14})")
    @Note("滚的时候蹲得很低还带无敌帧，但滚完会站原地——它是躲，不是跑。")
    @SeeAlso({"parkour.parkour.slide", "threat.threat.near"})
    public static Move roll(ServerCall call, RollOpt opt) {
        NumenPlayer her = her(call);
        double fx = her.m_20185_();
        double fy = her.m_20186_();
        double fz = her.m_20189_();
        String toward = opt.toward().orElse("forward").trim().toLowerCase(Locale.ROOT);
        double[] f = facing(her);
        double dx;
        double dz;
        String zh;
        switch (toward) {
            case "forward", "front", "" -> {
                dx = f[0];
                dz = f[1];
                zh = "前";
            }
            case "back", "backward" -> {
                dx = -f[0];
                dz = -f[1];
                zh = "后";
            }
            case "left" -> {
                dx = f[1];
                dz = -f[0];
                zh = "左";
            }
            case "right" -> {
                dx = -f[1];
                dz = f[0];
                zh = "右";
            }
            default -> throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "toward 只收 forward / back / left / right，给的是 '" + toward + "'",
                    "写 parkour.parkour.roll({toward = 'back'})");
        }
        int iframe = opt.iframe().orElse(10);
        if (iframe < 0 || iframe > 20) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "iframe 只收 0 到 20 刻，给的是 " + iframe,
                    "写 parkour.parkour.roll({iframe = 10})；不写就是 10");
        }
        Motion.set(her, dx * 0.52, her.m_20096_() ? 0.18 : 0.02, dz * 0.52);
        her.f_20920_ = Math.max(her.f_20920_, iframe);
        Motion.begin(her, "roll", 14, (h, age, job) -> {
            if (age == 1) {
                Motion.pose(h, Pose.SWIMMING);
                return true;
            }
            if (age >= 8 || (h.m_20096_() && age >= 4)) {
                job.note = "滚完了";
                return false;
            }
            return true;
        });
        return Move.started("roll", fx, fy, fz, 2.5,
                "朝" + zh + "滚出去了，滚的这 " + iframe + " 刻里不吃伤害");
    }

    @Fn("抓边上墙：面前 1-3 格高的墙，跳上去抓边、把自己拉上墙顶。")
    @Example("parkour.parkour.climb()")
    @Note("只对 1-3 格高的墙有用，而且墙顶上要能站人；更高的墙要垫方块（numen.build.place）。")
    @SeeAlso({"parkour.parkour.vault", "parkour.parkour.around"})
    public static Move climb(ServerCall call) {
        NumenPlayer her = her(call);
        Level level = her.m_9236_();
        double fx = her.m_20185_();
        double fy = her.m_20186_();
        double fz = her.m_20189_();
        int feet = (int) Math.floor(fy);
        Side side = nearestWall(level, her, feet);
        if (side == null) {
            throw new ApiError(ErrorKind.FAILED, "她面前没有墙，没东西可爬",
                    "先 parkour.parkour.around() 看周围");
        }
        if (side.height > 3) {
            throw new ApiError(ErrorKind.FAILED, "这堵墙 " + side.height + " 格高，抓不到边",
                    "3 格以内才行；更高的墙先垫方块（numen.build.place）再上");
        }
        if (!standable(level, side.x, feet + side.height, side.z)) {
            throw new ApiError(ErrorKind.FAILED, "墙顶上站不了人，爬上去也待不住",
                    "换一边爬，或者用 numen.move.to 绕过去");
        }
        double topY = feet + side.height;
        double up = Math.min(0.66, Math.sqrt(2 * Motion.GRAVITY * Math.max(0.8, side.height + 0.25)));
        Motion.set(her, side.nx * 0.12, up, side.nz * 0.12);
        her.f_19791_ = 0.0f;
        Motion.begin(her, "climb", 60, (h, age, job) -> {
            double y = h.m_20186_();
            if (y >= topY) {
                // 手已经过墙头：轻轻把身体送上去，等脚尖踩实才算爬上来。
                // 竖直方向不能压成 0 以上——压住的话她会浮在墙头顶上不落地，一路飘过去。
                if (h.m_20096_() && y >= topY - 0.1) {
                    job.note = "爬上墙顶了";
                    return false;
                }
                Motion.set(h, side.nx * 0.16, Motion.vy(h), side.nz * 0.16);
                return true;
            }
            if (h.m_20096_() && age > 6) {
                job.note = "没抓到边，掉回原处了";
                return false;
            }
            // 贴墙往上爬：每刻固定抬 0.18 格（重力吃掉 0.08，净约 0.1 格/刻，3 格高的墙 30 刻到顶）
            Motion.set(h, side.nx * 0.10, 0.18, side.nz * 0.10);
            return true;
        });
        return Move.started("climb", fx, fy, fz, side.height + 1.0,
                "跳起来抓这堵 " + side.height + " 格高墙的边——几刻后问 parkour.parkour.last() 看她上没上去");
    }

    // ---------------------------------------------------------------- 回看

    @Fn("上一次跑酷动作的结果：成没成、从哪到哪、挪了几格、几刻。")
    @Example("local r = parkour.parkour.last()")
    @Example("if r.done then print(r.line) end")
    @Note("每个动作刚发起时 done=false（还在飞）；过一刻以上再问就能看到落点。")
    @SeeAlso({"parkour.parkour.around"})
    public static Move last(ServerCall call) {
        NumenPlayer her = her(call);
        Move last = Motion.last(her);
        return last == null ? Move.none() : last;
    }

    // ---------------------------------------------------------------- 地形计算

    /** 一面墙：在哪、离多远、几格高、从她指向墙的方向（nx, nz）。 */
    private static final class Side {
        final String zh;
        final double nx;
        final double nz;
        final double distance;
        final int height;
        final int x;
        final int z;

        Side(String zh, double nx, double nz, double distance, int height, int x, int z) {
            this.zh = zh;
            this.nx = nx;
            this.nz = nz;
            this.distance = distance;
            this.height = height;
            this.x = x;
            this.z = z;
        }
    }

    /** 她面朝的方向（水平单位向量，返回 {dx, dz}）。 */
    private static double[] facing(NumenPlayer her) {
        double rad = Math.toRadians(her.m_146908_());
        return new double[] {-Math.sin(rad), Math.cos(rad)};
    }

    /**
     * 找出离她最近的一面墙：四个正方向里挑最近的那个方向，再数那一列有几格高。
     * 只认有碰撞体积的方块——草、火把、告示牌不算墙。
     */
    private static Side nearestWall(Level level, NumenPlayer her, int feet) {
        double ax = her.m_20185_();
        double az = her.m_20189_();
        double[] f = facing(her);
        String[] names = {"前面", "后面", "左边", "右边"};
        // 四个方向要往里走的方向：朝前、朝后、朝左、朝右（左 = 面朝方向的左手边）
        double[][] dirs = {
            {f[0], f[1]},
            {-f[0], -f[1]},
            {f[1], -f[0]},
            {-f[1], f[0]},
        };
        Side best = null;
        for (int i = 0; i < 4; i++) {
            double dx = dirs[i][0];
            double dz = dirs[i][1];
            double bestDist = -1;
            int bx = 0;
            int bz = 0;
            for (double d = 0.45; d <= REACH; d += 0.15) {
                int cx = (int) Math.floor(ax + dx * d);
                int cz = (int) Math.floor(az + dz * d);
                if (blocked(level, cx, feet, cz) || blocked(level, cx, feet + 1, cz)) {
                    bestDist = d;
                    bx = cx;
                    bz = cz;
                    break;
                }
            }
            if (bestDist < 0) {
                continue;
            }
            int height = 0;
            for (int h = 0; h <= 6; h++) {
                if (blocked(level, bx, feet + h, bz)) {
                    height = h + 1;
                } else {
                    break;
                }
            }
            if (height == 0) {
                continue;
            }
            if (best == null || bestDist < best.distance) {
                // 存「从她身上指向墙」的单位向量：翻墙/爬墙朝它走，蹬墙跳反着来
                best = new Side(names[i], dx, dz, bestDist, height, bx, bz);
            }
        }
        return best;
    }

    /** 这一格能不能站人：本格与上面一格都空，脚下那格实心。 */
    private static boolean standable(Level level, int x, int y, int z) {
        return !blocked(level, x, y, z)
                && !blocked(level, x, y + 1, z)
                && blocked(level, x, y - 1, z);
    }

    /** 脚下悬空几格（最多数 8）。 */
    private static int dropBelow(Level level, NumenPlayer her, int feet) {
        int cx = (int) Math.floor(her.m_20185_());
        int cz = (int) Math.floor(her.m_20189_());
        if (blocked(level, cx, feet - 1, cz)) {
            return 0;
        }
        for (int d = 1; d <= 8; d++) {
            if (blocked(level, cx, feet - d, cz)) {
                return d - 1;
            }
        }
        return 8;
    }

    /** 有没有碰撞体积（光、草、火把、告示牌都算没有）。没加载的区块当实心，免得把她往虚空里送。 */
    private static boolean blocked(Level level, int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.m_46749_(pos)) {
            return true;
        }
        return !level.m_8055_(pos).m_60812_(level, pos).m_83281_();
    }

    private static String opposite(String side) {
        return switch (side) {
            case "前面" -> "后";
            case "后面" -> "前";
            case "左边" -> "右";
            case "右边" -> "左";
            default -> "外";
        };
    }

    private static double fmt(double d) {
        return Math.round(d * 10) / 10.0;
    }

    private static NumenPlayer her(ServerCall call) {
        ServerPlayer player = call.her();
        if (!(player instanceof NumenPlayer numen)) {
            throw new ApiError(ErrorKind.FAILED, "这具身体不是同伴，做不了跑酷",
                    "跑酷动作只能由同伴自己调");
        }
        return numen;
    }
}
