package dev.local.numenhordes;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.registries.ForgeRegistries;
import net.smileycorp.hordes.common.capability.HordesCapabilities;
import net.smileycorp.hordes.hordeevent.capability.HordeEvent;
import net.smileycorp.hordes.hordeevent.capability.HordeSavedData;

/**
 * {@code horde}: when the dead walk, and who among the players they infected.
 *
 * <p>The Hordes keeps one {@link HordeEvent} per player in the <em>overworld's</em>
 * {@link HordeSavedData}. That store is per level and the mod only ever advances a horde for a
 * player standing in the overworld ({@code HordeEvent.update} returns early anywhere else), so this
 * always reads the overworld's copy — reading the owner's current level answered "nothing is
 * happening" whenever they stepped into the nether.
 *
 * <p>Infection is a mob effect ({@code hordes:infected}), not the capability: the capability's
 * {@code getInfectionCount()} counts how many infections were <em>cured</em> (the mod multiplies the
 * next infection's length by it), so it is reported separately as {@code cured_times} instead of
 * being mistaken for "how infected".
 *
 * <p>Reading is all this does: no wave is started or stopped, no infection is added or cured.
 */
public final class HordeApi {

    /** How many chased players {@code hunted} lists before it stops and says how many it left out. */
    private static final int MAX_ROWS = 8;

    private static final ResourceLocation INFECTED = new ResourceLocation("hordes", "infected");
    private static final ResourceLocation IMMUNITY = new ResourceLocation("hordes", "immunity");

    /** {@code HordeEvent.timer}: ticks left in the running wave. Private, so read by name once. */
    private static final Field TIMER = field("timer");
    /** {@code HordeEvent.entitiesSpawned}: the wave's own mobs, so we can count the living ones. */
    private static final Field SPAWNED = field("entitiesSpawned");

    private static final java.util.Map<Class<?>, Method> GET_CAPABILITY = new java.util.HashMap<>();

    private HordeApi() {
    }

    static void install(NumenApi numen) {
        numen.api("horde", "潮汐：哪一夜尸潮会来、现在是不是正在打、还剩多久，她自己和主人身上有没有感染。", HordeApi.class);
    }

    // ---------------------------------------------------------------- shapes

    /** Where her owner's horde stands tonight. */
    @Doc("主人那边今晚的尸潮情况。")
    public record State(
            @Doc("今晚是不是主人要挨尸潮的那一夜（只有主世界算）。") boolean horde_night,
            @Doc("此刻有没有一波尸潮挂在主人名下。") boolean active,
            @Doc("最近一波尸潮是第几个游戏日开始的（开始那天的游戏日，不是「已经打了几天」）；没开过为 0。") int started_day,
            @Doc("当前游戏日。") int game_day,
            @Doc("模组排的下一次尸潮是第几个游戏日（模组自己照着的那个数）；0 表示没有排期。") int next_day,
            @Doc("离下一次还有几个游戏日；排期已过时是负数，写 0。") int days_left,
            @Doc("主世界今晚轮到挨尸潮的人数（含正在打的）。") int players_with_horde,
            @Doc("主人这一波还剩几秒；-1 表示没在打。") int seconds_left,
            @Doc("主人这一波场上还活着几只；没在打为 0。") int mobs,
            @Doc("她自己那一波的情况（她也是玩家，也可能被尸潮盯上）。") Mine mine,
            @Doc("一句话结论，可以直接读给主人听。") String line) {
    }

    /** The horde that is chasing her, if any. */
    @Doc("追她自己的那一波尸潮。")
    public record Mine(
            @Doc("她自己是不是正在被尸潮追。") boolean active,
            @Doc("今晚轮不轮到她挨（只有主世界算）。") boolean horde_night,
            @Doc("还剩几秒；-1 表示没在打。") int seconds_left,
            @Doc("她这一波场上还活着几只。") int mobs) {

        static Mine none() {
            return new Mine(false, false, -1, 0);
        }
    }

    /** One player who is being chased by a horde right now. */
    @Doc("一个正在被尸潮追的玩家。")
    public record Hunted(
            @Doc("玩家名。") String name,
            @Doc("他那一波是第几个游戏日排的（正在打就是开始那天）。") int started_day,
            @Doc("还剩几秒。") int seconds_left,
            @Doc("离她多少格；不在同一维度时是 -1。") int distance,
            @Doc("一句话结论。") String line) {
    }

