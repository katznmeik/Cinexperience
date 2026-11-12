export const meta = {
  name: 'cinexperience-full-review',
  description: 'Phase 1 full-codebase review of Cinexperience: scoped reviewers, adversarial verification, completeness critic (read-only)',
  phases: [
    { title: 'Review', detail: 'ten scoped reviewers read the code and report verified findings' },
    { title: 'Verify', detail: 'independent skeptics try to refute each finding' },
    { title: 'Critic', detail: 'completeness critic reviews what the others did not cover' },
    { title: 'Verify critic', detail: 'skeptics check the critic findings' },
  ],
}

const S = '<scratchpad of the new session>' // set this: the folder holding baseline.log

const COMMON = `
You are one reviewer in a full-codebase review of Cinexperience (repo: /home/user/Cinexperience), a personal Android app (Kotlin, Jetpack Compose, Material 3, Room, manual DI, multi-module). CLAUDE.md (already in your context) describes the modules and the binding conventions; docs/SPEC.md is the source of truth for behaviour: read the sections that concern your scope before judging anything.

GOAL: code that is correct, safe with the user's data, and easy to change, without changing intended behaviour. A short list of real, verified problems is worth far more than a long list of speculative ones. Finding nothing significant in an area is a valid result. Report at most 8 findings; keep only the ones you would defend in front of the owner.

HARD RULES (this is the review phase, no code changes):
- Read-only. Do not create, modify or delete any file inside the repository. No git commands that change anything. Do not edit docs/SPEC.md under any circumstances.
- Do not run Gradle (a baseline build is using it). Verify by reading: trace the code path, check the callers, read the tests and the spec section.
- Read the code in your scope fully (the real files, not excerpts), and the code at its module boundaries: most bugs here sit between repository <-> Room <-> fakes, ViewModel <-> screen, and feature <-> CinexApp wiring.
- Things CLAUDE.md, README.md or the spec already document as intended, accepted or a known limitation are NOT findings (e.g. no "Replace anyway" for imports, USE_EXACT_ALARM and Google Play, wheelchair numbering of old layouts, R8 off, SV shown as is).
- If code and spec disagree, do not decide which is right: report it under specConflicts.

VERIFY before reporting: every finding needs a concrete failure scenario (input or state -> wrong result) that you traced through the actual code, or, for maintainability findings, the concrete change it makes harder. Name the exact functions and lines you traced in "evidence". Anything you could not confirm goes under "questions", not "findings".

FINDING FORMAT (fields of each finding):
- severity: critical (data loss, privacy leak, crash on a normal path, security rule broken) / important (wrong behaviour in a realistic case, or a real maintainability hazard) / nice (small, cosmetic, cheap clean-up)
- location: module/path/File.kt:line
- problem: one sentence
- scenario: the concrete failure scenario or cost
- reference: CLAUDE.md rule or spec section, if any
- fix: the proposed fix; fixKind: local or refactoring; effort: S / M / L
- evidence: what you read or traced to confirm it

Also return: refactoringProposals (larger restructurings: what they gain, what they touch, their risk), specConflicts, questions, and coverage (which files you read fully, which you only skimmed, which you did not get to: be honest, it is used to find gaps).
`

