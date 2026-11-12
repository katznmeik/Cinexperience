# Full-codebase review: handoff (paused 2026-11-12)

A full review of the whole codebase, followed by approved refactoring, was
started on 2026-11-12 and **paused by the owner in the middle of Phase 1**
because of its token cost. This file says what is done, what is left and how to
continue cheaply. It is a working document: delete `docs/review/` once the
review is finished.

**No source file was changed.** This branch differs from the reviewed commit
only by `docs/review/`.

## Where things stand

| Step | State |
|---|---|
| Phase 0: orient, SDK setup, baseline | done |
| Phase 1: review, 7 of 10 areas | done, **unverified** |
| Phase 1: review, 3 areas (UI and accessibility, both test areas) | **not done** |
| Phase 1: independent verification of every finding | **not done** (0 of 29 findings, 0 of 6 spec conflicts) |
| Phase 1: completeness critic (uncovered areas, scope boundaries) | **not done** |
| Phase 1: consolidated list shown to the owner | **not done** |
| Phase 2: fixes and refactoring | not started; needs the owner's approval per item |

Files:

- `phase1-findings-unverified.md`: everything the seven finished reviewers
  reported, in full (finding, scenario, fix, the reviewer's evidence, spec
  conflicts, refactoring proposals, questions, coverage).
- `phase1-workflow.js`: the workflow script that was used, with the exact scope
  text of all ten areas, the finding format and the verifier's instructions.

## Baseline (commit `c19988b`, spec v1.5.0, rev. 40)

- `./gradlew testDebugUnitTest recordRoborazziDebug assembleDebug lintDebug`:
  BUILD SUCCESSFUL (10 min 50 s on a 4-CPU cloud machine).
- Tests: 2,216 run, 0 failures, 0 errors, 3 skipped. The skipped ones are
  `RealProgramPageTest`, `RealMembershipCardTest` and `RealTicketTest`, which
  need a local file.
- Seed generator tests: 40 run, OK.
- Lint: 0 errors, **19 warnings; README lists 16**. The 14 others match the
  README (portrait lock 2, targetSdk 1, `ExifInterface` 2, `UseKtx` 9). The
  dependency-update hints grew from 2 to 5 as new versions were published:
  Android Gradle plugin 9.4.1 → 9.5.1, `androidx.test:core` 1.6.1 → 1.7.0,
  kotlinx-serialization-json 1.11.0 → 1.13.0, kotlinx-coroutines 1.10.2 →
  1.11.0, Roborazzi 1.76.0 → 1.78.0. Not caused by a code change.
- 358 screenshots and 40 transition frames recorded; debug APK built.

## Results so far (all unverified)

Seven reviewers reported 29 findings, 6 spec conflicts and 16 refactoring
proposals. **None is critical.** The severities are the reviewers' own; nobody
has tried to refute them yet.

🟠 Important, as rated by the reviewer (7):

| Id | Finding |
|---|---|
| `data-backup-1` | Screens and dialogs opened before a backup import act on the imported rows by id once the lock lifts |
| `lifecycle-1` | Visit form: a photo chosen or taken while the app's process was killed is silently dropped after the restore |
| `lifecycle-2` | A reminder tap can open a second form of the same visit above a stale one; saving the lower form later overwrites the newer data |
| `untrusted-program-1` | The network scan skips code the build still compiles (folders named `build` or hidden, relocated modules, included builds) |
| `untrusted-program-2` | The network scan misses plain platform APIs that open connections without any matched word |
| `architecture-1` | The plain-text rule exists in five places with three behaviours; the label checks miss format characters outside the BMP |
| `architecture-2` | The production composition root and its start-up duties are executed by no test |

🟡 Nice-to-have (22): `data-room-1..3`, `data-backup-2..5`, `model-1..3`,
`untrusted-program-3`, `untrusted-ticket-privacy-1..4`, `lifecycle-3`,
`architecture-3..8`. Known duplicate: `lifecycle-3` and `architecture-3` are
the same finding (the visit form's default date and the export file name read
`LocalDate.now()` instead of `AppContainer.clock`).

