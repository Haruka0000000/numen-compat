package dev.local.numenprobe;

import com.dwinovo.numen.entity.Companions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Temporary admin probe: brings a numen companion into a world without an owner logging in.
 *
 * <p>Why this exists: most sense groups in this project were only ever checked structurally. Their
 * real readings cannot be exercised while nobody is online, because the engine's own
 * {@code /numen player summon} needs a player to run it. {@link Companions#summon} takes a plain
 * owner UUID and a position, so a synthetic owner is enough to get a body into the world; after that
 * the console can drive her with {@code /numen drive <name> <lua>} and each call's receipt lands in
 * the server log.
 *
 * <p>This is NOT a sense plugin and must not stay installed: it writes a companion into the save and
 * only exists for one verification pass. {@code /numenprobe dismiss} removes both the body and the
 * registry entry.
 */
@Mod(ProbeMod.MODID)
public final class ProbeMod {

    public static final String MODID = "numen_probe";
    public static final String BUILD = "probe1";

    /** Synthetic owner: not a real account, so the companion can never be "yours" by accident. */
    private static final UUID PROBE_OWNER = UUID.fromString("00000000-0000-4000-8000-00000000d0b1");

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public ProbeMod() {
        LOG.warn("[numen_probe] constructed build={} numen={}", BUILD, ModList.get().isLoaded("numen"));
        if (!ModList.get().isLoaded("numen")) {
            return;
        }
        MinecraftForge.EVENT_BUS.addListener(ProbeMod::commands);
        com.dwinovo.numen.api.NumenPlugins.register(com.dwinovo.numen.api.NumenPlugins.NUMEN, ProbeApi::install);
    }

    private static void commands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.m_82127_("numenprobe")
                .requires(source -> source.m_6761_(2))
                .then(Commands.m_82127_("summon")
                        .executes(ctx -> summon(ctx, "D"))
                        .then(Commands.m_82129_("name", StringArgumentType.word())
                                .executes(ctx -> summon(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.m_82127_("dismiss")
                        .executes(ctx -> dismiss(ctx, "D"))
                        .then(Commands.m_82129_("name", StringArgumentType.word())
                                .executes(ctx -> dismiss(ctx, StringArgumentType.getString(ctx, "name")))))
                .then(Commands.m_82127_("owner")
                        .then(Commands.m_82129_("companion", StringArgumentType.word())
                                .then(Commands.m_82129_("player", StringArgumentType.word())
                                        .executes(ProbeMod::owner))))
                .then(Commands.m_82127_("where").executes(ProbeMod::where))
                .then(Commands.m_82127_("ghouls").executes(ProbeMod::ghouls))
                .then(Commands.m_82127_("ghoulref")
                        .then(Commands.m_82129_("companion", StringArgumentType.word())
                                .executes(ProbeMod::ghoulref)))
                .then(Commands.m_82127_("ghoulcheck").executes(ProbeMod::ghoulcheck))
                .then(Commands.m_82127_("open")
                        .then(Commands.m_82129_("companion", StringArgumentType.word())
                                .then(Commands.m_82129_("pos", StringArgumentType.greedyString())
                                        .executes(ProbeMod::open))))
                .then(Commands.m_82127_("run")
                        .then(Commands.m_82129_("companion", StringArgumentType.word())
                                .then(Commands.m_82129_("program", StringArgumentType.greedyString())
                                        .executes(ProbeMod::run))))
                .then(Commands.m_82127_("async")
                        .then(Commands.m_82129_("companion", StringArgumentType.word())
                                .then(Commands.m_82129_("program", StringArgumentType.greedyString())
                                        .executes(ProbeMod::async))))
                .then(Commands.m_82127_("pingtest")
                        .then(Commands.m_82129_("companion", StringArgumentType.word())
                                .then(Commands.m_82129_("spec", StringArgumentType.greedyString())
                                        .executes(ProbeMod::pingtest)))));
    }

    /**
     * Where does a dismissed body still live? Prints every List/Map field on the PlayerList
     * (by runtime type, no obfuscated names) plus each level's player list, with the companions
     * (ServerPlayer subclasses declared by numen) still present in them. A leak shows up as a
     * dismissed body that the list no longer shows but a map/level still holds.
     */
    private static int ghouls(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.m_81377_();
        StringBuilder sb = new StringBuilder("ghouls:");
        try {
            net.minecraft.server.players.PlayerList list = server.m_6846_();
            for (java.lang.reflect.Field f : list.getClass().getSuperclass().getDeclaredFields()) {
                f.setAccessible(true);
                Object v = f.get(list);
                if (v instanceof java.util.Collection<?> c) {
                    sb.append(" ").append(f.getName()).append("=C").append(c.size());
                    for (Object o : c) {
                        if (o instanceof net.minecraft.server.level.ServerPlayer p
                                && p.getClass() != net.minecraft.server.level.ServerPlayer.class) {
                            sb.append("[").append(p.m_7755_().getString()).append("]");
                        }
                    }
                } else if (v instanceof java.util.Map<?, ?> m) {
                    sb.append(" ").append(f.getName()).append("=M").append(m.size());
                    int shown = 0;
                    for (var e : m.entrySet()) {
                        if (shown++ >= 3) break;
                        sb.append("{").append(e.getKey() == null ? "null" : e.getKey().getClass().getSimpleName())
                                .append("->").append(e.getValue() == null ? "null" : e.getValue().getClass().getSimpleName()).append("}");
                        if (e.getValue() instanceof net.minecraft.server.level.ServerPlayer p
                                && p.getClass() != net.minecraft.server.level.ServerPlayer.class) {
                            sb.append("[").append(p.m_7755_().getString()).append("]");
                        }
                    }
                }
            }
            for (net.minecraft.server.level.ServerLevel lvl : server.m_129785_()) {
                int custom = 0;
                for (net.minecraft.world.entity.player.Player p : lvl.m_6907_()) {
                    if (p.getClass() != net.minecraft.server.level.ServerPlayer.class
                            && p instanceof net.minecraft.server.level.ServerPlayer) {
                        custom++;
                    }
                }
                sb.append(" level=").append(lvl.m_46472_().m_135782_()).append("/").append(lvl.m_6907_().size())
                        .append(" bodies=").append(custom);
            }
        } catch (Throwable t) {
            sb.append(" failed: ").append(t);
        }
        String line = sb.toString().replace('\n', ' ');
        LOG.warn("[numen_probe] {}", line);
        source.m_288197_(() -> Component.m_237113_(line), false);
        return 1;
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.m_81377_();
        try {
            Vec3 at = source.m_81371_();
            NumenPlayer her = Companions.summon(server, PROBE_OWNER, name, source.m_81372_(), at);
            if (her == null) {
                source.m_81352_(Component.m_237113_("probe summon returned null for " + name));
                return 0;
            }
            BlockPos pos = her.m_20183_();
            String line = "probe summoned " + her.m_7755_().getString()
                    + " uuid=" + her.m_20148_()
                    + " at " + pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_()
                    + " dim=" + her.m_9236_().m_46472_().m_135782_()
                    + " owner=" + PROBE_OWNER;
            LOG.warn("[numen_probe] {}", line);
            source.m_288197_(() -> Component.m_237113_(line), false);
            return 1;
        } catch (Throwable t) {
            LOG.warn("[numen_probe] summon failed", t);
            source.m_81352_(Component.m_237113_("probe summon failed: " + t));
            return 0;
        }
    }

    private static int dismiss(CommandContext<CommandSourceStack> ctx, String name) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.m_81377_();
        try {
            int removed = Companions.dismissByName(server, PROBE_OWNER, name);
            String line = "probe dismissed " + name + " bodies=" + removed;
            LOG.warn("[numen_probe] {}", line);
            source.m_288197_(() -> Component.m_237113_(line), false);
            return removed;
        } catch (Throwable t) {
            LOG.warn("[numen_probe] dismiss failed", t);
            source.m_81352_(Component.m_237113_("probe dismiss failed: " + t));
            return 0;
        }
    }

    /**
     * Point a probe body at a real owner, so owner-bound sense plugins can be exercised with nobody
     * else logged in. The named player is looked up in the player list, which includes companions, so
     * {@code /numenprobe owner D D} makes her her own owner and lets her own per-player saved data
     * (horde events, infection) be read back through those plugins.
     */
    private static int owner(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.m_81377_();
        String name = StringArgumentType.getString(ctx, "companion");
        String who = StringArgumentType.getString(ctx, "player");
        NumenPlayer her = find(source, name);
        if (her == null) {
            source.m_81352_(Component.m_237113_("no companion named " + name + " here"));
            return 0;
        }
        for (net.minecraft.server.level.ServerPlayer player : server.m_6846_().m_11314_()) {
            if (player.m_7755_().getString().equals(who)) {
                her.setOwnerUuid(player.m_20148_());
                String line = "probe owner " + name + " -> " + who + " (" + player.m_20148_() + ")";
                LOG.warn("[numen_probe] {}", line);
                source.m_288197_(() -> Component.m_237113_(line), false);
                return 1;
            }
        }
        source.m_81352_(Component.m_237113_("no player named " + who + " online"));
        return 0;
    }

    private static java.lang.ref.WeakReference<net.minecraft.server.level.ServerPlayer> GHOUL_REF;
    private static String GHOUL_NAME;

    /**
     * Forge a Ping Wheel marker without a client: builds a {@code PingLocationC2SPacket} by
     * reflection and hands it to the plugin's hook, exactly where the mixin would.
     *
     * <p>{@code /numenprobe pingtest D 41 119 41 [chan=home] [dim=-1] [entity=<uuid>] [nopos] [nullchan]}
     * — {@code nullchan} produces the only shape Ping Wheel itself calls corrupt, so it shows what the
     * plugin records before the mod's own validation runs.
     */
    private static int pingtest(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.m_81377_();
        String name = StringArgumentType.getString(ctx, "companion");
        String spec = StringArgumentType.getString(ctx, "spec");
        NumenPlayer her = find(source, name);
        if (her == null) {
            source.m_81352_(Component.m_237113_("no companion named " + name + " here"));
            return 0;
        }
        java.util.List<String> nums = new java.util.ArrayList<>();
        String channel = "";
        int dim = 0;
        UUID entity = null;
        boolean noPos = false;
        boolean nullChan = false;
        for (String token : spec.trim().split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("chan=")) {
                channel = token.substring(5);
            } else if (token.startsWith("dim=")) {
                dim = Integer.parseInt(token.substring(4));
            } else if (token.startsWith("entity=")) {
                entity = UUID.fromString(token.substring(7));
            } else if (token.equals("nopos")) {
                noPos = true;
            } else if (token.equals("nullchan")) {
                nullChan = true;
            } else {
                nums.add(token);
            }
        }
        double x = 0, y = 0, z = 0;
        try {
            if (nums.size() >= 3) {
                x = Double.parseDouble(nums.get(0));
                y = Double.parseDouble(nums.get(1));
                z = Double.parseDouble(nums.get(2));
            }
        } catch (NumberFormatException e) {
            source.m_81352_(Component.m_237113_("bad coordinates in spec: " + spec));
            return 0;
        }
        try {
            Class<?> pktClass = Class.forName("nx.pingwheel.common.network.PingLocationC2SPacket");
            Object packet = pktClass
                    .getConstructor(String.class, net.minecraft.world.phys.Vec3.class, UUID.class, int.class, int.class)
                    .newInstance(nullChan ? null : channel, noPos ? null : new net.minecraft.world.phys.Vec3(x + 0.5, y + 0.001, z + 0.5),
                            entity, 1, dim);
            boolean corrupt = (boolean) pktClass.getMethod("isCorrupt").invoke(packet);
            Class<?> hook = Class.forName("dev.local.numenpingwheel.PingHook");
            hook.getMethod("onPing", MinecraftServer.class, net.minecraft.server.level.ServerPlayer.class, pktClass)
                    .invoke(null, server, her, packet);
            String line = "probe pingtest " + name + " pos=" + (noPos ? "none" : x + "," + (y + 0.001) + "," + (z + 0.5))
                    + " chan=" + (nullChan ? "null" : "\"" + channel + "\"") + " dim=" + dim
                    + " entity=" + entity + " isCorrupt=" + corrupt + " (hook invoked)";
            LOG.warn("[numen_probe] {}", line);
            source.m_288197_(() -> Component.m_237113_(line), false);
            return 1;
        } catch (Throwable t) {
            String line = "probe pingtest failed: " + t;
            LOG.warn("[numen_probe] {}", line, t);
            source.m_81352_(Component.m_237113_(line));
            return 0;
        }
    }

    /**
     * Stage a dismissed body for a post-mortem: keep a weak reference to it, then dismiss it. Run
     * {@code ghoulcheck} a minute later, after a forced full GC, to learn whether the object is
     * still reachable — and, if it is, which static field in the engine is holding it.
     */
    private static int ghoulref(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "companion");
        NumenPlayer her = null;
        for (var player : source.m_81377_().m_6846_().m_11314_()) {
            if (player instanceof NumenPlayer she && she.m_7755_().getString().equals(name)) {
                her = she;
            }
        }
        if (her == null) {
            source.m_81352_(Component.m_237113_("no companion named " + name + " here"));
            return 0;
        }
        GHOUL_REF = new java.lang.ref.WeakReference<>(her);
        GHOUL_NAME = name;
        int removed = Companions.dismissByName(source.m_81377_(), PROBE_OWNER, name);
        String line = "ghoulref staged " + name + " uuid=" + her.m_20148_() + " dismissed=" + removed;
        LOG.warn("[numen_probe] {}", line);
        source.m_288197_(() -> Component.m_237113_(line), false);
        return 1;
    }

    /**
     * Verdict on the staged body. A cleared reference means no leak: the object was garbage and the
     * histogram count was simply this one waiting to be collected. A live reference means something
     * still points at it; then every static field of every numen class is checked for identity, one
     * container level deep, and the holders are printed.
     */
    private static int ghoulcheck(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        StringBuilder sb = new StringBuilder("ghoulcheck:");
        try {
            if (GHOUL_REF == null) {
                sb.append(" nothing staged");
            } else {
                Object held = GHOUL_REF.get();
                if (held == null) {
                    sb.append(" ").append(GHOUL_NAME).append(" = CLEARED (collected; no leak)");
                } else {
                    sb.append(" ").append(GHOUL_NAME).append(" = ALIVE");
                    if (held instanceof net.minecraft.world.entity.Entity e) {
                        sb.append(" removed=").append(e.m_213877_());
                    }
                    sb.append(" holders=").append(holders(held));
                    sb.append(" world=").append(worldHit(source.m_81377_(), held));
                    sb.append(" path=").append(graphPath(source.m_81377_(), held));
                    sb.append(" statics=").append(staticSweep(held, "D:/项目/_audit/classnames.txt"));
                }
            }
        } catch (Throwable t) {
            sb.append(" failed: ").append(t);
        }
        String line = sb.toString().replace('\n', ' ');
        LOG.warn("[numen_probe] {}", line);
        source.m_288197_(() -> Component.m_237113_(line), false);
        return 1;
    }

    private static java.util.List<String> NUMEN_CLASSES;

    /**
     * Is the staged body still in a level's entity index? A dismissed player that is gone from the
     * roster but still resolvable by UUID from {@code ServerLevel.m_8791_} is held by the level, not
     * by any static field — which is exactly the case a static sweep can never see.
     */
    private static String worldHit(net.minecraft.server.MinecraftServer server, Object held) {
        if (!(held instanceof net.minecraft.world.entity.Entity entity)) {
            return "n/a";
        }
        java.util.UUID id = entity.m_20148_();
        java.util.List<String> hits = new java.util.ArrayList<>();
        for (net.minecraft.server.level.ServerLevel level : server.m_129785_()) {
            net.minecraft.world.entity.Entity found = level.m_8791_(id);
            if (found != null) {
                hits.add(level.m_46472_().m_135782_() + (found == held ? "=same" : "=copy"));
            }
        }
        return hits.isEmpty() ? "absent" : hits.toString();
    }

    /**
     * Every top-level class name inside the numen jar, read straight off the jar the modloader
     * loaded, so the static-field sweep cannot miss a package by guessing where it lives.
     */
    private static java.util.List<String> numenClasses() {
        if (NUMEN_CLASSES != null) {
            return NUMEN_CLASSES;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String path = net.minecraftforge.fml.ModList.get().getModFileById("numen").getFile().getFilePath().toString();
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(path)) {
                var it = zip.entries();
                while (it.hasMoreElements()) {
                    var entry = it.nextElement();
                    String n = entry.getName();
                    if (n.endsWith(".class") && n.startsWith("com/dwinovo/numen/") && !n.contains("$")) {
                        out.add(n.substring(0, n.length() - 6).replace('/', '.'));
                    }
                }
            }
        } catch (Throwable t) {
            LOG.warn("[numen_probe] numenClasses failed: {}", t.toString());
        }
        NUMEN_CLASSES = out;
        return out;
    }

    private static String holders(Object body) {
        var classes = numenClasses();
        if (classes.isEmpty()) {
            return "no-classes";
        }
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String binary : classes) {
            Class<?> c;
            try {
                c = Class.forName(binary, false, NumenPlayer.class.getClassLoader());
            } catch (Throwable t) {
                continue;
            }
            java.lang.reflect.Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field f : declared) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                if (f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v == null || v == body) {
                        if (v == body) {
                            found.add(c.getSimpleName() + "." + f.getName());
                        }
                        continue;
                    }
                    if (v instanceof java.util.Collection<?> col) {
                        for (Object o : col) {
                            if (o == body) {
                                found.add(c.getSimpleName() + "." + f.getName() + "#elem");
                                break;
                            }
                        }
                    } else if (v instanceof java.util.Map<?, ?> m) {
                        for (Object o : m.values()) {
                            if (o == body) {
                                found.add(c.getSimpleName() + "." + f.getName() + "#value");
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return found.isEmpty() ? "none-in-numen-statics" : String.join(",", found.subList(0, Math.min(5, found.size())));
    }

    /**
     * Sweep the static fields of every class named in a jcmd class-histogram dump. A static field in
     * a class we do not own is the one retainer a graph walk cannot name: the walk can only reach a
     * static through an object it already holds. The names come from {@code GC.class_histogram}, so
     * the list is what is loaded, not a guess.
     */
    private static final java.util.HashMap<Class<?>, java.lang.reflect.Field[]> FIELD_CACHE =
            new java.util.HashMap<>();

    private static java.lang.reflect.Field[] fieldsOf(Class<?> c) {
        synchronized (FIELD_CACHE) {
            java.lang.reflect.Field[] cached = FIELD_CACHE.get(c);
            if (cached != null) {
                return cached;
            }
            java.lang.reflect.Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                fs = new java.lang.reflect.Field[0];
            }
            java.util.ArrayList<java.lang.reflect.Field> keep = new java.util.ArrayList<>();
            for (java.lang.reflect.Field f : fs) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    keep.add(f);
                } catch (Throwable ignored) {
                }
            }
            java.lang.reflect.Field[] out = keep.toArray(new java.lang.reflect.Field[0]);
            FIELD_CACHE.put(c, out);
            return out;
        }
    }

    /**
     * Path from {@code v} to {@code body}, walking containers and then one level of plain object
     * fields. {@code depth} counts container levels plus field scans; a plain object is scanned once
     * when there is budget left. Returns null when the body is not reachable this way.
     *
     * <p>This is what a direct-equality sweep misses: {@code Map<UUID, Running>} holds the body two
     * hops away (map value -> {@code Running.claimed}/{@code .program}), which is exactly the shape
     * the retainer for a dismissed {@code NumenPlayer} is expected to have.
     */
    private static String refPath(Object v, Object body, int depth) {
        if (v == null || body == null) {
            return null;
        }
        if (v == body) {
            return "";
        }
        if (depth <= 0) {
            return null;
        }
        if (v instanceof java.util.Collection<?> col) {
            int i = 0;
            try {
                for (Object o : col) {
                    if (o == body) {
                        return "#elem[" + i + "]";
                    }
                    String p = refPath(o, body, depth - 1);
                    if (p != null) {
                        return "#elem[" + i + "]" + p;
                    }
                    if (++i > 4096) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
        if (v instanceof java.util.Map<?, ?> m) {
            int i = 0;
            try {
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getValue() == body) {
                        return "#value[" + i + "]";
                    }
                    if (e.getKey() == body) {
                        return "#key[" + i + "]";
                    }
                    String p = refPath(e.getValue(), body, depth - 1);
                    if (p != null) {
                        return "#value[" + i + "]" + p;
                    }
                    p = refPath(e.getKey(), body, depth - 1);
                    if (p != null) {
                        return "#key[" + i + "]" + p;
                    }
                    if (++i > 4096) {
                        break;
                    }
                }
            } catch (Throwable ignored) {
            }
            return null;
        }
        for (java.lang.reflect.Field f : fieldsOf(v.getClass())) {
            try {
                if (f.get(v) == body) {
                    return "." + f.getName();
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static String staticSweep(Object body, String listPath) {
        java.util.List<String> names;
        try {
            names = java.nio.file.Files.readAllLines(java.nio.file.Path.of(listPath));
        } catch (Throwable t) {
            return "unreadable(" + t + ")";
        }
        ClassLoader own = NumenPlayer.class.getClassLoader();
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        java.util.List<String> hits = new java.util.ArrayList<>();
        int resolved = 0;
        for (String name : names) {
            if (name.isBlank()) {
                continue;
            }
            Class<?> c = null;
            try {
                c = Class.forName(name, false, own);
            } catch (Throwable t) {
                try {
                    c = Class.forName(name, false, ctx);
                } catch (Throwable ignored) {
                }
            }
            if (c == null) {
                continue;
            }
            resolved++;
            java.lang.reflect.Field[] fs;
            try {
                fs = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field f : fs) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v == null) {
                        continue;
                    }
                    String p = refPath(v, body, 3);
                    if (p != null) {
                        hits.add(name + "." + f.getName() + p);
                        if (hits.size() >= 8) {
                            break;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
            if (hits.size() >= 8) {
                break;
            }
        }
        return resolved + "/" + names.size() + " resolved; hits="
                + (hits.isEmpty() ? "none" : String.join(",", hits));
    }

    /**
     * Breadth-first walk of the live object graph from the server and every numen static, looking for
     * the shortest reference path to {@code body}. Bounded by depth, node count and wall clock; it
     * runs on the server thread, so the caps matter more than completeness. This is the only way to
     * name a retainer without a heap dump: a static sweep finds engine fields, but a body parked in
     * the chunk map or a tracker is held by plain Minecraft objects.
     */
    private static String graphPath(MinecraftServer server, Object body) {
        long deadline = System.currentTimeMillis() + 20000L;
        java.util.IdentityHashMap<Object, String> seen = new java.util.IdentityHashMap<>();
        java.util.ArrayDeque<Object> queue = new java.util.ArrayDeque<>();
        java.util.ArrayDeque<Integer> depth = new java.util.ArrayDeque<>();
        for (String binary : numenClasses()) {
            java.lang.reflect.Field[] fs;
            try {
                fs = Class.forName(binary, false, NumenPlayer.class.getClassLoader()).getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            for (java.lang.reflect.Field f : fs) {
                if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(null);
                    if (v != null && !seen.containsKey(v)) {
                        seen.put(v, binary.substring(binary.lastIndexOf('.') + 1) + "." + f.getName());
                        queue.add(v);
                        depth.add(0);
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        seen.put(server, "MinecraftServer");
        queue.add(server);
        depth.add(0);
        for (net.minecraft.server.level.ServerLevel lvl : server.m_129785_()) {
            String dim = lvl.m_46472_().m_135782_().toString();
            seen.put(lvl, "level(" + dim + ")");
            queue.add(lvl);
            depth.add(0);
        }
        seen.put(server.m_6846_(), "PlayerList");
        queue.add(server.m_6846_());
        depth.add(0);
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            seen.put(t, "thread(" + t.getName() + ")");
            queue.add(t);
            depth.add(0);
        }
        int nodes = 0;
        while (!queue.isEmpty() && nodes < 400000 && System.currentTimeMillis() < deadline) {
            Object o = queue.poll();
            int d = depth.poll();
            nodes++;
            if (d >= 8) {
                continue;
            }
            String base = seen.get(o);
            if (o.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(o);
                for (int i = 0; i < len; i++) {
                    Object v = java.lang.reflect.Array.get(o, i);
                    if (v == null) {
                        continue;
                    }
                    if (v == body) {
                        return base + "[" + i + "]";
                    }
                    if (!seen.containsKey(v)) {
                        seen.put(v, base + "[" + i + "]");
                        queue.add(v);
                        depth.add(d + 1);
                    }
                }
                continue;
            }
            for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                java.lang.reflect.Field[] fs;
                try {
                    fs = c.getDeclaredFields();
                } catch (Throwable t) {
                    continue;
                }
                for (java.lang.reflect.Field f : fs) {
                    if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) {
                        continue;
                    }
                    Object v;
                    try {
                        f.setAccessible(true);
                        v = f.get(o);
                    } catch (Throwable t) {
                        continue;
                    }
                    if (v == null) {
                        continue;
                    }
                    String label = base + ">" + c.getSimpleName() + "." + f.getName();
                    if (v == body) {
                        return label;
                    }
                    if (seen.containsKey(v)) {
                        continue;
                    }
                    seen.put(v, label);
                    String tn = v.getClass().getName();
                    if (tn.startsWith("java.lang.") || tn.startsWith("java.lang.reflect.")
                            || tn.startsWith("java.io.") || tn.startsWith("it.unimi.")
                            || v instanceof Class<?> || v instanceof ClassLoader
                            || v instanceof String || v instanceof Number || v instanceof Enum<?>) {
                        continue;
                    }
                    queue.add(v);
                    depth.add(d + 1);
                }
            }
        }
        return "no-path(nodes=" + nodes + ")";
    }

    /**
     * Run one Lua program against one companion on the server thread, through the engine's own tester
     * ({@link com.dwinovo.numen.sdk.ApiTester#run}), and write the answer to the log.
     *
     * <p>Why not {@code /numen drive}: drive reports its receipt to the source that typed it, and a
     * console receipt never reaches the RCON client. The tester runs the same program on the same
     * registry and hands the result back synchronously, so the answer is a log line.
     */
    private static int run(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "companion");
        String program = StringArgumentType.getString(ctx, "program");
        NumenPlayer her = null;
        for (var player : source.m_81377_().m_6846_().m_11314_()) {
            if (player instanceof NumenPlayer she && she.m_7755_().getString().equals(name)) {
                her = she;
            }
        }
        if (her == null) {
            source.m_81352_(Component.m_237113_("no companion named " + name + " here"));
            return 0;
        }
        try {
            com.dwinovo.numen.sdk.ApiTester.Run result =
                    com.dwinovo.numen.sdk.ApiTester.run(her, her.m_20148_(), program);
            String line = "[probe] <" + name + "> " + program + " -> ok=" + result.ok()
                    + " | " + result.message()
                    + " | returned=" + result.returned()
                    + " | replies=" + result.replies();
            LOG.warn(line);
            source.m_288197_(() -> Component.m_237113_(line), false);
            return result.ok() ? 1 : 0;
        } catch (Throwable t) {
            LOG.warn("[probe] <{}> {} -> threw {}", name, program, t.toString());
            source.m_81352_(Component.m_237113_("run threw: " + t));
            return 0;
        }
    }

    /**
     * Launch one Lua program on the engine's own asynchronous runner and log its receipt when it ends.
     *
     * <p>Why this exists next to {@code run}: {@link com.dwinovo.numen.sdk.ApiTester#run} refuses any
     * program that does not end at once, which excludes every call that waits for a body job -
     * {@code numen.route.plan}, {@code numen.use.block}, {@code numen.move.go}. Those are exactly the
     * calls whose results have to be checked against a real world, and
     * {@code ServerPrograms.launch} is the path the engine itself uses for them, so the probe rides
     * that instead of guessing.
     */
    private static int async(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "companion");
        String program = StringArgumentType.getString(ctx, "program");
        NumenPlayer her = find(source, name);
        if (her == null) {
            source.m_81352_(Component.m_237113_("no companion named " + name + " here"));
            return 0;
        }
        try {
            com.dwinovo.numen.program.ServerPrograms.launch(her, "probe", program,
                    com.dwinovo.numen.program.CallObserver.NONE,
                    receipt -> LOG.warn("[probe] <{}> async receipt -> {}", name,
                            receipt == null ? "null" : receipt.replace('\n', ' ')));
            LOG.warn("[probe] <{}> async launched: {}", name, program);
            source.m_288197_(() -> Component.m_237113_("probe launched async program on " + name), false);
            return 1;
        } catch (Throwable t) {
            LOG.warn("[probe] <{}> async {} -> threw {}", name, program, t.toString());
            source.m_81352_(Component.m_237113_("async threw: " + t));
            return 0;
        }
    }

    /** Find one online companion by name (the same loop `run` and `where` use). */
    private static NumenPlayer find(CommandSourceStack source, String name) {        for (var player : source.m_81377_().m_6846_().m_11314_()) {
            if (player instanceof NumenPlayer she && she.m_7755_().getString().equals(name)) {
                return she;
            }
        }
        return null;
    }

    /**
     * Open a real container block for her, so a {@code ChestMenu} (a menu she does not always have)
     * can be driven instead of the always-present {@code InventoryMenu}.
     *
     * <p>Why a command instead of her own {@code numen.use.block}: that function is asynchronous - it
     * waits for a body job - and {@link com.dwinovo.numen.sdk.ApiTester#run} refuses a program that
     * does not end at once ("run it in a GameTest"). Opening the block here is the same server-side
     * call the block itself makes, so the menu it produces is the real one.
     */
    private static int open(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "companion");
        String raw = StringArgumentType.getString(ctx, "pos");
        NumenPlayer her = find(source, name);
        if (her == null) {
            source.m_81352_(Component.m_237113_("no companion named " + name + " here"));
            return 0;
        }
        String[] parts = raw.split(",");
        if (parts.length != 3) {
            source.m_81352_(Component.m_237113_("pos must be x,y,z"));
            return 0;
        }
        try {
            BlockPos pos = new BlockPos(Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
            var level = her.m_9236_();
            var be = level.m_7702_(pos);
            if (!(be instanceof net.minecraft.world.Container container)) {
                source.m_81352_(Component.m_237113_(pos + " has no container block entity: " + be));
                return 0;
            }
            her.m_5893_(new net.minecraft.world.SimpleMenuProvider(
                    (id, inv, p) -> container.m_6643_() > 27
                            // m_39237_ = threeRows, m_39246_ = sixRows; those are the only two
                            // container-taking factories, and the wrong one throws
                            // "Container size N is smaller than expected M"
                            ? net.minecraft.world.inventory.ChestMenu.m_39246_(id, inv, container)
                            : net.minecraft.world.inventory.ChestMenu.m_39237_(id, inv, container),
                    Component.m_237113_("probe container")));
            String line = "opened " + pos.m_123341_() + "," + pos.m_123342_() + "," + pos.m_123343_()
                    + " for " + name + " menu=" + her.f_36096_.getClass().getSimpleName()
                    + " slots=" + her.f_36096_.f_38839_.size();
            LOG.warn("[numen_probe] {}", line);
            source.m_288197_(() -> Component.m_237113_(line), false);
            return 1;
        } catch (Throwable t) {
            LOG.warn("[numen_probe] open failed", t);
            source.m_81352_(Component.m_237113_("open failed: " + t));
            return 0;
        }
    }

    private static int where(CommandContext<CommandSourceStack> ctx) {        CommandSourceStack source = ctx.getSource();
        StringBuilder text = new StringBuilder("companions online:");
        int found = 0;
        for (var player : source.m_81377_().m_6846_().m_11314_()) {
            if (player instanceof NumenPlayer she) {
                BlockPos pos = she.m_20183_();
                text.append(' ').append(she.m_7755_().getString())
                        .append('@').append(pos.m_123341_()).append(',').append(pos.m_123342_())
                        .append(',').append(pos.m_123343_());
                found++;
            }
        }
        if (found == 0) {
            text.append(" none");
        }
        String line = text.toString();
        LOG.warn("[numen_probe] {}", line);
        source.m_288197_(() -> Component.m_237113_(line), false);
        return found;
    }
}
