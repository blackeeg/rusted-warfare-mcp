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

## 工具（14 个）

| 工具 | 读/写 | 作用 |
| --- | --- | --- |
| `rw_probe_ping` | 读 | 探针是否在线（构建号、运行时长） |
| `rw_state` | 读 | 战局快照：经济/生产/战斗/UI/选中/相机 |
| `rw_units` | 读 | 单位坐标（`side`=mine/other/all） |
| `rw_panel` | 读 | 当前选中单位的面板动作（名字 + 说明） |
| `rw_missions` | 读 | 战役关卡清单 |
| `rw_act` | 写 | `move` / `select` / `credits` / `spawn` / `mission` / `startmatch` / `unpause` / `use` |
| `rw_panel_map` | 读 | 把面板动作换算成可点击坐标（`index/name/x/y`，附 `click`、`ctrlClick`） |
| `rw_click` | 写 | 合成点击：`"x,y"` 普通、`"x,y,c"` Ctrl、`"x,y,r"` 右键，分号分隔多项 |
| `rw_playbook` | 读 | 操作要点；`doc=guide/build/mission1/index` 读对应文档 |
| `rw_records_tail` | 读 | 记录尾部 N 行 |
| `rw_records_list` | 读 | 记录文件列表 |
| `rw_summarize` | 读 | 记录摘要（按键/点击/经济/战斗/UI/选中） |
| `rw_timeline` | 读 | 合并时间线 |
| `rw_maintain` | 写 | `status` / `stop` / `backup` / `rotate` / `prune-crash` |

## 探针接口

只读：`/ping` `/state` `/units` `/panel` `/missions` `/records` `/tail`
写：`/act?do=move|select|credits|spawn|mission|startmatch|unpause|use`

`/act?do=use` 是空实现（面板动作的执行入口还没找到，详见 [UI-MAP.md](UI-MAP.md)）。所以生产只能走合成点击。

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

## 许可证

未指定。仓库里不含游戏本体与游戏资源；使用时请遵守 Rusted Warfare 自身的使用条款。
