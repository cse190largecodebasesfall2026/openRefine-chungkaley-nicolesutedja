# Process Journal — Translate Column Feature

**Authors:** Kaley Chung & Nicole Sutedja

---

## Feature Exploration

We were given a fairly open-ended prompt: add a "Translate Column" feature to OpenRefine. The assignment left most of the design up to us — which translation service to use, how to structure the backend, what the UI should look like, and how to handle edge cases.

Before writing any code, we opened OpenRefine and clicked through the existing "Edit column" menus to understand what adding a column already looked like from a user perspective. We paid particular attention to "Add column by fetching URLs…" because it also makes an external network call per row — the closest existing analog to what we were building.

Our main feature decisions at the exploration stage:
- We needed a translation service that costs $0 and requires no signup. The agent listed seven options. We asked follow-up questions about why MyMemory's quality is inconsistent (crowd-sourced translation memory with fuzzy matching) and how to set up LibreTranslate. We ended up choosing **LibreTranslate** because it has no account requirement, runs locally, returns deterministic results (important for testing), and has a clean HTTP API.
- We decided the feature should live in `main/` as a **new operation** (not reusing fetch-URLs or a new extension), so it appears with its own name in undo history and follows the same patterns as the other column operations.

**AI's role here:** The agent provided the initial comparison table of translation services and explained each one's pros/cons in depth when we asked. We directed which questions to ask (e.g., "why is MyMemory inconsistent?", "how do we set up LibreTranslate?"). The final service choice was ours.

Relevant prompt snippet from chat-1:
> "Compare the translation services stating pros and cons for each option."

After reading the agent's breakdown, we chose LibreTranslate ourselves based on the $0, no-account, and testability requirements.

---

## Comprehension Process

Understanding the codebase was one of the hardest parts of this assignment. OpenRefine is a large, multi-module Maven project and its conventions for operations, commands, and history are not immediately obvious.

**Code navigation approach:**

We started by asking the agent to explain the codebase structure in relation to what we needed to build (chat-2). The agent's explanation of the end-to-end flow was the most useful single piece:

```
User clicks menu
  → JS dialog (menu-edit-column.js)
      → Refine.postCoreProcess("command-name", params)
          → HTTP POST /command/core/command-name
              → RefineServlet routes to Command class
                  → Command extracts params, creates Operation
                      → Operation.createProcess() → LongRunningProcess
                          → HistoryEntry(Operation, ColumnAdditionChange)
```

This gave us a mental model before we read any individual file.

We used **VS Code's "Go to Definition"** (Cmd+click) extensively to trace how `ColumnAdditionByFetchingURLsOperation` calls `ColumnAdditionChange`, how the command receives and parses request parameters, and how `controller.js` wires everything together at startup.

We also used **global text search** for key terms: `"add-column-by-fetching-urls"`, `"registerOperation"`, `"EngineDependentOperation"`, `"$.i18n"`. This helped locate the registration points in `controller.js`, the i18n key patterns, and the existing tests in `ColumnAdditionByFetchingURLsOperationTests.java` we used as a template.

**Key files we needed to understand:**

| File | Why it mattered |
|---|---|
| `ColumnAdditionByFetchingURLsOperation.java` | Template for our operation: LongRunningProcess, per-row loop, error handling |
| `ColumnAdditionChange.java` | The class that actually writes cells and handles undo — we reused it unchanged |
| `AddColumnByFetchingURLsCommand.java` | Template for our command: reads HTTP params, creates operation |
| `menu-edit-column.js` | Where all column menu items and dialog functions live |
| `controller.js` | Where commands and operations are registered at startup |

We were confused for a while about the distinction between a **Command** and an **Operation** — the Command is the HTTP endpoint (thin layer, parses the request), and the Operation is the actual logic saved to history. The agent's explanation of this separation helped, though we kept second-guessing it as we read code that confirmed it.

**AI's role here:** We asked the agent to explain the codebase's structure (chat-2), and separately asked it to walk us through the frontend before implementation (chat-2 again). This helped us understand both halves. We still read the files ourselves and used Go to Definition to verify what the agent described. When something didn't match, we asked follow-up questions.

---

## Implementation Process

We split implementation across sessions, with each person driving one or more sessions while the other watched and navigated.

| Step | Description | Driver | Navigator |
|---|---|---|---|
| Exploration & design decisions | Chat-1: translation service comparison, 9 design decisions walked through one by one | Kaley | Nicole |
| Codebase comprehension | Chat-2: end-to-end flow, frontend structure, menu layout | Nicole | Kaley |
| Design doc | Chat-3: writing `design.md` with all decisions, pros/cons bullets, attribution | Nicole | Kaley |
| Implementation planning | Chat-4: reading `design.md`, resolving 7 additional gaps (A–G), confirming the plan | Kaley | Nicole |
| Backend implementation | Chat-4 (continued): `TranslationConfig`, `LibreTranslateClient`, `ColumnTranslationOperation`, `TranslateColumnCommand`, tests | Kaley | Nicole |
| Re-add backend + design doc diagram | Chat-5: re-added backend files from chat-4; also created class diagram artifact | Nicole | Kaley |
| Frontend implementation | Chat-6: dialog, menu item, i18n, Cypress tests, `./refine` ini fix, small backend change | Kaley | Nicole |

