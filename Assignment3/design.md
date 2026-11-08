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