    /** Everyone being chased right now. */
    @Doc("此刻被尸潮追的人。")
    public record Hunt(
            @Doc("被追的人，最近的排最前；最多 8 个人。") List<Hunted> rows,
            @Doc("一共有几个人被追（可能比 rows 多）。") int total,
            @Doc("一句话结论。") String line) {
    }

    /** What one subject carries infection-wise. */
    @Doc("一个人的感染情况。")
    public record Infection(
            @Doc("说的是谁（「她自己」「主人」或玩家名）。") String who,
            @Doc("身上有没有感染（hordes:infected 效果）。") boolean infected,
            @Doc("有没有免疫护体（hordes:immunity，期间不会被传染）。") boolean immune,
            @Doc("感染有几级（效果等级）；没感染为 0。") int level,
            @Doc("感染还剩几秒；没感染为 0，永久为 -1。") int seconds,
            @Doc("被治好过几次：每治好一次，下一次感染会来得更快；-1 表示读不到。") int cured_times,
            @Doc("效果的中文名，例如「病毒感染」。") String name,
            @Doc("一句话结论，可以直接读给人听。") String line) {
    }

    /** Which subject {@code horde.horde.infection} asks about. */
    @Doc("看谁的感染。")
    public record WhoOpt(
            @Doc("不写就是她自己；写「主人」就是主人；也可以写玩家名。") Optional<String> player) {
    }

    // ---------------------------------------------------------------- calls

    @Fn("看主人那边今晚的尸潮：今天是不是那一夜、有没有正在打、还要打多久、下一次排在哪天，顺带她自己那一波。")
    @Note("只读。它不会提前或推迟尸潮，也不会把尸潮引开或引来；要动手还是得靠她自己。")
    @Example("horde.horde.state()")
    public static State state(ServerCall call) {
        ServerPlayer her = call.her();
        ServerPlayer owner = owner(call);
        HordeSavedData data = data(call);
        HordeEvent event = data.getEvent(owner);
        int gameDay = safe(() -> event.getCurrentDay(owner), 0);
        boolean active = safe(event::isActive, false);
        boolean night = safe(() -> data.isHordeNight(owner), false);
        int started = safe(event::getDay, 0);
        int nextDay = safe(event::getNextDay, 0);
        int daysLeft = nextDay <= 0 ? 0 : Math.max(0, nextDay - gameDay);
        int playersWithHorde = safe(() -> (int) data.getPlayersWithHorde().count(), 0);
        int secondsLeft = active ? seconds(event) : -1;
        int mobs = active ? mobs(event) : 0;

        Mine mine = Mine.none();
        if (her != null) {
            HordeEvent herEvent = data.getEvent(her);
            boolean herActive = safe(herEvent::isActive, false);
            mine = new Mine(herActive,
                    safe(() -> data.isHordeNight(her), false),
                    herActive ? seconds(herEvent) : -1,
                    herActive ? mobs(herEvent) : 0);
        }

        boolean inOverworld = owner.m_9236_().m_46472_() == Level.f_46428_;
        String line;
        if (active && inOverworld) {
            line = "尸潮正在打（第 " + started + " 个游戏日开始的），场上还有 " + mobs + " 只，大约还要打 "
                    + wave(secondsLeft) + "；别回地面送";
        } else if (active) {
            line = "有一波尸潮还挂在主人名下（大约还剩 " + wave(secondsLeft) + "），但主人在别的维度；"
                    + "尸潮只在主世界打，他回主世界才会继续";
        } else if (night) {
            line = "今天（第 " + gameDay + " 个游戏日）就是主人要挨尸潮的那一夜；天黑前把入口封好、子弹上满，别睡";
        } else if (nextDay > 0 && daysLeft > 0) {
            line = "今晚安静。模组排的下一次尸潮是第 " + nextDay + " 个游戏日，还有 " + daysLeft + " 天可以准备";
        } else if (nextDay > 0) {
            line = "今晚安静。模组排的那天（第 " + nextDay + " 个游戏日）已经过去了，等它自己重排下一波";
        } else {
            line = "今晚安静，也没有排上尸潮的日子";
        }
        if (mine.active() && her != owner) {
            line += "；她自己也被一波尸潮追着，大约还剩 " + wave(mine.seconds_left());
        }
        return new State(night, active, started, gameDay, nextDay, daysLeft, playersWithHorde,
                secondsLeft, mobs, mine, line);
    }

