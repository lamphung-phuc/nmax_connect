# Changelog

## 1.6.13 — 2026-10-20

### First start
- The welcome logo is centred.
- Permissions are split into **Required** (Nearby devices, Location) and **Optional** (notifications, calls and notifications on the dash). Grant asks only for the required ones; the optional ones can be allowed right after or later in Settings.

### Help
- Tips are now mostly pictures: numbered markers on the illustration match short numbered captions, and a pulsing ring shows what to tap.
- Done on the last page of a tip goes back to that screen's tips instead of closing help.
- The Dash help drops the "what the bike will show" tip; the preview on the tab already shows it.

### Navigate
- The header is just the search bar with the **?** button beside it, like the other tabs.
- Home, Work and saved places float as chips at the top of the map.

### Settings
- Bike name and Auto connect sit indented under the bike they belong to.
- About → **Support the author** shows a MoMo/VietQR code you can scan or save to Photos.
- About → **Zalo support group** (coming soon).

### Small fixes
- Short screens (Dash, Home) no longer collapse their title and leave an empty band above the tab bar when swiped.
- Tapping a km field in the Bike tab selects its value, so typing replaces it.

## 1.6.12 — 2026-10-19

### Navigate
- Navigating without the dash connected now shows the map on the phone and follows you. "Map is on the dash" only shows when the dash is connected.

### First start
- The app opens on a splash screen with the NMAX Connect logo (light and dark) instead of a blank screen; no white flash in dark mode.
- Smaller logo on the welcome screen.
- Onboarding asks only for Bluetooth, location and notifications. Calls and notifications on the dash stay optional.
- After choosing a bike, the app connects right away and turns on Auto connect.

### Your bike
- The bike is shown by its ID (e.g. 00060301011415) instead of its Bluetooth name.
- Settings → Bike → Bike name gives it your own name; Home shows the name with the ID under it.
- Choose bike on Home opens the bike picker directly.

### Bike tab
- "No ODO yet" says the km come from the control unit once it connects.
- A hint under the list says tapping a row sets its interval or last change; TalkBack reads rows as editable.
- "Last changed at" can't be above the current ODO.

### Look and feel
- Switching tabs fades the old screen out before the new one fades in, so two screens no longer show at once.
- Settings has the ? help button like the other tabs.

### Vietnamese
- The dash is "màn hình xe" everywhere, including the tab, so it isn't mistaken for the phone screen.
- Home's connection list is headed "Trạng thái kết nối".

## 1.6.11 — 2026-10-19

### Help
- A help button (?) on Home, Bike, Navigate (in the search bar), Dash and the debug screen opens tips for that screen, in the style of Google's Quick Share: a list of tips, each opening a short walkthrough you can swipe through.
- Settings → Help lists the tips for every screen in one place.
- Tips are illustrated with the app's own cards and sample values, and are in English and Vietnamese.

### Vietnamese
- The bike's TFT is now called "màn hình" everywhere (was "đồng hồ").

## 1.6.10 — 2026-10-19

### Look and feel
- New look matching Google's own apps: a toned-down background with white cards, rounded Google Sans Flex text, and title and label sizes measured from Google's Quick Share.
- Sharp vector logo on the welcome screen; the welcome content sits in the middle of the screen.

### Onboarding
- Grant asks for each permission in the order the list shows, including Calls on the dash and Notifications on the dash (calls now come first).
- Permission texts say what each permission does; "Optional" is gone.

### Navigate
- The fastest route is listed first and picked by default.
- The map frames all route options between the From/To fields and the route list, and stays still while a swap reloads routes.
- Swapping From and To slides the two places past each other and turns the swap button; a spinner shows while routes load.
- The destination is a red pin on the map and in the To field.
- Place cards slide straight up when opened and down when closed; the search bar is as light as the From/To fields.
- "Map is on the dash" sits in the middle of the map while navigating.

### Home and Dash
- Removed the "On the dash" section from Home and the "On the dash" header from the Dash tab; the Dash tab uses the cast icon.

### Settings
- New Language (System / English / Tiếng Việt, Android 13+) and Theme (System / Light / Dark) options.
- The Map section shows only the offline map of your area.

## 1.6.9 — 2026-10-18

### Bike
- New Bike tab: a maintenance tracker with a progress bar per item, one-tap reset with undo, and a settings dialog for each item's interval.
- The Bike tab now sits between Home and Navigate.

### Settings
- Auto connect now disconnects after 1 minute without the bike instead of quitting the app.

## 1.6.8 — 2026-10-18

### Settings
- New "Auto connect" switch under Bike: the app connects to the bike when it opens, and quits after 1 minute without a dash connection to save battery.

## 1.6.7 — 2026-10-18

