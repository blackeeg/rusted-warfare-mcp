import { spawn } from 'node:child_process'
const child = spawn(process.execPath, ['mcp/server.mjs'], { stdio: ['pipe', 'pipe', 'inherit'] })
let buf = ''
const replies = []
child.stdout.on('data', (d) => {
  buf += String(d)
  let i
  while ((i = buf.indexOf('\n')) >= 0) {
    const line = buf.slice(0, i).trim()
    buf = buf.slice(i + 1)
    if (line) {
      try { replies.push(JSON.parse(line)) } catch { /* ignore */ }
    }
  }
})
const send = (m) => child.stdin.write(JSON.stringify(m) + '\n')
const call = (id, name, args) =>
  send({ jsonrpc: '2.0', id, method: 'tools/call', params: { name, arguments: args || {} } })

send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 't', version: '1' } } })
send({ jsonrpc: '2.0', method: 'notifications/initialized' })
send({ jsonrpc: '2.0', id: 2, method: 'tools/list' })
call(3, 'rw_playbook', {})
call(4, 'rw_panel_map', {})
call(5, 'rw_playbook', { doc: 'index' })
await new Promise((r) => setTimeout(r, 2500))
child.kill()
const pick = (id) => replies.find((r) => r.id === id)
const list = pick(2)
console.log('tools:', list && list.result && list.result.tools.map((t) => t.name).join(', '))
for (const id of [3, 4, 5]) {
  const r = pick(id)
  const txt = r && r.result && r.result.content && r.result.content[0] && r.result.content[0].text
  console.log('--- id', id, r && r.result && r.result.isError ? '(error)' : '')
  console.log(String(txt || '(no reply)').slice(0, 400))
}
