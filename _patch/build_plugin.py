"""Build one numen plugin jar from its source tree.

Runs javac with *relative* paths (the workspace path contains Chinese characters, and
Windows' javac decodes argv as ANSI, so absolute paths would arrive mangled).

Usage:  python _patch/build_plugin.py plugin-tacz
"""
import glob
import os
import shutil
import subprocess
import sys

JDK = r"C:\Program Files\Java\jdk-17\bin"
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

PLUGINS = {
    "plugin-tacz": "numen_tacz-1.0.0.jar",
    "plugin-sb": "numen_sophisticatedbackpacks-1.0.0.jar",
    "plugin-lso": "numen_legendarysurvivaloverhaul-1.0.0.jar",
    "plugin-lostcity": "numen_lostcities-1.0.0.jar",
    "plugin-corpse": "numen_corpse-1.0.0.jar",
    "plugin-pingwheel": "numen_pingwheel-1.0.0.jar",
    "plugin-rs": "numen_refinedstorage-1.0.0.jar",
    "plugin-power": "numen_immersiveengineering-1.0.0.jar",
    "plugin-hordes": "numen_hordes-1.0.0.jar",
    "plugin-stats": "numen_stats-1.0.0.jar",
    "plugin-skills": "numen_puffish_skills-1.0.0.jar",
    "plugin-vehicles": "numen_mts-1.0.0.jar",
    "plugin-items": "numen_items-1.0.0.jar",
    "plugin-fluid": "numen_fluid-1.0.0.jar",
    "plugin-effects": "numen_effects-1.0.0.jar",
    "plugin-probe": "numen_probe-1.0.0.jar",
    "plugin-cooking": "numen_farmersdelight-1.0.0.jar",
    "plugin-threat": "numen_threat-1.0.0.jar",
    "plugin-schematic": "numen_engineered_schematics-1.0.0.jar",
    "plugin-i18n": "numen_i18n-1.0.0.jar",
    "plugin-parcool": "numen_parcool-1.0.0.jar",
}

# Mod jars the plugin compiles against (the rest of the mods folder is not needed).
EXTRA_MODS = ("tacz-*.jar", "sophisticatedbackpacks-*.jar", "sophisticatedcore-*.jar",
              "curios-forge-*.jar", "legendarysurvivaloverhaul-*.jar", "legendarysurvivaladdon-*.jar",
              "lostcities-*.jar", "lostcity-*.jar", "corpse-forge-*.jar",
              "corpsecurioscompat-*.jar", "cosmeticcorpsecompat-*.jar",
              "open-parties-and-claims-*.jar", "SereneSeasons-*.jar", "refinedstorage-*.jar",
              "Ping-Wheel-*.jar", "create-1.20.1-*.jar", "The-Hordes-*.jar",
              "ImmersiveEngineering-*.jar", "EngineeredSchematics-*.jar",
              "puffish_skills-*.jar", "StorageDrawers-*.jar", "*SecurityCraft*.jar",
              "ParCool-*.jar")

# Merged build: every plugin except the probe compiles into one jar under one mod id, so the
# mod list shows a single entry. The probe stays its own jar (it is a test tool, not a feature).
MERGED = "plugin-all"
MERGED_JAR = "numen_compat-1.0.0.jar"
MERGED_MEMBERS = [name for name in sorted(PLUGINS) if name != "plugin-probe"]


def rel(*parts):
    return os.path.join(*parts)


def classpath(mod):
    """Compile classpath as paths relative to *mod* (javac runs with cwd=mod)."""
    srv = rel("..", "..", "Server")
    srv_abs = os.path.join(ROOT, "Server")
    srg = os.path.join(srv, "libraries", "net", "minecraft", "server",
                       "1.20.1-20230612.114412", "server-1.20.1-20230612.114412-srg.jar")
    if not os.path.exists(os.path.join(mod, srg)):
        raise SystemExit("missing srg jar: " + srg)

    cp = [srg, os.path.join("..", "..", "_patch", "nested_numen_api-forge-1.20.1-0.1.4.1.jar")]
    libs = sorted(glob.glob(os.path.join(srv_abs, "libraries", "**", "*.jar"), recursive=True))
    cp += [os.path.relpath(p, mod) for p in libs]
    for pattern in EXTRA_MODS:
        for extra in sorted(glob.glob(os.path.join(srv_abs, "mods", pattern))):
            cp.append(os.path.relpath(extra, mod))
    # Libraries a mod embeds as jar-in-jar (Create ships Ponder this way); they are not in
    # Server/mods, but a plugin type hierarchy needs them on the compile classpath.
    for extra in sorted(glob.glob(os.path.join(ROOT, "_patch", "_libs", "*.jar"))):
        cp.append(os.path.relpath(extra, mod))
    return cp


