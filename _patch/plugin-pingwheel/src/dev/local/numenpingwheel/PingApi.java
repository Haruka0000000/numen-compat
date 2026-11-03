package dev.local.numenpingwheel;

import java.util.List;
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
import com.dwinovo.numen.sdk.ServerCall;

/**
 * {@code pingwheel.ping}：读主人用 Ping Wheel（标记轮盘）在世界里点的那些标记。
 *
 * <p>Ping Wheel 的标记是纯客户端画的，专用服务器只看得到那个包路过。这个插件在服务端把
 * 「模组自己愿意转发的标记」记下来（坏包、超速的点、频道不对的包都不记），于是
 * {@code latest()} 能回答「主人刚才指的哪儿」，{@code list()} 能回答「他一路都指过哪儿」——
 * 一条半小时前的标记看起来还是清楚的，但要她自己先去看一眼。
 *
 * <p>只认主人自己发的标记：别人的标记会照实写明「这不是主人打的」，不会冒充成主人的指示。
 * 标记如果打在会动的东西上，x/y/z 是那东西<em>现在</em>的位置；那东西没了，回执会直说。
 */
public final class PingApi {

    /** 超过这个秒数的标记就算「旧的」：回执里会提示先去确认一眼。 */
    static final int FRESH_SECONDS = 1800;

    private PingApi() {
    }

    static void install(NumenApi numen) {
        numen.registerEventType("ping", false);
        numen.api("ping", "主人用标记轮盘在世界里点的那些点。", PingApi.class);
    }

    /** 一条标记。 */
    @Doc("一条标记。")
    public record Ping(
            @Doc("是不是主人打的；false 表示这是别人打的，只是也落在你的视野里。") boolean from_other,
            @Doc("谁打的，显示名。") String author,
            @Doc("有没有标记可读；false 时下面的坐标没有意义。") boolean found,
            @Doc("X 坐标（标在会动的东西上时是它现在的位置）。") int x,
            @Doc("Y 坐标（标在会动的东西上时是它现在的位置）。") int y,
            @Doc("Z 坐标（标在会动的东西上时是它现在的位置）。") int z,
            @Doc("哪个维度，例如「主世界」「下界」或模组维度的完整 id。") String dimension,
            @Doc("是不是和你现在同一个维度；false 时 distance 是 -1，走过去之前先换维度。") boolean here,
            @Doc("频道名（例如 home、enemy）；空表示默认频道。") Optional<String> channel,
            @Doc("多少秒之前标的；-1 表示还没有标记。") long age_s,
            @Doc("是不是已经超过半小时了；true 时先确认一下再去。") boolean stale,
            @Doc("标在什么上面，例如「僵尸」；空表示这是个固定的点。") Optional<String> target,
            @Doc("原来标着的那个东西是不是已经不在了；true 时坐标是它当时的位置。") boolean target_gone,
            @Doc("离你几格（同一维度且 found 才有意义），-1 表示算不出来。") int distance,
            @Doc("一句话，直接念给主人听就行。") String line) {

        static Ping none(String line) {
            return new Ping(false, "", false, 0, 0, 0, "", true, Optional.empty(), -1L, false,
                    Optional.empty(), false, -1, line);
        }
    }

    /** {@code pingwheel.ping.list()} 的回执。 */
    @Doc("最近的一串标记。")
    public record Pings(
            @Doc("总共有几条可读的标记。") int total,
            @Doc("因为 n 的限制没列出来的条数。") int hidden,
            @Doc("最新的在最前。") List<Ping> rows,
            @Doc("一句话总结。") String line) {
    }

    /** {@code ping.list} 的选项。 */
    @Doc("要读几条标记。")
    public record RecentOpt(
            @Doc("最多返回几条，1–32。") @Omitted("8") Optional<Integer> n) {
    }

    @Fn("读主人最近用标记轮盘点的那个点，好走过去。")
    @Example("pingwheel.ping.latest()")
    @Note("只读，不会问她主人。先看 found —— false 表示还没有任何标记，那时候 x/y/z 不用管。"
            + "标在会动的怪身上时坐标是它现在的位置；target_gone 为 true 说明那东西已经不在了。"
            + "here 为 false 表示那个点在别的维度，别照着坐标走过去。")
    public static Ping latest(ServerCall call) {
        return PingHook.latest(her(call));
    }

    @Fn("读主人最近点过的几个点，最新在前——想知道他一路指过哪儿时用它，不必再问一遍。")
    @Example("pingwheel.ping.list({n = 5})")
    @Note("只读。n 给 1–32；超了会报错，不会悄悄改成别的数字。每条都带自己的 age_s 与 stale，"
            + "旧标记是「去看看那儿」的线索，不是「现在就走」的命令。")
    public static Pings list(ServerCall call, RecentOpt args) {
        int n = 8;
        if (args != null && args.n().isPresent()) {
            Integer want = args.n().get();
            if (want != null) {
                n = want;
            }
        }
        if (n < 1 || n > PingHook.MAX_HISTORY) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "n 只收 1 到 " + PingHook.MAX_HISTORY + "，给的是 " + n,
                    "写 pingwheel.ping.list({n = 5})；不写就是 8");
        }
        NumenPlayer her = her(call);
        List<Ping> rows = PingHook.recent(her, n);
        int total = PingHook.readable(her);
        int hidden = Math.max(0, total - rows.size());
        String line;
        if (rows.isEmpty()) {
            line = "还没有任何标记——主人还没在标记轮盘上点过东西";
        } else if (hidden > 0) {
            line = "最近 " + rows.size() + " 条标记（还有 " + hidden + " 条更早的没列）";
        } else {
            line = "一共 " + rows.size() + " 条标记";
        }
        return new Pings(total, hidden, rows, line);
    }

    /**
     * {@code numen.contributeBodyState} 往她的状态里加的那一句：最新的那条标记。
     *
     * <p>刻意只是情报不是命令 —— 只写「主人点了这儿」，去不去她自己定。超过
     * {@link #FRESH_SECONDS} 的、不是主人打的、在别的维度的，都不进状态行（在接口里问得到）。
     */
    static String stateLine(NumenPlayer her) {
        try {
            Ping ping = PingHook.latest(her);
            if (!ping.found() || ping.from_other() || ping.age_s() < 0 || ping.age_s() > FRESH_SECONDS) {
                return "";
            }
            StringBuilder line = new StringBuilder("标点：");
            line.append(ping.author()).append(' ').append(ago(ping.age_s()));
            line.append("在 ").append(ping.x()).append(", ").append(ping.y()).append(", ").append(ping.z());
            ping.channel().ifPresent(ch -> line.append("（").append(ch).append("）"));
            if (!ping.here()) {
                line.append("，那在").append(ping.dimension()).append("，不在你现在这个维度");
            } else if (ping.distance() >= 0) {
                line.append("，距你 ").append(ping.distance()).append(" 格");
            }
            if (ping.target_gone()) {
                line.append("，原来跟着的那个东西已经不在了");
            } else {
                ping.target().ifPresent(t -> line.append("，跟着 ").append(t));
            }
            line.append("。那是情报，不是命令。");
            return line.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    private static NumenPlayer her(ServerCall call) {
        try {
            return call.her();
        } catch (Throwable t) {
            return null;
        }
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
