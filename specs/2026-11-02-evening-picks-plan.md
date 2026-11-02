# Evening Picks Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the drawer's weather card with a daily "for tonight" row: 4 playable posters picked by ChatGPT (with web search) from the household's taste, and a "Cringe for tonight" shelf of 7 tips.

**Architecture:** A pure-Java `EveningPicks` (parsing, filtering, history, schedule; JVM-tested like the news) and an Android `Evening` singleton in `:home` (taste gathering, one OpenAI Responses call with `web_search` per rubric, resolving through `Recs`, storage in `files/evening.json`). `HomeView` draws the row and its list in a new focus zone, reusing the drawer's glass, the dock's focus grow and `Backdrop`'s whole-picture mode.

**Tech Stack:** Java 8, Android SDK only (no libraries), `HttpURLConnection`, `org.json`; OpenAI Responses API; kino.pub API; YouTube Data API v3.

**Spec:** `specs/2026-11-02-evening-picks-design.md`

## Global Constraints

- Java 8 language level, minSdk 28, no libraries; `./build.sh` must end with `Built FreeMyTV.apk`.
- 60 fps in `HomeView`: no allocations in `onDraw`, no network or disk on the UI thread.
- On-screen text through `Ui.tr(pt, en)` with Russian, German and Spanish in `Russian.java`, `German.java`, `Spanish.java`.
- Personal taste stays out of git: `local-assets/evening.txt` (references) and `local-assets/kinopoisk.txt` (ratings) are read as assets; the repo gets only `evening.example.txt` with neutral content.
- kino.pub's refresh token is refreshed only in `:home` (use `Recs`' existing token helpers, never a new refresh path).
- Never print or log keys; log costs like the news (`tokens in/out`).
- Model: `Config.NEWS_MODEL`; web search via the Responses API tool `{"type":"web_search"}`.
- Daily build after 15:00 local time, retry 30 min after a failure; history kept 90 days; 4 posters; 7 cringe tips (3 shown on the shelf).
- YouTube search costs 100 quota units: at most 20 searches per daily build.

## Review Focus

1. **The model returns prose around the JSON or a fenced block** → the first balanced `{…}` is parsed; nothing shown is better than a crash. (Task 1 test `parsesJsonInsideProse`.)
2. **A pick repeats yesterday's or one already watched** → filtered by history and by kino.pub watch history titles. (Task 1 test `dropsAlreadySuggested`.)
3. **Fewer than 4 picks resolve** → show what resolved (1–3 posters), never an empty poster; 0 resolved → the weather card as before. (Task 1 test `keepsOnlyResolved`; Task 4 visual check.)
4. **No OpenAI key, feature off, or first day before 15:00 with nothing stored** → drawer exactly as today. (Task 4 check.)
5. **TV clock changes or the day rolls over at night** → `due()` compares calendar days, not 24-hour spans; a build after midnight but before 15:00 doesn't happen. (Task 1 test `dueOncePerDayAfter15`.)

---

### Task 1: EveningPicks — the pure logic

**Files:**
- Create: `app/src/io/freemytv/EveningPicks.java`
- Create: `tools/news_tests/EveningTests.java`
- Modify: `tools/test_news.sh` (add `EveningPicks` to the source list), `tools/news_tests/NewsTests.java` (add `io.freemytv.EveningTests` to the extra classes)

**Interfaces:**
- Produces:
  - `final class EveningPicks.Item { String kind /* movie|serial|anime|show|video */, title, year, query, why, link, image, app /* kinowatch|youtube|null */; boolean playable() }`
  - `static List<Item> parse(String modelText)` — items from `{"picks":[{kind,title,year,query,why}]}`; tolerates prose and code fences around it.
  - `static List<Item> fresh(List<Item> in, Map<String,Long> history, Set<String> watched, long now)` — drops titles (normalized: lower case, ё→е, punctuation stripped) suggested within 90 days or watched.
  - `static List<Item> take(List<Item> resolved, int n)` — the first `n` with `playable()`, order kept.
  - `static void remember(Map<String,Long> history, List<Item> shown, long now)` and `static void prune(Map<String,Long> history, long now)` (drop older than 90 days).
  - `static boolean due(long lastBuilt, long now, TimeZone tz)` — true when `now` is at or after 15:00 local and `lastBuilt` is not on today's local date.
  - `static String key(String title)` — the normalized form used for history.

