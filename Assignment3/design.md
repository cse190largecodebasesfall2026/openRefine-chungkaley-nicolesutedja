# Design Document

## Overview

We added a **Translate Column** feature to OpenRefine. The user picks an existing text column, chooses a target language (and optionally a source language), names the new column, and OpenRefine calls a translation API once per cell and writes the results into a new column immediately to the right of the source. The source column is preserved, row order is preserved, and the operation is recorded in history so it can be undone.

## Relevant Code Structure

*(To be filled in after implementation)*

## Diagrams

*(To be filled in after implementation)*

## Key Design Decisions

### Decision 1: Translation service

**Problem:** The assignment requires $0 cost and that a classmate can run the project without friction. We evaluated seven options: LibreTranslate (self-hosted), MyMemory, DeepL API Free, Google Cloud Translation, Azure Translator, a local LLM via Ollama, and the unofficial Google Translate endpoint.

**Options considered:**

- **LibreTranslate (self-hosted)**
  - Pros: Free, no account or key required, runs locally, supports en/es/zh and auto-detect, open source REST API, deterministic output makes testing straightforward
  - Cons: Requires installing a Python package and downloading language models (~1–2 GB), translation quality is below commercial APIs

- **MyMemory (free hosted API)**
  - Pros: Nothing to install, no key needed, works immediately
  - Cons: 5,000 character daily quota (anonymously) which a few test runs can exhaust, quality is inconsistent because results come from a crowd-sourced translation memory — the same input can return different results week to week, and short cells are especially prone to wrong fuzzy matches

- **DeepL API Free**
  - Pros: Excellent quality, 500,000 characters/month free
  - Cons: Requires an account and a credit card for verification, which is a real barrier for someone else setting up the project

- **Google Cloud Translation / Azure Translator**
  - Pros: High quality, wide language support
  - Cons: Both require accounts with payment details, and Google uses a credit-based free tier where going over costs money

- **Local LLM via Ollama**
  - Pros: Free, offline, private, no key
  - Cons: Multi-GB model download, slow on CPU, non-deterministic output (may add extra text around the translation), hard to write reliable tests

- **Unofficial Google Translate endpoint**
  - Pros: Free, good quality, no key
  - Cons: Not a public API, likely violates Google's terms of service, can be blocked without warning

**Tradeoffs:** The core tension is between quality and setup effort. The highest-quality options (DeepL, Google, Azure) all require accounts and credentials, which contradicts the "easy for someone else to set up" requirement. The options with no setup (MyMemory, unofficial Google) have reliability or legal problems. LibreTranslate and Ollama are the two that are genuinely free and self-contained; LibreTranslate wins because its output is deterministic and its API returns clean HTTP errors, which makes testing straightforward.

**Decision:** Kaley and Nicole chose LibreTranslate. The agent provided a detailed comparison of all seven options with pros and cons. We decided based on the requirement that a classmate must be able to run it without an account or credit card, and that translation results must be predictable enough to test.

---

### Decision 2: Architecture — new operation vs. reusing fetch-URLs vs. new extension

**Problem:** We needed to decide where to put the translation logic and how to structure it.

**Options considered:**

- **Reuse `ColumnAdditionByFetchingURLsOperation` with a preset URL**
  - Pros: No new operation class needed, less code to write
  - Cons: The user has to construct the API call as a GREL expression, which is error-prone; the operation appears in history as "Add column by fetching URLs" rather than "Translate column"; source/target language are not first-class parameters

- **New extension under `extensions/`**
  - Pros: Cleanest separation, appropriate for large independent features
  - Cons: Significantly more boilerplate (Butterfly module, separate `controller.js`, separate `pom.xml`); the feature is small enough that it does not need its own module

- **New operation in `main/` (chosen)**
  - Pros: Follows the same pattern as the other column operations, appears in history with its own name and parameters, lives alongside the existing column operations at the right level of abstraction
  - Cons: More code than reusing fetch-URLs

