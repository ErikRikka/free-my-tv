# Плитка «Новости»: план работ

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Цель:** плитка «Новости» на полке. Фокус на ней показывает свежие истории наверху главного
экрана, ОК открывает полноэкранную ленту по одной истории. Отбор и пересказы на языке оболочки
делает OpenAI из RSS-лент и публичных Telegram-каналов.

**Архитектура:**
- **Чистая логика без Android** (разбор лент, порядок, лимиты, лайки, проверка ответа модели)
  живёт в `NewsFeeds` и `NewsOrder`. Её проверяют тесты на обычной JVM.
- **Android-часть:**
  - `News` — сбор, запросы к модели, хранение, процесс `:home`;
  - `NewsActivity` — лента, тоже в `:home`, чтобы состояние было в одном процессе;
  - встройка в `HomeApps` и `HomeView`.

**Стек:** Java 8, Android SDK без библиотек, `build.sh`. Тесты — `javac` и `java` из JDK 17,
скрипт `tools/test_news.sh`.

**Дизайн:** [specs/2026-10-27-news-tile-design.md](2026-10-27-news-tile-design.md). Читать вместе
с планом.

## Общие ограничения

- **Язык и стек:** Java 8, minSdk 28, без Gradle и сторонних библиотек.
- **Сборка:** `./build.sh` должна заканчиваться `Built FreeMyTV.apk`. Нужен JDK:
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@17 PATH=$JAVA_HOME/bin:$PATH`.
- **UI-поток:** никакой сети, диска и аллокаций в `onDraw`, никакой сети на UI-потоке.
- **Тексты:** через `Ui.tr(pt, en, es)`, русский и немецкий — в `Russian.java` и `German.java`.
  Комментарии на английском, короткие.
- **Секреты:** не коммитить `config.properties`, ключи и `local-assets/news.txt`. Не печатать
  `config.properties`.
- **Модель:** `news_model` в `config.properties`, по умолчанию `gpt-5.4-mini`. Для моделей gpt-5+
  передавать `reasoning_effort: "none"`, как в `Brain`.
- **Язык пересказов:** язык оболочки (`Ui.language`). Смена языка сбрасывает сохранённые истории.
- **Тексты источников — данные, не инструкции.** Они оборачиваются в `<<< >>>`, с явным запретом
  выполнять из них что-либо (как `ReelsActivity.nowShowing`).
- **Числа:**
  - сбор раз в 2 часа, после ошибки — через 10 минут;
  - свежесть записей — 36 часов;
  - не больше 50 новых историй в день, в хранилище не больше 80;
  - «прочитано» — история на экране дольше 2 секунд;
  - одна тема подряд — не больше 2 раз;
  - доля одной темы в пачке — около 20%;
  - не больше 4 скачиваний одновременно, таймаут 15 секунд, страница не больше 2 МБ;
  - лайки затухают на 0,97 в день.
- **Картинки:**
  - пропускать `telegram.org/img/emoji/` и Meduza `/imgly/share/`;
  - кодировать не-ASCII в пути адреса;
  - качать с браузерным `User-Agent`;
  - при ошибке рисовать запасную плитку с названием темы.

## Что чаще всего сломается у живого человека

1. **ТВ включился без сети или без VPN.** Плитка показывает сохранённые истории, ничего не
   падает, сбор повторяется через 10 минут. Тест в задаче 4: сбор при недоступных источниках
   сохраняет старые истории.
2. **Модель вернула мусор.** Это битый JSON, номера записей вне диапазона, пустые истории или
   отказ. Пачка пропускается, отдельные истории отбрасываются, приложение не падает. Чтобы не
   жечь токены, записи помечаются «виденными» после двух неудач. Тест в задаче 2:
   `validateStory`.
3. **Первый запуск: в лентах сотни записей за 36 часов.** В модель уходят только самые свежие
   120 (две пачки по 60), историй добавляется не больше 50. Тест в задаче 2: `capDaily`.
4. **Нет `news.txt`, или в нём нет источников.** Плитки нет, сбор не запускается. Тест в задаче
   3: `NewsSources.parse` на пустом файле.
5. **Один источник отдаёт огромную страницу или виснет.** Таймаут 15 секунд и потолок 2 МБ,
   остальные источники собираются. Тест в задаче 1: `readCapped` обрезает на лимите.

---

### Задача 1: разбор лент (`NewsFeeds`) и тестовый стенд

**Файлы:**
- Создать: `app/src/io/freemytv/NewsFeeds.java` (только `java.*` и `javax.xml.*`, без
  `android.*` и `org.json`).
- Создать: `tools/news_tests/NewsTests.java` — простой раннер, `check(boolean, String)` и `main`,
  который печатает `N passed` или падает с ненулевым кодом.
- Создать: `tools/news_tests/fixtures/` — синтетические образцы, свои тексты, не копии статей:
  - `rss_media.xml`
  - `rss_enclosure.xml`
  - `atom.xml`
  - `telegram.html`
  - `broken.xml`
- Создать: `tools/test_news.sh` — компилирует `NewsFeeds.java`, `NewsOrder.java`,
  `NewsSources.java` и тесты в `out/news-tests` и запускает их.

**Интерфейсы (Produces):**
```java
final class NewsFeeds {
    static final class Item { String id, source, title, text, link, image; long at; }   // at: ms epoch; id: stable hash of link (or source+text)
    static List<Item> parseRss(String xml, String source);        // RSS 2.0 and Atom; never throws, [] on bad input
    static List<Item> parseTelegram(String html, String channel); // t.me/s/<channel> page; oldest→newest as on the page
    static boolean imageAllowed(String url);                     // false for emoji/share-card images and blank
    static String encodeUrl(String url);                         // percent-encodes non-ASCII in the path
    static String readCapped(java.io.InputStream in, int maxBytes) throws java.io.IOException; // stops at maxBytes, UTF-8
}
```

- [ ] **Шаг 1. Тесты.** В `NewsTests` написать:
  - `rssMediaContent`: одна запись из `rss_media.xml`. `title`, `link`, `image` равны URL из
    `media:content`, `at` — разобранный `pubDate`.
  - `rssEnclosureAndInlineImg`: из `enclosure type="image/jpeg"`, а если его нет — из первого
    `<img src>` в `description`. HTML-теги вырезаны из `text`.
  - `atomEntry`: ссылка берётся из `<link href>`, дата — из `updated`.
  - `telegramPost`: из `telegram.html`:
    - текст сообщения без тегов;
    - `link == "https://t.me/chan/123"` из `data-post`;
    - `at` из `datetime`;
    - `image` из `background-image:url('https://cdn4.telesco.pe/…')`.
    Второе сообщение с картинкой `//telegram.org/img/emoji/…` даёт `image == null`.
  - `imageFilter`:
    - `imageAllowed("https://telegram.org/img/emoji/40/X.png") == false`;
    - `imageAllowed("https://meduza.io/imgly/share/1/x") == false`;
    - обычный jpg — `true`.
  - `encodeCyrillic`: в результате `encodeUrl("https://a.com/wp/сн.jpg")` нет символов вне
    ASCII, а хост и `https://` не тронуты.
  - `brokenXml`: `parseRss("<rss><item><title>x", "s")` не бросает исключение и возвращает пустой
    список.
  - `readCappedStops`: поток в 3 МБ при лимите 2 МБ даёт строку не длиннее 2 МБ.
