package dev.local.numenparcool;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.phys.Vec3;

import com.alrex.parcool.api.Attributes;
import com.alrex.parcool.api.Effects;
import com.alrex.parcool.common.action.Action;
import com.alrex.parcool.common.action.ActionList;
import com.alrex.parcool.common.capability.IStamina;
import com.alrex.parcool.common.capability.Parkourability;
import com.alrex.parcool.common.info.ClientSetting;
import com.alrex.parcool.common.info.ServerLimitation;
import com.alrex.parcool.config.ParCoolConfig;
import com.alrex.parcool.utilities.WorldUtil;

/**
 * 所有 ParCool 调用都在这一个类里，每一处都单独 try/catch。
 *
 * <p>为什么这么集中：ParCool 的这几样东西（气力、跑酷能力、服主限制）不是「装了就在」的——
 * {@code ServerLimitation} 要等存档的 serverconfig 读进来才可信，气力要客户端同步过才有值，
 * 墙体探测在专用服上还没人实测过。任何一处抛出去，整个 Lua 调用就废了；这里全部压成
 * 「读到了 / 没读到」的数据，让上层给一句人话，而不是一个栈。
 *
 * <p>这里**不碰**任何 {@code @OnlyIn(Dist.CLIENT)} 的成员：{@code Stamina#setValue}、
 * {@code Action#canStart}、{@code Parkourability#trySyncLimitation}、{@code ClientSetting#readFromLocalConfig}
 * 一个都没调。判定逻辑是照抄服务端能看的那部分数据自己算的。
 */
final class Probe {

    private Probe() {
    }

    /** 一次调用里读到的气力。{@code ok=false} 时只有 why 有意义。 */
    record Stamina(boolean ok, String why, int value, int max, boolean exhausted, boolean penalized,
                   boolean infinite, double recovery, String doing, int doingTicks) {
    }

    /** 一次调用里读到的服主限制 + 他客户端的跑酷设置。 */
    record Perm(boolean resolved, boolean serverSynced, boolean permitted, int serverCost,
                boolean clientSynced, boolean possible, int clientCost) {
    }

    /** 她面前的那堵墙。{@code null} 表示读不到。 */
    record Wall(boolean present, double height, String side, double distance, boolean vaultable) {
    }

    /** 动作表 + 这个服允许哪些。{@code known=false} 表示限制系统没同步，allowed 全是乐观值。 */
    record Acts(boolean known, boolean limitationEnabled, List<String> ids, boolean[] allowed, int[] costs) {
    }

    // ---------------------------------------------------------------- 气力

    static Stamina stamina(ServerPlayer p) {
        try {
            IStamina st = IStamina.get(p);
            if (st == null) {
                return new Stamina(false, "ParCool 没给他挂上气力，可能刚上线还没同步", 0, 0,
                        false, false, false, 0, "", 0);
            }
            int max = st.getActualMaxStamina();
            int value = st.get();
            if (max <= 0) {
                return new Stamina(false, "他的气力上限还是 0（服务端刚起来、他刚换维度、或者刚重连）",
                        value, 0, false, false, false, 0, "", 0);
            }
            boolean exhausted = st.isExhausted();
            boolean penalized = st.isImposingExhaustionPenalty();
            boolean infinite = infinite(p);
            double recovery = recovery(p);
            String doing = "";
            int ticks = 0;
            try {
                Parkourability k = Parkourability.get(p);
                if (k != null) {
                    for (Action a : k.getList()) {
                        if (a != null && a.isDoing()) {
                            doing = a.getClass().getSimpleName();
                            ticks = a.getDoingTick();
                            break;
                        }
                    }
                }
            } catch (Throwable ignored) {
                // 动作列表读不到不影响气力本身的结论。
            }
            return new Stamina(true, "", value, max, exhausted, penalized, infinite, recovery, doing, ticks);
        } catch (Throwable t) {
            return new Stamina(false, "读气力时 ParCool 抛了 " + t.getClass().getSimpleName(), 0, 0,
                    false, false, false, 0, "", 0);
        }
    }

