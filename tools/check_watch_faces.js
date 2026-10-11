// Offline check for H4/H6: for every face of every element in phone_watch_3d.json,
// resolve its UV rectangle to atlas pixels and report alpha coverage.
// The generated atlas uses PNG filter type 0 on every row, so inflate + stride slicing is enough.
const fs = require('fs');
const zlib = require('zlib');
const path = require('path');

// Resolved against the repo root, so this works from any checkout / any machine.
const ROOT = path.resolve(__dirname, '..');
const MODEL = path.join(ROOT, 'src/main/resources/assets/huhutalk/models/item/phone_watch_3d.json');
const TEX = path.join(ROOT, 'src/main/resources/assets/huhutalk/textures/item/phone_watch_3d.png');

const model = JSON.parse(fs.readFileSync(MODEL, 'utf8'));
const [tw, th] = model.texture_size;

// --- decode the texture (RGBA, filter 0 only, but handle all filters defensively) ---
const b = fs.readFileSync(TEX);
let off = 8, w = 0, h = 0, ct = 0; const idat = [];
while (off < b.length) {
  const len = b.readUInt32BE(off), t = b.toString('ascii', off + 4, off + 8);
  if (t === 'IHDR') { w = b.readUInt32BE(off + 8); h = b.readUInt32BE(off + 12); ct = b[off + 17]; }
  else if (t === 'IDAT') idat.push(b.subarray(off + 8, off + 8 + len));
  else if (t === 'IEND') break;
  off += 12 + len;
}
const raw = zlib.inflateSync(Buffer.concat(idat));
const ch = ct === 6 ? 4 : 3, stride = w * ch;
const px = Buffer.alloc(w * h * 4);
{
  let pos = 0, prev = Buffer.alloc(stride);
  for (let y = 0; y < h; y++) {
    const ft = raw[pos++], row = Buffer.from(raw.subarray(pos, pos + stride)); pos += stride;
    for (let i = 0; i < stride; i++) {
      const a = i >= ch ? row[i - ch] : 0, bb = prev[i], c = i >= ch ? prev[i - ch] : 0;
      let v = row[i];
      if (ft === 1) v += a; else if (ft === 2) v += bb;
      else if (ft === 3) v += (a + bb) >> 1;
      else if (ft === 4) { const pa = Math.abs(bb - c), pb = Math.abs(a - c), pc = Math.abs(a + bb - 2 * c); v += (pa <= pb && pa <= pc) ? a : (pb <= pc ? bb : c); }
      row[i] = v & 255;
    }
    for (let x = 0; x < w; x++) {
      const o = (y * w + x) * 4;
      px[o] = row[x * ch]; px[o + 1] = row[x * ch + 1]; px[o + 2] = row[x * ch + 2];
      px[o + 3] = ch === 4 ? row[x * ch + 3] : 255;
    }
    prev = row;
  }
}
const alpha = (x, y) => (x < 0 || y < 0 || x >= w || y >= h) ? -1 : px[(y * w + x) * 4 + 3];

const DIRS = ['north', 'south', 'east', 'west', 'up', 'down'];
// The fox-tail decal region is painted as a transparent-background sprite on purpose:
// the item renderer uses a translucent sheet for non-BlockItem items, so alpha 0 there
// means "see the case behind it", not "black". Only that region may be transparent.
const DECAL_REGION = '32,88,60,116';
let bad = 0, faces = 0, worst = { a: 256 };
const byRegion = new Map();
const illegal = [];
const LEGAL = new Set([-45, -22.5, 0, 22.5, 45]);

model.elements.forEach((el, i) => {
  if (el.rotation && !LEGAL.has(el.rotation.angle)) illegal.push(`element[${i}] angle=${el.rotation.angle}`);
  DIRS.forEach(dir => {
    const f = el.faces[dir];
    if (!f) return;
    faces++;
    // model-json uv is in 0..16 space; pixel = uv / 16 * texture_size
    const u1 = f.uv[0] / 16 * tw, v1 = f.uv[1] / 16 * th;
    const u2 = f.uv[2] / 16 * tw, v2 = f.uv[3] / 16 * th;
    const x0 = Math.floor(Math.min(u1, u2)), x1 = Math.ceil(Math.max(u1, u2));
    const y0 = Math.floor(Math.min(v1, v2)), y1 = Math.ceil(Math.max(v1, v2));
    let min = 255, n = 0, sum = 0;
    for (let y = y0; y < Math.max(y1, y0 + 1); y++) {
      for (let x = x0; x < Math.max(x1, x0 + 1); x++) {
        const a = alpha(x, y); n++; sum += a; if (a < min) min = a;
      }
    }
    const avg = sum / n;
    const key = `${x0},${y0},${x1},${y1}`;
    const rec = byRegion.get(key) || { key, n: 0, sum: 0, min: 255, uses: 0 };
    rec.n += n; rec.sum += sum; rec.min = Math.min(rec.min, min); rec.uses++;
    byRegion.set(key, rec);
    if (min === 0 && key !== DECAL_REGION) { bad++; console.log(`  TRANSPARENT element[${i}] ${dir} uv=[${f.uv}] px=[${x0},${y0},${x1},${y1}] minA=${min} avgA=${avg.toFixed(1)}`); }
    if (avg < worst.a) worst = { a: avg, el: i, dir, uv: f.uv.slice(), px: [x0, y0, x1, y1] };
  });
});

console.log('---');
console.log('atlas           :', w + 'x' + h, 'colortype=' + ct, 'texture_size=' + tw + 'x' + th);
console.log('elements        :', model.elements.length);
console.log('faces checked   :', faces);
console.log('faces w/ any transparent pixel (outside the fox-tail decal):', bad);
console.log('lowest average alpha face     :', JSON.stringify(worst));
console.log('per-atlas-region used by the 120 faces (px rect -> avg alpha, min alpha, #faces):');
[...byRegion.values()].sort((p, q) => p.key.localeCompare(q.key)).forEach(r => {
  console.log(`  [${r.key}]  avgA=${(r.sum / r.n).toFixed(1).padStart(6)}  minA=${String(r.min).padStart(3)}  faces=${r.uses}`);
});
console.log('illegal rotation angles       :', illegal.length ? illegal.join(', ') : 'none');
console.log('verdict         :', (bad === 0 && illegal.length === 0) ? 'OK' : 'PROBLEM');