- [ ] **Шаг 2.** Запустить `tools/test_news.sh` → компиляция падает: нет `NewsFeeds`.
- [ ] **Шаг 3. Реализация `NewsFeeds`.**
  - RSS и Atom: через `javax.xml.parsers.DocumentBuilder` с выключенными внешними сущностями
    (`setExpandEntityReferences(false)` и фича `disallow-doctype-decl`). Даты: RFC 822 и ISO 8601.
  - Telegram: регулярные выражения по правилам из дизайна (раздел «Что показал прототип»).
- [ ] **Шаг 4.** `tools/test_news.sh` → `8 passed`.
- [ ] **Шаг 5.** Коммит: `News: feed parsing with tests`.

### Задача 2: порядок, лимиты, лайки, проверка ответа модели (`NewsOrder`)

**Файлы:**
- Создать: `app/src/io/freemytv/NewsOrder.java` (только `java.*`).
- Изменить: `tools/news_tests/NewsTests.java` — добавить тесты.

**Интерфейсы:**
- Consumes: `NewsFeeds.Item`.
- Produces:
```java
final class NewsOrder {
    static final class Story { String id, title, summary, topic, flag, image, link, source, details; int score; long at; boolean read, liked; List<String> itemIds = new ArrayList<>(); }
    static List<Story> order(List<Story> unread);                       // score desc, then no topic more than 2 in a row while another topic remains
    static List<Story> capDaily(List<Story> candidates, int addedToday); // keeps the best (50 - addedToday), never negative
    static List<NewsFeeds.Item> freshest(List<NewsFeeds.Item> items, Set<String> seen, long now); // not seen, within 36 h, newest first, at most 120
    static boolean validateStory(List<Integer> itemIndexes, int batchSize, String title, String summary); // false if empty text or no index inside [0,batchSize)
    static double decayed(double weight, long likedAt, long now);         // weight * 0.97^days
    static List<String> topLiked(Map<String, double[]> weights, long now, int n); // value = {weight, likedAt}; top n names by decayed weight > 0.05
}
```

