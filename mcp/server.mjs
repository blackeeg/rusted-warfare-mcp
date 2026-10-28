#!/usr/bin/env node
// DSH · 铁锈战争 MCP server
// 纯 stdio JSON-RPC（无第三方依赖），把探针接口、记录分析和维护脚本包成 MCP 工具。
//
// 注册示例（MCP 客户端配置）：
//   "rusted-warfare": {
//     "command": "node",
//     "args": ["D:/Game/rwpp-probe/mcp/server.mjs"]
//   }
import { execFile } from 'node:child_process'
import path from 'node:path'
import fs from 'node:fs'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'

// 项目根目录：默认是本文件上一级，可用环境变量 RWPP_PROBE_ROOT 覆盖
const ROOT = process.env.RWPP_PROBE_ROOT || path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const PROBE = 'http://127.0.0.1:17999'
const RECORDS = path.join(ROOT, 'records')
const TOOLS_DIR = path.join(ROOT, 'tools')
const MAINTAIN = 'D:/Game/dsh-launcher-adapters/scripts/rwpp-maintain.mjs'
const NODE = process.execPath

const SERVER_INFO = { name: 'rusted-warfare', version: '1.0.0' }
const PROTOCOL = '2024-11-05'

async function probe(pathname, timeoutMs = 6000) {
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), timeoutMs)
  try {
    const res = await fetch(PROBE + pathname, { signal: controller.signal })
    const text = await res.text()
    try {
      return { ok: true, data: JSON.parse(text) }
    } catch {
      return { ok: true, data: text }
    }
  } catch (error) {
    return { ok: false, error: String(error && error.message ? error.message : error) }
  } finally {
    clearTimeout(timer)
  }
}

