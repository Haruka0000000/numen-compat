package dev.local.numentacz;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.reflex.Reflex;

import com.tacz.guns.api.entity.ShootResult;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gun self-defense: when she is holding a TaCZ gun and a hostile gets close, take the body and
 * shoot it — the gun analogue of numen's built-in melee {@code mob_defense} chain.
 *
 * <p>It is registered a notch <em>above</em> melee self-defense (priority 28 vs 30), so when a gun
 * is in hand the gun wins and she shoots instead of swinging a sword; numen's own flee chain (25)
 * still outranks this, so she will run away when she has to. When no gun is held this chain never
 * runs and melee defense takes over as usual.
 *
 * <p>Firing details (draw-once, relative shot timestamp, reload gating) live in {@link TaczApi} and
 * are shared with the model's own {@code tacz.gun.shoot}, so the two paths cannot drift apart.
 */
public final class GunDefenseChain implements Task, Reflex {

    /** Reflex roster id; {@code numen.pauseReflex} can hold this reflex. */
    public static final String ID = "gun_defense";

    /** 怪物离她多近，这把枪才会自己抬起来。 */
    static final double ENGAGE_RADIUS = 14.0;

    /** Grace after the last danger leaves, so a monster straddling the edge does not re-trigger. */
    private static final long CALM_GRACE_MILLIS = 600L;

    /** Never-engaged sentinel. */
    private static final long NEVER = Long.MIN_VALUE;

    /** Inside a cramped room she closes in with the blade unless gunfire is clearly the safer option. */
    private static final double CQB_YIELD = 3.5;

    /** Min time between reload attempts per companion, in millis. */
    private static final long RELOAD_COOLDOWN_MILLIS = 1500L;

    /**
     * Take the body only for something with a line to it. A hostile behind a wall is not a fight: the
     * body has to stay with whatever she was doing, or the walk task never ticks and the path driver
     * re-plans the same two blocks until the plan runs out.
     */
    /** An engagement that has not landed a single hit in this long is not a fight, it is a stall. */
    private static final long MAX_ENGAGE_MILLIS = 15_000L;

    /** After standing down, stay out of the way this long so the interrupted work can resume. */
    private static final long STAND_DOWN_MILLIS = 5_000L;

    private static final Logger LOG = LoggerFactory.getLogger("numen_tacz");

    private long dangerLastSeen = NEVER;

    /** Last thing the chain reported, so the log shows the state machine, not a stampede. */
    private String lastNote = "";
    private long lastNoteAt = 0L;

    /** When the current engagement started; {@link #NEVER} while not engaged. */
    private long engagedSince = NEVER;
    /** Whether the current engagement has landed at least one shot. */
    private boolean struckThisEngagement;
    /** No re-engagement before this instant, after a fruitless engagement was dropped. */
    private long standDownUntil = NEVER;
    private static final Map<UUID, Long> RELOAD_AT = new ConcurrentHashMap<>();

    /** 每具身体此刻的自动防御状态，供 tacz.gun.defense() 读；不在表里 = 没在开火。 */
    static final Map<UUID, String> STATE = new ConcurrentHashMap<>();

    /** 她此刻在打什么；没在打返回 null。 */
    static String stateOf(UUID id) {
        return id == null ? null : STATE.get(id);
    }

