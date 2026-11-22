// Điểm khởi động của dịch vụ cầu nối.
import { mkdirSync } from 'node:fs';
import path from 'node:path';
import { loadConfig, ConfigError } from './config.mjs';
import { Bridge } from './bridge.mjs';
import { EventBuffer } from './events.mjs';
import { AddLedger } from './guard.mjs';
import { SessionStore } from './session-store.mjs';
import { createServer } from './server.mjs';
import { FakeAdapter } from './fake-adapter.mjs';

// Nhật ký chỉ có thông điệp ngắn, KHÔNG có uid, số điện thoại hay tên.
const log = (msg) => console.log(JSON.stringify({ t: new Date().toISOString(), msg }));

let config;
try {
  config = loadConfig();
} catch (e) {
  if (e instanceof ConfigError) {
    console.error(`Cầu nối không khởi động: ${e.message}`);
    process.exit(1);
  }
  throw e;
}

mkdirSync(config.dataDir, { recursive: true, mode: 0o700 });
let adapter;
if (config.mode === 'fake') {
  adapter = new FakeAdapter({ ownId: config.accountId || '1000', autoScanMs: 4000 });
  log('CHẾ ĐỘ GIẢ: không nối Zalo thật');
} else {
  const { ZcaAdapter } = await import('./zca-adapter.mjs');
  adapter = new ZcaAdapter();
}

const bridge = new Bridge({
  config,
  adapter,
  store: new SessionStore(path.join(config.dataDir, 'session.enc'), config.sessionKey),
  ledger: new AddLedger(path.join(config.dataDir, 'adds.json')),
  events: new EventBuffer(),
  log,
});
await bridge.start();
const server = createServer({ bridge, config, fake: config.mode === 'fake' ? adapter : null });
server.listen(config.port, config.host, () => log(`nghe ở cổng ${config.port}, trạng thái ${bridge.status().state}`));

for (const sig of ['SIGTERM', 'SIGINT']) {
  process.on(sig, () => {
    bridge.adapter.stopListening();
    server.close(() => process.exit(0));
  });
}
