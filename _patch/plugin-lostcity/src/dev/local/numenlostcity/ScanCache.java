package dev.local.numenlostcity;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.server.level.ServerLevel;

/**
 * 一次全量扫描的缓存。
 *
 * <p>为什么要它：{@code lostcity.loot.range} / {@code page} / {@code plan} / {@code preview} 和新的
 * {@code loot.sweep} 全都要「这栋楼里有哪些箱子」。以前每个函数各扫一遍——同一栋 5x5 的楼要翻
 * 一千多个区块的方块实体，四个函数问下来就是四遍。缓存后一遍就够。
 *
 * <p>缓存的是**全量**结果：不限高度、不看空不空、不被 {@code max} 截断。筛高度、筛空箱、按数量切页
 * 都是读的时候现做，所以 {@code range}（要一段高度）和 {@code plan}（要整栋的路线）能共用同一份。
 *
 * <p>键里带地皮：同一组坐标站在两栋相邻的楼里是不同的楼，<b>坐标相同也不能串用</b>。
 *
 * <p>什么时候过期：{@link #TTL} 刻（两秒）。她自己的手一动（搬空一个箱子、挖掉一个刷怪笼）就
 * {@link #invalidate}，不等它自然过期——否则她会照着两秒前的旧账说「这个箱子还有东西」。
 */
final class ScanCache {

    /** 最多同时记几份扫描。她一次只在一栋楼里干活，8 份足够覆盖「来回问几次」的常见情形。 */
    private static final int MAX_ENTRIES = 8;

    /** 多久算旧（游戏刻）。两秒足够让一串 range→page→sweep 命中同一份，又短到不会让她说太离谱的旧话。 */
    static final int TTL = 40;

    private record Entry(LostCityApi.Loot loot, long madeAt, List<String> sites) {
    }

    private static final Map<String, Entry> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private ScanCache() {
    }

    /**
     * 拿一份全量扫描：命中缓存就直接给，没命中就真扫一遍再存下。
     *
     * <p>像素级（方块坐标）做键，不是区块级：{@code radius} 这个圈是以她脚下为圆心筛的，
     * 站在同一个区块的另一个角上，能扫到的箱子就变了，用区块做键会给她一份偏心的旧结果。
     */
    static LostCityApi.Loot scan(ServerLevel level, double px, double pz, int radius) {
        long now = level.m_46467_();
        String key = key(level, px, pz, radius);
        Entry hit = CACHE.get(key);
        if (hit != null && now - hit.madeAt() <= TTL) {
            return hit.loot();
        }
        LostCityApi.Loot fresh = CityBridge.raw(level, px, pz, radius);
        List<String> sites = new ArrayList<>();
        for (LostCityApi.Box b : fresh.containers()) {
            if (b.site() != null && !sites.contains(b.site())) {
                sites.add(b.site());
            }
        }
        CACHE.put(key, new Entry(fresh, now, sites));
        return fresh;
    }

    /**
     * 这块地皮的扫描作废。她自己搬空一个箱子、拆掉一个刷怪笼之后必须调一次。
     *
     * @param site 地皮 id（{@code multi:…} / {@code chunk:…} / {@code wild}）；传 {@code null} 就是全清
     */
    static void invalidate(String site) {
        if (site == null) {
            CACHE.clear();
            return;
        }
        Iterator<Map.Entry<String, Entry>> it = CACHE.entrySet().iterator();
        while (it.hasNext()) {
            Entry e = it.next().getValue();
            if (e.sites().contains(site)) {
                it.remove();
            }
        }
    }

    /** 只给排查用：现在缓存里躺着几份。 */
    static int size() {
        return CACHE.size();
    }

    private static String key(ServerLevel level, double px, double pz, int radius) {
        int x = (int) Math.floor(px);
        int z = (int) Math.floor(pz);
        String site;
        try {
            site = CityBridge.siteAt(level, x, z);
        } catch (Throwable t) {
            site = "?";
        }
        return level.m_46472_().m_135782_() + "|" + x + "," + z + "|" + radius + "|" + site;
    }
}