const DIMS = [
  {
    key: 'data-room',
    title: 'Data integrity: Room, repositories, seed, file stores, fakes',
    scope: `SCOPE: core/data (db/, repository/, seed/, settings/, files/, DataContainer and the top-level package), the exported schemas in core/data/schemas, and the fakes in core/testing that re-implement the Room rules (compare them rule by rule with the Room repositories; RepositoryContract in core/data tests runs both).
LOOK FOR: schema and migrations 1->2->3 (exported schema vs entities vs MIGRATION SQL, no destructive fallback, foreign keys and cascades); visits always updated and never REPLACEd (a replace would cascade-delete the ticket); ticket rules (one per visit, booking number unique across visits, SaveTicketResult.BookingNumberInUse, saveVisit vs saveVisitWithTicket, removeTicket, restoreQrModules guarded, removeExpiredQrModules); seat ids never reused (Hall.nextSeatId mark, repository rejecting reused ids), SeatOverrideRepository (one favourite per hall); transactions that should be atomic but are not; flows that miss a column (isFavourite carried by every cinema flow); the Seeder rules of CLAUDE.md "Seed data" (insert missing, fill empty, never overwrite or delete, adoption, deleted items stay deleted, appliedLayout replacement rules, screen width/distance following, MAX_HALLS); file stores (ReferenceImageStore, VisitPhotoStore: paths confined to their folder, delete only after successful save, the orphan sweep ages 7 days / 1 h, never touching the program cache); limits (TextLimits, DataLimits, ScreenSizeRules, SeatLayout limits) enforced by every writer; ReminderSettingsStore and MembershipCardStore (a card that cannot be read whole counts as none; commit results). The backup engine (core/data backup/) is reviewed by another reviewer: only touch it where a repository rule and the backup disagree.`,
  },
  {
    key: 'data-backup',
    title: 'Data integrity: backup export/import engine',
    scope: `SCOPE: core/data backup/ (BackupFormat, DTOs, BackupMigrations, BackupValidator, BackupReader, BackupRestorer, the writer/exporter, safety copies, BackupRepository), core/data json/ (LayoutJson), the fixtures in core/data/src/test/resources/backup/ (never to be changed), FakeBackupRepository in core/testing, and how feature/backup (Settings ViewModel and screens) and app CinexApp (BackupImportLock) use the engine.
LOOK FOR: validate-everything-before-touching-data; free-space checks; the safety copy (sequence ordering, reuse if newest already holds current data, a failed import deletes the copy it wrote, pruning to newest 2 only after success, restore through the same prepare -> preview -> commit path); all rows replaced in one transaction; imported files added under fresh names and removed if the transaction fails; nothing after commit (progress callbacks, clean-up, pruning, seeding, Errors) can turn the import into a failure or delete its safety copy (onCommitted); strict parsing and BackupMigrations 1->2->3 (defaults, QR modules never in a backup, restore writes tickets without them); does every table and column of schema version 3 reach the backup (compare entities with DTOs field by field); does the restore keep seed state (seed_snapshot), ids, and auto-increment sequences sane; do validator limits equal the writers' limits and the forms' limits (a value the app can write must never fail an export; a value the validator accepts must never crash a reader, e.g. model init blocks); zip safety (entry names, path traversal, sizes, duplicate entries, entries before manifest); isImporting state on every exit path (exceptions, cancellation); export while Back is blocked; what happens to the membership card, reminder settings and program cache during an import (must be untouched) and whether reminders are rescheduled after an import (spec 11.3 rule 4).`,
  },
  {
    key: 'model',
    title: 'Domain model: seat layouts, numbering, scores, time rules',
    scope: `SCOPE: core/model (all 23 files: SeatLayout, LayoutRow.withCells, SeatLabels, LayoutEditSession, hallScores/cinemaScores/seatStates, viewingAngle, ScreenOffsetRules, ScreenSizeRules, ShowingTime, UpcomingVisits, ReminderSchedule, Ticket/BookingNumber/TicketSeat/QrModules, MembershipCard/MembershipCardText, TextLimits, VisitPrefillArgs) and the layout editor's use of it in feature/seatmap editor/ (LayoutDraft, PaintStroke, strokeOrder, ScreenRules, undo/redo), plus core/data json/LayoutJson where it encodes these types.
LOOK FOR: seat numbering against spec 4.3 and the CLAUDE.md "Seat numbering" and "Rows without a name" rules (directions, blocks, blockStartNumbers through cell-type changes: split, merge, removal of the first seat, a seat added in front counting back to 1 or to just after the previous block, fixed labels moving with their seat and never lost by the editor, restore of id and fixed label within one editing session); seat ids never reused, SeatIdsExhaustedException at the limit; init-block checks that a caller can violate with stored data (crash on load); rating maths against spec section 5 (means of non-empty values, overall = mean of category scores with data, cinema overall including hall overall scores, thresholds 4.0 and 2.5, never SQL math); floating point edge cases; Berlin time handling in ShowingTime/ReminderSchedule/UpcomingVisits (DST gaps and overlaps, midnight, untimed visits, the 30 minute "Started" window, QR expiry 7 days, lastPossibleAt, at most 50 reminders, passed ones never planned); ScreenOffsetRules.snap/maxMagnitude and LayoutDraft.start snapping rules; equality/hashCode pitfalls on types used as map keys or in StateFlow; undo/redo consistency in the editor.`,
  },
  {
    key: 'untrusted-program',
    title: 'Untrusted input: program download, parser, network confinement',
    scope: `SCOPE: core/program (all files incl. net/: HttpsProgramTransport, ProgramDownloader; ProgramPageParser, ProgramRules, ProgramText, ProgramTime, ProgramLinks, ProgramCache, DefaultProgramRepository, the Yorck-name table), its tests NetworkConfinementTest and BuildLogicScanTest (do the scans really enforce what CLAUDE.md claims? name a concrete construct that would slip through, without writing exploit code), build-logic (forbidFileDependencies, CheckDependencyGroupsTask), app/src/main/res/xml/network_security_config.xml and every AndroidManifest.xml, feature/program where it opens links (ACTION_VIEW only to https://www.yorck.de/) and joins Yorck texts with other text (isolate()).
LOOK FOR, against spec 11.1 "Network & security" rules 1-10 (binding): consent checked before every request (DownloadPermission.ALLOWED asked again before each request, also on redirects and retries); exactly one request shape (no query, body, cookies; headers; at most 3 redirects only to https://www.yorck.de/...; timeouts; 90 s watchdog; 10 MB counted while streaming also with gzip; only HTTP 200); commands running to the end when the caller is cancelled, off stored before the download is stopped, one download at a time, races between refresh/delete/switch-off that could leave a program on disk after delete or write a cache after switching off; parser bounds (JSON values 500,000, depth 64, the fallback tree decode), every field checked, texts cleaned, offsets +01:00/+02:00/none only, DST handling in ProgramTime; links only from fixed patterns with checked id/slug; cache format versioning, 4 MB limit, atomic replace, unreadable = none and deleted; failures (including Errors) becoming ProgramStatus.Failed while the last good program stays; nothing from the page logged. This is a defensive review: describe a weakness and its fix; do not write exploit code or payloads.`,
  },
  {
    key: 'untrusted-ticket-privacy',
    title: 'Untrusted input and privacy: ticket PDF, membership card, intents, notifications',
    scope: `SCOPE: core/ticket (pdf/, text/, qr/, card/, TicketPdfReader, TicketPdfRules, TicketPdfLimits, MembershipCardImageReader, QrLocator, QrGridReader, the Bitmap decoders), feature/tickets importing/ (TicketIntents, TicketUris, ContentTicketFileSource, TicketImportViewModel, TicketMatching) and membership/ (CardImageReader, EditMembershipCardViewModel), core/data settings (MembershipCardStore), the zip handling of core/data backup/BackupReader (entry names, sizes, traversal: untrusted file), app (MainActivity intent handling, reminders/ReminderIntents, ReminderIntentToken, ReminderNotifier, receivers), every AndroidManifest.xml (exported components, intent filters, permissions), app/src/main/res/xml/backup_rules.xml and data_extraction_rules.xml, .gitignore.
LOOK FOR, against spec 11.2 and 11.4 security rules (binding) and the CLAUDE.md "Ticket PDF", "Membership card", "Tickets" and "Reminders" sections: limits that count work actually done (each documented limit in TicketPdfLimits enforced on every path, including error paths and repeated references); read() never throwing for any content (uncaught exception types such as StackOverflowError, OutOfMemoryError, NumberFormatException, IndexOutOfBounds, DataFormatException on a path outside the catch); the booker's name, prices and category never becoming a field; no value-bearing toString() on Ticket, TicketPdfContent, BookingNumber, MembershipCard, drafts or UI state (data classes print their fields: check each); nothing with visit data, booking numbers, card values, URIs or file contents in Log calls, exception messages shown or logged, or analytics; only content: URIs of other apps read (not the app's own providers, not file:), size capped while reading, never persisted or cached to disk; image decoding bounds first, subsampling, memory use; QR validation (finder, timing, alignment, format copies, Reed-Solomon syndromes) with no content decoding; MembershipCardStore phone-local and excluded from Auto Backup and device transfer, as are reminder_alarms.xml and reminder_intents.xml and the photo/reference/program files; exported receivers checking their action; PendingIntents immutable and explicit; the per-install intent token (compared safely, never handed out if not stored); notification public versions without film, cinema, seat, and never the booking number; saved instance state holding sensitive values (what is kept in SavedStateHandle and is that intended by the spec); FLAG_SECURE is not required by the spec, so do not report its absence. This is a defensive review: describe a weakness and its fix; do not write exploit code or payloads.`,
  },
  {
    key: 'lifecycle',
    title: 'Lifecycle and concurrency: reminders, ViewModels, repositories',
    scope: `SCOPE: app (CinexApplication/onAppStart, MainActivity, di/AppContainer, reminders/: ReminderCoordinator, AlarmReminderScheduler, ReminderNotifier, ReminderAlarmReceiver, ReminderRestartReceiver, ReminderRetry, ReminderIntents; IncomingTicketViewModel, IncomingReminderViewModel; ui/CinexApp incl. BackupImportLock), every ViewModel in feature/* (scopes, StateFlow sharing, SavedStateHandle), the repositories' coroutine use in core/data and core/program (dispatchers, NonCancellable sections, mutexes), core/ui flow/Flows.kt (minuteTicks, asResult), DataContainer start-up work (removeExpiredQrModules, sweepOrphanFiles, seeding).
LOOK FOR: coroutine scopes and cancellation (work that must finish but runs in viewModelScope, e.g. file copies, saves, backup export; GlobalScope-like leaks; CancellationException swallowed by catch (Throwable) or runCatching so cancellation no longer propagates; collectors restarted on every recomposition); process death and saved state (visit form with photos and TicketDraft, membership card form, editor draft documented as not surviving, import in progress); intents handled exactly once (savedInstanceState, FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, onNewIntent, configuration change, the backup-import lock delaying an import); double taps causing double saves or double navigation (CloseOnce); the backup-import lock covering every writer including reminders rescheduling and ticket restoreQrModules; reminder debounce, one-run-at-a-time, unlimited retry with pause reset, rescheduleNow from receivers within the 50 s / 8 s limits using goAsync correctly (finish always called, also on exceptions and timeouts), ReminderRetry pauses 15/30/60/120/240 min and the stored failure count, exact-alarm fallback when canScheduleExactAlarms() is false, alarms whose time has passed never cancelled, markPosted/isPosted/isStillDue races between the alarm receiver and a concurrent reschedule, SharedPreferences commit vs apply where the result matters; minuteTicks and clock use (AppContainer.clock, never the system clock directly in screens); repository failures becoming messages, never crashes (uncaught exceptions in launch blocks, flows without catch feeding stateIn); main-thread disk or database I/O.`,
  },
  {
    key: 'architecture',
    title: 'Architecture and maintainability across all modules',
    scope: `SCOPE: all modules, their build.gradle.kts files, settings.gradle.kts, build-logic/convention, gradle/libs.versions.toml, app/.../ui/CinexApp.kt and CinexTransitions.kt, di/AppContainer.kt.
LOOK FOR: module boundaries (features never depend on each other; core modules not depending on features; core:model free of Android; what each module's build file really declares vs the CLAUDE.md module map; api vs implementation leaks); duplicated logic that is likely to drift (the same rule, formatter, limit, regex, date text, seat text or validation implemented in two places: name both places and what differs today, if anything); classes, files or composables that do too much (list the largest main-source files with line counts and say which are a real problem and which are fine); unclear or misleading names; dead code (unused functions, parameters, resources, routes; verify with a search over all modules including tests); conventions from CLAUDE.md: colours and text styles only from core:ui (search feature modules for Color( literals, hard-coded sp/fonts), transitions only in CinexTransitions.kt, build config only in convention plugins (config duplicated in module build files), the clock only via AppContainer.clock (search for Instant.now, LocalDate.now, System.currentTimeMillis, Clock.system outside the container and tests), "Row ..." texts built by hand instead of SeatLabel.displayText/shortText/description, scores from core:model never SQL math, texts from Yorck joined without isolate(), no invented sample data in the app, no AI model names or email addresses in the repo; CLAUDE.md or README statements that no longer match the code (documentation drift: report under findings with severity nice or important, since agents rely on it). For maintainability findings the "scenario" is the concrete change that is harder or riskier today.`,
  },
  {
    key: 'ui-a11y',
    title: 'UI and accessibility: Compose state, a11y coverage, screenshots',
    scope: `SCOPE: core/ui, core/seatmapui, the screens and composables of feature/* and app/.../ui, the *AccessibilityTest classes and core/testing assertAccessible/contrastRatio, ThemeContrastTest and EditorContrastTest. The baseline build records screenshots into app/build/outputs/roborazzi and feature/*/build/outputs/roborazzi (and core/*/build/outputs/roborazzi), and transition frames into app/build/outputs/transitions: list those folders and LOOK AT a broad sample of the PNGs with the Read tool (at least: each main screen once, each *_font_200 variant, the seat map and editor shots, the ticket and membership card screens, the transition frames at 25/50/75 %). If a folder is still empty, wait a few minutes (check ${S}/baseline.log for "exit=") and try again; say in coverage which PNGs you looked at.
LOOK FOR: Compose state problems (state not remembered or remembered with wrong keys, rememberSaveable missing where rotation/process death loses typed input, derivedStateOf misuse, unstable lambdas or parameters causing heavy recomposition of the seat map canvas, LaunchedEffect keys, collecting flows without lifecycle awareness, side effects in composition, snackbar/message handling shown twice or lost, KeepScreenBrightAndOn restore); assertAccessible coverage: build the list of main screens (every route in every feature plus CinexApp destinations) and check each has an AccessibilityTest at font scale 1, 1.5 and 2: name the screens or states (dialogs, bottom sheets, empty and error states) that have none; labels (icon-only buttons with content descriptions, decorative icons null, a control's description replacing its visible texts, nothing announced twice), 48 dp targets (minimumInteractiveComponentSize or heightIn(min = 48.dp), padding does not count), headings (SectionHeader/ScreenTitle), large-text layouts (fixed heights that text must fit in, rows that do not wrap, two-column layouts not switching with isLargeText(), clipped or ellipsised text visible in the font_200 PNGs), canvas text sized in dp not sp, contrast (text >= 4.5:1, graphics and outline-only controls >= 3:1, outline/divider decorative only, fieldOutline for control borders), colour never the only cue on the seat map (x, star, lightness, legend, descriptions), the QR panel the only light surface; visual defects you can see in the PNGs (overlap, clipping, misalignment, wrong theme colours); transition frames against spec section 7 (no overlap, no crossfade on push/pop, bottom bar).`,
  },
  {
    key: 'tests-data',
    title: 'Tests: model, data, migrations, backup, seed',
    scope: `SCOPE: the tests of core/model, core/data (MigrationTest, RepositoryContract and its two runners, backup tests and fixtures, Seeder tests, YorckSeedTest, file store tests, LayoutJson tests), core/testing self-tests (AccessibilityChecksTest and the fakes' own tests), scripts/seed tests, and the test configuration in build-logic (configureUnitTests: UTC, skipped tests listed). If ${S}/baseline.log contains "exit=", read its list of skipped tests.
LOOK FOR: gaps on critical paths: for each of these, say whether a test would fail if the code were wrong, and name the missing case concretely: every migration step with real data in every table and the final schema compared to the exported one; migration from 1 straight to 3; backup round-trip of every table and column (export -> import -> equal rows, files included), the v1 and v2 fixtures through BackupMigrations, a failed transaction leaving data and files untouched, failures after commit, safety copy pruning and reuse, free-space refusal; visits never REPLACEd (ticket survives every visit update path); ticket uniqueness rules; seat numbering (both directions, blocks, starts through splits/merges/removals, fixed labels, unnamed rows) and the seed fixture test; seat id reuse rejection; Seeder rules (deleted stays deleted, adoption, appliedLayout replacement and its blockers, MAX_HALLS); orphan sweep ages; the fakes vs Room contract (rules implemented in Room but absent from RepositoryContract). Tests that would pass even if the code were wrong: assertions on the fake instead of the subject, tautological expected values computed by the same function under test, assertions inside a callback or collect that may never run, try/catch swallowing failures, tests with no assertion, assertTrue on a non-empty list where exact content matters, Robolectric tests that silently skip. Flakiness: system clock, time zone or locale dependence (LocalDate.now(), default Locale formatting, DST dates), ordering assumptions on sets, maps or file listings, real delays or sleeps, shared static state or files between tests, dependence on test execution order. A missing test is a finding only with the concrete wrong behaviour it would let through.`,
  },
  {
    key: 'tests-features',
    title: 'Tests: ticket, program, reminders, feature ViewModels and screens',
    scope: `SCOPE: the tests of core/ticket, core/program, app (reminders, intents, AppPermissionsTest, BackupRulesTest, TransitionFramesTest, navigation tests, FakeAppContainer), feature/* (ViewModel tests, screen tests, AccessibilityTests, screenshot tests), core/ui and core/seatmapui tests. If ${S}/baseline.log contains "exit=", read its list of skipped tests.
LOOK FOR: gaps on critical paths: for each, say whether a test would fail if the code were wrong, and name the missing case concretely: ticket import end to end (intent offered once, content URI rules, size cap, imported-before path with restoreQrModules, the path during a backup import, TicketMatching misses, save keeping a stored ticket through every edit, expired QR dropped); each TicketPdfLimits limit having a test that fails without the limit (CLAUDE.md says each has one: check); read() never throws (fuzz tests: are they deterministic and seeded?); the booker-name rules; QR validation negative cases; membership card reader negative cases (several codes, slanted, oversized) and store exclusion from Auto Backup; program: consent before every request, redirect rules, size cap with gzip, watchdog, parser bounds, cache replace, commands finishing under cancellation; reminders: schedule maths at DST changes, debounce and single-run, retry pauses and reset, restart receiver actions, passed alarms never cancelled, markPosted/isPosted, notification content public version and no booking number, tap intents with a wrong token; form ViewModels: process death restore, double save, failure messages. Tests that would pass even if the code were wrong: assertions on a fake rather than the subject, expected values computed by the code under test, assertions inside callbacks or collectors that may never run, runTest with unadvanced virtual time so the asserted coroutine never ran, try/catch swallowing failures, screenshot tests that record but never compare (recording is this project's design: do not report that, but report screens whose shots are identical or blank if you see it), tests with no assertion. Flakiness: system clock or default time zone/locale (the build pins UTC: tests that need Berlin time and only pass in UTC, or the reverse), real delays or sleeps, Dispatchers.Main without MainDispatcherRule, ordering of sets, maps or file listings, shared static state, dependence on test order. A missing test is a finding only with the concrete wrong behaviour it would let through.`,
  },
]

