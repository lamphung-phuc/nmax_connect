# VietMap proxy: managing keys

The Worker holds every VietMap key. Changing keys never needs an app release.

- **API keys** (route, search, place, reverse): the Worker uses them in order. When VietMap rejects one
  (401/403: used up or wrong), the Worker moves on to the next and skips the rejected key until 00:00 Vietnam
  time. A 429 (the 200/min limit) only skips it for 5 minutes.
- **Tile keys** (map display): the app's map loads styles, tiles and fonts from `{worker}/maps/…`. The Worker
  adds a tile key (random start, so the 5,000 tiles/day limits are shared), rotates on rejection like the API
  keys, removes keys from style.json, and caches files at the Cloudflare edge so nearby riders share one fetch.
  The app holds no VietMap key. 1.6.13 and older still carry their built-in tile key and call VietMap directly.
- **Custom styles**: the app's day/night styles are made in VietMap's map editor (tools.vietmap.vn/map-editor/manage).
  The Worker serves copies kept in KV (`custom:/maps/custom/…`) as `{worker}/maps/custom/{day,night}/style.json`,
  sprites included, so neither the APK nor the served style names the editor link. After re-publishing a style:
  `node scripts/sync-styles.mjs day=<publicId> night=<publicId>` (ids from Share → Style URL; never commit them).
  Custom files skip the edge cache (KV is already at the edge) and styles carry a 1-hour max-age, so riders get a
  sync within the hour. Tiles and fonts inside still go through `/maps/…` with tile keys.
  The script also adapts what the app's map engine would drop a layer over (scripts/compat.mjs): the editor's
  "starts with" `slice` expressions become `in`, and a one-number `line-dasharray` (`[0]`) is removed.
- Every map file the app loads counts as one Worker request, edge-cached ones included. The free plan allows
  100,000 a day; Workers Paid ($5/month) allows 10 million. Check Workers → Metrics as riders grow.

## One-time setup

From `proxy/vietmap/`:

1. `npx wrangler login` (if not logged in yet).
2. `npx wrangler kv namespace create KEYS` and copy the `id` it prints.
3. In `wrangler.toml`, uncomment the `[[kv_namespaces]]` block and paste the id.
4. `npx wrangler deploy`. Until step 5, the Worker keeps using the `VIETMAP_API_KEY` secret, same as before.
5. Add the keys (next section).

## Add, remove or replace keys

Each VietMap account has two keys (console: https://maps.vietmap.vn/console-v2/): the **map APIs** key goes in
`api`, the **tilemap** key goes in `tile`. Put the whole list in one KV entry named `keys`:

```json
{
  "api":  ["API_KEY_ACCOUNT_1", "API_KEY_ACCOUNT_2", "API_KEY_ACCOUNT_3"],
  "tile": ["TILE_KEY_ACCOUNT_1", "TILE_KEY_ACCOUNT_2", "TILE_KEY_ACCOUNT_3"]
}
```

**Dashboard (easiest):** Cloudflare dashboard → Storage & Databases → KV → `KEYS` → entry `keys` → edit → paste the JSON → save.

**Command line:** save the JSON as `keys.json` (never commit it), then

```sh
npx wrangler kv key put keys --path keys.json --binding KEYS --remote
```

Changes go live within about a minute. No redeploy needed.

## See what the Worker parked

KV entry `dead` = `{ "<key>": <epoch ms until it is retried> }`. Only the Worker writes it. Delete the entry
to make the Worker retry every key right away, for example after VietMap raised a limit.

## Check

```sh
curl -H "X-App-Token: $APP_TOKEN" "https://<worker>.workers.dev/maps/styles/tm/style.json"   # no "apikey" inside
```
