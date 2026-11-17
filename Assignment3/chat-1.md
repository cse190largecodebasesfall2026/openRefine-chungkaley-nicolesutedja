
❯ I'm working on a course assignment in this OpenRefine repo (branch:
  assignment3, created from main). We need to add a "translate column" feature:
  the user selects an existing text column, chooses a target language, and a
  new column with the translated text is created next to the original.

  Requirements:
  - Support English, Spanish, and Mandarin Chinese, each as both source and
  target. More languages are optional; adding one should be easy and
  documented.
  - Translation can use a hosted API or a local server/model, but it must cost
  $0, be easy for someone else to set up, and keep credentials out of committed
  files.
  - The new column must align row-for-row with the original. Preserve the
  source column, all other data, and row order. Never silently overwrite an
  existing column.
  - Follow OpenRefine's existing conventions for frontend and backend
  (operations, commands, history/undo, dialogs, i18n).
  - Handle edge cases and failures, with the chosen behavior documented.
  - Add tests for normal behavior, edge cases, and failure handling. Run the
  existing relevant tests, and never delete or disable failing ones.
  - Deliverables later: Assignment3/design.md, journal.md, a ~20-row
  multilingual sample dataset, and chat export.

  IMPORTANT ground rules: my partner and I must make the design decisions, not
  you. Do NOT write or modify any code yet.

● I'll orient myself in the codebase first (read-only) so I can lay out the
  relevant conventions and the decisions you two will need to make.

  Ran 6 shell commands (ctrl+o to expand)