- [ ] **Step 1: Write the failing tests** in `EveningTests.all()`:
  - `parsesJsonInsideProse`: `parse("Here you go:\n```json\n{\"picks\":[{\"kind\":\"movie\",\"title\":\"X\",\"year\":2019,\"query\":\"X 2019\",\"why\":\"slow horror\"}]}\n```")` → 1 item, title `X`, year `2019`, why `slow horror`; `parse("no json")` → empty; `parse(null)` → empty.
  - `dropsAlreadySuggested`: history has `key("Ёлки")` at `now - 10 days` and `key("Old")` at `now - 91 days`; watched has `key("Seen")`; input `Елки`, `Old`, `Seen`, `New` → `Old`, `New`.
  - `keepsOnlyResolved`: 5 items, 2nd and 4th with `link == null` → `take(..., 4)` gives the other 3 in order.
  - `prunesAfter90Days`: entry at 91 days removed, at 89 kept.
  - `dueOncePerDayAfter15` (tz `Europe/Moscow`): 14:59 today with lastBuilt yesterday → false; 15:00 → true; 22:00 with lastBuilt today 15:05 → false; 01:00 tomorrow with lastBuilt today 15:05 → false; 15:00 tomorrow → true.
- [ ] **Step 2: Run** `bash tools/test_news.sh` → compile error (no `EveningPicks`), "compile failed".
- [ ] **Step 3: Implement `EveningPicks`** as specified, with no `org.json` (the JVM tests compile only `app/src` files, like `NewsOrder`): `parse` finds the outermost balanced `{…}` (string-aware brace scanner) and reads the `picks` array with a minimal hand parser for flat string/number fields.
- [ ] **Step 4: Run** `bash tools/test_news.sh` → `N passed`, no FAIL.
- [ ] **Step 5: Commit** `git commit -m "Evening picks: parsing, history and schedule"`.

### Task 2: Recs — taste and resolving helpers

**Files:**
- Modify: `app/src/io/freemytv/Recs.java`

**Interfaces:**
- Consumes: existing `kinopub(token, path)`, `kinopubToken()`, `youtubeGet(path)`, `findKinopub(title, year, kind)`, `youtubeConfigured()`.
- Produces:
  - `List<String> kinopubWatchedTitles(int max)` — titles from `/v1/history?perpage=50` (first part before " / ").
  - `List<String> kinopubBookmarkTitles(int max)` — titles across `/v1/bookmarks` folders.
  - `List<String> youtubeChannels(int max)` — subscription channel titles (`/subscriptions?mine=true`, paged).
  - `List<String> youtubeLikedTitles(int max)` — titles of `/videos?myRating=like` (newest first).
  - `JSONObject findKinopub(...)` additionally puts `"poster"` (the item's `posters.big`, else `medium`) and `"type"`.
  - `JSONObject youtubeVideo(String query)` — the first search hit as `{id, title, channel, image, seconds}` (thumbnail `maxres|standard|high`, duration via `/videos?part=contentDetails`), skipping results under 60 s; null when none.
- [ ] **Step 1: Implement the four taste methods and the two resolvers** in `Recs`, each catching its own IO errors and returning an empty list / null (a source down must not stop the build).
- [ ] **Step 2: Build** `./build.sh` → `Built FreeMyTV.apk`.
- [ ] **Step 3: Verify on the TV** with a temporary log call from `HomeView.resume()` on a worker thread: counts of channels, liked titles, watched titles; one `youtubeVideo("…")` and one `findKinopub("…")` result. Expected: non-zero counts, a poster URL. Remove the temporary call.
- [ ] **Step 4: Commit** `git commit -m "Recs: taste and resolving helpers for evening picks"`.

### Task 3: Evening — building the day's picks

**Files:**
- Create: `app/src/io/freemytv/Evening.java`
- Create: `evening.example.txt` (neutral: "Gloriously bad films and shows: home-made films with wooden acting…", no personal names)
- Modify: `README.md` (an "Evening picks" paragraph next to the News tile one)

**Interfaces:**
- Consumes: Task 1 (`EveningPicks.*`), Task 2 (`Recs` helpers), `News.chat`-style HTTP (write a Responses variant here; don't change `News`).
- Produces:
  - `static Evening get(Context c)`; `interface Listener { void onEvening(); }`, `addListener/removeListener`.
  - `boolean enabled()` — OpenAI key set and `Prefs.evening()`.
  - `void refreshIfDue()` — runs a build on its own executor when `EveningPicks.due(...)`, or 30 min after a failed one.
  - `List<EveningPicks.Item> posters()` (≤4, all playable) and `List<EveningPicks.Item> cringe()` (≤7).
- [ ] **Step 1: Taste prompt:** assemble ≤ 4000 characters: up to 60 channel names, 40 liked titles, 40 watched + bookmarked titles, `kinopoisk.txt` lines rated ≥ 8 (if the asset exists), interests from `news.txt`'s `[topics]` section.
- [ ] **Step 2: Two Responses calls** (`POST https://api.openai.com/v1/responses`, `model=Config.NEWS_MODEL`, `tools=[{"type":"web_search"}]`, `reasoning.effort` low), output text read from `output[].content[].text` of type `output_text`, parsed with `EveningPicks.parse`:
  - regular: "8 picks for tonight for this household… mix films, series, anime, shows and YouTube videos… each with a search query that finds it on kino.pub or YouTube and a one-line reason in Russian…";
  - cringe: the whole of `evening.txt` as the brief and the references, "find 10 new things of this kind on the web; never the references themselves or anything listed as already suggested…".
  Both get the already-suggested titles (history keys) and the watched titles.