    public GunDefenseChain() {
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (!companion.m_6084_()) {
            return false;
        }
        if (!TaczApi.hasGun(companion)) {
            return false;
        }
        if (companion.reflexPaused(ID)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < standDownUntil) {
            return false;
        }
        List<Entity> near = nearby(companion);
        Entity visible = nearestVisible(near, companion);
        if (visible == null) {
            // Nothing she can shoot. Leave the body to whatever she was doing, and let melee defense
            // wake at 4 m when something actually comes through the doorway.
            return dangerLastSeen != NEVER && now - dangerLastSeen < CALM_GRACE_MILLIS;
        }
        dangerLastSeen = now;
        if (visible != null && dist2(companion, visible) <= CQB_YIELD * CQB_YIELD
                && TaczApi.cramped(companion) && !gunIsSaferHere(companion, near, visible)) {
            // Face to face indoors, blade not clearly the worse option: do not take the body at all, so
            // melee self-defense (which wakes at 4 m) can fight it. This used to be decided inside tick()
            // and "handed the body over" by returning CANCELLED — which hands nothing over, so she just
            // stood there holding a gun while the zombie closed in.
            return false;
        }
        return true;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        long now = System.currentTimeMillis();
        List<Entity> near = nearby(companion);
        Entity target = nearestVisible(near, companion);
        if (target != null) {
            dangerLastSeen = now;
        }
        if (target == null) {
            // Nothing she can shoot. Do NOT stop the controls here: the selector has already given the
            // body to this chain, so a stop() leaves her standing still with no input at all, and the
            // task she was running never gets a tick to drive. Guarding is a stance, not a takeover.
            note(companion, "guard:no_los", null);
            return TaskState.RUNNING;
        }

        if (engagedSince == NEVER) {
            engagedSince = now;
            struckThisEngagement = false;
        } else if (!struckThisEngagement && now - engagedSince > MAX_ENGAGE_MILLIS) {
            // Sat here aiming at something that never became hittable. Give it up for a while so a
            // zombie tide cannot pin her in place by merely existing within range.
            standDownUntil = now + STAND_DOWN_MILLIS;
            engagedSince = NEVER;
            struckThisEngagement = false;
            STATE.remove(companion.m_20148_());
            note(companion, "standDown:no_hits", target);
            companion.controls().stop();
            return TaskState.CANCELLED;
        }

        // Cramped close quarters belongs to the blade, and canRun() already declines the body there, so
        // by the time we are here the gun has the job: aim and fire.

        // Aim at the target's eyes so the visual facing matches where the bullet goes.
        double ty = target instanceof LivingEntity ? target.m_20188_() : target.m_20186_() + 0.5;
        InputDriver.lookAt(companion, new Vec3(target.m_20185_(), ty, target.m_20189_()));
        float yaw = companion.m_146908_();
        float pitch = companion.m_146909_();

        // The gun has to be drawn before TaCZ will fire, and busy() is what draws it (once per
        // engagement) and tells us why it is not ready yet. Firing straight into fire() without
        // this gate returns NOT_DRAW forever - the chain then looks alive but never shoots.
        String why = TaczApi.busy(companion);
        if (why != null) {
            note(companion, "busy:" + why, target);
            if (why.startsWith("RELOADING") && TaczApi.ammoNow(companion) <= 0) {
                tryReload(companion);
            }
            return TaskState.RUNNING;
        }

        ShootResult r = TaczApi.fire(companion, pitch, yaw);
        note(companion, "fire:" + r, target);
        if (r == ShootResult.SUCCESS) {
            struckThisEngagement = true;
        } else if (r == ShootResult.NO_AMMO) {
            tryReload(companion);
        } else if (r == ShootResult.NEED_BOLT) {
            TaczApi.boltNow(companion);
        } else if (r == ShootResult.NOT_DRAW) {
            // Something cleared the draw state (a preemption, an equip): start a fresh draw next tick.
            TaczApi.forget(companion.m_20148_());
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        companion.controls().stop();
        TaczApi.forget(companion.m_20148_());
        engagedSince = NEVER;
        struckThisEngagement = false;
        STATE.remove(companion.m_20148_());
    }

    @Override
    public String name() {
        return ID;
    }

    // ---- Reflex roster paperwork ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "手持枪械时，有怪物靠近就自动举枪射击";
    }

    // ---- what counts as a target ----