**Tradeoffs:** Reusing fetch-URLs saves code but loses clarity in history and forces the user to know the API format. A new extension is more correct architecturally for a truly independent feature, but the overhead is not justified here. A new operation in `main/` is the natural fit.

**Decision:** Kaley and Nicole chose a new operation in `main/`. The agent explained how the existing operations and extensions are structured, and we decided this feature belongs alongside the other column operations, not in a separate module.

---

### Decision 3: Where to make the translation call — server-side vs. client-side

**Problem:** The translation call could be made from the Java backend or directly from the browser's JavaScript.

**Options considered:**

- **Client-side (JavaScript calls LibreTranslate directly)**
  - Pros: No new Java code needed, the browser collects translated values and posts them to the existing `add-column` command
  - Cons: Browsers block cross-origin requests by default (CORS); any API key would be visible in the browser's network tab; the operation saved in history would just be "add column with these hardcoded values" and cannot be replayed on a different dataset

- **Server-side via a `LongRunningProcess` (chosen)**
  - Pros: The operation is stored in history with its parameters (not its results) so it can be replayed, shows a progress bar, user can cancel mid-run, consistent with how fetch-URLs works
  - Cons: More code to write than the other options

- **Server-side synchronously via `createHistoryEntry()`**
  - Pros: Simpler than a `LongRunningProcess`, no background job needed
  - Cons: Blocks the UI for the entire translation run, no progress feedback, no way to cancel

**Tradeoffs:** Client-side removes the need for new Java code but breaks history replay and has CORS and credential exposure problems. Synchronous server-side is simpler but gives the user no feedback during a long run and no way to cancel. Async server-side with a `LongRunningProcess` is the most work but is the right fit for an operation that may take seconds to minutes depending on dataset size.

**Decision:** Kaley and Nicole chose server-side via a `LongRunningProcess`, consistent with fetch-URLs. The agent identified the client-side option and the sync vs. async distinction; we chose async because translation on a large dataset will be slow and users should be able to see progress and cancel.

---

### Decision 4: One API call per cell vs. batching

**Problem:** LibreTranslate accepts either a single string or an array of strings per request. We could call it once per cell or collect all cells into one batched request.

**Options considered:**

- **One call per cell (chosen)**
  - Pros: Straightforward to implement, each cell's success or failure is independent so the on-error policy applies cleanly per cell, progress updates naturally as each cell completes
  - Cons: More network round-trips, slower for large datasets

- **Batched requests**
  - Pros: Fewer network round-trips, significantly faster for large datasets
  - Cons: If the batch request fails it is harder to tell which cells failed, mapping results back to rows requires careful index alignment, error handling becomes more complex

**Tradeoffs:** Batching is a meaningful performance improvement but adds complexity to error handling and result mapping. For the scope of this assignment, per-cell calls are easier to reason about and test.

**Decision:** Kaley and Nicole chose one call per cell. This keeps error handling simple and consistent with how fetch-URLs processes rows. Batching would be a reasonable future optimization and is documented here as a known tradeoff.

---

### Decision 5: Credentials and server URL

**Problem:** The LibreTranslate URL and optional API key must not be committed to the repository. Operation parameters are saved in project history JSON and can be exported, so storing the URL or key there would leak them.

**Options considered:**

- **Dialog input fields**
  - Pros: No setup required
  - Cons: User must re-enter the URL every session

- **Environment variables**
  - Pros: One-time setup, standard practice
  - Cons: Requires terminal knowledge, behavior varies by OS

- **Separate config file (e.g. `translate.properties`)**
  - Pros: Self-documenting if a `.example` file is included
  - Cons: Introduces a new file type and convention that users have to learn

- **`refine.ini` / `refine-dev.ini` (chosen)**
  - Pros: OpenRefine already has this pattern, `refine-dev.ini` is already gitignored, variables are passed to the JVM as `-D` flags and read via `Configurations.get()` — the same mechanism used by all other OpenRefine settings
  - Cons: Users unfamiliar with OpenRefine's config system may not know to look there