function runNode(script, args = [], timeoutMs = 120000) {
  return new Promise((resolve) => {
    execFile(NODE, [script, ...args], { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (error, stdout, stderr) => {
      resolve({
        ok: !error,
        code: error && typeof error.code === 'number' ? error.code : 0,
        stdout: String(stdout || ''),
        stderr: String(stderr || ''),
      })
    })
  })
}

function runPowerShell(script, args = [], timeoutMs = 120000) {
  return new Promise((resolve) => {
    const argv = ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', script, ...args]
    execFile('powershell', argv, { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (error, stdout, stderr) => {
      resolve({
        ok: !error,
        stdout: String(stdout || ''),
        stderr: String(stderr || ''),
      })
    })
  })
}

// 侧栏面板几何（app 坐标，画面 ÷ 1.25；行距 53，由实测两点定出来）
const PANEL_LEFT_X = 1380
const PANEL_RIGHT_X = 1484
const PANEL_ROW0_Y = 264
const PANEL_ROW_STEP = 53

function panelSlot(index) {
  return {
    x: index % 2 === 0 ? PANEL_LEFT_X : PANEL_RIGHT_X,
    y: PANEL_ROW0_Y + Math.floor(index / 2) * PANEL_ROW_STEP,
  }
}

function textResult(value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value, null, 2)
  return { content: [{ type: 'text', text }] }
}

function errorResult(message) {
  return { content: [{ type: 'text', text: message }], isError: true }
}

const TOOLS = [
  {
    name: 'rw_probe_ping',
    description: '探针是否在线（返回构建号、运行时长、记录文件名）。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'rw_state',
    description: '读取战局快照：是否局内、资金/收入、我方兵种构成、敌方分队伍、地图、UI 面板、选中明细、相机缩放。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'rw_units',
    description: '列出场上单位坐标。side=mine 只看我方，other 只看其他队伍，默认两边都给；neutral=1 时包含 team<0 的地图装饰（树等）。',
    inputSchema: {
      type: 'object',
      properties: {
        side: { type: 'string', enum: ['mine', 'other', 'all'] },
        neutral: { type: 'boolean' },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'rw_panel',
    description: '读取当前选中单位的面板动作列表（名字 + 说明）。选中建筑后能拿到它的建造/升级/生产项。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'rw_missions',
    description: '列出战役关卡（name / map / type）。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'rw_act',
    description: '对游戏执行一个动作。do 可选：move(x,y) / select(关键字) / credits(数值) / spawn(类型,x,y,数量，仅房主) / mission(关卡名) / startmatch / unpause / use(面板动作名，目前是空实现)。',
    inputSchema: {
      type: 'object',
      properties: {
        do: { type: 'string' },
        v: { type: 'string' },
      },
      required: ['do'],
      additionalProperties: false,
    },
  },
  {
    name: 'rw_records_tail',
    description: '读当前记录文件的最后 N 行（状态/事件/输入）。',
    inputSchema: {
      type: 'object',
      properties: { n: { type: 'number' } },
      additionalProperties: false,
    },
  },
  {
    name: 'rw_records_list',
    description: '列出记录文件。',
    inputSchema: { type: 'object', properties: {}, additionalProperties: false },
  },
  {
    name: 'rw_summarize',
    description: '把记录压成摘要：规模、按键、点击（带世界坐标）、经济、战斗、UI 切换、选中变化。可选 since（epoch 毫秒）。',
    inputSchema: {
      type: 'object',
      properties: { since: { type: 'number' } },
      additionalProperties: false,
    },
  },
  {
    name: 'rw_timeline',
    description: '把状态/事件/输入合并成一条时间线。参数 limit 控制行数（默认 80）。',
    inputSchema: {
      type: 'object',
      properties: { limit: { type: 'number' } },
      additionalProperties: false,
    },
  },
  {
    name: 'rw_maintain',
    description: '游戏目录维护：status / stop / backup / rotate / prune-crash。可传 dir 指定游戏目录、keep 指定备份保留份数。',
    inputSchema: {
      type: 'object',
      properties: {
        command: { type: 'string', enum: ['status', 'stop', 'backup', 'rotate', 'prune-crash'] },
        dir: { type: 'string' },
        keep: { type: 'number' },
      },
      required: ['command'],
      additionalProperties: false,
    },
  },
  {
    name: 'rw_panel_map',
    description: '把当前选中单位的面板动作换算成可点击的 app 坐标：每项给出 index/name/x/y。配合 rw_click 就能直接建造或生产。',
    inputSchema: {
      type: 'object',
      properties: {
        match: { type: 'string' },
      },
      additionalProperties: false,
    },
  },
  {
    name: 'rw_click',
    description: '在游戏窗口里发合成点击（app 坐标，画面 ÷ 1.25）。clicks 写法："x,y" 普通、"x,y,c" 按住 Ctrl（面板里一次排 10 个）、"x,y,r" 右键（移动/攻击），多项用分号连接。会先把游戏窗口切到前台。',
    inputSchema: {
      type: 'object',
      properties: {
        clicks: { type: 'string' },
        gapMs: { type: 'number' },
      },
      required: ['clicks'],
      additionalProperties: false,
    },
  },
  {
    name: 'rw_playbook',
    description: '读操作要点（真人可读的作战手册）。doc 可选：guide（画面操作指南）/ build（建造流程推导）/ mission1（第一关复盘）/ index（档案索引），默认给要点摘要 + 文档路径。',
    inputSchema: {
      type: 'object',
      properties: {
        doc: { type: 'string', enum: ['summary', 'guide', 'build', 'mission1', 'index'] },
      },
      additionalProperties: false,
    },
  },
]

async function callTool(name, args = {}) {
  switch (name) {
    case 'rw_probe_ping': {
      const r = await probe('/ping', 3000)
      if (!r.ok) return errorResult('探针没应答：' + r.error + '（游戏可能没启动）')
      return textResult(r.data)
    }
    case 'rw_state': {
      const r = await probe('/state')
      if (!r.ok) return errorResult('探针没应答：' + r.error)
      return textResult(r.data)
    }
    case 'rw_units': {
      const side = args.side || 'all'
      const neutral = args.neutral ? '1' : '0'
      const r = await probe(`/units?side=${encodeURIComponent(side)}&neutral=${neutral}`)
      if (!r.ok) return errorResult('探针没应答：' + r.error)
      return textResult(r.data)
    }
    case 'rw_panel': {
      const r = await probe('/panel')
      if (!r.ok) return errorResult('探针没应答：' + r.error)
      return textResult(r.data)
    }
    case 'rw_missions': {
      const r = await probe('/missions')
      if (!r.ok) return errorResult('探针没应答：' + r.error)
      return textResult(r.data)
    }
    case 'rw_act': {
      if (!args.do) return errorResult('缺少 do 参数')
      const q = `/act?do=${encodeURIComponent(args.do)}` + (args.v ? `&v=${encodeURIComponent(args.v)}` : '')
      const r = await probe(q, 10000)
      if (!r.ok) return errorResult('探针没应答：' + r.error)
      return textResult(r.data)
    }
    case 'rw_records_tail': {
      const n = Math.max(1, Math.min(500, Number(args.n) || 40))
      const r = await probe(`/tail?n=${n}`)
      if (!r.ok) return errorResult('探针没应答：' + r.error)
      return textResult(r.data)
    }
    case 'rw_records_list': {
      if (!fs.existsSync(RECORDS)) return textResult([])
      const files = fs.readdirSync(RECORDS)
        .filter((f) => f.endsWith('.jsonl'))
        .map((f) => ({ file: f, size: fs.statSync(path.join(RECORDS, f)).size }))
        .sort((a, b) => b.file.localeCompare(a.file))
      return textResult(files)
    }
    case 'rw_summarize': {
      const extra = args.since ? ['--since', String(args.since)] : []
      const r = await runNode(path.join(TOOLS_DIR, 'summarize.mjs'), extra)
      const out = (r.stdout || '') + (r.stderr ? '\n[stderr]\n' + r.stderr : '')
      return r.ok ? textResult(out) : errorResult(out || '分析脚本失败')
    }
    case 'rw_timeline': {
      const limit = String(Math.max(1, Math.min(500, Number(args.limit) || 80)))
      const r = await runNode(path.join(TOOLS_DIR, 'timeline.mjs'), [limit])
      const out = (r.stdout || '') + (r.stderr ? '\n[stderr]\n' + r.stderr : '')
      return r.ok ? textResult(out) : errorResult(out || '分析脚本失败')
    }
    case 'rw_maintain': {
      const argv = [args.command]
      if (args.dir) argv.push('--dir', args.dir)
      if (args.keep) argv.push('--keep', String(args.keep))
      const r = await runNode(MAINTAIN, argv, 300000)
      const out = (r.stdout || '') + (r.stderr ? '\n[stderr]\n' + r.stderr : '')
      return r.ok ? textResult(out) : errorResult(out || '维护脚本失败')
    }
    case 'rw_panel_map': {
      const r = await probe('/panel')
      if (!r.ok) return errorResult('探针没应答：' + r.error)
      const data = r.data || {}
      if (data.ok === false) return errorResult(String(data.error || '读不到面板'))
      const actions = Array.isArray(data.actions) ? data.actions : []
      const needle = (args.match || '').toLowerCase()
      const rows = actions.map((a, i) => {
        const slot = panelSlot(i)
        return {
          index: i,
          name: a.b || a.a || '',
          desc: String(a.a || '').slice(0, 60),
          x: slot.x,
          y: slot.y,
          click: `${slot.x},${slot.y}`,
          ctrlClick: `${slot.x},${slot.y},c`,
        }
      }).filter((row) => !needle || row.name.toLowerCase().includes(needle))
      return textResult({ unit: data.unit || null, count: actions.length, rows })
    }
    case 'rw_click': {
      if (!args.clicks) return errorResult('缺少 clicks 参数')
      const script = path.join(TOOLS_DIR, 'click_seq.ps1')
      if (!fs.existsSync(script)) return errorResult('找不到点击脚本：' + script)
      const argv = ['-Clicks', String(args.clicks)]
      if (args.gapMs) argv.push('-GapMs', String(Math.max(50, Math.min(2000, Number(args.gapMs)))))
      const r = await runPowerShell(script, argv, 60000)
      const out = (r.stdout || '') + (r.stderr ? '\n[stderr]\n' + r.stderr : '')
      return r.ok ? textResult(out || '点击已发送') : errorResult(out || '点击失败')
    }
    case 'rw_playbook': {
      const DOCS = {
        guide: path.join(ROOT, '画面操作指南.md'),
        build: path.join(ROOT, '作战档案', '002-沙盒建造推导.md'),
        mission1: path.join(ROOT, '作战档案', '003-战役第一关-DividingRiver.md'),
        index: path.join(ROOT, '作战档案', 'README.md'),
      }
      const doc = args.doc || 'summary'
      if (doc !== 'summary') {
        const file = DOCS[doc]
        if (!file || !fs.existsSync(file)) return errorResult('没有这份文档：' + doc)
        return textResult(fs.readFileSync(file, 'utf8'))
      }
      const lines = [
        '# 铁锈战争 · 操作要点',
        '',
        '## 建造（走鼠标输入）',
        '1. 选中建造者 → 侧栏出现建造列表。',
        '2. 点建筑格 → 出现跟随鼠标的投影。',
        '3. 在目标点点一下 → 建造者自己走过去施工，完工后出现在 rw_units。',
        '4. 资源抽取器必须落资源坑，落位会自动吸附到最近的坑。',
        '坐标用 rw_panel_map 算，别手抄；点击用 rw_click。',
        '',
        '## 生产（走鼠标输入）',
        '- 选中陆军工厂/指挥中心，面板里点兵种。普通点击 = 1 台，Ctrl+点击 = 一次 10 台。',
        '- 一座工厂只有一个队列，多点几座陆军工厂才是真的提速。',
        '- 判据：格子后面出现 (n) 就说明排上了；队列满了再点不会有反应。',
        '',
        '## 指挥（走脚本）',
        '- rw_act do=select v=坦克 选中全军，再用 rw_click 的右键点地面下移动/攻击令。',
        '- /act?do=move 用的 e(float,float) 坐标语义和 rw_units 的世界坐标对不上，别用它指挥。',
        '',
        '## 陷阱',
        '- 游戏失焦或 ESC 菜单打开时画面冻结、点击无效；先点一下窗口或点「继续」。',
        '- 面板格子坐标不能跨面板复用，每次用 rw_panel_map 现算。',
        '- 已死单位仍能被 select 选中，面板会误导；先看 rw_state 的 own。',
        '- 敌方建造者会修建筑，火力不够就别在修复范围里磨。',
        '',
        '文档：',
        '- guide    画面操作指南（窗口校准、点击纪律）',
        '- build    建造流程推导（含坐标换算公式）',
        '- mission1 战役第一关复盘',
        '- index    作战档案索引',
      ]
      return textResult(lines.join('\n'))
    }
    default:
      return errorResult('未知工具：' + name)
  }
}

const rl = readline.createInterface({ input: process.stdin, crlfDelay: Infinity })

function send(message) {
  process.stdout.write(JSON.stringify(message) + '\n')
}

rl.on('line', async (line) => {
  const raw = line.trim()
  if (!raw) return
  let msg
  try {
    msg = JSON.parse(raw)
  } catch {
    return
  }
  const { id, method, params } = msg || {}
  try {
    if (method === 'initialize') {
      send({
        jsonrpc: '2.0',
        id,
        result: {
          protocolVersion: PROTOCOL,
          capabilities: { tools: {} },
          serverInfo: SERVER_INFO,
        },
      })
      return
    }
    if (method === 'notifications/initialized' || method === 'initialized') return
    if (method === 'ping') {
      send({ jsonrpc: '2.0', id, result: {} })
      return
    }
    if (method === 'tools/list') {
      send({ jsonrpc: '2.0', id, result: { tools: TOOLS } })
      return
    }
    if (method === 'tools/call') {
      const name = params && params.name
      const args = (params && params.arguments) || {}
      const result = await callTool(name, args)
      send({ jsonrpc: '2.0', id, result })
      return
    }
    send({ jsonrpc: '2.0', id, error: { code: -32601, message: '未知方法：' + method } })
  } catch (error) {
    send({ jsonrpc: '2.0', id, error: { code: -32603, message: String(error && error.message ? error.message : error) } })
  }
})

process.on('SIGINT', () => process.exit(0))
