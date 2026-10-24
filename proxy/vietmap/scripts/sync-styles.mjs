// Copies the owner's map-editor styles (and their icon sprites) into KV, where the Worker serves them as
// /maps/custom/{day,night}/style.json. Run from proxy/vietmap after re-publishing a style in the editor:
//   node scripts/sync-styles.mjs day=<publicId> night=<publicId>
// Ids come from the editor's Share → Style URL. Keep them out of git: the repo is public.
import { execSync } from 'node:child_process';
import { rmSync, writeFileSync } from 'node:fs';
import { MAPS_ORIGIN, scrubMaps } from '../src/index.js';
import { dropShortDashes, startsWithToIn } from './compat.mjs';

const EDITOR = 'https://tools.vietmap.vn/map-editor';
const SPRITE_FILES = ['sprite.json', 'sprite.png', 'sprite@2x.json', 'sprite@2x.png'];

const ids = Object.fromEntries(process.argv.slice(2).map((a) => a.split('=')));
if (!['day', 'night'].every((name) => /^[0-9a-f]+$/.test(ids[name] ?? ''))) {
  console.error('usage: node scripts/sync-styles.mjs day=<publicId> night=<publicId>');
  process.exit(1);
}

async function get(url) {
  const res = await fetch(url);
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  return Buffer.from(await res.arrayBuffer());
}

const entries = [];
for (const name of ['day', 'night']) {
  // Scrubbing against VietMap's own origin only drops the editor's embedded apikey; the Worker re-points at itself.
  const style = startsWithToIn(
    JSON.parse(scrubMaps((await get(`${EDITOR}/maps/styles/${ids[name]}/style.json`)).toString(), MAPS_ORIGIN)),
  );
  dropShortDashes(style.layers);
  if (JSON.stringify(style).includes('"slice"')) console.warn(`${name}: a "slice" the app can't parse is left; that layer won't draw`);
  const sprite = /\/icon-sprites\/([0-9a-f]+)\/sprite$/.exec(style.sprite ?? '')?.[1];
  if (sprite) {
    for (const file of SPRITE_FILES) {
      const body = await get(`${EDITOR}/styles/api/icon-sprites/${sprite}/${file}`);
      entries.push({ key: `custom:/maps/custom/sprites/${sprite}/${file}`, value: body.toString('base64'), base64: true });
    }
    style.sprite = `${MAPS_ORIGIN}/maps/custom/sprites/${sprite}/sprite`;
  }
  entries.push({ key: `custom:/maps/custom/${name}/style.json`, value: JSON.stringify(style) });
  console.log(`${name}: ${style.name ?? '?'}, sprite ${sprite ?? 'not from the editor, left as is'}`);
}

const file = 'sync-styles.tmp.json'; // relative and space-free: it goes through a shell (npx is a .cmd on Windows)
writeFileSync(file, JSON.stringify(entries));
try {
  execSync(`npx wrangler kv bulk put ${file} --binding KEYS --remote`, { stdio: 'inherit' });
} finally {
  rmSync(file);
}
console.log('Synced. Riders get it within the hour (style max-age).');