    @Fn("看主世界此刻有谁正在被尸潮追、谁离她最近、各自还剩多久。")
    @Note("只读。看到别人被追不等于要去救；要不要过去由她自己决定。不在主世界的人不在这份名单里。")
    @Example("horde.horde.hunted()")
    public static Hunt hunted(ServerCall call) {
        ServerPlayer her = call.her();
        HordeSavedData data = data(call);
        List<Hunted> out = new ArrayList<>();
        int total;
        try {
            List<ServerPlayer> chased = data.getPlayersWithHorde().toList();
            total = chased.size();
            for (ServerPlayer player : chased) {
                HordeEvent event = data.getEvent(player);
                int started = safe(event::getDay, 0);
                // 模组的 getPlayersWithHorde() 是「今晚轮到他挨」，不是「正在打」：
                // 名单里有人在等着开打（isActive=false），照「正被追」写就是在替模组编话。
                boolean active = safe(event::isActive, false);
                int left = active ? seconds(event) : -1;
                int distance = -1;
                if (her != null && her.m_9236_() == player.m_9236_()) {
                    distance = (int) Math.round(Math.sqrt(her.m_20280_(player)));
                }
                String name = nameOf(player);
                String where = distance < 0 ? "在别的维度" : "距你 " + distance + " 格";
                String what = active
                        ? "正被尸潮追（第 " + started + " 个游戏日开始的，还剩 " + wave(left) + "）"
                        : "今晚轮到他挨尸潮（第 " + started + " 个游戏日排的），还没开打";
                out.add(new Hunted(name, started, left, distance, name + " " + what + "，" + where));
            }
        } catch (Throwable t) {
            throw new ApiError(ErrorKind.FAILED, "读不到尸潮名单", "别的地方可能正在改动潮汐数据，下次再问。", t.toString());
        }
        out.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        int shown = Math.min(out.size(), MAX_ROWS);
        List<Hunted> rows = new ArrayList<>(out.subList(0, shown));
        String line;
        if (rows.isEmpty()) {
            line = "现在没有人的尸潮夜";
        } else if (total > shown) {
            line = "现在主世界有 " + total + " 个人的尸潮夜，最近的 " + shown + " 个是：" + rows.get(0).name() + " 等";
        } else {
            line = "现在主世界有 " + total + " 个人的尸潮夜，最近的是 " + rows.get(0).name();
        }
        return new Hunt(rows, total, line);
    }

    @Fn("看某个人身上的感染：有没有、几级、还剩多久、免疫在不在、被治好过几次。")
    @Note("只读。它不会治疗也不会加重感染——治疗要用解药或疫苗，这里只负责让她知道情况。"
            + "不写 who 就是看她自己；感染的判据是 hordes:infected 这个效果，不是模组那个「治好次数」计数器。")
    @Example("horde.horde.infection()")
    @Example("horde.horde.infection({player = \"主人\"})")
    public static Infection infection(ServerCall call, WhoOpt opt) {
        ServerPlayer target = target(call, opt);
        MobEffect infected = effect(INFECTED);
        MobEffect immunity = effect(IMMUNITY);
        MobEffectInstance state = infected == null ? null : target.m_21124_(infected);
        boolean immune = immunity != null && target.m_21023_(immunity);
        String name = infected == null ? "infection" : Zh.name(infected.m_19481_());
        int cured = curedTimes(target);

        boolean sick = state != null;
        int level = sick ? state.m_19564_() + 1 : 0;
        int seconds = sick ? secondsOf(state.m_19557_()) : 0;
        String line;
        if (sick) {
            line = target.m_5446_().getString() + " 身上有" + name + "（" + level + " 级，还剩 " + words(seconds)
                    + "）；被感染的怪打中会传染，吃解药或者赶紧躲开感染源";
        } else if (immune) {
            line = target.m_5446_().getString() + " 身上没有感染，而且有免疫护体，这段时间不会被传染";
        } else {
            line = target.m_5446_().getString() + " 身上没有感染";
        }
        if (cured > 0) {
            line += "；已经被治好过 " + cured + " 次，下次感染会来得更快";
        }
        return new Infection(target.m_5446_().getString(), sick, immune, level, seconds, cured, name, line);
    }

