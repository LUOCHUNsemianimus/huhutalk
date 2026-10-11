// Generic idempotent appender for files outside the session workspace.
//   node build/_append_doc.js <src.md> <dst.md> "<marker that means already-applied>"
// The DSH file sandbox cannot write D:\Moddevelop, but the IDEA terminal (and therefore
// node run from it) can -- so we write the payload inside the workspace and splice it here.
const fs = require('fs');

const [src, dst, marker] = process.argv.slice(2);
if (!src || !dst || !marker) {
  console.error('usage: node build/_append_doc.js <src.md> <dst.md> "<marker>"');
  process.exit(2);
}

const payload = fs.readFileSync(src, 'utf8').trim();
let doc = fs.readFileSync(dst, 'utf8');

if (doc.includes(marker)) {
  console.log(`marker "${marker}" already present -- nothing to do`);
  process.exit(0);
}

doc = doc.replace(/\s*$/, '') + '\n\n---\n\n' + payload + '\n';
fs.writeFileSync(dst, doc);
console.log(`appended ${payload.length} chars from ${src} -> ${dst} (now ${doc.length} chars)`);
