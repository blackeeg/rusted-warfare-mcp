// 一次操作记录的摘要：规模、按键、点击（含世界坐标估算）、经济/战斗/地图数据、关键时刻。
// 用法：node summarize.mjs [--dir 目录] [--since epoch毫秒]
import fs from 'node:fs'
import path from 'node:path'

const args = process.argv.slice(2)
let dir = 'D:/Game/rwpp-probe/records'
let since = 0
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--dir') dir = args[++i]
  else if (args[i] === '--since') since = Number(args[++i])
}

function readAll(pattern) {
  if (!fs.existsSync(dir)) return []
  const names = fs.readdirSync(dir).filter((n) => pattern.test(n)).sort()
  const rows = []
  for (const name of names) {
    for (const line of fs.readFileSync(path.join(dir, name), 'utf8').split(/\r?\n/)) {
      if (!line.trim()) continue
      try {
        const row = JSON.parse(line)
        if (row.t >= since) rows.push(row)
      } catch {
        /* 半行 */
      }
    }
  }
  return rows
}

// 只读 rec-*.jsonl 和最新的 input-*.jsonl：旧的 mouse-*.jsonl 与修复前的 input 文件是旧标签方案，混进来会重复计数
function latestInput() {
  if (!fs.existsSync(dir)) return null
  const names = fs.readdirSync(dir).filter((n) => /^input-.*\.jsonl$/.test(n))
  if (!names.length) return null
  names.sort((a, b) => fs.statSync(path.join(dir, b)).mtimeMs - fs.statSync(path.join(dir, a)).mtimeMs)
  return names[0]
}

const rows = [...readAll(/^rec-.*\.jsonl$/)]
const inputName = latestInput()
if (inputName) {
  for (const line of fs.readFileSync(path.join(dir, inputName), 'utf8').split(/\r?\n/)) {
    if (!line.trim()) continue
    try {
      const row = JSON.parse(line)
      if (row.t >= since) rows.push(row)
    } catch {
      /* 半行 */
    }
  }
}
rows.sort((a, b) => a.t - b.t)
if (!rows.length) {
  console.log('没有可分析的记录')
  process.exit(0)
}

const t0 = rows[0].t
const tEnd = rows[rows.length - 1].t
const at = (t) => ((t - t0) / 1000).toFixed(2).padStart(8) + 's'

const keys = new Map()
const clicks = []
const events = new Map()
const states = []
const rich = []
const uiTimeline = []
const selTimeline = []
let beats = 0

for (const row of rows) {
  if (row.k === 'key') {
    if (row.down) keys.set(row.key, (keys.get(row.key) || 0) + 1)
    continue
  }
  if (row.k === 'mouse') {
    if (row.down) clicks.push({ t: row.t, btn: row.btn, x: row.x, y: row.y })
    continue
  }
  if (row.k === 'event') {
    events.set(row.name, (events.get(row.name) || 0) + 1)
    continue
  }
  if (row.k === 'beat') { beats += 1; continue }
  if (row.k === 'state') { states.push(row); continue }
  if (row.k === 'ui') { uiTimeline.push(row); continue }
  if (row.k === 'sel') { selTimeline.push(row); continue }
  if (row.k === 'rich') rich.push(row)
}

// 相机/缩放最近的样本，用来把屏幕坐标换算成世界坐标
function nearestState(t) {
  let lo = 0, hi = states.length - 1, best = null
  while (lo <= hi) {
    const mid = (lo + hi) >> 1
    if (states[mid].t <= t) { best = states[mid]; lo = mid + 1 } else { hi = mid - 1 }
  }
  return best && Math.abs(best.t - t) < 1500 ? best : null
}

// 相机据实测是屏幕中心的世界坐标，缩放为 z 时：world = cam + (screen - 中心) / z
function toWorld(click) {
  const s = nearestState(click.t)
  if (!s || !s.cam || !s.zoom) return null
  return [
    Math.round(s.cam[0] + (click.x - 768) / s.zoom),
    Math.round(s.cam[1] + (click.y - 432) / s.zoom),
  ]
}

const zoomVals = states.map((r) => r.zoom).filter((v) => v != null)
const objVals = states.map((r) => r.obj).filter((v) => v != null)
const selVals = states.map((r) => r.sel).filter((v) => v != null)

