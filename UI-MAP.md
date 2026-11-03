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

## 七、社区逆向答案（2026-11-03 吸收，已用 javap 对本地 jar 复核）

来源：另一位在 Steam 版上做同款自动化的逆向者（`交接_给社区.md`、`逆向笔记.md`、`技术总览_交给技术总管.md`）。
本机 `game-lib.jar` 与 Steam 版同一套混淆，**签名逐条对得上**。

### 7.1 执行链为什么全是空壳（已确认）

| 方法 | 字节码实际内容 |
| --- | --- |
| `units.am.b(a.s, boolean)` | `return;` |
| `units.y.a(a.s, float, float)` | `return true;` |
| `units.a.s.a(float, float)` | `return false;` ← 这就是 `f.g.b()` 恒走红叉分支的原因 |
| `units.e.b.a(a.s, boolean)` | `return;` |
| `units.am.a(a.s)` | `return;` |

**结论：`use` 这条路走不通，不是我们姿势不对，是根本没有实现。** 别再试。

### 7.2 真正的入口

**生产 / 升 T2（建筑侧排产）**

```java
building.a(菜单条目, false);   // am.a(units.a.s, boolean)，内部 this.z.a(action, flag, null, null)
```

连续排 10 个要**连发 10 条**（间隔 <1 秒），`flag=true` 不是「一次排 5 个」。
「升级 - T2」也走同一条队列：`units.d.n extends units.a.w`，扣 2000，约 20 秒。

**建造（`units.y` 是所有能建造单位的基类）**

```java
public units.z a(units.au ctx, units.as type, int variant, float worldX, float worldY);
// units.z = { am a; am b; boolean c; a.s d; }
```

最小正确集（四个 `-1`、一个 `false`、一个 `z.c`）：

```java
units.a.s act = builder.a(type, -1, false);
if (act == null) return;              // 类型不对
if (!act.b(builder)) return;          // 真正的可用性判断
units.au ctx = builder.ao();          // 每次必须重新取，不能复用
ctx.b = type;  ctx.n = false;         // n=true = 编辑器模式，跳过全部校验
units.z z = builder.a(ctx, type, -1, wx, wy);
```

坐标：执行版 `a(ctx,type,int,float,float)` 要**世界像素**；校验版 `units.d.d.a(...)` 要**瓦片格号**
（`bL.c(worldX,worldY)` 换算，`bL.r/bL.s` 是 public float 比例）。

**坑**：`z.c` 不可靠（另一位说恒 false），成败看资金变化 + 新对象是否出现。
**代价**：这条路**没有施工过程**——建筑落地即可用、建造者不会走过去。

**移动（整队，带寻路）**

```java
Debug dbg = new Debug(ScriptEngine.scriptEngine.getRoot());
dbg.moveAllUnitsOnTeam(team, x, y);   // 14 秒走到，自动寻路
```

`Debug` 构造器是包私有的（`Debug(Root)`），反射要用 `getDeclaredConstructor`。
同层还有 `setTeamCredits` / `lookAt` / `completeAllUnitsQueues` / `selectNextUnit` 等。

### 7.3 坐标与对象 ID

`gameFramework.w` 是所有游戏对象的祖先：

| 字段 | 含义 |
| --- | --- |
| `eh` | **稳定对象 ID**（不随增删变，索引会漂） |
| `eo` / `ep` | 世界像素 x / y（`units.am.l/m` 早被静态 Paint 遮蔽，别用） |
| `es` | 类型索引（同值会被多类型共用） |

`units.am.a` 是活集合，下标每秒漂 3~10 位——用 `eh` 定位。

### 7.4 可复用的其它结论

- 选中：`fw.bS.y()` 清空 + `fw.bS.j(unit)` 选中（`Debug.selectNextUnit` 的字节码就是这两句）
- 建造菜单：`units.e.b.a(ArrayList, int tech)`，每项 `units.a.v`：`i()` 类型、`c()` 价格、`b()` 名称、`e()` 动作种类
- `units.av` 命令枚举 17 项：`a=move b=attack c=build d=repair e=loadInto f=unloadAt g=reclaim h=attackMove i=loadUp j=patrol k=guard l=guardAt m=touchTarget n=follow o=triggerAction p=triggerActionWhenInRange q=setPassiveTarget`
- `units.d.k` 队列读当前任务是 `queue.b()`，不是 `dw()`
- 改字段会破坏寻路/碰撞（单位进海卡死、建筑被判非法消失）——**数值层只读**
- **UI 调用必须在主线程**，否则 `rocketConnector64.dll` 原生崩溃；我们全走 `Game.post` 是对的
- 方法查找要按「类名.方法名 + 描述符」，`getMethods()` 拿不到包私有父类的公开方法（本轮实测踩到）