**How we switched roles:** We switched when it was a natural stopping point between sessions — once the design doc was done, once the backend was done. Within a session, we'd take turns suggesting what to ask the agent next, but the person at the keyboard drove the session's direction.

**Key implementation steps:**

1. **Backend translation client** (`translation/` package): `LibreTranslateClient.java` sends a JSON POST per cell and parses `translatedText`. `TranslationConfig.java` reads the server URL first from a JVM property, then from an environment variable, then defaults to `http://localhost:5000`. Neither the URL nor the API key is ever stored in the operation's JSON (which would leak into exported history).

2. **Operation** (`ColumnTranslationOperation.java`): Extends `EngineDependentOperation`, runs as a `LongRunningProcess`. Per-run caching: identical text + language pair is translated once and reused. Non-text cells are copied unchanged; blank cells are skipped.

3. **Frontend dialog** (`translate-column-dialog.html` + `doTranslateColumn` in `menu-edit-column.js`): The default column name auto-fills as `<column>_<code>` when you pick a target language and stops updating once you edit it. An inline warning disables OK if the name is already taken. Another inline warning disables OK if source and target are the same.

4. **D6 backend change**: Added `TranslationServiceUnreachableException`, which causes the whole job to abort and report a clear error message rather than silently producing a blank column when LibreTranslate is down.

5. **`./refine` script fix**: The ini file uses bash arrays, which are never passed to child processes. We added a loop to re-export the two LibreTranslate variables as plain environment variables after the ini is loaded.

---

## Running and Testing

### Unit and integration tests (backend)

The agent wrote 32 TestNG tests against a fake LibreTranslate server (`MockWebServer`). These cover:
- Normal translation in all language directions
- Blank and non-text cells
- Per-run caching (identical values translated once)
- All three on-error modes
- Facets on and off
- Name collisions (auto-numbered `review_es2`)
- Undo
- Unreachable server (D6): job fails with no column added
- API key and URL never appearing in the serialized operation JSON

**Bug encountered in chat-4:** Two tests failed to compile because TestNG's `assertThrows` doesn't return the exception — `expectThrows` is the correct variant:

```java
// broken
TranslationException e = assertThrows(TranslationException.class, () -> ...);
// fixed
TranslationException e = expectThrows(TranslationException.class, () -> ...);
```

**Bug encountered in chat-4:** Facet tests failed at runtime because the GREL parser was never registered in the test setup. The fix was adding `@BeforeMethod` / `@AfterMethod` hooks to register and unregister it:

```java
@BeforeMethod
public void registerGRELParser() {
    MetaParser.registerLanguageParser("grel", "GREL", Parser.grelParser, "value");
}
```

### Cypress tests (frontend)

Eight tests in `translate_column.cy.js`:
1. Menu entry opens the dialog
2. Choosing languages fills the name and disables OK on same-language selection
3. New column appears right of the original with rows aligned
4. Name collision warning disables OK
5. Unreachable service shows the error pop-up with no column added
6. Rejected request shows error inside the dialog (dialog stays open)
7. Cancel closes the dialog with no changes
8. Undo removes the column

**Obstacle encountered in chat-6:** Cypress requires `Xvfb` (a virtual display) to run headless on Linux. It was not installed on the DSMLP server and sudo was not available, so the Cypress tests could not be run to completion on that machine. The tests were written, linted (ESLint + Prettier), and are ready to run, but we have not seen them pass in a real run yet.

**Bug encountered in chat-6:** The first draft of the `./refine` ini fix did not work because assigning to a bash array name only sets element 0 and leaves it unexported. The correct fix was to copy, unset, and re-export:

```bash
for var in LIBRETRANSLATE_URL LIBRETRANSLATE_API_KEY ; do
    if [ -n "${!var}" ] ; then
        value="${!var}"
        unset "$var"
        export "$var=$value"
    fi
done
```

### Manual testing

We manually tested the feature end-to-end in a browser. Steps performed:
- Started LibreTranslate: `pip install libretranslate && libretranslate --load-only en,es,zh`
- Started OpenRefine: `LIBRETRANSLATE_URL=http://localhost:5000 ./refine`
- Loaded `sample_reviews.csv`
- Translated `review` → Spanish; verified new column `review_es` appears to the right, rows aligned
- Translated with an empty cell; verified blank output (not an error)
- Tried to create a column named `review_es` again; verified the inline warning appears and OK is disabled
- Stopped LibreTranslate; tried a translation; verified the error pop-up appears and no column is added
- Tried translating en → en; verified the same-language warning disables OK
- Undid a translation; verified the column is removed

