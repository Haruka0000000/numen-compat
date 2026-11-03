package dev.local.numenstats;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code stats}：她的身体现在是什么状态。
 *
 * <p>读的是游戏里那张真正在用的属性表，所以钻石胸甲、力量药水、模组饰品、带属性的武器
 * 都已经算进去了：这里报的数就是游戏判定伤害、判定跑得多快时用的那个数。
 *
 * <p>只读，而且只读她自己。不改任何属性、不施加任何效果、不缓存——每次调用都重新问一遍。
 */
public final class StatsApi {

    /**
     * 优先列出来的属性，按这个顺序。它们是决定一场仗能不能打的几个数；其余的排在后面，
     * 有位置才列。
     */
    private static final List<String> IMPORTANT = List.of(
            "max_health", "armor", "armor_toughness", "attack_damage", "attack_speed",
            "movement_speed", "knockback_resistance", "luck", "follow_range", "flying_speed");

    /** 一次最多列多少项。超出的在回执里写明还有几项，不静默丢掉。 */
    private static final int MAX_ROWS = 24;

    private StatsApi() {
    }

    static void install(NumenApi numen) {
        numen.api("stats", "属性：我自己的血量、护甲、攻击、移速，装备与药水的加成都会算进去。",
                StatsApi.class);
    }

    // ---------------------------------------------------------------- 回执形状

    /** 一项属性，现在这一刻的值。 */
    @Doc("一项属性。")
    public record Stat(
            @Doc("属性的短名，例如 `max_health`、`armor`、`attack_damage`。") String id,
            @Doc("中文名，例如「最大生命值」「护甲值」。") String name,
            @Doc("当前生效值，装备、药水、模组加成都已经算进去。") double value) {
    }

