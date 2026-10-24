// Run: node --test proxy/vietmap/test/index.test.mjs
import { beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { buildUpstreamUrl, deadUntil, mapsMaxAge, resetMemory, scrubMaps } from '../src/index.js';
import { dropShortDashes, startsWithToIn } from '../scripts/compat.mjs';

beforeEach(resetMemory);

const env = { APP_TOKEN: 't0k', VIETMAP_API_KEY: 'SECRET' };
const req = (path, headers = { 'x-app-token': 't0k' }, method = 'GET') =>
  new Request('https://proxy.example' + path, { method, headers });

test('route: keeps repeated points, motorcycle unless car, drops client apikey and unknown params', () => {
  const u = buildUpstreamUrl(
    'https://p/route?point=10.75,106.66&point=10.76,106.67&vehicle=truck&apikey=evil&foo=1&heading=90', 'SECRET');
  assert.equal(u.origin + u.pathname, 'https://maps.vietmap.vn/api/route/v4');
  assert.deepEqual(u.searchParams.getAll('point'), ['10.75,106.66', '10.76,106.67']);
  assert.equal(u.searchParams.get('vehicle'), 'motorcycle');
  assert.deepEqual(u.searchParams.getAll('apikey'), ['SECRET']);
  assert.equal(u.searchParams.get('foo'), null);
  assert.equal(u.searchParams.get('heading'), '90');
  assert.equal(buildUpstreamUrl('https://p/route?point=1,2&vehicle=car&avoid=motorway', 'S').searchParams.get('vehicle'), 'car');
  assert.equal(buildUpstreamUrl('https://p/route?point=1,2', 'S').searchParams.get('vehicle'), 'motorcycle');
});

test('place: passes refid verbatim', () => {
  const refid = 'auto:RAkP/ci+mZ3d=';
  const u = buildUpstreamUrl('https://p/place?refid=' + encodeURIComponent(refid), 'K');
  assert.equal(u.pathname, '/api/place/v4');
  assert.equal(u.searchParams.get('refid'), refid);
});

test('unknown endpoint → null', () => {
  assert.equal(buildUpstreamUrl('https://p/matrix?point=1,2', 'K'), null);
});

test('fetch: rejects missing token, wrong method, unknown path; forwards upstream response', async () => {
  assert.equal((await worker.fetch(req('/route', {}), env)).status, 401);
  assert.equal((await worker.fetch(req('/route', undefined, 'POST'), env)).status, 405);
  assert.equal((await worker.fetch(req('/nope'), env)).status, 404);
  assert.equal((await worker.fetch(req('/route'), { VIETMAP_API_KEY: 'SECRET' })).status, 401); // no APP_TOKEN configured

  const realFetch = globalThis.fetch;
  let called;
  globalThis.fetch = async (url) => { called = String(url); return new Response('{"code":"OK"}', { status: 200, headers: { 'content-type': 'application/json' } }); };
  try {
    const res = await worker.fetch(req('/autocomplete?text=197%20tran%20phu&focus=10.7,106.6&display_type=6'), env);
    assert.equal(res.status, 200);
    assert.deepEqual(await res.json(), { code: 'OK' });
    assert.match(called, /^https:\/\/maps\.vietmap\.vn\/api\/autocomplete\/v4\?/);
    assert.match(called, /apikey=SECRET/);
  } finally {
    globalThis.fetch = realFetch;
  }
});

const fakeKv = (data) => ({
  data,
  writes: 0,
  async get(k) { return data[k] ?? null; },
  async put(k, v) { this.writes++; data[k] = JSON.parse(v); },
});

async function withUpstream(statusFor, fn, body = () => '{}') {
  const realFetch = globalThis.fetch;
  const calls = [];
  const urls = [];
  globalThis.fetch = async (url) => {
    const key = new URL(url).searchParams.get('apikey');
    calls.push(key);
    urls.push(String(url));
    const b = body(String(url));
    return new Response(b.text ?? b, { status: statusFor(key), headers: { 'content-type': b.type ?? 'application/json' } });
  };
  try { await fn(calls, urls); } finally { globalThis.fetch = realFetch; }
}

test('fetch: inherited names like /constructor are unknown endpoints, not upstream errors', async () => {
  for (const p of ['/constructor', '/__proto__', '/toString']) {
    assert.equal((await worker.fetch(req(p), env)).status, 404);
  }
});

// Counts calls per key like the binding; [limit] per key.
const fakeLimiter = (limit) => {
  const seen = new Map();
  return { keys: seen, async limit({ key }) { seen.set(key, (seen.get(key) ?? 0) + 1); return { success: seen.get(key) <= limit }; } };
};

test('rate limit: route and search have their own limiter, keyed per endpoint and IP; over it → 429, no upstream', async () => {
  const ROUTE_LIMIT = fakeLimiter(2);
  const SEARCH_LIMIT = fakeLimiter(1);
  const limited = { ...env, ROUTE_LIMIT, SEARCH_LIMIT };
  const from = (ip, path) => req(path, { 'x-app-token': 't0k', 'cf-connecting-ip': ip });
  await withUpstream(() => 200, async (calls) => {
    const route = [];
    for (let i = 0; i < 3; i++) route.push((await worker.fetch(from('1.1.1.1', '/route?point=1,2'), limited)).status);
    assert.deepEqual(route, [200, 200, 429]);
    assert.equal(calls.length, 2); // the limited one never reached VietMap
    assert.equal((await worker.fetch(from('2.2.2.2', '/route?point=1,2'), limited)).status, 200); // other IP
    assert.equal((await worker.fetch(from('1.1.1.1', '/reverse?lat=1&lng=2'), limited)).status, 200); // other endpoint
    assert.equal((await worker.fetch(from('1.1.1.1', '/reverse?lat=1&lng=2'), limited)).status, 429);
    assert.deepEqual([...ROUTE_LIMIT.keys.keys()], ['route:1.1.1.1', 'route:2.2.2.2']);
    assert.deepEqual([...SEARCH_LIMIT.keys.keys()], ['reverse:1.1.1.1']);
  });
  assert.equal((await worker.fetch(from('1.1.1.1', '/nope'), limited)).status, 404); // unknown paths don't count
  assert.equal(ROUTE_LIMIT.keys.size + SEARCH_LIMIT.keys.size, 3);
});

test('deadUntil: 401/403 until next VN midnight, 429 pauses 5 min, others never', () => {
  const now = Date.UTC(2026, 9, 24, 18, 30); // 01:30 on the 25th in Vietnam
  assert.equal(deadUntil(401, now), Date.UTC(2026, 9, 25, 17, 0)); // 00:00 on the 26th VN
  assert.equal(deadUntil(403, Date.UTC(2026, 9, 24, 16, 59)), Date.UTC(2026, 9, 24, 17, 0));
  assert.equal(deadUntil(429, now), now + 5 * 60_000);
  assert.equal(deadUntil(200, now), null);
  assert.equal(deadUntil(500, now), null);
});

test('route: rotates past a used-up key, parks it in KV once, skips it next time', async () => {
  const KEYS = fakeKv({ keys: { api: ['r1', 'r2', 'r3'] } });
  await withUpstream((k) => (k === 'r1' ? 401 : 200), async (calls) => {
    assert.equal((await worker.fetch(req('/route?point=1,2'), { ...env, KEYS })).status, 200);
    assert.deepEqual(calls, ['r1', 'r2']);
    assert.ok(KEYS.data.dead.r1 > Date.now());
    calls.length = 0;
    assert.equal((await worker.fetch(req('/route?point=1,2'), { ...env, KEYS })).status, 200);
    assert.deepEqual(calls, ['r2']);
    assert.equal(KEYS.writes, 1);
  });
});

test('route: all keys used up → one probe, upstream status passed through, no extra KV writes', async () => {
  const until = deadUntil(401, Date.now());
  const KEYS = fakeKv({ keys: { api: ['a1', 'a2'] }, dead: { a1: until, a2: until } });
  await withUpstream(() => 401, async (calls) => {
    assert.equal((await worker.fetch(req('/route?point=1,2'), { ...env, KEYS })).status, 401);
    assert.deepEqual(calls, ['a1']);
    assert.equal(KEYS.writes, 0);
  });
});

test('route: no KV binding → falls back to the VIETMAP_API_KEY secret', async () => {
  await withUpstream(() => 200, async (calls) => {
    assert.equal((await worker.fetch(req('/reverse?lat=1&lng=2'), env)).status, 200);
    assert.deepEqual(calls, ['SECRET']);
  });
});

test('scrubMaps: style tile URLs point at the Worker and carry no apikey', () => {
  const style = '{"tiles":["https://maps.vietmap.vn/maps/tiles/vlc-1/{z}/{x}/{y}.pbf?apikey=SECRET"],'
    + '"a":"https://maps.vietmap.vn/x?apikey=SECRET&v=2","b":"https://maps.vietmap.vn/y?v=2&apikey=SECRET",'
    + '"glyphs":"https://maps.vietmap.vn/maps/fonts/{fontstack}/{range}.pbf"}';
  const out = scrubMaps(style, 'https://w.dev');
  assert.doesNotMatch(out, /SECRET|apikey|maps\.vietmap\.vn/);
  assert.match(out, /"https:\/\/w\.dev\/maps\/tiles\/vlc-1\/\{z\}\/\{x\}\/\{y\}\.pbf"/);
  assert.match(out, /"https:\/\/w\.dev\/x\?v=2"/);
  assert.match(out, /"https:\/\/w\.dev\/y\?v=2"/);
});

test('mapsMaxAge: only tiles, fonts and styles are proxied', () => {
  assert.equal(mapsMaxAge('/maps/tiles/vlc-1/1/2/3.pbf'), 30 * 86_400);
  assert.equal(mapsMaxAge('/maps/fonts/Roboto/0-255.pbf'), 30 * 86_400);
  assert.equal(mapsMaxAge('/maps/styles/tm/style.json'), 86_400);
  assert.equal(mapsMaxAge('/maps/custom/day/style.json'), 3_600);
  assert.equal(mapsMaxAge('/maps/custom/sprites/89cb/sprite@2x.png'), 30 * 86_400);
  assert.equal(mapsMaxAge('/maps/statics/tm'), null);
});

test('sync: "starts with" slices become `in`, nested anywhere; other expressions untouched', () => {
  const name = ['downcase', ['to-string', ['get', 'name']]];
  const layer = {
    id: 'poi', filter: ['all', ['==', 'class', 'hospital'], ['any', ['==', ['slice', name, 0, 3], 'bv ']]],
    layout: { 'symbol-sort-key': ['case', ['==', ['slice', name, 0, 3], 'nh '], 1, 2], 'text-size': 12 },
  };
  assert.deepEqual(startsWithToIn({ layers: [layer] }), { layers: [{
    id: 'poi', filter: ['all', ['==', 'class', 'hospital'], ['any', ['in', 'bv ', name]]],
    layout: { 'symbol-sort-key': ['case', ['in', 'nh ', name], 1, 2], 'text-size': 12 },
  }] });
});

test('sync: a one-number dash array is dropped (solid line); real dashes and zoom stops stay', () => {
  const stops = { stops: [[6, [1, 1]], [10, [2, 2]]] };
  const layers = dropShortDashes([
    { id: 'a', paint: { 'line-dasharray': [0], 'line-width': 2 } },
    { id: 'b', paint: { 'line-dasharray': [2, 1] } },
    { id: 'c', paint: { 'line-dasharray': stops } },
    { id: 'd' },
  ]);
  assert.deepEqual(layers.map((l) => l.paint?.['line-dasharray']), [undefined, [2, 1], stops, undefined]);
  assert.equal(layers[0].paint['line-width'], 2);
});

test('maps: custom styles and sprites come from KV, never upstream; links point at the Worker', async (t) => {
  const KEYS = fakeKv({
    keys: { tile: ['t1'] },
    'custom:/maps/custom/day/style.json': '{"sprite":"https://maps.vietmap.vn/maps/custom/sprites/89cb/sprite",'
      + '"tiles":["https://maps.vietmap.vn/maps/tiles/v/{z}/{x}/{y}.pbf"]}',
    'custom:/maps/custom/sprites/89cb/sprite@2x.png': 'PNG',
  });
  const edge = [];
  globalThis.caches = { default: { async match(k) { edge.push(k); }, async put(k) { edge.push(k); } } };
  t.after(() => { delete globalThis.caches; });
  await withUpstream(() => 200, async (calls) => {
    const style = await worker.fetch(req('/maps/custom/day/style.json?&checksum=1'), { ...env, KEYS });
    assert.equal(style.status, 200);
    assert.equal(style.headers.get('cache-control'), 'max-age=3600');
    assert.equal(await style.text(), '{"sprite":"https://proxy.example/maps/custom/sprites/89cb/sprite",'
      + '"tiles":["https://proxy.example/maps/tiles/v/{z}/{x}/{y}.pbf"]}');
    const png = await worker.fetch(req('/maps/custom/sprites/89cb/sprite@2x.png'), { ...env, KEYS });
    assert.equal(png.headers.get('content-type'), 'image/png');
    assert.equal(await png.text(), 'PNG');
    assert.equal((await worker.fetch(req('/maps/custom/night/style.json'), { ...env, KEYS })).status, 404); // not synced
    assert.equal((await worker.fetch(req('/maps/custom/../keys'), { ...env, KEYS })).status, 404); // only custom: keys
    assert.deepEqual(calls, []); // no tile key spent
    assert.deepEqual(edge, []); // a sync shows up at once: no edge copy to outlive it
  });
});

test('maps: adds a tile key upstream, strips the client one, scrubs style.json, rotates on 401', async () => {
  const KEYS = fakeKv({ keys: { tile: ['t1', 't2'] } });
  const style = () => ({ text: '{"tiles":["https://maps.vietmap.vn/maps/tiles/v/{z}/{x}/{y}.pbf?apikey=t2"]}' });
  await withUpstream((k) => (k === 't1' ? 401 : 200), async (calls, urls) => {
    for (let i = 0; i < 6; i++) {
      const res = await worker.fetch(req('/maps/styles/tm/style.json?apikey=evil'), { ...env, KEYS });
      assert.equal(res.status, 200);
      assert.equal(res.headers.get('cache-control'), 'max-age=86400');
      assert.equal(await res.text(), '{"tiles":["https://proxy.example/maps/tiles/v/{z}/{x}/{y}.pbf"]}');
    }
    assert.ok(!calls.includes('evil'));
    assert.equal(calls.filter((k) => k === 't1').length, 1); // parked after its first 401
    assert.match(urls[0], /^https:\/\/maps\.vietmap\.vn\/maps\/styles\/tm\/style\.json\?apikey=t[12]$/);
    assert.ok(KEYS.data.dead.t1 > Date.now());
  }, style);
});

test('maps: the query is dropped, so cache-busting params share one edge entry and never reach VietMap', async () => {
  const KEYS = fakeKv({ keys: { tile: ['t1'] } });
  const stored = new Map();
  const lookups = [];
  globalThis.caches = {
    default: {
      async match(k) { lookups.push(k); return stored.get(k)?.clone(); },
      async put(k, res) { stored.set(k, res); },
    },
  };
  try {
    await withUpstream(() => 200, async (calls, urls) => {
      for (const q of ['?bust=1', '?bust=2&apikey=evil', '']) {
        assert.equal((await worker.fetch(req(`/maps/tiles/v/14/1/2.pbf${q}`), { ...env, KEYS })).status, 200);
      }
      assert.deepEqual(urls, ['https://maps.vietmap.vn/maps/tiles/v/14/1/2.pbf?apikey=t1']); // one fetch, then hits
      assert.ok(lookups.every((k) => k === 'https://proxy.example/maps/tiles/v/14/1/2.pbf'));
    }, () => ({ text: 'PBF', type: 'application/x-protobuf' }));
  } finally {
    delete globalThis.caches;
  }
});

test('maps: tiles pass through unscrubbed; unknown paths, no key and no token are refused', async () => {
  const KEYS = fakeKv({ keys: { tile: ['t1'] } });
  await withUpstream(() => 200, async () => {
    const res = await worker.fetch(req('/maps/tiles/v/14/1/2.pbf'), { ...env, KEYS });
    assert.equal(res.headers.get('content-type'), 'application/x-protobuf');
    assert.equal(await res.text(), 'PBF apikey=t1');
  }, () => ({ text: 'PBF apikey=t1', type: 'application/x-protobuf' }));
  assert.equal((await worker.fetch(req('/maps/statics/tm'), { ...env, KEYS })).status, 404);
  assert.equal((await worker.fetch(req('/maps/tiles/v/1/2/3.pbf'), env)).status, 503); // no tile keys configured
  assert.equal((await worker.fetch(req('/maps/tiles/v/1/2/3.pbf', {}), { ...env, KEYS })).status, 401);
});