- [ ] **Шаг 1. Тесты:**
  - `orderNoLongRuns`: 6 историй, четыре темы «ИИ» со счётом 90–60 и две «Музыка» со счётом 50–40.
    В результате нигде нет трёх «ИИ» подряд, а первая история — «ИИ» со счётом 90.
  - `orderAllSameTopic`: 3 истории одной темы. Порядок по счёту, ничего не теряется.
  - `capDailyRespectsToday`: 60 кандидатов, 10 уже добавлено сегодня. Остаётся 40 с наибольшим
    счётом. При 55 уже добавленных возвращается пустой список.
  - `freshestFilters`:
    - отбрасывает виденные id и записи старше 36 часов;
    - сортирует от новых к старым;
    - режет до 120.
  - `validateStoryRejects`:
    - `([7], 5, "t", "s")` → false;
    - `([], 5, "t", "s")` → false;
    - `([1], 5, "", "s")` → false;
    - `([1, 9], 5, "t", "s")` → true: годится хотя бы один номер, лишние отбрасываются при
      сборке.
  - `decay`: `decayed(1.0, now − 10 дней, now)` равно `0.97^10` с точностью 1e-6.
  - `topLikedOrder`: три имени с разными весами и датами → два лучших по затухшему весу.
- [ ] **Шаг 2.** `tools/test_news.sh` → падает: нет `NewsOrder`.
- [ ] **Шаг 3. Реализация.** `order` — жадная перестановка: брать лучшую историю, тема которой
  не нарушает правило «не больше 2 подряд». Если таких нет, брать лучшую из оставшихся.
- [ ] **Шаг 4.** `tools/test_news.sh` → все проходят.
- [ ] **Шаг 5.** Коммит: `News: ordering, daily cap, likes decay`.

### Задача 3: файл источников и тем, настройки

**Файлы:**
- Создать: `app/src/io/freemytv/NewsSources.java` (только `java.*`).
- Создать: `news.example.txt` в корне репозитория. Нейтральный пример:
  - 3 RSS-ленты (The Verge, PetaPixel, Pitchfork) и 2 Telegram-канала (rozetked, meduzalive);
  - 4 общие темы;
  - 1 строка в `[practical]`.
- Создать (не коммитить, `local-assets/` уже в `.gitignore`): `local-assets/news.txt`.
  - Источники — полный список из дизайна (раздел «Источники и темы»).
  - Темы:
    - гаджеты, телефоны, прошивки, Mac;
    - игры и PS5;
    - мировая музыка: пост-хардкор и металкор (Bring Me the Horizon, Northlane, Sleep Token),
      яркий рэп (Tyler, the Creator, Kanye West);
    - кино: инди, хорроры, A24, Netflix, документалки;
    - съёмка: камеры, объективы, DJI, монтаж, DaVinci Resolve, цветокоррекция;
    - VR и Quest;
    - нейросети и код;
    - стиль и кроссовки: дропы и коллаборации;
    - громкая политика: мир, Россия, Петербург.
  - `[practical]`: Таиланд для туристов; Сербия — визы, ВНЖ, граница.
  - Раздел `[boring]`: сделки и акции, отчёты компаний, рядовые аресты чиновников, криминал без
    резонанса.
