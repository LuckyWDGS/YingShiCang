# Project instructions

## Project memory

- Use the codex-session-continuity skill when available. Keep local memory under `.codex/session/`; it is ignored by Git.
- Read `HANDOFF.md`, `PROJECT.md` and `TASK.md` there when present. Read `ASSETS.md` for design references and `DEBUG_SCREENSHOTS.md` for runtime evidence.
- If local memory is absent, start with `README.md`, `docs/build.md` and `docs/短剧漫剧独立分类-2026-10-10.md`.
- Verify remembered state against source and Git status. Update HANDOFF before completing or pausing project work.
- Preserve user configuration, databases, all preferences and disabled-source choices. Exclude private URLs, credentials, raw logs, session data, APKs and local design/QA artifacts from Git.
- This v35 snapshot was authorized for a local commit on 2026-10-10. No remote push was requested. Do not clean/reset unrelated files.

## Current behavior to preserve

- Version 0.1.34-alpha / versionCode 35. TV and mobile flavors share `com.yingshicang.player`; build commands and external Media3 setup are in `docs/build.md`.
- Search uses the approved black three-column page and existing five-column result cards. Card geometry, episodes, ratings, source labels and shortcut order are frozen.
- Keep current keyword rows while suggestions are pending, fail or return empty. Replace them directly on latest nonempty success; explicit clear restores hot rows. Preserve cancellation, generation isolation, 150ms debounce, bounded parsing and caching.
- Search HTTP and plugin lanes are independently bounded; preserve per-source deadlines, progressive results, origin identity, automatic paging and actual HTTP cancellation.
- Nine homepage entries have one focus node each. First click selects content with filters closed; repeat current click toggles; Back closes filters first. Keep single16sp titles/12dp inline arrows,40dp height, Home weight0.8, labels length>=5 weight1.2/padding2dp, other labels1.0. 电视剧榜单 has5characters.
- Home loading spinner, return-to-top, reset/collapse buttons and visible adult-content row remain removed. Keep genuine empty/failure/retry states, translucent panels and first-filter-row DPAD_UP behavior.
- Movie/TV discovery queries current configured sites, including sites with search disabled. Map actual declared categories/facets, retain origin key/id and independent source paging/retry. Other discovery providers retain their ordinary-content isolation.
- 短剧漫剧 is independent AI index8; History uses index9. It currently uses confirmed AiDj AI漫剧/AI短剧 and8real topics+全部. Do not invent popular/latest facets. Keep separate legal persisted type/topic state, exact configuration identity, actual origin details and bounded paging/retry/cancellation.
- AiDj compatibility is strictly gated to the proven plugin. Real route/selector/API identities supply current topics; original empty-topic API remains. Current web query.topic is empty, so validate categoryRoute/apiQuery instead. Do not restore the old ignored-topic behavior.
- Preserve successful-only JS/JAR/Init caches, JS HTML helper fallback, strict search response schema, proven base-pagination gate, exact SixV form compatibility and scoped AiDj/oE host Context/Python startup.
- Adult selection stays off and is never restored. Require explicit source category/filter/tag/rating metadata; do not infer adult classification from source branding, plot or cast.
- Default-poster candidates remain unselected. Rejected designs and old cloud/Quark proposals are not approved product changes.

## Validation and device safety

- Completed v35 evidence: TV/mobile ARM64 builds and173JVM tests; two AI types × all/fantasy × p1/p2 product pages; TV real filtering, pagination, remote focus, movie/TV/AI/history isolation and restart checks.
- This evidence does not establish all-topic product coverage, complete playback, phone interaction, frame timing or full-catalog completeness. Remaining external source failures are documented in `docs/空内容与异常站源修复-2026-10-10.md` and the v35 report.
- Avoid automatically repeating completed builds/QA; run checks appropriate to new changes or unresolved failures.
- Device operations belong to the root agent and must use `scripts/adb_target.py` or `scripts/emulator.py` against a verified emulator. Do not operate physical devices. Preserve and hash-verify all original database/preferences around diagnostics, remove diagnostics afterward and return Home.