**Tradeoffs:** Dialog fields require no setup but are annoying to use repeatedly. Environment variables are powerful but less approachable. A separate config file is clean but introduces a new convention. `refine-dev.ini` has the least friction because the pattern is already established and the file is already gitignored.

**Decision:** Kaley and Nicole chose `refine.ini` / `refine-dev.ini`. The agent found that this pattern already exists in the codebase (used for Google Data OAuth credentials) and that `Configurations.get()` already supports defaults. We decided this was the most consistent approach. The backend defaults to `http://localhost:5000`, so users running LibreTranslate locally on the standard port do not need to touch any config file.

---

### Decision 6: Source language — required vs. auto-detect default

**Problem:** LibreTranslate supports `"source": "auto"` for automatic language detection. We needed to decide whether to require the user to specify a source language or allow auto-detection.

**Options considered:**

- **Always require source language**
  - Pros: Reliable, no ambiguity
  - Cons: Adds friction; most users do not know the source language and do not expect to have to specify it

- **Always auto-detect**
  - Pros: Simplest dialog, no extra input needed
  - Cons: Less reliable for short strings or mixed-language columns, removes user control

- **Default to auto-detect, with optional override (chosen)**
  - Pros: Matches user expectations (consistent with Google Translate), still gives users who know the source language the option for more reliable results
  - Cons: Slightly more complex dialog than always auto-detect

**Tradeoffs:** Auto-detect can fail on short or ambiguous strings. Requiring a source language is more reliable but less usable. The optional override gives the best of both.

**Decision:** Kaley and Nicole chose default auto-detect with an optional override. We concluded that defaulting to auto-detect is the right call because it matches the behavior users are familiar with from other translation tools.

---

### Decision 7: On-error behavior

**Problem:** Some cells may fail to translate (API down, empty cell, LibreTranslate returns an error). We needed to decide what to write in the new column for those cells.

**Options considered:**

- **Set to blank**
  - Pros: Clean, does not pollute the data
  - Cons: Silent failure — the user may not notice cells were skipped

- **Store error**
  - Pros: Useful for debugging, makes failures visible
  - Cons: Pollutes the data with error messages

- **Copy original value**
  - Pros: Useful when the dataset has mixed languages and an untranslated value is better than a blank
  - Cons: May be confusing if the user does not realize translation failed

- **All three as user-selectable options (chosen)**
  - Pros: Consistent with `ColumnAdditionOperation` which also offers all three, each option is useful in different contexts
  - Cons: Slightly more UI to build

**Decision:** Kaley and Nicole chose to offer all three options. The agent pointed out that "copy original" does not exist in the fetch-URLs dialog but does exist in the simpler add-column dialog, and that it is especially useful for translation. We decided it was worth adding.

---

### Decision 8: Menu placement

**Problem:** The column header dropdown has several groups. We needed to place the new item in the right one.

**Options considered:**

- **In the "Add column" group (chosen)**
  - Pros: The feature creates a new column, which is exactly what that group contains; consistent with existing items
  - Cons: None

- **A new separate group**
  - Pros: Makes translation visually distinct
  - Cons: A single item does not warrant its own group; would only make sense if multiple translation-related operations existed

**Decision:** Kaley and Nicole chose the "Add column" group because the feature creates a new column, which is exactly what that group contains.

---

### Decision 9: Language names in the UI

**Problem:** The language display names in the dropdowns ("English", "Spanish", "Mandarin Chinese") could be hardcoded in HTML or stored as i18n keys.

**Options considered:**

- **Hardcoded in HTML**
  - Pros: Simpler, no extra keys to maintain
  - Cons: Inconsistent with the rest of OpenRefine's UI, where every visible string goes through `$.i18n()`

