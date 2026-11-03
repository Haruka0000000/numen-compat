package dev.local.numenlostcity;

import com.dwinovo.numen.agent.script.ApiError;
import com.dwinovo.numen.agent.script.ErrorKind;
import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.Omitted;
import com.dwinovo.numen.sdk.SeeAlso;
import com.dwinovo.numen.sdk.ServerCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Lets the companion write things down herself: lessons from a run, and whole skills.
 *
 * <p>Skills are the engine's own mechanism (a {@code SKILL.md} directory), so the only thing missing
 * was a way for her to author one from inside the game. Everything here is plain text on disk —
 * nothing is executed, nothing is deleted, and every write is bounded.
 */
public final class LearnApi {

    private static Path base;
    private static Path skills;
    private static final int MAX_BODY = 20000;
    private static final int MAX_LINES = 400;

    private LearnApi() {
    }

    static void init(NumenApi numen) {
        try {
            base = numen.configDir().resolve("lostcity");
            Files.createDirectories(base);
            Path onClient = clientSkills(numen.configDir());
            skills = onClient != null ? onClient : numen.configDir().resolve("skills");
            Files.createDirectories(skills);
        } catch (Throwable t) {
            base = null;
            skills = null;
        }
        // Lessons live on the server but the brain runs on the owner's client, so the only way a note
        // she wrote reaches her next turn is through the body-state fragment the server sends along.
        try {
            numen.contributeBodyState(her -> {
                String recent = tail(3);
                if (recent == null || recent.isBlank()) {
                    return "";
                }
                return "经验：" + clip(recent, 420);
            });
        } catch (Throwable ignored) {
            // fragments unavailable: lessons stay readable through lostcity.learn.lessons only
        }
    }

