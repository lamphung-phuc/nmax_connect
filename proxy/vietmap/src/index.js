// Holds every VietMap key server-side; the app ships none (VietMap requires REST calls from a backend).
// App calls GET {worker}/{endpoint}?<params> or {worker}/maps/… with header X-App-Token; the Worker adds apikey.
// Keys rotate without an app release: KV `keys` = {"api":[…],"tile":[…]} (edited by hand, see README.md);
// the Worker skips used-up keys via KV `dead` = {key: untilMs}, which only the Worker writes.
const UPSTREAM = 'https://maps.vietmap.vn/api';
export const MAPS_ORIGIN = 'https://maps.vietmap.vn';
// The app's day/night styles and their sprites: copies of the owner's map-editor styles that scripts/sync-styles.mjs
// keeps in KV under `custom:<path>`, so neither the APK nor the served style names the editor link.
const CUSTOM_PREFIX = '/maps/custom/';

// Per endpoint: upstream path + query params the app may pass (everything else is dropped).
// `choices`: params limited to a list, first = default. `car` is only a main-road shape the app
// checks with motorcycle routing before use (docs/navigate-roadmap.md N27); truck/container stay off.
// Params from docs/vietmap-agent-docs/routing.txt and search-geocoding.txt.
const SEARCH_PARAMS = ['text', 'focus', 'display_type', 'cityId', 'distId', 'wardId',
  'circle_center', 'circle_radius', 'cats', 'layers', 'admin_new'];
export const ENDPOINTS = {
  route: { path: '/route/v4', params: ['point', 'points_encoded', 'avoid', 'annotations', 'time', 'alternative', 'heading'], choices: { vehicle: ['motorcycle', 'car'] } },
  autocomplete: { path: '/autocomplete/v4', params: SEARCH_PARAMS },
  search: { path: '/search/v4', params: SEARCH_PARAMS },
  place: { path: '/place/v4', params: ['refid'] },
  reverse: { path: '/reverse/v4', params: ['lat', 'lng', 'display_type'] },
};

export function buildUpstreamUrl(requestUrl, apiKey) {
  const url = new URL(requestUrl);
  const name = url.pathname.replace(/^\/+|\/+$/g, '');
  if (!Object.hasOwn(ENDPOINTS, name)) return null; // not `in`: "/constructor" would find Object's
  const ep = ENDPOINTS[name];
  const out = new URL(UPSTREAM + ep.path);
  for (const name of ep.params) {
    for (const value of url.searchParams.getAll(name)) out.searchParams.append(name, value); // `point` repeats
  }
  for (const [k, allowed] of Object.entries(ep.choices ?? {})) {
    const v = url.searchParams.get(k);
    out.searchParams.set(k, allowed.includes(v) ? v : allowed[0]);
  }
  out.searchParams.set('apikey', apiKey);
  return out;
}

// Map files the app's SDK loads through `/maps/…`, with their edge/SDK cache lifetime. Tile URLs are versioned
// (`/maps/tiles/vlc-20261015/…`) and glyphs never change; style.json names the tile version, so it stays short.
// Custom sprites sit under the editor's sprite id, which changes with the icons; a custom style is re-synced in place,
// so an hour lets a sync reach phones the same day.
export function mapsMaxAge(path) {
  if (path.startsWith('/maps/tiles/') || path.startsWith('/maps/fonts/')) return 30 * 86_400;
  if (path.startsWith(`${CUSTOM_PREFIX}sprites/`)) return 30 * 86_400;
  if (path.startsWith(CUSTOM_PREFIX)) return 3_600;
  if (path.startsWith('/maps/styles/')) return 86_400;
  return null;
}

// style.json names its tile URLs with the key: point them at this Worker and drop every apikey.
export function scrubMaps(text, selfOrigin) {
  return text.replaceAll(MAPS_ORIGIN, selfOrigin).replace(/\?apikey=[^&"]*&/g, '?').replace(/[?&]apikey=[^&"]*/g, '');
}

const VN_OFFSET_MS = 7 * 3600_000;
const DAY_MS = 86_400_000;
const RATE_LIMIT_PAUSE_MS = 5 * 60_000;
const KV_TTL_MS = 60_000;

// 401/403: bad or used-up key (probed 2026-10-24: 401, empty body) → off until VietMap's day resets (VN midnight).
// 429 may be the 200/min limit, not the daily one, so the key only pauses briefly.
export function deadUntil(status, nowMs) {
  if (status === 429) return nowMs + RATE_LIMIT_PAUSE_MS;
  if (status === 401 || status === 403) return Math.floor((nowMs + VN_OFFSET_MS) / DAY_MS) * DAY_MS + DAY_MS - VN_OFFSET_MS;
  return null;
}

const memDead = new Map(); // this isolate's view, so a key is skipped before KV's ~60 s propagation
let memKv = null; // KV read at most once a minute per isolate: map tiles alone would pass the 100k reads/day free tier

export function resetMemory() {
  memDead.clear();
  memKv = null;
}

async function loadKeys(env) {
  const now = Date.now();
  if (env.KEYS && (!memKv || now - memKv.at > KV_TTL_MS)) {
    const [stored, dead] = await Promise.all([env.KEYS.get('keys', { type: 'json' }), env.KEYS.get('dead', { type: 'json' })]);
    memKv = { at: now, stored, dead };
  }
  const { stored, dead } = (env.KEYS && memKv) || {};
  const knownUntil = (k) => Math.max(dead?.[k] ?? 0, memDead.get(k) ?? 0);
  // All dead → probe one: a marker can outlive the real block (VietMap's reset time is a guess).
  const live = (keys) => {
    const alive = keys.filter((k) => knownUntil(k) <= now);
    return alive.length ? alive : keys.slice(0, 1);
  };
  return {
    api: live(stored?.api?.length ? stored.api : [env.VIETMAP_API_KEY].filter(Boolean)),
    tile: live(stored?.tile ?? []),
    knownUntil,
  };
}

