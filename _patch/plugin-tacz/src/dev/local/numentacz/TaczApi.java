package dev.local.numentacz;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.permission.Action;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.EntityRef;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.entity.ReloadState;
import com.tacz.guns.api.entity.ShootResult;
import com.tacz.guns.api.item.IAmmo;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.config.common.AmmoConfig;
import com.tacz.guns.resource.CommonAssetsManager;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.entity.shooter.ShooterDataHolder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.AbstractGlassBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StainedGlassPaneBlock;
import net.minecraft.world.level.block.state.properties.NoteBlockInstrument;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code tacz.gun}：开火、装填、拉栓、开镜，都在她手里这把枪上。
 *
 * <p>小D 是没有客户端的 {@link ServerPlayer}，永远发不出 TaCZ 那条「客户端开枪」的包。好在 TaCZ
 * 把 {@link IGunOperator} 留在服务端，这组函数驱动的就是它：先把枪抽出来一次（抽枪会重置它自己的
 * 冷却，所以不能每枪都抽）、给一个相对时间戳（TaCZ 会校验时间戳与 {@code baseTimestamp} 对得上，
 * 而没有客户端的同伴永远收不到那条同步包）、把疾跑这个闸门按下去。
 *
 * <p>一次调用只打一发：一条命令一个意图，想连发就自己循环，节奏写在技能页里。
 */
public final class TaczApi {

    static final String NAMESPACE = "tacz";

    private TaczApi() {
    }

    static void install(NumenApi numen) {
        numen.api("gun", "手里的枪：是什么枪、还剩几发、开枪、装填、拉栓、开镜。", TaczApi.class);
    }

    // ---- 读枪 ----

    /** 她手里这把枪。 */
    @Doc("她手里这把枪的读数。")
    public record GunInfo(
            @Doc("主手是不是拿着一把 TaCZ 的枪。") boolean hasGun,
            @Doc("枪的 id，例如 tacz:ak47；空手时没有。") Optional<String> id,
            @Doc("枪的名字，中文。") String name,
            @Doc("弹匣里还有几发。") int mag,
            @Doc("弹匣满时能装几发；读不出来是 -1。") int magSize,
            @Doc("膛里是不是压着一发；1 有、0 没有。") int barrel,
            @Doc("背包里同型号子弹的总数（够不够续弹看这个）。") int reserve,
            @Doc("射击模式：AUTO 全自动、SEMI 半自动、BURST 三连发、UNKNOWN 读不出。") String fireMode,
            @Doc("弹匣加膛里的一发，连起来写的样子，例如 17+1；没有枪时是 -。") String ammo,
            @Doc("是不是正在装填。") boolean reloading,
            @Doc("是不是正在拉栓。") boolean bolting,
            @Doc("是不是正端着瞄准。") boolean aiming,
            @Doc("射击冷却还剩几刻；0 就是能打。") long shootCoolDown,
            @Doc("抽枪冷却还剩几刻；0 就是枪已经抽出来了。") long drawCoolDown) {
    }

    @Fn("看她手里那把枪：是什么枪、还剩几发、背包里还有多少子弹、能不能打。")
    @Example("tacz.gun.info()")
    @Note("只读 TaCZ 在服务端记的状态，什么都不改。")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.reload", "tacz.gun.bolt"})
    public static GunInfo info(ServerCall call) {
        NumenPlayer her = call.her();
        ItemStack st = her.m_21205_();
        if (st == null || st.m_41619_() || IGun.getIGunOrNull(st) == null) {
            return new GunInfo(false, Optional.empty(), "-", 0, -1, 0, 0, "UNKNOWN", "-",
                    false, false, false, 0L, 0);
        }
        IGun g = IGun.getIGunOrNull(st);
        IGunOperator op = IGunOperator.fromLivingEntity(her);
        ReloadState.StateType rt = reloadType(op);
        ResourceLocation gunRl = g.getGunId(st);
        String gunId = gunRl == null ? "?" : gunRl.toString();
        GunData data = gunData(gunRl);
        int mag = g.getCurrentAmmoCount(st);
        int barrel = g.hasBulletInBarrel(st) ? 1 : 0;
        String mode;
        try {
            mode = g.getFireMode(st) == null ? "UNKNOWN" : g.getFireMode(st).name();
        } catch (Throwable t) {
            mode = "UNKNOWN";
        }
        if ("UNKNOWN".equals(mode) && data != null) {
            try {
                mode = data.getFireModeSet() == null || data.getFireModeSet().isEmpty()
                        ? "UNKNOWN" : data.getFireModeSet().get(0).name();
            } catch (Throwable ignored) {
                mode = "UNKNOWN";
            }
        }
        return new GunInfo(true,
                Optional.of(gunId),
                gunName(st, gunId),
                mag,
                data == null ? -1 : data.getAmmoAmount(),
                barrel,
                reserve(her, gunRl, st),
                mode,
                mag + (barrel > 0 ? "+1" : ""),
                rt != null && rt.isReloading(),
                op != null && (op.getSynIsBolting() || isBolting(op)),
                op != null && op.getSynIsAiming(),
                op == null ? 0L : Math.max(0L, op.getSynShootCoolDown()),
                op == null ? 0L : Math.max(0L, op.getSynDrawCoolDown()));
    }

    // ---- 把枪拿到手上 ----

    /** 抽枪的结果。 */
    @Doc("把枪拿到手上的结果。")
    public record Equipped(
            @Doc("是把枪拿到手上了（true），还是本来就在手上、或者身上根本没有枪（false）。") boolean equipped,
            @Doc("一句话说清楚刚才做了什么。") String line,
            @Doc("她的手：槽号 + 物品名，[枪] 标出哪一格是 TaCZ 的枪。") String hotbar) {
    }