    /**
     * 她的技能表在客户端：{@code Client/.minecraft/versions/<版本>/config/numen/skills}。服务端写自己的
     * {@code config/numen/skills} 她根本读不到（以前就是这样，写了等于白写），所以先往上找到那个目录。
     * 找不到（比如服务端和客户端真在两台机器上）才退回服务端自己那份。
     */
    private static Path clientSkills(Path serverConfig) {
        try {
            Path root = serverConfig;
            for (int i = 0; i < 3 && root != null; i++) {
                root = root.getParent();
            }
            if (root == null) {
                return null;
            }
            Path versions = root.resolve("Client").resolve(".minecraft").resolve("versions");
            if (!Files.isDirectory(versions)) {
                return null;
            }
            try (var stream = Files.list(versions)) {
                return stream.filter(Files::isDirectory)
                        .map(p -> p.resolve("config").resolve("numen").resolve("skills"))
                        .filter(Files::isDirectory)
                        .findFirst()
                        .orElse(null);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static Path lessonsFile() {
        return base.resolve("lessons.md");
    }

    @Doc("她自己写的一课：情况 + 结论")
    public record LessonArgs(
            @Doc("当时是什么情况（怎么认出来的、在哪）") String situation,
            @Doc("结论：下次遇到同样情况该怎么做") String lesson,
            @Doc("归到哪个主题，例如 搜刮 / 战斗 / 上楼 / 传说生存") @Omitted("通用") Optional<String> tag
    ) {}

    @Doc("写进去了")
    public record Saved(
            @Doc("写到了哪个文件（相对 config/numen）") String file,
            @Doc("这个文件现在一共几行") int lines,
            @Doc("下一步怎么办") String note
    ) {}

    @Doc("她自己记下的一课")
    public record LessonList(
            @Doc("取回几行") int count,
            @Doc("一共记了多少行") int total,
            @Doc("每行一条：日期 主题 情况 → 结论") List<String> items
    ) {}

    @Doc("写一份技能（会真的生成 SKILL.md）")
    public record SkillArgs(
            @Doc("技能名（同时是文件夹名），例如 高架桥搜刮") String name,
            @Doc("技能正文（markdown）：什么时候用、怎么做、什么情况别用") String body,
            @Doc("一句话说明，模型靠它决定要不要读这份技能") @Omitted("用正文第一行") Optional<String> description,
            @Doc("true = 覆盖同名的旧技能") @Omitted("false，同名会报错让你换名字") Optional<Boolean> overwrite,
            @Doc("true = 把同名技能停用（改名成 .disabled），不删内容") @Omitted("false") Optional<Boolean> disable
    ) {}

    @Doc("技能写好了")
    public record SkillSaved(
            @Doc("技能文件在哪（相对 config/numen）") String file,
            @Doc("正文多少字符") int chars,
            @Doc("叫什么") String name,
            @Doc("下一步怎么办（两边都要重载才生效）") String note
    ) {}

    private static String stamp() {
        LocalDate d = LocalDate.now();
        return d.toString();
    }

    public static final class SelfFns {

        private SelfFns() {
        }

        @Fn("把这次的教训记下来（她自己的经验库，下次开局还在）")
        @Example("lostcity.learn.lesson('失落的城市里同一栋楼重复出现，半径 24 会扫到隔壁楼', '先 loot.plan 看 scope，scope 变了就是换楼了，别按旧路线走', {tag = '搜刮'})")
        @Note("只写文本，不改世界；同一句重复写会被跳过")
        @Note("写完可以用 lostcity.learn.lessons 读回来，确认自己记对了")
        @SeeAlso({"lostcity.learn.lessons", "lostcity.learn.skill", "lostcity.loot.plan"})
        public static Saved lesson(ServerCall call, LessonArgs args) {
            if (base == null) {
                throw new ApiError(ErrorKind.FAILED, "经验存不进去：config/numen/lostcity 写不了",
                        "告诉主人这个目录不可写，她这次只能记在脑子里");
            }
            String situation = oneLine(args.situation());
            String lesson = oneLine(args.lesson());
            if (situation.isEmpty() || lesson.isEmpty()) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "「当时什么情况」和「下次怎么做」两样都要写",
                        "写成 lostcity.learn.lesson('当时是什么情况', '下次该怎么做')");
            }
            String tag = args.tag().map(s -> oneLine(s)).orElse("通用");
            String key = "[" + tag + "] " + situation + " → " + lesson;
            String line = "- " + stamp() + " " + key;
            Path f = lessonsFile();
            try {
                List<String> lines = Files.isRegularFile(f)
                        ? new ArrayList<>(Files.readAllLines(f, StandardCharsets.UTF_8))
                        : new ArrayList<>();
                for (String old : lines) {
                    int cut = old.indexOf(" [");
                    if (cut >= 0 && old.substring(cut + 1).equals(key)) {
                        return new Saved("lostcity/lessons.md", lines.size(), "这条已经记过了，没有重复写");
                    }
                }
                lines.add(line);
                if (lines.size() > MAX_LINES) {
                    lines = new ArrayList<>(lines.subList(lines.size() - MAX_LINES, lines.size()));
                }
                Files.writeString(f, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
                return new Saved("lostcity/lessons.md", lines.size(),
                        "记下了。下次开局先 lostcity.learn.lessons{tag = '" + tag + "'} 读一遍再动手");
            } catch (IOException e) {
                throw new ApiError(ErrorKind.FAILED, "经验写盘失败：" + e.getMessage(),
                        "告诉主人这件事，别自己重试第二遍", e.getMessage());
            }
        }

        @Fn("把记下的教训读回来（默认最近 10 条，可按主题筛）")
        @Example("local l = lostcity.learn.lessons{tag = '搜刮', n = 5}\nfor _, s in ipairs(l.items) do print(s) end")
        @Note("只读")
        @SeeAlso({"lostcity.learn.lesson"})
        public static LessonList lessons(ServerCall call, LessonsArgs args) {
            // 以前越界会被悄悄改成 10：她写 n = 999 得到 10 条，还以为读全了。
            int n = LostCityApi.bounded("n", args.n().orElse(10), 1, 50);
            List<String> all = readLessons();
            String tag = args.tag().map(s -> oneLine(s)).orElse("");
            List<String> picked = new ArrayList<>();
            for (int i = all.size() - 1; i >= 0 && picked.size() < n; i--) {
                String line = all.get(i);
                if (tag.isEmpty() || line.contains("[" + tag + "]")) {
                    picked.add(line);
                }
            }
            // 不要在这里排序：上面是从最新往回挑的，按字典序再排一遍等于把日期抹了，
            // 她会以为最早记的那条才是最近的。
            return new LessonList(picked.size(), all.size(), picked);
        }

        @Fn("写一份技能，让她自己把经验固化成以后每局都能用的说明书")
        @Example("lostcity.learn.skill('高架桥搜刮', '## 什么时候用：楼顶有高架时，先上到高架层再往下搜 ## 怎么做：沿高架走，见到缺口就下去', {description = '上到高架层再往下搜更省路'})")
        @Note("会真的写 config/numen/skills/<名字>/SKILL.md；不执行任何代码")
        @Note("**两边都要重载才生效**：客户端 F3+T / 重启，服务端重启")
        @Note("同名默认不覆盖，要覆盖传 overwrite = true；想停用传 disable = true（改名 .disabled，不删）")
        @SeeAlso({"lostcity.learn.lesson", "lostcity.learn.list"})
        public static SkillSaved skill(ServerCall call, SkillArgs args) {
            if (skills == null) {
                throw new ApiError(ErrorKind.FAILED, "技能目录写不了（找不到可写的 config/numen/skills）",
                        "告诉主人：服务端和客户端的 config/numen/skills 都不可写");
            }
            String name = args.name().trim();
            if (name.isEmpty() || name.length() > 40) {
                throw new ApiError(ErrorKind.BAD_ARGUMENT, "技能名要 1-40 个字",
                        "lostcity.learn.skill('高架桥搜刮', '正文……')");
            }
            for (char c : name.toCharArray()) {
                if ("/\\:*?\"<>|".indexOf(c) >= 0) {
                    throw new ApiError(ErrorKind.BAD_ARGUMENT, "技能名里不能有这个字符（文件夹装不下）：" + c,
                            "用汉字、字母、数字、空格、- 或 _");
                }
            }
            Path folder = skills.resolve(name);
            Path target = folder.resolve("SKILL.md");
            boolean disable = args.disable().orElse(false);
            try {
                if (disable) {
                    if (!Files.isRegularFile(target)) {
                        throw new ApiError(ErrorKind.NOT_FOUND, "没有叫「" + name + "」的技能",
                                "先 lostcity.learn.list() 看有哪些");
                    }
                    Files.move(target, folder.resolve("SKILL.md.disabled"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return new SkillSaved("skills/" + name + "/SKILL.md.disabled", 0, name,
                            "已停用。重启客户端 / F3+T 后它就不在技能表里了。");
                }
                String body = args.body();
                if (body == null || body.isBlank()) {
                    throw new ApiError(ErrorKind.BAD_ARGUMENT, "正文是空的",
                            "lostcity.learn.skill('技能名', '## 什么时候用……')");
                }
                if (body.length() > MAX_BODY) {
                    throw new ApiError(ErrorKind.BAD_ARGUMENT,
                            "正文 " + body.length() + " 字，超过上限 " + MAX_BODY,
                            "把细节塞进 reference，正文只留决策规则");
                }
                boolean overwrite = args.overwrite().orElse(false);
                String desc = args.description().map(s -> oneLine(s)).orElseGet(() -> firstLine(body));
                if (desc.isEmpty()) {
                    desc = name;
                }
                String full = "---\nname: " + name + "\ndescription: " + desc + "\n---\n\n" + body.strip() + "\n";
                if (Files.isRegularFile(target) && !overwrite) {
                    // Repeating a lesson you already know has to be a no-op, not an error: a retry
                    // after a dropped reply would otherwise look like "I cannot save this at all".
                    // Only a *different* body is worth refusing, because that is where work is lost.
                    String existing = Files.readString(target, StandardCharsets.UTF_8);
                    if (existing.equals(full)) {
                        return new SkillSaved("skills/" + name + "/SKILL.md", full.length(), name,
                                "这个技能她已经有一模一样的了，这次没动它。");
                    }
                    throw new ApiError(ErrorKind.BAD_ARGUMENT, "已经有一个叫「" + name + "」的技能了",
                            "内容不一样：换个名字，或者传 overwrite = true 覆盖它");
                }
                Files.createDirectories(folder);
                Files.writeString(target, full, StandardCharsets.UTF_8);
                return new SkillSaved("skills/" + name + "/SKILL.md", full.length(), name,
                        "写好了。**技能两边都要重载**：客户端 F3+T 或重启、服务端重启，之后 numen 才能读到它。");
            } catch (IOException e) {
                throw new ApiError(ErrorKind.FAILED, "技能写盘失败：" + e.getMessage(),
                        "告诉主人这件事，别自己重试第二遍", e.getMessage());
            }
        }

        @Fn("列出她自己写过的技能文件")
        @Example("local l = lostcity.learn.list()\nfor _, s in ipairs(l.items) do print(s) end")
        @Note("只读；只列她/我们写在 config/numen/skills 下的，不列 jar 里自带的")
        public static LessonList list(ServerCall call) {
            List<String> items = new ArrayList<>();
            if (skills != null && Files.isDirectory(skills)) {
                try (var stream = Files.list(skills)) {
                    stream.filter(Files::isDirectory).forEach(p -> {
                        Path f = p.resolve("SKILL.md");
                        Path off = p.resolve("SKILL.md.disabled");
                        try {
                            if (Files.isRegularFile(f)) {
                                items.add(p.getFileName() + " (" + Files.size(f) + " B)");
                            } else if (Files.isRegularFile(off)) {
                                items.add(p.getFileName() + " (已停用)");
                            }
                        } catch (Throwable ignored) {
                            // unreadable folder: skip it
                        }
                    });
                } catch (Throwable ignored) {
                    // unreadable skills dir
                }
            }
            items.sort(Comparator.naturalOrder());
            return new LessonList(items.size(), items.size(), items);
        }

        @Fn("这些经验存在哪（出问题时报给主人）")
        @Example("print(lostcity.learn.about().notes)")
        @Note("只读")
        public static LessonsAbout about(ServerCall call) {
            return new LessonsAbout(
                    base == null ? "(not writable)" : base.toString(),
                    lessonsFile().toString(),
                    skills == null ? "(not writable)" : skills.toString(),
                    "经验写在服务端 config/numen/lostcity/lessons.md；技能目录见上面 skills 那一行——"
                            + "优先写进客户端那份（她读得就是它），客户端不在本机时才退回服务端。");
        }

        private static String oneLine(String raw) {
            return raw == null ? "" : raw.replace('\r', ' ').replace('\n', ' ').trim();
        }

        private static String firstLine(String body) {
            for (String line : body.split("\n")) {
                String t = line.replace("#", "").trim();
                if (!t.isEmpty()) {
                    return t.length() > 120 ? t.substring(0, 120) : t;
                }
            }
            return "";
        }
    }

    @Doc("读教训的参数")
    public record LessonsArgs(
            @Doc("只要这个主题的，例如 搜刮") @Omitted("全部主题") Optional<String> tag,
            @Doc("最近几条（1-50）") @Omitted("10 条") Optional<Integer> n
    ) {}

    @Doc("经验存在哪")
    public record LessonsAbout(
            @Doc("数据目录") String dir,
            @Doc("教训文件") String lessons,
            @Doc("技能目录") String skills,
            @Doc("说明") String notes
    ) {}

    private static List<String> readLessons() {
        if (base == null) {
            return List.of();
        }
        Path f = lessonsFile();
        try {
            if (!Files.isRegularFile(f)) {
                return List.of();
            }
            List<String> all = new ArrayList<>();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.startsWith("- ")) {
                    all.add(line);
                }
            }
            return all;
        } catch (Throwable t) {
            return List.of();
        }
    }

    /** Every lesson as one paragraph, for the body-state fragment. */
    static String tail(int n) {
        List<String> all = readLessons();
        if (all.isEmpty()) {
            return null;
        }
        List<String> picked = all.subList(Math.max(0, all.size() - n), all.size());
        return String.join(" | ", picked);
    }

}
