package dev.local.numenthreat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * {@code threat}：她面前那只是什么做的。
 *
 * <p>{@code stats} 答的是「我自己多强」，对朝她走过来的那只怪一句话都没有。这个整合包里这个缺口
 * 很要命：同一副外形，可能是 10 血的游荡者，也可能是 60 血、带甲、带属性修饰的强化怪
 * （Zombie Extreme / Undead Revamp / Deceased Beast 之类）。只看外形判断「顶上去还是撤」就是猜。
 *
 * <p>这些数字取自每只生物**真正在用的属性表**，所以模组变体、装备、药水、难度缩放都已经折进去了：
 * 就是游戏掷伤害时用的那个数。这里全是读：不写、不指定目标、不驯服、不伤害任何东西，
 * 读一次不会引发一场仗。
 *
 * <p>除了数字，还会给两样判断：看得见看不见（隔墙的怪不能算「在追你」），以及「她打不打得过」
 * ——拿她自己的血量和攻击力，对着这只怪的护甲和攻击力算一个回合数。
 */
public final class ThreatApi {

    /** 她能感觉到敌意的距离。再远就是背景噪音，不是决策。 */
    private static final int DEFAULT_RADIUS = 24;
    private static final int MAX_RADIUS = 48;
    private static final int MAX_ROWS = 8;
    private static final int MAX_SPECIES = 10;

    /** 竖直方向差这么多格以上，就在回执里明说「在你上面/下面」。 */
    private static final int HEIGHT_SAYS = 3;

    private ThreatApi() {
    }

    static void install(NumenApi numen) {
        numen.api("threat",
                "威胁：附近敌对生物的血量、护甲、攻击力、离她多远、看得见看不见、正在追谁，"
                        + "以及「她打不打得过」——判断顶上去还是撤看这个，别看外形。",
                ThreatApi.class);
    }

    // ---------------------------------------------------------------- 回执形状

    /** 一只敌对生物当下的数值与结论。 */
    @Doc("一只敌对生物当下的数值。")
    public record Foe(
            @Doc("注册 id，例如 `minecraft:zombie`、`zombie_extreme:mutant_zombie`。") String id,
            @Doc("中文名（有语言文件就翻），例如「僵尸」。") String name,
            @Doc("血量，形如 `12.0/20.0`。") String health,
            @Doc("护甲值（实际生效的，装备与模组加成都在里面）。") double armor,
            @Doc("攻击力：每下大约多少点伤害（实际生效值）。") double attack,
            @Doc("它靠什么打人：`近战` / `远程` / `爆炸`。后两种的伤害不走攻击力，别按 attack 算。") String damage,
            @Doc("直线距离（格）。") int distance,
            @Doc("竖直方向差几格：正数表示它在上面，负数在下面。") int height,
            @Doc("她能不能看见它（中间隔着方块就是 false）。") boolean visible,
            @Doc("它在追谁：`me`（她）、`owner`（真主人）、`someone`（别人）、空表示没在追人。") String targeting,
            @Doc("它在追谁的说明，中文，例如「正在追你」；没在追人是空串。") String chasing,
            @Doc("她打不打得过的结论，例如「她能赢（她约 3 下，它约 9 下）」；算不出来时说明原因。") String verdict,
            @Doc("一句话，可以直接念给主人。") String line) {
    }

