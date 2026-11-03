package dev.local.numenlso;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.Pending;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Containers;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.UseAnim;
import net.minecraft.world.level.Level;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import org.apache.commons.lang3.tuple.Pair;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.BodyDamageUtil;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.BodyPartEnum;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.IBodyDamageCapability;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.MalusBodyPartEnum;
import sfiomn.legendarysurvivaloverhaul.api.data.json.JsonHealingConsumable;
import sfiomn.legendarysurvivaloverhaul.api.data.json.JsonThirstConsumable;
import sfiomn.legendarysurvivaloverhaul.api.data.manager.BodyDamageDataManager;
import sfiomn.legendarysurvivaloverhaul.api.data.manager.ThirstDataManager;
import sfiomn.legendarysurvivaloverhaul.api.temperature.ModifierBase;
import sfiomn.legendarysurvivaloverhaul.api.thirst.ThirstUtil;
import sfiomn.legendarysurvivaloverhaul.common.capabilities.temperature.TemperatureCapability;
import sfiomn.legendarysurvivaloverhaul.common.capabilities.thirst.ThirstCapability;
import sfiomn.legendarysurvivaloverhaul.registry.MobEffectRegistry;
import sfiomn.legendarysurvivaloverhaul.registry.SoundRegistry;
import sfiomn.legendarysurvivaloverhaul.registry.TemperatureModifierRegistry;
import sfiomn.legendarysurvivaloverhaul.util.CapabilityUtil;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code lso.body}：生存大修（Legendary Survival Overhaul）的水分、体温、四肢伤口，全部在服务端读写。
 *
 * <p>同伴是没有客户端的 {@code ServerPlayer}，所以生存大修画在客户端的水分条、体温条和「身体界面」
 * （{@code healing_charges > 0} 的创可贴/绷带唯一的使用入口）她一个都看不见。但那些数字全都躺在
 * 服务端的能力里，身体界面的效果也可以用服务端手段复现（{@link BodyDamageUtil#applyHealingTimeBodyPart}
 * + 恢复效果 + 音效）。这几个函数就是把读数、上药和喝水交给她。
 */
public final class LsoApi {

    static final String NAMESPACE = "lso";

    /** 一次最多翻多少个容器格，防止背着一箱箱子背包时卡住。 */
    private static final int MAX_SCAN = 600;

    private LsoApi() {
    }

    static void install(NumenApi numen) {
        numen.api("body", "生存大修：她的水分、体温、四肢伤口，身上带着的药（创可贴/绷带/药草/补剂/医疗包）"
                + "和能喝的饮料。", LsoApi.class);
        // 她当初把生存大修的水分条当工具读，从来没主动去喝——一场里渴死九次，每次都是伤害已经开始才喝。
        // 所以这个数字还放在她每刻都能看到的地方：身体状态那一行。
        numen.contributeBodyState(SelfCheck::stateLine);
        com.dwinovo.numen.task.BrainChains.register(8, ThirstGuard::new);
    }

    // ---------------------------------------------------------------- 读数

    /** {@code hydration=15/20 saturation=3.5 exhaustion=1.2 full=false}。 */
    @Doc("生存大修记的水分和体温。")
    public record Vitals(
            @Doc("水分，0..20。") int hydration,
            @Doc("水分上限。") int maxHydration,
            @Doc("口中含水（饱和）度。") double saturation,
            @Doc("消耗度，到 4.0 就开始掉水分。") double exhaustion,
            @Doc("水分是不是满的。") boolean full,
            @Doc("体温。") double temperature,
            @Doc("环境想把她拉到多少度。") double targetTemperature,
            @Doc("冷热状态（中文），例如「偏冷」「冻伤」「中暑」。") String temperatureState,
            @Doc("原始状态名：NORMAL / COLD / HOT / FROSTBITE / HEAT_STROKE。") String temperatureStateId,
            @Doc("体温是被谁拉偏的，从影响最大的开始（正数是往热里拉，负数是往冷里拉）。") List<String> temperatureCauses) {
    }

    @Fn("读她的水分和体温（生存大修记的那一套数字）。")
    @Example("lso.body.vitals()")
    @Note("读的是生存大修界面上画的同一批数字，什么都不改。")
    @SeeAlso({"lso.body.heal", "lso.body.limbs", "lso.body.drink"})
    public static Vitals vitals(ServerCall call) {
        NumenPlayer her = call.her();
        ThirstCapability t = CapabilityUtil.getThirstCapability(her);
        if (t == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读不到她的水分（生存大修没装好，或者这个存档关了水分）", null);
        }
        TemperatureCapability temp = CapabilityUtil.getTempCapability(her);
        String id = temp == null ? "NORMAL" : String.valueOf(temp.getTemperatureEnum());
        return new Vitals(t.getHydrationLevel(), ThirstCapability.MAX_HYDRATION,
                (double) t.getSaturationLevel(), (double) t.getThirstExhaustion(),
                t.getHydrationLevel() >= ThirstCapability.MAX_HYDRATION,
                temp == null ? 0.0 : (double) temp.getTemperatureLevel(),
                temp == null ? 0.0 : (double) temp.getTargetTemperatureLevel(),
                temp == null ? "读不到" : zhState(id), id,
                temp == null ? new ArrayList<>() : causes(her));
    }

    /** 一个部位。 */
    @Doc("一个部位的状态。")
    public record Limb(
            @Doc("部位代号，上药时写这个：HEAD、CHEST、LEFT_ARM、RIGHT_ARM、LEFT_LEG、RIGHT_LEG、LEFT_FOOT、RIGHT_FOOT。")
            String part,
            @Doc("部位的中文名，例如「左腿」。") String label,
            @Doc("现在多少血。") double health,
            @Doc("满血是多少。") double max,
            @Doc("正在自动长回来的总血量，没有治疗时为空。") Optional<Double> healing,
            @Doc("照这个速度还有几秒长完。") Optional<Double> healingSeconds) {
    }

    /** 全部部位，伤得最重的最前面。 */
    @Doc("她的八个部位。")
    public record Limbs(
            @Doc("有几个部位在受伤。") int wounds,
            @Doc("伤得最重的那个部位（代号），没伤时为空。") Optional<String> worst,
            @Doc("所有部位加起来还剩多少血。") double totalHealth,
            @Doc("所有部位加起来满血是多少。") double totalMax,
            @Doc("每个部位，伤得重的在前。") List<Limb> limbs) {
    }

    @Fn("读她八个部位各自的血量，以及正在长回来的治疗。")
    @Example("lso.body.limbs()")
    @Note("读的是生存大修的身体数据，什么都不改。")
    @SeeAlso("lso.body.heal")
    public static Limbs limbs(ServerCall call) {
        NumenPlayer her = call.her();
        IBodyDamageCapability c = cap(her);
        if (c == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读不到她的身体数据（生存大修的身体伤害没启用）", null);
        }
        List<Limb> out = new ArrayList<>();
        int wounds = 0;
        BodyPartEnum worst = null;
        double worstRatio = 1.0;
        double sumHp = 0.0;
        double sumMax = 0.0;
        for (BodyPartEnum p : BodyPartEnum.values()) {
            double max = c.getBodyPartMaxHealth(p);
            double hp = health(c, p);
            sumHp += hp;
            sumMax += max;
            if (hp < max - 0.01) {
                wounds++;
                double r = max <= 0.0 ? 1.0 : hp / max;
                if (r < worstRatio) {
                    worstRatio = r;
                    worst = p;
                }
            }
            int ticks = c.getRemainingHealingTicks(p);
            float per = c.getHealingPerTicks(p);
            Limb limb = ticks > 0
                    ? new Limb(p.name(), zhPart(p), hp, max, Optional.of((double) (ticks * per)),
                            Optional.of(ticks / 20.0))
                    : new Limb(p.name(), zhPart(p), hp, max, Optional.empty(), Optional.empty());
            out.add(limb);
        }
        out.sort((a, b) -> Double.compare(a.health() / Math.max(1e-6, a.max()),
                b.health() / Math.max(1e-6, b.max())));
        return new Limbs(wounds, worst == null ? Optional.empty() : Optional.of(worst.name()),
                sumHp, sumMax, out);
    }

    /** 因为某个部位受伤而被挂上的一个减益。 */
    @Doc("因为部位受伤、生存大修一直给她续着的一个减益。")
    public record Malus(
            @Doc("伤口组：FEET、BOTH_FEET、ARMS、BOTH_ARMS、LEGS、BOTH_LEGS、HEAD、CHEST。") String group,
            @Doc("这一组代表哪些部位，例如「左脚/右脚」。") String parts,
            @Doc("减益 id，例如 minecraft:slowness。") String effect,
            @Doc("减益的中文名，例如「缓慢」。") String name,
            @Doc("等级，0 = 一级。") int amplifier,
            @Doc("这一组的血量比例，0..1。") double ratio) {
    }

    @Doc("现在有哪些减益是被伤口挂着的。")
    public record Maluses(
            @Doc("每一个被伤口挂上的减益。") List<Malus> hits,
            @Doc("是不是有止痛药在压着：伤还在，只是暂时不发作。") boolean painkiller,
            @Doc("一句话说明该怎么办。") String note) {
    }

    @Fn("读因为四肢受伤被挂上的减益：脚伤挂缓慢、手伤挂疲劳、双手都伤挂虚弱、腿伤挂摔落加重。")
    @Example("lso.body.malus()")
    @Note("这些减益不会自己消失：只要伤还在，生存大修每秒用无限时长续一次，伤好了才撤。用命令清掉只能撑一秒左右，正确做法是把伤治好。")
    @Note("止痛药（吗啡/针剂）能暂时压住它们，但一点伤都不治。")
    @SeeAlso({"lso.body.limbs", "lso.body.heal"})
    public static Maluses malus(ServerCall call) {
        NumenPlayer her = call.her();
        IBodyDamageCapability c = cap(her);
        if (c == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读不到她的身体数据（生存大修的身体伤害没启用）", null);
        }
        List<Malus> hits = new ArrayList<>();
        boolean wounded = false;
        for (MalusBodyPartEnum g : MalusBodyPartEnum.values()) {
            float ratio = c.getHealthRatioForMalusBodyPart(g);
            double deepest = -1.0;
            for (double t : g.thresholds) {
                if (t > deepest) {
                    deepest = t;
                }
            }
            if (deepest >= 0.0 && ratio <= deepest + 1.0e-4) {
                wounded = true;
            }
            for (Pair<MobEffect, Integer> p : BodyDamageUtil.getEffects(g, ratio)) {
                MobEffect eff = p.getLeft();
                ResourceLocation id = ForgeRegistries.MOB_EFFECTS.getKey(eff);
                hits.add(new Malus(g.name(), partsOf(g), id == null ? "unknown" : id.toString(),
                        effName(eff), p.getRight(), (double) ratio));
            }
        }
        boolean painkiller = wounded && hits.isEmpty();
        String note;
        if (!hits.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Malus m : hits) {
                if (sb.length() > 0) {
                    sb.append("；");
                }
                sb.append("「").append(m.name()).append("」来自").append(m.parts());
            }
            note = "这些是" + sb + "。医疗包一剂能同时治八个部位（次数 0），创可贴/绷带只能治你点名的那个部位。";
        } else if (painkiller) {
            note = "有止痛药在压着，伤还在——不治的话药劲过去还会回来。";
        } else {
            note = "现在没有哪个伤口在挂减益。";
        }
        return new Maluses(hits, painkiller, note);
    }

    /** 伤口组代表哪些部位，用 {@code lso.body.limbs} 里的代号。 */
    private static String partsOf(MalusBodyPartEnum g) {
        switch (g) {
            case HEAD:
                return "头";
            case CHEST:
                return "胸";
            case ARMS:
                return "左臂/右臂";
            case BOTH_ARMS:
                return "左臂和右臂";
            case LEGS:
                return "左腿/右腿";
            case BOTH_LEGS:
                return "左腿和右腿";
            case FEET:
                return "左脚/右脚";
            case BOTH_FEET:
                return "左脚和右脚";
            default:
                return g.name();
        }
    }

    /** 她身上带着的一件能用的东西：药或者饮料。 */
    @Doc("她身上带着的一件药或者饮料。")
    public record Kit(
            @Doc("在哪：「主手」「快捷栏第2格」「背包第3格」这种。") String where,
            @Doc("这一格有几个。") int count,
            @Doc("物品 id。") String id,
            @Doc("物品的中文名。") String name,
            @Doc("这一格是药（heal）还是喝的（drink）。") String kind,
            @Doc("一件能治几个部位；0 = 能治所有受伤部位。饮料这里是 0。") int charges,
            @Doc("每件治多少血（药）或补多少水分（喝的）。") double value,
            @Doc("要多久见效（秒）。") double seconds) {
    }

    /** 她现在能用什么。 */
    @Doc("她现在身上带着的药和饮料。")
    public record Items(
            @Doc("药和饮料，手上的、背包里的排在前面。") List<Kit> items,
            @Doc("有几件是药。") int healing,
            @Doc("有几件是能喝的。") int drinks) {
    }

    @Fn("列出她身上带着的药和能喝的饮料，以及每件能做什么。")
    @Example("lso.body.items()")
    @Note("翻她的手、40 个物品栏格、饰品栏，以及物品栏里每件带格子的东西（背包、箱子背包）——只翻一层，不往背包里再翻背包。什么都不改。")
    @SeeAlso({"lso.body.heal", "lso.body.drink"})
    public static Items items(ServerCall call) {
        List<Kit> out = new ArrayList<>();
        int healing = 0;
        int drinks = 0;
        for (Spot sp : spots(call.her())) {
            String name = Zh.name(sp.stack);
            if (sp.heal != null) {
                out.add(new Kit(sp.where, count(sp.stack), String.valueOf(sp.id), name, "heal",
                        sp.heal.healingCharges, (double) sp.heal.healingValue, sp.heal.healingTime / 20.0));
                healing++;
            } else if (sp.drink != null) {
                out.add(new Kit(sp.where, count(sp.stack), String.valueOf(sp.id), name, "drink",
                        0, sp.drink.hydration, 0.0));
                drinks++;
            }
        }
        return new Items(out, healing, drinks);
    }

    // ---------------------------------------------------------------- 上药

    /** 要治哪个部位。 */
    public record Heal(
            @Doc("要治哪个部位：HEAD、CHEST、LEFT_ARM、RIGHT_ARM、LEFT_LEG、RIGHT_LEG、LEFT_FOOT、RIGHT_FOOT。")
            @Omitted("治伤得最重的那几个部位")
            Optional<String> part) {
    }

    /** 一剂药用下去之后。 */
    @Doc("一剂药用下去之后的样子。")
    public record Healed(
            @Doc("用了什么，例如「创可贴」。") String used,
            @Doc("这件东西原来在哪。") String from,
            @Doc("挂上的恢复效果，例如「60 秒 一级」，没有就是「没有」。") String effect,
            @Doc("这一剂治了哪几个部位（中文名）。") List<String> limbs,
            @Doc("用完之后的血量。") double hp,
            @Doc("用完之后的血量上限。") double maxHp,
            @Doc("这种东西身上还剩几个。") int left,
            @Doc("一句话说明。") String line) {
    }

    @Fn("用一件药治一个部位（不点名就治伤得最重的那个）。她会把药真的拿到手上用，客户端看得见动作和声音，用满时间才生效。")
    @Example("lso.body.heal()")
    @Example("lso.body.heal({part = \"LEFT_LEG\"})")
    @Note("会消耗她身上一件药，不需要问主人（只动她自己的背包和身体，权限层不会拦）。")
    @Note("创可贴治一个部位，绷带最多三个，补剂和医疗包治所有受伤部位。她身上没有药、或者点名的部位根本没受伤时，会直接报错说清楚。")
    @Note("这条路和真人一模一样：先把药拿到手上、举起来用满时间，然后才生效——所以这个调用会等那一会儿再回结果。用多久由整合包配置决定：本包创可贴 5 tick、绷带 5 tick、补剂 10 tick、医疗包 20 tick（20 tick = 1 秒，所以创可贴只闪 0.25 秒——真人用也是这么快）。")
    @SeeAlso({"lso.body.limbs", "lso.body.items", "lso.body.drink"})
    public static Pending<Healed> heal(ServerCall call, Heal args) {
        NumenPlayer her = call.her();
        IBodyDamageCapability c = cap(her);
        if (c == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读不到她的身体数据（生存大修的身体伤害没启用）", null);
        }
        String asked = args.part().orElse("").trim();
        List<BodyPartEnum> parts = pickParts(c, asked, 0);
        if (parts.isEmpty() && !asked.isEmpty() && !asked.equalsIgnoreCase("auto")
                && !asked.equalsIgnoreCase("worst")) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "没有这个部位：" + asked,
                    "部位写 HEAD / CHEST / LEFT_ARM / RIGHT_ARM / LEFT_LEG / RIGHT_LEG / LEFT_FOOT / RIGHT_FOOT");
        }
        if (parts.isEmpty()) {
            throw new ApiError(ErrorKind.FAILED, "她身上没有伤，用不着上药（东西也没动）", "lso.body.limbs()");
        }
        boolean named = !asked.isEmpty() && !asked.equalsIgnoreCase("auto") && !asked.equalsIgnoreCase("worst");
        if (named && c.getBodyPartDamage(parts.get(0)) <= 0.05f) {
            throw new ApiError(ErrorKind.FAILED, "「" + zhPart(parts.get(0)) + "」没受伤，不用上药（东西也没动）",
                    "lso.body.limbs()");
        }
        Spot spot = find(her);
        if (spot == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL,
                    "她身上没有能疗伤的东西（创可贴/绷带/药草/补剂/医疗包）——物品栏、快捷栏、饰品栏和背包都翻过了",
                    "lso.body.items()");
        }
        JsonHealingConsumable data = spot.heal;
        int charges = data.healingCharges;
        List<BodyPartEnum> use = new ArrayList<>();
        for (BodyPartEnum p : pickParts(c, asked, charges)) {
            float deficit = c.getBodyPartDamage(p);
            if (deficit <= 0.05f) {
                continue;
            }
            float covered = c.getRemainingHealingTicks(p) * c.getHealingPerTicks(p);
            if (covered >= deficit * 0.99f) {
                continue;
            }
            use.add(p);
        }
        if (use.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (BodyPartEnum p : parts) {
                names.add(zhPart(p));
            }
            throw new ApiError(ErrorKind.FAILED, "「" + String.join("、", names)
                    + "」正在长回来，用不着再上一剂（东西也没动）", "lso.body.limbs()");
        }

        final String usedName = Zh.name(spot.stack);
        final String where = spot.where;
        final ResourceLocation id = spot.id;
        final int beforeTotal = totalOf(her, id);
        ItemStack one = readyOne(her, spot);
        if (one == null) {
            throw new ApiError(ErrorKind.FAILED, "拿不出" + usedName + "（" + where + "不让她拿）", null);
        }
        // 上一次没走完的「使用」要先收掉，不然原版还挂着上一件东西的使用状态，
        // 这一件会立刻被判成「用完了」（实测有过 0.4 秒就回执的情况，动画没播出来）。
        Show.cancel(her);
        Show.stop(her);
        Show.hold(her, one);
        if (!Show.use(her)) {
            stash(her, one);
            throw new ApiError(ErrorKind.FAILED, "没拿稳" + usedName + "，没能开始上药（东西已经放回背包）", null);
        }

        final List<BodyPartEnum> limbs = use;
        final JsonHealingConsumable healData = data;
        final Pending<Healed> out = Pending.create();
        final boolean[] once = {false};
        Runnable finish = () -> {
            if (once[0]) {
                return;
            }
            once[0] = true;
            try {
                String eff = "没有";
                if (healData.healingCharges > 0) {
                    // 创可贴/绷带这类：原版的 finishUsingItem 在服务端什么也不做（它要玩家在客户端界面里挑部位），
                    // 也不扣物品，所以这两步得我们自己来。
                    Show.sound(her, SoundRegistry.HEAL_BODY_PART.get(), 1.0f, 1.0f);
                    if (healData.recoveryEffectDuration > 0) {
                        her.m_7292_(new MobEffectInstance(MobEffectRegistry.RECOVERY.get(),
                                healData.recoveryEffectDuration, healData.recoveryEffectAmplifier, false, false, true));
                        eff = String.format(Locale.ROOT, "%d 秒 %d 级",
                                (int) (healData.recoveryEffectDuration / 20.0), healData.recoveryEffectAmplifier + 1);
                    }
                    for (BodyPartEnum p : limbs) {
                        BodyDamageUtil.applyHealingTimeBodyPart(her, p, healData.healingValue, healData.healingTime);
                    }
                    ItemStack hand = her.m_21205_();
                    if (!hand.m_41619_()) {
                        hand.m_41774_(1);
                    }
                }
                int afterTotal = totalOf(her, id);
                List<String> limbNames = new ArrayList<>();
                for (BodyPartEnum p : limbs) {
                    limbNames.add(zhPart(p));
                }
                StringBuilder line = new StringBuilder();
                line.append("用了一剂").append(usedName).append("（").append(where).append("），治了")
                        .append(String.join("、", limbNames));
                if (healData.healingTime > 0) {
                    line.append("，").append(String.format(Locale.ROOT, "%.1f", healData.healingTime / 20.0))
                            .append(" 秒长回来");
                }
                if (beforeTotal - afterTotal > 1) {
                    line.append("。注意：这一下掉了 ").append(beforeTotal - afterTotal).append(" 个（本来只该掉 1 个）");
                }
                line.append("。");
                out.complete(new Healed(usedName, where, eff, limbNames,
                        (double) her.m_21223_(), (double) her.m_21233_(), afterTotal, line.toString()));
            } catch (Throwable t) {
                out.fail(new ApiError(ErrorKind.FAILED, "上药的时候游戏报错了：" + t, null));
            }
        };
        Show.onFinish(her, finish);
        Show.after(her, useTicks(one) + 60, finish);
        return out;
    }

    // ---------------------------------------------------------------- 喝

    /** 喝什么。 */
    public record Drink(
            @Doc("只喝这种，例如 minecraft:potion；不写就喝身上第一种能喝的。")
            @Omitted("喝身上第一种能喝的")
            Optional<String> id) {
    }

    /** 喝完之后。 */
    @Doc("喝了一口之后的样子。")
    public record Drank(
            @Doc("喝了什么，例如「水」。") String item,
            @Doc("喝之前在哪个格子。") String from,
            @Doc("喝之前的水分。") int before,
            @Doc("喝之后的水分。") int after,
            @Doc("水分上限。") int max,
            @Doc("这种东西身上还剩几个。") int left,
            @Doc("一句话说明。") String line) {
    }

    @Fn("喝一口水或者饮料。她会把瓶子真的拿到手上喝（客户端看得见动作和声音），喝完了才补水分、才还玻璃瓶。")
    @Example("lso.body.drink()")
    @Example("lso.body.drink({id = \"minecraft:potion\"})")
    @Note("会消耗一件，不需要问主人。")
    @Note("水分满了只拦「能补水的饮料」；药水、牛奶、蜂蜜这类是拿来要效果的，水分满了一样能喝（游戏里也是这样）。")
    @Note("玻璃瓶由游戏自己还（生存大修的瓶装饮料、原版药水、蜂蜜瓶都会），塞不回背包就掉在脚边。")
    @Note("这条路和真人一模一样：举起来喝，喝满时间（药水约 1.6 秒）才生效——这个调用会等那一会儿再回结果。")
    @SeeAlso({"lso.body.vitals", "lso.body.items"})
    public static Pending<Drank> drink(ServerCall call, Drink args) {
        NumenPlayer her = call.her();
        ThirstCapability t = CapabilityUtil.getThirstCapability(her);
        if (t == null) {
            throw new ApiError(ErrorKind.NOT_FOUND, "读不到她的水分（生存大修没装好，或者这个存档关了水分）", null);
        }
        boolean active;
        try {
            active = ThirstUtil.isThirstActive(her);
        } catch (Throwable ignored) {
            active = true;
        }
        if (!active) {
            throw new ApiError(ErrorKind.FAILED, "这个存档里水分系统是关着的，喝什么都补不上", null);
        }
        // 先把 id 校验掉：写错的 id 永远该报「没这个物品」，不能被满水、没水这些东西挡在前面。
        String wanted = args.id().orElse("").trim();
        if (!wanted.isEmpty() && !ForgeRegistries.ITEMS.containsKey(new ResourceLocation(wanted))) {
            throw new ApiError(ErrorKind.BAD_ARGUMENT, "游戏里没有这个物品：" + wanted,
                    "写全名，例如 minecraft:potion；lso.body.items() 里 kind = drink 的都能喝");
        }
        int before = t.getHydrationLevel();
        Spot spot = firstDrink(her, wanted);
        if (spot == null) {
            throw new ApiError(ErrorKind.NO_MATERIAL, wanted.isEmpty()
                    ? "她身上没有能喝的东西（水瓶、饮料、水壶都算）——物品栏、快捷栏、饰品栏和背包都翻过了"
                    : "她身上没有能喝的 " + wanted,
                    "lso.body.items()");
        }
        JsonThirstConsumable data = spot.drink;
        final int adds = data == null ? 0 : data.hydration;
        // 只有「纯喝水」才拦满水。生存大修的数据表把 minecraft:potion 整个当成能补水 6 的饮料，
        // 不分里面装的是什么药水；所以还要再看一眼这瓶东西自己带不带效果——治疗药水水分满了也得能喝。
        final boolean effectOnly = hasOwnEffect(spot.stack);
        if (adds > 0 && !effectOnly && before >= ThirstCapability.MAX_HYDRATION) {
            throw new ApiError(ErrorKind.FAILED, "水分已经满了（" + before + "/" + ThirstCapability.MAX_HYDRATION
                    + "），先别喝，留着路上用", null);
        }
        final String itemName = Zh.name(spot.stack);
        final String where = spot.where;
        final ResourceLocation id = spot.id;
        final int beforeTotal = totalOf(her, id);
        ItemStack one = readyOne(her, spot);
        if (one == null) {
            throw new ApiError(ErrorKind.FAILED, "拿不出" + itemName + "（" + where + "不让她拿）", null);
        }
        // 上一次没走完的「使用」要先收掉，不然原版还挂着上一件东西的使用状态，
        // 这一件会立刻被判成「用完了」（实测有过 0.4 秒就回执的情况，动画没播出来）。
        Show.cancel(her);
        Show.stop(her);
        Show.hold(her, one);
        if (!Show.use(her)) {
            stash(her, one);
            throw new ApiError(ErrorKind.FAILED, "没拿稳" + itemName + "，没能开始喝（东西已经放回背包）", null);
        }

        final Pending<Drank> out = Pending.create();
        final boolean[] once = {false};
        Runnable finish = () -> {
            if (once[0]) {
                return;
            }
            once[0] = true;
            try {
                int after = t.getHydrationLevel();
                int left = totalOf(her, id);
                String line;
                if (adds > 0) {
                    line = "喝了" + itemName + "（" + where + "），水分 " + before + " → " + after + " / "
                            + ThirstCapability.MAX_HYDRATION;
                    if (after <= before) {
                        line += effectOnly
                                ? "（水分本来就满了，这瓶主要是拿它的药效）"
                                : "（这一口没补上多少，可能已经接近上限了）";
                    }
                } else {
                    line = "喝了" + itemName + "（" + where + "），这件不补水，是拿它的效果";
                }
                if (beforeTotal - left > 1) {
                    line += "。注意：这一下掉了 " + (beforeTotal - left) + " 个（本来只该掉 1 个）";
                }
                out.complete(new Drank(itemName, where, before, after, ThirstCapability.MAX_HYDRATION, left,
                        line + "。"));
            } catch (Throwable e) {
                out.fail(new ApiError(ErrorKind.FAILED, "喝水的时候游戏报错了：" + e, null));
            }
        };
        Show.onFinish(her, finish);
        Show.after(her, useTicks(one) + 60, finish);
        return out;
    }

    // ---- 下面都是内部实现 ----

    /** 这瓶东西自己带不带效果（药水的药效、食物的附加效果）。纯水不算。 */
    private static boolean hasOwnEffect(ItemStack st) {
        try {
            if (st == null || st.m_41619_()) {
                return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
        try {
            if (!net.minecraft.world.item.alchemy.PotionUtils.m_43547_(st).isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            net.minecraft.world.food.FoodProperties food = st.m_41720_().m_41473_();
            if (food != null && !food.m_38749_().isEmpty()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 把她要用那一件从原处拿出来（原处按规矩扣掉），手上有别的东西先收进背包。 */
    private static ItemStack readyOne(ServerPlayer her, Spot sp) {
        ItemStack one = sp.stack.m_41777_();
        one.m_41764_(1);
        if (sp.onHand) {
            ItemStack rest = sp.stack.m_41777_();
            rest.m_41774_(1);
            her.m_21008_(InteractionHand.MAIN_HAND, ItemStack.f_41583_);
            if (!rest.m_41619_()) {
                // 别用普通的「塞背包」：手里那格就是选中的快捷栏格，塞回去之后马上被手上那一件盖掉，
                // 剩下的一摞就凭空没了（实测 3 瓶药水一口下去全没了）。
                stashAside(her, rest);
            }
            return one;
        }
        ItemStack old = her.m_21205_();
        if (!old.m_41619_()) {
            her.m_21008_(InteractionHand.MAIN_HAND, ItemStack.f_41583_);
            stash(her, old.m_41777_());
        }
        if (!takeOne(her, sp)) {
            return null;
        }
        return one;
    }

    /** 塞进「不是手里那格」的空位：先按同物品叠，再找空格，实在没有就掉脚边。 */
    private static void stashAside(ServerPlayer her, ItemStack st) {
        if (st == null || st.m_41619_()) {
            return;
        }
        Inventory bag = her.m_150109_();
        int sel = bag.f_35977_;
        for (int i = 0; i < bag.m_6643_(); i++) {
            if (i == sel) {
                continue;
            }
            ItemStack slot = bag.m_8020_(i);
            if (!slot.m_41619_() && slot.m_150930_(st.m_41720_())
                    && ItemStack.m_150942_(slot, st) && slot.m_41613_() < slot.m_41741_()) {
                int room = slot.m_41741_() - slot.m_41613_();
                int move = Math.min(room, st.m_41613_());
                slot.m_41764_(slot.m_41613_() + move);
                st.m_41774_(move);
                if (st.m_41619_()) {
                    return;
                }
            }
        }
        for (int i = 0; i < bag.m_6643_(); i++) {
            if (i == sel) {
                continue;
            }
            if (bag.m_8020_(i).m_41619_()) {
                bag.m_6836_(i, st);
                return;
            }
        }
        stash(her, st);
    }

    /** 背包塞得下就塞背包，塞不下掉在脚边。 */
    private static void stash(ServerPlayer her, ItemStack st) {
        if (st == null || st.m_41619_()) {
            return;
        }
        try {
            if (her.m_150109_().m_36054_(st)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Containers.m_18992_(her.m_9236_(), her.m_20185_(), her.m_20186_(), her.m_20189_(), st);
        } catch (Throwable ignored) {
        }
    }

    /** 这件东西要举多久（刻）。原版用它算「正在使用」还剩多久。 */
    private static int useTicks(ItemStack one) {
        try {
            int t = one.m_41720_().m_8105_(one);
            if (t <= 0) {
                return 1;
            }
            return Math.min(t, 200);
        } catch (Throwable ignored) {
            return 32;
        }
    }

    private static String zhPart(BodyPartEnum p) {
        switch (p) {
            case HEAD:
                return "头";
            case CHEST:
                return "胸";
            case LEFT_ARM:
                return "左臂";
            case RIGHT_ARM:
                return "右臂";
            case LEFT_LEG:
                return "左腿";
            case RIGHT_LEG:
                return "右腿";
            case LEFT_FOOT:
                return "左脚";
            case RIGHT_FOOT:
                return "右脚";
            default:
                return p.name();
        }
    }

    /** 体温区间的中文名。枚举只有五个值：FROSTBITE / COLD / NORMAL / HOT / HEAT_STROKE。 */
    private static String zhState(String id) {
        switch (id.toUpperCase(Locale.ROOT)) {
            case "NORMAL":
                return "正常";
            case "COLD":
                return "偏冷";
            case "HOT":
                return "偏热";
            case "FROSTBITE":
                return "冻伤";
            case "HEAT_STROKE":
                return "中暑";
            default:
                return id;
        }
    }

    /** 体温修正项的中文名：按注册表里的键后半段查。 */
    private static final Map<String, String> CAUSE_NAMES = new HashMap<>();

    static {
        CAUSE_NAMES.put("altitude", "海拔");
        CAUSE_NAMES.put("attribute", "装备加成");
        CAUSE_NAMES.put("biome", "生物群系");
        CAUSE_NAMES.put("blocks", "身边的方块");
        CAUSE_NAMES.put("dimension", "所在维度");
        CAUSE_NAMES.put("mount", "坐骑");
        CAUSE_NAMES.put("freeze", "冻伤");
        CAUSE_NAMES.put("on_fire", "身上着火");
        CAUSE_NAMES.put("player_huddling", "一起取暖");
        CAUSE_NAMES.put("sprint", "刚跑过");
        CAUSE_NAMES.put("time", "时间（昼夜）");
        CAUSE_NAMES.put("weather", "天气");
        CAUSE_NAMES.put("wetness", "泡在水里");
        CAUSE_NAMES.put("item_attribute", "随身物品的属性");
        CAUSE_NAMES.put("coat_attribute", "衣服的保暖");
        CAUSE_NAMES.put("temperature_resistance", "耐热耐寒");
        CAUSE_NAMES.put("mount_dynamic", "骑着的坐骑");
        CAUSE_NAMES.put("serene_seasons", "季节");
        CAUSE_NAMES.put("ecliptic_seasons", "季节");
        CAUSE_NAMES.put("world_temperature_terra_firma_craft", "群系温度");
        CAUSE_NAMES.put("item_temperature_terra_firma_craft", "物品温度");
        CAUSE_NAMES.put("origins", "出身");
        CAUSE_NAMES.put("origins_resistance", "出身的耐性");
    }

    /**
     * 体温是被谁拉偏的。走的是生存大修自己算 {@code getPlayerTargetTemperature} 时用的那批修正项，
     * 所以数字能和它给的 {@code targetTemperature} 对上。
     */
    private static List<String> causes(ServerPlayer her) {
        List<String> out = new ArrayList<>();
        try {
            IForgeRegistry<ModifierBase> reg = TemperatureModifierRegistry.MODIFIERS_REGISTRY.get();
            if (reg == null) {
                return out;
            }
            BlockPos pos = her.m_20183_();
            Level level = her.m_9236_();
            List<double[]> values = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (ModifierBase m : reg.getValues()) {
                float v = 0.0f;
                try {
                    v += m.getWorldInfluence(her, level, pos);
                } catch (Throwable ignored) {
                }
                try {
                    v += m.getPlayerInfluence(her);
                } catch (Throwable ignored) {
                }
                if (Math.abs(v) < 0.05f) {
                    continue;
                }
                ResourceLocation key = reg.getKey(m);
                String path = key == null ? "?" : key.m_135815_();
                String label = CAUSE_NAMES.get(path);
                if (label == null) {
                    label = path;
                }
                values.add(new double[] {v});
                labels.add(label);
            }
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < values.size(); i++) {
                order.add(i);
            }
            order.sort(Comparator.comparingDouble((Integer i) -> -Math.abs(values.get(i)[0])));
            for (int k = 0; k < order.size() && k < 4; k++) {
                int i = order.get(k);
                double v = values.get(i)[0];
                out.add(String.format(Locale.ROOT, "%s %+.1f（往%s里拉）", labels.get(i), v,
                        v > 0 ? "热" : "冷"));
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static String effName(MobEffect eff) {
        if (eff == null) {
            return "未知";
        }
        try {
            return Zh.name(eff.m_19481_());
        } catch (Throwable ignored) {
            return String.valueOf(ForgeRegistries.MOB_EFFECTS.getKey(eff));
        }
    }

    private static IBodyDamageCapability cap(ServerPlayer bot) {
        return CapabilityUtil.getBodyDamageCapability(bot);
    }

    private static double health(IBodyDamageCapability c, BodyPartEnum p) {
        return Math.max(0.0, c.getBodyPartMaxHealth(p) - c.getBodyPartDamage(p));
    }

    /** 别用 {@code m_41776_} 当数量；1.20.1 的 SRG 里那不是 getCount。 */
    private static int count(ItemStack st) {
        try {
            return st == null ? 0 : st.m_41613_();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 一件能用的东西可能在什么地方。 */
    private static final class Spot {
        final ItemStack stack;
        final ResourceLocation id;
        final JsonHealingConsumable heal;
        final JsonThirstConsumable drink;
        final IItemHandler holder;
        final int holderSlot;
        final String where;
        /** 在主手上（使用之前不用挪位置）。 */
        final boolean onHand;

        Spot(ItemStack stack, ResourceLocation id, JsonHealingConsumable heal, JsonThirstConsumable drink,
                IItemHandler holder, int holderSlot, String where) {
            this.stack = stack;
            this.id = id;
            this.heal = heal;
            this.drink = drink;
            this.holder = holder;
            this.holderSlot = holderSlot;
            this.where = where;
            this.onHand = holder == null && holderSlot == -1 && "主手".equals(where);
        }
    }

    private static Spot spotOf(ItemStack st, IItemHandler holder, int holderSlot, String where) {
        if (st == null || st.m_41619_()) {
            return null;
        }
        ResourceLocation id;
        try {
            id = ForgeRegistries.ITEMS.getKey(st.m_41720_());
        } catch (Throwable t) {
            return null;
        }
        if (id == null) {
            return null;
        }
        JsonHealingConsumable heal = null;
        try {
            heal = BodyDamageDataManager.getHealingItem(id);
        } catch (Throwable ignored) {
        }
        JsonThirstConsumable drink = null;
        try {
            if (st.m_41780_() == UseAnim.DRINK) {
                drink = ThirstDataManager.getConsumable(st);
                if (drink != null && drink.hydration <= 0) {
                    drink = null;
                }
            }
        } catch (Throwable ignored) {
        }
        if (heal == null && drink == null) {
            return null;
        }
        return new Spot(st, id, heal, drink, holder, holderSlot, where);
    }

    /** 物品栏 41 格 + 饰品栏 + 物品栏里每件带格子的东西（只翻一层）。 */
    private static List<Spot> spots(ServerPlayer bot) {
        List<Spot> out = new ArrayList<>();
        int budget = MAX_SCAN;
        try {
            Inventory inv = bot.m_150109_();
            for (int slot = 0; slot < 41 && budget > 0; slot++) {
                budget--;
                try {
                    Spot sp = spotOf(inv.m_8020_(slot), null, -1, whereOf(bot, slot));
                    if (sp != null) {
                        out.add(sp);
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        out.addAll(wornSpots(bot));
        out.addAll(carriedSpots(bot, budget));
        return out;
    }

    /** 装在哪：主手、快捷栏第几格、物品栏第几格、护甲、副手。 */
    private static String whereOf(ServerPlayer bot, int slot) {
        int selected = -1;
        try {
            selected = bot.m_150109_().f_35977_;
        } catch (Throwable ignored) {
        }
        if (slot == selected) {
            return "第" + (slot + 1) + "格（正拿在手上）";
        }
        if (slot < 9) {
            return "快捷栏第" + (slot + 1) + "格";
        }
        if (slot < 36) {
            return "物品栏第" + (slot - 8) + "格";
        }
        if (slot < 40) {
            return "护甲栏第" + (slot - 35) + "格";
        }
        return "副手";
    }

    /** 饰品栏里挂着的容器（背包挂饰品味之类）。 */
    private static List<Spot> wornSpots(ServerPlayer bot) {
        List<Spot> out = new ArrayList<>();
        try {
            if (!ModList.get().isLoaded("curios")) {
                return out;
            }
            ICuriosItemHandler handler = CuriosApi.getCuriosInventory(bot).orElse(null);
            if (handler == null) {
                return out;
            }
            for (var stacksHandler : handler.getCurios().values()) {
                var stacks = stacksHandler.getStacks();
                if (stacks == null) {
                    continue;
                }
                for (int i = 0; i < stacks.getSlots(); i++) {
                    ItemStack worn = stacks.getStackInSlot(i);
                    if (worn == null || worn.m_41619_()) {
                        continue;
                    }
                    IItemHandler inside = insideOf(worn);
                    if (inside == null) {
                        continue;
                    }
                    String label = "饰品栏里的" + Zh.name(worn);
                    for (int j = 0; j < inside.getSlots(); j++) {
                        try {
                            Spot sp = spotOf(inside.getStackInSlot(j), inside, j,
                                    label + "第" + (j + 1) + "格");
                            if (sp != null) {
                                out.add(sp);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /**
     * 放在物品栏里的背包/箱子背包里面的东西。整合包里背包常常就在物品栏里，不翻它们，
     * 她出门前塞进背包的药就等于不存在。
     */
    private static List<Spot> carriedSpots(ServerPlayer bot, int budget) {
        List<Spot> out = new ArrayList<>();
        try {
            Inventory inv = bot.m_150109_();
            for (int slot = 0; slot < 41 && budget > 0; slot++) {
                ItemStack bag = inv.m_8020_(slot);
                if (bag == null || bag.m_41619_()) {
                    continue;
                }
                IItemHandler inside = insideOf(bag);
                if (inside == null) {
                    continue;
                }
                String label = whereOf(bot, slot) + "的" + Zh.name(bag);
                for (int j = 0; j < inside.getSlots() && budget > 0; j++) {
                    budget--;
                    try {
                        Spot sp = spotOf(inside.getStackInSlot(j), inside, j,
                                label + "第" + (j + 1) + "格");
                        if (sp != null) {
                            out.add(sp);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    private static IItemHandler insideOf(ItemStack stack) {
        try {
            return ((ICapabilityProvider) (Object) stack)
                    .getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 手上优先，其次物品栏，最后背包里的。 */
    private static Spot find(ServerPlayer bot) {
        try {
            Spot inHand = spotOf(bot.m_21205_(), null, -1, "主手");
            if (inHand != null && inHand.heal != null) {
                return inHand;
            }
            Spot offHand = spotOf(bot.m_21206_(), null, -1, "副手");
            if (offHand != null && offHand.heal != null) {
                return offHand;
            }
        } catch (Throwable ignored) {
        }
        for (Spot sp : spots(bot)) {
            if (sp.heal != null) {
                return sp;
            }
        }
        return null;
    }

    private static Spot firstDrink(ServerPlayer bot, String wanted) {
        try {
            for (ItemStack hand : new ItemStack[] {bot.m_21205_(), bot.m_21206_()}) {
                Spot sp = spotOf(hand, null, -1, hand == bot.m_21205_() ? "主手" : "副手");
                if (sp != null && sp.drink != null && matches(sp.id, wanted)) {
                    return sp;
                }
            }
        } catch (Throwable ignored) {
        }
        for (Spot sp : spots(bot)) {
            if (sp.drink != null && matches(sp.id, wanted)) {
                return sp;
            }
        }
        return null;
    }

    private static boolean matches(ResourceLocation id, String wanted) {
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        if (id.toString().equalsIgnoreCase(wanted)) {
            return true;
        }
        return !wanted.contains(":") && id.m_135815_().equalsIgnoreCase(wanted);
    }

    /** 从她身上拿走一件。成功返回 true。 */
    private static boolean takeOne(ServerPlayer bot, Spot sp) {
        int have = count(sp.stack);
        if (have <= 0) {
            return false;
        }
        if (sp.holder != null) {
            try {
                ItemStack got = sp.holder.extractItem(sp.holderSlot, 1, false);
                int n = count(got);
                if (n >= 1) {
                    if (n > 1) {
                        ItemStack back = got.m_41777_();
                        back.m_41764_(n - 1);
                        try {
                            sp.holder.insertItem(sp.holderSlot, back, false);
                        } catch (Throwable ignored) {
                        }
                    }
                    return true;
                }
            } catch (Throwable ignored) {
            }
            return false;
        }
        sp.stack.m_41764_(have - 1);
        return true;
    }

    /** 把刚才拿走的那件放回去（疗伤/喝水报错时用）。 */
    private static void putBack(ServerPlayer bot, Spot sp) {
        ItemStack one = sp.stack.m_41777_();
        one.m_41764_(1);
        try {
            if (sp.holder != null) {
                ItemStack rest = sp.holder.insertItem(sp.holderSlot, one, false);
                if (rest.m_41619_()) {
                    return;
                }
                one = rest;
            }
        } catch (Throwable ignored) {
        }
        try {
            if (bot.m_150109_().m_36054_(one)) {
                return;
            }
        } catch (Throwable ignored) {
        }
        try {
            Containers.m_18992_(bot.m_9236_(), bot.m_20185_(), bot.m_20186_(), bot.m_20189_(), one);
        } catch (Throwable ignored) {
        }
    }


    private static int totalOf(ServerPlayer bot, ResourceLocation id) {
        int n = 0;
        for (Spot sp : spots(bot)) {
            if (sp.id.equals(id)) {
                n += count(sp.stack);
            }
        }
        return n;
    }

    /**
     * 挑要治的部位。点名的优先；没点名就按「伤得重、没在长回来」排。
     * {@code charges > 0} 时最多取这么多个。
     */
    private static List<BodyPartEnum> pickParts(IBodyDamageCapability c, String arg, int charges) {
        List<BodyPartEnum> out = new ArrayList<>();
        if (arg != null && !arg.isEmpty() && !arg.equalsIgnoreCase("auto") && !arg.equalsIgnoreCase("worst")) {
            try {
                BodyPartEnum p = BodyPartEnum.get(arg.toUpperCase(Locale.ROOT));
                if (p != null) {
                    out.add(p);
                    return out;
                }
            } catch (Throwable ignored) {
            }
            return out;
        }
        if (c == null) {
            return out;
        }
        List<BodyPartEnum> wounded = new ArrayList<>();
        for (BodyPartEnum p : BodyPartEnum.values()) {
            if (c.getBodyPartDamage(p) > 0.0f) {
                wounded.add(p);
            }
        }
        wounded.sort((a, b) -> Float.compare(c.getBodyPartHealthRatio(a), c.getBodyPartHealthRatio(b)));
        int take = charges > 0 ? Math.min(charges, wounded.size()) : wounded.size();
        for (int i = 0; i < take; i++) {
            out.add(wounded.get(i));
        }
        return out;
    }
}