async function markDead(env, keys, key, until) {
  if (keys.knownUntil(key) >= until) return; // already parked: keeps KV writes (1,000/day free) to one per key
  memDead.set(key, until);
  if (!env.KEYS) return;
  // shortcut: read-modify-write can lose a marker when two edges race; the loser is re-marked on its next failure.
  const now = Date.now();
  const dead = Object.fromEntries(Object.entries((await env.KEYS.get('dead', { type: 'json' })) ?? {}).filter(([, t]) => t > now));
  dead[key] = until;
  await env.KEYS.put('dead', JSON.stringify(dead));
}

/** First response VietMap doesn't reject with [list]'s keys in order; undefined when [list] is empty. */
async function fetchWithKeys(env, keys, list, upstreamFor, init) {
  let res;
  for (const key of list) {
    res = await fetch(upstreamFor(key), init);
    const until = deadUntil(res.status, Date.now());
    if (!until) break;
    await markDead(env, keys, key, until);
    if (key !== list.at(-1)) await res.body?.cancel();
  }
  return res;
}

const json = (status, body) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json' },
});

async function maps(url, env) {
  const maxAge = mapsMaxAge(url.pathname);
  if (!maxAge) return json(404, { error: 'unknown_endpoint' });
  // Map files take no query (style.json URLs carry none once scrubbed), so drop it all: a random `?x=` would
  // otherwise miss the edge cache and spend a tile key's quota on every request.
  const keyless = new URL(url);
  keyless.search = '';
  // Edge cache: one VietMap fetch serves every rider near this Cloudflare site. Custom files skip it: KV already
  // sits at the edge, and a cached copy would outlive the next sync by a day.
  const cache = keyless.pathname.startsWith(CUSTOM_PREFIX) ? undefined : globalThis.caches?.default;
  const hit = await cache?.match(keyless.href);
  if (hit) return hit;

  let res;
  if (keyless.pathname.startsWith(CUSTOM_PREFIX)) {
    const body = await env.KEYS?.get(`custom:${keyless.pathname}`, { type: 'arrayBuffer' });
    const type = keyless.pathname.endsWith('.png') ? 'image/png' : 'application/json';
    res = body ? new Response(body, { headers: { 'content-type': type } }) : json(404, { error: 'not_synced' });
  } else {
    const keys = await loadKeys(env);
    const start = Math.floor(Math.random() * keys.tile.length); // spread the tile quota and the per-minute limit
    const order = keys.tile.slice(start).concat(keys.tile.slice(0, start));
    res = await fetchWithKeys(env, keys, order, (key) => {
      const up = new URL(MAPS_ORIGIN + keyless.pathname);
      up.searchParams.set('apikey', key);
      return up;
    });
  }
  if (!res) return json(503, { error: 'no_tile_key' });
  const type = res.headers.get('content-type') ?? 'application/octet-stream';
  if (res.status !== 200) return new Response(res.body, { status: res.status, headers: { 'content-type': type } });
  const body = /json|text/.test(type) ? scrubMaps(await res.text(), url.origin) : res.body;
  const out = new Response(body, { status: 200, headers: { 'content-type': type, 'cache-control': `max-age=${maxAge}` } });
  if (cache) await cache.put(keyless.href, out.clone());
  return out;
}

export default {
  async fetch(request, env) {
    if (request.method !== 'GET') return json(405, { error: 'method_not_allowed' });
    // shortcut: a static app token is extractable from the APK; it only stops casual abuse.
    // Upgrade to Play Integrity verification if quota abuse shows up in the VietMap Daily Report.
    if (!env.APP_TOKEN || request.headers.get('x-app-token') !== env.APP_TOKEN) {
      return json(401, { error: 'unauthorized' });
    }
    const url = new URL(request.url);
    try {
      if (url.pathname.startsWith('/maps/')) return await maps(url, env);
      if (!buildUpstreamUrl(request.url, '')) return json(404, { error: 'unknown_endpoint' });
      // Per-minute brake against floods (docs/security/route-quota-and-rate-limit.md). Keyed on IP for want of a
      // per-install ID; counts are per Cloudflare location and approximate. No binding (tests, old config) = no limit.
      const name = url.pathname.replace(/^\/+|\/+$/g, '');
      const limiter = name === 'route' ? env.ROUTE_LIMIT : env.SEARCH_LIMIT;
      const ip = request.headers.get('cf-connecting-ip') ?? '';
      if (limiter && !(await limiter.limit({ key: `${name}:${ip}` })).success) return json(429, { error: 'rate_limited' });
      const keys = await loadKeys(env);
      const res = await fetchWithKeys(env, keys, keys.api, (key) => buildUpstreamUrl(request.url, key), {
        headers: { accept: 'application/json' },
      });
      if (!res) return json(503, { error: 'no_api_key' });
      return new Response(res.body, {
        status: res.status,
        headers: { 'content-type': res.headers.get('content-type') ?? 'application/json' },
      });
    } catch {
      return json(502, { error: 'upstream_unreachable' });
    }
  },
};
