package dev.local.numeneffects;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;

/**
 * {@code effects}：她身上现在有哪些药水效果（buff / debuff）、各剩多久、哪几个是坏的。
 *
 * <p>只读她自己。中毒、失明、着火这类会改变其它判断的状态，会额外写进身体状态那一行。
 * 中文名走共享表（{@link Zh}），模组加的效果只要有 zh_cn 语言文件就能翻出来。
 */
public final class EffectsApi {

    /** 身体状态那一行算出来之后复用多久。 */
    private static final long STATE_CACHE_MS = 3_000L;

    /** 最多摊开几条，其余只计数。 */
    private static final int MAX_SHOW = 16;

    /** 总结行里最多念几条坏效果；坏的一般不会太多。 */
    private static final int MAX_HARMFUL_IN_LINE = 6;

    private static final String EFFECT_FIRE_RESISTANCE = "minecraft:fire_resistance";
    private static final String EFFECT_BLINDNESS = "minecraft:blindness";
    private static final String EFFECT_DARKNESS = "minecraft:darkness";
    private static final String EFFECT_POISON = "minecraft:poison";
    private static final String EFFECT_WITHER = "minecraft:wither";
    private static final String EFFECT_HUNGER = "minecraft:hunger";

    /** 身体状态的缓存要按身体分开：两个同伴在同一帧里读，不能串。 */
    private static final Map<UUID, String> STATE_TEXT = new HashMap<>();
    private static final Map<UUID, Long> STATE_STAMP = new HashMap<>();

    private EffectsApi() {
    }

    static void install(NumenApi numen) {
        numen.api("effects",
                "状态效果：她身上现在有哪些药水效果、各剩多久、哪几个是坏的，"
                        + "也能单独问「有没有某个效果」「某个效果还剩多久」。"
                        + "中毒、失明、着火这类会改变其它判断的状态会自动提醒。",
                EffectsApi.class);
        numen.contributeBodyState(EffectsApi::stateLine);
    }

    /** 启动日志那一行。 */
    static String health() {
        return "原版 + 模组状态效果，中文名走共享表";
    }

    // ---------------------------------------------------------------- options

    /** 要看哪个效果。 */
    @Doc("要查的效果：写注册 id，形如 'minecraft:poison'；模组加的一样写法。")
    public record IdArg(
            @Doc("效果 id，例如 'minecraft:fire_resistance'、'legendarysurvivaloverhaul:thirst'。") String id) {
    }

    // ---------------------------------------------------------------- shapes

    /** 一条状态效果。 */
    @Doc("一条状态效果：名字、几级、还剩多久、是不是坏的。")
    public record Effect(
            @Doc("效果的注册 id，形如 'minecraft:regeneration'；模组加的也是这个格式。") String id,
            @Doc("中文名（原版和带中文语言文件的模组都有；查不到就用它的英文名，再查不到用 id）。") String name,
            @Doc("等级，1 就是普通的一级；数字越大越强。") int level,
            @Doc("还剩多少秒；-1 表示永久（命令给的、不会自己消失）。") int duration_s,
            @Doc("true 是坏效果（中毒、凋零、失明、饥饿、挖掘疲劳这些），false 是好的。") boolean harmful,
            @Doc("给人看的一行，形如 '生命恢复 II 级，还有 8 秒'。") String line) {
    }

    /** 她身上所有的状态效果。 */
    @Doc("她身上现在所有的状态效果：总数、好坏各几个、细表和一行总结。")
    public record Effects(
            @Doc("看的是谁，形如 '我（名字）'。") String who,
            @Doc("一共几个效果。") int count,
            @Doc("其中坏效果几个。") int harmful,
            @Doc("其中好效果几个。") int beneficial,
            @Doc("现在是不是在燃烧（这不在效果表里，但和抗火有关，所以一起报）。") boolean burning,
            @Doc("明细，坏效果排在前面，然后按剩得少的排前面；最多 16 条。") List<Effect> list,
            @Doc("一行总结，形如 '你身上有 2 个状态效果（其中 1 个是坏的）：生命恢复 II 还有 8 秒、饥饿 永久'。") String line) {
    }

    /** 某一个效果在不在。 */
    @Doc("问她身上有没有某个效果：在不在、几级、还剩多久。")
    public record Has(
            @Doc("问的是哪个效果（注册 id）。") String id,
            @Doc("中文名；她身上没有这个效果时也会给名字。") String name,
            @Doc("true 是她现在身上有这个效果。") boolean found,
            @Doc("等级，1 是一级；found 为 false 时是 0。") int level,
            @Doc("还剩多少秒；-1 永久；found 为 false 时是 -2（这个数字只表示「没有」）。") int duration_s,
            @Doc("是不是坏效果。") boolean harmful,
            @Doc("给人看的一行，形如 '你身上有中毒 II 级，还有 8 秒'。") String line) {
    }

