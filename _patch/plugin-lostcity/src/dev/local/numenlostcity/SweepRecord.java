package dev.local.numenlostcity;

import com.dwinovo.numen.sdk.ServerCall;
import com.dwinovo.numen.task.TaskRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次「把这一片搜刮干净」的记账本。
 *
 * <p>她边干活边往这里记：开了几个箱子、搬走多少堆、有多少塞进了精妙背包、有多少放弃了、为什么放弃。
 * 收工的时候整本交回去，所以主人问「搜到哪了」不用再翻聊天记录。
 *
 * <p>注意 {@code stopped} 的三个取值，含义完全不同：
 * <ul>
 *   <li>{@code done} —— 这一片全搜完了；</li>
 *   <li>{@code packed} —— 物品栏和背包都塞满了，剩下的她拿不了；</li>
 *   <li>{@code stopped} —— 中途被叫停（{@code numen.task.stop}）或者身体没了。</li>
 * </ul>
 */
public final class SweepRecord extends TaskRecord {

    /** 这次搜的是哪片地皮，例如 {@code multi:deceasedcraft:multi_hospitaldamaged:16,-33}。 */
    public String scope = "";

    /** 排队准备搜的箱子数。 */
    public int planned;

    /** 真正打开过的箱子数（含空箱）。 */
    public int opened;

    /** 打开以后发现是空的（可能已经被人搜过了）。 */
    public int empty;

    /** 搬走的堆数。 */
    public int took;

    /** 其中塞进精妙背包的堆数。 */
    public int stashed;

    /** 放弃的箱子数（去不了、被挡住、够不着）。 */
    public int skipped;

    /** 挖掉的刷怪笼数。 */
    public int spawnersBroken;

    /** 绕开没挖的刷怪笼数。 */
    public int spawnersLeft;

    /** 每一步遇到的麻烦，一句一条，最多留 20 条。 */
    public final List<String> problems = new ArrayList<>();

    /** done / packed / stopped。 */
    public String stopped = "done";

    /** 最后一句中文总结，直接给她念。 */
    public String note = "";

    public SweepRecord(ServerCall source, long deadline) {
        super(source, deadline);
    }

    /** 排队要搜的箱子，按高度由下往上、同高度先近后远。派活的时候填好，任务只照着走。 */
    public List<LostCityApi.Box> queue = new ArrayList<>();

    /** 这一片扫到的刷怪笼，用来判断「进没进触发范围」。 */
    public List<LostCityApi.Spawn> spawners = new ArrayList<>();

    /** reach / leave / ignore。 */
    public String spawnerMode = "reach";

    /** 记一条问题，超过 20 条就不再堆了，免得回报刷屏。 */
    void problem(String what) {
        if (problems.size() < 20) {
            problems.add(what);
        }
    }

    @Override
    public String describe() {
        return "搜刮 " + (scope.isEmpty() ? "这一片" : scope)
                + "（已开 " + opened + " 个箱子，搬走 " + took + " 堆）";
    }
}