console.log('=== 规模 ===')
console.log(`时长 ${((tEnd - t0) / 1000).toFixed(1)}s   状态 ${states.length}   富记录 ${rich.length}   心跳 ${beats}   点击 ${clicks.length}   按键 ${[...keys.values()].reduce((a, b) => a + b, 0)}   事件 ${[...events.values()].reduce((a, b) => a + b, 0)}`)

if (keys.size) console.log('按键 ' + [...keys.entries()].sort((a, b) => b[1] - a[1]).map(([k, v]) => `${k}×${v}`).join('  '))
if (events.size) console.log('事件 ' + [...events.entries()].map(([k, v]) => `${k}×${v}`).join('  '))
if (zoomVals.length) console.log(`缩放 ${Math.min(...zoomVals)}..${Math.max(...zoomVals)}   选中峰值 ${Math.max(...selVals, 0)}   场上对象 ${Math.min(...objVals)}..${Math.max(...objVals)}`)

if (rich.length) {
  const credits = rich.map((r) => r.eco && r.eco.credits).filter((v) => typeof v === 'number')
  const income = rich.map((r) => r.eco && r.eco.income).filter((v) => typeof v === 'number')
  const ownCount = rich.map((r) => r.own && r.own.count).filter((v) => typeof v === 'number')
  const ownHp = rich.map((r) => r.own && r.own.hp).filter((v) => typeof v === 'number')
  const enemyCount = rich.map((r) => r.enemy && r.enemy.count).filter((v) => typeof v === 'number')
  const maps = [...new Set(rich.map((r) => r.map && r.map.name).filter(Boolean))]
  console.log('\n=== 地图 ===')
  console.log((maps.length ? maps.join('、') : '未记录') + (rich[rich.length - 1].map ? `  玩家数 ${rich[rich.length - 1].map.players}` : ''))
  console.log('\n=== 经济 ===')
  if (credits.length) console.log(`资金 ${Math.min(...credits)} → ${Math.max(...credits)}（末值 ${credits[credits.length - 1]}）`)
  if (income.length) console.log(`收入 ${Math.min(...income)} → ${Math.max(...income)}（末值 ${income[income.length - 1]}）`)
  console.log('\n=== 生产 / 战斗 ===')
  if (ownCount.length) console.log(`我方单位 ${Math.min(...ownCount)}..${Math.max(...ownCount)}（末值 ${ownCount[ownCount.length - 1]}）`)
  if (ownHp.length) console.log(`我方总血量 ${Math.min(...ownHp)}..${Math.max(...ownHp)}（末值 ${ownHp[ownHp.length - 1]}）`)
  if (enemyCount.length) console.log(`敌方单位 ${Math.min(...enemyCount)}..${Math.max(...enemyCount)}（末值 ${enemyCount[enemyCount.length - 1]}）`)
  // 兵种构成：取最后一条
  const lastRich = [...rich].reverse().find((r) => r.own && r.own.byType)
  if (lastRich) console.log('兵种 ' + Object.entries(lastRich.own.byType).map(([k, v]) => `${k}×${v}`).join('  '))
}

if (clicks.length) {
  console.log('\n=== 点击（含世界坐标估算） ===')
  for (const c of clicks.slice(-30)) {
    const w = toWorld(c)
    console.log(`${at(c.t)}  ${c.btn} (${c.x},${c.y})` + (w ? `  → 世界(${w[0]},${w[1]})` : '  → 世界?(无相机样本)'))
  }
}

if (uiTimeline.length) {
  console.log('\n=== 界面切换 ===')
  for (const r of uiTimeline.slice(-15)) {
    console.log(`${at(r.t)}  ${(r.views && r.views.length) ? r.views.join('+') : '主界面'}`)
  }
}

if (selTimeline.length) {
  console.log('\n=== 选中变化 ===')
  for (const r of selTimeline.slice(-20)) {
    const types = r.byType ? Object.entries(r.byType).map(([k, v]) => `${k}×${v}`).join(' ') : ''
    console.log(`${at(r.t)}  ${r.count} 个  ${types}`)
  }
}
