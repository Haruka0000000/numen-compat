package dev.local.numenparcool;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code parcool}：ParCool 的气力、动作、墙。
 *
 * <p>ParCool 这个模组把跑酷做成了「玩家客户端按键触发」——动作是客户端发起的，服务器上只有一份
 * 镜像数据。所以她做不了跑酷动作，这里也不提供「发起跑酷」的接口（那种接口在服务器上只会静默失效，
 * 是最坏的一种接口：看起来成功，实际什么都没发生）。
 *
 * <p>这里能给的，是她做决策真正需要的东西：主人还有多少气力、某个动作现在能不能用（服主允不允许、
 * 他自己关没关、气力够不够）、她面前那堵墙有多高、这个服一共开了哪些动作。过墙看
 * {@code wall}：那是按她自己真有的本事（原版跳跃、垫方块）算的，不依赖 ParCool 的动作。
 *
 * <p>所有 ParCool 调用都压在 {@link Probe} 里逐处 try/catch；ParCool 没装、没起来、数据没同步，
 * 这里都退化成 {@code known=false} 加一句中文，而不是抛栈。
 */
public final class ParCoolApi {

    /** 默认算主人；写 me 才是她自己。 */
    private static final String ME = "me";
    private static final String OWNER = "owner";

    private ParCoolApi() {
    }

    static void install(NumenApi numen) {
        numen.api("parcool",
                "跑酷：主人还剩多少气力、某个跑酷动作现在能不能用（服主禁没禁、他自己关没关、气力够不够）、"
                        + "她面前那堵墙多高、这个服一共开了哪些动作。她不跑酷，要过墙看 wall。",
                ParCoolApi.class);
    }

    // ---------------------------------------------------------------- 回执形状

    /** 问谁的气力。 */
    @Doc("问谁的气力：不写是主人，me 是她自己。")
    public record StateOpt(
            @Doc("写 owner 是主人，写 me 是她自己，也可以写在线的玩家名。不写就是主人。") @Omitted("owner")
            Optional<String> who) {
    }

    /** 一个人当下的气力状态。 */
    @Doc("一个人当下的气力状态。")
    public record State(
            @Doc("问的是谁：`owner` / `me` / 玩家名。") String who,
            @Doc("数字可不可信。false 表示这一条读不到（气力是客户端算的，服务器上只有一个镜像），"
                    + "看 why 和 line，别拿 stamina 下结论。") boolean known,
            @Doc("现在有多少气力。") int stamina,
            @Doc("气力上限。") int max,
            @Doc("气力百分比，0-100。") int percent,
            @Doc("是不是力竭了（力竭时不能疾跑、走路也慢）。") boolean exhausted,
            @Doc("力竭惩罚是不是正在生效。") boolean penalized,
            @Doc("身上有没有「无尽气力」（气力不消耗）。") boolean infinite,
            @Doc("正在做哪个跑酷动作（英文 id，例如 `Vault`）；没在做是空串。") String doing,
            @Doc("这个动作已经做了几刻。") int doingTicks,
            @Doc("照当前回复速度，几刻能回满；已经满了或读不到是 0。") int refillTicks,
            @Doc("为什么读不到（known=false 时才有内容），中文。") String why,
            @Doc("一句话，可以直接念给主人。") String line) {
    }

    /** 问一个动作。 */
    @Doc("问一个跑酷动作现在能不能用。")
    public record CanArgs(
            @Doc("动作名，中文（撑越、滑铲、蹬墙跳）或英文（Vault、Slide、WallJump）都行。")
            String action,
            @Doc("问谁：不写是主人，me 是她自己。") @Omitted("owner") Optional<String> who) {
    }