    /** 一圈里的敌对生物。 */
    @Doc("附近的敌对生物。")
    public record Threats(
            @Doc("这次扫的半径（格）。") int radius,
            @Doc("这一圈里敌对生物总数（含没列出来的）。") int total,
            @Doc("没列出来的有几只（一次最多列 8 只）。") int hidden,
            @Doc("最多 8 只，按距离从近到远。") List<Foe> rows,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    /** 一种敌对生物有几只。 */
    @Doc("一种敌对生物在这一圈里有几只。")
    public record SpeciesCount(
            @Doc("注册 id。") String id,
            @Doc("中文名。") String name,
            @Doc("有几只。") int count,
            @Doc("最近的那只离她多少格。") int closest,
            @Doc("它靠什么打人：`近战` / `远程` / `爆炸`。") String damage) {
    }

    /** 按种类统计。 */
    @Doc("按种类统计的敌对生物。")
    public record Tally(
            @Doc("这次扫的半径（格）。") int radius,
            @Doc("这一圈里敌对生物总数。") int total,
            @Doc("一共有几种。") int species,
            @Doc("没列出来的有几种（一次最多列 10 种）。") int hidden,
            @Doc("最多 10 种，按数量从多到少。") List<SpeciesCount> rows,
            @Doc("一句话结论。") String line) {
    }

    /** 两个函数共用的选项。 */
    @Doc("扫多大一圈。")
    public record RadiusOpt(
            @Doc("半径（格），1-48。") @Omitted("24") Optional<Integer> radius) {
    }

    // ---------------------------------------------------------------- 函数

    @Fn("扫她附近的敌对生物：每一只的血量、护甲、攻击力、距离、看得见看不见、在追谁，以及她打不打得过。")
    @Example("threat.threat.near()")
    @Example("threat.threat.near({radius = 12})")
    @Note("只读。默认半径 24 格，上限 48，超出范围会报错而不是悄悄改。最多列 8 只，按远近排，"
            + "没列出来的只数写在 hidden 里。只算敌对生物和正在追人的怪，村民、动物、她的同伴都不在里面。"
            + "护甲和攻击力是**实际生效值**（装备、药水、模组强化都算进去了）；苦力怕这种靠爆炸、"
            + "骷髅这种靠远程的，attack 不是它的伤害，damage 字段会写明。")
    public static Threats near(ServerCall call, RadiusOpt args) {
        int radius = radiusOf(args);
        Scan scan = scan(call, radius);
        List<Foe> foes = scan.foes();
        List<Foe> rows = foes.size() > MAX_ROWS ? new ArrayList<>(foes.subList(0, MAX_ROWS)) : foes;
        int hidden = foes.size() - rows.size();

        StringBuilder line = new StringBuilder();
        if (foes.isEmpty()) {
            line.append("半径 ").append(radius).append(" 格内没有敌对生物");
        } else {
            Foe first = foes.get(0);
            line.append("半径 ").append(radius).append(" 格内有 ").append(foes.size())
                    .append(" 只敌对生物；最近的是 ").append(first.name())
                    .append("（").append(first.health()).append(" 血，护甲 ").append(num(first.armor()))
                    .append("，攻击 ").append(num(first.attack())).append("（").append(first.damage())
                    .append("），距 ").append(first.distance()).append(" 格")
                    .append(first.visible() ? "" : "，隔着方块看不见");
            if (!first.verdict().isEmpty()) {
                line.append("；").append(first.verdict());
            }
            line.append("）");
            if (hidden > 0) {
                line.append("；另有 ").append(hidden).append(" 只没列（一次最多列 ")
                        .append(MAX_ROWS).append(" 只）");
            }
        }
        return new Threats(radius, foes.size(), hidden, rows, line.toString());
    }

    @Fn("按种类统计附近的敌对生物：每种几只、最近的一只多远、它靠什么打人。")
    @Example("threat.threat.count()")
    @Example("threat.threat.count({radius = 32})")
    @Note("只读。默认半径 24 格，上限 48，超出范围会报错。最多列 10 种，没列出来的种类数写在 hidden 里。"
            + "要具体数值（血量、护甲、追谁、打不打得过）用 threat.threat.near()。")
    public static Tally count(ServerCall call, RadiusOpt args) {
        int radius = radiusOf(args);
        Scan scan = scan(call, radius);
        List<Foe> foes = scan.foes();

        Map<String, int[]> byId = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, String> damages = new LinkedHashMap<>();
        for (Foe foe : foes) {
            int[] cell = byId.get(foe.id());
            if (cell == null) {
                byId.put(foe.id(), new int[]{1, foe.distance()});
                names.put(foe.id(), foe.name());
                damages.put(foe.id(), foe.damage());
            } else {
                cell[0]++;
                cell[1] = Math.min(cell[1], foe.distance());
            }
        }

        List<SpeciesCount> all = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : byId.entrySet()) {
            String id = entry.getKey();
            all.add(new SpeciesCount(id, names.get(id), entry.getValue()[0], entry.getValue()[1],
                    damages.get(id)));
        }
        all.sort(Comparator.comparingInt(SpeciesCount::count).reversed()
                .thenComparingInt(SpeciesCount::closest)
                .thenComparing(SpeciesCount::id));

        List<SpeciesCount> rows = all.size() > MAX_SPECIES
                ? new ArrayList<>(all.subList(0, MAX_SPECIES)) : all;
        int hidden = all.size() - rows.size();

        StringBuilder line = new StringBuilder();
        if (foes.isEmpty()) {
            line.append("半径 ").append(radius).append(" 格内没有敌对生物");
        } else {
            SpeciesCount first = rows.get(0);
            line.append("半径 ").append(radius).append(" 格内共 ").append(foes.size())
                    .append(" 只，").append(all.size()).append(" 种；最多的是 ").append(first.name())
                    .append(" ").append(first.count()).append(" 只（最近的距 ").append(first.closest())
                    .append(" 格，").append(first.damage()).append("）");
            if (hidden > 0) {
                line.append("；另有 ").append(hidden).append(" 种没列（一次最多列 ")
                        .append(MAX_SPECIES).append(" 种）");
            }
        }
        return new Tally(radius, foes.size(), all.size(), hidden, rows, line.toString());
    }