def build(name):
    mod = os.path.join(ROOT, "_patch", name)
    if not os.path.isdir(mod):
        raise SystemExit("no such plugin dir: " + mod)

    cp = classpath(mod)

    src = os.path.join(mod, "src")
    sources = sorted(glob.glob(os.path.join(src, "**", "*.java"), recursive=True))
    if not sources:
        raise SystemExit("no sources under " + src)

    classes = os.path.join(mod, "build", "classes")
    if os.path.isdir(classes):
        shutil.rmtree(classes)
    os.makedirs(classes, exist_ok=True)

    argfile = os.path.join(mod, "build", "sources.txt")
    with open(argfile, "w", encoding="ascii") as fh:
        for s in sources:
            fh.write(os.path.relpath(s, mod).replace(os.sep, "/") + "\n")

    javac = os.path.join(JDK, "javac.exe")
    args = [javac, "-encoding", "UTF-8", "-nowarn", "-cp", ";".join(cp),
            "-d", "build/classes", "@build/sources.txt"]
    env = dict(os.environ, JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US")
    rc = subprocess.call(args, cwd=mod, env=env)
    print("javac exit:", rc, "sources:", len(sources), "cp:", len(cp))
    if rc != 0:
        return rc

    res = os.path.join(src, "main", "resources")
    if os.path.isdir(res):
        shutil.copytree(res, classes, dirs_exist_ok=True)

    out = os.path.join(mod, "build", PLUGINS[name])
    if os.path.exists(out):
        os.remove(out)
    rc = subprocess.call([os.path.join(JDK, "jar.exe"), "--create", "--file", out, "-C", classes, "."], cwd=mod)
    print("jar exit:", rc, "->", out, os.path.getsize(out) if os.path.exists(out) else "-")
    return rc


def build_merged():
    """Compile the 20 plugins plus the CompatMod entry point into one jar."""
    base = os.path.join(ROOT, "_patch", MERGED)
    if not os.path.isdir(os.path.join(base, "src")):
        raise SystemExit("no merged source dir: " + base)

    cp = classpath(base)

    sources = sorted(glob.glob(os.path.join(base, "src", "**", "*.java"), recursive=True))
    for name in MERGED_MEMBERS:
        src = os.path.join(ROOT, "_patch", name, "src")
        for path in sorted(glob.glob(os.path.join(src, "**", "*.java"), recursive=True)):
            # Scratch drafts such as plugin-sb/_sb3_snippet.java are not part of the build.
            if os.path.basename(path).startswith("_"):
                continue
            sources.append(path)

    classes = os.path.join(base, "build", "classes")
    if os.path.isdir(classes):
        shutil.rmtree(classes)
    os.makedirs(classes, exist_ok=True)

    argfile = os.path.join(base, "build", "sources.txt")
    with open(argfile, "w", encoding="ascii") as fh:
        for s in sources:
            fh.write(os.path.relpath(s, base).replace(os.sep, "/") + "\n")

    args = [os.path.join(JDK, "javac.exe"), "-encoding", "UTF-8", "-nowarn",
            "-cp", ";".join(cp), "-d", "build/classes", "@build/sources.txt"]
    env = dict(os.environ, JAVA_TOOL_OPTIONS="-Duser.language=en -Duser.country=US")
    rc = subprocess.call(args, cwd=base, env=env)
    print("javac exit:", rc, "sources:", len(sources), "cp:", len(cp))
    if rc != 0:
        return rc

    # Every member's resources land in one tree (assets/, the pingwheel mixin config, the
    # vanilla name table i18n reads by /assets path). plugin-all's own manifest goes last so
    # that exactly one META-INF/mods.toml survives.
    for name in MERGED_MEMBERS:
        res = os.path.join(ROOT, "_patch", name, "src", "main", "resources")
        if os.path.isdir(res):
            shutil.copytree(res, classes, dirs_exist_ok=True)
    shutil.copytree(os.path.join(base, "src", "main", "resources"), classes, dirs_exist_ok=True)

    out = os.path.join(base, "build", MERGED_JAR)
    if os.path.exists(out):
        os.remove(out)
    rc = subprocess.call([os.path.join(JDK, "jar.exe"), "--create", "--file", out, "-C", classes, "."], cwd=base)
    print("jar exit:", rc, "->", out, os.path.getsize(out) if os.path.exists(out) else "-")
    return rc


if __name__ == "__main__":
    if len(sys.argv) < 2:
        raise SystemExit("usage: python _patch/build_plugin.py <plugin-dir> [more plugin dirs]"
                         "\n       python _patch/build_plugin.py merged")
    targets = sys.argv[1:]
    if targets in (["merged"], ["plugin-all"]):
        print("== build merged ({} plugins)".format(len(MERGED_MEMBERS)))
        sys.exit(build_merged())
    worst = 0
    for name in targets:
        print("== build", name)
        worst = max(worst, build(name))
    sys.exit(worst)
