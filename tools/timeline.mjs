// 把探针记录（rec-*.jsonl）和鼠标记录（mouse-*.jsonl）按时间戳合并成一条时间线。
// 用法：node timeline.mjs [条数，默认 80] [--dir 记录目录]
import fs from 'node:fs'
import path from 'node:path'

const args = process.argv.slice(2)
let limit = 80
let dir = 'D:/Game/rwpp-probe/records'
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--dir') dir = args[++i]
  else if (/^\d+$/.test(args[i])) limit = Number(args[i])
}

const files = fs.existsSync(dir) ? fs.readdirSync(dir).filter((n) => /\.jsonl$/.test(n)) : []
const rows = []
for (const name of files) {
  const full = path.join(dir, name)
  const text = fs.readFileSync(full, 'utf8')
  for (const line of text.split(/\r?\n/)) {
    if (!line.trim()) continue
    try {
      rows.push(JSON.parse(line))
    } catch {
      // 跳过写了一半的行
    }
  }
}
rows.sort((a, b) => a.t - b.t)
const t0 = rows.length ? rows[0].t : Date.now()
const tail = rows.slice(-limit)

function fmt(row) {
  const at = ((row.t - t0) / 1000).toFixed(2).padStart(8)
  if (row.k === 'key') {
    return `${at}s  按键 ${row.key} ${row.down ? '按下' : '松开'}`
  }
  if (row.k === 'mouse') {
    return `${at}s  鼠标 ${row.btn} ${row.down ? '按下' : '松开'}  (${row.x},${row.y})`
  }
  if (row.k === 'mousewatcher' || row.k === 'inputwatcher') return `${at}s  --- 输入监听启动 ---`
  if (row.k === 'event') {
    const extra = []
    if (row.key) extra.push(`键 ${row.key}`)
    if (row.keyCode != null && !row.key) extra.push(`码 ${row.keyCode}`)
    if (row.message) extra.push(`"${row.message}"`)
    return `${at}s  事件 ${row.name}${extra.length ? '  ' + extra.join(' ') : ''}`
  }
  if (row.k === 'beat') return `${at}s  ·`
  const parts = []
  if (row.gaming != null) parts.push(row.gaming ? '局内' : '菜单')
  if (row.cam) parts.push(`相机(${row.cam[0]},${row.cam[1]})`)
  if (row.zoom != null) parts.push(`缩放${row.zoom}`)
  if (row.sel != null) parts.push(`选中${row.sel}`)
  if (row.obj != null) parts.push(`对象${row.obj}`)
  if (row.cursor) parts.push(`光标(${row.cursor[0]},${row.cursor[1]})`)
  return `${at}s  状态 ${parts.join(' ')}`
}

console.log(`记录目录：${dir}`)
console.log(`文件 ${files.length} 个，共 ${rows.length} 行，显示最后 ${tail.length} 行\n`)
for (const row of tail) console.log(fmt(row))