    // ---------------------------------------------------------------- body state

    /**
     * What {@code numen.contributeBodyState} appends: one line about tonight's horde, so she stops
     * asking "is it safe to go up" every turn.
     */
    static String stateLine(com.dwinovo.numen.entity.NumenPlayer her) {
        if (her == null) {
            return "";
        }
        try {
            ServerPlayer owner = her.resolveOwnerPlayer();
            HordeSavedData data = HordeSavedData.getData(overworld(her));
            StringBuilder out = new StringBuilder();
            if (owner != null) {
                HordeEvent event = data.getEvent(owner);
                if (safe(event::isActive, false)) {
                    out.append("潮汐：主人在挨尸潮，还剩 ").append(wave(seconds(event)))
                            .append("（场上 ").append(mobs(event)).append(" 只）");
                } else if (safe(() -> data.isHordeNight(owner), false)) {
                    out.append("潮汐：今晚就是尸潮夜，天黑会来");
                } else {
                    int next = safe(event::getNextDay, 0);
                    int now = safe(() -> event.getCurrentDay(owner), 0);
                    if (next > now) {
                        out.append("潮汐：安静，下一次第 ").append(next).append(" 个游戏日（还有 ").append(next - now)
                                .append(" 天）");
                    }
                }
            }
            HordeEvent mine = data.getEvent(her);
            if (safe(mine::isActive, false)) {
                if (out.length() > 0) {
                    out.append("；");
                }
                out.append("潮汐：她自己也被追着，还剩 ").append(wave(seconds(mine)));
            }
            return out.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    // ---------------------------------------------------------------- plumbing

    /** The overworld's saved data: hordes never run anywhere else, whatever level the owner is in. */
    private static HordeSavedData data(ServerCall call) {
        return HordeSavedData.getData(overworld(call.her()));
    }

    private static ServerLevel overworld(ServerPlayer her) {
        MinecraftServer server = her.m_9236_().m_7654_();
        ServerLevel overworld = server.m_129880_(Level.f_46428_);
        if (overworld == null) {
            throw new ApiError(ErrorKind.FAILED, "读不到主世界", "服务器还没把主世界加载出来，等一下再问。", null);
        }
        return overworld;
    }

    private static ServerPlayer owner(ServerCall call) {
        com.dwinovo.numen.entity.NumenPlayer her = call.her();
        ServerPlayer owner = her == null ? null : her.resolveOwnerPlayer();
        if (owner == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没有主人", "她还没有主人，读不到谁的尸潮。", null);
        }
        return owner;
    }

    /** "自己 / 主人 / 玩家名" → the player it means. */
    private static ServerPlayer target(ServerCall call, WhoOpt opt) {
        String who = opt == null || opt.player() == null ? "" : opt.player().orElse("").trim();
        ServerPlayer her = call.her();
        if (who.isEmpty() || who.equals("我") || who.equals("自己") || who.equals("她自己")) {
            if (her == null) {
                throw new ApiError(ErrorKind.NOT_FOUND, "她没有身体", "她现在没有可读的身体。", null);
            }
            return her;
        }
        if (who.equals("主人") || who.equals("owner")) {
            return owner(call);
        }
        ServerPlayer found = her == null ? null : her.m_9236_().m_7654_().m_6846_().m_11255_(who);
        if (found == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "没找到这个玩家：" + who,
                    "写完整的游戏名；也可以不写（看她自己）或者写「主人」。", null);
        }
        return found;
    }

    private static int rank(Hunted hunted) {
        return hunted.distance() < 0 ? Integer.MAX_VALUE : hunted.distance();
    }

    private static String nameOf(ServerPlayer player) {
        return player.m_36316_().getName();
    }

    private static MobEffect effect(ResourceLocation id) {
        return ForgeRegistries.MOB_EFFECTS.getValue(id);
    }

    /**
     * How many times this player has been cured. It comes from the Forge capability and is only a
     * footnote, so an unreadable capability reports -1 instead of pretending nobody was ever cured.
     */
    private static int curedTimes(ServerPlayer player) {
        try {
            Object capability = capabilityOf(player);
            if (!(capability instanceof net.minecraftforge.common.util.LazyOptional<?> lazy)) {
                return -1;
            }
            Object value = lazy.resolve().orElse(null);
            if (!(value instanceof net.smileycorp.hordes.infection.capability.Infection infection)) {
                return -1;
            }
            return infection.getInfectionCount();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Ticks left in a running wave, in seconds; -1 when the timer cannot be read. */
    private static int seconds(HordeEvent event) {
        if (TIMER == null) {
            return -1;
        }
        try {
            int ticks = TIMER.getInt(event);
            return ticks <= 0 ? -1 : Math.max(1, (ticks + 19) / 20);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** Living mobs this wave still has out. 0 when the set cannot be read. */
    private static int mobs(HordeEvent event) {
        if (SPAWNED == null) {
            return 0;
        }
        try {
            Object value = SPAWNED.get(event);
            if (!(value instanceof Set<?> spawned)) {
                return 0;
            }
            int alive = 0;
            for (Object mob : spawned) {
                if (mob instanceof Entity entity && entity.m_213877_()) {
                    alive++;
                }
            }
            return alive;
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private static int secondsOf(int ticks) {
        if (ticks < 0) {
            return -1;
        }
        return Math.max(1, (ticks + 19) / 20);
    }

    /**
     * 尸潮还剩多久。
     *
     * <p>尸潮没有「永久」这回事：{@code seconds(...)} 读不到时回 -1，直接喂给 {@link #words(int)}
     * 会印成「大约还要打 永久」，所以这里单独把负数说成「不知道多久」。
     */
    private static String wave(int seconds) {
        return seconds < 0 ? "不知道多久" : words(seconds);
    }

    /** -1 → 「永久」, negative → 「不知道还要多久」, else N 秒 / N 分 M 秒. */
    private static String words(int seconds) {
        if (seconds == -1) {
            return "永久";
        }
        if (seconds < 0) {
            return "不知道多久";
        }
        if (seconds < 60) {
            return seconds + " 秒";
        }
        return (seconds / 60) + " 分 " + (seconds % 60) + " 秒";
    }

    private static Field field(String name) {
        try {
            Field found = HordeEvent.class.getDeclaredField(name);
            found.setAccessible(true);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Whether the reflective capability lookup resolves at all here. Logged once at registration,
     * because a silently missing lookup would read as "nobody was ever cured" forever.
     */
    static boolean capabilityLookupReady() {
        return methodFor(net.minecraft.world.entity.Entity.class) != null;
    }

    private static Object capabilityOf(ServerPlayer player) {
        Method method = methodFor(player.getClass());
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(player, HordesCapabilities.INFECTION, null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * The patched {@code getCapability(Capability, Direction)} of this entity type. It is a Forge
     * patch method: present at runtime, absent from the SRG jar, so it is found by name once per type.
     */
    private static Method methodFor(Class<?> type) {
        Method method = GET_CAPABILITY.get(type);
        if (method != null) {
            return method;
        }
        for (Class<?> search = type; search != null && search != Object.class; search = search.getSuperclass()) {
            for (Method candidate : search.getDeclaredMethods()) {
                if (!candidate.getName().equals("getCapability") || candidate.getParameterCount() != 2) {
                    continue;
                }
                if (candidate.getParameterTypes()[0] != Capability.class) {
                    continue;
                }
                try {
                    candidate.setAccessible(true);
                } catch (Throwable ignored) {
                    continue;
                }
                GET_CAPABILITY.put(type, candidate);
                return candidate;
            }
        }
        try {
            Method found = type.getMethod("getCapability", Capability.class, net.minecraft.core.Direction.class);
            found.setAccessible(true);
            GET_CAPABILITY.put(type, found);
            return found;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static <T> T safe(java.util.function.Supplier<T> body, T fallback) {
        try {
            T value = body.get();
            return value == null ? fallback : value;
        } catch (Throwable ignored) {
            return fallback;
        }
    }
}