    /** 一个动作现在能不能用，为什么。 */
    @Doc("一个动作现在能不能用，以及为什么。")
    public record Can(
            @Doc("问的是谁：`owner` / `me` / 玩家名。") String who,
            @Doc("动作的英文 id，例如 `Vault`。") String action,
            @Doc("动作的中文名，例如「撑越」。") String actionZh,
            @Doc("结论可不可信。false 表示有一条读不到（主要见于他客户端的设置还没同步过来）。") boolean known,
            @Doc("能不能用。known=false 时这是个估计值，别当准数。") boolean can,
            @Doc("为什么能用 / 不能用，中文。") String reason,
            @Doc("这个动作要多少气力。") int cost,
            @Doc("cost 是哪来的：`他的设置` / `服务器` / `默认值`。") String costFrom,
            @Doc("气力够不够（无尽气力、零耗气都算够）。") boolean enough,
            @Doc("他现在有多少气力。") int have,
            @Doc("一句话，可以直接用。") String line) {
    }

    /** 她面前的那堵墙。 */
    @Doc("她面前的那堵墙。")
    public record Wall(
            @Doc("她面前有没有能跑/能撑的墙。") boolean present,
            @Doc("墙高（格）。") double height,
            @Doc("在哪个方向：正前方 / 左边 / 右边 / 身后。") String side,
            @Doc("离她多远（格）。") double distance,
            @Doc("主人能不能直接撑越过去（ParCool 的判定：墙高超过她身高 0.44 倍且前方有可踩的台阶）。") boolean vaultable,
            @Doc("结论：`撑越能过` / `撑越过不去` / `没有墙`。") String verdict,
            @Doc("她自己按原版算还差几格才上得去：0 表示一跳就上去了，正数表示得先垫方块。") int blocksNeeded,
            @Doc("主人跑墙最多能连着跑几刻。") int wallRunTicks,
            @Doc("一句话，可以直接说。") String line) {
    }

    /** 一个动作。 */
    @Doc("一个跑酷动作。")
    public record Act(
            @Doc("英文 id，例如 `Vault`。") String action,
            @Doc("中文名，例如「撑越」。") String actionZh,
            @Doc("这个服允许不允许。") boolean allowed,
            @Doc("默认耗气。") int cost) {
    }

    /** 这个服的动作表。 */
    @Doc("这个服开了哪些跑酷动作。")
    public record Acts(
            @Doc("ParCool 一共注册了多少个动作。") int total,
            @Doc("这个服允许多少个。") int allowed,
            @Doc("27 个动作，按 ParCool 的注册顺序。") List<Act> list,
            @Doc("一句话，可以直接用。") String line) {
    }

    // ---------------------------------------------------------------- state

    @Fn("问他（默认是主人）还剩多少气力、力竭没力竭、正在做什么跑酷动作、多久能回满。")
    @Example("parcool.parcool.state()")
    @Example("parcool.parcool.state({who = \"me\"})")
    @Note("ParCool 的气力是玩家客户端自己算的，服务器这边只有一份镜像（最多差几刻），所以数字是"
            + "「刚刚的」，不是实时的。她自己的气力恒为 0：她不是真客户端，根本没在跑 ParCool，"
            + "读她自己只会得到一条 known=false。主人离线不报错，回一条 known=false 的说明；"
            + "写名字的人不在线会直接报错（不是静默当成主人）。")
    @SeeAlso({"parcool.parcool.can", "parcool.parcool.wall"})
    public static State state(ServerCall call, StateOpt args) {
        LivingEntity her = call.her();
        String who = whoOf(args == null ? null : args.who());
        if (isSelf(who)) {
            return new State(ME, false, 0, 0, 0, false, false, false, "", 0, 0,
                    "她不是真客户端，服务器这边没有她的 ParCool 气力",
                    "她自己的气力读不到：ParCool 的气力是玩家客户端自己算的，她不是真客户端，"
                            + "服务器这边只有一个 0。她现在也没在做任何跑酷动作（她本来也做不了，"
                            + "见 parcool.parcool.can）。");
        }
        ServerPlayer target;
        if (isOwner(who)) {
            target = ownerOf(her);
            if (target == null) {
                return new State(OWNER, false, 0, 0, 0, false, false, false, "", 0, 0,
                        "主人不在线", "主人现在没上线，读不到他的气力。");
            }
        } else {
            target = byName(her, who);
        }
        String label = isOwner(who) ? "主人" : target.m_5446_().getString();
        Probe.Stamina st = Probe.stamina(target);
        if (!st.ok()) {
            return new State(idOf(who, target), false, st.value(), st.max(), 0, false, false, false,
                    "", 0, 0, st.why(),
                    label + "的气力现在读不到：" + st.why() + "；过一会儿再问。");
        }
        int percent = (int) Math.round(100.0 * st.value() / st.max());
        int refill = 0;
        if (st.recovery() > 0 && st.value() < st.max()) {
            refill = (int) Math.ceil((st.max() - st.value()) / st.recovery());
        }
        StringBuilder line = new StringBuilder();
        line.append(label).append("现在气力 ").append(st.value()).append("/").append(st.max())
                .append("（").append(percent).append("%），");
        if (st.infinite()) {
            line.append("身上有无尽气力，气力不消耗，随便跑");
        } else if (st.exhausted()) {
            line.append("已经力竭了：不能疾跑、走路也慢，这会儿别催他跑，等它回上来");
        } else {
            line.append("没力竭");
        }
        if (!st.doing().isEmpty()) {
            Names.Entry entry = Names.byId(st.doing());
            String zh = entry == null ? st.doing() : entry.label();
            line.append("，正在").append(zh).append("（已经 ").append(st.doingTicks()).append(" 刻）");
        }
        if (!st.infinite() && !st.exhausted() && refill > 0) {
            line.append("；按每刻回 ").append((int) Math.round(st.recovery())).append(" 算，")
                    .append(seconds(refill)).append(" 秒能回满");
        }
        line.append("。");
        return new State(idOf(who, target), true, st.value(), st.max(), percent, st.exhausted(),
                st.penalized(), st.infinite(), st.doing(), st.doingTicks(), refill, "", line.toString());
    }

