// Idempotent literal patcher for files outside the session workspace.
//   node tools/_patch_doc.js <old.md> <new.md> <target.md>
// Requires exactly one occurrence of <old.md>'s trimmed content in the target, and only
// writes when the replacement has not already been applied. Aborts otherwise.
const fs = require('fs');

const [oldF, newF, dst] = process.argv.slice(2);
if (!oldF || !newF || !dst) {
  console.error('usage: node tools/_patch_doc.js <old.md> <new.md> <target.md>');
  process.exit(2);
}

const oldS = fs.readFileSync(oldF, 'utf8').trim();
const newS = fs.readFileSync(newF, 'utf8').trim();
let doc = fs.readFileSync(dst, 'utf8');

if (doc.includes(newS)) {
  console.log('replacement already applied -- nothing to do');
  process.exit(0);
}

const n = doc.split(oldS).length - 1;
if (n !== 1) {
  console.error(`expected exactly 1 occurrence of the old block, found ${n} -- ABORTING, file untouched`);
  process.exit(1);
}

doc = doc.replace(oldS, newS);
fs.writeFileSync(dst, doc);
console.log(`patched ${dst}: ${oldS.length} -> ${newS.length} chars (total ${doc.length})`);