const FINDINGS_SCHEMA = {
  type: 'object',
  properties: {
    findings: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          severity: { type: 'string', enum: ['critical', 'important', 'nice'] },
          title: { type: 'string' },
          location: { type: 'string' },
          problem: { type: 'string' },
          scenario: { type: 'string' },
          reference: { type: 'string' },
          fix: { type: 'string' },
          fixKind: { type: 'string', enum: ['local', 'refactoring'] },
          effort: { type: 'string', enum: ['S', 'M', 'L'] },
          evidence: { type: 'string' },
        },
        required: ['severity', 'title', 'location', 'problem', 'scenario', 'fix', 'fixKind', 'effort', 'evidence'],
      },
    },
    refactoringProposals: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          title: { type: 'string' },
          gain: { type: 'string' },
          touches: { type: 'string' },
          risk: { type: 'string' },
          effort: { type: 'string', enum: ['S', 'M', 'L'] },
        },
        required: ['title', 'gain', 'touches', 'risk', 'effort'],
      },
    },
    specConflicts: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          location: { type: 'string' },
          specSection: { type: 'string' },
          description: { type: 'string' },
        },
        required: ['location', 'specSection', 'description'],
      },
    },
    questions: { type: 'array', items: { type: 'string' } },
    coverage: { type: 'string' },
  },
  required: ['findings', 'refactoringProposals', 'specConflicts', 'questions', 'coverage'],
}