- **i18n keys (chosen)**
  - Pros: Consistent with OpenRefine conventions, language names can be localized for non-English UI users
  - Cons: Requires adding keys to every translation file

**Decision:** Kaley and Nicole chose i18n keys to be consistent with how every other string in the UI is handled.

---

### Decision 10: Menu entry position, label, and code location

**Problem:** Decision 8 put the feature in the "Add column" group of the **Edit column** menu. We still needed to decide where in that group it goes, what it is called, and where its code lives.

**Options considered:**

- **Right after "Add column by fetching URLs…" (chosen)**
  - Pros: Sits next to its closest relative, another operation that calls an outside service to build a new column
  - Cons: Pushes "Add columns from reconciled values…" down one row

- **Right after "Add column based on this column…"**
  - Pros: Near the top of the group, easy to find
  - Cons: Separates the two related "based on this column" and "fetching URLs" items

- **Last in the group, after "Add columns from reconciled values…"**
  - Pros: Existing items keep their positions
  - Cons: Reconciliation sits between it and fetch-URLs, which is a looser fit

For the label we considered "Translate column…" and "Add column by translating…". For the code we considered a `doTranslateColumn` function inside `menu-edit-column.js` (like every sibling) or a separate JS file (which would also need registering in `controller.js`).

**Tradeoffs:** All three positions work. The question is which neighbors make the feature easiest to understand. A separate file keeps the feature together but breaks the pattern every other Edit-column operation follows.

**Decision:** Kaley and Nicole chose to place it right after "Add column by fetching URLs…", labeled **"Translate column…"** (the "…" because it opens a dialog), with the code inside `menu-edit-column.js`. The agent showed how the existing menu is registered (`MenuSystem.appendTo` in `menu-edit-column.js`) and recommended these options. We agreed that grouping it with fetch-URLs and following the sibling pattern was the clearest choice.

---

### Decision 11: Choosing the column and the languages

**Problem:** The dialog needs a source column, an optional source language and a target language. We needed to decide how each is picked and how to handle choosing the same language for source and target, which the backend rejects.

**Options considered:**

- **Column:** the column whose header menu was opened, shown in the dialog title (chosen), or a dropdown of all columns pre-filled with that column
- **Target language:**
  - A dropdown with no default ("Choose a language…"), with OK disabled until one is picked (chosen). Pros: the user cannot translate into the wrong language by accident. Cons: one extra click
  - A dropdown defaulting to the first language that is not the source. Pros: fastest. Cons: easy to submit the wrong language without noticing
  - Radio buttons. Pros: all choices visible. Cons: crowded if more languages are added
- **Same source and target:**
  - An inline warning, with OK disabled until they differ (chosen)
  - Let it submit and show the backend's error
  - Remove the chosen source language from the target list. Tidy, but the list changes as you choose, which can be confusing

**Tradeoffs:** A pre-filled target is faster but makes mistakes easy. Requiring an explicit choice costs one click. Catching the same-language case in the dialog gives immediate feedback instead of a backend error after submitting.

**Decision:** Kaley and Nicole chose the column from the header menu, a target dropdown with no default, and an inline same-language warning with OK disabled. The source dropdown starts with "Detect automatically" (Decision 6). The agent pointed out that every Edit-column operation uses the clicked column, and that the backend's `validate()` already rejects identical languages, so the dialog should prevent that case before it is sent.

---

### Decision 12: Default name of the new column

**Problem:** The new column needs a name. Its placement is already fixed by the backend (immediately right of the source), but we needed a sensible default name.

**Options considered:**

- **`<column>_<code>`, e.g. `review_es` (chosen)**
  - Pros: Short, easy to use in GREL (`cells.review_es.value`), the same in every UI language, matches the backend's example
  - Cons: Language codes are less friendly than full names

- **`<column> (Spanish)`**
  - Pros: Easiest to read in the grid
  - Cons: Spaces and parentheses are awkward in expressions; the name depends on the UI language, so the same steps give different names for different users

