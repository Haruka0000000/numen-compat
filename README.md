# numen-compat

给 **Numen**（Minecraft 里的 AI 同伴）补「感官」和「动手」能力的一组服务器端兼容插件，
外加一套给她读的技能页。

> **这是非官方的下游配件，不是 Numen 本体。** 本体在 [Dwinovo/minecraft-numen](https://github.com/Dwinovo/minecraft-numen)，
> 引擎与公共 API 在 [Dwinovo/numen-api](https://github.com/Dwinovo/numen-api)。
> 本仓库只放给本体补感官的插件源码与技能页，不含本体的任何代码或素材，也不代表上游立场；
> 「Numen」「言出法随」的名称与品牌归上游所有。

- 目标环境：Minecraft **1.20.1** + Forge **47.x**
- 依赖：Numen `0.1.4+`（非必需——没装时插件会自己跳过注册，服能正常开）
- 产物：一个 `numen_compat-1.0.0.jar`，把下面 20 个插件合成一个模组

## 它能做什么

Numen 本体负责对话与脚本内核，但它不知道玩家手里拿的是什么物品、前面那台沉浸工程的机器怎么用、
箱子里的东西该怎么归类。这个仓库把每一类模组的能力翻译成 Numen 可以**直接调用的 Lua 函数**，
让同伴能自己看、自己动手。

| 组 | 面向的模组 | 干什么 |
|---|---|---|
| `items` / `fluid` / `effects` | 原版 + Curios | 看手上的东西、装备、药水效果、储罐里的液体 |
| `scan` / `stats` / `threat` | 原版 | 扫周围的方块与实体、看自己的数值、判断威胁 |
| `loot` / `building` / `multi` / `stairs` / `reach` / `blueprint` / `learn` | Lost Cities | 认楼、搜刮整片城区、记下搜刮经验 |
| `kitchen` | Farmer's Delight | 认锅、砧板、煎锅、炉子的状态 |
| `corpse` | Corpse | 认尸体、把遗物收回来 |
| `sb` / `rs` / `power` / `tacz` / `vehicles` / `parcool` / `schematic` / `pingwheel` / `hordes` / `lso` / `skills` | 对应模组 | 背包、存储网络、机器、枪、载具、跑酷、蓝图、标记、尸潮、身体状态、技能树 |
| `i18n` | 全部 | 把物品、方块、模组名词翻成中文交给她 |

技能页在 `_skills/`：`skills/` 是玩法技能（怎么开箱子、怎么打龙、怎么过前期），
`plugins/` 是模组专属技能（女仆、YSM 模型、万花筒烹饪）。

## 插件是怎么写出来的

一个插件 = 一个 jar，在 `@Mod` 构造函数里向 Numen 注册一整组 Lua 函数：

```java
NumenPlugins.register("items", ItemsApi::install);
```

`ItemsApi` 里用 `@Fn("说明")` 标注的 static 方法就是 `items.*` 这一组 Lua 函数；参数用 record 承接，
返回值可以是 record、`Pending`、`Job` 或 `void`。需要改世界的动作先调 `call.authorize(...)`
交给 Numen 的权限层裁决——**插件自己不判断「能不能做」**。

## 目录结构

```
_patch/
├── plugin-all/        合并入口：唯一带 @Mod 的类，把 20 个插件的入口反射 new 出来
├── plugin-*/          21 个插件的源码（含测试探针 plugin-probe）
│   ├── src/dev/local/numen<名字>/
│   │   ├── <名字>Mod.java   入口：判依赖 → NumenPlugins.register(组名, …)
│   │   ├── <名字>Api.java   函数本体，@Fn 标注的方法即 Lua 函数
│   │   └── Zh.java          中文文案
│   └── src/main/resources/  mods.toml、pack.mcmeta
├── build_plugin.py    出包（merged 目标出合并版，单目录出分装版）
└── deploy_plugins.py  部署到 Server/mods
_skills/
├── skills/            玩法技能页（SKILL.md）
└── plugins/           模组专属技能页
```

## 需要自备的文件

下面这些是第三方二进制，**不进仓库**，得自己准备：

| 文件 | 用途 | 怎么获得 |
|---|---|---|
| `_patch/nested_numen_api-forge-1.20.1-0.1.4.1.jar` | 编译插件所需的 Numen API | 从已安装的 Numen 模组 jar 里取出内嵌的 api jar |
| `Server/` | 编译期 classpath 与部署目标 | 完整的 1.20.1 Forge 整合包服务端（`Server/mods` 里要有上表那些模组） |

## 构建与部署

需要 **JDK 17** 和 **Python 3.12**。脚本里 `JDK` 常量默认写的是 `C:\Program Files\Java\jdk-17`，换机器改这一行。

```bat
python _patch\build_plugin.py merged      :: 出合并包 numen_compat-1.0.0.jar
python _patch\build_plugin.py plugin-rs   :: 只出某一个插件的分装包
python _patch\deploy_plugins.py           :: 拷进 Server\mods，并清掉旧分装 jar
```

产物在 `_patch/plugin-all/build/` 下。

## 许可证

- 本仓库的**自研代码与文档**采用 [MIT 许可证](LICENSE)，Copyright (c) 2026 Haruka0000000。
- MIT **只覆盖自研部分**。第三方模组的版权与许可证归各自作者，不因本仓库的 LICENSE 而改变。
- 本仓库**不分发任何第三方模组**，构建所需的第三方 jar 由使用者自行准备。
- Numen、Lost Cities、Farmer's Delight、Corpse、Immersive Engineering、Refined Storage、
  Sophisticated Backpacks、TaCZ、The Hordes、ParCool、Curios 等模组的版权归各自作者所有。
