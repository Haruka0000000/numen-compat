package dev.local.numenpingwheel;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.server.ServerLifecycleHooks;

import nx.pingwheel.common.network.PingLocationC2SPacket;

import com.dwinovo.numen.entity.CompanionRegistry;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.event.NumenEvents;

/**
 * 把 Ping Wheel 的标记接到 numen 这边，让她能读懂「主人刚才指的哪儿」。
 *
 * <p>Ping Wheel 的标记是纯客户端画的，专用服务器只看得到 {@code PingLocationC2SPacket} 路过。
 * 我们在 {@code ServerCore.onPingLocation} 的头部截住它（见 {@link dev.local.numenpingwheel.mixin.ServerCoreMixin}），
 * 把标记记下来，于是 {@code pingwheel.ping.latest()} 能报最近一个、
 * {@code pingwheel.ping.list()} 能报最近一串。
 *
 * <p>**只记模组自己会接受的那些包**。模组在同一个方法里先做三件事再转发：坏包（{@code isCorrupt()}，
 * 判据是 {@code channel == null}）直接拒、限流（{@code RateLimiter}，配置是
 * {@code rateLimit} 次 / {@code msToRegenerate} 毫秒）静默丢、频道模式（{@code DISABLED} /
 * {@code TEAM_ONLY}）不合规拒。我们在 HEAD 收到包时模组还没跑这些检查，所以自己按同一套规则
 * 再判一次 —— 否则「模组退回了、客户端根本没画」的包也会进历史，把真正有用的标记挤掉。
 *
 * <p>包里带着 {@code dimension}（整数维度 id）和 {@code entity}（指着某个实体/掉落物时的 UUID）。
 * 维度必须一起记 —— 不然下界标的一个点会被当成主世界里的同一组坐标。实体在查询时才去解析
 * 当前位置，所以跟着走的怪她能读到它「现在」在哪；实体没了也要说清楚，别让她以为点就在那儿。
 */
public final class PingHook {

    private PingHook() {
    }

    /** 历史里最多留几条；够一次出门用，又不会一直涨。 */
    static final int MAX_HISTORY = 32;

    /** 最新在前。自己一把锁；mixin 跑在服务端线程，读的一方不一定。 */
    private static final Deque<Shot> HISTORY = new ArrayDeque<>();

    /** 每个玩家的限流窗口（毫秒时间戳），照着模组那套规则再判一遍。 */
    private static final Map<UUID, Deque<Long>> RATE = new HashMap<>();

    /** 一条标记原样记下来；{@code entity} 在读的时候才解析成实时位置。 */
    static final class Shot {
        final String author;
        final UUID authorId;
        final int x;
        final int y;
        final int z;
        final String channel;
        final long at;
        final UUID entity;
        final ResourceKey<Level> dimension;

        Shot(String author, UUID authorId, int x, int y, int z, String channel, long at, UUID entity,
                ResourceKey<Level> dimension) {
            this.author = author;
            this.authorId = authorId;
            this.x = x;
            this.y = y;
            this.z = z;
            this.channel = channel;
            this.at = at;
            this.entity = entity;
            this.dimension = dimension;
        }
    }

    /** 模组在 {@code ServerCore.onPingLocation} 里用的那套判据，能反射拿到就用真的值。 */
    private static final class Mod {

        static Object serverConfig() {
            try {
                Class<?> cls = Class.forName("nx.pingwheel.common.config.ServerConfig");
                Field f = cls.getField("HANDLER");
                Object handler = f.get(null);
                Method get = handler.getClass().getMethod("getConfig");
                return get.invoke(handler);
            } catch (Throwable ignored) {
                return null;
            }
        }

        static int rateLimit() {
            return intOf(serverConfig(), "getRateLimit", 5);
        }

        static int msToRegenerate() {
            return intOf(serverConfig(), "getMsToRegenerate", 1000);
        }

        static String channelMode() {
            try {
                Object cfg = serverConfig();
                if (cfg == null) {
                    return "AUTO";
                }
                Object mode = cfg.getClass().getMethod("getDefaultChannelMode").invoke(cfg);
                return mode == null ? "AUTO" : String.valueOf(mode);
            } catch (Throwable ignored) {
                return "AUTO";
            }
        }

        static boolean hasTeam(ServerPlayer player) {
            try {
                Class<?> cls = Class.forName("nx.pingwheel.common.integration.TeamContextHandler");
                Method m = cls.getMethod("hasTeam", net.minecraft.world.entity.player.Player.class);
                Object r = m.invoke(null, player);
                return Boolean.TRUE.equals(r);
            } catch (Throwable ignored) {
                return false;
            }
        }

