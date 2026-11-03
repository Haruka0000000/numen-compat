package dev.local.numenlostcity;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import mcjty.lostcities.LostCitiesImp;
import mcjty.lostcities.api.ILostChunkInfo;
import mcjty.lostcities.api.ILostCityAsset;
import mcjty.lostcities.api.ILostCityInformation;
import mcjty.lostcities.api.ILostCityMultiBuilding;
import mcjty.lostcities.setup.Registration;
import mcjty.lostcities.worldgen.IDimensionInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The only class that names Lost Cities types.
 *
 * <p>Every public entry point here takes and returns plain data (the records declared in
 * {@link LostCityApi}), never a Lost Cities interface, so {@code LostCityApi} can be loaded even when
 * the mod is missing; the class only resolves when one of these methods is actually called.
 */
final class CityBridge {

    static final int MAX_CONTAINERS = 60;

    private CityBridge() {}

    // ---------------------------------------------------------------- lookups

    static LostCityApi.At here(ServerLevel level, double x, double z) {
        requireLoaded(level, (int) Math.floor(x), (int) Math.floor(z));
        ILostCityInformation info = info(level);
        return at(info, (int) Math.floor(x), (int) Math.floor(z));
    }

    static LostCityApi.At at(ServerLevel level, int x, int z) {
        requireLoaded(level, x, z);
        ILostCityInformation info = info(level);
        return at(info, x, z);
    }

