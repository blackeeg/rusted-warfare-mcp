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
    if (line) replies.push(JSON.parse(line))
  }
})
const send = (m) => child.stdin.write(JSON.stringify(m) + '\n')
send({ jsonrpc: '2.0', id: 1, method: 'initialize', params: { protocolVersion: '2024-11-05', capabilities: {}, clientInfo: { name: 'smoke', version: '1' } } })
send({ jsonrpc: '2.0', method: 'notifications/initialized' })
send({ jsonrpc: '2.0', id: 2, method: 'tools/list' })
send({ jsonrpc: '2.0', id: 3, method: 'tools/call', params: { name: 'rw_records_list', arguments: {} } })
await new Promise((r) => setTimeout(r, 1200))
child.kill()
const init = replies.find((r) => r.id === 1)
const list = replies.find((r) => r.id === 2)
const call = replies.find((r) => r.id === 3)
console.log('initialize:', init && init.result && init.result.serverInfo)
console.log('tools:', list && list.result && list.result.tools.map((t) => t.name).join(', '))
console.log('call rw_records_list:', call && call.result && call.result.content[0].text.slice(0, 160))