    /** 一个身体的属性总览。 */
    @Doc("一个身体的属性总览。")
    public record Stats(
            @Doc("这是谁。") String who,
            @Doc("血量，形如 `18.0/20.0`。") String health,
            @Doc("护甲值（含装备与药水）。") double armor,
            @Doc("攻击力（含武器与药水）。") double attack,
            @Doc("走路速度，格/秒（含加成；冲刺更快）。") double speed,
            @Doc("属性明细，重要项在前，最多 24 项。") List<Stat> attributes,
            @Doc("没列出来的非空属性有几项（太多放不下时）。") int more,
            @Doc("没列出来的「值为 0」的属性有几项。") int empty,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    // ---------------------------------------------------------------- 函数

    @Fn("我自己的属性：血量、护甲、攻击、移速，以及属性明细。")
    @Example("stats.stats.self()")
    @Note("只读，而且只读她自己——主人或别的玩家的属性不从这里看。数值是现在这一刻的，"
            + "换了装备、药水效果消失之后要重新读。")
    public static Stats self(ServerCall call) {
        return read(call.her());
    }

    // ---------------------------------------------------------------- 内部实现

    private static Stats read(LivingEntity entity) {
        AttributeMap map = entity.m_21204_();
        Map<String, Stat> byFullId = new LinkedHashMap<>();
        Collection<AttributeInstance> instances = map.m_22170_();
        if (instances != null) {
            for (AttributeInstance instance : instances) {
                if (instance == null) {
                    continue;
                }
                Attribute attribute = instance.m_22099_();
                if (attribute == null) {
                    continue;
                }
                ResourceLocation key = ForgeRegistries.ATTRIBUTES.getKey(attribute);
                String full = key != null ? key.toString() : attribute.m_22087_();
                if (byFullId.containsKey(full)) {
                    continue;
                }
                byFullId.put(full, new Stat(
                        key != null ? shortOf(key.m_135815_()) : full,
                        label(key, attribute),
                        // m_22135_ 是「算上所有加成之后」的值；m_22115_ 是基础值，别用那个
                        round(instance.m_22135_())));
            }
        }

        List<Stat> rows = new ArrayList<>();
        Set<String> shown = new HashSet<>();
        for (String want : IMPORTANT) {
            String full = pick(byFullId.keySet(), want);
            if (full == null) {
                // 刚生成的身体里，有些属性实例还没被谁问过，于是不在同步表里；问一次就建出来。
                full = ensure(map, byFullId, want);
            }
            if (full != null && shown.add(full)) {
                rows.add(byFullId.get(full));
            }
        }

        List<String> rest = new ArrayList<>();
        int empty = 0;
        for (Map.Entry<String, Stat> entry : byFullId.entrySet()) {
            if (shown.contains(entry.getKey())) {
                continue;
            }
            if (entry.getValue().value() != 0.0D) {
                rest.add(entry.getKey());
            } else {
                empty++;
            }
        }
        rest.sort(Comparator.comparing(full -> byFullId.get(full).id()));

        int space = Math.max(0, MAX_ROWS - rows.size());
        int added = 0;
        for (String full : rest) {
            if (added >= space) {
                break;
            }
            rows.add(byFullId.get(full));
            added++;
        }
        int more = rest.size() - added;

        double health = round(entity.m_21223_());
        double maxHealth = round(entity.m_21233_());
        double armor = valueOf(byFullId, "armor");
        double attack = valueOf(byFullId, "attack_damage");
        double speed = round(valueOf(byFullId, "movement_speed") * 20.0D);

        StringBuilder line = new StringBuilder();
        line.append(maxHealth).append(" 血上限，现在 ").append(health).append(" 血；护甲 ").append(armor)
                .append("，攻击 ").append(attack)
                .append("，走路约 ").append(speed).append(" 格/秒（冲刺更快）");
        if (empty > 0) {
            line.append("；另有 ").append(empty).append(" 项属性是 0（没列）");
        }
        if (more > 0) {
            line.append("；还有 ").append(more).append(" 项没列（一次最多列 ").append(MAX_ROWS).append(" 项）");
        }

        String who = "我（" + entity.m_5446_().getString() + "）";
        return new Stats(who, health + "/" + maxHealth, armor, attack, speed, rows, more, empty,
                line.toString());
    }

    /**
     * 重要属性如果连实例都还没有，就主动问一次属性表把它建出来。
     *
     * <p>刚生成的身体上，有些属性实例要等有人第一次读它才存在；不补这一下，刚召唤出来的那一秒
     * 会漏报攻击力之类的数（实测：刚 summon 完读 attack_damage 是缺的，隔一会儿才有）。
     */
    private static String ensure(AttributeMap map, Map<String, Stat> byFullId, String want) {
        String full = "minecraft:generic." + want;
        Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(new ResourceLocation(full));
        if (attribute == null) {
            return null;
        }
        AttributeInstance instance = map.m_22146_(attribute);
        if (instance == null) {
            return null;
        }
        byFullId.put(full, new Stat(want, label(new ResourceLocation(full), attribute),
                round(instance.m_22135_())));
        return full;
    }

    /** 重要属性按短名找：优先 {@code minecraft:generic.<id>}，否则任意同短名的。 */
    private static String pick(Collection<String> fullIds, String want) {
        String generic = "minecraft:generic." + want;
        if (fullIds.contains(generic)) {
            return generic;
        }
        for (String full : fullIds) {
            if (shortOf(pathOf(full)).equals(want)) {
                return full;
            }
        }
        return null;
    }

    private static double valueOf(Map<String, Stat> byFullId, String want) {
        String full = pick(byFullId.keySet(), want);
        return full == null ? 0.0D : byFullId.get(full).value();
    }

    /** {@code minecraft:generic.max_health} → {@code generic.max_health}。 */
    private static String pathOf(String full) {
        int colon = full.indexOf(':');
        return colon < 0 ? full : full.substring(colon + 1);
    }

    /** {@code generic.max_health} → {@code max_health}。 */
    private static String shortOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /**
     * 属性的中文名。原版的键是 {@code attribute.name.generic.max_health}；模组属性的键不统一，
     * 所以按几个常见形状逐个试，都查不到就退回短名（例如 {@code armor_shred}）——不抛异常。
     */
    private static String label(ResourceLocation key, Attribute attribute) {
        String path = key != null ? key.m_135815_() : attribute.m_22087_();
        String ns = key != null ? key.m_135827_() : "minecraft";
        String shortId = shortOf(path);
        for (String candidate : new String[]{
                "attribute.name." + path,
                "attribute.name." + ns + "." + shortId,
                "attribute." + ns + "." + shortId}) {
            String text = Zh.name(candidate);
            if (text != null && !text.isBlank() && !text.equals(candidate)) {
                return text;
            }
        }
        return shortId;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }
}