    /** 某一个效果还剩多久。 */
    @Doc("问她身上某个效果还剩多久：秒数、是不是永久、有没有。")
    public record Rest(
            @Doc("问的是哪个效果（注册 id）。") String id,
            @Doc("中文名。") String name,
            @Doc("true 是她现在身上有这个效果。") boolean found,
            @Doc("还剩多少秒；永久时是 -1；没有这个效果时是 -2。") int seconds,
            @Doc("true 表示不会自己消失（命令给的）。") boolean permanent,
            @Doc("给人看的一行，形如 '中毒还有 8 秒'。") String line) {
    }

    // ---------------------------------------------------------------- functions

    @Fn("看你自己身上现在有哪些状态效果、各剩多久。中毒、失明、着火这类会改变判断的东西先浮出来。")
    @Example("effects.effects.self()")
    @Note("只读，只看她自己。读的是服务端同步过来的效果表，模组加的效果也在里面。"
            + "duration_s 是 -1 就是永久。燃烧不在效果表里，单独用 burning 报。"
            + "想知道某个具体效果在不在、还剩多久，用 effects.effects.has / effects.effects.remaining，"
            + "不用自己扫整张表。")
    public static Effects self(ServerCall call) {
        return read(call.her());
    }

    @Fn("问她身上有没有某个效果、几级、还剩多久。只有一个 id 参数。")
    @Example("effects.effects.has(\"minecraft:fire_resistance\")")
    @Note("只读，只看她自己。有没有都会正常返回（found 说有没有），id 写错才报错。"
            + "只要剩余时间就用 effects.effects.remaining。")
    public static Has has(ServerCall call, IdArg args) {
        MobEffectInstance instance = instanceOf(call.her(), idOf(args));
        String id = idOf(args);
        String name = nameOf(id);
        if (instance == null) {
            return new Has(id, name, false, 0, -2, false, "你身上没有" + name);
        }
        int level = instance.m_19564_() + 1;
        int seconds = secondsOf(instance);
        boolean harmful = harmfulOf(instance);
        String line = "你身上有" + name + (level > 1 ? " " + roman(level) + " 级" : "")
                + "，" + timeText(seconds);
        return new Has(id, name, true, level, seconds, harmful, line);
    }

    @Fn("问她身上某个效果还剩多久（秒）。")
    @Example("effects.effects.remaining(\"minecraft:poison\")")
    @Note("只读，只看她自己。已经没有时 found = false、seconds = -2；永久时 seconds = -1。"
            + "和 has 的区别只是这份回执更短，链里判断「还能撑多久」用它更省。")
    public static Rest remaining(ServerCall call, IdArg args) {
        MobEffectInstance instance = instanceOf(call.her(), idOf(args));
        String id = idOf(args);
        String name = nameOf(id);
        if (instance == null) {
            return new Rest(id, name, false, -2, false, "你身上没有" + name);
        }
        int seconds = secondsOf(instance);
        String line = seconds < 0
                ? name + "不会自己消失"
                : name + "还有 " + shortTime(seconds);
        return new Rest(id, name, true, seconds, seconds < 0, line);
    }

    // ---------------------------------------------------------------- readings

