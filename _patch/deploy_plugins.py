"""把编好的 jar 部署到 Server/mods，文件名带上「给谁做的」中文标签。

命名规则（2026-11 起，用户定的）：
    合并后的合集 jar -> `[全兼容] numen_compat-1.0.0.jar`（20 个不停，模组列表一条）
    探针 jar         -> `[通用] numen_probe-1.0.0.jar`（单独装，测试工具不进合集）
    分装模式（旧）   -> `[中文模组名兼容] numen_<modid>-1.0.0.jar` / `[通用] name.jar`

一个 jar 只服务一个 mod，只提供接口（读 + 过授权的动作），不做任何自动行为。
jar 里的 modId 与代码里的类名都不动，改变的只是磁盘上的文件名——所以玩家看
Server/mods 就知道每个 jar 是给哪个 mod 补的嘴。

用法：
    python _patch/deploy_plugins.py            # 部署合集 jar + 探针，并清掉 20 个旧分装 jar
    python _patch/deploy_plugins.py --split    # 回到分装模式，逐个部署 21 个普通 jar
    python _patch/deploy_plugins.py cooking    # 分装模式下只部署若干（plugin- 前缀可省）
    python _patch/deploy_plugins.py --clean    # 顺手删掉 Server/mods 里已失效的旧名
"""
import os
import shutil
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MODS = os.path.join(ROOT, "Server", "mods")

# 合集 jar：20 个插件编译在一起，模组列表里只有 numen_compat 一条。
MERGED_DIR = "plugin-all"
MERGED_JAR = "numen_compat-1.0.0.jar"
MERGED_LABEL = "[全兼容] "

# 插件目录 -> (目标 modId 或 None, 中文标签)
# modId 为 None 表示这组感官不依赖任何具体 mod（读的是原版或引擎自身的东西）。
TARGETS = {
    # ---- mod 专属：一个 jar 一个 mod ----
    "plugin-cooking": ("farmersdelight", "农夫乐事"),
    "plugin-corpse": ("corpse", "尸体"),
    "plugin-hordes": ("hordes", "尸潮"),
    "plugin-lostcity": ("lostcities", "失落的城市"),
    "plugin-lso": ("legendarysurvivaloverhaul", "生存大修"),
    "plugin-pingwheel": ("pingwheel", "标记轮盘"),
    "plugin-power": ("immersiveengineering", "沉浸工程"),
    "plugin-schematic": ("engineered_schematics", "工程师图纸"),
    "plugin-rs": ("refinedstorage", "精致存储"),
    "plugin-sb": ("sophisticatedbackpacks", "精妙背包"),
    "plugin-skills": ("puffish_skills", "技能树"),
    "plugin-tacz": ("tacz", "TACZ枪械"),
    "plugin-vehicles": ("mts", "交通运输模拟"),
    # ---- 通用：不针对某个 mod ----
    "plugin-stats": (None, "自身状态"),
    "plugin-threat": (None, "威胁"),
    "plugin-items": (None, "物品"),
    "plugin-fluid": (None, "流体"),
    "plugin-effects": (None, "药水效果"),
    "plugin-i18n": (None, "中文名"),
    "plugin-parcool": ("parcool", "跑酷"),
    "plugin-probe": (None, "探针"),
}

# 合并/改名后留在 Server/mods 里的僵尸文件（部署时顺手删掉）。
STALE = [
    "numen_train-1.0.0.jar",
    "[列车] numen_train-1.0.0.jar",
]


def load_plugins():
    """从 build_plugin.py 读 PLUGINS，避免两处维护同一份 jar 名。"""
    sys.path.insert(0, HERE)
    import build_plugin  # noqa: E402
    return build_plugin.PLUGINS


def label(name):
    mod, cn = TARGETS[name]
    return "[" + (cn + "兼容" if mod else "通用") + "] "


def copy_jar(src, prefix, jar, dry=False):
    if not os.path.exists(src):
        print("  skip(no build): " + os.path.relpath(src, ROOT))
        return False
    if dry:
        print("  would copy -> " + prefix + jar)
        return True
    shutil.copy2(src, os.path.join(MODS, prefix + jar))
    print("  deployed:         " + prefix + jar)
    return True


def deploy(name, jar, dry=False):
    return copy_jar(os.path.join(HERE, name, "build", jar), label(name), jar, dry)


def deploy_merged(dry=False):
    ok = copy_jar(os.path.join(HERE, MERGED_DIR, "build", MERGED_JAR), MERGED_LABEL, MERGED_JAR, dry)
    # 探针不进合集：它是测试工具，随时可能被替换，留在外面单独一个 jar。
    ok = deploy("plugin-probe", load_plugins()["plugin-probe"], dry) and ok
    return ok


def expected_names(split):
    if split:
        return {label(name) + jar for name, jar in load_plugins().items()}
    names = {MERGED_LABEL + MERGED_JAR, label("plugin-probe") + load_plugins()["plugin-probe"]}
    return names


def prune(split=False, dry=False):
    """删掉 Server/mods 里任何一个不是本次命名的 numen jar（旧名、合并掉的、改过标签的）。"""
    expected = expected_names(split)
    dropped = 0
    for other in sorted(os.listdir(MODS)):
        if not other.endswith(".jar") or "numen_" not in other or other in expected:
            continue
        if dry:
            print("  would remove:     " + other)
        else:
            os.remove(os.path.join(MODS, other))
            print("  removed stale:    " + other)
        dropped += 1
    return dropped


def main(argv):
    clean = "--clean" in argv
    split = "--split" in argv
    dry = "--dry" in argv
    argv = [a for a in argv if not a.startswith("--")]
    plugins = load_plugins()
    if not os.path.isdir(MODS):
        raise SystemExit("no mods dir: " + MODS)

    if not split:
        if argv:
            raise SystemExit("merged 模式不接受单个插件名；要逐个装请加 --split")
        deploy_merged(dry)
        print("deployed merged jar + probe")
    else:
        if argv:
            picked = []
            for arg in argv:
                key = arg if arg.startswith("plugin-") else "plugin-" + arg
                if key not in plugins:
                    raise SystemExit("unknown plugin: " + arg)
                picked.append(key)
        else:
            picked = list(plugins)
        done = 0
        for name in picked:
            if name not in TARGETS:
                raise SystemExit("no target for " + name + " (add it to TARGETS)")
            done += deploy(name, plugins[name], dry)
        print("deployed %d/%d" % (done, len(picked)))

    if clean:
        for stale in STALE:
            path = os.path.join(MODS, stale)
            if os.path.exists(path):
                if dry:
                    print("  would remove:     " + stale)
                else:
                    os.remove(path)
                    print("  removed stale:    " + stale)
        prune(split, dry)


if __name__ == "__main__":
    main(sys.argv[1:])