- **Empty; the user must type a name**
  - Pros: Matches "Add column based on this column…" and fetch-URLs
  - Cons: More friction for every run

**Tradeoffs:** Readable names are nicer in the grid but harder to use in expressions and not reproducible across UI languages. An empty field is consistent with siblings but slower.

**Decision:** Kaley and Nicole chose `<column>_<code>`. The name fills in when a target language is picked and follows the target if it changes, until the user edits the name; from then on it is left alone. The agent recommended this option because it keeps the column easy to reference in later GREL expressions.

---

### Decision 13: When the column name already exists

**Problem:** The new column must never silently overwrite an existing one. The backend avoids overwriting by renaming silently (`getUnduplicatedColumnName` turns `review_es` into `review_es2`), so the user would get a name they did not type and no warning.

**Options considered:**

- **Inline warning under the name field, OK disabled until the name is unique (chosen)**
  - Pros: Clearest; the column always gets exactly the name the user typed
  - Cons: The user must rename before continuing, which happens every time the same column is translated into the same language twice

- **Inline warning, OK still allowed ("will be named review_es2")**
  - Pros: No extra step
  - Cons: The frontend has to copy the backend's suffix rule exactly or the message is wrong; the user gets a name they did not type

- **Pop-up `alert()` on OK**
  - Pros: Matches the existing "You must enter a column name" check
  - Cons: The user only finds out after clicking, and pop-ups are easy to dismiss without reading

**Tradeoffs:** Blocking is the safest and simplest, at the cost of one rename. Allowing the backend's rename saves a step but makes the frontend depend on backend internals.

**Decision:** Kaley and Nicole chose the inline warning with OK disabled. The check compares against the project's existing column names exactly, the same way the backend does. The backend's silent rename remains only as a safety net for a rare race (a column added from another tab while the dialog is open). The agent found that neither sibling dialog checks for collisions; both leave it to the backend.

---

### Decision 14: What the user sees while translation runs

**Problem:** Translation makes one call per cell (Decision 4), so large columns take a while. The backend runs it as a `LongRunningProcess` that reports its progress and can be cancelled (Decision 3).

**Options considered:**

- **Close the dialog on OK and use the standard progress panel (chosen)**
  - Pros: Identical to fetch-URLs; shows "N% complete" and a Cancel link; the user can keep looking at the data; no new UI
  - Cons: Only a percentage, no "cell 120 of 500"

- **Keep the dialog open with its own progress bar**
  - Pros: Keeps the user's attention on the task
  - Cons: Needs its own polling of `get-processes`; no other OpenRefine dialog works this way; blocks the grid

- **Standard panel plus a completion summary ("Translated 48 of 50 cells, 2 failed")**
  - Pros: Tells the user exactly what happened
  - Cons: Needs a backend change to count failed cells

**Tradeoffs:** The standard panel is consistent and free. A summary would be more informative but needs extra backend work.

**Decision:** Kaley and Nicole chose the standard progress panel, with no backend change for this decision. The agent showed how `Refine.postCoreProcess` hands a `pending` response to `ui.processPanel`, which polls every 500 ms and shows progress and Cancel.

---

### Decision 15: How failures appear in the UI

**Problem:** Three kinds of failure behave differently:
1. **The request is rejected** (e.g. an unknown language). OpenRefine shows this as a browser `alert()` by default.
2. **The whole job fails** (e.g. the column was deleted). The standard pop-up "The last operation encountered some errors" appears.
3. **LibreTranslate is down.** Each cell fails on its own and the on-error policy applies, so the job still "succeeds". With "set to blank", the user gets an empty column and no message.

**Options considered:**

- **Fail fast when the service is unreachable (small backend change), plus errors shown inside the dialog (chosen)**
  - Pros: A dead service is never silent; the message says what to fix; no half-done column is left behind
  - Cons: Changes backend behavior and needs a backend test

