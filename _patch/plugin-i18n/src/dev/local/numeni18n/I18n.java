package dev.local.numeni18n;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 中文名总表：整个整合包里所有 {@code zh_cn.json} 汇成一张表，谁想问名字都来这儿问。
 *
 * <p>四处来源，后来的盖住先来的：
 * <ol>
 *   <li>原版名字：服务端的 jar 里没有语言文件，所以随本插件打包一份（{@code /assets/numen_i18n/vanilla_zh_cn.json}）。</li>
 *   <li>模组名字：{@code mods/*.jar} 里所有以 {@code /lang/zh_cn.json} 结尾的文件，第一次用到时全扫一遍。
 *       不要求一定在 {@code assets/<ns>/lang/} 下——嵌套的语言包（例如 TaCZ 自带的枪包）
 *       路径是 {@code assets/tacz/custom/<pack>/assets/tacz/lang/zh_cn.json}，也得读。</li>
 *   <li>本地语言包：{@code config/numen/lang/*.json}，同样是扁平 {@code {"键": "中文"}}。
 *       这一层是为了客户端资源包（KubeJS / resourcepacks 里那些 {@code zh_cn.json}）——
 *       那些文件在玩家电脑上，服务端根本读不到，只能由人把它们复制到这个目录里来。</li>
 *   <li>手写覆盖：{@code config/numen/i18n_zh_cn.json}，一个扁平的 {@code {"键": "中文"}}，优先级最高，
 *       用来补「整合包里没有翻译」或「翻译得不对」的那些。</li>
 * </ol>
 *
 * <p>这张表是<b>共享</b>的：别的插件不打包自己那份 183KB 的表，而是反射调 {@link #lookup(String)}
 * （见各插件里的 {@code Zh} 薄壳）。所以少装本插件时它们只会退回 id / 英文名，不会崩。
 *
 * <p>只读：除了读文件什么都不写。改名要改 {@code i18n_zh_cn.json} 再 {@code i18n.i18n.reload()}。
 */
public final class I18n {

    /** 覆盖文件的位置：{@code config/numen/i18n_zh_cn.json}。 */
    public static final String OVERRIDE_FILE = "i18n_zh_cn.json";

    /** 本地语言包目录：{@code config/numen/lang/*.json}，放客户端资源包里的 zh_cn.json。 */
    public static final String LANG_DIR = "lang";

    private static volatile Map<String, String> table;
    private static volatile Map<String, String> origin;

    private I18n() {
    }

    /** 按语言键取中文名；没有就返回 {@code null}（调用方自己决定退回什么）。 */
    public static String lookup(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        String hit = table().get(key);
        if (hit == null || hit.isBlank()) {
            return null;
        }
        return clean(hit);
    }

    /** 这个键是从哪儿来的：{@code 原版} / {@code 模组} / {@code 手写覆盖}；没有就返回空串。 */
    public static String originOf(String key) {
        if (key == null) {
            return "";
        }
        String hit = origin().get(key);
        return hit == null ? "" : hit;
    }

    /** 表里有多少条。 */
    public static int size() {
        return table().size();
    }

    /** 重新读一遍（覆盖文件改了之后调）。 */
    public static synchronized void reload() {
        table = null;
        origin = null;
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

    /**
     * 一个 id 的中文名。返回 {@code null} 表示「这个名字没人翻过」，调用方要自己说清楚。
     *
     * <p>按「这个 id 在哪些注册表里」决定试哪些键：物品 {@code item.*}、方块 {@code block.*}/{@code tile.*}、
     * 实体 {@code entity.*}、状态效果 {@code effect.*}、流体 {@code fluid.*}/{@code fluid_type.*}、
     * 附魔 {@code enchantment.*}、属性 {@code attribute.*}。猜不出来就按全部前缀挨个试。
     */
    public static String nameOf(String id) {
        for (String key : keysFor(id)) {
            String hit = lookup(key);
            if (hit != null) {
                return hit;
            }
        }
        return null;
    }

    /** 这个 id 在哪些注册表里（{@code item}/{@code block}/…），用来讲给调用方听。 */
    public static List<String> kindsOf(String id) {
        List<String> out = new ArrayList<>();
        ResourceLocation rl = parse(id);
        if (rl == null) {
            return out;
        }
        if (ForgeRegistries.ITEMS.containsKey(rl)) {
            out.add("item");
        }
        if (ForgeRegistries.BLOCKS.containsKey(rl)) {
            out.add("block");
        }
        if (ForgeRegistries.ENTITY_TYPES.containsKey(rl)) {
            out.add("entity");
        }
        if (ForgeRegistries.MOB_EFFECTS.containsKey(rl)) {
            out.add("effect");
        }
        if (ForgeRegistries.FLUIDS.containsKey(rl)) {
            out.add("fluid");
        }
        if (ForgeRegistries.ENCHANTMENTS.containsKey(rl)) {
            out.add("enchantment");
        }
        if (ForgeRegistries.ATTRIBUTES.containsKey(rl)) {
            out.add("attribute");
        }
        return out;
    }

    /** 按注册表顺序列出这个 id 可能对应的语言键。 */
    public static List<String> keysFor(String id) {
        List<String> keys = new ArrayList<>();
        if (id == null || id.isBlank()) {
            return keys;
        }
        String raw = id.trim();
        // 直接给了语言键（item.minecraft.diamond_sword 这种）就只试它
        if (raw.indexOf(':') < 0 && raw.indexOf('.') > 0) {
            keys.add(raw);
            return keys;
        }
        ResourceLocation rl = parse(raw);
        if (rl == null) {
            return keys;
        }
        String flat = rl.toString().replace(':', '.');
        for (String kind : kindsOf(raw)) {
            switch (kind) {
                case "item":
                    keys.add("item." + flat);
                    break;
                case "block":
                    keys.add("block." + flat);
                    keys.add("tile." + flat);
                    break;
                case "entity":
                    keys.add("entity." + flat);
                    break;
                case "effect":
                    keys.add("effect." + flat);
                    break;
                case "fluid":
                    keys.add("fluid." + flat);
                    keys.add("fluid_type." + flat);
                    keys.add("block." + flat);
                    break;
                case "enchantment":
                    keys.add("enchantment." + flat);
                    break;
                case "attribute":
                    keys.add("attribute." + flat);
                    break;
                default:
                    break;
            }
        }
        if (!keys.isEmpty()) {
            return keys;
        }
        // 哪个注册表都不认识：可能是个还没注册的 id，或者拼错了——把常见前缀都试一遍
        for (String prefix : new String[]{"item.", "block.", "tile.", "entity.", "effect.",
                "fluid.", "fluid_type.", "enchantment.", "attribute."}) {
            keys.add(prefix + flat);
        }
        return keys;
    }

    /** {@code ns:path}；没写命名空间就补 {@code minecraft:}；写坏了返回 {@code null}。 */
    public static ResourceLocation parse(String id) {
        if (id == null) {
            return null;
        }
        String text = id.trim();
        if (text.isEmpty()) {
            return null;
        }
        if (text.indexOf(':') < 0) {
            text = "minecraft:" + text;
        }
        try {
            return new ResourceLocation(text);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Map<String, String> table() {
        Map<String, String> cached = table;
        if (cached != null) {
            return cached;
        }
        synchronized (I18n.class) {
            if (table != null) {
                return table;
            }
            Map<String, String> names = new HashMap<>(65536);
            Map<String, String> from = new HashMap<>(65536);
            try {
                load(names, from);
            } catch (Throwable ignored) {
                names.clear();
                from.clear();
            }
            origin = from;
            table = names;
            return names;
        }
    }

    private static Map<String, String> origin() {
        table();
        Map<String, String> cached = origin;
        return cached == null ? Map.of() : cached;
    }

    private static void load(Map<String, String> out, Map<String, String> from) throws Exception {
        Gson gson = new Gson();
        java.lang.reflect.Type type = new TypeToken<Map<String, String>>() {
        }.getType();

        readResource(gson, type, out, from, "/assets/numen_i18n/vanilla_zh_cn.json", "原版");

        Path mods = FMLPaths.MODSDIR.get();
        if (mods != null && Files.isDirectory(mods)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(mods, "*.jar")) {
                for (Path jar : stream) {
                    if (!Files.isRegularFile(jar)) {
                        continue;
                    }
                    try (ZipFile zip = new ZipFile(jar.toFile())) {
                        var entries = zip.entries();
                        while (entries.hasMoreElements()) {
                            ZipEntry entry = entries.nextElement();
                            String entryName = entry.getName();
                            if (!entryName.endsWith("/lang/zh_cn.json")) {
                                continue;
                            }
                            try (InputStream in = zip.getInputStream(entry)) {
                                merge(gson, type, in, out, from, "模组");
                            } catch (Throwable ignored) {
                                // 一个读不动的语言文件不值得毁掉整次扫描
                            }
                        }
                    } catch (Throwable ignored) {
                        // 不是 jar 的文件
                    }
                }
            }
        }

        // 本地语言包：client 资源包里的 zh_cn.json 复制到这儿（服务端读不到客户端目录）
        try {
            Path langDir = FMLPaths.CONFIGDIR.get().resolve("numen").resolve(LANG_DIR);
            if (Files.isDirectory(langDir)) {
                List<Path> files = new ArrayList<>();
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(langDir, "*.json")) {
                    for (Path one : stream) {
                        if (Files.isRegularFile(one)) {
                            files.add(one);
                        }
                    }
                }
                files.sort((a, b) -> a.getFileName().toString().compareTo(b.getFileName().toString()));
                for (Path one : files) {
                    try (InputStream in = Files.newInputStream(one)) {
                        merge(gson, type, in, out, from, "本地语言包");
                    } catch (Throwable ignored) {
                        // 单个文件坏了不影响别的
                    }
                }
            }
        } catch (Throwable ignored) {
            // 没有这个目录就是没有
        }

        // 手写覆盖最后读、直接盖掉（这一份就是用来纠正上面几层的）
        try {
            Path cfg = FMLPaths.CONFIGDIR.get().resolve("numen").resolve(OVERRIDE_FILE);
            if (Files.isRegularFile(cfg)) {
                try (Reader reader = Files.newBufferedReader(cfg, StandardCharsets.UTF_8)) {
                    Map<String, String> one = gson.fromJson(reader, type);
                    if (one != null) {
                        for (Map.Entry<String, String> kv : one.entrySet()) {
                            String value = kv.getValue();
                            if (value == null || value.isBlank()) {
                                continue;
                            }
                            if (kv.getKey().startsWith("_")) {
                                continue; // "_说明" 这种注释行
                            }
                            out.put(kv.getKey(), value);
                            from.put(kv.getKey(), "手写覆盖");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
            // 覆盖文件写坏了就当没有
        }
    }

    private static void readResource(Gson gson, java.lang.reflect.Type type, Map<String, String> out,
                                     Map<String, String> from, String path, String tag) {
        try (InputStream in = I18n.class.getResourceAsStream(path)) {
            if (in != null) {
                merge(gson, type, in, out, from, tag);
            }
        } catch (Throwable ignored) {
            // 打包文件坏了就退回英文名
        }
    }

    private static void merge(Gson gson, java.lang.reflect.Type type, InputStream in, Map<String, String> out,
                              Map<String, String> from, String tag) {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            // 用 JsonParser 而不是 gson.fromJson(Map)：后者遇到重复键会直接抛
            // 「duplicate key: xxx」，整份文件作废。整合包里有九个 jar 的语言文件重复了键
            // （BiomesOPlenty、Quark、jei、vinery、ExtremeReactors、NaturesCompass、
            // CustomNPCs、ApothicAttributes、create_central_kitchen），用 Map 反序列化
            // 等于把这些模组的中文名全丢掉。JsonObject 是普通 map，重复键后者覆盖，不抛。
            com.google.gson.JsonElement root = com.google.gson.JsonParser.parseReader(reader);
            if (root == null || !root.isJsonObject()) {
                return;
            }
            for (Map.Entry<String, com.google.gson.JsonElement> kv
                    : root.getAsJsonObject().entrySet()) {
                com.google.gson.JsonElement value = kv.getValue();
                if (value == null || !value.isJsonPrimitive()) {
                    continue;
                }
                String text = value.getAsString();
                if (text == null || text.isBlank()) {
                    continue;
                }
                if (out.putIfAbsent(kv.getKey(), text) == null) {
                    from.putIfAbsent(kv.getKey(), tag);
                }
            }
        } catch (Throwable ignored) {
            // 单个语言文件坏了不影响别的
        }
    }

    /** 只给调试用：某一段键名（前缀）里有多少条。 */
    public static Map<String, Integer> counts() {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String kind : new String[]{"item.", "block.", "entity.", "effect.", "fluid.",
                "enchantment.", "attribute."}) {
            int n = 0;
            for (String key : table().keySet()) {
                if (key.startsWith(kind)) {
                    n++;
                }
            }
            out.put(kind.substring(0, kind.length() - 1), n);
        }
        return out;
    }
}