- Изменить: `build.sh` — `static final String NEWS_MODEL = "$(prop news_model 'gpt-5.4-mini')";`
  рядом с `OPENAI_MODEL` (строка 75).
- Изменить: `config.example.properties` — `news_model=` с комментарием.
- Изменить: `README.md` — короткий раздел о `news.txt`: формат, где лежит, что без него плитки
  нет.
- Изменить: `tools/test_news.sh` — компилировать и `NewsSources.java`.

**Интерфейсы (Produces):**
```java
final class NewsSources {
    static final class Source { final String kind /* "rss" | "tg" */, where /* url or channel */, label; }
    final List<Source> sources; final List<String> topics, practical, boring;
    static NewsSources parse(String text);   // sections [sources] [topics] [practical] [boring]; '#' comments; bad lines skipped
    boolean usable();                         // at least one source
}
```

- [ ] **Шаг 1. Тесты:**
  - `sourcesParse`: файл со всеми четырьмя разделами, комментарием и строкой
    `rss` без URL. Получается 2 источника, а строка без URL пропущена. Темы — без пустых строк.
  - `sourcesEmpty`: `parse("")` и `parse("[topics]\nИИ")` дают `usable() == false`.
- [ ] **Шаг 2.** Тесты падают → реализовать → `tools/test_news.sh` проходит.
- [ ] **Шаг 3.** `./build.sh` → `Built FreeMyTV.apk`. Проверить, что `out/gen/io/freemytv/Config.java`
  содержит `NEWS_MODEL`.
- [ ] **Шаг 4.** `git status` не показывает `local-assets/news.txt`. Коммит:
  `News: sources file, example, model setting`.

### Задача 4: сбор и отбор (`News`)

**Файлы:**
- Создать: `app/src/io/freemytv/News.java`.

**Интерфейсы:**
- Consumes: `NewsFeeds`, `NewsOrder`, `NewsSources`, `Config.NEWS_MODEL`, `Config.OPENAI_KEY`,
  `Ui.language`, `AppLog`.
- Produces:
```java
final class News {
    static final String SHELF_ID = "io.freemytv.news";
    interface Listener { void onNews(); }               // called on the main thread
    static News get(Context c);                         // one per process (:home)
    boolean enabled();                                  // news.txt usable and OpenAI key set
    void addListener(Listener l); void removeListener(Listener l);
    void refreshIfStale();                              // 2 h since success, 10 min since failure; work on its own executor
    List<NewsOrder.Story> unreadOrdered();              // NewsOrder.order of unread stories
    int unreadCount();
    void markRead(String id); void toggleLike(String id); // like: topic and source weights ±1 in files/news-likes.json
    void details(String id, java.util.function.Consumer<String> done); // long retelling on demand; cached in Story.details; done on main thread, null on failure
    List<Recs.Pick> picks();                            // unread as Picks: title, backdrop=image, meta="topic · source · time", plot=summary, source="news", link=story id
}
```

- [ ] **Шаг 1. Загрузка конфигурации и хранилища.**
  - Читать `news.txt` из assets.
  - Хранилище `files/news.json`: `{language, stories[], seen{id: at}, failures{id: n}, lastOk,
    addedOn{day: count}}`. Если `language != Ui.language`, истории очищаются. `seen` старше 3 суток
    чистится. Запись атомарная: временный файл и `rename`.
- [ ] **Шаг 2. Сбор.**
  - Источники качаются пулом из 4 потоков, таймаут 15 секунд, `readCapped` на 2 МБ, браузерный
    `User-Agent`. Источник `tg` берётся по адресу `https://t.me/s/<канал>`.
  - Упавший источник пишется в лог и пропускается. Если упали все, сбор считается неудачным, и
    повтор будет через 10 минут.