- [ ] **Step 3: Resolve** each candidate: kinds movie/serial/anime/show → `findKinopub` (link `https://kino.watch/item/view/<id>`, image = poster, app `kinowatch`); video, or not found on kino.pub → `youtubeVideo(query)` (link `https://www.youtube.com/watch?v=<id>`, image = thumbnail, app `youtube`). Stop after 20 YouTube searches. Posters: `take(resolved, 4)`. Cringe: resolved first, then unresolved as text, 7 in all.
- [ ] **Step 4: Store** `files/evening.json` (`builtAt`, `posters`, `cringe`, `history`), written outside any lock; load on construction; `remember` + `prune` the history; notify listeners on the main thread.
- [ ] **Step 5: Build, install, verify** on the TV with the clock past 15:00: log shows `evening: N posters, M cringe, tokens …`; `evening.json` contents logged once (titles only). Commit `git commit -m "Evening picks: the daily build"`.

### Task 4: HomeView — the row, the shelf and the list

**Files:**
- Modify: `app/src/io/freemytv/HomeView.java`, `app/src/io/freemytv/Prefs.java` (`static boolean evening()`, default true), `SettingsActivity.java` Home Settings list (line ~628: add a "Tonight's picks" row after weather and quotes), `Russian.java`, `German.java`, `Spanish.java`

**Interfaces:**
- Consumes: Task 3 (`Evening.get(ctx)`, `posters()`, `cringe()`, `Listener`), `Recs.backdrop`-style image loading (load posters on a worker into a small `LruCache<String,Bitmap>`, never in `onDraw`), `HomeApps` launching (same intent as a KINO.WATCH/YouTube pick with `link`).
- Produces: zone `Z_EVENING`; `eveningIndex` 0–3 posters, 4–6 shelf rows, 7 "All 7 tips"; overlay `eveningListOpen`.
- [ ] **Step 1: Layout** in the weather card's rect when `Evening.get(ctx).enabled()` and `posters()` is non-empty (else the weather card as today): left ~60% four portrait posters (2:3, radius as drawer tiles) with title (1 line, ellipsized) and reason (1 line, dim), a hairline divider, right ~40% the shelf: amber "VHS" chip + "Cringe for tonight", 3 rows (16:9 thumb, title, reason in amber), then "All 7 tips ›". A YouTube thumbnail in a portrait poster: whole picture over its blurred copy (as `Backdrop` does for memes).
- [ ] **Step 2: Focus and keys:** Down from the shortcuts row enters `Z_EVENING` at poster 0; Right/Left walk posters 0–3 then into shelf row 4; Up/Down move within rows 4–7; Up from a poster returns to the drawer row; OK on a poster or a playable row opens its `link` in its app; OK on "All 7 tips" opens the overlay list (7 rows, same row style, scrolling focus); Back closes the overlay, then the drawer. Focused poster grows like dock tiles (`FOCUS_GROW`), focused row gets the white pill.
- [ ] **Step 3: Strings:** "Tonight" ("На вечер"), "built today at %s" ("собрано сегодня в %s"), "Cringe for tonight" ("Кринж на вечер"), "All %d tips" ("Все %d советов"), "Tonight's picks" ("Подборка на вечер") in all languages.
- [ ] **Step 4: Build, install, screenshot** the drawer: posters, shelf, focus on a poster, focus on a shelf row, the overlay list; empty state (rename `evening.json` away) shows the weather card. One fix round at most.
- [ ] **Step 5: Commit** `git commit -m "Evening picks in the drawer"`.