- **Frontend only: errors inside the dialog; dead-service failures visible only through the on-error choice**
  - Pros: No backend change
  - Cons: With "set to blank", a dead service still produces a silently empty column

- **Backend records the failure count in the history description ("12 cells failed")**
  - Pros: Always recorded, including partial failures
  - Cons: Backend change, and easy to miss because there is no pop-up

**Tradeoffs:** Only the fail-fast option fixes the silent empty column, at the cost of a small backend change.

**Decision:** Kaley and Nicole chose fail-fast and approved the backend change. We also kept **"set to blank"** as the default on-error choice, matching the other dialogs.
- **Rejected requests:** shown in red inside the dialog, which stays open so the user can fix and retry.
- **Unreachable service:** if any cell cannot reach LibreTranslate (connection refused, unknown host, timeout), the job stops with "Translation service unreachable at http://host:port. Is LibreTranslate running?", shown in the standard error pop-up, and no column is added. This uses a new `TranslationServiceUnreachableException`.
- **HTTP errors on individual cells** while the server is up still follow the on-error policy.

While implementing this, the agent found two details, which we kept:
- **Cancel:** cancelling interrupts the job's thread, so an interrupt is not treated as "unreachable". Otherwise clicking Cancel would show a false error.
- **URL in the message:** the message shows only the scheme, host and port, so a password or key embedded in the URL is never displayed.

---

### Decision 16: Language of the backend error message

**Problem:** All dialog text goes through `$.i18n()` (Decision 9). The "service unreachable" message from Decision 15, however, is created in Java and shown as received, so it stays in English even when the UI is in another language.

**Options considered:**

- **Keep the English backend message (chosen)**
  - Pros: Matches every other OpenRefine operation (e.g. "Another column already named …"); no shared code changes
  - Cons: One message, plus a few unlikely validation messages, cannot be translated

- **Backend sends an error code; the shared error pop-up translates it**
  - Pros: Fully meets the i18n rule; other operations could reuse it
  - Cons: Changes `process-panel.js`, which is shared by every operation, and the backend error format

- **Check the service from the dialog before starting**
  - Pros: The common "forgot to start LibreTranslate" case gets a translated message inside the dialog
  - Cons: A new backend command and an extra request per run; a mid-run failure would still be in English

**Tradeoffs:** Full i18n would mean changing shared code for one message. Keeping English is consistent with the rest of OpenRefine.

**Decision:** Kaley and Nicole chose to keep the English backend message. **Known limitation:** backend error messages (the "service unreachable" message and the `validate()` messages) are in English and are not in the i18n files. The `validate()` messages are rarely seen, because the dialog prevents those cases before submitting. The frontend wording around them ("The last operation encountered some errors", "The translation could not be started: …") is translatable.

---

### Decision 17: Which frontend tests to write

**Problem:** We needed to choose which Cypress end-to-end tests cover the feature.

**Options considered:** a core set covering the required behaviors, plus optional tests for Cancel and Undo.

**Decision:** Kaley and Nicole chose all of the following, in one spec, `main/tests/cypress/cypress/e2e/project/grid/column/edit-column/translate_column.cy.js`. It sits next to the existing add-column spec and loads small inline tables with `cy.loadAndVisitProject`.
1. The menu entry opens the dialog, with the correct title and labels, and OK starts disabled
2. Choosing languages: the default name follows the target, the same-language warning appears, a name the user edited is kept, and the correct parameters are sent to `translate-column`
3. The new column appears directly right of the original with rows aligned, and empty cells stay empty
4. The name-collision warning appears with OK disabled; a unique name works and the existing column is untouched
5. An unreachable service shows the error pop-up and adds no column
6. A rejected request shows its error inside the dialog, which stays open
7. Cancel changes nothing and sends no request
8. Undo removes the translated column

---

### Decision 18: Keeping the tests away from the real translation service

