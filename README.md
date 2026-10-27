<div align="center">

<img src="src/main/resources/assets/villagerbreedingwatch/textures/icon.png" width="128" alt="图标">

# 村民繁殖观测 · Villager Breeding Watch

**看懂村民为什么生、为什么不生。**

Minecraft **26.3** · Fabric · 中英双语

作者：[黑山大叔](https://space.bilibili.com/19428259)

</div>

---

## 它解决什么问题

村民繁殖是原版里最容易"看起来一切都对、就是不生"的机制。
床够、食物够、也不在冷却，村民就是不冒心 —— 因为原版**从不告诉你原因**。

这个模组把判定过程摊开给你看：

- **头顶状态** —— `#12 ♥ 可繁殖` / `#12 ✕ 缺食物3点`，编号跨重启稳定
- **床绑定射线** —— 一眼看出这张床今晚会不会被村民丢掉
- **详情页** —— 食物、床、作息、配对对象、全部条件逐条 ✔✘
- **全图统计** —— 出生 / 失败 / 死亡，失败带原因（缺床、床走不到、求爱超时、被打断…）

| 判断 | 依据 |
|---|---|
| 能不能生 | `foodLevel + 背包营养点 ≥ 12`、不在睡觉、`age == 0` |
| 床会不会被丢 | 床 POI 是否还在、距离是否 >16 格、床位票、是否可寻路 |
| 什么时候能生 | 村民作息：IDLE / WORK / MEET / REST，只在 IDLE 发起求爱 |
| 为什么失败 | 在生育窗口附近采样条件后**推断**（原版没有失败事件，界面已标注） |

## 安装

模组有**两个加载器版本**，功能一致、版本号统一为 `1.1.0`，按你用的加载器选一个装：

| 文件 | 加载器 | 依赖 |
|---|---|---|
| `villager-breeding-watch-fabric-26.3-1.1.0.jar` | Fabric | Fabric Loader 0.19.5+ / Fabric API（26.3 任一版本） |
| `villager-breeding-watch-neoforge-26.3-1.1.0.jar` | NeoForge | NeoForge 26.3.0.67+（26.3.0.86 实测） |

1. 按加载器选对应的 jar，放进 `mods/`
2. 需要 **Java 25**
3. 客户端与服务端都要装（单人游戏自动满足）
4. 模组 id 相同，**不能同时装两个版本**

从 [Releases](https://github.com/heiheids/villager-breeding-watch/releases) 下载 jar。

## 按键

| 键 | 作用 |
|---|---|
| `V` | 打开设置 |
| `B` | 打开统计 |
| `N` | 总开关 |

都可在「控制 → 按键绑定 → 村民繁殖观测」里改。

## 射线颜色

| 颜色 | 含义 |
|---|---|
| 🟢 绿实线 | 床正常绑定（存在、可达、16 格内、是自己的） |
| 🟠 橙实线 | 这张床夜间有被丢弃的风险，或不是该村民所有 |
| ⚪ 灰线 | 床距离超过 16 格（原版校验范围外，繁殖时不认） |
| 🔴 红线 | HOME 床已不存在（被拆 / 被抢 / 未加载） |
| 🔵 青虚线 | 该村民没有床，指向最近的空闲床 |

虚线表示：夜间寻路时会丢弃这张床。

## 已知限制（如实说明）

1. **头顶文字受实体同步限制**：只有客户端已经拥有这个实体时才画得出，
   超出约 5 区块（≈80 格）就画不出来。远处的村民请看射线 / 详情页 / 统计页。
2. **头顶名牌的呈现方式两个版本不同**：
   - **NeoForge**：用 `RenderNameTagEvent` 直接替换原版名牌，头顶只有一行
     `#12 ♥ 可繁殖 名字`
   - **Fabric**：Fabric 侧没有等价钩子（已逐个扫描全部 fabric-api jar 确认），
     只能用 `Gizmos` 额外画一行，于是头顶是「原版名字 + 一行」

   这是引擎能力差异，不是实现取舍。
3. **失败原因是推断**，不是原版事件。极端情况下可能标错，界面里带原因码可对照。
4. 床位统计只覆盖已加载的区块。
5. 「常开」模式上限 600 名村民，扫描间隔不小于 1 秒，寻路探测有预算。

## 从源码构建

这个仓库有**两个加载器分支**：

```
src/                  Fabric 版（Loom 的 main + client 双源码集）
neoforge/             NeoForge 版（单一 main，客户端类在 client/ 包下）
docs/                 三份文档
```

```bash
# Fabric 版，需要 JDK 25
powershell -ExecutionPolicy Bypass -File tools/build.ps1

# NeoForge 版
cd neoforge
powershell -ExecutionPolicy Bypass -File tools/build.ps1
```

产物分别在 `build/libs/` 与 `neoforge/build/libs/`。

脚本会自动定位 JDK 25（`-Jdk` 参数 → `JAVA_HOME` → Gradle toolchain 缓存 → PATH），
并把 `GRADLE_USER_HOME` 指到工程内的 `.gradle-home`，不在工程外产生缓存。

**两个分支共用的代码有 17 个文件**，`WatchServer` / `ClientHub` / `Eval` /
`StatsStore` / `Lang` / `Json` / 三个界面都在其中——
加载器差异被收进 `PacketSender` / `ClientSender` / `ConfigDirs` 三个接口。
改共享逻辑时必须两边同步，清单见 [经验与踩坑](docs/lessons.md) 第 27 条。

## 自检工具

这个工程带四道自动检查，专门防"编译通过、运行静默失效"这类问题：

| 工具 | 检查什么 |
|---|---|
| `tools/check-jar.ps1` | 入口点、两套语言文件、键集一致性、误打包 |
| `tools/check-source.ps1` | 哨兵值溢出、维度时钟做闸门、渲染入口、跨侧引用 |
| `tools/RegressionTest.java` | 已踩过的时间闸门 bug（可执行断言，含反证） |
| `tools/diag-log.ps1` | 运行时按链路顺序定位"数据不通"的断点 |

```bash
powershell -ExecutionPolicy Bypass -File tools/check-jar.ps1
powershell -ExecutionPolicy Bypass -File tools/check-source.ps1
java tools/RegressionTest.java
powershell -ExecutionPolicy Bypass -File tools/diag-log.ps1
```

## 文档

| 文档 | 内容 |
|---|---|
| [使用说明](docs/usage.md) | 安装、按键、界面怎么看、多语言、限制、排查 |
| [技术说明](docs/technical.md) | 程序结构、26.3 关键 API 事实、数据格式、统计口径、时间与节拍的硬规则 |
| [经验与踩坑](docs/lessons.md) | 开发踩坑与事故复盘，按"能不能自己查出来"排序 |

## 许可

[MIT](LICENSE)