const VERDICT_SCHEMA = {
  type: 'object',
  properties: {
    verdicts: {
      type: 'array',
      items: {
        type: 'object',
        properties: {
          id: { type: 'string' },
          verdict: { type: 'string', enum: ['confirmed', 'partly', 'refuted', 'intended', 'uncertain'] },
          severity: { type: 'string', enum: ['critical', 'important', 'nice', 'spec'] },
          reasoning: { type: 'string' },
          corrected: { type: 'string' },
          checked: { type: 'string' },
          needsExperiment: { type: 'string' },
        },
        required: ['id', 'verdict', 'severity', 'reasoning', 'checked'],
      },
    },
  },
  required: ['verdicts'],
}

const RANK = { critical: 0, important: 1, spec: 2, nice: 3 }

function chunk(list, n) {
  const out = []
  for (let i = 0; i < list.length; i += n) out.push(list.slice(i, i + n))
  return out
}

function toItems(key, r) {
  const items = r.findings.map((f, i) => ({ id: `${key}-${i + 1}`, kind: 'finding', ...f }))
  r.specConflicts.forEach((c, i) => items.push({
    id: `${key}-S${i + 1}`, kind: 'specConflict', severity: 'spec', title: 'Spec conflict',
    location: c.location, problem: c.description, reference: c.specSection,
  }))
  return items.sort((a, b) => RANK[a.severity] - RANK[b.severity])
}

