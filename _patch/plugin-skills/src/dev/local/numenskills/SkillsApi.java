package dev.local.numenskills;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.api.NumenApi;
import com.dwinovo.numen.sdk.Doc;
import com.dwinovo.numen.sdk.Example;
import com.dwinovo.numen.sdk.Fn;
import com.dwinovo.numen.sdk.Note;
import com.dwinovo.numen.sdk.ServerCall;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import net.puffish.skillsmod.api.Category;
import net.puffish.skillsmod.api.Experience;
import net.puffish.skillsmod.api.SkillsAPI;

/**
 * {@code skills}: what this body has actually trained in Pufferfish Skills.
 *
 * <p>Reads the mod's own category objects, so the numbers are the ones the mod itself shows in its
 * screen: experience level, progress inside the level, how many points are still unspent, and how
 * many nodes in that tree are already unlocked.
 *
 * <p>Only this body's own data. The owner's skill book is not readable from here -- the mod keys
 * everything by the player entity, and this body is its own player entity.
 *
 * <p>Reading only. No point is granted or spent, no skill is unlocked or reset, and no screen is
 * opened.
 */
public final class SkillsApi {

    private static final int MAX_ROWS = 12;

    private SkillsApi() {
    }

    static void install(NumenApi numen) {
        numen.api("skills", "技能树：这具身体练了哪几条线、每线几级、还剩多少点没花。", SkillsApi.class);
    }

    // ---------------------------------------------------------------- shapes

    /** One skill category as it stands for that player. */
    @Doc("一条技能线。")
    public record Tree(
            @Doc("技能线 id，例如 `combat`。") String id,
            @Doc("这条线开了没有。") boolean unlocked,
            @Doc("这条线的等级。") int level,
            @Doc("当前这一级里已经攒了多少经验。") int experience,
            @Doc("当前这一级一共要攒多少经验（这一级经验条的长度）。") int experience_needed,
            @Doc("还差多少经验才升到下一级。") int experience_left,
            @Doc("还剩多少点没花（已经花掉的不算在里面）。") int points,
            @Doc("这条线里已经点开的技能数。") int unlocked_skills,
            @Doc("这条线一共有几个技能。") int total_skills) {
    }

    /** A player's whole skill book. */
    @Doc("一个人的技能树总览。")
    public record Skills(
            @Doc("这是谁。") String who,
            @Doc("最多 12 条线。") List<Tree> trees,
            @Doc("一句话结论，可以直接用。") String line) {
    }

    // ---------------------------------------------------------------- calls

    @Fn("我自己这具身体练过的技能线。")
    @Example("skills.skills.self()")
    @Note("只读。读到的是我这具身体自己的存档，不是主人的——游戏按玩家实体分开存，主人练的东西我这里看不到。"
            + "这具身体一般没练过技能线，所以等级多半是 0、点数多半是 0，这是正常的，不是读失败。"
            + "点了几个技能看 unlocked_skills；还剩多少点没花看 points（已经花掉的不算）。")
    public static Skills self(ServerCall call) {
        ServerPlayer her = call.her();
        String who = her == null ? "我" : "我（" + her.m_5446_().getString() + "）";
        return read(her, who);
    }

    // ---------------------------------------------------------------- guts

    private static Skills read(ServerPlayer player, String who) {
        if (player == null) {
            return new Skills(who, List.of(), who + " 现在不在世界里，读不到技能树");
        }
        List<Tree> rows = new ArrayList<>();
        int lines = 0;
        int totalLevels = 0;
        int totalPoints = 0;
        int opened = 0;
        String failure = null;
        try {
            for (Category category : SkillsAPI.streamCategories().toList()) {
                Tree row = row(player, category);
                if (row == null) {
                    continue;
                }
                lines++;
                totalLevels += row.level();
                totalPoints += row.points();
                opened += row.unlocked_skills();
                if (rows.size() < MAX_ROWS) {
                    rows.add(row);
                }
            }
        } catch (Throwable t) {
            // the mod is mid-reload or a category is broken: report what was read so far, but say so
            failure = t.getClass().getSimpleName();
        }

        String line;
        if (lines == 0) {
            line = who + " 还没有任何技能线数据";
        } else {
            line = who + " 开了 " + lines + " 条技能线，等级合计 " + totalLevels
                    + "，已点开 " + opened + " 个技能，剩 " + totalPoints + " 点没花";
            if (lines > rows.size()) {
                line += "（只列了前 " + rows.size() + " 条）";
            }
        }
        if (failure != null) {
            line += "；有几条线这次没读到（" + failure + "，技能树模组可能正在重载），数字可能不全";
        }
        return new Skills(who, rows, line);
    }

    private static Tree row(ServerPlayer player, Category category) {
        if (category == null) {
            return null;
        }
        try {
            ResourceLocation id = category.getId();
            String name = id == null ? "?" : id.m_135815_();
            boolean unlocked = category.isUnlocked(player);
            Experience experience = category.getExperience().orElse(null);
            int level = experience == null ? 0 : experience.getLevel(player);
            int current = experience == null ? 0 : experience.getCurrent(player);
            int needed = experience == null ? 0 : experience.getRequired(player, level);
            int left = Math.max(0, needed - current);

            // getPointsLeft 才是「还剩多少」：getPoints 加的是发过的来源，点过技能也不会变
            int points = category.getPointsLeft(player);
            int totalSkills = (int) category.streamSkills().count();
            int openSkills = (int) category.streamUnlockedSkills(player).count();
            return new Tree(name, unlocked, level, current, needed, left, points, openSkills, totalSkills);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