### Settings
- Removed the "Google Maps (coming soon)" option. Google Maps Platform billing is blocked for Vietnamese accounts, so VietMap is the only map. Sharing a place from the Google Maps app still works.

## 1.6.6 — 2026-10-18

### Dash
- Go home / Go to work on the dash menu now work when Home/Work are saved; before, the dash said the location was not set.

### Known limits
- Changing Home/Work while the bike is connected may only reach the dash on the next connect.

## 1.6.5 — 2026-10-18

Fixes from a whole-repo audit (`docs/audit-roadmap.md`, A1–A15).

### Bike link
- If the dash stops answering map frames while still connected, the app now reconnects by itself instead of leaving the map frozen.
- The control unit (CCU) is found with the phone screen off, so it reconnects with the phone in a pocket.
- Disconnecting while the CCU was still connecting no longer leaves a Bluetooth connection open; before, a few of those could stop the CCU from connecting until Bluetooth was turned off and on.
- No more rare crash when the dash stream stops (bike disconnects, dash model changes) while the map is moving.
- Android 10–11: the app now asks for precise location, which the CCU scan needs there; with approximate location the scan silently found nothing.

### Navigate
- One bad traffic answer from VietMap no longer crashes the map; that coloured segment is skipped.
- Rerouting waits at most 1.5 s for the main-road route, then takes the motorbike route, so reroutes stay fast on weak signal.
- Shared Google Maps links only follow redirects on Google's own sites.

### Dash
- Switching off every bike stat no longer leaves an empty dark column over the dash map.
- The dash volume buttons and the call volume shown on the dash now always use the same stream.

### Known limits
- The screen-off CCU scan looks for the CCU by its broadcast name; not yet tried on the bike. If the CCU stops being found when its saved address fails, that's the first suspect.
- Android 10–11 with approximate location: the dash (TFT) link is now blocked too, though only the CCU needs precise location.
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.6.4 — 2026-10-18

### Navigate
- Routes prefer main roads. The app now takes the bigger-road route instead of threading through small hẻm/ngõ to save a few seconds, which suits bigger bikes like the XMAX 300. It shows as the first route, with ETAs at motorbike speed; the usual motorbike routes are still listed below it. Before using a main-road route, the app checks it's open to motorbikes (no expressways or banned elevated roads); if the check fails, you get the usual motorbike route. Start from a place card picks the main-road route when there is one.
- The Home and Work icons on a place card now work like the bookmark: tap them again to remove that place as Home or Work.

### Known limits
- Short flyovers (under 100 m) aren't checked for motorbike bans, so one could slip into a main-road route.
- Main roads can be busier, and planning a route takes a moment longer and uses more VietMap calls (3–6 instead of 1).
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.6.3 — 2026-10-18

### Navigate
- The map opens drawn: it's prepared while you're on Home, so the Navigate tab shows your area in ~0.3 s instead of ~1 s, and it opens where you last were instead of over the whole country.
- No more squashed, tilted-looking map for a moment when the tab opens.

### Home
- The Connect button no longer flashes "Choose bike" when the app starts.
- Connections (Dash and control unit status) moved here from the Dash tab, above "On the dash".

### Dash
- Only what the dash shows: the preview plus the Turns and Bike stats switches. The phone's speed and bike data sections are gone (bike stats still show on the dash while navigating).
- The Bike stats switch no longer flicks off and on at start; if you turned it off, it stays off.

### Settings
- Offline map of my area sits under VietMap, with its bin lined up with VietMap's check.
- Permissions are folded into one "N of M allowed" row; tap it to see each one. Granted ones show a check instead of a switch, since a tap opens Android's settings rather than toggling.
- The offline map and bike rows no longer flash "download" / "99%" / "No bike chosen" when the screen opens.

### Known limits
- The map is prepared at every app start, even if you never open Navigate (one map style request, and a short pause just after launch).
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.6.2 — 2026-10-18

### Dash map
- Switching the dash to another screen (Music, …) and back no longer reloads the map: it stays ready in the background and follows your position, so the map shows at once instead of after ~5 s.
- No more empty first picture when the map starts; the map style is cached for a day.