    // ---------------------------------------------------------------- 扫描

    /** 一次扫描的结果：附近的怪，加上她自己那几个用来算回合数的数。 */
    private record Scan(List<Foe> foes) {
    }

    private static int radiusOf(RadiusOpt args) {
        if (args == null || args.radius() == null || args.radius().isEmpty()) {
            return DEFAULT_RADIUS;
        }
        int radius = args.radius().get();
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT,
                    "半径只收 1 到 48 格，给的是 " + radius,
                    "写 threat.threat.near({radius = 12})；不写就是 24 格", null);
        }
        return radius;
    }

    private static Scan scan(ServerCall call, int radius) {
        var her = call.her();
        if (!(her.m_9236_() instanceof ServerLevel level)) {
            throw new ApiError(ErrorKind.FAILED, "读不到她所在的世界",
                    "这个接口只在服务端世界里成立；她现在大概不在任何维度里", null);
        }
        UUID me = her.m_20148_();
        ServerPlayer owner = ownerOf(her);
        double hx = her.m_20185_();
        double hy = her.m_20186_();
        double hz = her.m_20189_();
        AABB box = new AABB(hx - radius, hy - radius, hz - radius, hx + radius, hy + radius, hz + radius);

        Her mine = new Her(
                her.m_21223_(),
                valueOf(her, "attack_damage"),
                valueOf(her, "armor"));

        List<LivingEntity> found;
        try {
            found = level.m_45976_(LivingEntity.class, box);
        } catch (Throwable t) {
            throw new ApiError(ErrorKind.FAILED, "扫不到附近的生物",
                    "读取实体表的时候出错了；这一般是版本差异，不是世界的问题", null);
        }

        List<Foe> out = new ArrayList<>();
        for (LivingEntity entity : found) {
            if (entity == null || entity == her || !entity.m_6084_()) {
                continue;
            }
            if (entity.m_20148_().equals(me)) {
                continue;
            }
            boolean hostile = entity instanceof Enemy;
            String targeting = targetingOf(entity, me, owner);
            if (!hostile && targeting.isEmpty()) {
                continue;
            }
            out.add(foe(her, entity, targeting, owner, mine));
        }
        out.sort(Comparator.comparingInt(Foe::distance)
                .thenComparing(Foe::id));
        return new Scan(out);
    }

    /** 她自己那几个用来算回合数的数。 */
    private record Her(double health, double attack, double armor) {
    }

    private static Foe foe(LivingEntity her, LivingEntity entity, String targeting,
                           ServerPlayer owner, Her mine) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(entity.m_6095_());
        String key = id == null ? "?" : id.toString();
        String name = zhName(id, entity);

        double health = round(entity.m_21223_());
        double maxHealth = round(entity.m_21233_());
        double armor = valueOf(entity, "armor");
        // m_22135_ 是「算上所有加成之后」的值；m_22115_ 是基础值，别用那个（实测：僵尸拿钻石剑
        // 穿铁甲，基础值 2/2，实际生效 11/7）。
        double attack = valueOf(entity, "attack_damage");
        String damage = damageKind(entity, attack);

        int distance = (int) Math.round(Math.sqrt(entity.m_20275_(her.m_20185_(), her.m_20186_(),
                her.m_20189_())));
        int height = (int) Math.round(entity.m_20186_() - her.m_20186_());
        boolean visible = canSee(her, entity);

        String chasing = chase(targeting, owner, entity);
        String verdict = verdict(damage, health, armor, attack, mine);

        StringBuilder line = new StringBuilder();
        line.append(name).append("：").append(health).append("/").append(maxHealth).append(" 血，护甲 ")
                .append(num(armor)).append("，攻击 ").append(num(attack)).append("（").append(damage).append("）")
                .append("，距 ").append(distance).append(" 格");
        if (height >= HEIGHT_SAYS) {
            line.append("（在你上面 ").append(height).append(" 格）");
        } else if (height <= -HEIGHT_SAYS) {
            line.append("（在你下面 ").append(-height).append(" 格）");
        }
        if (!visible) {
            line.append("，隔着方块看不见");
        }
        if (!chasing.isEmpty()) {
            line.append("，").append(chasing);
        }
        if (!verdict.isEmpty()) {
            line.append("；").append(verdict);
        }

        return new Foe(key, name, health + "/" + maxHealth, armor, attack, damage, distance, height,
                visible, targeting, chasing, verdict, line.toString());
    }

    // ---------------------------------------------------------------- 数值

    /**
     * 某项属性的实际生效值（0 表示这台身体没有这项）。
     *
     * <p>两条都要注意，都是实测踩出来的：
     * <ul>
     *   <li>读 {@code m_22135_()}（算上加成）而不是 {@code m_22115_()}（基础值）；</li>
     *   <li>刚刷出来的怪，属性实例可能还没被谁问过，同步表 {@code m_22170_()} 里根本没有它；
     *       先用 {@code m_22146_()} 问一次把实例建出来，否则掠夺者、恶魂这类会报 0。</li>
     * </ul>
     */
    private static double valueOf(LivingEntity entity, String want) {
        AttributeMap map = entity.m_21204_();
        AttributeInstance instance = find(map, want);
        if (instance == null) {
            Attribute attribute = ForgeRegistries.ATTRIBUTES.getValue(
                    new ResourceLocation("minecraft:generic." + want));
            if (attribute != null) {
                instance = map.m_22146_(attribute);
            }
        }
        if (instance == null) {
            return 0.0D;
        }
        try {
            return round(instance.m_22135_());
        } catch (Throwable t) {
            return 0.0D;
        }
    }

    /** 在已经建出来的实例里按短名找：优先 {@code minecraft:generic.<id>}，否则任意同短名的。 */
    private static AttributeInstance find(AttributeMap map, String want) {
        Collection<AttributeInstance> instances = map.m_22170_();
        if (instances == null) {
            return null;
        }
        AttributeInstance any = null;
        for (AttributeInstance instance : instances) {
            if (instance == null) {
                continue;
            }
            Attribute attribute = instance.m_22099_();
            if (attribute == null) {
                continue;
            }
            ResourceLocation key = ForgeRegistries.ATTRIBUTES.getKey(attribute);
            String path = key != null ? key.m_135815_() : attribute.m_22087_();
            String shortId = shortOf(path);
            if (!want.equals(shortId)) {
                continue;
            }
            if ("minecraft".equals(key == null ? "" : key.m_135827_())
                    && path.startsWith("generic.")) {
                return instance;
            }
            if (any == null) {
                any = instance;
            }
        }
        return any;
    }

    private static String shortOf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /**
     * 它靠什么打人。
     *
     * <p>爆炸和远程的伤害不走 {@code attack_damage}：苦力怕读出来是 0，骷髅的近战属性也远低于
     * 一支箭。不标出来，她会从「攻击 0」得出「它不伤人」。
     */
    private static String damageKind(LivingEntity entity, double attack) {
        if (entity instanceof Creeper) {
            return "爆炸";
        }
        if (entity instanceof RangedAttackMob || entity instanceof Ghast || entity instanceof Blaze) {
            // 恶魂和烈焰人不用 RangedAttackMob 这套接口（各自一套火球 AI），但它们的危险同样是远程。
            return "远程";
        }
        if (attack <= 0.5D) {
            // 恶魂、史莱姆这类：属性里压根没有攻击力，伤害是它自己的机制（火球、跳撞）。
            // 写成「近战 0」会被读成「它不伤人」，所以单独标出来。
            return "不算攻击力";
        }
        return "近战";
    }

    // ---------------------------------------------------------------- 判断

    private static boolean canSee(LivingEntity her, LivingEntity entity) {
        try {
            return her.m_142582_(entity);
        } catch (Throwable t) {
            return true;
        }
    }

    /**
     * 「她打不打得过」：两边各算一个回合数。
     *
     * <p>护甲减免用原版 1.20.1 那条公式（{@code 减伤 = min(20, max(护甲/5, 护甲 - 伤害/(2+韧性/4)))/25}，
     * 这里按没有韧性算，偏保守）。往返一算，谁先倒下就是谁赢；差距在一下之内算势均力敌。
     */
    private static String verdict(String damage, double foeHealth, double foeArmor, double foeAttack,
                                  Her mine) {
        if ("爆炸".equals(damage) || "远程".equals(damage)) {
            return "它的伤害不走攻击力，算不出来（看 damage 字段）";
        }
        if (foeAttack <= 0.0D) {
            return "它的属性里没有攻击力，伤害靠它自己的机制（火球、跳撞之类），算不出来";
        }
        if (mine.attack() <= 0.0D) {
            return "";
        }
        double toHer = afterArmor(foeAttack, mine.armor());
        double toIt = afterArmor(mine.attack(), foeArmor);
        if (toHer <= 0.0D || toIt <= 0.0D) {
            return "";
        }
        // 名字容易弄反，这里写清楚：herHits 是**它**打死她要几下，itsHits 是**她**打死它要几下。
        int herHits = (int) Math.ceil(mine.health() / toHer);
        int itsHits = (int) Math.ceil(foeHealth / toIt);
        // 打印时按「她几下 / 它几下」的顺序给 itsHits 在前，否则读者会以为她打得更多。
        if (itsHits + 1 < herHits) {
            return "她能赢（她约 " + itsHits + " 下，它约 " + herHits + " 下）";
        }
        if (herHits + 1 < itsHits) {
            return "打不过（她约 " + itsHits + " 下，它约 " + herHits + " 下）";
        }
        return "势均力敌（各约 " + Math.min(herHits, itsHits) + " 下）";
    }

    /** 原版 1.20.1 的护甲减免：伤害打进来之后还剩多少。 */
    private static double afterArmor(double damage, double armor) {
        double reduce = Math.max(armor / 5.0D, armor - damage / 2.0D);
        reduce = Math.min(20.0D, Math.max(0.0D, reduce));
        return Math.max(0.5D, damage * (1.0D - reduce / 25.0D));
    }

    // ---------------------------------------------------------------- 追谁

    private static String targetingOf(LivingEntity entity, UUID me, ServerPlayer owner) {
        if (!(entity instanceof Mob mob)) {
            return "";
        }
        LivingEntity target;
        try {
            target = mob.m_5448_();
        } catch (Throwable t) {
            return "";
        }
        if (target == null || !target.m_6084_()) {
            return "";
        }
        UUID id = target.m_20148_();
        if (id.equals(me)) {
            return "me";
        }
        if (owner != null && id.equals(owner.m_20148_())) {
            return "owner";
        }
        return "someone";
    }

    private static String chase(String targeting, ServerPlayer owner, LivingEntity entity) {
        switch (targeting) {
            case "me":
                return "正在追你";
            case "owner":
                return "正在追主人" + (owner == null ? "" : "（" + owner.m_5446_().getString() + "）");
            case "someone":
                return "正在追别人";
            default:
                return "";
        }
    }

    /**
     * 她的真主人。
     *
     * <p>以前这里是「第一个不是她的在线玩家」——这条在实战里会把路人写成主人。现在问的是权限层：
     * 主人没上线就是没人，不猜。
     */
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

    // ---------------------------------------------------------------- 小工具

    private static String zhName(ResourceLocation id, LivingEntity entity) {
        String english = entity.m_5446_().getString();
        if (id != null) {
            String key = "entity." + id.m_135827_() + "." + id.m_135815_();
            String cn = Zh.name(key);
            if (cn != null && !cn.isEmpty() && !key.equals(cn)) {
                return cn;
            }
        }
        return english;
    }

    private static double round(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static String num(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.005D) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format(Locale.ROOT, "%.1f", value);
    }

    static String health() {
        return "threat.near/count";
    }
}