function verifyPrompt(title, items) {
  return `
You are an independent verifier in a full-codebase review of Cinexperience (repo: /home/user/Cinexperience; CLAUDE.md is in your context, docs/SPEC.md is the source of truth). Another reviewer (area: ${title}) reported the items below. Your job is to try to REFUTE each one, independently. The owner will act on what survives, so a wrong "confirmed" costs real work and a wrong "refuted" hides a real bug: decide from the code, not from how convincing the report sounds.

For each item:
1. Open the files it names and read the surrounding code yourself (the reviewer's line numbers and summaries may be wrong). Trace the claimed scenario step by step through the real code, including callers, guards earlier in the path, and tests that already cover it.
2. Check whether CLAUDE.md, README.md or docs/SPEC.md document the behaviour as intended, accepted or a known limitation (verdict "intended").
3. Decide: "confirmed" (you traced the scenario and it fails as described), "partly" (a real problem, but the description, scope or severity is off: put the corrected statement in "corrected"), "refuted" (say exactly which guard, caller or rule prevents it), "intended", or "uncertain" (you could neither trace nor refute it: say what experiment would settle it in "needsExperiment").
4. Set the severity you would give it: critical (data loss, privacy leak, crash on a normal path, broken security rule), important (wrong behaviour in a realistic case, or a real maintainability hazard), nice (small or cosmetic). For items of kind specConflict use severity "spec" and check both the code and the spec text: quote the spec sentence in your reasoning.
5. In "checked" list the files, functions and lines you read, and any command you ran.

RULES: Read-only: do not create, modify or delete any file in the repository, no git changes, never edit docs/SPEC.md. You may run an EXISTING focused test to settle an item (./gradlew :module:testDebugUnitTest --tests '...' --console=plain, or :core:model:test for the JVM module), but only if "pgrep -f recordRoborazziDebug" prints nothing (a baseline build may be running; do not start Gradle while it is) and only one such run at a time; do not add test files. Do not write exploit code or payloads; this is a defensive review. Default to "uncertain", not "confirmed", when you could not trace the path.

Return one verdict per item id.

ITEMS:
${JSON.stringify(items, null, 1)}
`
}

