# NeoForge 26.3 分支

村民繁殖观测的 NeoForge 版本，与仓库根目录的 Fabric 版**功能一致、版本号统一**（1.1.1）。

## 为什么要独立成子工程

MC 那一层（`Eval` / `Views` / `StatsStore` / `Cond` / `Lang` / `Json` / 三个界面）
两个加载器**完全共用**；只有 11 个文件是加载器特有的：

```
net/Payloads.java          codec 缓冲区类型不同（ByteBuf vs RegistryFriendlyByteBuf）
net/VbwNetworking.java     注册入口不同（PayloadTypeRegistry vs RegisterPayloadHandlersEvent）
net/PacketSender.java      实现不同（服务端发包）
net/ClientSender.java      实现不同（客户端发包）
net/ConfigDirs.java        实现不同（FabricLoader vs FMLPaths）
VbwMod.java                入口注解与总线
server/ServerHooks.java    事件钩子名
client/VbwClient.java      按键/HUD/渲染注册
client/HUD.java            GuiLayer
client/BedLines.java       RenderLevelStageEvent 子类
client/NameTags.java       RenderNameTagEvent（Fabric 侧没有这个钩子）
client/MerchantHook.java   ScreenEvent.Init.Post
```

其余 17 个文件与根目录 `src/` 逐字节同源。

## 构建

```bash
# 需要 JDK 25；脚本会自动定位并把 GRADLE_USER_HOME 指到本目录下的 .gradle-home
powershell -ExecutionPolicy Bypass -File tools/build.ps1
powershell -ExecutionPolicy Bypass -File tools/build.ps1 -Offline   # 缓存已预热时

# 产物自检
powershell -ExecutionPolicy Bypass -File tools/check-jar.ps1
```

产物：`build/libs/villager-breeding-watch-neoforge-26.3-1.1.1.jar`

## 版本坐标

| 项 | 值 |
|---|---|
| Minecraft | 26.3 |
| 编译用 NeoForge | 26.3.0.67-beta（离线缓存里唯一可用的编译件） |
| 实测运行 | 26.3.0.86 |
| ModDevGradle | 2.0.148 |
| JDK | 25 |

`neoforge.mods.toml` 里的依赖下界写的是 `[26.3.0.67-beta,)`，
所以 0.86 满足；beta 线之间若有 API 漂移，装进实例后需要实测。

## 与 Fabric 版的唯一功能差异

NeoForge 侧有 `RenderNameTagEvent`，可以直接替换原版名牌内容，
头顶只有一行 `#12 ♥ 可繁殖 原版名字`；
Fabric 侧没有等价钩子（已逐个扫描全部 fabric-api jar），只能用
`Gizmos.billboardTextOverMob` 额外画一行。

## 修改共享逻辑时

改完根目录的共享文件，必须同步到本子工程的对应路径，否则两个版本会静默分叉。
共享清单见 `../docs/lessons.md` 第 27 条。