    private static Effects read(LivingEntity subject) {
        Collection<MobEffectInstance> active = subject.m_21220_();
        List<Effect> rows = new ArrayList<>();
        int harmful = 0;
        boolean fireResistance = false;

        if (active != null) {
            for (MobEffectInstance instance : active) {
                if (instance == null) {
                    continue;
                }
                Effect row = describe(instance);
                if (row == null) {
                    continue;
                }
                rows.add(row);
                if (row.harmful()) {
                    harmful++;
                }
                if (EFFECT_FIRE_RESISTANCE.equals(row.id())) {
                    fireResistance = true;
                }
            }
        }
        int beneficial = rows.size() - harmful;

        rows.sort(Comparator
                .comparing((Effect e) -> e.harmful() ? 0 : 1)
                .thenComparingInt(e -> e.duration_s() < 0 ? Integer.MAX_VALUE : e.duration_s())
                .thenComparing(Effect::id));

        boolean burning = subject.m_6060_();
        int count = rows.size();
        int shown = Math.min(count, MAX_SHOW);
        List<Effect> kept = shown == count ? rows : new ArrayList<>(rows.subList(0, shown));

        StringBuilder sb = new StringBuilder();
        String who = "你";
        if (count == 0) {
            sb.append(who).append("身上没有任何状态效果");
        } else {
            sb.append(who).append("身上有 ").append(count).append(" 个状态效果");
            if (harmful > 0) {
                sb.append("（其中 ").append(harmful).append(" 个是坏的）");
            }
            sb.append("：");
            // 坏的先全部念出来（最多 6 条），再补好的，免得最该看的被「等」字吞掉。
            int said = 0;
            int harmSaid = 0;
            for (Effect row : rows) {
                if (!row.harmful() || harmSaid >= MAX_HARMFUL_IN_LINE) {
                    continue;
                }
                if (said > 0) {
                    sb.append("、");
                }
                sb.append(brief(row));
                said++;
                harmSaid++;
            }
            for (Effect row : rows) {
                if (row.harmful() || said >= MAX_HARMFUL_IN_LINE + 2) {
                    continue;
                }
                if (said > 0) {
                    sb.append("、");
                }
                sb.append(brief(row));
                said++;
            }
            if (said < Math.min(shown, kept.size())) {
                sb.append(" 等");
            }
            if (count > shown) {
                sb.append("；还有 ").append(count - shown).append(" 个没列");
            }
        }
        if (burning) {
            sb.append(fireResistance
                    ? "；你现在在燃烧，但有抗火，不疼"
                    : "；你现在在燃烧，每秒都在掉血，快进水里");
        }

        String displayName;
        try {
            displayName = subject.m_5446_().getString();
        } catch (Throwable t) {
            displayName = "";
        }
        String whoText = displayName.isEmpty() ? who : "我（" + displayName + "）";
        return new Effects(whoText, count, harmful, beneficial, burning, kept, sb.toString());
    }

    private static String brief(Effect row) {
        return row.name() + (row.level() > 1 ? " " + roman(row.level()) + " 级" : "")
                + " " + timeText(row.duration_s());
    }

    private static Effect describe(MobEffectInstance instance) {
        MobEffect effect = instance.m_19544_();
        if (effect == null) {
            return null;
        }
        String id = idOf(effect);
        String name = nameOf(id);
        int level = instance.m_19564_() + 1;
        int seconds = secondsOf(instance);
        boolean harmful = harmfulOf(instance);
        String line = name + (level > 1 ? " " + roman(level) + " 级" : "") + "，" + timeText(seconds);
        return new Effect(id, name, level, seconds, harmful, line);
    }

    // ---------------------------------------------------------------- body state

    /**
     * {@code numen.contributeBodyState} 追加的那一行：着火、看不见、中毒/凋零在滴血的时候才说话。
     * 其余时间安静 —— 一个力量药水不是新闻，每 tick 都变的碎片反而没人看。
     */
    static String stateLine(NumenPlayer her) {
        UUID id;
        try {
            id = her.m_20148_();
        } catch (Throwable t) {
            id = null;
        }
        long now = System.currentTimeMillis();
        if (id != null) {
            Long stamp = STATE_STAMP.get(id);
            if (stamp != null && now - stamp < STATE_CACHE_MS) {
                String cached = STATE_TEXT.get(id);
                return cached == null ? "" : cached;
            }
        }
        String text;
        try {
            text = stateLine0(her);
        } catch (Throwable t) {
            text = "";
        }
        if (id != null) {
            STATE_STAMP.put(id, now);
            STATE_TEXT.put(id, text);
        }
        return text;
    }

    private static String stateLine0(NumenPlayer her) {
        // m_6060_ = isOnFire，不是 m_20096_（onGround）。
        boolean burning = her.m_6060_();
        int fireResistance = remaining(her, EFFECT_FIRE_RESISTANCE);
        if (burning && fireResistance == Integer.MIN_VALUE) {
            return "<effects>警告：你身上着火了，每秒都在掉血。跳进水里，或者找个水池，别顶着火跑。</effects>";
        }
        int blind = Math.max(remaining(her, EFFECT_BLINDNESS), remaining(her, EFFECT_DARKNESS));
        if (blind != Integer.MIN_VALUE) {
            String when = blind < 0 ? "不会自己好" : "还剩 " + shortTime(blind);
            return "<effects>注意：你现在看不见（失明" + when + "）。这期间别按记忆里的路线跑，先站着别动，"
                    + "或者问主人方向。</effects>";
        }
        int wither = remaining(her, EFFECT_WITHER);
        if (wither != Integer.MIN_VALUE) {
            return "<effects>警告：你在凋零（" + when(wither) + "），血会一路掉到 1 点，喝牛奶或者躲开来源。</effects>";
        }
        int poison = remaining(her, EFFECT_POISON);
        if (poison != Integer.MIN_VALUE) {
            return "<effects>注意：你在中毒（" + when(poison) + "），血会一直掉到剩 1 点，别再往怪堆里冲。</effects>";
        }
        int hunger = remaining(her, EFFECT_HUNGER);
        if (hunger != Integer.MIN_VALUE) {
            return "<effects>注意：你饿得快（" + when(hunger) + "），饱食度会掉得比平时快，省着点跑。</effects>";
        }
        return "";
    }