    /** 身上有没有「无尽气力」效果。读不到就当没有。 */
    private static boolean infinite(ServerPlayer p) {
        try {
            return p.m_21023_(Effects.INEXHAUSTIBLE.get());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 每刻回多少气力：取 STAMINA_RECOVERY 的实际生效值，读不到返回 0。 */
    private static double recovery(ServerPlayer p) {
        try {
            Attribute attribute = Attributes.STAMINA_RECOVERY.get();
            AttributeInstance instance = p.m_21204_().m_22146_(attribute);
            return instance == null ? 0 : instance.m_22135_();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ---------------------------------------------------------------- 权限

    /**
     * 一个动作现在能不能用。
     *
     * <p>{@code ServerLimitation.get} 一条 Lua 调用只调一次（这里就是那一次），而且必须包起来：
     * 服务端刚起的时候它会抛 {@code IllegalStateException}，因为存档的限制表还没 init。
     */
    static Perm permission(ServerPlayer p, String id) {
        Class<? extends Action> cls = actionClass(id);
        if (cls == null) {
            return new Perm(false, false, true, -1, false, true, -1);
        }
        boolean serverSynced = false;
        boolean permitted = true;
        int serverCost = -1;
        try {
            ServerLimitation lim = ServerLimitation.get(p);
            if (lim != null && lim != ServerLimitation.UNSYNCED_INSTANCE && lim.isSynced()) {
                serverSynced = true;
                permitted = lim.isPermitted(cls);
                serverCost = lim.getStaminaConsumptionOf(cls);
            }
        } catch (Throwable ignored) {
            // 服务端刚起 / 存档还没读：当作没同步，上层不下死结论。
        }
        boolean clientSynced = false;
        boolean possible = true;
        int clientCost = -1;
        try {
            Parkourability k = Parkourability.get(p);
            ClientSetting cs = k == null ? null : k.getClientInfo();
            if (cs != null && cs != ClientSetting.UNSYNCED_INSTANCE) {
                clientSynced = true;
                possible = cs.getPossibilityOf(cls);
                clientCost = cs.getStaminaConsumptionOf(cls);
            }
        } catch (Throwable ignored) {
            // 他还没进过一次游戏 / 同步包还没到：同样当没同步。
        }
        return new Perm(true, serverSynced, permitted, serverCost, clientSynced, possible, clientCost);
    }

    /** 把英文 id 换成 ParCool 的动作类；表里没有返回 {@code null}。 */
    static Class<? extends Action> actionClass(String id) {
        try {
            List<Class<? extends Action>> all = ActionList.ACTIONS;
            if (all == null) {
                return null;
            }
            for (Class<? extends Action> cls : all) {
                if (cls != null && cls.getSimpleName().equalsIgnoreCase(id)) {
                    return cls;
                }
            }
        } catch (Throwable ignored) {
            // ParCool 不在 / 表结构变了。
        }
        return null;
    }

    /** 她面前那堵墙；读不到返回 {@code null}。 */
    static Wall wall(LivingEntity her) {
        try {
            Vec3 wall = WorldUtil.getWall(her);
            if (wall == null) {
                return new Wall(false, 0, "", 0, false);
            }
            double height = WorldUtil.getWallHeight(her);
            double dx = wall.f_82479_ - her.m_20185_();
            double dz = wall.f_82481_ - her.m_20189_();
            double distance = Math.sqrt(dx * dx + dz * dz);
            boolean vaultable = false;
            try {
                vaultable = WorldUtil.getVaultableStep(her) != null && height > her.m_20206_() * 0.44D;
            } catch (Throwable ignored) {
                // 撑越判断读不到就按「过不去」算，宁可保守。
            }
            return new Wall(true, height, side(her, dx, dz), distance, vaultable);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 墙在她哪个方向：按她自己朝的方向算。 */
    private static String side(LivingEntity her, double dx, double dz) {
        double absolute = Math.toDegrees(Math.atan2(-dx, dz));
        double relative = absolute - her.m_146908_();
        while (relative > 180) {
            relative -= 360;
        }
        while (relative < -180) {
            relative += 360;
        }
        if (relative > -35 && relative < 35) {
            return "正前方";
        }
        if (relative >= 35 && relative < 145) {
            return "左边";
        }
        if (relative <= -35 && relative > -145) {
            return "右边";
        }
        return "身后";
    }

    /** 主人跑墙最多能连着跑几刻：读不到服主设置就用 ParCool 自己的默认值。 */
    static int wallRunTicks(ServerPlayer owner) {
        if (owner != null) {
            try {
                ServerLimitation lim = ServerLimitation.get(owner);
                if (lim != null && lim != ServerLimitation.UNSYNCED_INSTANCE && lim.isSynced()) {
                    Integer v = lim.get(ParCoolConfig.Server.Integers.MaxWallRunContinuableTick);
                    if (v != null && v.intValue() > 0) {
                        return v.intValue();
                    }
                }
            } catch (Throwable ignored) {
                // 没同步：退回默认值。
            }
        }
        try {
            return ParCoolConfig.Server.Integers.MaxWallRunContinuableTick.DefaultValue;
        } catch (Throwable ignored) {
            return 40;
        }
    }

    /** 限制系统开没开；读不到就当没开（那样所有动作都算允许）。 */
    static boolean limitationEnabled() {
        try {
            return Boolean.TRUE.equals(ParCoolConfig.Server.LimitationEnabled.get());
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 动作表 + 这个服允许哪些；读不到返回 {@code null}。 */
    static Acts acts(ServerPlayer owner) {
        try {
            List<Class<? extends Action>> all = ActionList.ACTIONS;
            if (all == null || all.isEmpty()) {
                return null;
            }
            int n = all.size();
            boolean[] allowed = new boolean[n];
            int[] costs = new int[n];
            List<String> ids = new ArrayList<>();
            boolean enabled = limitationEnabled();
            boolean synced = false;
            ServerLimitation lim = null;
            if (enabled) {
                try {
                    lim = ServerLimitation.get(owner);
                    synced = lim != null && lim != ServerLimitation.UNSYNCED_INSTANCE && lim.isSynced();
                } catch (Throwable ignored) {
                    synced = false;
                }
            }
            for (int i = 0; i < n; i++) {
                Class<? extends Action> cls = all.get(i);
                String id = cls == null ? "" : cls.getSimpleName();
                ids.add(id);
                Names.Entry entry = Names.byId(id);
                costs[i] = entry == null ? 0 : entry.cost();
                boolean ok = true;
                if (enabled && synced && lim != null) {
                    try {
                        ok = lim.isPermitted(cls);
                    } catch (Throwable ignored) {
                        ok = true;
                    }
                }
                allowed[i] = ok;
            }
            return new Acts(enabled ? synced : true, enabled, ids, allowed, costs);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 这个整合包里 ParCool 到底有没有在跑。 */
    static boolean active() {
        try {
            return com.alrex.parcool.ParCool.isActive();
        } catch (Throwable ignored) {
            return false;
        }
    }
}
