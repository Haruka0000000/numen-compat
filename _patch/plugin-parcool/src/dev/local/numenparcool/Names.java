package dev.local.numenparcool;

import java.util.List;
import java.util.Locale;

/**
 * 27 个 ParCool 动作的英文名、中文名和默认耗气。
 *
 * <p>这张表是硬编码的，顺序就是 {@code ActionList} 的注册顺序（id 0..26）：ParCool 没有给出
 * 「拿默认耗气」的稳定公开入口，而一个插件要是靠反射去猜，换一版就全错。与其猜，不如把 3.4.3.3
 * 这一版的 27 个动作写死——它是这个整合包里唯一会出现的版本，多出来的动作会被当成「表外动作」，
 * 照样列出来，只是没有中文名。
 *
 * <p>{@code StartSwimByCrawl} 故意没有中文名：它是个半成品（ParCool 自己也没给它做说明），
 * 给它编一个中文名会让人以为它是个能用的功能。界面上它退回英文名。
 */
final class Names {

    /** 一个动作：英文 id（ParCool 的类名）、中文名（可能没有）、默认耗气。 */
    record Entry(String id, String zh, int cost) {

        /** 界面上用的名字：有中文就用中文，没有就用英文 id。 */
        String label() {
            return zh == null || zh.isEmpty() ? id : zh;
        }
    }

    private static final List<Entry> ALL = List.of(
            new Entry("BreakfallReady", "受身", 0),
            new Entry("CatLeap", "猫式远跳", 200),
            new Entry("ChargeJump", "蓄力高跳", 100),
            new Entry("ClimbPoles", "爬杆", 0),
            new Entry("ClimbUp", "攀登", 150),
            new Entry("ClingToCliff", "猫挂", 2),
            new Entry("HideInBlock", "方块藏匿", 0),
            new Entry("Crawl", "匍匐", 0),
            new Entry("Dive", "鱼跃下高", 0),
            new Entry("Dodge", "躲闪", 80),
            new Entry("FastRun", "疾跑", 2),
            new Entry("FastSwim", "速游", 3),
            new Entry("Flipping", "空翻", 80),
            new Entry("HangDown", "挂杠", 3),
            new Entry("HorizontalWallRun", "横向跑墙", 2),
            new Entry("JumpFromBar", "挂杠荡跳", 100),
            new Entry("QuickTurn", "快速转向", 0),
            new Entry("RideZipline", "使用滑索", 2),
            new Entry("Roll", "翻滚缓冲", 100),
            new Entry("SkyDive", "高空俯冲", 0),
            new Entry("Slide", "滑铲", 0),
            new Entry("Tap", "四肢缓冲", 100),
            new Entry("Vault", "撑越", 50),
            new Entry("VerticalWallRun", "竖向跑墙", 150),
            new Entry("WallJump", "蹬墙跳", 120),
            new Entry("WallSlide", "滑墙", 8),
            new Entry("StartSwimByCrawl", null, 0));

    private Names() {
    }

    /** 全部 27 个，按 ParCool 的注册顺序。 */
    static List<Entry> all() {
        return ALL;
    }

    /** 按 id 找；表外的动作返回 {@code null}。 */
    static Entry byId(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        String want = id.toLowerCase(Locale.ROOT);
        for (Entry entry : ALL) {
            if (entry.id().toLowerCase(Locale.ROOT).equals(want)) {
                return entry;
            }
        }
        return null;
    }

    /** 按用户写的名字找：中文名、英文 id 都认，全角空格和大小写放宽。表外的返回 {@code null}。 */
    static Entry parse(String text) {
        if (text == null) {
            return null;
        }
        String want = text.trim().replace('\u3000', ' ');
        if (want.isEmpty()) {
            return null;
        }
        for (Entry entry : ALL) {
            if (entry.id().equalsIgnoreCase(want) || entry.label().equals(want)) {
                return entry;
            }
        }
        return null;
    }

    /** 能写的名字，给报错用：中文名几个 + 英文 id 几个。 */
    static String examples() {
        return "撑越、滑铲、蹬墙跳（也可以写 Vault、Slide、WallJump）";
    }
}