    private static String when(int seconds) {
        return seconds < 0 ? "不会自己好" : "还剩 " + shortTime(seconds);
    }

    /**
     * 某个效果还剩几秒：{@link Integer#MIN_VALUE} 表示没有，-1 表示有且永久。
     */
    private static int remaining(LivingEntity subject, String id) {
        MobEffectInstance instance = instanceOf(subject, id);
        if (instance == null) {
            return Integer.MIN_VALUE;
        }
        int duration = instance.m_19557_();
        return duration < 0 ? -1 : secondsOf(instance);
    }

    private static MobEffectInstance instanceOf(LivingEntity subject, String id) {
        Collection<MobEffectInstance> active = subject.m_21220_();
        if (active == null) {
            return null;
        }
        for (MobEffectInstance instance : active) {
            if (instance == null) {
                continue;
            }
            MobEffect effect = instance.m_19544_();
            if (effect == null) {
                continue;
            }
            if (id.equals(idOf(effect))) {
                return instance;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- helpers

    /** 把用户写的 id 收拾干净；空的报 bad_argument。 */
    private static String idOf(IdArg args) {
        String raw = args == null || args.id() == null ? "" : args.id().trim();
        if (raw.isEmpty()) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没说要查哪个效果",
                    "写效果 id，形如 effects.effects.has(\"minecraft:poison\")；"
                            + "不确定 id 就先 effects.effects.self() 看清单里的 id 字段",
                    "空 id");
        }
        if (raw.indexOf(':') < 0) {
            return "minecraft:" + raw;
        }
        return raw;
    }

    /** 剩几秒：向上取整，剩 19 tick 也报 1 秒，不会出现「还有 0 秒」。 */
    private static int secondsOf(MobEffectInstance instance) {
        int duration = instance.m_19557_();
        if (duration < 0) {
            return -1;
        }
        return Math.max(1, (duration + 19) / 20);
    }

    private static boolean harmfulOf(MobEffectInstance instance) {
        try {
            MobEffect effect = instance.m_19544_();
            return effect != null && effect.m_19483_() == MobEffectCategory.HARMFUL;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String idOf(MobEffect effect) {
        try {
            ResourceLocation key = BuiltInRegistries.f_256974_.m_7981_(effect);
            return key == null ? "unknown" : key.toString();
        } catch (Throwable t) {
            return "unknown";
        }
    }

    /** 中文名：共享表 → 效果自己的显示名 → 短的注册 id。 */
    private static String nameOf(String id) {
        if (id == null || id.isEmpty() || "unknown".equals(id)) {
            return "不认识的效果";
        }
        String key = "effect." + id.replace(':', '.');
        String cn = Zh.name(key);
        if (!key.equals(cn)) {
            return cn;
        }
        String shortId = id.startsWith("minecraft:") ? id.substring("minecraft:".length()) : id;
        MobEffect effect = null;
        try {
            effect = ForgeRegistries.MOB_EFFECTS.getValue(new ResourceLocation(id));
        } catch (Throwable ignored) {
            // id 不合法就当查不到
        }
        if (effect != null) {
            try {
                String raw = effect.m_19482_().getString();
                if (!raw.isEmpty() && raw.indexOf('.') < 0) {
                    return raw;
                }
            } catch (Throwable ignored) {
                // 退回短 id
            }
        }
        return shortId;
    }

    private static String timeText(int seconds) {
        if (seconds < 0) {
            return "永久";
        }
        return "还有 " + shortTime(seconds);
    }

    private static String shortTime(int seconds) {
        if (seconds < 60) {
            return seconds + " 秒";
        }
        int minutes = seconds / 60;
        int rest = seconds % 60;
        return rest == 0 ? minutes + " 分" : minutes + " 分 " + rest + " 秒";
    }

    private static String roman(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> String.valueOf(level);
        };
    }
}
