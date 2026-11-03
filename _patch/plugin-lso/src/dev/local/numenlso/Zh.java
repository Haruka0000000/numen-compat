package dev.local.numenlso;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 中文名薄壳：名字全都在 {@code numen_i18n} 那张总表里（原版 + 所有模组的 zh_cn.json +
 * {@code config/numen/i18n_zh_cn.json} 手写覆盖），这里只是反射过去问一句。
 *
 * <p><b>由 {@code _patch/gen_zh.py} 生成，勿手改</b>——改模板才是改这里。
 *
 * <p>为什么反射而不是直接 import：插件 jar 之间不能互相依赖（少装一个 jar 就会
 * NoClassDefFoundError，导致整个命名空间注册失败，表面上只看到 no_function）。反射找不到
 * 那个类时就退回 id / 英文名，功能一个不少。
 */
public final class Zh {

    private static final String OWNER = "dev.local.numeni18n.I18n";

    private static final AtomicReference<Method> LOOKUP = new AtomicReference<>();

    private static final java.util.concurrent.atomic.AtomicInteger FAILS = new java.util.concurrent.atomic.AtomicInteger();

    /** 连续失败几次就放弃；重试是因为第一次调用可能撞上总表初始化/世界还没就绪。 */
    private static final int GIVE_UP_AFTER = 3;

    private static volatile boolean complained;

    private Zh() {
    }

    /** 物品的中文名。 */
    public static String name(ItemStack stack) {
        if (stack == null || stack.m_41619_()) {
            return "";
        }
        String key = stack.m_41778_();
        String hit = ask(key);
        if (hit != null) {
            return clean(hit);
        }
        try {
            String hover = stack.m_41786_().getString();
            if (hover != null && !hover.isBlank() && !hover.equals(key)) {
                return clean(hover);
            }
        } catch (Throwable ignored) {
            // 退回下面那行
        }
        return shortPath(key);
    }

    /** 按翻译键取中文名；表里没有就返回键本身。 */
    public static String name(String descriptionId) {
        if (descriptionId == null || descriptionId.isBlank()) {
            return "";
        }
        String hit = ask(descriptionId);
        return hit == null ? descriptionId : clean(hit);
    }

    /** 方块的中文名；拿不到 id 返回空串。 */
    public static String block(Block block) {
        if (block == null) {
            return "";
        }
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(block);
        if (id == null) {
            return "";
        }
        String flat = id.toString().replace(':', '.');
        String hit = ask("block." + flat);
        if (hit == null) {
            hit = ask("tile." + flat);
        }
        if (hit != null) {
            return clean(hit);
        }
        try {
            return Component.m_237113_(id.toString()).getString();
        } catch (Throwable t) {
            return id.toString();
        }
    }

    /** 液体的中文名：先找它的方块名（水是 block.minecraft.water），再找 fluid.*，最后英文名。 */
    public static String fluid(Fluid fluid) {
        if (fluid == null) {
            return "";
        }
        ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid);
        if (id != null) {
            String flat = id.toString().replace(':', '.');
            String hit = ask("block." + flat);
            if (hit == null) {
                hit = ask("fluid." + flat);
            }
            if (hit == null) {
                hit = ask("fluid_type." + flat);
            }
            if (hit != null) {
                return clean(hit);
            }
        }
        try {
            return new net.minecraftforge.fluids.FluidStack(fluid, 1).getDisplayName().getString();
        } catch (Throwable t) {
            return id == null ? "" : id.toString();
        }
    }

    /** 去掉语言文件里的占位符（%s / %d）与颜色码（§x）。 */
    public static String clean(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' && i + 1 < text.length()
                    && (text.charAt(i + 1) == 's' || text.charAt(i + 1) == 'd')) {
                continue;
            }
            if (c == 0x00a7) {
                i++;
                continue;
            }
            out.append(c);
        }
        return out.toString().trim();
    }

    /** 问总表要一个名字；总表不在、或者没这条，返回 null。 */
    private static String ask(String key) {
        if (key == null || key.isBlank() || FAILS.get() >= GIVE_UP_AFTER) {
            return null;
        }
        try {
            Method m = LOOKUP.get();
            if (m == null) {
                Class<?> owner = Class.forName(OWNER);
                m = owner.getMethod("lookup", String.class);
                LOOKUP.set(m);
            }
            Object hit = m.invoke(null, key);
            FAILS.set(0);
            if (!(hit instanceof String)) {
                return null;
            }
            String text = (String) hit;
            return text.isBlank() ? null : text;
        } catch (Throwable t) {
            // 不立刻放弃：前几次可能是总表还没初始化完。连续错够三次才认定「没装中文名插件」。
            int bad = FAILS.incrementAndGet();
            if (bad >= GIVE_UP_AFTER && !complained) {
                complained = true;
                System.out.println("[numen-zh] 问不到中文名总表（" + t.getClass().getSimpleName()
                        + "），之后一律退回 id / 英文名；装上 numen_i18n 再重启就能恢复");
            }
            return null;
        }
    }

    /** item.farmersdelight.bacon -> bacon（问不到中文时的最后退路）。 */
    private static String shortPath(String key) {
        if (key == null || key.isBlank()) {
            return "";
        }
        int dot = key.lastIndexOf('.');
        return dot > 0 && dot + 1 < key.length() ? key.substring(dot + 1) : key;
    }
}
