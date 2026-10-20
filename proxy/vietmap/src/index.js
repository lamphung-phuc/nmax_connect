// Holds the VietMap API key server-side (VietMap requires REST calls from a backend).
// App calls GET {worker}/{endpoint}?<params> with header X-App-Token; the Worker adds apikey.
const UPSTREAM = 'https://maps.vietmap.vn/api';

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
  const ep = ENDPOINTS[url.pathname.replace(/^\/+|\/+$/g, '')];
  if (!ep) return null;
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

const json = (status, body) => new Response(JSON.stringify(body), {
  status, headers: { 'content-type': 'application/json' },
});

export default {
  async fetch(request, env) {
    if (request.method !== 'GET') return json(405, { error: 'method_not_allowed' });
    // shortcut: a static app token is extractable from the APK; it only stops casual abuse.
    // Upgrade to Play Integrity verification if quota abuse shows up in the VietMap Daily Report.
    if (!env.APP_TOKEN || request.headers.get('x-app-token') !== env.APP_TOKEN) {
      return json(401, { error: 'unauthorized' });
    }
    const upstream = buildUpstreamUrl(request.url, env.VIETMAP_API_KEY);
    if (!upstream) return json(404, { error: 'unknown_endpoint' });

    let res;
    try {
      res = await fetch(upstream, { headers: { accept: 'application/json' } });
    } catch {
      return json(502, { error: 'upstream_unreachable' });
    }
    return new Response(res.body, {
      status: res.status,
      headers: { 'content-type': res.headers.get('content-type') ?? 'application/json' },
    });
  },
};
