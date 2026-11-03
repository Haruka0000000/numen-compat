package dev.local.numenprobe;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.ServerCall;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@code probe} group: one function that writes a line to the server log.
 *
 * <p>Why it exists: {@code /numen drive} reports a program's receipt to whoever typed it, and the
 * console's receipt never reaches the RCON client that sent the command. So a program's return value
 * is invisible from here. This function gives the program a way to put its own answer in a place the
 * log tail can read.
 *
 * <p>Read-only in the sense that matters: it writes nothing to the world. It is a debugging aid and
 * is deleted together with the rest of this jar.
 */
public final class ProbeApi {

    private static final Logger LOG = LoggerFactory.getLogger("numen_probe");

    private ProbeApi() {
    }

    static void install(NumenApi numen) {
        numen.api("probe", "调试用：把一行字写进服务端日志，供 RCON 侧读取。", ProbeApi.class);
    }

    /** What to log. */
    @Doc("要写进日志的那一行字。")
    public record Line(String text) {
    }

    /** What came back. */
    @Doc("回执：原样回显写进去的那一行。")
    public record Said(String line) {
    }

    @Fn("把一行字写进服务端日志。")
    @Example("numen.probe.log(\"count=\" .. tostring(effects.effects.self().count))")
    @Note("只写日志，不动世界。给无人在线时远程核对读数用。")
    public static Said log(ServerCall call, Line args) {
        String text = args.text() == null ? "" : args.text();
        LOG.warn("[probe] {}", text);
        return new Said(text);
    }
}