    // ---------------------------------------------------------------- can

    @Fn("问一个跑酷动作现在能不能用：服主禁没禁、他自己关没关、气力够不够。")
    @Example("parcool.parcool.can(\"撑越\")")
    @Example("parcool.parcool.can(\"Vault\", {who = \"me\"})")
    @Note("动作名写中文或英文都行，不认识会报错并告诉你有哪些。判定顺序：她自己（永远是 false，"
            + "她不跑酷）→ 服主禁 → 他自己在设置里关了 → 气力不够 → 能用。他客户端的设置要等他"
            + "进过一次游戏才同步到服务器；没同步时 known=false，cost 退回默认值、costFrom 写「默认值」，"
            + "不要拿这一条当死结论。")
    @SeeAlso({"parcool.parcool.state", "parcool.parcool.acts"})
    public static Can can(ServerCall call, CanArgs args) {
        LivingEntity her = call.her();
        String raw = args == null || args.action() == null ? "" : args.action().trim();
        if (raw.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说问哪个动作。",
                    "写 parcool.parcool.can(\"撑越\") 这样（动作名在前，{who=…} 在后可选）；" + Names.examples() + "。", null);
        }
        Names.Entry entry = Names.parse(raw);
        if (entry == null) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "ParCool 没有叫「" + raw + "」的动作。",
                    "动作名写中文（撑越、滑铲、蹬墙跳）或英文（Vault、Slide、WallJump）；"
                            + "全部动作见 parcool.parcool.acts()。", null);
        }
        String who = whoOf(args.who());
        if (isSelf(who)) {
            return new Can(ME, entry.id(), entry.label(), false, false,
                    "ParCool 的动作全靠玩家客户端按键触发，服务器上发起不了；她的气力也不会涨（服务器这边恒 0）。",
                    entry.cost(), "默认值", false, 0,
                    "她不会跑酷，别等她翻墙——要过墙看 parcool.parcool.wall()，那是按她真有的本事算的。");
        }
        ServerPlayer target;
        if (isOwner(who)) {
            target = ownerOf(her);
            if (target == null) {
                return new Can(OWNER, entry.id(), entry.label(), false, false,
                        "主人不在线，读不到他的跑酷设置和气力。", entry.cost(), "默认值", false, 0,
                        "主人现在没上线，问不了他能不能用「" + entry.label() + "」。");
            }
        } else {
            target = byName(her, who);
        }
        String label = isOwner(who) ? "主人" : target.m_5446_().getString();
        try {
            Probe.Perm pm = Probe.permission(target, entry.id());
            if (!pm.resolved()) {
                return new Can(idOf(who, target), entry.id(), entry.label(), false, false,
                        "这一版 ParCool 的动作表里没找到「" + entry.id() + "」，插件里的名字表可能和它不一致。",
                        entry.cost(), "默认值", false, 0,
                        "这个服的 ParCool 和插件里的动作表对不上，先按 parcool.parcool.acts() 看它到底有哪些动作。");
            }
            Probe.Stamina st = Probe.stamina(target);

            int cost;
            String costFrom;
            if (pm.clientSynced() && pm.clientCost() >= 0) {
                cost = pm.clientCost();
                costFrom = "他的设置";
            } else if (pm.serverSynced() && pm.serverCost() >= 0) {
                cost = pm.serverCost();
                costFrom = "服务器";
            } else {
                cost = entry.cost();
                costFrom = "默认值";
            }
            boolean infinite = st.ok() && st.infinite();
            boolean enough = infinite || (st.ok() && st.value() >= cost);
            int have = st.ok() ? st.value() : 0;
            String zh = entry.label();

            if (pm.serverSynced() && !pm.permitted()) {
                String key = "permit_" + entry.id().toLowerCase(Locale.ROOT);
                return new Can(idOf(who, target), entry.id(), zh, true, false,
                        "服主把这个动作关了（存档里的 serverconfig/parcool-server.toml，" + key + " = false）。",
                        cost, costFrom, enough, have,
                        "这个服不许「" + zh + "」。");
            }
            if (pm.clientSynced() && !pm.possible()) {
                return new Can(idOf(who, target), entry.id(), zh, true, false,
                        "他自己在跑酷设置里把「" + zh + "」关掉了。",
                        cost, costFrom, enough, have,
                        label + "现在不能用「" + zh + "」：他自己在设置里关掉了。");
            }
            if (st.ok() && !infinite && st.value() < cost) {
                return new Can(idOf(who, target), entry.id(), zh, true, false,
                        "气力不够：要 " + cost + "，他只剩 " + st.value() + "。",
                        cost, costFrom, false, have,
                        label + "现在不能用「" + zh + "」：气力不够，要 " + cost + "，他只有 "
                                + st.value() + "。");
            }
            if (!pm.clientSynced()) {
                String reason = "他的跑酷设置还没同步到服务器，这一条只能按服主设置估，可能不准。";
                String line;
                if (!st.ok()) {
                    line = label + "现在大概能用「" + zh + "」，但气力和他的客户端设置都还没读到，别当准数。";
                } else if (enough) {
                    line = label + "大概能用「" + zh + "」：气力够（要 " + cost + "，他有 " + have
                            + "），但他自己的跑酷设置还没同步，可能另外关了。";
                } else {
                    line = label + "大概不能用「" + zh + "」：气力不够（要 " + cost + "，他只有 " + have + "）。";
                }
                return new Can(idOf(who, target), entry.id(), zh, false, enough, reason,
                        cost, costFrom, enough, have, line);
            }
            String tail = pm.serverSynced() ? "；这个服也没禁" : "";
            String line = infinite
                    ? label + "现在能用「" + zh + "」：他有无尽气力，不耗" + tail + "。"
                    : label + "现在能用「" + zh + "」：要 " + cost + " 气力，他有 " + have + tail + "。";
            return new Can(idOf(who, target), entry.id(), zh, true, true,
                    "服主允许、他自己也开着、气力够（要 " + cost + "，他有 " + have + "）。",
                    cost, costFrom, true, have, line);
        } catch (ApiError e) {
            throw e;
        } catch (Throwable t) {
            return new Can(idOf(who, target), entry.id(), entry.label(), false, false,
                    "读他的跑酷数据时出错了：" + t.getClass().getSimpleName() + "（ParCool 可能没装好）。",
                    entry.cost(), "默认值", false, 0,
                    "这条现在读不到，先别按它下结论；过一会儿再问，或者看 parcool.parcool.acts()。");
        }
    }

    // ---------------------------------------------------------------- wall

    @Fn("看她面前那堵墙：多高、在哪个方向、多远、主人能不能撑越过去、她自己按原版算还差几格。")
    @Example("parcool.parcool.wall()")
    @Note("按她一身的真实位置和朝向算，不依赖 ParCool 的动作，也不判朝向能不能跑。没有墙时"
            + "present=false（不报错）。她自己能不能过，用的是原版跳跃高度 1.25 格加上她自己的身高；"
            + "「差几格」说的是还得垫几个方块。读不到墙体探测时会说读不到，而不是说「没有墙」。")
    @SeeAlso({"parcool.parcool.state", "parcool.parcool.can"})
    public static Wall wall(ServerCall call) {
        LivingEntity her = call.her();
        int ticks;
        try {
            ServerPlayer owner = ownerOf(her);
            ticks = Probe.wallRunTicks(owner);
        } catch (Throwable ignored) {
            ticks = 40;
        }
        Probe.Wall wall;
        try {
            wall = Probe.wall(her);
        } catch (Throwable ignored) {
            wall = null;
        }
        if (wall == null) {
            return new Wall(false, 0, "", 0, false, "读不到", 0, ticks,
                    "读不到她面前的墙：ParCool 的墙体探测在服务端没跑起来（或者 ParCool 没装）。"
                            + "要过墙就按原版算——两格以内她自己跳得上去。");
        }
        if (!wall.present()) {
            return new Wall(false, 0, "", 0, false, "没有墙", 0, ticks,
                    "她" + "正前方三格内没有墙，直接走就行。");
        }
        double height = round1(wall.height());
        double distance = round1(wall.distance());
        int blocksNeeded = wall.height() <= 1.25D ? 0
                : (int) Math.max(0, Math.ceil(wall.height() - 1.25D));
        String verdict = wall.vaultable() ? "撑越能过" : "撑越过不去";
        StringBuilder line = new StringBuilder();
        line.append("她").append(wall.side()).append(" ").append(num(distance)).append(" 格处有堵 ")
                .append(num(height)).append(" 格高的墙：");
        if (wall.vaultable()) {
            line.append("主人一撑就过去了");
        } else {
            line.append("主人这个高度撑不过去");
        }
        if (blocksNeeded <= 0) {
            line.append("；她自己按原版算一跳就上去了。");
        } else {
            line.append("；她自己按原版算差 ").append(blocksNeeded).append(" 格，得先垫个方块。");
        }
        return new Wall(true, height, wall.side(), distance, wall.vaultable(), verdict,
                blocksNeeded, ticks, line.toString());
    }

    // ---------------------------------------------------------------- acts

    @Fn("这个服一共开了哪些跑酷动作、关掉了哪几个。")
    @Example("parcool.parcool.acts()")
    @Note("27 个动作按 ParCool 的注册顺序全列出来，每个带中文名、这个服允不允许、默认耗气。"
            + "限制系统没开时全算允许，line 里会说明。他客户端里自己关掉的动作这里看不到"
            + "（那要一个个问 parcool.parcool.can）。")
    @SeeAlso({"parcool.parcool.can", "parcool.parcool.state"})
    public static Acts acts(ServerCall call) {
        LivingEntity her = call.her();
        Probe.Acts snap;
        try {
            snap = Probe.acts(ownerOf(her));
        } catch (Throwable ignored) {
            snap = null;
        }
        if (snap == null) {
            List<Act> fallback = new ArrayList<>();
            for (Names.Entry entry : Names.all()) {
                fallback.add(new Act(entry.id(), entry.label(), true, entry.cost()));
            }
            return new Acts(fallback.size(), fallback.size(), fallback,
                    "读不到 ParCool 的动作表（ParCool 没装，或者这一版改了注册方式）；"
                            + "下面列的是插件自己记的 " + fallback.size() + " 个动作，按「全都允许」算。");
        }
        List<Act> list = new ArrayList<>();
        int allowed = 0;
        List<String> denied = new ArrayList<>();
        for (int i = 0; i < snap.ids().size(); i++) {
            Names.Entry entry = Names.byId(snap.ids().get(i));
            String id = entry == null ? (snap.ids().get(i) == null ? "?" + i : snap.ids().get(i)) : entry.id();
            String zh = entry == null ? id : entry.label();
            boolean ok = i < snap.allowed().length && snap.allowed()[i];
            int cost = i < snap.costs().length ? snap.costs()[i] : 0;
            list.add(new Act(id, zh, ok, cost));
            if (ok) {
                allowed++;
            } else if (entry != null && entry.zh() != null && !entry.zh().isEmpty()) {
                denied.add(zh);
            }
        }
        StringBuilder line = new StringBuilder();
        if (!snap.known()) {
            line.append("这个服没启用跑酷限制，").append(snap.ids().size())
                    .append(" 个动作全都允许（他自己客户端里可能另外关了几个）。");
        } else if (denied.isEmpty()) {
            line.append("这个服开了 ").append(snap.ids().size()).append(" 个跑酷动作，一个都没关。");
        } else {
            line.append("这个服开了 ").append(snap.ids().size()).append(" 个跑酷动作里的 ").append(allowed)
                    .append(" 个；关掉的是：").append(String.join("、", denied)).append("。");
        }
        return new Acts(snap.ids().size(), allowed, list, line.toString());
    }

    // ---------------------------------------------------------------- 小工具

    /** 没写就是主人；空串当没写。 */
    private static String whoOf(Optional<String> who) {
        if (who == null || who.isEmpty()) {
            return "";
        }
        String raw = who.get() == null ? "" : who.get().trim();
        return raw;
    }

    private static boolean isSelf(String who) {
        if (who == null || who.isEmpty()) {
            return false;
        }
        String w = who.toLowerCase(Locale.ROOT);
        return w.equals("me") || w.equals("self") || who.equals("我") || who.equals("自己")
                || who.equals("她") || who.equals("她自己");
    }

    private static boolean isOwner(String who) {
        if (who == null || who.isEmpty()) {
            return true;
        }
        String w = who.toLowerCase(Locale.ROOT);
        return w.equals("owner") || who.equals("主人");
    }

    /** 回执里的 who 字段。 */
    private static String idOf(String who, ServerPlayer target) {
        if (isOwner(who)) {
            return OWNER;
        }
        try {
            return target.m_5446_().getString();
        } catch (Throwable ignored) {
            return who;
        }
    }

    /** 她的真主人。没上线就是 null，绝不退回「第一个在线玩家」。 */
    private static ServerPlayer ownerOf(LivingEntity her) {
        try {
            if (her instanceof com.dwinovo.numen.entity.NumenPlayer numen) {
                return numen.resolveOwnerPlayer();
            }
        } catch (Throwable ignored) {
            // 没主人 / 主人离线：当作没人。
        }
        return null;
    }

    /** 按名字找在线玩家；找不到就报错，并给出在线名单。 */
    private static ServerPlayer byName(LivingEntity her, String who) {
        ServerPlayer found = null;
        try {
            found = her.m_9236_().m_7654_().m_6846_().m_11255_(who);
        } catch (Throwable ignored) {
            found = null;
        }
        if (found == null) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "没有叫「" + who + "」的玩家在线。",
                    "写 owner 是主人，写 me 是她自己；现在在线的是：" + online(her), null);
        }
        return found;
    }

    /** 在线玩家的名字，逗号分开。 */
    private static String online(LivingEntity her) {
        try {
            List<String> names = new ArrayList<>();
            for (ServerPlayer p : her.m_9236_().m_7654_().m_6846_().m_11314_()) {
                names.add(p.m_5446_().getString());
            }
            return names.isEmpty() ? "（一个都没有）" : String.join("、", names);
        } catch (Throwable ignored) {
            return "（读不到）";
        }
    }

    /** 刻数换成「几秒」，整数就不带小数点。 */
    private static String seconds(int ticks) {
        double secs = ticks / 20.0;
        return num(round1(secs));
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    /** 1.0 打成「1」，1.25 打成「1.25」，别在中文句子里冒出 .0。 */
    private static String num(double v) {
        if (Math.abs(v - Math.rint(v)) < 1.0E-6) {
            return Long.toString((long) Math.rint(v));
        }
        String s = String.format(Locale.ROOT, "%.2f", v);
        while (s.endsWith("0")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.endsWith(".")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }
}