Spec conflicts, report only (6): `data-room-S1` (§9: screen width and distance
follow a replaced seed layout only while unchanged), `model-S1` (§4.3: a block
start is not handed on when its whole block is painted away), `model-S2`
(§11.3: "Rate your visit" is also planned for a visit saved after its showing
started), `untrusted-program-S1` (§11.1 rules 2 and 3: a redirect within
www.yorck.de keeps the server's path and query), `architecture-S1` (§11.2 and
§11.3: the showing match also uses the film title, in two different ways),
`architecture-S2` (§4.4: "today" is the device's date, other rules use
Berlin's).

Areas a reviewer checked and found sound are listed under "Coverage" of each
area in the findings file (e.g. schema and migrations 1 → 2 → 3, no REPLACE
anywhere, the ticket rules, every Seeder rule, path confinement of the file
stores).

## What is left in Phase 1

1. **Three areas not reviewed.** Their scope texts are the `DIMS` entries
   `ui-a11y`, `tests-data` and `tests-features` in `phase1-workflow.js`.
2. **Verification.** Every finding and spec conflict still needs an independent
   attempt to refute it: open the named files, trace the scenario, check for a
   guard earlier in the path, check whether CLAUDE.md, README or the spec
   documents it as intended. Verdicts: confirmed / partly / refuted / intended /
   uncertain. The verifier's full instructions are `verifyPrompt` in the script.
3. **Completeness critic.** Boundaries no reviewer owned: `CinexApp` wiring
   between features, `feature:cinemas` manage (deleting cinemas and halls with
   their files, visits, tickets and seed snapshot), `feature:rankings`,
   `scripts/` and `.github/workflows` (pinned SHAs, release guards,
   `verify-release-apk.sh`, the setup script's checksums).
4. **Consolidate** into one ranked list (baseline, findings by severity,
   refactoring proposals, spec conflicts, questions), show it to the owner and
   **stop for approval**. Phase 2 starts only with the owner's choice of items.

## How to continue

Read this file, then `phase1-findings-unverified.md`. A new session starts from
a fresh clone: run `scripts/setup-android-sdk.sh`, and rebuild only if needed
(the baseline above holds while `git diff c19988b -- . ':!docs/review'` is
empty; the UI area needs the screenshots, so it needs
`./gradlew recordRoborazziDebug` first).

The cheapest sensible order:

1. **Verify the seven important findings first**, in the main session, without
   subagents: each names its files and lines, so it is a handful of reads per
   finding. `lifecycle-1`, `lifecycle-2` and `data-backup-1` touch the owner's
   data and deserve a failing test as proof (Phase 2 rule: failing test first).
2. **Accept or drop the nice-to-have findings on their written evidence**, and
   verify one only when the owner picks it for Phase 2.
3. **Show the owner the list** and ask whether the three open areas and the
   critic are still wanted. If yes, run them one area per session (see below).
4. Phase 2 as the review command describes: a new branch, one approved item per
   commit, tests pinning behaviour first, the full pre-push command after each
   item, no push or release unless asked.

To re-run the original design instead, pass `phase1-workflow.js` to the
workflow tool after setting its `S` constant. The finished areas will run again:
the run's cache was local to the paused session.

## What the first run cost, and why

- One reviewer used about 450,000 tokens (5 reviewers: 2.25 million, 362 tool
  calls). The run stopped at the account's usage limit after five reviewers; two
  more finished after the limit reset, two were stopped half-way.
- Projected for the whole design (10 reviewers, about 15 verifier runs, critic
  and its verifiers): roughly 9 to 10 million tokens.
- Where it goes: every agent, verifiers included, starts with all of CLAUDE.md
  and reads the spec sections again; a reviewer then reads whole modules, and
  each further tool call carries everything read so far. The cost is the
  repeated reading, not the writing.
- The machine has 4 CPUs, so only two agents run at a time: the whole design
  takes about three hours.

## Ways to spend less without losing much

Ordered by saving. The first four together should bring a full review to about
a third of the first design.

1. **One area per session.** The review command takes a focus argument (e.g.
   `core:data`, `reminders`). Running areas on different days keeps each run
   inside one usage window and lets the owner stop after the areas that matter.
2. **Review what changed, not everything.** Every milestone was reviewed before
   its merge (spec §11.1 rule 11, §11.2 rule 6, §13). A full review is worth it
   at a major version; in between, review `git diff <last reviewed tag>..HEAD`
   plus the code at its boundaries.
3. **Verify by severity.** Give only critical and important findings an
   independent verifier; let the main session check them, since they come with
   file and line. Nice-to-have findings are verified when picked for fixing.
   This drops about 40 % of the agents; so far it would mean 7 checks instead
   of 35.
4. **Match effort to risk** (as spec §13.1 does for building): the highest
   effort for data integrity, untrusted input and concurrency; a lower effort
   level or a smaller model tier for convention checks, test-gap lists and
   accessibility coverage, which are mostly searching and listing.
5. **A shorter CLAUDE.md.** It is about 7,200 words and is loaded into every
   agent of every session, not only reviews. Keep the module map, commands and
   the short conventions there, and move the long sections (Reminders, Ticket
   PDF, Program / network, Backup, Seed data, Seat numbering) into a
   `CLAUDE.md` inside the module they describe (`core/ticket/`, `core/program/`,
   `core/data/`, `app/…/reminders/`): those are loaded only when an agent works
   in that folder. This is the one change that saves on every future session.
6. **Give reviewers a reading budget.** Hand each one the file list with line
   counts and tell it to search first and read fully only the files on the
   risky paths. The tests are 45,000 lines, more than the app's 36,000: the test
   reviewers should start from the critical paths (migrations, backup
   round-trip, ticket import, reminders, seat numbering) and read the tests for
   those, not every test class.
7. **Fewer screenshots.** Images are expensive to read. The UI reviewer needs
   the `*_font_200` shots, the seat map and editor, and screens changed since
   the last review, not a broad sample of 358.
8. **Skip the critic as an agent.** The reviewers' coverage statements already
   say what nobody read; the main session can compare them with the source tree
   and review a gap only where the owner cares.
9. **Shorter reports.** At most five findings per area, evidence in two lines,
   refactoring proposals only when asked. About a third of the 120 KB findings
   file is proposals, questions and coverage.
10. **Save results as they arrive.** Write each area's findings into the
    repository (or the session's scratch folder) when it finishes, so a usage
    limit or a restart costs one area, not the run.