### Navigate
- Faster rerouting: a wrong turn is noticed in ~2–3 s (you're moving, more than 12 m off the route and heading away from it), instead of after 30 m + 3 s. Riding onto one of the preview's other routes switches to it instantly, without a new request. Reroutes may follow each other after 3 → 6 → 10 s instead of a fixed 10 s (at most 6 a minute).
- The new turn reaches the dash as soon as the new route arrives; the dash route line turns grey while rerouting.
- The map along your route is saved on the phone when guidance starts and after each reroute, so the dash map keeps drawing where there's no signal.
- Off the route with no signal: the banner says so and you keep the old route; it retries every 2–10 s and reroutes soon after the signal returns.

### Settings
- Offline map of my area (Settings → Map): save a 30 × 30 km square around you for riding without signal; shows progress, then size; tap to re-centre, bin to delete.
- Map section is above Permissions; the Developer section is only in debug builds.

### Known limits
- None of this is tested on the bike yet. The wrong-turn thresholds (12 m, 50°) are first guesses.
- Saving the area costs about 35 VietMap requests; the first route save also downloads the map fonts once (a few MB).
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.6.1 — 2026-10-17

### Navigate
- Share a place from Google Maps (Share → NmaxConnect), or open any `geo:` link with the app: it switches to Navigate and opens the place card. Dropped pins and links with coordinates use Google's exact point (both maps use the same GPS coordinates in Vietnam).
- Named places (Google sends no coordinates for them) are matched on VietMap by name and street, and only accepted in the city Google gave; otherwise the search opens with the name filled in.
- Places addressed by a Plus Code (e.g. "RH6J+F7M Camping Đồi Bù") open at Google's exact pin.

### Map
- Map tiles are now cached on the phone (up to 256 MB): places already seen load without downloading again, also after restarting the app.

### Known limits
- Two branches of a chain on the same street can still be confused; the card shows the match before Start.
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.6.0 — 2026-10-17

### Bike
- Destination menu on the dash, like Garmin StreetCross: Home and Work (shown when saved in the app), Favourites (the app's bookmarked places, nearest first, with distance and direction) and Gas stations within 5 km (VietMap). Picking one routes there and starts guidance; Stop route on the dash ends it.

### Navigate
- Set as Home / Set as Work are now icons next to the bookmark on the place card; they fill in when the place already is Home or Work.

### Known limits
- Dash menu not yet tested on a bike. Opening the gas-station list costs about 11 VietMap requests.
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.5.4 — 2026-10-17

### Navigate
- Start button on the place card, next to Directions: routes and starts guiding right away on the fastest route, with a spinner while the route loads.
- While guiding, the map is shown on the dash only; the phone shows a "Map is on the dash" message instead of rendering its own map, to keep it cooler and save battery.
- The map is always top-down: no tilted camera left over after a trip ends.

### Known limits
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.5.3 — 2026-10-17

### Bike
- Dash weather and notifications work again: the fuel-economy CAN subscription no longer holds up the CCU link, which made the dash's weather request and early notifications get dropped on CCUs that don't answer it.

### Known limits
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.5.2 — 2026-10-17

### App
- Material 3 motion throughout: tabs fade through, Settings → Debug and onboarding steps slide forward and back (with predictive back), and the bottom bar slides away on Debug.
- Navigate: the search, directions and turn-banner header and the bottom panel animate between states; each new turn slides into the banner; the my-location button and the choose-on-map pin animate in.
- Link status chips, route options and the bookmark button animate their colors; cards and lists resize smoothly as rows come and go.
- Live numbers (speed, turn distance, trip time) use fixed-width digits so they don't jitter.
- Unselected tabs use outlined icons.
- On Android 11 and older, every theme color is now defined (some fell back to purple, e.g. Snackbar actions).

### Development
- detekt and ktlint (`./gradlew detekt ktlintCheck`) with baselines for existing findings, plus a pre-commit hook in `.githooks/`.

### Known limits
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.5.1 — 2026-10-17

### App
- Welcome screen shows the app logo instead of the bike icon.
- Onboarding permissions also list notifications and calls on the dash (optional; tap a row to grant).
- Settings has a Permissions section with a switch for every permission: nearby devices, location, notifications, notifications on the dash, calls on the dash. Off opens Android settings (apps cannot revoke their own permissions); a permission Android no longer asks for opens the app's settings page.
- Navigate no longer flashes the "location needed" card when location is already granted.
- First release with the APK attached on GitHub.

### Known limits
- Same signing/R8 shortcuts as 1.0.0: the APK is signed with the debug key of the build machine.

## 1.5.0 — 2026-10-17

### App
- New app icon (NMAX CONNECT logo, also as the Android 13+ themed icon).
- Connection and navigation notifications use the app's "N" mark instead of stock Android icons.
- README with the current features and screenshots.

### Known limits
- Same signing/R8 shortcuts as 1.0.0.

## 1.4.0 — 2026-10-17

### Dash
- New **Dash** tab (replaces Bike, gauge icon): a preview of the dash screen as it looks on the bike, then what the map stream shows.
- **Turns** switch: next turn in a left panel on the map (big centred arrow, distance and street).
- **Bike stats** switch (shown while navigating): air temperature, engine temperature, battery and instant fuel economy, each with its own switch and a coloured icon; the turn moves to a compact block on top of the panel.
- The panel sits under the dash's zoom buttons and above its ETA bar; the map and position puck centre in the area to its right.
- Instant fuel economy (km/L) from the CCU's injection data, also on the Dash tab's bike data.

### Known limits
- Same signing/R8 shortcuts as 1.0.0. Panel layout measured from photos of one dash; fuel economy not yet checked on a ride ("--" until the bike moves).

## 1.3.0 — 2026-10-17

### Dash
- Incoming SIM calls on the dash: caller name (from contacts) or number, missed calls, and the call start/end, so the dash shows its call screen.
- Answer, reject and hang up from the dash's call buttons (reject/hang up need Android 9+).
- Dash volume buttons: during a SIM call they change the call volume; during a Zalo/Messenger/other app call, media ± now changes the call volume too (it used to change the music nobody hears). Mic mute now works.

### App
- Settings → Dash → **Calls on the dash**: asks for phone, call log, contacts and answer-call access (optional; without it calls aren't announced).

### Known limits
- Same signing/R8 shortcuts as 1.0.0. App calls (Zalo, Messenger…) don't get the dash's call screen, only the notification.

## 1.2.0 — 2026-10-17

### Navigate (redesigned, Google Maps style)
- Interactive VietMap on the Navigate tab: GPS puck, day/night style, follow camera with tilt while guiding, my-location button. The map stays loaded across tab switches.
- Search page: Your location / Choose on map, Home / Work / favorites strip, recent places, live suggestions.
- Directions: From / To boxes (From defaults to your location), pick any start point (search, saved, or a pin on the map), swap From and To.
- Up to 3 route options (Route v4 alternatives) with time, distance, main roads, "Fastest" and traffic hints; live congestion colors on the chosen route; pick a route from the list or by tapping its line.
- Long-press the map to drop a pin (named by Reverse v4).
- Favorites: bookmark a place from its card; favorites appear as chips under the search bar.
- Guiding: turn banner on top, trip bar (time left, ETA, stop) at the bottom.

### Fixes
- Back no longer jumps to the Home tab from Navigate, Bike or Settings; back from route options returns to the map.
- Added the missing VietMap turf library (the location puck crashed without it).

### Known limits
- Same signing/R8 shortcuts as 1.0.0. Turn-by-turn starts only from your current location (custom starts are preview only).

## 1.1.0 — 2026-10-16

### Dash
- Map frames: zoom with the dash's zoom buttons, a next-turn panel while guiding, a position puck, and the passed part of the route greyed out.
- Dash volume buttons change the phone's call/media volume (mic mute not yet).

### App
- Notifications: **Disconnect** on the connection notification quits the app; **Stop navigation** on the navigation notification ends the trip.
- Search results are sorted nearest first.
- Bike pickers (onboarding and Settings) list only Yamaha control units (`YCCU_…`/`YSCCU_…`); the Settings picker scrolls.
- Onboarding asks for location (required) next to Bluetooth; notifications are marked optional.

### Fixes
- Disconnect while connecting now stops right away instead of staying on "Connecting".

### Known limits
- Same as 1.0.0: debug-key signing, no R8. Phone services and the new dash features are not yet tested on the bike.

## 1.0.0 — 2026-10-16

First release of NmaxConnect: navigation and phone features for the Yamaha NMAX dash, without Y-Connect.

### Dash (TFT) link
- Connects to the Garmin-StreetCross-capable TFT over Bluetooth (handshake, auto-reconnect).
- Streams a live VietMap map to the dash: track-up GPS camera, speed-based zoom, day/night style by the sun, scale label.
- Destination search (VietMap through the Cloudflare proxy), Home/Work/recents, motorcycle routing with turn-by-turn on the dash (turn icon, distance, street, ETA) and automatic rerouting.

### Control unit (CCU) link
- Connects to the bike's CCU like Y-Connect (SPP bootstrap, BLE, bonding, authentication) and keeps itself alive alongside the dash link.
- Bike tab: odometer, battery voltage, engine and air temperature, engine speed, and both connection states; speed falls back to the CCU when the dash link is down.
- On the dash: phone notifications from chat apps, the song playing with next/previous/play/pause from the dash buttons, phone battery/volume/signal/heat/headset status, and weather (Open-Meteo, with the place name from VietMap).
- Answers the bike's clock and language requests.

### App
- Onboarding (permissions, choose bike), Home / Navigate / Bike / Settings tabs, English and Vietnamese.
- Settings → Dash → Notifications on the dash (notification access, also used for music).

### Known limits
- Signed with the debug key: fine for your own phone; a future public release needs a real key and a reinstall.
- Release build ships without R8 shrinking (partial optimization crashed at launch); APK ~53 MB.
- Notifications, music, phone status and weather are not yet tested on the bike.
- Not built yet: incoming-call screen, dash volume buttons, fuel consumption.
- Open-Meteo's free tier is for non-commercial use.
