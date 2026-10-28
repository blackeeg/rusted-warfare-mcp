# RWPP 1.6.2 · UI 功能与底层调用对照

来源：反编译 `rwpp-core-desktop`（RWPP 自己的 Compose 界面）+ `game-lib`（游戏原生界面）。
每条都标了出处，没标的就是还没确认。

## 一、主菜单（RWPP 的 Compose 界面）

| UI 功能 | 底层 |
| --- | --- |
| 战役 | `MissionKt`；开关是 UI 单例的 `showMissionView` |
| 遭遇战 / 房间 | `MultiplayerRoomKt`；开关 `showRoomView` |
| 多人游戏 | `MultiplayerKt`；开关 `showMultiplayerView` |
| 模组 | `ModsKt`；开关 `showModsView` |
| 设置 | `SettingsKt`；开关 `showSettingsView` |
| 资源浏览器 | `ResourceBrowserKt`；开关 `showResourceBrowser` |
| 扩展 | `ExtensionsKt`；开关 `showExtensionView` |
| 观看回放 | `ReplaysKt`；开关 `showReplayView` |
| 沙盒编辑器 | 走 `MultiplayerRoomKt` 的分支（`$isSandboxGame` 参数） |

出处：`AppKt$App$3$3$1*`（菜单渲染）、`io/github/rwpp/ui/UI.class`（开关字段）。

## 二、开局（房间 → 开始）

| 动作 | 底层调用 | 出处 |
| --- | --- | --- |
| 选地图 | `GameRoom.setSelectedMap(GameMap)` | `MapsKt` |
| 房间选项（人数/难度/迷雾/初始资金…） | `GameRoom.applyRoomConfig(maxPlayers, sharedControl, credits, units, fog, aiDiff, incomeMult, noNukes, spectators, teamLock, teamMode)` | `MultiplayerRoomKt:6827` |
| 建主（多人） | `Game.hostStartWithPasswordAndMods(boolean, String, boolean)` | `MultiplayerKt:7465` |
| 点「开始」 | 先检查未准备的玩家 → `isHostServer` 为真时 `sendQuickGameCommand("-start")`，否则 `room.startGame()` | `MultiplayerRoomKt:9019-9026` |

**这就是之前卡黑屏的原因**：直接调 `startGame()` 跳过了 `applyRoomConfig` 和房间上下文，开成了一个没有地图/没人准备的空房间。

## 三、游戏内面板（原生 UI）

所有指令经同一个命令控制器：

```
GameFramework.l.B().cf.a(player)  →  命令对象 e
```

`e` 的方法（`gameFramework/e.java`）：

| 方法 | 语义 | 路径点类型 | 出处 |
| --- | --- | --- | --- |
| `a(unit)` | 把单位加进这条命令 | — | `e.java:286` |
| `a(float,float)` | 移动 | `av.a` | `e.java:309` |
| `b(float,float)` | 移动（另一种） | `av.h` | `e.java:314` |
| `a(am target)` | 锁定某个单位（点敌人） | `av.b` | `e.java:319` |
| `a(float,float,as action,int)` | 带动作下达（建造/生产） | `av.c` | `e.java:322` |

路径点内部实现：`units/au.java:172-198`（`a(x,y)` / `b(x,y)` / `a(am)` / `a(x,y,as,int)` 各自设置 `av` 枚举）。

右侧面板按钮点一下 = 取该建筑的 `as` 动作对象，再点地面 = `e.a(x,y,action,1)`。AI 就是这么造的：`game/a/i.java:656` 的 `buildUnit`。

## 四、还没确认的

- `as` 动作对象怎么从建筑类型拿到（builder 的 `e(as)` 返回 `s`，`s.z()` 才给命令对象，绕一层）
- 生产单位走的是不是同一条 `a(x,y,as,n)`（大概率是，对工厂单位）
- 沙盒编辑器分支的具体入口

## 五、对我们接口的启示

1. 开局：应该 `applyRoomConfig → setSelectedMap → startGame` 三步走，而不是单调 `startGame`
2. 建造：`cmd.a(unit)` + `cmd.a(x,y,action,1)`
3. 攻击：`cmd.a(unit)` + `cmd.a(targetUnit)`
4. 移动：`cmd.a(unit)` + `cmd.a(x,y)`（比现在用的 `y.e()` 更正规）

## 六、面板动作的执行链（2026-10-28 补）

读面板已经通了：动作用单位实例上的 `N()`（返回 7 项），每项：

| 方法 | 内容 |
| --- | --- |
| `b()` | 短名（Set Rally / Upgrade - T2 / Builder / Scout / Tank / Hovertank / Artillery） |
| `a()` | 长说明（含 HP / 攻速 / 射程） |

**执行没通**。动作对象自带 `b(am unit)`（`units/a/s.java:215`），内部转给 handler：

```java
public boolean b(am am2) { return this.h.a(am2, false); }
```

而默认 handler `units/a/a.java:24` 是空实现：

```java
public boolean a(am am2, boolean bl2) { return true; }
```

所以调用返回 `true` 但什么都没发生——实测三次 `use Tank`，资金没扣、单位没出、`unitCreated` 0 条。

**下一步**：找到真正执行生产的入口。候选方向：
- 原生面板（不是 RML）的点击回调
- 单位上的建造/生产队列字段
- `units/a/w` 子类里被 override 的 handler
