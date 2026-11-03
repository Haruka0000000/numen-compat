package dev.local.numenrs;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingEntityUseItemEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 让 API 做出来的事在客户端也看得见——由 _patch/gen_show.py 生成，别手改。
 *
 * <p>以前这些动作是纯后端：直接扣物品、直接加数值，玩家那一侧一点动静都没有，看起来像
 * 「她什么都没干，东西却没了」。真人不是这样：真人有动作、有声音、有「正在用」的那个姿势。
 *
 * <p>这里就补那一步：
 * <ul>
 *   <li>{@link #hold} 把那一件拿到主手；</li>
 *   <li>{@link #use} 让她开始「使用」——服务端一调，原版会把「正在用」同步给周围所有客户端，
 *       客户端照着物品的 {@code UseAnim} 播喝水/吃药动作和声音；</li>
 *   <li>使用时间满，原版自己调 {@code finishUsingItem}（药水会真的被喝掉、瓶子会真的还回来），
 *       同时 {@link #onFinish} 排下的那件事被触发；</li>
 *   <li>{@link #after} 是兜底：万一这次使用被打断（被打、换手、被别的插件抢手），到点了还没走完
 *       就把话收掉，不让等结果的那一边一直挂着。</li>
 * </ul>
 *
 * <p>砍树先看树：动作必须是原版那一条路，不能自己画——客户端只认「正在使用」这个同步标记，
 * 和手上那件东西的 {@code getUseAnimation}。
 */
public final class Show {

    /** 用完了要做什么（按身体）。 */
    private static final Map<UUID, Runnable> FINISH = new ConcurrentHashMap<>();

    /** 到点还没走完的兜底（按身体）。 */
    private static final Map<UUID, Timer> TIMERS = new ConcurrentHashMap<>();

    private static boolean installed;

    private Show() {
    }

    /** 注册事件（一个插件调一次就够，重复调不会重复注册）。 */
    public static void install() {
        if (installed) {
            return;
        }
        installed = true;
        MinecraftForge.EVENT_BUS.register(Show.class);
    }

    // ---------------------------------------------------------------- 手上

    /** 把她要用的那一件拿到主手；原来手上那件返回给调用方去安排（通常是塞回它来的那一格）。 */
    public static ItemStack hold(Player her, ItemStack one) {
        ItemStack old = her.m_21205_();
        her.m_21008_(InteractionHand.MAIN_HAND, one);
        return old;
    }

    /** 开始使用主手的东西。手上是空的返回 false。 */
    public static boolean use(Player her) {
        if (her.m_21205_().m_41619_()) {
            return false;
        }
        her.m_6672_(InteractionHand.MAIN_HAND);
        return true;
    }

    /** 收掉「正在使用」（不会触发 {@code finishUsingItem}，效果自己补）。 */
    public static void stop(Player her) {
        try {
            her.m_5810_();
        } catch (Throwable ignored) {
        }
    }

    /** 挥一下手（敲、放、倒东西时用）。 */
    public static void swing(Player her) {
        try {
            her.m_21011_(InteractionHand.MAIN_HAND, true);
        } catch (Throwable ignored) {
        }
    }

    /** 在她所在的位置放一声给周围人听（不是只给她自己）。 */
    public static void sound(LivingEntity her, SoundEvent sound, float volume, float pitch) {
        if (sound == null) {
            return;
        }
        try {
            her.m_9236_().m_5594_(null, her.m_20183_(), sound, SoundSource.PLAYERS, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    /** 按 id 放声：{@code minecraft:item.bucket.fill} 这种。查不到就什么也不做。 */
    public static void sound(LivingEntity her, String id, float volume, float pitch) {
        try {
            SoundEvent ev = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(id));
            sound(her, ev, volume, pitch);
        } catch (Throwable ignored) {
        }
    }

    // ---------------------------------------------------------------- 收尾

    /** 她用完了主手那件东西之后做什么。同一具身体只留最后一条。 */
    public static void onFinish(Player her, Runnable then) {
        FINISH.put(her.m_20148_(), then);
    }

    /** 等 {@code ticks} 刻之后做一件事；同一具身体只留最后一条。 */
    public static void after(Player her, int ticks, Runnable then) {
        TIMERS.put(her.m_20148_(), new Timer(Math.max(1, ticks), then));
    }

    /** 把某一具身体排着的东西都清掉（提前失败、换人、下线时用）。 */
    public static void cancel(Player her) {
        if (her == null) {
            return;
        }
        FINISH.remove(her.m_20148_());
        TIMERS.remove(her.m_20148_());
    }

    // ---------------------------------------------------------------- 事件

    @SubscribeEvent
    public static void onFinishedUsing(LivingEntityUseItemEvent.Finish event) {
        LivingEntity who = event.getEntity();
        if (!(who instanceof Player her)) {
            return;
        }
        UUID id = her.m_20148_();
        TIMERS.remove(id);
        Runnable then = FINISH.remove(id);
        if (then != null) {
            run(her, then);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || TIMERS.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Timer>> it = TIMERS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Timer> e = it.next();
            Timer t = e.getValue();
            if (--t.left > 0) {
                continue;
            }
            it.remove();
            Runnable then = FINISH.remove(e.getKey());
            if (then == null) {
                then = t.then;
            }
            Player her = event.getServer().m_6846_().m_11259_(e.getKey());
            if (her != null) {
                run(her, then);
            }
        }
    }

    private static void run(Player her, Runnable then) {
        try {
            then.run();
        } catch (Throwable ignored) {
            // 插件自己的收尾炸了不能带走服务端主循环。
        } finally {
            stop(her);
        }
    }

    private static final class Timer {
        int left;
        final Runnable then;

        Timer(int left, Runnable then) {
            this.left = left;
            this.then = then;
        }
    }
}