---

## Obstacles Encountered

**1. Confusion about the codebase structure**
OpenRefine's layered architecture (command → operation → process → change) was not obvious from reading code alone. We kept second-guessing whether a piece of logic belonged in the command or the operation. We resolved this by asking the agent to trace a full request end-to-end (chat-2), then verified against the actual files using Go to Definition. Even after that, we returned to the same confusion in chat-4 when we were confused about where facet logic should live.

**2. Lost files from a missed commit**
After the backend session in chat-4, we forgot to commit. So, Nicole added the backend files back in a follow-up commit ("Add initial backend"), which is why that commit appears after the main backend commit in the history.

**3. `assertThrows` vs `expectThrows` (TestNG)**
TestNG's `assertThrows` doesn't return the exception object, unlike JUnit's `assertThrows`. When we needed to inspect the exception message, the code didn't compile. The fix was switching to `expectThrows`, which does return it. This is worth noting: if we encounter a similar issue in a TestNG project in the future, `expectThrows` is the method for checking exception properties.

**4. GREL parser not registered in tests**
Two facet-related tests failed at runtime with a parse error because the test class didn't register the GREL language parser before running tests. We added `@BeforeMethod` / `@AfterMethod` hooks. Reference for the future: any test class that exercises engine/facet logic in OpenRefine needs to register the GREL parser manually.

**5. `refine.ini` variables not reaching the Java process**
`load_configs` in the `./refine` bash script reads ini values into bash arrays. Bash arrays are never passed to child processes as environment variables, so `LIBRETRANSLATE_URL` set in `refine.ini` was silently ignored by Java. The fix was re-exporting the variables as plain strings after the ini is loaded. This was a subtle issue that required reading the bash script carefully; the agent caught it during the frontend planning chat (chat-6).

**6. Cypress/Xvfb requirement on DSMLP**
The DSMLP server lacks a virtual display, so Cypress cannot run. The tests are written and linted, but were not executed against a live server. We verified end-to-end behavior through manual testing instead (see Manual testing section above).

---

## Resources Consulted

**Agentic tool:** Claude Code (Sonnet 4.6 / Opus 5.5), used through Claude's CLI in all six chat sessions.
- Used for codebase exploration and understanding — explaining the layered architecture, tracing the request flow, finding relevant files
- Used as a sounding board for design decisions — the agent presented options and tradeoffs; we made the final calls
- Used for implementation — writing all Java, JavaScript, and test code after we approved the plan
- **What we learned:** The agent was most valuable when we gave it a specific question rooted in the codebase ("how does the existing fetch-URLs operation handle facets?") versus a vague one. When we weren't sure whether the agent's explanation was accurate, we used Go to Definition to verify it against the actual source.

**OpenRefine source code and AGENTS.md:** The repo's own `AGENTS.md` file was a useful starting point for understanding build conventions and test commands.

**TestNG documentation:** Consulted to understand `expectThrows` after the `assertThrows` compilation failure.

---

## Reflection

**How we interacted with AI**

We used the agent as a collaborator for comprehension and implementation, but kept all design decisions with us. At the start of every session, we explicitly instructed the agent not to write or modify code until we approved a plan. This was effective: the agent consistently laid out options with tradeoffs and waited for our input.

The most useful things the agent did:
- Explained the end-to-end request flow in a way that gave us a mental model before we read the files
- Found the existing `refine-dev.ini` / `Configurations.get()` pattern for credentials — something we would have taken much longer to find ourselves
- Caught the bash array bug in the `./refine` script — a subtle shell scripting issue we would not have noticed during implementation

The most frustrating parts:
- When we weren't sure whether the agent's description of the codebase was accurate. For example, the agent told us that `Configurations.get()` reads JVM properties from ini files directly — this turned out to be incorrect (the ini variables become env vars, not system properties). We only caught this because the agent re-checked in chat-4. This underscored that we couldn't take agent explanations of code at face value without verifying.
- Context switching between sessions: each new chat required re-explaining the full project context. We got better at this over time by opening with a concise summary and pointing to `design.md`, but it was still overhead.

**Managing different contexts while pair programming**

Having two laptops and two chat sessions was harder to coordinate than expected. We had to be careful not to duplicate work or make conflicting changes. Our approach was to designate one person per session as the driver, with the other watching and directing — this kept the agent working off one coherent context. We also used git commits between sessions as a forcing function to synchronize state.

**What we'll take away**

- **Ground rules upfront matter.** Explicitly telling the agent not to implement anything without approval kept us in control of design decisions throughout.
- **Verify codebase claims.** Agent explanations of unfamiliar code should always be spot-checked with Go to Definition or a text search. The agent gets things right most of the time, but it can be confidently wrong.
- **Write the design doc before implementation.** Having `design.md` as a shared reference between the two of us and the agent prevented repeated re-litigating of decisions across sessions.
