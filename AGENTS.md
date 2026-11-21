# Project instructions

## Project memory

- Use the codex-session-continuity skill when available. Keep local memory under `.codex/session/`; it is ignored by Git.
- Read `HANDOFF.md`, `PROJECT.md` and `TASK.md` there when present. Read `ASSETS.md` for design references and `DEBUG_SCREENSHOTS.md` for runtime evidence.
- If local memory is absent, start with `README.md`, `docs/build.md` and `docs/短剧漫剧独立分类-2026-10-10.md`.
- `docs/开发记录.md` holds the full per-release history this file used to carry (implementation details, measurements, what was and was not verified, user decisions with dates). Look there for why a rule below exists; this file keeps only the rules in force. Add each release's detailed entry there, not here.
- Verify remembered state against source and Git status. Update HANDOFF before completing or pausing project work.
- Preserve user configuration, databases, all preferences and disabled-source choices. Exclude private URLs, credentials, raw logs, session data, APKs and local design/QA artifacts from Git.
- The user authorized Git submission and a formal release. Formal releases continue the existing release signature. Never commit/upload Release/signing, private source URLs, logs or device backups. Do not clean/reset unrelated files.

## Releases

- Current version is 3.6.1 / versionCode 114 (tag v3.6.1). Only TV (leanback) packages are published; phone builds stay local. Build and publishing steps: `docs/build.md`.
- Release notes keep `<!-- ysc-min-version: 2.1.0 -->` plus a `<!-- ysc-important: … -->` reason line, unchanged, by default: devices below 2.1.0 are forced to update. The app reads only the latest release body, so dropping the marker stops forcing. Never add, raise or drop it unless the user says that release is important; ask first.
- A published version name is never reused (UpdateNotice.isNewer compares names); fixes go out as a new version.
- Formal builds need the untracked `local.properties` entries (signing, log upload URL). Never commit them. Keep each release's `mapping.txt` locally under `Release/<version>/mapping` for retracing.
- Updates come from GitHub Releases only (user 2026-11-20): the update mirror and custom manifest URLs were removed from the app, so releases from 3.6.2 on no longer read the mirror. Do not touch the VPS's services.
- Releases are summarised for users in README "最近更新" and in the GitHub release body (### groups of • bullets).

## Rules in force

Each rule is a user decision or a fixed regression; `docs/开发记录.md` has the dates and evidence.

### Home (leanback)

- Nine tab entries, one focus node each. Focus resting on a tab opens it after 250ms (keys held across tabs open only the one they stop on); focus arriving from elsewhere stays on the tab it reached. OK on the open tab toggles filters; Back closes filters first. 20sp single-line labels in a 52dp row of 44dp tabs; every tab carries a fixed 10dp arrow drawable shown only when open and filterable, so widths never change; fitTabs shrinks all labels together, floor 75%; no underline on the open tab.
- Down from the title lands on the open tab; Up from the cards (list at its top) lands on the open tab; focus parked on 主页 by the history page does not reopen 主页.
- Shortcuts row only on Home; other categories and History hide the toolbar (source/weather/clock) with GONE. The header shows the configuration's own logo (GIF kept; hidden when missing) and one current source title (20sp); no extra brand label. Before the configuration loads, the title shows the last site name for that configuration.
- History sits in Home's five-column grid, focus restored by key; deleting focuses the replacement; 有更新 marks redraw while History or 收藏 is open (no red dots: the user withdrew them).
- Card grids (Home, search results) use CardGridView: a held Up/Down stays in the cards, rows scroll in immediately, and poster loads (the grid's Glide requests) wait until the key is released. Showing posters already in memory while held was tried and dropped: it cost most of the gain.
- Cards: 3:4 poster plus a separate 36dp single-line footer; metadata badges share one translucent bar (#CC202020, 16sp). 豆瓣推荐 shows only on Home; movie/TV/AI discovery categories hide site labels; AI cards show only the source's 共N集; zero scores are hidden.
- Black cinema theme (v42): #080808 backdrop, #141414 panel, #1E1E1E control, #303030 selected, #F2F2F2/#AAAAAA text, white 2dp focus; discovery filter panel at 35% black. No loading spinner, return-to-top, reset/collapse buttons or visible adult row.
- Weather pill is display only. Location: granted device location, else ipip.net (then Open-Meteo geocoding in that province), else ipwho.is. Forecast: Open-Meteo, then wttr.in; after a backup answer the backup goes first for 6 hours. No 约/≈ prefix.
- 追剧提醒 (default on): 45s after Home shows, up to 10 favorites/recent series, one detail at a time, every 6h (3 days for single episodes); a count only goes up; the first look reports nothing; opening the title clears the mark. Leaving Home stops the round. FollowCheckScope blocks a plugin's synchronous finish of Home from that background call only.
- The TV never asks for the notification permission (user 2026-11-16: on Android 13+ the prompt came on every cold start, Back is not a refusal, and Home's start-up waited behind it). The phone build still asks. DeviceReport carries notifications=on|off.
- SourceWarmup: 10s after Home shows, one plugin at a time (home site, favorites, recent history; searchable plugins only with ≥ 3 GB RAM), stops when Home is left, once per configuration revision.

### Updates

- No update window at launch (user 2026-11-20): Home checks GitHub once in the background and only records a newer version; Home's 设置 shortcut carries a red speech bubble with a white up arrow on its top-right corner (user 2026-11-21: a bare dot did not say what it meant and an arrow reads better than text; drawn by the shortcut header's overlay, not added to the row), Settings opens on 关于与更新 with 版本更新 focused (dot on that tab too), and picking 版本更新 checks again and opens the update window with the version and release notes. Only a release whose `ysc-min-version` is above this build still opens the window at launch.

### Configuration

- Automatic loads (Home start, a screen that needs it first) start from the saved copy of the same address when it is under 24h old, then check the address once in the background: same → the copy is renewed; changed → saved and used from the next start; no answer → kept; an explicit refusal (msg, no sites, 4xx other than 408/429) → the copy is dropped. Loads the user asks for (refresh, another line, restore) go to the network first, falling back to the copy only on network failure, never on an explicit refusal.
- A screen opened before anything loaded the configuration (Android recreating it after a background kill) starts the load itself: details via VodDataSource.awaitConfig, search via CollectActivity.awaitConfig.
- Configuration is reached directly on the LAN (bare IP:port, no pairing or token); a newly added line is activated at once with rollback, duplicates are safe. IDN hosts are accepted. HTTPS uses platform validation and refuses downgrade redirects.
- The bundled plugin fallback is bound to the exact audited address hash and declared MD5; an online download wins, then a verified cache, then the bundle; a changed or removed checksum never uses the bundle.

### Search

- Search page: black three-column input page; hot lists show exactly two rows, shortest titles first; search history packs its column and records only keywords the user entered; typed suggestions keep their rows while new ones load and include 短剧漫剧 titles matched by pinyin initials.
- Letters (2-12) stay the keyword: up to 7 candidate titles are searched with them; only titles related to a candidate, containing the letters or spelling them are shown.
- Results (CollectActivity): five columns; order is relevance alone, stable for ties; 全部来源 pinned above the source list; only sources with cards are listed; source focus shows that source after 250ms; Left from the results goes straight to the selected source. Further pages of one source run through SearchPaging (two automatic retries, three-page scan past filtered pages). Batches are merged at most every 150ms and wait while a key is held on the cards.
- Search plugins are started when the search page opens; SourceBench rests a source failing three rounds in a row for 30 minutes (never all sources); a source failing twice in one search sits out its remaining queries.
- Card-origin searches carry title and year, reject other installments and wrong years, and keep raw identities for paging and focus.

### Detail and playback

- TV detail: fixed preview/metadata/source/line row with a six-column independently scrolling episode grid; no play-address row, keyword search or bottom tools; full episode labels, marquee only on focus or the playing card.
- Line choice: lines of drives the user has scanned in come first; the saved line stays unless it is an unscanned drive line and a scanned one exists; the closest episode (same name/number, saved season first) is chosen, and a different episode resets the position. The episode being watched is carried across sources. Lines that clearly lack it are passed over automatically (still pickable); when no line has it, other sources are searched; when none has it, the first source plays its closest episode with a notice.
- A failing line is retried twice in place (the first quiet when nothing had played), then lines go round once, then other sources. A refusal for want of a drive login is shown in the source's words and that drive's other lines are passed over. A line picked by hand is always asked.
- Reopening a title opened in the last 3 days starts on the copy of its detail kept from then (VodDetailCache, cache directory, 40 titles), asks the play address first and the source's detail after it; an address from the copy that fails waits for the fresh detail and is asked again, and counts as a failure only if the source then has nothing usable.
- Next/previous skip neighbours of the same name; the last episode asks 下一集 once per request.
- Exo: hardware decoders preferred when they support the profile even above their declared level; subtitles selected by default; the first supported audio track by default; AiDj next-episode preload on unless the user turned it off; media reads time out at 15s (30s for loopback plugin proxies).
- TV player controls: hidden controls open on 下集 with Up/Down; OK pauses and opens on the time bar; Left/Right seek 10s (30s after 2s held), presses within 400ms add up; 片头/片尾 set in the first/second half per source and line, the label shows the result; the strip never scrolls; 更多 holds low-frequency and plugin actions; paused controls hide after 5 minutes; Back from full screen focuses the episode playing.
- Touch on the full-screen video: no swipe volume; horizontal swipe seeks; holding the right third plays at 2x (user 2026-11-20; was 5x), the left third rewinds in real time, both showing the time.
- Danmaku on TV: a plain toggle that searches the playing episode when nothing is loaded; at least 12s across and 1.3x size, top quarter only; failed loads retried twice; a load is dropped after 60s.

### Plugins and sources

- PluginNoticeCompatibility recognises the audited plugin by structure and silences only the exact texts listed in it (ad count, 哔哩游客 notice, 弹幕【…】加载成功); no global Toast interception.
- PluginCrashGuard skips errors with a plugin frame on the stack (main and background threads); app errors still crash normally.
- WoGG: every SiteApi callback that reads or refreshes its address shares one fair instance lock; detail/player preempt a search holding it. No guessed domains or extra retries.
- AiDj (短剧漫剧) compatibility is gated to the proven plugin builds; an unrecognised AiDj keeps its own types and filters.
- A source class missing from the plugin package is remembered for that package (MissingClasses). Proxy calls are bounded (danmaku 1 slot/55s, media 6 slots/60s). The local server restarts itself when its socket dies.
- The plugin owns drive logins and QR flows; the app only restyles the exact chooser window and never builds its own login.
- Adult selection stays off and is never restored; adult content is recognised only from explicit source metadata.

### 短剧漫剧

- Independent AI category (index 8; History is 9) with types AI漫剧/AI短剧, the source's declared topics and 默认/最新 sort; topic and sort are independent. Never invent facets. An empty filter combination shows 暂无符合条件的短剧漫剧 without retrying.

### Diagnostics and logs

- Diagnostic events never carry URLs, titles, IDs or credentials (api labels, families and counts only). Crashes upload automatically once (up to 3 attempts); every other upload is manual. CrashTrail records the screen and running plugin calls for the next launch.
- Read user logs with `python scripts/fetch_logs.py latest|device <id>|report <uuid> [--download]` (read-only key from the user environment; never print or commit it). Device M8RJ-DHH4 is the LDPlayer QA emulator, not a user. The TCL TV (5GJM-WDAX) is retired: its findings are not followed.

## Validation and device safety

- Run checks appropriate to the change; avoid repeating completed builds/QA. `python scripts/check.py --offline` runs the TV JVM tests, the mobile compile and both service test roots.
- `.github/workflows/check.yml` runs the same `scripts/check.py` on GitHub for pushes to main and every pull request (no secrets, no APK, no native libraries, no device). Keep it green; a CI pass is not device evidence.
- Device operations belong to the root agent and must use `scripts/adb_target.py` or `scripts/emulator.py`. The verified LDPlayer is `127.0.0.2:5555` (E:/leidian/LDPlayer14, libhoudini.so); `127.0.0.1:5555` is a separate AVD and is not LDPlayer evidence. Do not operate physical devices.
- Around any device test: back up the installed package and every file of the app's data (including exoplayer_internal.db and companions), then reinstall the original package, restore the data and verify each file's SHA, the inventory, runtime permissions, app-ops, external folders, ADB mappings and screen size; remove probes and temporary files and return Home. Never open authoritative SQLite snapshots directly; copy the database with its WAL/SHM first.
- Say plainly what was verified where (LDPlayer, JVM, a user's log) and what was not; an emulator result is not proof for a physical TV.

## Open

- Not looked into: on LDPlayer the warm-up can sit for minutes starting the Python runtime for a Python source (one thread, 100% CPU, no lock contention; 3.5.0 alike), so no source_warmup line appears; not measured on a TV.