    /**
     * Lost Cities 的 {@code getChunkInfo} 会为了回答「这个坐标是什么楼」去生成区块：对着几万格外的坐标
     * 查一次，服务端会卡上好几秒（实测 8 秒），然后返回一个 dummycity 的假答案。先问一句「这块区块在
     * 不在」既快又诚实——她宁可听到「还没加载」，也不该拿到「这里是荒野」这种编出来的话。
     */
    private static void requireLoaded(ServerLevel level, int x, int z) {
        if (!level.m_7232_(x >> 4, z >> 4)) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "这个坐标的区块还没加载，现在查不到城市数据",
                    "先走过去让区块加载出来，或者用 find.buildings 在附近找，再来问这里是什么楼");
        }
    }

    static LostCityApi.Found find(ServerLevel level, double fromX, double fromZ,
                                  String match, int radiusChunks, int limit) {
        ILostCityInformation info = info(level);
        int pcx = ((int) Math.floor(fromX)) >> 4;
        int pcz = ((int) Math.floor(fromZ)) >> 4;
        Map<String, LostCityApi.Hit> nearest = new LinkedHashMap<>();
        int scanned = 0;
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                scanned++;
                ILostChunkInfo ci;
                try {
                    ci = info.getChunkInfo(cx, cz);
                } catch (Throwable t) {
                    continue;
                }
                if (ci == null) {
                    continue;
                }
                ResourceLocation rid = ci.getBuildingId();
                String name = ci.getBuildingType();
                if (rid == null || name == null) {
                    continue;
                }
                if (!matches(match, name, path(rid))) {
                    continue;
                }
                double hx = (cx << 4) + 8.5;
                double hz = (cz << 4) + 8.5;
                double d = Math.sqrt((hx - fromX) * (hx - fromX) + (hz - fromZ) * (hz - fromZ));
                LostCityApi.Hit hit = new LostCityApi.Hit(name, BuildingNames.chinese(name), BuildingNames.english(name),
                        rid.toString(), cx, cz,
                        (cx << 4) + 8, (cz << 4) + 8, round(d), ci.getNumFloors(), multiOf(ci),
                        multiW(ci), multiH(ci));
                String key = family(name);
                LostCityApi.Hit old = nearest.get(key);
                if (old == null || hit.distance() < old.distance()) {
                    nearest.put(key, hit);
                }
            }
        }
        List<LostCityApi.Hit> list = new ArrayList<>(nearest.values());
        list.sort(Comparator.comparingDouble(LostCityApi.Hit::distance));
        int total = list.size();
        if (list.size() > limit) {
            list = new ArrayList<>(list.subList(0, limit));
        }
        return new LostCityApi.Found(scanned, total, list);
    }

    static LostCityApi.Types types(ServerLevel level, String match) {
        ILostCityInformation info = info(level);
        List<String> names = new ArrayList<>();
        for (ILostCityAsset asset : info.getBuildings().getIterable()) {
            String n = asset.getName();
            if (n == null || !matches(match, n, path(asset.getId()))) {
                continue;
            }
            String fam = family(n);
            if (!names.contains(fam)) {
                names.add(fam);
            }
        }
        names.sort(Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder()));
        return new LostCityApi.Types(names.size(), names);
    }

    // ---------------------------------------------------------------- loot

    /** A stable key for the building site that owns chunk (cx, cz). Identical buildings side by
     *  side get different keys, because a normal building is exactly one chunk and a multi building
     *  is keyed by its own origin chunk. */
    static String site(ILostCityInformation info, int cx, int cz) {
        try {
            ILostChunkInfo ci = info.getChunkInfo(cx, cz);
            if (ci == null) {
                return "wild";
            }
            if (!ci.isCity()) {
                return "wild";
            }
            ILostChunkInfo.MultiBuildingInfo mb = ci.getMultiBuildingInfo();
            if (mb != null && mb.buildingType() != null) {
                return "multi:" + mb.buildingType() + ":" + (cx - mb.offsetX()) + "," + (cz - mb.offsetZ());
            }
            return "chunk:" + cx + "," + cz;
        } catch (Throwable t) {
            return "wild";
        }
    }


    static LostCityApi.Loot loot(ServerLevel level, double px, double py, double pz,
                                 int radius, Integer yOverride, int dy, boolean unlooted) {
        return loot(level, px, py, pz, radius, yOverride, dy, unlooted, null);
    }

    static LostCityApi.Loot loot(ServerLevel level, double px, double py, double pz,
                                 int radius, Integer yOverride, int dy, boolean unlooted, String onlySite) {
        return loot(level, px, py, pz, radius, yOverride, dy, unlooted, onlySite, MAX_CONTAINERS);
    }

    /**
     * 按她这次问的口径，从缓存的全量扫描里筛出来。
     *
     * <p>为什么拆成「先全量扫、再按需筛」：{@code range} 只要一段高度、{@code plan} 要整栋的路线、
     * {@code sweep} 要全部——三者的差别只是筛法，扫描本身是同一遍。全量扫一遍存进 {@link ScanCache}，
     * 各函数各筛各的，同一栋楼就只翻一次区块。
     */
    static LostCityApi.Loot loot(ServerLevel level, double px, double py, double pz,
                                 int radius, Integer yOverride, int dy, boolean unlooted, String onlySite,
                                 int max) {
        LostCityApi.Loot all = ScanCache.scan(level, px, pz, radius);
        int y0 = yOverride != null ? yOverride : (int) Math.floor(py);
        List<LostCityApi.Box> boxes = new ArrayList<>();
        List<LostCityApi.Spawn> spawners = new ArrayList<>();
        for (LostCityApi.Box b : all.containers()) {
            if (Math.abs(b.y() - y0) > dy) {
                continue;
            }
            if (unlooted && b.empty() && !b.lootr()) {
                continue;
            }
            boxes.add(b);
        }
        for (LostCityApi.Spawn s : all.spawners()) {
            if (Math.abs(s.y() - y0) > dy) {
                continue;
            }
            spawners.add(s);
        }
        // 先分地皮，再截断：反过来的话，隔壁楼离得近的箱子会把这一栋的挤出去。
        int others = 0;
        String scope = onlySite == null ? "" : onlySite;
        List<LostCityApi.Box> mine = boxes;
        List<LostCityApi.Spawn> mineSpawners = spawners;
        if (onlySite != null) {
            mine = new ArrayList<>();
            mineSpawners = new ArrayList<>();
            for (LostCityApi.Box b : boxes) {
                if (onlySite.equals(b.site())) {
                    mine.add(b);
                } else {
                    others++;
                }
            }
            for (LostCityApi.Spawn s : spawners) {
                if (onlySite.equals(s.site())) {
                    mineSpawners.add(s);
                }
            }
            if (mine.isEmpty() && !boxes.isEmpty()) {
                // 站在街上/桥下/地下夹层：退而用最近那个箱子所在的地皮，而不是回「什么都没有」。
                String nearest = null;
                double best = Double.MAX_VALUE;
                for (LostCityApi.Box b : boxes) {
                    if (b.distance() < best) {
                        best = b.distance();
                        nearest = b.site();
                    }
                }
                if (nearest != null) {
                    scope = nearest;
                    others = 0;
                    for (LostCityApi.Box b : boxes) {
                        if (nearest.equals(b.site())) {
                            mine.add(b);
                        } else {
                            others++;
                        }
                    }
                    for (LostCityApi.Spawn s : spawners) {
                        if (nearest.equals(s.site())) {
                            mineSpawners.add(s);
                        }
                    }
                }
            }
        }
        mine.sort(Comparator.comparingDouble(LostCityApi.Box::distance));
        mineSpawners.sort(Comparator.comparingDouble(s -> {
            double dx = s.x() + 0.5 - px;
            double dz = s.z() + 0.5 - pz;
            return Math.sqrt(dx * dx + dz * dz);
        }));
        boolean truncated = false;
        if (mine.size() > max) {
            mine = new ArrayList<>(mine.subList(0, max));
            truncated = true;
        }
        return new LostCityApi.Loot(all.chunks(), mine, mineSpawners, truncated, scope, others, all.note());
    }

    /** 站在 (x,z) 这块地皮的 id；读不到城市信息时回 {@code "wild"}。缓存键要用它，地皮不同不能串用。 */
    static String siteAt(ServerLevel level, int x, int z) {
        return site(info(level), x >> 4, z >> 4);
    }

    /** 全量扫一遍（不筛高度、不看空不空、不截断），给 {@link ScanCache} 用。 */
    static LostCityApi.Loot raw(ServerLevel level, double px, double pz, int radius) {
        return rawAll(level, px, 0, pz, radius, null, Integer.MAX_VALUE, false, null, Integer.MAX_VALUE);
    }

    /**
     * @param max 最多留几个容器。传 {@link Integer#MAX_VALUE} 就是全给——按高度切段、按数量分页时要用全量，
     *            否则「整栋只留最近 60 个」会把楼上直接切掉，而那不是她想要的排序。
     */
    static LostCityApi.Loot rawAll(ServerLevel level, double px, double py, double pz,
                                 int radius, Integer yOverride, int dy, boolean unlooted, String onlySite,
                                 int max) {
        ILostCityInformation info = info(level);
        // 扫描范围取 max(她要的半径, 这栋楼的包围盒)：5x5 大楼的侧翼离她脚下可能 80 格，只扫 radius 会报半个楼。
        int sweep = Math.max(radius, plotRadius(info, (int) Math.floor(px), (int) Math.floor(pz)));
        int y0 = yOverride != null ? yOverride : (int) Math.floor(py);
        int minX = (int) Math.floor(px) - sweep;
        int maxX = (int) Math.floor(px) + sweep;
        int minZ = (int) Math.floor(pz) - sweep;
        int maxZ = (int) Math.floor(pz) + sweep;
        List<LostCityApi.Box> boxes = new ArrayList<>();
        List<LostCityApi.Spawn> spawners = new ArrayList<>();
        int chunks = 0;
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                // hasChunk first: getChunk would generate terrain otherwise.
                if (!level.m_7232_(cx, cz)) {
                    continue;
                }
                LevelChunk chunk = level.m_6325_(cx, cz);
                if (chunk == null) {
                    continue;
                }
                chunks++;
                String site = site(info, cx, cz);
                for (Map.Entry<BlockPos, BlockEntity> e : chunk.m_62954_().entrySet()) {
                    BlockPos p = e.getKey();
                    int bx = p.m_123341_();
                    int by = p.m_123342_();
                    int bz = p.m_123343_();
                    if (bx < minX || bx > maxX || bz < minZ || bz > maxZ) {
                        continue;
                    }
                    if (Math.abs(by - y0) > dy) {
                        continue;
                    }
                    double d = Math.sqrt((bx + 0.5 - px) * (bx + 0.5 - px) + (bz + 0.5 - pz) * (bz + 0.5 - pz));
                    if (d > radius + 0.5) {
                        continue;
                    }
                    BlockEntity be = e.getValue();
                    String bid = blockId(be);
                    if (be instanceof SpawnerBlockEntity spawner) {
                        int spawnRange = spawnerRange(spawner);
                        String mob = mobOf(spawner);
                        spawners.add(new LostCityApi.Spawn(bx, by, bz, mob, mobName(mob),
                                spawnRange, playerWithin(level, bx + 0.5, by + 0.5, bz + 0.5, spawnRange),
                                site));
                        continue;
                    }
                    if (be instanceof Container container) {
                        int slots = 0;
                        boolean empty = false;
                        try {
                            slots = container.m_6643_();
                            empty = container.m_7983_();
                        } catch (Throwable ignored) {
                        }
                        boolean lootr = bid != null && bid.startsWith("lootr:");
                        if (unlooted && empty && !lootr) {
                            continue;
                        }
                        boxes.add(new LostCityApi.Box(bx, by, bz, bid, blockName(level, p),
                                empty, lootr, slots, site, round(d)));
                        continue;
                    }
                    IItemHandler handler = itemHandler(be);
                    if (handler == null) {
                        continue;
                    }
                    int slots = 0;
                    boolean empty = true;
                    try {
                        slots = handler.getSlots();
                        for (int i = 0; i < slots; i++) {
                            if (!handler.getStackInSlot(i).m_41619_()) {
                                empty = false;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                    boolean lootr2 = bid != null && bid.startsWith("lootr:");
                    if (unlooted && empty && !lootr2) {
                        continue;
                    }
                    boxes.add(new LostCityApi.Box(bx, by, bz, bid, blockName(level, p),
                            empty, lootr2, slots, site, round(d)));
                }
            }
        }
        // 先分地皮，再截断：反过来的话，隔壁楼离得近的箱子会把这一栋的挤出去。
        int others = 0;
        String scope = onlySite == null ? "" : onlySite;
        List<LostCityApi.Box> mine = boxes;
        List<LostCityApi.Spawn> mineSpawners = spawners;
        if (onlySite != null) {
            mine = new ArrayList<>();
            mineSpawners = new ArrayList<>();
            for (LostCityApi.Box b : boxes) {
                if (onlySite.equals(b.site())) {
                    mine.add(b);
                } else {
                    others++;
                }
            }
            for (LostCityApi.Spawn s : spawners) {
                if (onlySite.equals(s.site())) {
                    mineSpawners.add(s);
                }
            }
            if (mine.isEmpty() && !boxes.isEmpty()) {
                // 站在街上/桥下/地下夹层：退而用最近那个箱子所在的地皮，而不是回「什么都没有」。
                String nearest = null;
                double best = Double.MAX_VALUE;
                for (LostCityApi.Box b : boxes) {
                    if (b.distance() < best) {
                        best = b.distance();
                        nearest = b.site();
                    }
                }
                if (nearest != null) {
                    scope = nearest;
                    others = 0;
                    for (LostCityApi.Box b : boxes) {
                        if (nearest.equals(b.site())) {
                            mine.add(b);
                        } else {
                            others++;
                        }
                    }
                    for (LostCityApi.Spawn s : spawners) {
                        if (nearest.equals(s.site())) {
                            mineSpawners.add(s);
                        }
                    }
                }
            }
        }
        mine.sort(Comparator.comparingDouble(LostCityApi.Box::distance));
        mineSpawners.sort(Comparator.comparingDouble(s -> {
            double dx = s.x() + 0.5 - px;
            double dz = s.z() + 0.5 - pz;
            return Math.sqrt(dx * dx + dz * dz);
        }));
        boolean truncated = false;
        if (mine.size() > max) {
            mine = new ArrayList<>(mine.subList(0, max));
            truncated = true;
        }
        // chunks == 0 是真事：她只看得见已加载的区块，而「没加载」不等于「没有箱子」。
        String note = chunks == 0
                ? "半径里一个区块都没加载——先走过去，等区块出来了再查；这里现在报 0 不是真的没有"
                : "";
        return new LostCityApi.Loot(chunks, mine, mineSpawners, truncated, scope, others, note);
    }

    // ---------------------------------------------------------------- internals

    static ILostCityInformation info(ServerLevel level) {
        IDimensionInfo di;
        try {
            di = Registration.LOSTCITY_FEATURE.get().getDimensionInfo((WorldGenLevel) level);
        } catch (Throwable t) {
            di = null;
        }
        if (di == null) {
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "这个维度不是失落的城市生成的（没有城市数据）",
                    "用 numen.status.world() 看她现在在哪个维度；只有主世界/失落的城市维度能查建筑");
        }
        return new LostCitiesImp.LostCityInformation(di);
    }

    private static LostCityApi.At at(ILostCityInformation info, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        ILostChunkInfo ci;
        try {
            ci = info.getChunkInfo(cx, cz);
        } catch (Throwable t) {
            ci = null;
        }
        if (ci == null) {
            // 以前这里直接解引用：区块还没生成时她收到的是英文空指针栈，而不是一句能看懂的话。
            // site() 和 anchor() 早就判了 null，只有这一处漏了。
            throw new ApiError(ErrorKind.NOT_FOUND,
                    "这个坐标还没有城市数据（区块没生成，或者不在失落的城市范围里）",
                    "先走到那里让区块加载出来再看；只有生成过的区块才有建筑信息");
        }
        String name = ci.getBuildingType() == null ? "" : ci.getBuildingType();
        ResourceLocation rid = ci.getBuildingId();
        String id = rid == null ? "" : rid.toString();
        String kind = id.isEmpty() ? (ci.isCity() ? "street" : "wilderness") : "building";
        String style = "";
        try {
            if (ci.getCityStyle() != null) {
                style = ci.getCityStyle().getName();
            }
        } catch (Throwable ignored) {
        }
        int cityLevel = ci.getCityLevel();
        int groundY = info.getRealHeight(cityLevel);
        int floors = ci.getNumFloors();
        int cellars = ci.getNumCellars();
        List<Integer> floorsY = new ArrayList<>();
        // 地下室也要报：一栋楼的地下几层往往比地上肥，以前只列地上等于把最好的那几层藏了。
        for (int k = Math.min(cellars, 32); k >= 1; k--) {
            floorsY.add(groundY - 6 * k + 1);
        }
        for (int i = 0; i < floors && i < 64; i++) {
            // getRealHeight points at the first solid block of a floor; the walkable space is +1.
            floorsY.add(groundY + 6 * i + 1);
        }
        // getRuinLevel 返回的是「废墟开始那一层的**绝对 Y**」，不是层号：上游
        // BuildingInfo.getRuinLevel() = cityGroundLevel + 1 + ruinHeight * floors * FLOORHEIGHT。
        // 以前直接印成「从第 N 层开始塌」，N 其实是个七十多的坐标。
        int ruinY = ci.getRuinLevel();
        int ruinLevel = -1;
        if (ruinY >= 0) {
            ruinLevel = Math.max(1, (ruinY - groundY - 1) / 6 + 1);
        }
        return new LostCityApi.At(kind, name, BuildingNames.chinese(name), BuildingNames.english(name),
                id, style, cx, cz, (cx << 4) + 8, (cz << 4) + 8,
                cityLevel, groundY, floors, cellars, ruinLevel, ruinY, multiOf(ci),
                multiW(ci), multiH(ci), multiX(ci, cx), multiZ(ci, cz), floorsY);
    }

    /**
     * 这栋楼自己的原点：原点区块的角、地面 Y。普通建筑就是她那一格所在区块，跨区块大建筑要退回到
     * 它的第一个区块——同一栋楼每个副本都按同一个原点量坐标，蓝图才能跨楼复用。
     */
    static int[] anchor(ServerLevel level, double px, double pz) {
        ILostCityInformation info = info(level);
        int cx = ((int) Math.floor(px)) >> 4;
        int cz = ((int) Math.floor(pz)) >> 4;
        int groundY = 63;
        int ox = cx;
        int oz = cz;
        try {
            ILostChunkInfo ci = info.getChunkInfo(cx, cz);
            if (ci != null) {
                groundY = info.getRealHeight(ci.getCityLevel());
                ILostChunkInfo.MultiBuildingInfo mb = ci.getMultiBuildingInfo();
                if (mb != null) {
                    ox = cx - mb.offsetX();
                    oz = cz - mb.offsetZ();
                }
            }
        } catch (Throwable ignored) {
            // no chunk info: fall back to her own chunk corner
        }
        return new int[] {ox << 4, groundY, oz << 4};
    }

    /** 方块的中文名（原版/模组语言文件里有的都会被翻出来），读不到就给 id。 */
    private static String blockName(ServerLevel level, BlockPos pos) {
        try {
            return Zh.block(level.m_8055_(pos).m_60734_());
        } catch (Throwable t) {
            return "";
        }
    }

    /** 生物的中文名，例如 minecraft:zombie → 僵尸。 */
    static String mobName(String id) {
        if (id == null || id.isEmpty()) {
            return "";
        }
        try {
            return Zh.name("entity." + id.replace(':', '.'));
        } catch (Throwable t) {
            return "";
        }
    }

    private static String blockId(BlockEntity be) {
        try {
            BlockEntityType<?> type = be.m_58903_();
            if (type != null) {
                return String.valueOf(ForgeRegistries.BLOCK_ENTITY_TYPES.getKey(type));
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static IItemHandler itemHandler(BlockEntity be) {
        try {
            // getCapability is a Forge addition, so the SRG jar does not declare it on BlockEntity.
            return ((ICapabilityProvider) be).getCapability(ForgeCapabilities.ITEM_HANDLER, null).orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    private static String mobOf(SpawnerBlockEntity spawner) {
        try {
            CompoundTag tag = spawner.m_59801_().m_186381_(new CompoundTag());
            String id = tag.m_128469_("SpawnData").m_128469_("entity").m_128461_("id");
            if (id != null && !id.isEmpty()) {
                return id;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /**
     * How far a player has to be before this spawner starts spawning (vanilla default 16).
     *
     * <p>Reads the field the spawner actually uses, so a modded or tweaked spawner reports its real
     * range instead of our guess; falls back to 16 when the field is not readable.
     */
    static int spawnerRange(SpawnerBlockEntity spawner) {
        try {
            // 1.20.1 里 requiredPlayerRange 是 private 且被 SRG 改名（f_45447_），反射必然失败，
            // 所以以前永远回 16。存档 NBT 是官方出口：BaseSpawner.save 会写 RequiredPlayerRange。
            CompoundTag tag = spawner.m_59801_().m_186381_(new CompoundTag());
            int v = tag.m_128448_("RequiredPlayerRange");
            if (v > 0 && v <= 128) {
                return v;
            }
        } catch (Throwable ignored) {
            // fall through to the vanilla default
        }
        return 16;
    }

    /** True when any player is within {@code range} of that point — i.e. the spawner is live now. */
    static boolean playerWithin(Level level, double x, double y, double z, int range) {
        try {
            double r2 = (double) range * (double) range;
            for (Player p : level.m_6907_()) {
                double dx = p.m_20185_() - x;
                double dy = p.m_20186_() - y;
                double dz = p.m_20189_() - z;
                if (dx * dx + dy * dy + dz * dz <= r2) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // no player list (should not happen on a server level)
        }
        return false;
    }

    private static String multiOf(ILostChunkInfo ci) {
        try {
            if (ci.getMultiBuildingInfo() != null) {
                return String.valueOf(ci.getMultiBuildingInfo().buildingType());
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    /** How many chunks wide the multi building that owns this chunk is; 1 for a normal building. */
    private static int multiW(ILostChunkInfo ci) {
        try {
            ILostChunkInfo.MultiBuildingInfo mb = ci.getMultiBuildingInfo();
            if (mb != null && mb.w() > 0) {
                return mb.w();
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /** How many chunks deep the multi building that owns this chunk is; 1 for a normal building. */
    private static int multiH(ILostChunkInfo ci) {
        try {
            ILostChunkInfo.MultiBuildingInfo mb = ci.getMultiBuildingInfo();
            if (mb != null && mb.h() > 0) {
                return mb.h();
            }
        } catch (Throwable ignored) {
        }
        return 1;
    }

    /** Column of this chunk inside its multi building's grid, 0 based. */
    private static int multiX(ILostChunkInfo ci, int cx) {
        try {
            ILostChunkInfo.MultiBuildingInfo mb = ci.getMultiBuildingInfo();
            if (mb != null) {
                return cx - mb.offsetX();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** Row of this chunk inside its multi building's grid, 0 based. */
    private static int multiZ(ILostChunkInfo ci, int cz) {
        try {
            ILostChunkInfo.MultiBuildingInfo mb = ci.getMultiBuildingInfo();
            if (mb != null) {
                return cz - mb.offsetZ();
            }
        } catch (Throwable ignored) {
        }
        return 0;
    }

    /** Every multi building this modpack registered, with the footprint each one wants. */
    static LostCityApi.Multis multis(ServerLevel level, String match) {
        ILostCityInformation info = info(level);
        List<LostCityApi.MultiItem> items = new ArrayList<>();
        for (ILostCityAsset asset : info.getMultiBuildings().getIterable()) {
            if (!(asset instanceof ILostCityMultiBuilding mb)) {
                continue;
            }
            String n = asset.getName();
            if (n == null) {
                n = "";
            }
            String id = asset.getId() == null ? "" : asset.getId().toString();
            String shortName = path(asset.getId());
            if (shortName.isEmpty()) {
                shortName = n;
            }
            if (!matches(match, n, shortName)) {
                continue;
            }
            int dx = Math.max(1, mb.getDimX());
            int dz = Math.max(1, mb.getDimZ());
            // 显示名（asset.getName）常常是 "Vertical School" 这种，查不到中文；id 的后半段
            // （multi_verticalschool）才是中文表里的键，两个都试。
            String cn = BuildingNames.chinese(n);
            if (cn.isEmpty()) {
                cn = BuildingNames.chinese(shortName);
            }
            items.add(new LostCityApi.MultiItem(shortName, cn, id, dx, dz, dx * dz));
        }
        items.sort(Comparator.comparingInt(LostCityApi.MultiItem::chunks).reversed()
                .thenComparing(LostCityApi.MultiItem::name));
        return new LostCityApi.Multis(items.size(), items);
    }

    /** Footprint of the first multi building whose name contains {@code match}. */
    static LostCityApi.MultiSize multiSize(ServerLevel level, String match) {
        for (LostCityApi.MultiItem item : multis(level, match).items()) {
            return new LostCityApi.MultiSize(true, item.name(), item.nameCn(), item.dimX(), item.dimZ(), item.chunks());
        }
        return new LostCityApi.MultiSize(false, "", "", 0, 0, 0);
    }

    static String path(ResourceLocation id) {
        if (id == null) {
            return "";
        }
        String s = id.toString();
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    static boolean matches(String needle, String... haystack) {
        if (needle == null || needle.isBlank()) {
            return true;
        }
        String n = needle.trim().toLowerCase(Locale.ROOT);
        for (String h : haystack) {
            if (h != null && h.toLowerCase(Locale.ROOT).contains(n)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rotation suffixes and multi-building variant indices of one building collapse to one family.
     *
     * <p>The name Lost Cities hands back is not a bare id: it looks like
     * {@code deceasedcraft:multi_hospitaldamaged/multi_hospitaldamaged_3} — namespace first, then the
     * {@code /part} suffix.  Stripping only the trailing rotation/variant number left the namespace and
     * the part suffix in place, so for a multi building the family was the full id plus its part index:
     * every part chunk became its own family and one 2x3 hospital was reported as six buildings.
     */
    static String family(String name) {
        String s = name.toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        if (colon >= 0) {
            s = s.substring(colon + 1);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        s = s.replaceAll("_(90|180|270)$", "");
        if (s.startsWith("multi_")) {
            s = s.replaceAll("_\\d+$", "");
        }
        return s;
    }

    /**
     * 她脚下这栋楼的包围盒半径（格）。普通房子 1x1 区块 = 32；5x5 的大楼 = 96。
     *
     * <p>扫楼时必须按这个来，不能按她给的 radius：3x3 楼的侧翼离她脚下 40 格，只扫 radius 会报半个楼。
     * 以前这里写死成 72，跟楼实际多大无关——1x1 的小房子也被放大成 72，于是传 radius = 8 和 64
     * 拿到的东西一模一样，radius 形同虚设。
     */
    static int plotRadius(ILostCityInformation info, int x, int z) {
        try {
            ILostChunkInfo ci = info.getChunkInfo(x >> 4, z >> 4);
            if (ci != null) {
                return 16 * (Math.max(multiW(ci), multiH(ci)) + 1);
            }
        } catch (Throwable ignored) {
        }
        return 32;
    }

    /** 同上，按世界坐标问。 */
    static int plotRadius(ServerLevel level, double px, double pz) {
        return plotRadius(info(level), (int) Math.floor(px), (int) Math.floor(pz));
    }


    /** 最近的楼梯/梯子/脚手架所在的那一列：脚下那一格、最高能到哪、有几块。 */
    static LostCityApi.Stairs stairs(ServerLevel level, double px, double py, double pz, int radius, int dy) {
        int cx = (int) Math.floor(px);
        int cy = (int) Math.floor(py);
        int cz = (int) Math.floor(pz);
        Map<Long, int[]> columns = new LinkedHashMap<>();
        int[] trap = null;
        double trapDistance = Double.MAX_VALUE;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (int x = cx - radius; x <= cx + radius; x++) {
            for (int z = cz - radius; z <= cz + radius; z++) {
                // 先问区块在不在：Level.getBlockState 走的是 getChunk(FULL, true)，
                // 对没加载的区块会现场生成地形，而 @Note 上写的是「只在已加载的区块里找」。
                if (!level.m_7232_(x >> 4, z >> 4)) {
                    continue;
                }
                for (int y = cy - dy; y <= cy + dy; y++) {
                    probe.m_122178_(x, y, z);
                    BlockState state = level.m_8055_(probe);
                    if (state.m_60795_()) {
                        continue;
                    }
                    ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.m_60734_());
                    if (id == null) {
                        continue;
                    }
                    String name = id.toString();
                    if (name.contains("trapdoor") || name.contains("fence_gate")) {
                        double tdx = x + 0.5 - px;
                        double tdz = z + 0.5 - pz;
                        double td = tdx * tdx + tdz * tdz;
                        if (td < trapDistance) {
                            trapDistance = td;
                            trap = new int[] {x, y, z};
                        }
                        continue;
                    }
                    boolean step = name.contains("stairs");
                    boolean climb = name.contains("ladder") || name.contains("scaffolding");
                    if (!step && !climb) {
                        continue;
                    }
                    long key = ((long) x << 32) ^ (z & 0xffffffffL);
                    int[] column = columns.get(key);
                    if (column == null) {
                        column = new int[] {y, y, 0, climb ? 1 : 0, x, z};
                        columns.put(key, column);
                    }
                    if (y < column[0]) {
                        column[0] = y;
                    }
                    if (y > column[1]) {
                        column[1] = y;
                    }
                    column[2]++;
                    if (climb) {
                        column[3] = 1;
                    }
                }
            }
        }
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int[] column : columns.values()) {
            double dx = column[4] + 0.5 - px;
            double dz = column[5] + 0.5 - pz;
            // 楼梯是一整段（column[0]..column[1]），量到最近的那一端；只算水平距离会把楼上那层
            // 的楼梯报成「离你 1.4 格」。
            double gap = py < column[0] ? column[0] + 0.5 - py
                    : (py > column[1] ? py - (column[1] + 0.5) : 0.0);
            double d = dx * dx + gap * gap + dz * dz;
            if (d < bestDistance) {
                bestDistance = d;
                best = column;
            }
        }
        if (best == null) {
            return new LostCityApi.Stairs(false, cx, cy, cz, cy, 0, false, 0.0,
                    "这个范围里没找到楼梯或梯子；用 numen.route.plan 规划到上一层 floors_y + 1，别去右键活板门/栅栏门",
                    trap == null ? "" : (trap[0] + "," + trap[1] + "," + trap[2]));
        }
        String note = best[3] == 1
                ? "这是梯子/脚手架：走到它那一格再往上爬"
                : "这是台阶：先走到这一格，再往上走；旁边的活板门/栅栏门是护栏，不要右键";
        return new LostCityApi.Stairs(true, best[4], best[0], best[5], best[1], best[2], best[3] == 1,
                round(Math.sqrt(bestDistance)), note,
                trap == null ? "" : (trap[0] + "," + trap[1] + "," + trap[2]));
    }

    private static double round(double d) {
        return Math.round(d * 10.0) / 10.0;
    }
}
