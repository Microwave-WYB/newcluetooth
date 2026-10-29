// Regenerates src/test/resources/data-js-fixture.json from design/ui-v2/data.js (run from scanner/core-fake):
//   node fixture-from-data-js.mjs > src/test/resources/data-js-fixture.json
// FakeDataParityTest checks the Kotlin port against it.
import { readFileSync } from "node:fs";
import vm from "node:vm";

const context = { window: {} };
vm.runInNewContext(readFileSync(new URL("../design/ui-v2/data.js", import.meta.url), "utf8"), context);
const D = context.window.CluetoothData;
const ids = ["r1", "r2", "r3", "r4", "r5", "r6"];
const out = {
  devices: D.getDevices().map((d) => ({ addr: d.addr, history: d.history,
    structures: d.structures.map((s) => `${s.type}:${s.data}`) })),
  recordings: Object.fromEntries(ids.map((id) => [id, (D.getRecording(id).points || []).map((p) => [p.addr, p.at[0], p.at[1], p.rssi])])),
  routes: Object.fromEntries(ids.map((id) => [id, D.getRecording(id).route])),
  sightings: D.getDevices().slice(0, 3).map((d) => D.getDeviceSightings(d.addr).map((s) => [s.at[0], s.at[1], s.rssi])),
  // every device any recording or the live list references, extras included (the real core's parity seed)
  allDevices: [...new Set([...D.getDevices().map((d) => d.addr),
    ...ids.flatMap((id) => (D.getRecording(id).points || []).map((p) => p.addr))])].map((addr) => {
    const d = D.getDevice(addr);
    return { addr, name: d.name, product: d.product, company: d.company, connectable: d.connectable,
      legacy: d.legacy, structures: d.structures.map((s) => `${s.type}:${s.data}`) };
  }),
  all: { sessions: D.getAllSessionsView().sessions, devices: D.getAllSessionsView().devices },
};
process.stdout.write(JSON.stringify(out) + "\n");