    @Fn("确保她主手拿的是一把枪：不在手上就自己找、自己换到手上。")
    @Example("tacz.gun.equip()")
    @Note("会动她的物品栏（把找到的枪与当前手上的东西对调），但不会丢东西、不会复制。不会问主人——这把枪本来就在她身上。")
    @SeeAlso({"tacz.gun.info", "tacz.gun.shoot"})
    public static Equipped equip(ServerCall call) {
        NumenPlayer her = call.her();
        if (hasGun(her)) {
            return new Equipped(true, "枪就在她手上，不用换。", hotbar(her));
        }
        String line = bringGun(her);
        if (line == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她身上没有枪。",
                    "先给她一把 TaCZ 的枪（tacz:modern_kinetic_gun 之类），再 tacz.gun.equip()。", null);
        }
        return new Equipped(true, line, hotbar(her));
    }

    /**
     * 把身上的枪拿到主手上：快捷栏里有就切过去，只有背包里有就与当前这格对调。
     * 拿到了返回一句中文说明，身上根本没枪返回 null。
     */
    private static String bringGun(NumenPlayer her) {
        try {
            Inventory inv = her.m_150109_();
            if (inv == null) {
                return null;
            }
            int slot = -1;
            for (int i = 0; i < inv.m_6643_(); i++) {
                ItemStack st = inv.m_8020_(i);
                if (st == null || st.m_41619_() || IGun.getIGunOrNull(st) == null) {
                    continue;
                }
                if (i < 9) {
                    slot = i;
                    break;
                }
                if (slot < 0) {
                    slot = i;
                }
            }
            if (slot < 0) {
                return null;
            }
            String line;
            if (slot < 9) {
                inv.f_35977_ = slot;
                line = "把快捷栏第 " + slot + " 格的枪拿到手上了。";
            } else {
                int held = inv.f_35977_;
                ItemStack gun = inv.m_8020_(slot);
                ItemStack swapped = inv.m_8020_(held);
                inv.m_6836_(held, gun);
                inv.m_6836_(slot, swapped);
                inv.f_35977_ = held;
                line = "把背包第 " + slot + " 格的 " + Zh.name(gun)
                        + " 换到了手上（原来手上的东西挪到那一格）。";
            }
            forget(her.m_20148_());
            prepare(her);
            return line;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---- 开火 ----

    /** 打谁。 */
    public record Shoot(
            @Doc("要打的那个东西：numen.scan.entities 给的 id 或 uuid。")
            @Omitted("朝着她正看的方向打，不指定目标")
            Optional<EntityRef> target) {
    }

    /** 这一枪的结果。 */
    @Doc("一枪的结果。")
    public record Shot(
            @Doc("打出去了（true），还是没打成（false）。") boolean fired,
            @Doc("TaCZ 给的结果名：SUCCESS、COOL_DOWN、NO_AMMO、NOT_DRAW、NEED_BOLT、IS_RELOADING、"
                    + "IS_SPRINTING、OVERHEATED 等等；没打出去时看这个判断为什么。") String result,
            @Doc("打完这一枪后弹匣里还剩几发（加膛里那发）；没有枪是 -1。") int ammoLeft,
            @Doc("没打出去的原因，中文，一句话：还在抽枪 / 在装填 / 在拉栓 / 冷却 3 刻；打出去了就没有。")
            Optional<String> busy,
            @Doc("指定了目标时：她枪口是不是真的对着那个东西（视线夹角与遮挡都算过）。没指定目标时没有。")
            Optional<Boolean> onTarget,
            @Doc("目标在这一刻的血量；没指定目标时没有。子弹可能还要飞一刻才落地，所以这个数是打之前/打当时的。")
            Optional<Double> targetHealth,
            @Doc("最近一次真的打中的是什么（中文名）。子弹要飞一两刻才落地，所以这一发不会出现在这里，"
                    + "看到的通常是上一发；4 秒内什么都没打中就空着。") Optional<String> lastHit,
            @Doc("那一下扣了多少血。") Optional<Double> lastHitDamage,
            @Doc("那一下打死它了没有。") Optional<Boolean> lastHitKilled,
            @Doc("那是几秒前的事。") Optional<Double> lastHitSecondsAgo,
            @Doc("一句话说清楚刚才发生了什么。") String line) {
    }

    @Fn("瞄着一个目标打一发，或者朝她正看的方向打一发。")
    @Example("tacz.gun.shoot({target = 47})")
    @Example("tacz.gun.shoot()")
    @Note("一次一发；想连发就自己循环，看 ammo_left。指定了目标（她主动打谁）会先问主人同不同意"
            + "（attack 权限）；朝空气打不拦。她自己的自动防御反射不走这里，不受这条限制。")
    @SeeAlso({"tacz.gun.info", "tacz.gun.reload", "tacz.gun.bolt", "numen.scan.entities"})
    public static Pending<Shot> shoot(ServerCall call, Shoot args) {
        NumenPlayer her = call.her();
        if (!hasGun(her) && bringGun(her) == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她手里没有枪。",
                    "先 tacz.gun.equip() 把枪拿到手上，再打。", null);
        }
        if (args.target().isPresent()) {
            Entity target = call.entity(args.target().get());
            double dist = Math.sqrt(dist2(her, target));
            if (dist > MAX_SHOT_RANGE) {
                throw new ApiError(ErrorKind.OUT_OF_REACH,
                        "目标离她 " + Math.round(dist) + " 格，太远了，子弹够不着。",
                        "先走到 " + (int) MAX_SHOT_RANGE + " 格以内再打。", null);
            }
            String busyNow = busy(her);
            if (busyNow != null) {
                return Pending.of(notReady(her, busyNow));
            }
            return call.authorize(Action.attack(target)).then(ignored -> shootAt(her, target));
        }
        String busyNow = busy(her);
        if (busyNow != null) {
            return Pending.of(notReady(her, busyNow));
        }
        return Pending.of(shootStraight(her));
    }

    private static Shot notReady(NumenPlayer her, String busy) {
        RecentHit hit = recentHit(her);
        return new Shot(false, "NOT_READY", ammoNow(her), Optional.of(busyText(busy)),
                Optional.empty(), Optional.empty(),
                hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                "现在打不了：" + busyText(busy) + "。");
    }

    private static Shot shootStraight(NumenPlayer her) {
        RecentHit hit = recentHit(her);
        ShootResult r = fire(her, her.m_146909_(), her.m_146908_());
        int left = ammoNow(her);
        if (r == ShootResult.SUCCESS) {
            return new Shot(true, r.name(), left, Optional.empty(), Optional.empty(), Optional.empty(),
                    hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                    "朝她正看的方向打了一发" + (left >= 0 ? "，弹匣里还剩 " + left + " 发。" : "。"));
        }
        if (r == ShootResult.NEED_BOLT) {
            boltNow(her);
            return new Shot(false, r.name(), left, Optional.of("枪的栓没拉，已经替她开始拉栓了"),
                    Optional.empty(), Optional.empty(),
                    hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                    "这一发没打出去：枪要拉栓。已经替她拉上了，过一会儿再打一发。");
        }
        return new Shot(false, r.name(), left, Optional.of(resultText(r)),
                Optional.empty(), Optional.empty(),
                hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                "这一发没打出去：" + resultText(r) + "。");
    }

    private static Shot shootAt(NumenPlayer her, Entity target) {
        RecentHit hit = recentHit(her);
        if (!los(her, target)) {
            return new Shot(false, "NO_LOS", ammoNow(her),
                    Optional.of("她与它之间挡着墙"),
                    Optional.of(false), Optional.of(healthOf(target)),
                    hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                    "没打：她与那个东西之间挡着墙或地板，先走到看得见的地方。");
        }
        InputDriver.lookAt(her, new Vec3(target.m_20185_(), eyeY(target), target.m_20189_()));
        double hpBefore = healthOf(target);
        // TaCZ 在真正出弹那一刻才向这两个 supplier 取角度，所以在这里读到的是已经转过之后的值。
        final float[] seen = new float[2];
        ShootResult r = fireCapturing(her, seen);
        double angle = (seen[0] == 0.0F && seen[1] == 0.0F) ? 180.0 : aimError(her, target, seen[0], seen[1]);
        int left = ammoNow(her);
        boolean onTarget = angle <= ON_TARGET_DEGREES;
        if (r == ShootResult.SUCCESS) {
            return new Shot(true, r.name(), left, Optional.empty(),
                    Optional.of(onTarget), Optional.of(hpBefore),
                    hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                    (onTarget ? "对着 " : "朝 ") + label(target)
                            + (onTarget ? " 打了一发" : " 的方向打了一发，枪口偏了 " + round1(angle) + " 度")
                            + (left >= 0 ? "，弹匣里还剩 " + left + " 发。" : "。"));
        }
        if (r == ShootResult.NEED_BOLT) {
            boltNow(her);
            return new Shot(false, r.name(), left, Optional.of("枪的栓没拉，已经替她开始拉栓了"),
                    Optional.of(onTarget), Optional.of(hpBefore),
                    hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                    "这一发没打出去：枪要拉栓。已经替她拉上了，过一会儿再打。");
        }
        return new Shot(false, r.name(), left, Optional.of(resultText(r)),
                Optional.of(onTarget), Optional.of(hpBefore),
                hit.target(), hit.damage(), hit.killed(), hit.seconds(),
                "这一发没打出去：" + resultText(r) + "。");
    }

    /** 目标身上「打哪儿」的高度：活物瞄眼睛，别的东西瞄身体中部。 */
    private static double eyeY(Entity target) {
        return target instanceof LivingEntity ? target.m_20188_() : target.m_20186_() + 0.5;
    }

    // ---- 装填 / 拉栓 / 开镜 / 换模式 ----

    /** 装填的结果。 */
    @Doc("装填的结果。")
    public record Reloaded(
            @Doc("真的开始装填了（true），还是没开始（false）。") boolean started,
            @Doc("现在弹匣加膛里有几发。") int ammo,
            @Doc("没开装的原因，中文；开装了就没有。") Optional<String> why,
            @Doc("一句话说清楚刚才发生了什么。") String line) {
    }

    @Fn("给手里的枪装弹。")
    @Example("tacz.gun.reload()")
    @Note("装填要几秒钟；这期间打不了枪，看 tacz.gun.info() 的 reloading。"
            + "上一次的判读是错的（报没装其实装了），现在读服务端的真状态。")
    @SeeAlso({"tacz.gun.info", "tacz.gun.bolt", "tacz.gun.cancel_reload"})
    public static Reloaded reload(ServerCall call) {
        NumenPlayer her = call.her();
        if (!hasGun(her) && bringGun(her) == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她手里没有枪，没什么可装的。",
                    "先 tacz.gun.equip()。", null);
        }
        IGunOperator op = operator(her);
        ShooterDataHolder dh = op.getDataHolder();
        dh.currentGunItem = () -> her.m_21205_();
        ReloadState.StateType rt = reloadType(op);
        if (rt != null && rt.isReloading()) {
            return new Reloaded(true, ammoNow(her), Optional.empty(), "已经在装了，别催。");
        }
        long cd = op.getSynShootCoolDown();
        if (cd > 0) {
            return new Reloaded(false, ammoNow(her), Optional.of("射击冷却还剩 " + cd + " 刻"),
                    "现在装不了：刚打完，枪还在冷却（" + cd + " 刻）。");
        }
        if (op.getSynDrawCoolDown() != 0) {
            return new Reloaded(false, ammoNow(her), Optional.of("还在抽枪"),
                    "现在装不了：枪还没抽出来。");
        }
        if (op.getSynIsBolting() || dh.isBolting) {
            return new Reloaded(false, ammoNow(her), Optional.of("正在拉栓"),
                    "现在装不了：在拉栓，等它拉完。");
        }
        int before = ammoNow(her);
        op.reload();
        ReloadState.StateType after = reloadType(op);
        if (after != null && after.isReloading()) {
            return new Reloaded(true, before, Optional.empty(), "开始装填了，要几秒钟。");
        }
        return new Reloaded(false, before,
                Optional.of("游戏没让装（弹匣可能已经满了，或者背包里没有对型号的子弹）"),
                "没装成：弹匣已经满了，或者背包里没有它能用的子弹。");
    }

    /** 拉栓的结果。 */
    @Doc("拉栓的结果。")
    public record Bolted(
            @Doc("是不是开始拉栓了。") boolean started,
            @Doc("现在弹匣加膛里有几发。") int ammo,
            @Doc("一句话说清楚刚才发生了什么。") String line) {
    }

    @Fn("给栓动枪拉一次栓（打一发拉一下的那种枪，不拉就打不出下一发）。")
    @Example("tacz.gun.bolt()")
    @Note("拉栓要几刻才完；这期间 tacz.gun.info() 的 bolting 是 true。打不出去时如果结果是 NEED_BOLT，"
            + "tacz.gun.shoot() 已经会替她自动拉一次。")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.info"})
    public static Bolted bolt(ServerCall call) {
        NumenPlayer her = call.her();
        if (!hasGun(her) && bringGun(her) == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她手里没有枪，没什么可拉的。",
                    "先 tacz.gun.equip()。", null);
        }
        IGunOperator op = operator(her);
        ShooterDataHolder dh = op.getDataHolder();
        dh.currentGunItem = () -> her.m_21205_();
        if (op.getSynIsBolting() || dh.isBolting) {
            return new Bolted(true, ammoNow(her), "已经在拉了，等它拉完。");
        }
        op.bolt();
        dh.isBolting = true;
        return new Bolted(true, ammoNow(her), "开始拉栓了，过几刻才能打下一发。");
    }

    /** 开镜。 */
    public record AimOpt(
            @Doc("true 端起来瞄准、false 放下；不写就是端起来。")
            @Omitted("端起来瞄准")
            Optional<Boolean> on) {
    }

    /** 开镜的结果。 */
    @Doc("瞄准的状态。")
    public record Aimed(
            @Doc("现在是不是端着瞄准。") boolean aiming,
            @Doc("一句话说清楚刚才做了什么。") String line) {
    }

    @Fn("端起瞄准镜，或者放下。")
    @Example("tacz.gun.aim()")
    @Example("tacz.gun.aim({on = false})")
    @Note("端着瞄准会锁住疾跑，走路的时候记得放下。")
    @SeeAlso({"tacz.gun.stop", "tacz.gun.info"})
    public static Aimed aim(ServerCall call, AimOpt args) {
        NumenPlayer her = call.her();
        if (!hasGun(her) && bringGun(her) == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她手里没有枪，没什么可端起来的。",
                    "先 tacz.gun.equip()。", null);
        }
        boolean on = args.on().orElse(Boolean.TRUE);
        IGunOperator op = operator(her);
        op.aim(on);
        ShooterDataHolder dh = op.getDataHolder();
        if (dh != null) {
            dh.isAiming = on;
        }
        return new Aimed(on, on ? "端起枪瞄准了。" : "放下枪了。");
    }

    /** 射击模式的结果。 */
    @Doc("换射击模式的结果。")
    public record Selected(
            @Doc("换完之后的射击模式：AUTO / SEMI / BURST / UNKNOWN。") String fireMode,
            @Doc("一句话说清楚刚才做了什么。") String line) {
    }

    @Fn("切换射击模式（全自动 / 半自动 / 三连发，切到下一档）。")
    @Example("tacz.gun.fire_select()")
    @Note("只在她手上这把枪支持的档位里切；切完读一下 tacz.gun.info() 的 fire_mode 确认。")
    @SeeAlso({"tacz.gun.info", "tacz.gun.shoot"})
    public static Selected fire_select(ServerCall call) {
        NumenPlayer her = call.her();
        if (!hasGun(her) && bringGun(her) == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她手里没有枪，没有什么可切的。",
                    "先 tacz.gun.equip()。", null);
        }
        IGunOperator op = operator(her);
        op.fireSelect();
        String mode = "UNKNOWN";
        try {
            IGun g = IGun.getIGunOrNull(her.m_21205_());
            if (g != null && g.getFireMode(her.m_21205_()) != null) {
                mode = g.getFireMode(her.m_21205_()).name();
            }
        } catch (Throwable ignored) {
        }
        return new Selected(mode, "换了一档射击模式，现在是 " + mode + "。");
    }

    /** 取消装填的结果。 */
    @Doc("取消装填的结果。")
    public record Cancelled(
            @Doc("是不是真的取消了（原来在装）。") boolean cancelled,
            @Doc("现在弹匣加膛里有几发。") int ammo,
            @Doc("一句话说清楚刚才做了什么。") String line) {
    }

    @Fn("打断正在进行的装填。")
    @Example("tacz.gun.cancel_reload()")
    @Note("打一半不装了（比如怪物已经贴脸）；不装的时候什么也不做。")
    @SeeAlso({"tacz.gun.reload", "tacz.gun.info"})
    public static Cancelled cancel_reload(ServerCall call) {
        NumenPlayer her = call.her();
        if (!hasGun(her) && bringGun(her) == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, "她手里没有枪。", "先 tacz.gun.equip()。", null);
        }
        IGunOperator op = operator(her);
        ReloadState.StateType rt = reloadType(op);
        boolean was = rt != null && rt.isReloading();
        if (!was) {
            return new Cancelled(false, ammoNow(her), "本来就没在装填，什么都没做。");
        }
        op.cancelReload();
        ShooterDataHolder dh = op.getDataHolder();
        if (dh != null) {
            dh.reloadStateType = ReloadState.StateType.NOT_RELOADING;
        }
        return new Cancelled(true, ammoNow(her), "把装填打断了。");
    }

    /** 放下枪。 */
    @Doc("放下瞄准的结果。")
    public record Stopped(@Doc("放下之前是不是端着瞄准。") boolean wasAiming) {
    }

    @Fn("放下枪、不再端着瞄准。")
    @Example("tacz.gun.stop()")
    @Note("瞄准会锁住疾跑，走的时候先放下。")
    public static Stopped stop(ServerCall call) {
        NumenPlayer her = call.her();
        IGunOperator op = IGunOperator.fromLivingEntity(her);
        boolean was = op != null && op.getSynIsAiming();
        if (op != null) {
            op.aim(false);
            ShooterDataHolder dh = op.getDataHolder();
            if (dh != null) {
                dh.isAiming = false;
            }
        }
        forget(her.m_20148_());
        return new Stopped(was);
    }

    // ---- 自动防御的状态 ----

    /** 自动防御现在在干什么。 */
    @Doc("她自己的自动防御反射现在的状态。")
    public record Defense(
            @Doc("现在有没有在开火（true 就是她正对着某个东西打）。") boolean active,
            @Doc("她在打谁、血量多少；没在打就没有。") Optional<String> target,
            @Doc("她的交战半径，单位格。") double radius,
            @Doc("一条中文说明。") String line) {
    }

    @Fn("看她的自动防御反射现在在不在开火（不看就是不知道）。")
    @Example("tacz.gun.defense()")
    @Note("这是她自己的反射，不需要你下命令；这条函数只读它，不能替她开或关。")
    @SeeAlso({"tacz.gun.info", "tacz.gun.shoot"})
    public static Defense defense(ServerCall call) {
        NumenPlayer her = call.her();
        String state = GunDefenseChain.stateOf(her.m_20148_());
        if (state == null) {
            return new Defense(false, Optional.empty(), GunDefenseChain.ENGAGE_RADIUS,
                    "她现在没在自动开火（手持枪械时，怪物靠近她会自己举枪）。");
        }
        return new Defense(true, Optional.of(state), GunDefenseChain.ENGAGE_RADIUS,
                "她正在自动开火：" + state);
    }

    // ---- 视线 / 地形 ----

    /** 一发子弹打不穿的东西之外，玻璃/玻璃板/音符盒铁栏杆是会被打碎的。 */
    private static boolean bulletsBreakIt(ServerLevel level, BlockPos pos) {
        try {
            if (!AmmoConfig.DESTROY_GLASS.get()) {
                return false;
            }
            Block block = level.m_8055_(pos).m_60734_();
            if (block instanceof AbstractGlassBlock || block instanceof StainedGlassPaneBlock) {
                return true;
            }
            return block instanceof IronBarsBlock
                    && level.m_8055_(pos).m_280603_() == NoteBlockInstrument.HAT;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 子弹能不能真的打到它：从她眼睛到它眼睛的射线，只停在子弹穿不过的东西上。
     * 玻璃、染色玻璃板、音符盒铁栏杆不算挡——TaCZ 自己会把它们打碎，所以那一枪是有用的。
     */
    static boolean los(ServerPlayer her, Entity target) {
        try {
            ServerLevel level = (ServerLevel) her.m_9236_();
            Vec3 from = her.m_146892_();
            Vec3 to = target.m_146892_();
            for (int hop = 0; hop < MAX_HOPS; hop++) {
                BlockHitResult hit = (BlockHitResult) level.m_45547_(new ClipContext(from, to,
                        ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, her));
                if (hit.m_6662_() == HitResult.Type.MISS) {
                    return true;
                }
                if (!bulletsBreakIt(level, hit.m_82425_())) {
                    return false;
                }
                Vec3 at = hit.m_82450_();
                Vec3 dir = to.m_82537_(at);
                if (dir.m_82526_(at) < 1.0E-6) {
                    return true;
                }
                Vec3 unit = dir.m_82541_();
                from = at.m_82520_(unit.m_7096_() * 0.05, unit.m_7098_() * 0.05, unit.m_7094_() * 0.05);
            }
            return false;
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 一发子弹最多穿几层玻璃还算「够得着」。 */
    private static final int MAX_HOPS = 6;

    /** 超过这个距离就别打了（子弹飞不到、也没意义）。 */
    private static final double MAX_SHOT_RANGE = 96.0;

    /** 视线偏几度以内算「枪口对着它」。 */
    private static final double ON_TARGET_DEGREES = 2.0;

    /** 她枪口与目标之间的夹角（度）。全部手算，不碰 Vec3 的 SRG 方法名。 */
    private static double aimError(NumenPlayer her, Entity target, float pitch, float yaw) {
        try {
            double ex = her.m_20185_();
            double ey = her.m_20188_();
            double ez = her.m_20189_();
            double[] dir = look(pitch, yaw);
            return deg(ex, ey, ez, target.m_20185_(), eyeY(target), target.m_20189_(), dir);
        } catch (Throwable ignored) {
            return 180.0;
        }
    }

    /** 用眼睛位置和一发子弹该走的单位向量之间的夹角（度）；用于不依赖 Entity 的重载。 */
    private static double deg(double ex, double ey, double ez,
                             double tx, double ty, double tz, double[] dir) {
        double dx = tx - ex, dy = ty - ey, dz = tz - ez;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len <= 1.0E-6) {
            return 0.0;
        }
        double dot = (dx / len) * dir[0] + (dy / len) * dir[1] + (dz / len) * dir[2];
        return Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
    }

    /** pitch/yaw（度）→ 单位向量（Minecraft 约定：yaw 0 朝 +Z，pitch 正朝下）。 */
    private static double[] look(float pitch, float yaw) {
        double rp = Math.toRadians(pitch > 100 ? pitch - 360 : pitch);
        double ry = Math.toRadians(yaw);
        return new double[]{
                -Math.sin(ry) * Math.cos(rp),
                -Math.sin(rp),
                Math.cos(ry) * Math.cos(rp)
        };
    }

    /** 8 个水平方向里有几个被墙堵住（8 就是「在壁橱里」）。 */
    static int blockedDirections(ServerPlayer her, double reach) {
        int blocked = 0;
        try {
            ServerLevel level = (ServerLevel) her.m_9236_();
            Vec3 eye = her.m_146892_();
            for (int i = 0; i < 8; i++) {
                double a = Math.toRadians(i * 45.0);
                Vec3 to = eye.m_82520_(Math.cos(a) * reach, 0.0, Math.sin(a) * reach);
                if (level.m_45547_(new ClipContext(eye, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, her)).m_6662_() != HitResult.Type.MISS) {
                    blocked++;
                }
            }
        } catch (Throwable ignored) {
        }
        return blocked;
    }

    /** 在屋里 / 憋在角落：四米内大半方向被堵。 */
    static boolean cramped(ServerPlayer her) {
        return blockedDirections(her, 6.0) >= 6;
    }

    // ---- 内部实现（从命令时代的 BotGun 搬过来，那些坑都是踩过的） ----

    private static final Map<UUID, Boolean> ENGAGED = new ConcurrentHashMap<>();

    static boolean hasGun(NumenPlayer her) {
        try {
            ItemStack st = her.m_21205_();
            return st != null && !st.m_41619_() && IGun.getIGunOrNull(st) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static IGunOperator operator(NumenPlayer her) {
        IGunOperator op = IGunOperator.fromLivingEntity(her);
        if (op == null) {
            throw new ApiError(ErrorKind.FAILED, "TaCZ 没给这把枪一个操作器。",
                    "多半是枪械数据没加载完；过一会儿再试。", null);
        }
        ShooterDataHolder dh = op.getDataHolder();
        if (dh == null) {
            throw new ApiError(ErrorKind.FAILED, "这把枪的状态读不出来。",
                    "枪可能是别的模组塞进来的假枪；换一把真的 TaCZ 枪。", null);
        }
        return op;
    }

    /** 服务端的真实装填状态（同步值会慢一拍，别用它判断）。 */
    private static ReloadState.StateType reloadType(IGunOperator op) {
        try {
            ShooterDataHolder dh = op == null ? null : op.getDataHolder();
            return dh == null ? null : dh.reloadStateType;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isBolting(IGunOperator op) {
        try {
            ShooterDataHolder dh = op.getDataHolder();
            return dh != null && dh.isBolting;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 这把枪的枪械数据（弹匣容量、弹药型号、支持的射击模式都在这里）；读不到是 null。 */
    private static GunData gunData(ResourceLocation gunId) {
        if (gunId == null) {
            return null;
        }
        try {
            CommonAssetsManager assets = CommonAssetsManager.getInstance();
            if (assets == null) {
                return null;
            }
            com.tacz.guns.resource.index.CommonGunIndex index = assets.getGunIndex(gunId);
            return index == null ? null : index.getGunData();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 枪的显示名：优先中文表；表里没有（TaCZ 的枪名只存在客户端资源里）就退回 id 后半段。 */
    private static String gunName(ItemStack st, String gunId) {
        String zh = Zh.name(st);
        if (zh != null && !zh.startsWith("item.") && !zh.startsWith("block.") && zh.indexOf('.') < 0) {
            return zh;
        }
        if (gunId != null && gunId.contains(":")) {
            String path = gunId.substring(gunId.indexOf(':') + 1);
            return path.replace('_', ' ').trim();
        }
        return zh == null ? "-" : zh;
    }

    /** 背包里同型号子弹的总数：直接读弹药物品的 NBT（不依赖模组那套索引判断）。 */
    private static int reserve(NumenPlayer her, ResourceLocation gunId, ItemStack gun) {
        try {
            Inventory inv = her.m_150109_();
            if (inv == null) {
                return 0;
            }
            GunData data = gunData(gunId);
            ResourceLocation want = data == null ? null : data.getAmmoId();
            int total = 0;
            for (int i = 0; i < inv.m_6643_(); i++) {
                ItemStack st = inv.m_8020_(i);
                if (st == null || st.m_41619_() || st == gun) {
                    continue;
                }
                String key = idOf(st);
                if (!"tacz:ammo".equals(key)) {
                    continue;
                }
                String ammoId = null;
                try {
                    net.minecraft.nbt.CompoundTag tag = st.m_41783_();
                    if (tag != null && tag.m_128441_("AmmoId")) {
                        ammoId = tag.m_128461_("AmmoId");
                    }
                } catch (Throwable ignored) {
                }
                boolean hit = want == null
                        ? ammoId != null
                        : (ammoId != null && ammoId.equals(want.toString()));
                if (hit) {
                    total += st.m_41613_();
                }
            }
            return total;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 她的手：槽号 + 中文名，[枪] 标出枪那一格。 */
    private static String hotbar(NumenPlayer her) {
        try {
            Inventory inv = her.m_150109_();
            if (inv == null) {
                return "她还没有物品栏";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 9; i++) {
                ItemStack st = inv.m_8020_(i);
                if (st == null || st.m_41619_()) {
                    continue;
                }
                sb.append("第 ").append(i).append(" 格：").append(Zh.name(st));
                if (IGun.getIGunOrNull(st) != null) {
                    sb.append("[枪]");
                }
                sb.append("  ");
            }
            return sb.length() == 0 ? "她手上这一行是空的" : sb.toString().trim();
        } catch (Throwable t) {
            return "读物品栏时出错：" + t.getClass().getSimpleName();
        }
    }

    /**
     * 每场交战只抽一次枪，并把抽枪动画快进：TaCZ 的 draw() 在还没抽完时再调会重置自己的冷却，
     * 所以每个 tick 都抽就永远抽不好。抽枪动画没有客户端能播，这里直接把冷却跳过。
     */
    static void prepare(NumenPlayer her) {
        try {
            IGunOperator op = IGunOperator.fromLivingEntity(her);
            if (op == null) {
                return;
            }
            op.draw(() -> her.m_21205_());
            ShooterDataHolder dh = op.getDataHolder();
            if (dh != null) {
                dh.currentGunItem = () -> her.m_21205_();
                dh.drawTimestamp = System.currentTimeMillis() - 60_000L;
                dh.meleeTimestamp = -1L;
                dh.boltTimestamp = -1L;
                dh.isBolting = false;
                if (dh.baseTimestamp <= 0L) {
                    dh.baseTimestamp = System.currentTimeMillis() - 60_000L;
                }
            }
            ENGAGED.put(her.m_20148_(), Boolean.TRUE);
        } catch (Throwable ignored) {
        }
    }

    /** 现在打不了的原因（内部码），能打就是 null。 */
    static String busy(NumenPlayer her) {
        try {
            IGunOperator op = IGunOperator.fromLivingEntity(her);
            if (op == null) {
                return "NO_OPERATOR";
            }
            ShooterDataHolder dh = op.getDataHolder();
            if (dh == null) {
                return "NO_DATA";
            }
            dh.currentGunItem = () -> her.m_21205_();
            ReloadState.StateType rt = dh.reloadStateType;
            if (rt != null && rt.isReloading()) {
                return "RELOADING";
            }
            if (op.getSynIsBolting() || dh.isBolting) {
                return "BOLTING";
            }
            if (!Boolean.TRUE.equals(ENGAGED.get(her.m_20148_()))) {
                // 头一回碰这把枪：抽一次，把抽枪动画跳过，然后按抽好了算（真打不出去时 TaCZ 会回 NOT_DRAW）。
                prepare(her);
            }
            long drawCd = op.getSynDrawCoolDown();
            if (drawCd != 0 && !Boolean.TRUE.equals(ENGAGED.get(her.m_20148_()))) {
                return "DRAWING(" + drawCd + ")";
            }
            long cd = op.getSynShootCoolDown();
            if (cd > 0) {
                return "COOLDOWN(" + cd + ")";
            }
            return null;
        } catch (Throwable t) {
            return "ERR:" + t.getClass().getSimpleName();
        }
    }

    /** 内部码 → 中文。 */
    private static String busyText(String busy) {
        if (busy == null) {
            return "能打";
        }
        switch (busy) {
            case "RELOADING":
                return "正在装填";
            case "BOLTING":
                return "正在拉栓";
            case "DRAWING":
                return "枪还没抽出来";
            case "NO_OPERATOR":
                return "TaCZ 没给这把枪操作器";
            case "NO_DATA":
                return "这把枪的状态读不出来";
            default:
                if (busy.startsWith("DRAWING(")) {
                    return "枪还在抽（还剩 " + busy.substring(8, busy.length() - 1) + " 刻）";
                }
                if (busy.startsWith("COOLDOWN(")) {
                    String n = busy.substring(9, busy.length() - 1);
                    return "射击冷却还剩 " + n + " 刻";
                }
                if (busy.startsWith("ERR:")) {
                    return "读枪的状态时出错了（" + busy.substring(4) + "）";
                }
                return busy;
        }
    }

    /** ShootResult → 中文。 */
    private static String resultText(ShootResult r) {
        if (r == null) {
            return "TaCZ 没给结果";
        }
        switch (r) {
            case SUCCESS:
                return "打出去了";
            case COOL_DOWN:
                return "枪还在冷却";
            case NO_AMMO:
                return "没子弹了";
            case NOT_DRAW:
                return "枪还没抽出来";
            case NOT_GUN:
                return "手里不是枪";
            case ID_NOT_EXIST:
                return "这把枪的 id 在枪械数据里找不到";
            case NEED_BOLT:
                return "要拉栓";
            case IS_RELOADING:
                return "正在装填";
            case IS_DRAWING:
                return "正在抽枪";
            case IS_BOLTING:
                return "正在拉栓";
            case IS_MELEE:
                return "正在用枪托砸人";
            case IS_SPRINTING:
                return "正在疾跑（跑着打不了）";
            case NETWORK_FAIL:
                return "联机状态对不上";
            case FORGE_EVENT_CANCEL:
                return "被别的模组拦下来了";
            case OVERHEATED:
                return "枪管过热";
            default:
                return r.name();
        }
    }

    /** 开一枪，并把 TaCZ 出弹那一刻真正用的 pitch/yaw 记进 seen（seen[0]=pitch, seen[1]=yaw）。 */
    private static ShootResult fireCapturing(NumenPlayer her, float[] seen) {
        try {
            ItemStack st = her.m_21205_();
            if (st == null || st.m_41619_() || IGun.getIGunOrNull(st) == null) {
                return ShootResult.NOT_GUN;
            }
            IGunOperator op = IGunOperator.fromLivingEntity(her);
            if (op == null) {
                return ShootResult.UNKNOWN_FAIL;
            }
            ShooterDataHolder dh = op.getDataHolder();
            long timestamp = 0L;
            if (dh != null) {
                dh.currentGunItem = () -> her.m_21205_();
                long now = System.currentTimeMillis();
                if (dh.baseTimestamp <= 0L) {
                    dh.baseTimestamp = now - 60_000L;
                }
                timestamp = now - dh.baseTimestamp;
                dh.sprintTimeS = 0.0F;
            }
            return op.shoot(() -> {
                seen[0] = her.m_146909_();
                return seen[0];
            }, () -> {
                seen[1] = her.m_146908_();
                return seen[1];
            }, timestamp);
        } catch (Throwable t) {
            return ShootResult.UNKNOWN_FAIL;
        }
    }

    static ShootResult fire(NumenPlayer her, float pitch, float yaw) {
        try {
            ItemStack st = her.m_21205_();
            if (st == null || st.m_41619_() || IGun.getIGunOrNull(st) == null) {
                return ShootResult.NOT_GUN;
            }
            IGunOperator op = IGunOperator.fromLivingEntity(her);
            if (op == null) {
                return ShootResult.UNKNOWN_FAIL;
            }
            ShooterDataHolder dh = op.getDataHolder();
            long timestamp = 0L;
            if (dh != null) {
                dh.currentGunItem = () -> her.m_21205_();
                long now = System.currentTimeMillis();
                if (dh.baseTimestamp <= 0L) {
                    dh.baseTimestamp = now - 60_000L;
                }
                timestamp = now - dh.baseTimestamp;
                dh.sprintTimeS = 0.0F;
            }
            return op.shoot(() -> pitch, () -> yaw, timestamp);
        } catch (Throwable t) {
            return ShootResult.UNKNOWN_FAIL;
        }
    }

    static int ammoNow(NumenPlayer her) {
        try {
            ItemStack st = her.m_21205_();
            IGun g = IGun.getIGunOrNull(st);
            if (g == null) {
                return -1;
            }
            return g.getCurrentAmmoCount(st) + (g.hasBulletInBarrel(st) ? 1 : 0);
        } catch (Throwable t) {
            return -1;
        }
    }

    static void forget(UUID id) {
        ENGAGED.remove(id);
    }

    /** 拉一次栓（手动枪械打完一发要拉一下）。 */
    static void boltNow(NumenPlayer her) {
        try {
            IGunOperator op = IGunOperator.fromLivingEntity(her);
            if (op != null) {
                op.bolt();
                ShooterDataHolder dh = op.getDataHolder();
                if (dh != null) {
                    dh.isBolting = true;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 直接装填（反射用，没有 ServerCall）。真的开始了返回 true。 */
    static boolean reloadNow(NumenPlayer her) {
        try {
            ItemStack st = her.m_21205_();
            if (st == null || st.m_41619_() || IGun.getIGunOrNull(st) == null) {
                return false;
            }
            IGunOperator op = IGunOperator.fromLivingEntity(her);
            if (op == null) {
                return false;
            }
            ShooterDataHolder dh = op.getDataHolder();
            if (dh == null) {
                return false;
            }
            dh.currentGunItem = () -> her.m_21205_();
            ReloadState.StateType rt = dh.reloadStateType;
            if (rt != null && rt.isReloading()) {
                return true;
            }
            op.reload();
            ReloadState.StateType after = dh.reloadStateType;
            return after != null && after.isReloading();
        } catch (Throwable t) {
            return false;
        }
    }

    /** 目标此刻的血量。 */
    private static double healthOf(Entity target) {
        try {
            if (target instanceof LivingEntity le) {
                return Math.round(le.m_21223_() * 10.0) / 10.0;
            }
            return -1.0;
        } catch (Throwable t) {
            return -1.0;
        }
    }

    /** 目标的中文名（有自定义名就用自定义名）。 */
    static String label(Entity target) {
        try {
            return Zh.clean(target.m_5446_().getString());
        } catch (Throwable t) {
            return "那个东西";
        }
    }

    private static double dist2(Entity a, Entity b) {
        double dx = a.m_20185_() - b.m_20185_();
        double dy = a.m_20186_() - b.m_20186_();
        double dz = a.m_20189_() - b.m_20189_();
        return dx * dx + dy * dy + dz * dz;
    }

    private static String round1(double v) {
        return String.valueOf(Math.round(v * 10.0) / 10.0);
    }

    /** 给别处（技能页）用的枪名。 */
    static String gunName(NumenPlayer her) {
        try {
            ItemStack st = her.m_21205_();
            return st == null || st.m_41619_() ? "-" : Zh.name(st);
        } catch (Throwable t) {
            return "-";
        }
    }

    /** 物品 id 的字符串形式（调试 / 回执用）。 */
    static String idOf(ItemStack st) {
        try {
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(st.m_41720_());
            return id == null ? "?" : id.toString();
        } catch (Throwable t) {
            return "?";
        }
    }

    // ---- 打中了什么 / 蹲下 ----

    /** 命中记录看多久：4 秒。子弹最多飞一两刻，4 秒足够覆盖「上一发」。 */
    private static final int HIT_WINDOW_TICKS = 80;

    /** 打中的一下。 */
    @Doc("打中的一下。")
    public record HitRow(
            @Doc("被打中的是什么，中文名。") String target,
            @Doc("它的注册 id，例如 minecraft:zombie。") String id,
            @Doc("这一下扣了多少血。") double damage,
            @Doc("是不是爆头。") boolean headshot,
            @Doc("这一下打死它了没有。") boolean killed,
            @Doc("几秒前的事。") double seconds_ago,
            @Doc("打的时候她离它多远。") int distance) {
    }

    /** 最近几枪打中了什么。 */
    @Doc("最近几枪打中了什么。")
    public record Hits(
            @Doc("这个时间窗口里一共打中几下。") int total,
            @Doc("每一下，新的在前；最多 n 条。") java.util.List<HitRow> rows,
            @Doc("一句话说清楚。") String line) {
    }

    /** 看多久以内的命中。 */
    public record HitWindow(
            @Doc("最多列几条，1 到 32。")
            @Omitted("8 条")
            Optional<Integer> n,
            @Doc("只看这么多秒以内的，1 到 600。")
            @Omitted("60 秒")
            Optional<Integer> seconds) {
    }

    @Fn("她最近几枪打中了什么：什么怪、扣了多少血、爆头没有、打没打死。")
    @Example("tacz.gun.hits()")
    @Example("tacz.gun.hits({n = 3, seconds = 10})")
    @Note("子弹要飞一两刻才落地，所以 tacz.gun.shoot() 的回执里不会有这一枪的命中；想确认打中没有，"
            + "隔一会儿再问一次这里。记录只在内存里，服务器重启就清空。抬枪打空、被墙挡住、"
            + "TaCZ 取消掉的伤害都不会出现在这里。")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.info", "tacz.gun.stance"})
    public static Hits hits(ServerCall call, HitWindow args) {
        NumenPlayer her = call.her();
        int n = args.n().orElse(8);
        if (n < 1 || n > 32) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "n 只收 1 到 32，给的是 " + n,
                    "写 tacz.gun.hits({n = 5})；不写就是 8。", null);
        }
        int secs = args.seconds().orElse(60);
        if (secs < 1 || secs > 600) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "seconds 只收 1 到 600，给的是 " + secs,
                    "写 tacz.gun.hits({seconds = 30})；不写就是 60。", null);
        }
        long now = her.m_9236_().m_46467_();
        java.util.List<HitLog.Hit> all = HitLog.of(her.m_20148_(), now, secs * 20L);
        java.util.List<HitRow> rows = new java.util.ArrayList<>();
        for (HitLog.Hit h : all) {
            if (rows.size() >= n) {
                break;
            }
            rows.add(row(h, now));
        }
        String line;
        if (all.isEmpty()) {
            line = "最近 " + secs + " 秒里她没打中任何东西（打空了、打到墙，或者根本没开枪）。";
        } else {
            HitRow f = rows.get(0);
            line = "最近 " + secs + " 秒里打中 " + all.size() + " 下，最近的：" + f.target()
                    + "，扣 " + f.damage() + " 血"
                    + (f.headshot() ? "（爆头）" : "")
                    + (f.killed() ? "，打死了" : "")
                    + "，" + f.seconds_ago() + " 秒前，离 " + f.distance() + " 格。";
            if (all.size() > rows.size()) {
                line += "另有 " + (all.size() - rows.size()) + " 下没列（这次只要了 " + n + " 条）。";
            }
        }
        return new Hits(all.size(), rows, line);
    }

    /** 站稳了还是蹲着。 */
    @Doc("她现在的姿势与散布档位。")
    public record Stance(
            @Doc("现在的姿势，中文：站着 / 蹲着 / 趴着 / 别的。") String pose,
            @Doc("TaCZ 的散布档位：AIM 开镜、LIE 趴下、SNEAK 蹲下、MOVE 移动、STAND 站着。") String spread,
            @Doc("这个档位 TaCZ 默认给的散布数（越小越准）。") double spread_value,
            @Doc("一句话说清楚。") String line) {
    }

    /** 要摆成什么姿势。 */
    public record StanceOpt(
            @Doc("crouch 蹲下、lie 趴下、stand 站起来。")
            @Omitted("蹲下")
            Optional<String> mode) {
    }

    @Fn("蹲下、趴下或者站起来：蹲着和趴着打枪更准。")
    @Example("tacz.gun.stance({mode = \"crouch\"})")
    @Example("tacz.gun.stance({mode = \"stand\"})")
    @Note("TaCZ 按她的姿势选散布档（InaccuracyType）：蹲下是 SNEAK 档，默认 3.5；趴下是 LIE 档 2.5；"
            + "站着 5.0、走着 5.75、开镜 0.15。这里只改姿势，不动潜行键、也不改她的移动。"
            + "摆完会把服务器实际留下的姿势读回来；如果它把她摆回站姿，line 会直说。")
    @SeeAlso({"tacz.gun.shoot", "tacz.gun.info", "tacz.gun.hits"})
    public static Stance stance(ServerCall call, StanceOpt args) {
        NumenPlayer her = call.her();
        String want = args.mode().orElse("crouch").trim().toLowerCase(java.util.Locale.ROOT);
        net.minecraft.world.entity.Pose pose;
        switch (want) {
            case "crouch":
            case "sneak":
            case "蹲":
            case "蹲下":
                pose = net.minecraft.world.entity.Pose.CROUCHING;
                break;
            case "lie":
            case "crawl":
            case "趴":
            case "趴下":
                pose = net.minecraft.world.entity.Pose.SWIMMING;
                break;
            case "stand":
            case "站":
            case "站起来":
                pose = net.minecraft.world.entity.Pose.STANDING;
                break;
            default:
                throw new ApiError(ErrorKind.BAD_ARGUMENT,
                        "mode 只认 crouch（蹲下）、lie（趴下）、stand（站起来），给的是 " + want,
                        "写 tacz.gun.stance({mode = \"crouch\"})；不写就是蹲下。", null);
        }
        her.m_20124_(pose);
        net.minecraft.world.entity.Pose now = her.m_20089_();
        String spread = spreadOf(her, now);
        double value = spreadValue(spread);
        String line;
        if (now == pose) {
            line = "已经把姿势摆成" + poseName(now) + "，散布档现在是 " + spread + "（默认 "
                    + value + "，站着是 5.0、走着 5.75、开镜 0.15，越小越准）。";
        } else {
            line = "试了要把姿势摆成" + poseName(pose) + "，但服务器把她留在" + poseName(now)
                    + "（现在散布档 " + spread + "）——这个姿势在这里站不住，先换个地方再试。";
        }
        return new Stance(poseName(now), spread, value, line);
    }

    private static String poseName(net.minecraft.world.entity.Pose pose) {
        if (pose == null) {
            return "未知";
        }
        switch (pose) {
            case STANDING:
                return "站着";
            case CROUCHING:
                return "蹲着";
            case SWIMMING:
                return "趴着";
            case SLEEPING:
                return "躺着";
            case FALL_FLYING:
                return "滑翔";
            default:
                return pose.name();
        }
    }

    /** 照 TaCZ 的 InaccuracyType 口径：开镜 > 趴 > 蹲 > 移动 > 站。 */
    private static String spreadOf(NumenPlayer her, net.minecraft.world.entity.Pose pose) {
        if (aimingNow(her)) {
            return "AIM";
        }
        if (pose == net.minecraft.world.entity.Pose.SWIMMING) {
            return "LIE";
        }
        if (pose == net.minecraft.world.entity.Pose.CROUCHING) {
            return "SNEAK";
        }
        try {
            Vec3 v = her.m_20184_();
            if (v != null && Math.sqrt(v.f_82479_ * v.f_82479_ + v.f_82481_ * v.f_82481_) > 0.05) {
                return "MOVE";
            }
        } catch (Throwable ignored) {
        }
        return "STAND";
    }

    private static boolean aimingNow(NumenPlayer her) {
        try {
            return IGunOperator.fromLivingEntity(her).getSynAimingProgress() >= 1.0F;
        } catch (Throwable t) {
            return false;
        }
    }

    private static double spreadValue(String spread) {
        if ("AIM".equals(spread)) {
            return 0.15;
        }
        if ("LIE".equals(spread)) {
            return 2.5;
        }
        if ("SNEAK".equals(spread)) {
            return 3.5;
        }
        if ("MOVE".equals(spread)) {
            return 5.75;
        }
        return 5.0;
    }

    /** 最近一次命中，给 shoot 的回执用。 */
    private record RecentHit(Optional<String> target, Optional<Double> damage,
            Optional<Boolean> killed, Optional<Double> seconds) {

        static RecentHit none() {
            return new RecentHit(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }

    private static RecentHit recentHit(NumenPlayer her) {
        try {
            long now = her.m_9236_().m_46467_();
            HitLog.Hit h = HitLog.last(her.m_20148_(), now, HIT_WINDOW_TICKS);
            if (h == null) {
                return RecentHit.none();
            }
            HitRow r = row(h, now);
            return new RecentHit(Optional.of(r.target()), Optional.of(r.damage()),
                    Optional.of(r.killed()), Optional.of(r.seconds_ago()));
        } catch (Throwable t) {
            return RecentHit.none();
        }
    }

    private static HitRow row(HitLog.Hit h, long now) {
        String name = h.name == null ? "那个东西" : h.name;
        try {
            if (h.id != null && !h.id.isEmpty()) {
                String key = "entity." + h.id.replace(':', '.');
                String zh = Zh.name(key);
                if (zh != null && !zh.isEmpty() && !zh.equals(key)) {
                    name = zh;
                }
            }
            name = Zh.clean(name);
        } catch (Throwable ignored) {
        }
        double seconds = Math.round((now - h.tick) / 2.0) / 10.0;
        return new HitRow(name, h.id == null ? "" : h.id,
                Math.round(h.damage * 10.0) / 10.0, h.headshot, h.killed, seconds, h.distance);
    }
}