● I haven't written or changed anything. Below is what I found in the codebase,
  then the decisions that are yours and your partner's to make. Where it helps,
  I've listed options and trade-offs, but I haven't picked any.

  How OpenRefine already does something similar

  The closest existing feature is "Add column by fetching URLs". It reads a
  column, makes one HTTP call per row, and adds a new column. It touches every
  layer you'll need:

  Layer: Operation (backend logic, saved to history as JSON)
  Existing example: ColumnAdditionByFetchingURLsOperation.java
    (main/src/com/google/refine/operations/column/ColumnAdditionByFetchingURLsOp
  eration.java)
  ────────────────────────────────────────
  Layer: Background job with progress and cancel
  Existing example: the inner LongRunningProcess at line 268
    (main/src/com/google/refine/operations/column/ColumnAdditionByFetchingURLsOp
  eration.java#L268)
  ────────────────────────────────────────
  Layer: Undo/redo
  Existing example: ColumnAdditionChange.java
    (modules/core/src/main/java/com/google/refine/model/changes/ColumnAdditionCh
  ange.java),
     wrapped in a HistoryEntry (line 359
    (main/src/com/google/refine/operations/column/ColumnAdditionByFetchingURLsOp
  eration.java#L359))
  ────────────────────────────────────────
  Layer: Command (receives the HTTP request from the browser)
  Existing example: AddColumnByFetchingURLsCommand.java
    (main/src/com/google/refine/commands/column/AddColumnByFetchingURLsCommand.j
  ava)
  ────────────────────────────────────────
  Layer: Registration
  Existing example: controller.js:181
    (main/webapp/modules/core/MOD-INF/controller.js#L181)
  ────────────────────────────────────────
  Layer: Dialog and column menu
  Existing example: add-column-by-fetching-urls-dialog.html
    (main/webapp/modules/core/scripts/views/data-table/add-column-by-fetching-ur
  ls-dialog.html),
     menu-edit-column.js:108
    (main/webapp/modules/core/scripts/views/data-table/menu-edit-column.js#L108)
  ────────────────────────────────────────
  Layer: i18n
  Existing example: core-views/* keys in translation-en.json
    (main/webapp/modules/core/langs/translation-en.json)
  ────────────────────────────────────────
  Layer: Tests
  Existing example: ColumnAdditionByFetchingURLsOperationTests.java
    (main/tests/server/src/com/google/refine/operations/column/ColumnAdditionByF
  etchingURLsOperationTests.java)
     fakes the remote server with MockWebServer, plus the Cypress specs in
    main/tests/cypress/.../edit-column/

  Some existing behaviour bears directly on your requirements:
  - Name clash: if the new column name already exists, the job fails (line 320
    (main/src/com/google/refine/operations/column/ColumnAdditionByFetchingURLsOp
    eration.java#L320)). It doesn't overwrite.
  - Facets: only rows that pass the current facets/filters are processed. Rows
    that are filtered out get an empty cell in the new column.
  - Per-cell errors: handled by an OnError setting (keep original, set to blank,
    or store the error).
  - Credentials (important): the full operation JSON is saved in project history
    and shown by "Extract operation history". Anything stored as an operation
    parameter, such as an API key, would leak into exported histories and saved
    projects. Credentials need to live somewhere else, for example
    PreferenceStore (kept in the user's data directory, outside the repo), an
    environment variable, or refine.ini.

  Decisions for you two

  1. Translation service. Some candidates that meet the $0 requirement:
     - LibreTranslate, self-hosted: runs in Docker, needs no key, works offline,
       supports en/es/zh. The catch is a large first download.
     - A free hosted API such as MyMemory: nothing to install, but it has daily
       quotas and you depend on a third party.
     - The free tier of a commercial API (DeepL, Google, Azure): better quality,
       but it needs an account and a key, and sometimes a card.
     - A local LLM (e.g. Ollama): flexible but slow and less predictable, which
       makes it harder to test.
  2. Architecture:
     - a new dedicated operation modelled on fetch-URLs;
     - reusing the fetch-URLs operation with a preset URL template;
     - or a new extension under extensions/.

     Each choice changes how much code you write and how clearly translation
     shows up as its own step in the undo history.
  3. Where the endpoint and key live, and how someone sets them up. Your setup
     instructions depend on this.
  4. Source language: the user picks it, or the service auto-detects it. For
     Mandarin, you also need to choose Simplified or Traditional script and
     which language codes to use.
  5. Name for the new column: what the default is, and what happens on a clash.
     Options are to block it in the dialog, fail like fetch-URLs does, or add a
     suffix automatically.
  6. Edge-case behaviour:
     - blank or null cells;
     - numbers and dates;
     - cells that already hold errors;
     - source language equal to target;
     - very long text;
     - repeated values (cache them or not);
     - rows hidden by facets;
     - records mode vs. rows mode.
  7. Failure behaviour:
     - one cell fails vs. the service being down;
     - partial results vs. all-or-nothing;
     - timeouts, rate limits and retries;
     - what the user sees when it cancels.
  8. Making new languages easy to add: a hard-coded list, a config file, or a
     list fetched from the service.

  Once you've settled these, I can help write them up in Assignment3/design.md,
  look into any one option in more depth, or start coding.

  One small thing I noticed: AGENTS.md says "JDK 21 minimum" in one place and
  "must work with Java 11-21" in another. You may want to confirm which applies
  before relying on newer Java features.

❯ <ide_opened_file>The user opened the file
  /home/kac101/openRefine-chungkaley-nicolesutedja/AGENTS.md in the IDE. This
  may or may not be related to the current task.</ide_opened_file>

❯ Compare the translation services stating pros and cons for each option.

● Here's a comparison of the realistic options. Free-tier limits and signup
  rules change often, so check each provider's current terms before you rely on
  any of the numbers below. They reflect my knowledge as of mid-2026.

  At a glance

  LibreTranslate (self-hosted)
  Truly $0?: Yes
  Needs signup or key?: No
  Setup effort for a classmate: Medium (Docker)
  Quality (en/es/zh): Fair to good
  Data leaves the machine?: No
  Fits your repo/CI testing: Good
  ────────────────────────────────────────
  MyMemory
  Truly $0?: Yes
  Needs signup or key?: No (email optional)
  Setup effort for a classmate: Very low
  Quality (en/es/zh): Inconsistent
  Data leaves the machine?: Yes
  Fits your repo/CI testing: Good
  ────────────────────────────────────────
  DeepL API Free
  Truly $0?: Yes, within quota
  Needs signup or key?: Yes, plus a card for verification
  Setup effort for a classmate: Low once the account exists
  Quality (en/es/zh): Very good
  Data leaves the machine?: Yes
  Fits your repo/CI testing: Good
  ────────────────────────────────────────
  Google Cloud Translation
  Truly $0?: Free credit only
  Needs signup or key?: Yes, plus a billing account
  Setup effort for a classmate: Medium (GCP console)
  Quality (en/es/zh): Very good
  Data leaves the machine?: Yes
  Fits your repo/CI testing: Good
  ────────────────────────────────────────
  Azure Translator (F0 tier)
  Truly $0?: Yes (throttles instead of billing)
  Needs signup or key?: Yes, Azure account plus key and region
  Setup effort for a classmate: Medium
  Quality (en/es/zh): Very good
  Data leaves the machine?: Yes
  Fits your repo/CI testing: Good
  ────────────────────────────────────────
  Local LLM via Ollama
  Truly $0?: Yes
  Needs signup or key?: No
  Setup effort for a classmate: Medium to high (large model download)
  Quality (en/es/zh): Varies by model
  Data leaves the machine?: No
  Fits your repo/CI testing: Harder
  ────────────────────────────────────────
  Unofficial Google endpoint (translate_a/single)
  Truly $0?: "Free"
  Needs signup or key?: No
  Setup effort for a classmate: Very low
  Quality (en/es/zh): Very good
  Data leaves the machine?: Yes
  Fits your repo/CI testing: Not recommended

  Your unit tests will fake the HTTP server either way, as the existing
  fetch-URLs tests do with MockWebServer. So the choice mostly affects manual
  testing, the demo, and how easy it is for someone else to set up.

  Each option

  1. LibreTranslate (self-hosted, open source)

  Run it with docker run -p 5000:5000 libretranslate/libretranslate --load-only
  en,es,zh. It exposes a simple REST endpoint, POST /translate.

  Pros
  - $0 with no account, no key and no quota, so the credentials problem mostly
    disappears.
  - Works offline and keeps the data private.
  - Supports en, es and zh (Simplified), with Traditional available in newer
    versions. Also supports source: "auto" and accepts an array of strings per
    request.
  - Adding a language is mostly a matter of loading another model, which suits
    your "easy to add" requirement.
  - Open source (AGPL), and the same API can point at a hosted instance if
    someone already runs one.

  Cons
  - Each person needs Docker, plus a multi-GB first download and about 1–2 GB of
    RAM.
  - Translation quality is noticeably below DeepL or Google, especially for
    Chinese and idioms.
  - Slower on CPU-only laptops.
  - The public libretranslate.com instance needs a paid key, so "free" means
    self-hosted.

  2. MyMemory (free hosted API)

  Usage looks like GET
  https://api.mymemory.translated.net/get?q=...&langpair=en|es.

  Pros
  - Nothing to install, no key needed, and it works immediately.
  - Adding an optional email parameter raises the daily quota without any secret
    credential.

  Cons
  - Small daily quota: roughly 5,000 characters a day anonymously, about 50,000
    with an email. A few test runs on a 20-row dataset could use it up.
  - About 500 bytes per request and no batching, so one call per cell.
  - Results come from a mix of crowd-sourced translation memory and machine
    translation, so quality varies. You can occasionally get odd human matches.
  - Uses its own language codes (e.g. zh-CN) and has weak auto-detect.
  - Depends on a third party: if it's down or rate-limits you, your demo and
    tests are affected. Your data is sent externally.

  3. DeepL API Free

  Pros
  - Excellent quality, especially for es ↔ en, and good for zh.
  - About 500,000 characters a month free, which is plenty.
  - Batches many texts per request and has a clean, well-documented API with
    clear error codes for quota (456) and rate limits (429).

  Cons
  - Each person needs an account, and signup usually requires a credit card for
    verification. That may fail your "easy for someone else to set up"
    requirement.
  - An API key is mandatory, so you must build the secure configuration path
    (preferences, env var, or refine.ini) and document it.
  - Its language list is closed: you can only add languages DeepL supports.
  - Data is sent to DeepL.

  4. Google Cloud Translation

  Pros
  - Top-tier quality, the widest language coverage (adding languages is
    trivial), and good auto-detect.

  Cons
  - Needs a GCP project with billing enabled. The free allowance is a credit, so
    going past it costs money, which is a real risk against your $0 rule.
  - The heaviest setup of the hosted options: console, project, API enablement,
    key restrictions.
  - A key is required, and your data goes to Google.

  5. Azure Translator (F0 free tier)

  Pros
  - About 2M characters a month free. The F0 tier throttles rather than bills,
    so it's safer than Google for staying at $0.
  - High quality, broad language support, batching and auto-detect.

  Cons
  - Needs an Azure account (signup typically asks for a card), and you must
    create a resource and handle both a key and a region.
  - The setup steps are the most confusing for a classmate to follow.
  - Data goes to Microsoft.

  6. Local LLM via Ollama (e.g. a Qwen or Llama model)

  Pros
  - $0, offline and private, with no key.
  - Qwen-family models handle Chinese well. Any language the model knows works,
    so adding languages is easy.

  Cons
  - Slow on a CPU, possibly seconds per cell, and a multi-GB model download.
  - Output varies between runs and may add chatter ("Here is the translation:
    …"), so you need prompt design and output cleanup.
  - Harder to test for exact results, and failures are fuzzy (a wrong language
    or a partial answer) rather than clean HTTP errors.
  - Nobody guarantees quality.

  7. Unofficial Google Translate endpoint (listed so you can rule it out)

  It's free, needs no key and gives good quality. But it isn't a public API,
  likely breaks Google's terms of service, can be blocked without warning, and
  would look bad in a design review. I'd avoid it.

  Questions that might help you decide

  - Setup vs. credentials: do you prefer each user installing something
    (LibreTranslate or Ollama, with no key) or each user signing up for an
    account (DeepL or Azure, with a key to manage)?
  - Quality vs. independence: how much does translation quality matter compared
    with working offline and not depending on a third party?
  - Supporting several services: should the design allow more than one backend
    behind a common interface (e.g. LibreTranslate by default, DeepL optional)?
    That adds work but makes "easy to set up" and "good quality" both reachable.
  - Your demo: will the service be reachable at grading time? Quotas and outages
    matter most there.

❯ Why is MyMemory's quality inconsistent?

● MyMemory isn't one translation engine. It's a translation memory, a large
  database of sentence pairs that people have translated before, with machine
  translation as a fallback. What you get back depends on which of those paths
  answered your request.

  Where its translations come from

  1. Existing human translations taken from public multilingual sources, such as
     EU and UN documents and multilingual websites.
  2. Contributions from users. Anyone can add translation pairs through its API,
     and they aren't strictly reviewed.
  3. A machine-translation fallback. If the database has no good match, it runs
     the text through an MT engine (provided by Translated, the company behind
     MyMemory).

  For each request it looks for an exact or near ("fuzzy") match in the memory
  first, and only falls back to MT when nothing scores well.

  Why that makes quality uneven

  - Unvetted contributions. A crowd-submitted pair can be wrong, joking or spam,
    and if it matches your text exactly, it can win.
  - Fuzzy matches. A near match is the translation of a similar sentence, not
    yours. "The meeting is not on Tuesday" can match "The meeting is on Tuesday"
    closely and come back with the negation missing.
  - No context or domain. Each cell is looked up on its own. A short, ambiguous
    string like "Bank", "Spring" or "Chair" gets whichever stored sense happens
    to rank highest. This matters for your data, since spreadsheet cells are
    often short.
  - Changing results. The memory keeps growing, so the same input can translate
    differently from one week to the next.
  - Inherited formatting. Capitalization, punctuation and spacing can come from
    the stored sentence rather than your input.
  - Thinner coverage for Chinese. There are fewer stored zh ↔ es pairs than en ↔
    es, so you hit the MT fallback, or weaker fuzzy matches, more often.

  What this means for your edge-case handling

  - Quota errors that look like translations. When you run out of quota,
    MyMemory has historically returned a warning (for example "MYMEMORY WARNING:
    YOU USED ALL AVAILABLE FREE TRANSLATIONS FOR TODAY…") as the translated
    text, often with HTTP status 200. Code that only checks the HTTP status
    could write that warning into every cell of the new column. You'd need to
    check the status field inside the JSON response.
  - Match scores. The response includes a match score and a list of alternative
    matches. You could use the score to flag low-confidence results, though that
    adds design and testing work.

  Some of this behaviour may have changed, so if you're seriously considering
  MyMemory, make a few test calls first. Try a short ambiguous word, a negated
  sentence and an es→zh pair, and see what comes back.

❯ How to set up LibreTranslate and get test translation working for English,
  Spanish, and Mandarin

  Ran 1 shell command (ctrl+o to expand)

● I checked this machine: Docker isn't installed, and it looks like a shared
  UCSD DSMLP server where you probably can't install it. Python 3.11 is
  available, and LibreTranslate installs fine with pip, so Option A below works
  here. Option B is for teammates with Docker on their own laptops. I haven't
  installed or run anything.

  Option A: pip in a virtual environment (works on this server)

  # 1. Create an isolated environment outside the repo so it never gets
  committed
  python3 -m venv ~/libretranslate-venv
  source ~/libretranslate-venv/bin/activate
  pip install --upgrade pip
  pip install libretranslate

  # 2. Start the server with only the languages you need (smaller, faster first
  run)
  libretranslate --host 127.0.0.1 --port 5000 --load-only en,es,zh

  - The first start downloads the translation models, roughly 100 MB per
    language pair, into ~/.local/share/argos-translate/. Wait until you see a
    line like Running on http://127.0.0.1:5000.
  - Port 5000 may already be taken on a shared server. If so, use another port,
    such as --port 5055, in every command below.
  - Leave it running in its own terminal, or run it in tmux.
  - Next time, you only need to repeat the source ... line and the
    libretranslate ... command.

  Option B: Docker (for laptops that have it)

  docker run -it --rm -p 5000:5000 libretranslate/libretranslate --load-only
  en,es,zh

  Add -v lt-models:/home/libretranslate/.local to keep the models between runs
  so they aren't downloaded again.

  Check it's working

  Open a second terminal and run:

  1. List the language codes it actually loaded. Check the Chinese code here:
  depending on the version it's zh or zh-Hans, and some versions also load
  zt/zh-Hant for Traditional.
  curl -s http://127.0.0.1:5000/languages | python3 -m json.tool

  2. Translate in all six directions. Swap zh for whatever code step 1 showed.
  for pair in "en es" "en zh" "es en" "es zh" "zh en" "zh es"; do
    set -- $pair
    case $1 in en) text="The library opens at nine.";; es) text="La biblioteca
  abre a las nueve.";; zh) text="图书馆九点开门。";; esac
    printf '%s -> %s: ' "$1" "$2"
    curl -s -X POST http://127.0.0.1:5000/translate \
      -H 'Content-Type: application/json' \
      -d "{\"q\": \"$text\", \"source\": \"$1\", \"target\": \"$2\", \"format\":
  \"text\"}"
    echo
  done
  Each line should print something like {"translatedText":"La biblioteca abre a
  las nueve."}.

  3. Try the features you're weighing in your design decisions.
  # Auto-detect the source language
  curl -s -X POST http://127.0.0.1:5000/translate -H 'Content-Type:
  application/json' \
    -d '{"q": "¿Dónde está la estación?", "source": "auto", "target": "en"}'

  # Batch: send several cells in one request; the results come back in the same
  order
  curl -s -X POST http://127.0.0.1:5000/translate -H 'Content-Type:
  application/json' \
    -d '{"q": ["Hello", "", "12345", "Good morning"], "source": "en", "target":
  "es"}'

  # Error cases: bad language code, and server down (stop the server first)
  curl -s -i -X POST http://127.0.0.1:5000/translate -H 'Content-Type:
  application/json' \
    -d '{"q": "Hi", "source": "en", "target": "xx"}'

  Things these tests will tell you (useful for design.md and the journal)

  - Spanish ↔ Chinese: there's no direct model, so LibreTranslate translates
    through English. Expect lower quality for es ↔ zh than for pairs involving
    English. That's worth noting as a known limitation.
  - Edge cases: the batch call shows how blank strings and numbers come back.
  - Errors: the bad-language call shows the HTTP status code and JSON error your
    code would need to handle. Stopping the server gives you the "connection
    refused" case.
  - Speed: time a batch of about 20 cells with time curl ..., to judge whether
    one request per cell is fast enough.
  - API keys: none are needed by default. If you start the server with
    --api-keys, it requires them, which lets you test a credentials path if you
    decide to support one.

  If you'd like, I can run the setup and these checks here and report what comes
  back.