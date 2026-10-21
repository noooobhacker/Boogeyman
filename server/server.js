// BOOGEYMAN signaling server: npm install ws && node server.js
const WebSocket = require('ws');
const wss = new WebSocket.Server({ port: process.env.PORT || 8080 });
const rooms = {};
wss.on('connection', (ws, req) => {
  const q = new URL(req.url, 'http://x').searchParams;
  const room = q.get('room'), role = q.get('role');
  if (!room || !['host', 'viewer'].includes(role)) return ws.close();
  const r = rooms[room] || (rooms[room] = {});
  r[role] = ws;
  const other = () => r[role === 'host' ? 'viewer' : 'host'];
  if (role === 'viewer' && r.host) r.host.send(JSON.stringify({ type: 'peer-joined' }));
  ws.on('message', m => { const o = other(); if (o && o.readyState === 1) o.send(m.toString()); });
  ws.on('close', () => {
    if (r[role] === ws) delete r[role];
    const o = other();
    if (o && o.readyState === 1) o.send(JSON.stringify({ type: 'peer-left' }));
    if (!r.host && !r.viewer) delete rooms[room];
  });
});
console.log('BOOGEYMAN signaling on port', process.env.PORT || 8080);
