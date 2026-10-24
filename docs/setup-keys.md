# API keys & Cloudflare setup

Follow this once per environment (dev, then prod). VietMap keys live **only in Cloudflare**; the app ships none.
The app only knows the Worker URL and token (**`local.properties`**, gitignored). Nothing secret goes in git.

| Secret | Lives in | Used by |
|---|---|---|
| VietMap API + tile keys | Cloudflare KV `keys` (`proxy/vietmap/README.md`) | Worker → VietMap routing/search and map (`/maps/…`) |
| `VIETMAP_PROXY_URL` | `local.properties` → app | base URL of the Worker |
| `VIETMAP_PROXY_TOKEN` | `local.properties` **and** Cloudflare secret `APP_TOKEN` | app → Worker auth |
| `VIETMAP_API_KEY` | Cloudflare secret (fallback while KV has no `keys`) | Worker → VietMap routing/search |

---

## 0. Application ID
The app ID is **`com.lampp.nmaxconnect`** (`applicationId` and `namespace` in `app/build.gradle.kts`).

---

## 1. VietMap
Reference: `docs/vietmap-agent-docs/console.txt`.

1. Register at https://maps.vietmap.vn/console/register. The console is at https://maps.vietmap.vn/console-v2/.
2. Create one **Consumer (project)** for dev and another for prod.
3. Each consumer gives you two keys. Note both:
   - **tilemap key** → `tile` list in KV `keys` (Cloudflare only);
   - **map APIs key** → `api` list in KV `keys` (Cloudflare only, see §2 and `proxy/vietmap/README.md`).
4. Lock down each key:
   - **Tilemap key:**
     - set a per-key quota and disable every API except tilemap;
     - IP restriction won't work either: the Worker fetches tiles, and Workers don't call out from fixed IPs.
   - **API key:**
     - enable only **Route, Autocomplete, Search, Place, Reverse**;
     - set per-key and per-API quotas;
     - IP restriction won't work, because Cloudflare Workers don't call out from fixed IPs. The quota plus the Worker token are the protection.
5. Test both keys in the playground: https://tools.vietmap.vn/playground.

> **Trial limits:**
> - the free trial allows about **500 requests/day** per API and **5,000 tile requests/day**;
> - a moving map streamed to the bike pulls tiles continuously, so expect to hit the tile cap during road tests;
> - keep SDK tile caching on, and test on a short loop.

---

## 2. Cloudflare Worker (VietMap proxy)
Code: `proxy/vietmap/` (`src/index.js`, `wrangler.toml`). Needs Node ≥ 18 (this machine has 24).

1. **Create an account** at https://dash.cloudflare.com/sign-up (free plan) and verify your email.
2. **Log in from the terminal:**
   ```bash
   cd proxy/vietmap
   npx wrangler login        # opens the browser for OAuth
   npx wrangler whoami       # confirms the account
   ```
3. **Run the tests:**
   ```bash
   node --test test/index.test.mjs
   ```
4. **Deploy:**
   ```bash
   npx wrangler deploy
   ```
   - On the first deploy, wrangler asks you to pick a `*.workers.dev` subdomain. The URL becomes `https://nmax-vietmap-proxy.<subdomain>.workers.dev`, which is your `VIETMAP_PROXY_URL`.
   - Until the secrets exist, every call returns **401**. That's safe.
5. **Generate the app token:**
   ```bash
   node -e "console.log(require('crypto').randomBytes(32).toString('base64url'))"
   ```
6. **Store both secrets** (paste each value at the prompt; this deploys a new version automatically):
   ```bash
   npx wrangler secret put VIETMAP_API_KEY   # VietMap map-APIs key (NOT the tilemap key)
   npx wrangler secret put APP_TOKEN         # value from step 5
   ```
7. **Smoke test:**
   ```bash
   URL=https://nmax-vietmap-proxy.<subdomain>.workers.dev
   curl -i "$URL/autocomplete?text=197%20tran%20phu&focus=10.7589,106.6754&display_type=6"   # expect 401
   curl -s -H "X-App-Token: <token>" \
     "$URL/autocomplete?text=197%20tran%20phu&focus=10.7589,106.6754&display_type=6"         # expect JSON results
   curl -s -H "X-App-Token: <token>" \
     "$URL/route?point=10.755222,106.662633&point=10.755991,106.663323&points_encoded=false"  # expect "code":"OK"
   ```
8. **App config.** Add these to `local.properties`:
   ```
   VIETMAP_PROXY_URL=https://nmax-vietmap-proxy.<subdomain>.workers.dev
   VIETMAP_PROXY_TOKEN=<token>
   ```
9. **Local development (optional):**
   - create `proxy/vietmap/.dev.vars` (gitignored) containing:
     ```
     VIETMAP_API_KEY=…
     APP_TOKEN=…
     ```
   - run `npx wrangler dev`, which serves on `http://localhost:8787`;
   - from the Android emulator, use `http://10.0.2.2:8787`.
10. **Operations:**
    - `npx wrangler tail` streams live request logs (the key is never logged);
    - to add, remove or rotate VietMap keys, edit KV `keys` (`proxy/vietmap/README.md`); the app doesn't change;
    - every map tile is a Worker request (edge-cached ones too): the free plan allows 100k/day, Workers Paid ($5/month) 10M;
    - to rotate the app token, `wrangler secret put APP_TOKEN`, then ship an app update.
11. **Rate limiting (optional, recommended before public release):**
    - Workers has a rate-limit binding (`[[ratelimits]]` in `wrangler.toml`, period of 10 or 60 s, counted per Cloudflare location, needs wrangler ≥ 4.36);
    - adding it means a small change to `src/index.js` to return 429.

---

## 3. Checklist
- [ ] VietMap: dev/prod consumers, quotas, unused APIs disabled on both keys
- [ ] Worker deployed, both secrets set, curl test returns 401 without the token and JSON with it
- [ ] `local.properties` has `VIETMAP_PROXY_URL`, `VIETMAP_PROXY_TOKEN` (no VietMap key)
- [ ] Nothing secret in `git status`