**Problem:** Tests must never call a real translation service, but three of them (3, 5, 8) need the backend to actually run a translation.

**Options considered:**

- **A fake LibreTranslate server inside Cypress, plus request stubs where needed (chosen)**
  - Pros: Tests the real path from the dialog through the backend's HTTP call to the grid; predictable; no backend or production code changes
  - Cons: OpenRefine must be started with `LIBRETRANSLATE_URL` pointing at the fake server; adds code to the Cypress plugin file

- **Stub every backend request with `cy.intercept`**
  - Pros: No setup at all
  - Cons: The backend never runs, so no real column is added; the "rows aligned" test would only test the stub

- **A backend "test mode" with a built-in fake translator**
  - Pros: Simplest test code
  - Cons: Test-only code ships in the product; another backend change

**Tradeoffs:** Pure stubs are easy but prove little. A test mode puts test code in production. The fake server needs one environment variable, but it tests the real thing.

**Decision:** Kaley and Nicole chose the fake server. `main/tests/cypress/cypress/plugins/fake-libretranslate.js` runs on port 5055 with fixed translations (`hello` → `hola`, `thank you` → `gracias`, …), and Cypress tasks switch it "down" for test 5. Test 6 uses a `cy.intercept` stub that returns an error. If OpenRefine is not pointed at the fake server, the tests fail with a message saying so instead of calling a real service. The tests run with:

```
LIBRETRANSLATE_URL=http://localhost:5055 CYPRESS_SPECS=cypress/e2e/project/grid/column/edit-column/translate_column.cy.js ./refine e2e_tests
```

---

### Decision 19: Fixing `refine.ini` settings not reaching the backend

**Problem:** The agent found that on Linux and macOS, `LIBRETRANSLATE_URL` and `LIBRETRANSLATE_API_KEY` set in `refine.ini` / `refine-dev.ini` (Decision 5) never reached Java. The `./refine` script loads the ini as bash arrays (`export NAME=(value)`), and bash never passes arrays to child processes. The URL always fell back to `localhost:5000`, and an API key in the ini was ignored. Windows (`refine.bat`) was not affected, because it uses `set`.

**Options considered:**

- **Fix it now (chosen)**
  - Pros: Decision 5 actually works; testers can configure the server as documented
  - Cons: Changes the launch script

- **Leave it and document running `LIBRETRANSLATE_URL=… ./refine` instead**
  - Pros: No script change
  - Cons: The documented configuration does not work

**Decision:** Kaley and Nicole chose to fix it now.
- **The fix:** after reading the ini, `./refine` turns only those two variables back into plain environment variables, leaving how every other setting is loaded unchanged.
- **Why environment variables:** they were chosen over `-D` Java options, so the API key does not appear in `ps` output.
- **Testing:** the agent's first version did not work, because assigning to an existing bash array only sets its first element, and the agent's own check caught it. The fix was verified for an ini with values, an ini without them, and a value set only in the shell.

---

### Decision 20: Which translation files get the new UI strings

**Problem:** Decision 9 put every visible string in i18n keys, but its wording suggested adding the keys to every translation file (`main/webapp/modules/core/langs/` has 47).

**Options considered:**

- **Only `translation-en.json` (chosen)**
  - Pros: OpenRefine's convention; other languages are translated by the community on Weblate, and any missing key falls back to English automatically
  - Cons: Non-English UIs show the new strings in English until they are translated on Weblate

- **Also add English text to the other 46 files**
  - Pros: Every file lists the keys
  - Cons: Puts English text in other languages' files and works against the Weblate workflow

**Decision:** Kaley and Nicole chose `translation-en.json` only. It gains 12 new `core-views/translate-*` keys (menu label, title, field labels, "Detect automatically", "Choose a language…", the three language names, the two warnings and the in-dialog request error). Existing keys are reused where the wording is identical (`new-col-name`, `on-error`, `set-blank`, `store-err`, `copy-val`, OK, Cancel).
