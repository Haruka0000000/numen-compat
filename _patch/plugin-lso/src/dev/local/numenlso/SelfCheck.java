package dev.local.numenlso;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;
import org.apache.commons.lang3.tuple.Pair;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.BodyDamageUtil;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.BodyPartEnum;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.IBodyDamageCapability;
import sfiomn.legendarysurvivaloverhaul.api.bodydamage.MalusBodyPartEnum;
import sfiomn.legendarysurvivaloverhaul.api.data.manager.ThirstDataManager;
import sfiomn.legendarysurvivaloverhaul.common.capabilities.temperature.TemperatureCapability;
import sfiomn.legendarysurvivaloverhaul.common.capabilities.thirst.ThirstCapability;
import sfiomn.legendarysurvivaloverhaul.util.CapabilityUtil;

import java.util.List;

/**
 * One line into the body state with the four things LSO tracks and numen's own
 * runtime_state does not: hydration, body temperature, the worst wounded limb, and the
 * debuff a wound is pinning on her.
 *
 * <p>She never called {@code lso.body.vitals()} in a whole session and died of
 * dehydration nine times, so the numbers have to arrive without being asked for.
 */
final class SelfCheck {

    /** Below this hydration the line starts telling her to drink. */
    private static final int DRINK_AT = 10;

    /** Hard cap on the whole segment; the advice at the end always survives. */
    private static final int MAX_CHARS = 200;

    private SelfCheck() {
    }

    static String stateLine(NumenPlayer bot) {
        boolean any = false;
        StringBuilder sb = new StringBuilder(" | 自检");

        ThirstCapability thirst = CapabilityUtil.getThirstCapability(bot);
        int hydration = -1;
        if (thirst != null) {
            hydration = thirst.getHydrationLevel();
            sb.append(" 水").append(hydration).append("/20");
            int drinks = drinks(bot.m_150109_());
            if (drinks > 0) {
                sb.append("(能喝").append(drinks).append(")");
            }
            any = true;
        }

        // Infection first: it is the only debuff in this pack that kills on a timer, and numen's
        // own <effects> block prints the bare registry path ("infected 267s left") with no hint
        // that a golden apple stops it.
        Infection infection = infection(bot);
        if (infection != null) {
            sb.append(infection.text);
            any = true;
        }

        TemperatureCapability temp = CapabilityUtil.getTempCapability(bot);
        String temperature = null;
        if (temp != null) {
            temperature = String.valueOf(temp.getTemperatureEnum());
            sb.append(" 温").append(temperature);
            sb.append(' ').append(oneDecimal(temp.getTemperatureLevel()));
            any = true;
        }

        IBodyDamageCapability body = CapabilityUtil.getBodyDamageCapability(bot);
        String worst = null;
        String malus = null;
        boolean limbDropped = false;
        if (body != null) {
            BodyPartEnum worstPart = null;
            double worstRatio = 1.0D;
            for (BodyPartEnum p : BodyPartEnum.values()) {
                double max = body.getBodyPartMaxHealth(p);
                if (max <= 0.0D) {
                    continue;
                }
                double ratio = Math.max(0.0D, (max - body.getBodyPartDamage(p)) / max);
                if (ratio < worstRatio) {
                    worstRatio = ratio;
                    worstPart = p;
                }
            }
            if (worstPart != null) {
                worst = worstPart.name();
                limbDropped = worstRatio >= 0.75D;   // only worth a token when it actually hurts
                if (!limbDropped) {
                    sb.append(" 伤").append(worst).append(' ')
                            .append(Math.round(worstRatio * 100.0D)).append('%');
                }
                any = true;
            }
            malus = firstMalus(body);
            if (malus != null && !limbDropped) {
                sb.append(" 减益").append(malus);
            }
        }

        if (!any) {
            return "";
        }
        String advice = advice(hydration, temperature, worst, malus, infection);
        if (advice != null) {
            sb.append(" —— ").append(advice);
        }
        String line = sb.toString();
        return line.length() > MAX_CHARS ? line.substring(0, MAX_CHARS) : line;
    }

    /** A running infection: which one, how long until the next stage, what stops it. */
    private static final class Infection {
        final String text;
        final String advice;

        Infection(String text, String advice) {
            this.text = text;
            this.advice = advice;
        }
    }

