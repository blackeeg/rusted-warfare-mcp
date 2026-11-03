# rusted-warfare-mcp

给 [Rusted Warfare (铁锈战争) / RWPP](https://corrodinggames.com/) 用的 MCP 服务 + 进程内探针扩展：让 AI 代理读战局、造兵、指挥部队。

纯 stdio JSON-RPC，**无第三方依赖**（只用 Node 内置模块）。

```
AI 客户端 ── MCP(stdio) ──> server.mjs ──HTTP──> 游戏内探针扩展(127.0.0.1:17999) ──> 游戏对象
                                   └──PowerShell 合成点击──> 侧栏面板（建造/生产）
```

## 它解决什么

游戏没有 IPC 接口。这套东西分两条路：

1. **读**：进程内扩展反射调用 RWPP 的 Koin 容器与游戏内部对象，暴露 HTTP 只读接口（战局、单位、面板动作、战役列表）。
2. **写**：
   - 能直接下命令的走 API（选中部队、改资金、开战役、房主局刷兵）；
   - 只能在 UI 上点的走**合成点击**（建造、生产、移动/攻击令），自带窗口校准与前台校验。

## ⚠️ 成熟度：实验品，别当生产工具

这套东西是**试错试出来的**，不是训练出来的。说清楚现状：

- **模型端没有经过任何专门训练。** 它会读接口、会点面板，但怎么打、先造什么、什么时候撤，全靠现场试。实测打**战役第一关**（Normal 难度，也就是最小难度）花了 **10 分 30 秒**，造了 55 台坦克、损失 20 台才勉强推平一个只有 1 座工厂的基地。
- **只在 1 台机器、1 个版本上验证过**：RWPP 1.6.2、Windows、1920×1080 物理分辨率、125% 缩放。换分辨率、换缩放、改 HUD 缩放，面板坐标全部作废，得重新标定。
- **面板坐标是硬编码的经验值**（行距 53、纵向偏移 92、左右列 1380/1484）。不同面板的行数不同，坐标不能复用；每次都得用 `rw_panel_map` 现算。
- **合成点击依赖窗口在前台**。游戏失焦或 ESC 菜单打开时画面会冻结，点击全部无效——这不是 bug，是游戏自己的行为。
- **后期关卡完全没有验证。** 第一关没有防空压力、没有多兵种配合；第二关之后会遇到空中单位，纯坦克流会被打穿。多工厂并行生产也只在理论层面成立，没实测。
- **`/act?do=use` 是空实现**——动作对象背后的 handler 字节码就是 `return;`，这条路游戏里根本没实现，别再试。
- **原生建造/排产（`build2` / `enqueue` / `moveall`）是 2026-11-03 新增的半成品**：方法签名已用 `javap` 对本地 `game-lib.jar` 逐条核对、编译通过，但**游戏内验证没做完**。只有 `rw_buildmenu`（菜单枚举）是实测通的。别把未验证的接口当生产手段。

换句话说：**能演示，能打通第一关，但离"可靠"还远**。想拿它打完整战役，需要大量人工兜底。上面这些限制在 [作战档案/](作战档案/README.md) 里有逐条复盘。

## 快速开始

### 1. 构建并安装探针扩展

需要 JDK 17 和 RWPP 的 API jar（见 [探针说明.md](探针说明.md)）。

```bash
./build.sh                 # 产出 probe.jar
copy probe.jar  <游戏目录>\extension\probe.jar
```

扩展目录里要有 `info.toml`（本仓库自带），然后在 RWPP 的 `io.github.rwpp.config.EnabledExtensions.toml` 里启用它：

```toml
values = [ "probe" ]
```

扩展**只在游戏启动时加载**，改完要重启游戏。

### 2. 注册 MCP

```json
"rusted-warfare": {
  "command": "node",
  "args": ["<本仓库路径>/mcp/server.mjs"]
}
```

根目录默认取 `server.mjs` 的上一级，也可用环境变量 `RWPP_PROBE_ROOT` 覆盖。

### 3. 自检

```bash
node tools/mcp-smoke.mjs    # initialize + tools/list + 一次调用
node tools/mcp-test.mjs     # 顺带验证 rw_playbook / rw_panel_map
```

## 工具（15 个）

| 工具 | 读/写 | 作用 |
| --- | --- | --- |
| `rw_probe_ping` | 读 | 探针是否在线（构建号、运行时长） |
| `rw_state` | 读 | 战局快照：经济/生产/战斗/UI/选中/相机 |
| `rw_units` | 读 | 单位坐标 + 稳定对象 ID `eh`（`side`=mine/other/all） |
| `rw_panel` | 读 | 当前选中单位的面板动作（名字 + 说明） |
| `rw_buildmenu` | 读 | **原生枚举建造菜单**（`index/name/desc/price`），不依赖分辨率 |
| `rw_missions` | 读 | 战役关卡清单 |
| `rw_act` | 写 | `move` / `select` / `credits` / `spawn` / `mission` / `startmatch` / `unpause` / `use` / **`build2`** / **`enqueue`** / **`moveall`** |
| `rw_panel_map` | 读 | 把面板动作换算成可点击坐标（`index/name/x/y`，附 `click`、`ctrlClick`） |
| `rw_click` | 写 | 合成点击：`"x,y"` 普通、`"x,y,c"` Ctrl、`"x,y,r"` 右键，分号分隔多项 |
| `rw_playbook` | 读 | 操作要点；`doc=guide/build/mission1/index` 读对应文档 |
| `rw_records_tail` | 读 | 记录尾部 N 行 |
| `rw_records_list` | 读 | 记录文件列表 |
| `rw_summarize` | 读 | 记录摘要（按键/点击/经济/战斗/UI/选中） |
| `rw_timeline` | 读 | 合并时间线 |
| `rw_maintain` | 写 | `status` / `stop` / `backup` / `rotate` / `prune-crash` |

## 探针接口

只读：`/ping` `/state` `/units` `/panel` `/missions` `/buildmenu` `/records` `/tail`
写：`/act?do=move|select|credits|spawn|mission|startmatch|unpause|use|build2|enqueue|moveall`

`/act?do=use` 是空实现——动作对象背后的 handler 本身就是 `return;`，这条路没实现。
生产 / 升级走 `enqueue`（建筑侧 `building.a(entry,false)`），建造走 `build2`（`units.y.a(ctx,type,-1,x,y)`）。
**这两条 2026-11-03 新增，编译通过但游戏内验证未完成**，详见 [UI-MAP.md](UI-MAP.md) 第七节。

## 已知限制

- 探针只绑 `127.0.0.1:17999`，**没有鉴权**，别暴露到公网。
- 扩展只在游戏启动时加载，改代码必须重启游戏。
- `/act?do=move` 用的是内部 `e(float,float)`，坐标语义和 `/units` 的世界坐标对不上——**指挥用右键点击，别用它**。
- 合成点击要求游戏窗口在前台且尺寸未变；失焦或 ESC 菜单打开时画面冻结、点击无效。
- **一座建筑只有一条生产队列**，Ctrl+点击只是往同一条队里塞 10 个；要提速得用建造者多造几座工厂。
- 面板格子坐标不能跨面板复用，每次用 `rw_panel_map` 现算。

## 文档

| 文件 | 内容 |
| --- | --- |
| [探针说明.md](探针说明.md) | 扩展契约、构建、部署、接口细节 |
| [画面操作指南.md](画面操作指南.md) | 窗口校准、坐标基准、点击纪律、踩过的坑 |
| [UI-MAP.md](UI-MAP.md) | UI 功能 → 底层调用对照，含卡点 |
| [鼠标脚本化调查.md](鼠标脚本化调查.md) | 合成鼠标点击为什么进不去、试过的所有路子 |
| [作战档案/](作战档案/README.md) | 实战复盘：首战、建造流程推导、战役第一关通关 |

## 逆向依据

游戏本体是混淆 Jar（类名可读、方法字段 1~2 字母）。本项目的接口签名全部**对着本机 `game-lib.jar` 用 `javap` 核对**，不靠猜名字。

2026-11-03 吸收了另一位逆向者在 Steam 版上的成果（原生建造配方、建筑侧排产入口、空壳方法清单、`units.av` 命令枚举、`eh` 稳定对象 ID），签名逐条复核通过后写进 [UI-MAP.md](UI-MAP.md) 第七节。混淆映射在同一套（RWPP 与 Steam 版同 `game-lib.jar`），但这些结论来自对方实测，我们这边只验证到菜单枚举。

## 许可证

[MIT](LICENSE) © 2026 blackeeg

仓库里不含游戏本体与游戏资源；使用时请遵守 Rusted Warfare 自身的使用条款。
