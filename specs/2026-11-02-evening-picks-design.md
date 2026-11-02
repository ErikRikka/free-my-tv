# Evening picks: design

## Intent

The drawer under the dock (Down from the shelf) shows inputs, shortcuts and a big weather card today. The weather
repeats what the top right already shows, and the drawer is rarely worth opening. It becomes **a pick for tonight**:
something to watch, chosen once a day by ChatGPT from what the household actually likes, ready to play with one press.

The household watches together in the evening and often spends the evening searching instead of watching. Two needs:

1. **Something good to put on**, matching their taste (films, series, anime, shows, YouTube videos).
2. **Something gloriously bad**: a separate rubric of "cringe" content to laugh at while chilling. They have run out of
   the obvious ones and end up googling. The point of this rubric is finding *new* things of that kind.

## Goals

- In the drawer, in place of the weather card: a row of **playable cards** (about 4) and one **"Cringe for tonight"**
  card.
- Playable cards: each one a concrete title or video that opens and plays with OK in YouTube or KINO.WATCH. Anything
  goes: films, series, anime, TV shows, YouTube videos, as long as it plays.
- The cringe card opens a list of **5–7 tips**, each with one line on why it's funny. OK on a tip plays it right away
  when it can be found on YouTube or KINO.WATCH; otherwise it stays a text tip.
- Both refresh **once a day**, for the evening; nothing suggested before comes back (a history of suggestions).
- Both use **web search**: the cringe rubric needs real, recent, obscure finds, which the model alone can't know or
  would invent; the regular picks gain fresh releases.

## Non-goals

- No change to the weather in the top right; no new settings screen beyond what's needed to turn this on and off.
- No per-person profiles: one taste for the household.
- No sending links from a phone (a separate idea, not now).

## Anti-goals

- Suggesting the references themselves or things already seen (the whole point is new finds).
- Cards that don't play: a "playable" card that opens a search page or nothing.
- Invented titles or videos (every tip that claims to play must have been found and checked).
- Personal taste data in the public repository.

## Taste signals (checked 2026-11-02)

| Source | Access | Used for |
|---|---|---|
| YouTube | OAuth already in config: 140 subscriptions, ~2800 liked videos | channels and topics they like; liked video titles (a sample) |
| KINO.WATCH (kino.pub) | refresh token already in config, used on the TV | watch history and bookmarks (`/v1/history`, `/v1/bookmarks`); search to resolve picks; links that open the title |
| Kinopoisk ratings | not reachable automatically (Yandex sign-in) | a one-time export into `local-assets/kinopoisk.txt` (title, year, rating), refreshed on request |
| Interests | `local-assets/news.txt` topics, news likes | genres and themes |
| Cringe references | written by them | `local-assets/evening.txt`: the references and what makes them good (never suggested) |

Only kino.pub's own process may refresh its token (the home process, as today): a refresh elsewhere signs the TV out.

## Approaches considered

1. **ChatGPT once a day without web search**, resolving each pick through YouTube and KINO.WATCH search. Cheap
   (~1–2¢/day), but the model knows nothing recent and invents obscure videos: weak for the cringe rubric.
2. **ChatGPT with web search once a day** (OpenAI Responses API, `web_search` tool), then the same resolving and
   checking. Real, fresh finds; ~5–10¢/day; slower, but it runs ahead of time.
3. **No AI**: YouTube's related videos and TMDB's "similar". Nearly free, but recommends the popular, never the
   gloriously bad. Dropped.

**Chosen: 2 for everything** (their call): both the regular picks and the cringe rubric use web search.

## How it works (decided)

- **When:** once a day, prepared ahead for the evening (the first time the home screen runs after 15:00, or on the
  first wake after that); a failed run retries in 30 minutes.
- **Taste summary:** gathered on the TV each run from the sources above, trimmed to a compact prompt (channel names,
  a sample of liked titles, history titles, top Kinopoisk ratings, interests).
- **Regular picks:** the model returns ~8 candidates with kind, title, year and a search query; each is resolved:
  films/series/anime through kino.pub search (must match title and year), videos through YouTube search (must exist,
  not a short under 60 s unless asked). The first 4 that resolve become the cards. Already suggested or already
  watched (history) are excluded.
- **Cringe rubric:** the model gets the references and their description from `evening.txt`, searches the web for
  new finds of that kind, returns ~10 with a one-line "why it's funny" and a search query; each is resolved through
  YouTube, then kino.pub. The 5–7 best are shown; resolved ones play with OK, the rest stay text.
- **History:** everything shown is remembered for 90 days and passed to the model as "already suggested".
- **Drawer layout (chosen: "cassettes and a shelf"):** in the weather card's place, one row under the inputs and
  shortcuts, in the drawer's existing glass style:
  - left: 4 **portrait posters** (KINO.WATCH's poster; a YouTube video gets its thumbnail cropped to portrait over a
    blurred copy of itself), each with the title and a one-line reason under it ("for you: slow A24 horror"); the
    focused one grows like the dock tiles;
  - a thin divider, then right, wider: the **"Cringe for tonight" shelf**, its first 3 tips visible as rows
    (thumbnail, title, the one-line "why it's funny") with no extra press; each row is focusable and plays with OK;
  - the shelf's last row, "All 7 tips", opens the full list over the drawer; Back closes it;
  - the cringe shelf has its own warm accent (amber, like a VHS label) so it reads as a separate rubric, not a
    fifth poster.
- **Navigation:** Down from the shortcuts enters the posters; Right walks the posters, then into the shelf's rows;
  Down/Up move between the shelf's rows.
- **Off switch:** without an OpenAI key, or with the feature off in Home Settings, the drawer shows the weather as
  today.
- **Cost:** about 5–10¢ a day (two web-search calls plus tokens), logged like the news.

## Left to the builder

- The exact card and list layout within the drawer's current style (Liquid Glass, focus grow, TV-safe sizes).
- The OpenAI request shape for web search (Responses API) and the JSON the model returns.
- Matching rules for resolving (how strict on title and year), and YouTube quota use (search costs 100 units of
  10,000 a day: ~20 searches a day is fine).
- Strings in all languages (`Ui.tr`, Russian, German, Spanish).
- Where the day's result is stored (a file like `news.json`).