    /**
     * Hordes' {@code effect.hordes.infected} (curable: golden apple / enchanted golden apple, see
     * the {@code hordes:infection_cures} tag) and Spore's {@code effect.spore.mycelium_ef}.
     *
     * <p>Hordes config: {@code ticksForEffectStage = 8000} — four stages, ~400 s each, then a zombie
     * player spawns. So the countdown is not decoration; it is the death clock.
     */
    private static Infection infection(NumenPlayer bot) {
        for (var instance : bot.m_21220_()) {
            var effect = instance.m_19544_();
            if (effect == null) {
                continue;
            }
            var key = ForgeRegistries.MOB_EFFECTS.getKey(effect);
            if (key == null) {
                continue;
            }
            String id = String.valueOf(key);
            String path = id.substring(id.indexOf(':') + 1);
            int seconds = instance.m_19557_() / 20;
            if ("infected".equals(path)) {
                return new Infection(" 感染Hordes" + seconds + "s",
                        "带感染了，吃金苹果解（附魔金苹果也行），别拖到变僵尸");
            }
            if ("mycelium_ef".equals(path)) {
                int amp = instance.m_19564_();
                return new Infection(" 菌丝感染" + (amp + 1) + "级" + seconds + "s",
                        amp <= 0
                                ? "身上在长菌丝（1级不掉血，只更饿）：喝牛奶清掉，别贴脸也别踩尸堆"
                                : "菌丝感染" + (amp + 1) + "级正在掉血：立刻脱战，喝牛奶清掉（牛奶会清光正面效果）");
            }
        }
        return null;
    }

    /** The one thing that will kill her soonest, in her language. */
    private static String advice(int hydration, String temperature, String worst, String malus,
                                 Infection infection) {
        if (infection != null) {
            return infection.advice;
        }
        if (hydration >= 0 && hydration <= 2) {
            return "快渴死了，立刻喝水";
        }
        if (hydration >= 0 && hydration <= 4) {
            return "水快没了，先喝水再打";
        }
        if (temperature != null && !"NORMAL".equals(temperature)) {
            return "COLD".equals(temperature) || "FROSTBITE".equals(temperature)
                    ? "太冷，找热源或穿暖和再走"
                    : "太热，找阴凉、下水或脱层衣服";
        }
        if (worst != null) {
            return "伤在 " + worst + "，先包扎能走再包扎重伤";
        }
        if (malus != null) {
            return "身上挂着减益，那是伤口带来的，治伤才会消";
        }
        if (hydration >= 0 && hydration <= DRINK_AT) {
            return "该喝水了";
        }
        return null;
    }

    /** First debuff a wound group is pinning on her, e.g. {@code slowness I}. */
    private static String firstMalus(IBodyDamageCapability body) {
        for (MalusBodyPartEnum group : MalusBodyPartEnum.values()) {
            float ratio = body.getHealthRatioForMalusBodyPart(group);
            for (Pair<MobEffect, Integer> hit : BodyDamageUtil.getEffects(group, ratio)) {
                MobEffect mob = hit.getLeft();
                if (mob == null) {
                    continue;
                }
                String id = String.valueOf(ForgeRegistries.MOB_EFFECTS.getKey(mob));
                int amplifier = hit.getRight() == null ? 0 : hit.getRight();
                return id.substring(id.indexOf(':') + 1) + ' ' + roman(amplifier);
            }
        }
        return null;
    }

    private static String roman(int amplifier) {
        switch (amplifier) {
            case 0:
                return "I";
            case 1:
                return "II";
            case 2:
                return "III";
            case 3:
                return "IV";
            default:
                return String.valueOf(amplifier + 1);
        }
    }

    /** How many items in her inventory would actually restore hydration. */
    private static int drinks(Inventory inventory) {
        int count = 0;
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = inventory.m_8020_(slot);
            if (stack.m_41619_()) {
                continue;
            }
            if (isDrink(stack)) {
                count += stack.m_41613_();
            }
        }
        return count;
    }

    private static boolean isDrink(ItemStack stack) {
        var consumable = ThirstDataManager.getConsumable(stack);
        return consumable != null && consumable.hydration > 0;
    }

    private static String oneDecimal(float value) {
        return String.valueOf(Math.round(value * 10.0F) / 10.0F);
    }
}