    /** Hostile mobs within {@link #ENGAGE_RADIUS}. */
    private static List<Entity> nearby(NumenPlayer companion) {
        List<Entity> out = new ArrayList<>();
        try {
            double x = companion.m_20185_();
            double y = companion.m_20186_();
            double z = companion.m_20189_();
            AABB box = new AABB(x - ENGAGE_RADIUS, y - ENGAGE_RADIUS, z - ENGAGE_RADIUS,
                    x + ENGAGE_RADIUS, y + ENGAGE_RADIUS, z + ENGAGE_RADIUS);
            for (Entity e : companion.m_9236_().m_45976_(Entity.class, box)) {
                if (e == companion || !e.m_6084_()) {
                    continue;
                }
                if (e instanceof Monster || e instanceof Enemy || attackingHer(e, companion)) {
                    out.add(e);
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 正在打她的生物，哪怕不属于 {@link Enemy}。
     *
     * <p>{@code customnpcs} 的 {@code EntityNPCInterface} 只 extends {@code PathfinderMob}，
     * 不实现 {@code Enemy}，numen 自带的近战反射靠 {@code Menace.hostile} 也看不见它；这里补上
     * 「它瞄着她」与「它刚打过她」两种情形，让她被 NPC 枪手点名时也会抬枪还击。站着不动的
     * 商贩/任务 NPC 不会命中这两条，所以仍然不会被主动招惹。
     */
    private static boolean attackingHer(Entity e, NumenPlayer companion) {
        if (!(e instanceof Mob mob)) {
            return false;
        }
        try {
            return mob.m_5448_() == companion || companion.m_21214_() == e;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Nearest hostile that she can actually see; null when every one of them is behind cover. */
    private static Entity nearestVisible(List<Entity> near, NumenPlayer companion) {
        List<Entity> sorted = new ArrayList<>(near);
        sorted.sort((a, b) -> Double.compare(dist2(companion, a), dist2(companion, b)));
        for (Entity e : sorted) {
            if (TaczApi.los(companion, e)) {
                return e;
            }
        }
        return null;
    }

    private static double dist2(NumenPlayer companion, Entity e) {
        double dx = e.m_20185_() - companion.m_20185_();
        double dy = e.m_20186_() - companion.m_20186_();
        double dz = e.m_20189_() - companion.m_20189_();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Is shooting clearly safer than swinging here? True when she is already hurt, when several
     * hostiles are on top of her, or when the thing in front of her explodes when it is close.
     */
    private static boolean gunIsSaferHere(NumenPlayer companion, List<Entity> near, Entity target) {
        try {
            double ratio = companion.m_21223_() / Math.max(1.0, companion.m_21233_());
            if (ratio <= 0.5) {
                return true;
            }
            int close = 0;
            for (Entity e : near) {
                if (dist2(companion, e) <= 36.0) {
                    close++;
                }
            }
            if (close >= 3) {
                return true;
            }
            String id = String.valueOf(ForgeRegistries.ENTITY_TYPES.getKey(target.m_6095_()));
            return id.contains("creeper") || id.contains("explod");
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Report the state machine at most once a second, and immediately when it changes. */
    private void note(NumenPlayer companion, String what, Entity target) {
        long now = System.currentTimeMillis();
        if (what.equals(lastNote) && now - lastNoteAt < 1000L) {
            return;
        }
        boolean changed = !what.equals(lastNote);
        lastNote = what;
        lastNoteAt = now;
        if (target == null) {
            STATE.remove(companion.m_20148_());
        } else {
            STATE.put(companion.m_20148_(),
                    "打" + TaczApi.label(target) + "（" + what + "，它还剩 "
                            + String.format("%.1f", target instanceof LivingEntity le
                                    ? Math.max(0.0f, le.m_21223_()) : 0.0) + " 血）");
        }
        if (changed) {
            LOG.info("[numen_tacz] gun_defense {} · target={} · hp={}/{}", what,
                    target == null ? "-" : target.getClass().getSimpleName(),
                    String.format("%.1f", companion.m_21223_()),
                    String.format("%.1f", companion.m_21233_()));
        }
    }

    private static void tryReload(NumenPlayer companion) {
        UUID id = companion.m_20148_();
        long now = System.currentTimeMillis();
        Long last = RELOAD_AT.get(id);
        if (last != null && now - last < RELOAD_COOLDOWN_MILLIS) {
            return;
        }
        RELOAD_AT.put(id, now);
        TaczApi.reloadNow(companion);
    }
}
