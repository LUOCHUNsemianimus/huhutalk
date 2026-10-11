// Quick post-generator assertions for phone_watch_3d.json.
// Run from the repo root:  node tools/_verify.js
const fs = require('fs');
const path = require('path');

// Resolved against the repo root, so this works from any checkout / any machine.
const P = path.join(path.resolve(__dirname, '..'),
  'src/main/resources/assets/huhutalk/models/item/phone_watch_3d.json');
const m = JSON.parse(fs.readFileSync(P, 'utf8'));

console.log('--- rotations (java_block allows ONE axis + angle in {-45,-22.5,0,22.5,45}) ---');
m.elements.forEach((e, i) => {
  if (e.rotation) {
    console.log(`el${String(i).padStart(2)} from=${JSON.stringify(e.from).padEnd(30)} axis=${e.rotation.axis} angle=${e.rotation.angle}`);
  }
});

console.log('--- display transforms ---');
['thirdperson_righthand', 'firstperson_righthand', 'ground', 'gui', 'fixed', 'head'].forEach(k => {
  const t = m.display[k];
  console.log(`${k.padEnd(24)} scale=${JSON.stringify(t.scale)} rotation=${JSON.stringify(t.rotation)}`);
});

const ys = [];
m.elements.forEach(e => { ys.push(e.from[1], e.to[1]); });
const h = Math.max(...ys) - Math.min(...ys);
console.log('--- size math ---');
console.log(`model y extent = ${h} units`);
[0.3, 0.6, 1.0, 1.35, 1.8].forEach(s => {
  console.log(`  ground scale ${String(s).padEnd(5)} -> ${(h / 16 * s).toFixed(3)} blocks tall`);
});
