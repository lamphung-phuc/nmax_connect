// Run: node --test proxy/vietmap/test/index.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import worker, { buildUpstreamUrl } from '../src/index.js';

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