        /** 这个玩家现在待在哪个频道（模组自己的表）；拿不到就当不知道。 */
        @SuppressWarnings("unchecked")
        static String channelOf(UUID id) {
            try {
                Class<?> cls = Class.forName("nx.pingwheel.common.core.ServerCore");
                Field f = cls.getDeclaredField("PLAYER_CHANNELS");
                f.setAccessible(true);
                Object map = f.get(null);
                if (map instanceof Map) {
                    Object v = ((Map<Object, Object>) map).get(id);
                    return v == null ? "" : String.valueOf(v);
                }
            } catch (Throwable ignored) {
            }
            return "";
        }

        private static int intOf(Object cfg, String getter, int fallback) {
            try {
                if (cfg == null) {
                    return fallback;
                }
                Object v = cfg.getClass().getMethod(getter).invoke(cfg);
                return v instanceof Integer ? (Integer) v : fallback;
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    /** 模组真要转发的话，这里返回 true；返回 false 的包不要记。 */
    private static boolean accepted(ServerPlayer from, PingLocationC2SPacket packet, String channel) {
        try {
            if (packet.isCorrupt()) {
                return false;
            }
        } catch (Throwable ignored) {
        }
        // 限流：rateLimit 次 / msToRegenerate 毫秒，模组按人算
        int limit = Mod.rateLimit();
        if (limit > 0) {
            long window = Math.max(50L, Mod.msToRegenerate());
            long now = System.currentTimeMillis();
            synchronized (RATE) {
                Deque<Long> q = RATE.computeIfAbsent(from.m_20148_(), k -> new ArrayDeque<>());
                while (!q.isEmpty() && now - q.peekFirst() > window) {
                    q.removeFirst();
                }
                if (q.size() >= limit) {
                    return false;
                }
                q.addLast(now);
            }
        }
        String mode = Mod.channelMode();
        boolean blank = channel == null || channel.isEmpty();
        if (blank && "DISABLED".equals(mode)) {
            return false;
        }
        if (blank && "TEAM_ONLY".equals(mode) && !Mod.hasTeam(from)) {
            return false;
        }
        return true;
    }

    /** 从 mixin 调用：{@code ServerCore.onPingLocation} 的头部。 */
    public static void onPing(MinecraftServer server, ServerPlayer from, PingLocationC2SPacket packet) {
        try {
            if (server == null || from == null || packet == null) {
                return;
            }
            String channel;
            try {
                channel = packet.channel();
            } catch (Throwable t) {
                channel = null;
            }
            if (!accepted(from, packet, channel)) {
                return;
            }
            String name;
            try {
                name = from.m_7755_().getString();
            } catch (Throwable t) {
                name = "某人";
            }
            if (channel == null || channel.isEmpty()) {
                channel = "default";
            }
            Vec3 pos = packet.pos();
            // 标记是命中点（可能是方块中心 50.5），取 floor 才是它落在哪一个方块里；
            // round 会把 50.5 变成 51，偏一整格。
            int px = pos == null ? 0 : (int) Math.floor(pos.m_7096_());
            int py = pos == null ? 0 : (int) Math.floor(pos.m_7098_());
            int pz = pos == null ? 0 : (int) Math.floor(pos.m_7094_());
            UUID entity = null;
            try {
                entity = packet.entity();
            } catch (Throwable ignored) {
            }
            ResourceKey<Level> dim = null;
            try {
                dim = from.m_9236_().m_46472_();
            } catch (Throwable ignored) {
            }

            synchronized (HISTORY) {
                HISTORY.addFirst(new Shot(name, from.m_20148_(), px, py, pz, channel,
                        System.currentTimeMillis(), entity, dim));
                while (HISTORY.size() > MAX_HISTORY) {
                    HISTORY.removeLast();
                }
            }

            notifyCompanions(server, from, name, px, py, pz, channel, entity, dim);
        } catch (Throwable t) {
            try {
                org.apache.logging.log4j.LogManager.getLogger(PingMod.MODID)
                        .warn("ping hook failed: {}", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
    }

    /** 最新标记，给 {@code pingwheel.ping.latest()} 用；没有就返回 found = false。 */
    public static PingApi.Ping latest(NumenPlayer her) {
        List<Shot> all = snapshot(MAX_HISTORY);
        if (all.isEmpty()) {
            return PingApi.Ping.none("还没有任何标记——主人还没在标记轮盘上点过东西");
        }
        ServerPlayer owner = ownerOf(her);
        String ownerChannel = owner == null ? null : Mod.channelOf(owner.m_20148_());
        Shot best = null;
        boolean fromOwner = false;
        for (Shot shot : all) {
            if (!reachable(shot, owner, ownerChannel)) {
                continue;
            }
            if (owner != null && owner.m_20148_().equals(shot.authorId)) {
                best = shot;
                fromOwner = true;
                break;
            }
            if (best == null) {
                best = shot;
            }
        }
        if (best == null) {
            // 只有主人看不见的频道里的标记：如实说
            return PingApi.Ping.none("最近的标记都不是主人发给你看的那条"
                    + (owner == null ? "（主人现在不在线）" : "（频道对不上）"));
        }
        return resolve(her, best, fromOwner);
    }

    /** 最近 {@code n} 条，最新在前，给 {@code pingwheel.ping.list()} 用。 */
    public static List<PingApi.Ping> recent(NumenPlayer her, int n) {
        List<PingApi.Ping> out = new ArrayList<>();
        ServerPlayer owner = ownerOf(her);
        String ownerChannel = owner == null ? null : Mod.channelOf(owner.m_20148_());
        int seen = 0;
        for (Shot shot : snapshot(MAX_HISTORY)) {
            if (!reachable(shot, owner, ownerChannel)) {
                continue;
            }
            if (seen++ >= n) {
                break;
            }
            out.add(resolve(her, shot, owner != null && owner.m_20148_().equals(shot.authorId)));
        }
        return out;
    }

    /** 有多少条她读得到（主人看得见的频道 + 别人的也算，只用来算 hidden）。 */
    public static int readable(NumenPlayer her) {
        ServerPlayer owner = ownerOf(her);
        String ownerChannel = owner == null ? null : Mod.channelOf(owner.m_20148_());
        int n = 0;
        for (Shot shot : snapshot(MAX_HISTORY)) {
            if (reachable(shot, owner, ownerChannel)) {
                n++;
            }
        }
        return n;
    }

    /** 主人能不能看到这条标记：模组只转发同频道的。 */
    private static boolean reachable(Shot shot, ServerPlayer owner, String ownerChannel) {
        if (owner == null || ownerChannel == null || ownerChannel.isEmpty()) {
            return true;
        }
        return ownerChannel.equals(shot.channel);
    }

    private static ServerPlayer ownerOf(NumenPlayer her) {
        try {
            return her == null ? null : her.resolveOwnerPlayer();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 复制最新的 {@code n} 条 —— 复制，mixin 一边写我们一边读不打架。 */
    private static List<Shot> snapshot(int n) {
        List<Shot> out = new ArrayList<>();
        if (n <= 0) {
            return out;
        }
        synchronized (HISTORY) {
            for (Shot shot : HISTORY) {
                out.add(shot);
                if (out.size() >= n) {
                    break;
                }
            }
        }
        return out;
    }

    /** 把一条记下来的标记变成她要读的形状：实体解析成实时位置、维度与距离一起算出来。 */
    private static PingApi.Ping resolve(NumenPlayer her, Shot shot, boolean fromOwner) {
        int x = shot.x;
        int y = shot.y;
        int z = shot.z;
        String targetText = null;
        boolean gone = false;
        if (shot.entity != null) {
            Entity e = findEntity(shot);
            if (e != null) {
                x = (int) Math.floor(e.m_20185_());
                y = (int) Math.floor(e.m_20186_());
                z = (int) Math.floor(e.m_20189_());
                targetText = nameOf(e);
            } else {
                gone = true;
            }
        }
        long age = shot.at == 0L ? -1L : (System.currentTimeMillis() - shot.at) / 1000L;
        String dimText = dimName(shot.dimension);
        boolean here = sameDimension(her, shot.dimension);
        int distance = -1;
        if (her != null && here) {
            try {
                BlockPos a = her.m_20183_();
                BlockPos b = new BlockPos(x, y, z);
                distance = (int) Math.round(Math.sqrt(a.m_123331_(b)));
            } catch (Throwable ignored) {
            }
        }

        StringBuilder line = new StringBuilder();
        if (fromOwner) {
            line.append(shot.author).append(" 标记了 ");
        } else {
            line.append("这不是主人打的（").append(shot.author).append(" 标的），是 ");
        }
        line.append(x).append(", ").append(y).append(", ").append(z);
        if (!"default".equals(shot.channel)) {
            line.append("（频道 ").append(shot.channel).append("）");
        }
        if (!here) {
            line.append("，但那在 ").append(dimText).append("，不在你现在这个维度");
        } else if (distance >= 0) {
            line.append("，距你 ").append(distance).append(" 格");
        }
        if (targetText != null) {
            line.append("，跟着 ").append(targetText);
        } else if (gone) {
            line.append("，标在一个实体上，但那个实体已经不在了——坐标是标记当时的地方");
        }
        if (age > PingApi.FRESH_SECONDS) {
            line.append("；是").append(ago(age)).append("的点了，先去确认一下");
        } else if (age >= 0) {
            line.append("；").append(ago(age));
        }
        return new PingApi.Ping(!fromOwner, shot.author, true, x, y, z, dimText, here,
                "default".equals(shot.channel) ? Optional.empty() : Optional.of(shot.channel),
                age, age > PingApi.FRESH_SECONDS,
                targetText == null ? Optional.empty() : Optional.of(targetText),
                gone, distance, line.toString());
    }

    private static Entity findEntity(Shot shot) {
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return null;
        }
        if (shot.dimension != null) {
            ServerLevel lv = server.m_129880_(shot.dimension);
            if (lv != null) {
                Entity e = lv.m_8791_(shot.entity);
                if (e != null && e.m_6084_()) {
                    return e;
                }
            }
        }
        for (ServerLevel lv : server.m_129785_()) {
            Entity e = lv.m_8791_(shot.entity);
            if (e != null && e.m_6084_()) {
                return e;
            }
        }
        return null;
    }

    private static String nameOf(Entity e) {
        try {
            ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(e.m_6095_());
            String key = id == null ? "" : "entity." + id.toString().replace(':', '.');
            String zh = id == null ? "" : dev.local.numenpingwheel.Zh.name(key);
            if (zh != null && !zh.isEmpty() && !zh.equals(key)) {
                return zh;
            }
            return e.m_5446_().getString();
        } catch (Throwable t) {
            return shortUuid(e.m_20148_());
        }
    }

    private static String dimName(ResourceKey<Level> dim) {
        if (dim == null) {
            return "未知维度";
        }
        ResourceLocation id = dim.m_135782_();
        String ns = id.m_135827_();
        String path = id.m_135815_();
        if ("minecraft".equals(ns)) {
            if ("overworld".equals(path)) {
                return "主世界";
            }
            if ("the_nether".equals(path)) {
                return "下界";
            }
            if ("the_end".equals(path)) {
                return "末地";
            }
        }
        return ns + ":" + path;
    }

    private static boolean sameDimension(NumenPlayer her, ResourceKey<Level> dim) {
        try {
            if (her == null || dim == null) {
                return true;
            }
            return dim.equals(her.m_9236_().m_46472_());
        } catch (Throwable t) {
            return true;
        }
    }

    private static void notifyCompanions(MinecraftServer server, ServerPlayer from, String name,
            int x, int y, int z, String channel, UUID entity, ResourceKey<Level> dim) {
        try {
            UUID owner = from.m_20148_();
            Map<String, String> attrs = new HashMap<>();
            attrs.put("author", name);
            attrs.put("x", String.valueOf(x));
            attrs.put("y", String.valueOf(y));
            attrs.put("z", String.valueOf(z));
            attrs.put("dimension", dimName(dim));
            if (!"default".equals(channel)) {
                attrs.put("channel", channel);
            }
            if (entity != null) {
                attrs.put("entity", entity.toString());
            }
            String text = name + " 用标记轮盘点了个点（" + x + ", " + y + ", " + z + "，"
                    + dimName(dim) + "）"
                    + (entity != null ? "，跟着一个实体" : "")
                    + ("default".equals(channel) ? "" : "，频道 " + channel);
            for (Map.Entry<UUID, ?> e : CompanionRegistry.get(server).ownedBy(owner)) {
                NumenPlayer np = NumenPlayer.findByUuid(server, e.getKey());
                if (np != null) {
                    NumenEvents.emit(np, "ping", attrs, text, false);
                }
            }
        } catch (Throwable t) {
            try {
                org.apache.logging.log4j.LogManager.getLogger(PingMod.MODID)
                        .warn("ping notify failed: {}", String.valueOf(t));
            } catch (Throwable ignored) {
            }
        }
    }

    private static String shortUuid(UUID id) {
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String ago(long seconds) {
        if (seconds < 60) {
            return seconds + " 秒前";
        }
        if (seconds < 3600) {
            return (seconds / 60) + " 分钟前";
        }
        return (seconds / 3600) + " 小时前";
    }
}
