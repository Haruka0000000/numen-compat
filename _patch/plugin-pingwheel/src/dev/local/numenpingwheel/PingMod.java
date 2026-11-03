package dev.local.numenpingwheel;

import com.dwinovo.numen.api.NumenPlugins;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ping Wheel 的 numen 插件。
 *
 * <p>旧版的做法是「/botping 命令 + 一个后台线程」，那个切面是错的：numen 想要的是插件注册 Lua 函数、
 * 模型直接调。所以这个 mod 注册 {@code pingwheel.ping}（{@code latest} + {@code list}）和一句身体状态，
 * 负责记标记的 Mixin 仍然在同一个 jar 里。
 *
 * <p>没有 numen 或没有 Ping Wheel 时它是只读的死重：Mixin 是 {@code require = 0}，
 * 模组改名或卸载只丢钩子，不会崩服。
 */
public final class PingMod {

    public static final String MODID = "numen_pingwheel";
    public static final String BUILD = "p3-fix-all";

    private static final Logger LOG = LoggerFactory.getLogger(MODID);

    public PingMod() {
        boolean hasNumen = ModList.get().isLoaded("numen");
        boolean hasPingWheel = ModList.get().isLoaded("pingwheel");
        LOG.warn("[numen_pingwheel] constructed build={} numen={} pingwheel={}", BUILD, hasNumen, hasPingWheel);
        if (!hasNumen) {
            return;
        }
        try {
            NumenPlugins.register("pingwheel", numen -> {
                PingApi.install(numen);
                // 最新的标记跟着身体状态一起进她的上下文；否则它只是一条会被翻过去的聊天记录。
                try {
                    numen.contributeBodyState(PingApi::stateLine);
                } catch (Throwable ignored) {
                    // 拿不到状态片段也没关系：pingwheel.ping.list 仍然读得到
                }
            });
            LOG.warn("[numen_pingwheel] registered pingwheel.ping.* + a body-state line");
        } catch (Throwable t) {
            LOG.error("[numen_pingwheel] registration failed", t);
        }
    }
}