async function verifyAll(key, title, items, phaseName) {
  if (!items.length) return []
  const groups = chunk(items, 3)
  const results = await parallel(groups.map((g, gi) => () =>
    agent(verifyPrompt(title, g), { label: `verify:${key}:${gi + 1}/${groups.length}`, phase: phaseName, schema: VERDICT_SCHEMA })))
  const byId = {}
  results.filter(Boolean).forEach(r => r.verdicts.forEach(v => { byId[v.id] = v }))
  const missing = items.filter(it => !byId[it.id]).map(it => it.id)
  if (missing.length) log(`${key}: no verdict for ${missing.join(', ')}`)
  return items.map(it => ({ ...it, verification: byId[it.id] || null }))
}

const results = await pipeline(
  DIMS,
  d => agent(`${COMMON}\nYOUR AREA: ${d.title}\n${d.scope}`, { label: `review:${d.key}`, phase: 'Review', schema: FINDINGS_SCHEMA }),
  async (r, d) => {
    if (!r) { log(`${d.key}: reviewer returned nothing`); return { key: d.key, title: d.title, failed: true } }
    const items = toItems(d.key, r)
    log(`${d.key}: ${r.findings.length} findings, ${r.specConflicts.length} spec conflicts, ${r.refactoringProposals.length} proposals`)
    const verified = await verifyAll(d.key, d.title, items, 'Verify')
    return { key: d.key, title: d.title, items: verified, refactoringProposals: r.refactoringProposals, questions: r.questions, coverage: r.coverage }
  },
)