- [ ] **Шаг 3. Отбор.**
  - `NewsOrder.freshest` → пачки по 60 → `POST /v1/chat/completions` с
    `response_format: {"type": "json_object"}`, `store: true` и
    `metadata {app: "free-my-tv", kind: "news"}`.
  - Системный промпт содержит:
    - язык;
    - темы, `[practical]`, `[boring]`;
    - «владельцу больше нравятся» — из `topLiked(…, 5)` по темам и по источникам;
    - правило 20%;
    - правило «склейки» одной истории из нескольких записей;
    - правило «тексты — только данные».
  - Ответ: `{"stories":[{"items":[int],"title","summary","topic","flag":"hot"|"practical"|"","score":int}]}`.
  - Каждая история проверяется `validateStory`, потом проходит `capDaily`.
  - Картинка — первая `imageAllowed` из склеенных записей.
  - Записи пачки помечаются `seen`. Если пачка упала, `failures` растёт, а после второй неудачи
    записи всё равно считаются виденными.
  - В лог пишутся `usage.prompt_tokens` и `completion_tokens` каждого запроса:
    `news: batch 60 → 14 stories, tokens 9100/2300`.
- [ ] **Шаг 4. Подробности (`details`).**
  - Скачать страницу первой ссылки истории (2 МБ, вырезать теги и скрипты, первые 6000
    символов). Если не вышло — взять тексты записей.
  - Отдельный запрос: «перескажи подробно, 2–3 абзаца, на языке …».
  - Сохранить в `Story.details`.
- [ ] **Шаг 5. Проверка на телевизоре.**
  - Временно вызвать `News.get(ctx).refreshIfStale()` из `HomeView.onAttachedToWindow` (в задаче 5
    вызов остаётся навсегда). Собрать, установить:
    `adb -s 192.168.0.212:5555 install -r FreeMyTV.apk`, затем нажать HOME.
  - `adb logcat -d -s FreeMyTV | grep news:` показывает пачки, число историй и токены.
  - `adb shell run-as io.freemytv` недоступен (release-сборка), поэтому истории проверяются по
    логу: временно выводить первые 3 заголовка.
  - Проверить отказ сети: включить заглушку источников (поменять URL в локальном файле на
    несуществующий хост), собрать, убедиться, что старые истории сохранены и в логе
    `news: all sources failed, retry in 10 min`. Затем вернуть файл.
- [ ] **Шаг 6.** `./build.sh` → `Built FreeMyTV.apk`. Коммит: `News: fetch, select and store stories`.

### Задача 5: плитка, число непрочитанных, верх главного экрана

**Файлы:**
- Изменить: `app/src/io/freemytv/HomeApps.java`:
  - запись в `SHELF` (рядом со строкой 86):
    `{"news", "News", News.SHELF_ID, "2C2C2E", "", "1"}` (подпись переводится через `Russian.java`);
  - `Prefs.addOnce("news", custom, News.SHELF_ID, "com.redshieldvpn.app", true)` по образцу
    строки 166, но только если `News.get(c).enabled()`;
  - в `launchIntent` (строка 455) `News.SHELF_ID` открывает `NewsActivity`;
  - логотип — текстом, как у KINO.WATCH.
- Изменить: `app/src/io/freemytv/HomeView.java`:
  - `updateCards` (около строки 2815): если `feedKey` равен `News.SHELF_ID`, список берётся из
    `News.get(ctx).picks()`;
  - `HomeView` реализует `News.Listener`, `onNews()` делает то же, что `onRecs()` (строка 620);
  - `refreshIfStale()` вызывается там же, где `recs.refreshIfStale()` (строки 404 и 480);
  - ОК по верху экрана при новостной плитке открывает `NewsActivity` с
    `extra "story" = pick.link`;
  - текст кнопки «Читать», а не «Смотреть»;
  - в `drawShelfTile` (рядом с точкой VPN) — число непрочитанных в капсуле в правом верхнем углу
    плитки. Строка числа кешируется при изменении, как `pagerText`.
- Изменить: `Russian.java`, `German.java`: «News» → «Новости» / «Nachrichten», «Read» → «Читать» /
  «Lesen».
- Изменить: `AndroidManifest.xml`: `NewsActivity` с `android:process=":home"`, тема как у
  `ReelsActivity`, `exported=false`.

- [ ] **Шаг 1.** Внести изменения. Для манифеста создать пустой `NewsActivity`, который
  показывает id истории. Задача 6 заменит его настоящим.
