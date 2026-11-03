package dev.local.numentacz;

import com.tacz.guns.api.event.common.EntityHurtByGunEvent;
import com.tacz.guns.api.event.common.EntityKillByGunEvent;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * 枪打中了什么。
 *
 * <p>TaCZ 在服务端自己发 {@link EntityHurtByGunEvent.Post} 与 {@link EntityKillByGunEvent}，所以这里记的是
 * 真的扣了血的那一下，不是「她瞄得准不准」的估算：抬枪打空、被墙挡住、打到队友（TaCZ 会取消）都不会进这张表。
 *
 * <p>为什么需要它：{@code shoot()} 返回的时候子弹才刚出膛，命中要等子弹飞到（通常下一两刻），所以那一次
 * 调用永远不可能知道结果；命中只能在事后由事件记下来，下一次调用（或 {@link TaczApi#hits}）再读。
 *
 * <p>只留最近 {@value #MAX} 条，内存里，重启就没了——这是「刚才打中了没有」的临时账本，不是战绩统计。
 */
final class HitLog {

    static final int MAX = 64;

    /** 一条命中。 */
    static final class Hit {
        final UUID shooter;
        final UUID victim;
        final String name;
        final String id;
        final double damage;
        final boolean headshot;
        final boolean killed;
        final long tick;
        final int distance;

        Hit(UUID shooter, UUID victim, String name, String id, double damage,
                boolean headshot, boolean killed, long tick, int distance) {
            this.shooter = shooter;
            this.victim = victim;
            this.name = name;
            this.id = id;
            this.damage = damage;
            this.headshot = headshot;
            this.killed = killed;
            this.tick = tick;
            this.distance = distance;
        }
    }

    private static final Deque<Hit> HITS = new ArrayDeque<>();

    private HitLog() {
    }

    static void register() {
        MinecraftForge.EVENT_BUS.addListener(HitLog::onHurt);
        MinecraftForge.EVENT_BUS.addListener(HitLog::onKill);
    }

    private static void onHurt(EntityHurtByGunEvent.Post e) {
        try {
            LivingEntity attacker = e.getAttacker();
            Entity hurt = e.getHurtEntity();
            if (attacker == null || hurt == null) {
                return;
            }
            if (attacker.m_9236_().m_5776_() || hurt.m_9236_().m_5776_()) {
                return;
            }
            add(attacker, hurt, e.getAmount(), e.isHeadShot(), false);
        } catch (Throwable ignored) {
            // 事件里的字段随版本变；读不到就当没这条，不能让她的枪因为记账挂掉。
        }
    }

    private static void onKill(EntityKillByGunEvent e) {
        try {
            LivingEntity attacker = e.getAttacker();
            LivingEntity killed = e.getKilledEntity();
            if (attacker == null || killed == null) {
                return;
            }
            if (attacker.m_9236_().m_5776_()) {
                return;
            }
            // 同一发子弹通常先来 hurt（记下了）再来 kill：这时只把刚才那条标成「打死了」，
            // 不然一次射击会记成两条，读的人会以为她多打中了一下。
            Hit last = lastOf(attacker.m_20148_());
            if (last != null && killed.m_20148_().equals(last.victim)
                    && attacker.m_9236_().m_46467_() - last.tick <= 2L) {
                HITS.remove(last);
                HITS.addLast(new Hit(last.shooter, last.victim, last.name, last.id,
                        last.damage, last.headshot, true, last.tick, last.distance));
                return;
            }
            add(attacker, killed, e.getBaseDamage(), e.isHeadShot(), true);
        } catch (Throwable ignored) {
        }
    }

    private static void add(LivingEntity attacker, Entity hurt, double damage,
            boolean headshot, boolean killed) {
        String id = "";
        String name = hurt.m_5446_().getString();
        try {
            ResourceLocation rl = ForgeRegistries.ENTITY_TYPES.getKey(hurt.m_6095_());
            if (rl != null) {
                id = rl.toString();
            }
        } catch (Throwable ignored) {
        }
        int distance = (int) Math.round(Math.sqrt(
                attacker.m_20275_(hurt.m_20185_(), hurt.m_20186_(), hurt.m_20189_())));
        HITS.addLast(new Hit(attacker.m_20148_(), hurt.m_20148_(), name, id,
                damage, headshot, killed, attacker.m_9236_().m_46467_(), distance));
        while (HITS.size() > MAX) {
            HITS.removeFirst();
        }
    }

    private static Hit lastOf(UUID shooter) {
        java.util.Iterator<Hit> it = HITS.descendingIterator();
        while (it.hasNext()) {
            Hit h = it.next();
            if (h.shooter.equals(shooter)) {
                return h;
            }
        }
        return null;
    }

    /** 最近一次打击；{@code windowTicks} 以内没有就 null。 */
    static Hit last(UUID shooter, long now, long windowTicks) {
        Hit h = lastOf(shooter);
        if (h == null) {
            return null;
        }
        return now - h.tick <= windowTicks ? h : null;
    }

    /** 这个射手最近的命中，新的在前。 */
    static List<Hit> of(UUID shooter, long now, long windowTicks) {
        List<Hit> out = new ArrayList<>();
        java.util.Iterator<Hit> it = HITS.descendingIterator();
        while (it.hasNext()) {
            Hit h = it.next();
            if (!h.shooter.equals(shooter)) {
                continue;
            }
            if (now - h.tick > windowTicks) {
                break;
            }
            out.add(h);
        }
        return out;
    }
}