const done = results.map((r, i) => r || { key: DIMS[i].key, title: DIMS[i].title, failed: true })

phase('Critic')
const digest = done.map(d => d.failed
  ? `## ${d.title}\n(reviewer failed: this whole area is uncovered)`
  : `## ${d.title}\nCoverage: ${d.coverage}\nItems: ${d.items.map(it => `[${it.id}] ${it.title} @ ${it.location} (${it.verification ? it.verification.verdict : 'unverified'})`).join('; ') || 'none'}\nQuestions: ${d.questions.join(' | ') || 'none'}`
).join('\n\n')

const critic = await agent(`${COMMON}
YOUR AREA: completeness critic. Ten reviewers have covered the areas below. Their coverage statements, item titles and open questions follow. First decide what is MISSING: files or packages nobody read fully (compare the coverage statements with the real source tree: list src/main of every module), risky boundaries between two reviewers' scopes that each assumed the other covered (e.g. app/ui/CinexApp wiring of routes, callbacks and saved state between features; feature/visits edit ViewModel <-> TicketRepository <-> file stores; feature/seatmap editor save <-> HallRepository seat id rules; feature/cinemas manage (delete cinema/hall and its files, visits, tickets, seed snapshot); feature/rankings; scripts/ and .github/workflows (pinned SHAs, release workflow guards, verify-release-apk.sh, setup script checksums)), and open questions you can settle by reading the code. Then review the three to five most risky uncovered areas yourself, in depth, and report findings in the same format (at most 8, same verification bar). Do not repeat items already listed. In "coverage" list what remains unreviewed after you.

WHAT THE REVIEWERS COVERED:
${digest}
`, { label: 'critic', phase: 'Critic', schema: FINDINGS_SCHEMA })

let criticOut = { failed: true }
if (critic) {
  const items = toItems('critic', critic)
  log(`critic: ${critic.findings.length} findings, ${critic.specConflicts.length} spec conflicts`)
  const verified = await verifyAll('critic', 'Completeness critic (uncovered areas and boundaries)', items, 'Verify critic')
  criticOut = { key: 'critic', title: 'Completeness critic', items: verified, refactoringProposals: critic.refactoringProposals, questions: critic.questions, coverage: critic.coverage }
}

return { dimensions: done, critic: criticOut }