- [ ] **Шаг 2.** `./build.sh` → `Built FreeMyTV.apk`. Установить.
- [ ] **Шаг 3. Проверка на телевизоре скриншотами.** Скриншот: `adb exec-out screencap -p`, из
  вывода вырезать текстовый префикс до `\x89PNG`.
  - Плитка есть на полке, на ней число.
  - Фокус на плитке: наверху новостная история с картинкой, заголовком, «темой · источником ·
    временем» и пересказом.
  - Картинка наверху загружается: `Recs` грузит фон по `Pick.backdrop`. Если для чужих лент он
    этого не делает, добавить вызов загрузки для новостных Pick, не дублируя загрузчик.
  - Если непрочитанных 0, числа на плитке нет.
  - ОК открывает заглушку с нужным id.
  - Фокус на других плитках работает как раньше.
- [ ] **Шаг 4.** Коммит: `News: shelf tile, unread count, hero stories`.

### Задача 6: лента `NewsActivity`

**Файлы:**
- Создать или заменить: `app/src/io/freemytv/NewsActivity.java`.
- Изменить: `Russian.java`, `German.java`. Новые строки:
  - «You've read everything»;
  - «Next update at %s»;
  - «More like this»;
  - «Less of this»;
  - «Couldn't load details»;
  - «▲▼ next stories»;
  - «OK — details».

**Интерфейсы:** Consumes `News.get(this)`: `unreadOrdered`, `markRead`, `toggleLike`,
`details`, `refreshIfStale`; `ReelsActivity.Sign` (сердечко и кольцо загрузки) — сделать его
package-private, если ещё нет.

- [ ] **Шаг 1. Экран истории** — нативные `View` по макету C. Размеры — доли высоты экрана, как в
  `ReelsActivity`.
  - картинка на весь экран, `centerCrop`; запасная плитка с названием темы;
  - нижнее затемнение `GradientDrawable`;
  - полоски прогресса сверху;
  - тема и пометка: «обсуждают» (`#FF6A4D`) или «важно для поездки» (`#6FD3A8`);
  - «источник · время»;
  - заголовок: Inter semibold, около 6% высоты;
  - пересказ: около 3% высоты;
  - справа внизу «N / M» и подсказки.

  Картинки грузятся в фоне в `cacheDir/news-img/` (по хешу URL, уменьшение до размера экрана,
  лимит папки 60 МБ, старые удаляются).
- [ ] **Шаг 2. Пульт:**
  - вверх и вниз — соседняя история, скольжение с затуханием около 250 мс;
  - старт — с истории из `extra "story"`, иначе с первой непрочитанной;
  - через 2 секунды на экране — `markRead`;
  - после последней — экран «Вы всё прочитали» и время следующего обновления;
  - Назад — домой.
- [ ] **Шаг 3. Вправо.** `toggleLike`, сердечко из `ReelsActivity.Sign` и короткая подпись
  «Больше такого» или «Меньше такого».
- [ ] **Шаг 4. ОК.**
  - Пока ждём — кольцо загрузки.
  - Потом поверх картинки панель с подробным пересказом, тёмная подложка ~85%.
  - Вверх и вниз прокручивают текст, Назад закрывает панель.
  - Ошибка — «Не получилось загрузить подробности».
  - Второе ОК по той же истории показывает сохранённое сразу.
- [ ] **Шаг 5.** `./build.sh` → `Built FreeMyTV.apk`, установить.
- [ ] **Шаг 6. Проверка на телевизоре скриншотами:**
  - история;
  - листание вниз и вверх;
  - число на плитке уменьшилось после чтения;
  - ОК → кольцо → подробности → прокрутка → Назад;
  - вправо → сердечко.

  Для проверки лайка вернуть его вторым «вправо», чтобы не портить отбор владельца.
- [ ] **Шаг 7.** Коммит: `News: full-screen feed with likes and details`.

### Задача 7: итоговая проверка

- [ ] **Шаг 1.** `tools/test_news.sh` → все тесты проходят. `./build.sh` → `Built FreeMyTV.apk`.
  `python3 tools/check_reels_script.py` → `page script OK` (Reels не задеты).
- [ ] **Шаг 2.** На телевизоре через 2 часа или после перезапуска с очищенным `lastOk` новые
  истории добавляются, прочитанные не повторяются. В логе видны токены каждого сбора.
- [ ] **Шаг 3.** Посчитать по логу расход за день (токены × цена `news_model`) и сообщить
  владельцу.
- [ ] **Шаг 4.** Версия и релиз — только по просьбе владельца. Это крупное изменение по его
  правилу: +1, то есть 3.5.
