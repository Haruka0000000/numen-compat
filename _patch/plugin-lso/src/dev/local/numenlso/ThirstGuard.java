package dev.local.numenlso;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import sfiomn.legendarysurvivaloverhaul.api.data.json.JsonThirstConsumable;
import sfiomn.legendarysurvivaloverhaul.api.data.manager.ThirstDataManager;
import sfiomn.legendarysurvivaloverhaul.api.thirst.ThirstUtil;
import sfiomn.legendarysurvivaloverhaul.common.capabilities.thirst.ThirstCapability;
import sfiomn.legendarysurvivaloverhaul.util.CapabilityUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * Drink before the dehydration damage starts, not after.
 *
 * <p>LSO's exhaustion numbers are what kill her: 0.25 hydration per swing, 0.075 per block broken,
 * 0.03 every 10 ticks just standing there. Twenty points is eighty swings — a single spawner fight
 * empties the bar in under a minute. The observed failure was nine deaths in ninety minutes, every
 * one of them preceded by a drink taken only after the damage had already started.
 *
 * <p>She cannot be relied on to call {@code lso.body.vitals()} on a timer: the model checks when it
 * thinks to check, and mid-fight it does not think to check. So the reflex does it, and only the
 * reflex — this is the same contract as {@code flow_guard}: {@link #canRun} is the only thing that
 * can hand the body back, so it must go false the moment the job is done.
 *
 * <p>The drink runs the real item use ({@code startUsingItem} on the main hand, driven to
 * completion by the body's own {@code doTick} → {@code aiStep}), which means the bottle turns into
 * glass, the sounds play and LSO's own consume listener applies the water. If that path does not
 * take within {@link #USE_TIMEOUT} ticks (a consumable that is not actually drinkable — an empty
 * canteen, say), it falls back to LSO's own {@code ThirstUtil.takeDrink} plus one item removed, and
 * blacklists that item for a while so the next attempt picks a different one.
 */
public final class ThirstGuard implements Task, com.dwinovo.numen.task.reflex.Reflex {

    public static final String ID = "thirst_guard";

    private static final Logger LOG = LoggerFactory.getLogger("numen_lso");

    /** Drink when hydration is at or below this (out of 20). Damage starts at 0. */
    private static final int DRINK_AT = 10;
    /** Give the native item use this long before falling back to LSO's own drink call. */
    private static final int USE_TIMEOUT = 60;
    /** Give up on one attempt after this long no matter what. */
    private static final int ATTEMPT_TIMEOUT = 200;
    /** How long a drink that did nothing stays out of the running. */
    private static final long BLACKLIST_MILLIS = 60_000L;

    private static final Map<String, Long> BLACKLIST = new HashMap<>();

    /** Swapped backpack slot, or -1 when the drink was already in the hotbar. */
    private int swapSlot = -1;
    private int swapHotbar = -1;
    private ItemStack swapSaved;
    private int savedSelected = -1;
    private boolean started;
    private boolean fellBack;
    private int ticks;
    private int startHydration;
    private String drinkingId = "";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "drink before the thirst damage starts";
    }

    @Override
    public String describe() {
        return "Hydration is low, so take a drink out and drink it; the body goes back to the "
                + "current task as soon as the bar is back up.";
    }

    @Override
    public boolean canRun(NumenPlayer bot) {
        ThirstCapability thirst = CapabilityUtil.getThirstCapability(bot);
        if (thirst == null) {
            return false;
        }
        if (thirst.getHydrationLevel() > DRINK_AT) {
            return false;
        }
        return pick(bot.m_150109_(), bot) >= 0;
    }

    @Override
    public TaskState tick(NumenPlayer bot) {
        ThirstCapability thirst = CapabilityUtil.getThirstCapability(bot);
        if (thirst == null) {
            return TaskState.CANCELLED;
        }
        if (thirst.getHydrationLevel() > DRINK_AT) {
            // Someone else topped her up (a teammate, a water block, or the earlier bottle landing
            // a tick late) — nothing to do, and `canRun` is already false, so the body goes back.
            release(bot);
            return TaskState.CANCELLED;
        }
        if (!started) {
            int slot = pick(bot.m_150109_(), bot);
            if (slot < 0) {
                return TaskState.CANCELLED;
            }
            startHydration = thirst.getHydrationLevel();
            drinkingId = idOf(bot.m_150109_().m_8020_(slot));
            if (!hold(bot, slot)) {
                LOG.warn("[numen_lso] thirst_guard could not hold {}", drinkingId);
                return TaskState.CANCELLED;
            }
            bot.m_6672_(InteractionHand.MAIN_HAND);
            started = true;
            ticks = 0;
            LOG.warn("[numen_lso] thirst_guard drinking {} at hydration {}/20",
                    drinkingId, startHydration);
            return TaskState.RUNNING;
        }
        ticks++;
        if (thirst.getHydrationLevel() > startHydration) {
            LOG.warn("[numen_lso] thirst_guard drank {}: hydration {}/20 -> {}/20",
                    drinkingId, startHydration, thirst.getHydrationLevel());
            release(bot);
            return TaskState.CANCELLED;
        }
        if (!fellBack && ticks >= USE_TIMEOUT) {
            // The native use never took (the item is not actually a drink — an empty canteen).
            ItemStack inHand = bot.m_21205_();
            if (!inHand.m_41619_()) {
                ThirstUtil.takeDrink(bot, inHand);
                bot.m_150109_().m_7407_(bot.m_150109_().f_35977_, 1);
                fellBack = true;
                LOG.warn("[numen_lso] thirst_guard fell back to takeDrink for {}", drinkingId);
                if (thirst.getHydrationLevel() > startHydration) {
                    release(bot);
                    return TaskState.CANCELLED;
                }
                return TaskState.RUNNING;
            }
        }
        if (ticks >= ATTEMPT_TIMEOUT) {
            blacklist(drinkingId);
            LOG.warn("[numen_lso] thirst_guard gave up on {} (hydration still {}/20)",
                    drinkingId, thirst.getHydrationLevel());
            release(bot);
            return TaskState.CANCELLED;
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer bot, Task.StopReason reason) {
        release(bot);
    }

    // ------------------------------------------------------------------ body state line

    /**
     * What {@code numen.contributeBodyState} appends to her state block: the hydration bar she
     * cannot see, plus how many drinks she is carrying. Kept to one short line — it is emitted
     * about twice a second.
     */

    // ------------------------------------------------------------------ internals

    /** The best drink she is carrying right now, or -1. Highest hydration wins. */
    private static int pick(Inventory inventory, NumenPlayer bot) {
        long now = System.currentTimeMillis();
        int best = -1;
        int bestHydration = 0;
        for (int slot = 0; slot < 41; slot++) {
            ItemStack stack = inventory.m_8020_(slot);
            if (stack.m_41619_()) {
                continue;
            }
            String id = idOf(stack);
            Long until = BLACKLIST.get(id);
            if (until != null && until > now) {
                continue;
            }
            JsonThirstConsumable drink = ThirstDataManager.getConsumable(stack);
            if (drink == null || drink.hydration <= 0) {
                continue;
            }
            if (drink.hydration > bestHydration) {
                bestHydration = drink.hydration;
                best = slot;
            }
        }
        return best;
    }

    /** Put the drink in the main hand, remembering what we disturbed. */
    private boolean hold(NumenPlayer bot, int slot) {
        Inventory inventory = bot.m_150109_();
        savedSelected = inventory.f_35977_;
        if (slot < 9) {
            inventory.f_35977_ = slot;
            swapSlot = -1;
            return true;
        }
        int hotbar = inventory.f_35977_;
        ItemStack drink = inventory.m_8020_(slot);
        ItemStack displaced = inventory.m_8020_(hotbar);
        if (drink.m_41619_()) {
            return false;
        }
        swapSaved = displaced;
        swapSlot = slot;
        swapHotbar = hotbar;
        inventory.m_6836_(hotbar, drink);
        inventory.m_6836_(slot, displaced);
        return true;
    }

    /** Undo {@link #hold}: whatever is left of the drink goes back where it came from. */
    private void release(NumenPlayer bot) {
        if (started) {
            bot.m_5810_();
        }
        if (swapSlot >= 0) {
            Inventory inventory = bot.m_150109_();
            ItemStack now = inventory.m_8020_(swapHotbar);
            inventory.m_6836_(swapHotbar, swapSaved == null ? ItemStack.f_41583_ : swapSaved);
            inventory.m_6836_(swapSlot, now);
        }
        if (savedSelected >= 0 && savedSelected < 9) {
            bot.m_150109_().f_35977_ = savedSelected;
        }
        swapSlot = -1;
        swapHotbar = -1;
        swapSaved = null;
        savedSelected = -1;
        started = false;
        fellBack = false;
        ticks = 0;
    }

    private static void blacklist(String id) {
        BLACKLIST.put(id, System.currentTimeMillis() + BLACKLIST_MILLIS);
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation location = ForgeRegistries.ITEMS.getKey(stack.m_41720_());
        return location == null ? "unknown" : location.toString();
    }
}
