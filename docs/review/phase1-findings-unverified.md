# Phase 1 review: raw reviewer findings (UNVERIFIED)

Generated from the review run on commit `c19988b` (2026-11-12). Every item below was reported by one reviewer
that traced the code by reading it. **None has been checked by a second, independent verifier yet**, and none
has been re-checked by the orchestrating session. Treat each as a lead, not as a fact. See `HANDOFF.md`.

Ids are stable: `<area>-<n>` for findings, `<area>-S<n>` for spec conflicts.

## data-room: Data integrity: Room, repositories, seed, file stores, fakes

### data-room-1 · 🟡 nice-to-have · Membership card: a failed save before the first read is later reported as the stored card

- **Location:** `core/data/src/main/kotlin/io/github/katznmeik/cinexperience/core/data/settings/MembershipCardStore.kt:82`
- **Problem:** write() does not load the stored card first, so a commit that fails while the store is still Unread leaves `current` Unread, and the next read() returns the editor's values, which Android has already applied to the SharedPreferences in-memory map.
- **Failure scenario / cost:** Process death on "Edit membership card"; the restored ViewModel fills the form from SavedStateHandle and never reads the store. The owner taps Save and commit() fails (storage full): save() returns false and the form says it failed. Back on the card screen (or via a ticket's "Show membership card"), `card` runs load() -> read() and shows the new card as stored. After the next process start the previous card (or none) is back. This contradicts the store's contract "a failed write returns false and changes nothing". Needs a failed commit, so it is rare; the effect is a wrong display, not data loss.
- **Rule or spec reference:** MembershipCardStore KDoc (lines 25-26); CLAUDE.md "Membership card" and the Reminders note that Android keeps a failed commit's value in memory
- **Proposed fix** (local, effort S): In write(), call load() under the mutex before editing (as SharedPreferencesReminderSettingsStore.update already does at line 64), so `current` is always Loaded before any commit and read() never runs after a failed one. Add a store test whose failing editor applies the change to memory and whose first call is save().
- **Reviewer's evidence:** Read MembershipCardStore.kt fully: write() (82-98) sets `current` only on success; `card` (67-70) and load() (101-103) call read() only while Unread. EditMembershipCardViewModel.kt:97-105 restores from saved state without touching the store, and onSave (168-183) calls store.save(). CinexApp passes the store only to the ticket and membership screens (CinexApp.kt:253, 260), so nothing else reads it at start. MembershipCardStoreTest.aFailedWriteChangesNothing (99-116) reads `card` before the failing write and its FailingPreferences.commit() does not apply to memory, so this path is untested. The in-memory behaviour of a failed commit is Android framework behaviour that CLAUDE.md itself relies on for ReminderIntentToken; I did not run it.

### data-room-2 · 🟡 nice-to-have · Reference screenshot import leaves its .tmp file behind when encoding or writing fails

- **Location:** `core/data/src/main/kotlin/io/github/katznmeik/cinexperience/core/data/files/ReferenceImageStore.kt:98`
- **Problem:** importImage deletes the temp file only when the rename fails; an IOException while compressing or writing (e.g. storage full) leaves `hall-<id>-<uuid>.jpg.tmp` in files/reference/.
- **Failure scenario / cost:** Storage is nearly full; the owner picks a screenshot in the layout editor. bitmap.compress fails, the editor shows "import failed", and a partial file stays in files/reference/ until a later app start's sweep finds it more than 1 h old. FileVisitPhotoStore handles the same step with try/finally (VisitPhotoStore.kt:112-119), so the two stores behave differently for the same failure.
- **Rule or spec reference:** CLAUDE.md "App-private files"
- **Proposed fix** (local, effort S): Wrap the write and rename in try { ... } finally { temp.delete() }, as FileVisitPhotoStore.importPhoto does.
- **Reviewer's evidence:** Read ReferenceImageStore.kt:93-108 (temp.outputStream().use { ... throw IOException }; temp.delete() only inside the renameTo failure branch) and VisitPhotoStore.kt:107-123. deleteOrphans (120-126) is the only thing that later removes the leftover.

### data-room-3 · 🟡 nice-to-have · The limit rules re-implemented in the fake are not part of RepositoryContract

- **Location:** `core/data/src/test/kotlin/io/github/katznmeik/cinexperience/core/data/contract/RepositoryContract.kt:49`
- **Problem:** Text limits, row-label and screen-size limits (including "a value the hall already has is kept"), the hall cap and the unreadable-layout rule are hand-mirrored in FakeCinexRepository but tested only against Room, or only against the fake, never by the shared contract.
- **Failure scenario / cost:** Someone tightens or adds a limit in RoomRepositories.updateHall or saveVisitInTransaction and extends RepositoryLimitsTest / HallUpdateLimitsTest (Room only). The fake keeps the old rule, every ViewModel and screenshot test still passes on it, and the form's handling of the new refusal is never exercised. Small drifts exist already: the fake's renameHall goes through updateHall (offset, reference path and seat-id checks; name limit only if changed) while Room's changes only the name with requireName(); the fake's updateCinema returns false for a missing cinema before checking address and notes, where Room throws for over-long texts first.
- **Rule or spec reference:** CLAUDE.md "DI": the fakes re-implement the Room rules; RepositoryContract runs the same scenarios against both, so a changed rule gets a case there
- **Proposed fix** (refactoring, effort M): Move the interface-only scenarios of RepositoryLimitsTest, HallUpdateLimitsTest, HallLimitTest and FakeHallLimitTest into RepositoryContract (text limits on create/update/save, row label and screen bounds, kept legacy values via insertUnchecked, the hall cap), or share the rule code (see refactoring proposal 1).
- **Reviewer's evidence:** Read RepositoryContract.kt fully (its class doc, 37-48, lists what it covers: seat ids, override pruning, favourite seat, seat cleared on save, deletes, favourite cinemas, tickets; no limits). The mirrored rules are in FakeRepositories.kt:243-257, 267-287, 493-499 and RoomRepositories.kt:59-67, 187, 204-246. RepositoryLimitsTest and HallUpdateLimitsTest extend DatabaseTest (Room only); FakeHallLimitTest and FakeOverridesTest are fake only.

### Spec conflicts (report only)

- **data-room-S1** · `core/data/src/main/kotlin/io/github/katznmeik/cinexperience/core/data/seed/Seeder.kt:271` · spec §9 Seed updates: The spec says an untouched seed layout "is replaced by the newer seed's layout, together with its screen width and distance". The code replaces width and distance only while each still holds the value the seeder last wrote (appliedScreenWidth / appliedScreenDistance); a value the user changed is kept. CLAUDE.md describes the code's behaviour; the spec sentence reads as unconditional.

### Refactoring proposals

- **One source for the write rules that Room and the fake both apply** (effort M)
  - Gain: The text cleaning and limits (requireName, cleanText), the updateHall validation (changed-value limits, row labels, screen bounds, seat-id reuse, keep-unreadable) and the visit seat check exist twice, about 60 mirrored lines in FakeRepositories.kt. A pure rules object (e.g. HallUpdateRules.check(existing, new, mark), VisitSaveRules.clean(visit, stored, hallLayout)) used by both removes the drift risk and shrinks the contract to storage semantics.
  - Touches: core/data RoomRepositories.kt (updateHall, saveVisitInTransaction, text helpers), core/testing FakeRepositories.kt, a new rules file in core:model or core:data, the limit tests
  - Risk: Low to medium. Room compares against entity values and JSON (existing.layoutJson, decodeOrNull) and the fake against models; the "kept legacy value" and "keep unreadable layout" semantics must stay exactly as they are. The existing Room tests and the contract cover the behaviour.
- **Pull the per-hall seeding decision out of Seeder.apply into a pure plan** (effort M)
  - Gain: apply() is about 170 lines with interdependent locals (fillLayout, filled, stored, replacement, applied*, nested screenValue/appliedNow) and every rule of spec §9 in one loop. A pure function (hall entity, seed hall, snapshot view, visit/override counts, seat-id mark) -> (updated entity, snapshot records) would be readable rule by rule and testable on the JVM without Room or Robolectric.
  - Touches: core/data seed/Seeder.kt only; the Seed*Test files stay as regression tests
  - Risk: Medium: this is the most rule-dense code in the module and its behaviour is correct today as far as I traced it. Do it only with the roughly 1,500 lines of seeder tests green before and after, with no behaviour change.
- **Share the private-folder plumbing of the two file stores** (effort S)
  - Gain: resolve(), deleteOrphans() and the temp-write-then-rename step are duplicated in FileReferenceImageStore and FileVisitPhotoStore, with two different sample-size helpers (sampleSizeWithin, sampleSize). A small shared helper would have prevented the .tmp inconsistency above and gives path confinement one implementation to review.
  - Touches: core/data files/ReferenceImageStore.kt, files/VisitPhotoStore.kt and their tests
  - Risk: Low; the public interfaces do not change. The two stores sample differently on purpose (reference: at most MAX_DIMENSION; photos: at or above the target, then scaled), which must be kept.

### Questions (not confirmed)

- Auto Backup includes the whole database, so ticket booking numbers and QR modules go to Google's cloud backup and device transfer, while the app's own backup file leaves QR modules out and the membership card is excluded from Auto Backup for privacy. Spec §8 only says Auto Backup "covers the database". Is that intended for tickets?
- SQLite corruption: nothing in the repo overrides the open helper's corruption handling, and Android's default deletes the database file (framework behaviour, not verified here). The app would then reseed like a fresh install, and the next start's sweep would delete every now-unreferenced visit photo older than 7 days and reference screenshot older than 1 h. Is that acceptable, or should a corrupt file be kept aside and the sweep skipped on a freshly created database?
- Startup sweep versus a visit form restored after 7 or more days: VisitEditViewModel.checkRestoredPhotos checks photoStore.exists once, while DataContainer.sweepOrphanVisitPhotos runs concurrently from onAppStart and nothing orders the two. If the check runs first, the form keeps the path, the sweep deletes the file, and Save stores a visit pointing at a missing photo without the "no longer available" message. I did not confirm the timing in practice; the impact is a dangling path for a photo that would be lost either way.

### Coverage stated by the reviewer

Result: no critical or important problem found in this area; three small findings. Nothing was run (no Gradle); everything is from reading.

Checked and found correct: schema 1/2/3 createSql against the entities and MIGRATION_1_2 / MIGRATION_2_3; no destructive fallback and no REPLACE anywhere (visits, halls and cinemas use @Update, tickets update-then-insert); foreign keys and cascades; ticket rules (one per visit, unique booking number, BookingNumberInUse saves nothing, saveVisit keeps the ticket, guarded restoreQrModules, removeExpiredQrModules); seat-id mark and reuse rejection; override pruning and one favourite per hall; isFavourite in every cinema flow; every Seeder rule in CLAUDE.md (traced branch by branch); file stores' path confinement and sweep ages; limits in every repository writer; FakeCinexRepository compared rule by rule with Room (faithful apart from the small drifts in finding 3); the backup validator's rules against the repository rules (no disagreement).

Read fully (core/data main): db/Entities.kt, Daos.kt, CinexDatabase.kt, Mappers.kt, SeatIdMark.kt; repository/Repositories.kt, RoomRepositories.kt, RoomTicketRepository.kt, RankingsRepository.kt; seed/Seeder.kt; settings/MembershipCardStore.kt, ReminderSettingsStore.kt; files/ReferenceImageStore.kt, VisitPhotoStore.kt; json/LayoutJson.kt, TicketSeatsJson.kt; DataContainer.kt, DatabaseConfig.kt, AndroidManifest.xml, visit_photo_paths.xml, build.gradle.kts.

Read fully (core/testing): FakeRepositories.kt, FakeVisitPhotoStore.kt, FakeReferenceImageStore.kt, FakeMembershipCardStore.kt, FakeReminderSettingsStore.kt.

Read fully (tests): RepositoryContract.kt and both runners, TicketDatabaseBackstopTest, OrphanSweepAgeTest, SeedLimitsTest, DataContainerQrModulesTest, SeedingErrorTest, TestDatabase.kt.

Skimmed: schemas 1-3 (createSql and identity hashes only, not the per-field JSON); DatabaseOpenPathTest, RepositoryLimitsTest, HallUpdateLimitsTest, MembershipCardStoreTest (failed-write case), MigrationTest (case names), BackupLayoutAndScreenLimitsTest (seed case), TicketFixtures.kt; yorck.json (head only).

Boundaries read in part: core:model Domain.kt, Ticket.kt, ShowingTime.kt (full), SeatLayout.kt (SeatId, LayoutRow, SeatLayout init / isEditOf / shift / canonical, LayoutPattern), SeatOverrides, ScreenOffset / ScreenSize / TextLimits / DataLimits; backup: BackupValidator (BackupReader.kt:95-261), BackupWriter.kt, BackupRestorer.kt, BackupFormat.kt:236-485, RoomBackupRepository.prepare; app: AppContainer.onAppStart, MainActivity, backup_rules.xml, data_extraction_rules.xml; features: VisitEditViewModel (restore, photos, save, delete), LayoutEditorViewModel (reference image, save), EditMembershipCardViewModel (init, save), TicketImportViewModel.restoreQrCode. Spec §4, §8, §9, §10, §11.2.

Not read: the seeder test files (SeederTest, SeedUpgradeTest, SeedLayoutUpdateTest, SeedAppliedScreenTest, SeedDeletedItemsTest, SeederHallCapTest, SeederIdLimitTest, SeedBlockStarts*, SeedParserTest), YorckSeedTest, LayoutJson*Test, the file-store tests, CinemaRepositoryTest, HallRepositoryTest, VisitAndOverrideRepositoryTest, AllVisitsLayoutReadsTest, LiveDetailTest, UnreadableLayoutTest body, ReminderSettingsStoreTest body; core/testing ScreenshotFixtures, ProgramFixtures, FakeProgramRepository, FakeBackupRepository, AccessibilityChecks; the rest of core/data backup/ (another reviewer's area); scripts/seed.

## data-backup: Data integrity: backup export/import engine

### data-backup-1 · 🟠 important · Screens and dialogs opened before an import act on the imported rows by id once the lock lifts

- **Location:** `app/src/main/kotlin/io/github/katznmeik/cinexperience/ui/CinexApp.kt:123-128,342 (and core/data/.../backup/BackupRepository.kt:103-109)`
- **Problem:** The import lock only covers a window while isImporting is true; afterwards every screen, form and confirmation dialog that was open before the import is still there with its pre-import state and writes by id into the imported data.
- **Failure scenario / cost:** Open a ticket PDF from the email app: Cinexperience opens a second window in the email task. Leave an edit form or the 'Delete this visit?' dialog for visit 28 open there. Switch to the launcher window, Settings, import a backup (or restore a safety copy). Switch back: the form/dialog is unchanged. Save runs saveVisit(form.toVisit(28)) and overwrites the imported visit 28 with the old form's fields and hallId; Delete removes the imported visit 28. With a backup from another install (new phone used a little, then the old phone's backup imported) visit 28 is a different visit. A window that was in the background for the whole import never even shows the lock, because isImporting is collected with collectAsStateWithLifecycle and is already false when it resumes.
- **Rule or spec reference:** CLAUDE.md Backup: 'the UI blocks every other write'; BackupRepository.isImporting KDoc: 'a write queued behind the import would land on the imported data, possibly on a different row with the same id'
- **Proposed fix** (refactoring, effort M): Let the repository publish a data generation (a StateFlow counter bumped in onCommitted) instead of relying on the isImporting edge. Each CinexApp window remembers the generation it was built for (rememberSaveable) and, when it differs, pops to the start destination, drops saved tab stacks and shows a short message. Update BackupImportLockTest, whose dialog test currently expects the stale delete to go through.
- **Reviewer's evidence:** CinexApp.kt:128 lockedByImport = importing && !runsBackupImport, 342 the lock is the only reaction; no navigation reset anywhere (grep isImporting in app/ and feature/). VisitEditViewModel.kt:479-490 save() writes form.toVisit(visitId) from in-memory state, 595-600 confirmDelete() calls deleteVisit(visitId). RoomRepositories.kt:326-349 saveVisitInTransaction updates by id when the row exists. BackupImportLockTest.kt:143-162 asserts that the delete dialog opened before the import deletes the visit after it ('deleted once the import has ended'). CLAUDE.md: MainActivity is standard launch mode, a share opens a second window.

### data-backup-2 · 🟡 nice-to-have · A failed export leaves the empty document the picker created

- **Location:** `core/data/src/main/kotlin/io/github/katznmeik/cinexperience/core/data/backup/RoomBackupRepository.kt:118-130`
- **Problem:** export() deletes the destination only for failures inside the write block; when reading the data or validating it fails first, the zero-byte document that ACTION_CREATE_DOCUMENT already created stays at the destination.
- **Failure scenario / cost:** Current data fails validation (the documented case: a layout from a newer app, more than 1,000 halls). The user taps Export backup and picks Drive or Downloads; the picker creates cinexperience-backup-2026-11-12.zip, export() throws 'Your data can't be backed up: ...' at line 129 before line 133, and documents.delete is never called. A 0-byte file with a backup's name remains in the user's cloud folder. Same for 'Couldn't read your data for the backup.' (line 123) and for cancellation while waiting for the mutex or seeding.
- **Rule or spec reference:** BackupRepository.export KDoc: 'On failure the partly written document is deleted where possible'; RoomBackupRepository.kt:140 'Never leave a partial file that looks like a backup'
- **Proposed fix** (local, effort S): Move readBackupData and writer.prepare inside the try whose catch deletes the document (or wrap the whole body in try/catch that calls documents.delete for every failure and cancellation). Make the test FileDocuments create the file when the 'document' is handed out, like SAF does, so the existing assertFalse(File(doc).exists()) checks the real behaviour.
- **Reviewer's evidence:** RoomBackupRepository.kt:118-130 throw outside the try at 132-153 that holds documents.delete (141). SettingsScreen.kt:111 uses ActivityResultContracts.CreateDocument, which returns the URI of an already created empty document. SettingsViewModel.kt:175-192 only shows the message. BackupReviewRegressionTest.kt:244-257 asserts no file exists after a validation failure, but BackupTestSupport.kt:56-70 FileDocuments only creates the file in openOutput, so the test cannot see the leftover.

### data-backup-3 · 🟡 nice-to-have · A safety copy's temporary file is only removed after a later successful import

- **Location:** `core/data/src/main/kotlin/io/github/katznmeik/cinexperience/core/data/backup/RoomBackupRepository.kt:318-343`
- **Problem:** If the process dies or an Error is thrown while the safety copy is written, pre-import-<n>-<stamp>.zip.tmp stays in files/backups, and the only code that deletes .tmp files is SafetyCopies.prune(), which runs only after an import has committed.
- **Failure scenario / cost:** A user with 1.5 GB of photos starts an import and the app is killed (swiped away, low memory) or hits OutOfMemoryError during the safety copy. Up to 1.5 GB stays in files/backups, invisible in Settings (the list only parses *.zip). The next import attempt needs room for a full new safety copy plus 64 MB (line 311); on a nearly full phone the leftover itself makes that check fail, so no import can succeed and the leftover is never pruned. The only way out is clearing app data.
- **Rule or spec reference:** CLAUDE.md Backup: 'check free space ... before writing the safety copy'; 'a failed import deletes the copy it wrote'
- **Proposed fix** (local, effort S): Delete *.tmp in files/backups at app start (next to clearLeftoverStaging in DataContainer.sweepOrphanFiles) and at the start of ensureSafetyCopy, before the free-space check; catch Throwable instead of Exception around the write so an Error also removes the temp file.
- **Reviewer's evidence:** RoomBackupRepository.kt:320 temp name, 328-329 catch (e: Exception) deletes temp (not for Errors, although failure() at 263-271 expects OutOfMemoryError), 232-245 the pre-commit catch only deletes newCopy, which is still null. BackupStorage.kt:131-134 prune() is the only deletion of TEMP_SUFFIX files (grep TEMP_SUFFIX); RoomBackupRepository.kt:248-250 calls it only after the commit. BackupStorage.kt:136-138 parse() ignores *.zip.tmp, so Settings never lists it. DataContainer.kt:152-170 the startup sweep only clears cacheDir/backup-import.

### data-backup-4 · 🟡 nice-to-have · The validator accepts row ids up to Long.MAX_VALUE, which permanently blocks new rows

- **Location:** `core/data/src/main/kotlin/io/github/katznmeik/cinexperience/core/data/backup/BackupReader.kt:117,133,169`
- **Problem:** Cinema, hall and visit ids are only checked to be positive and unique; an id of 9223372036854775807 is inserted as is and exhausts the table's AUTOINCREMENT sequence, which no later import or safety-copy restore resets.
- **Failure scenario / cost:** A hand-edited backup has a visit with id 9223372036854775807. It passes validation and is imported. From then on every insert into visit with a generated id fails with SQLITE_FULL ('Couldn't save' for every new visit; for hall or cinema ids also the seeder's inserts at every start). Restoring the safety copy replaces the rows but leaves sqlite_sequence at the maximum, so the app stays broken until its data is cleared. Only a crafted file does this; the app's own ids grow by one.
- **Rule or spec reference:** CLAUDE.md Backup: 'BackupValidator holds the data rules'; seat ids already have MAX_SEAT_ID 'so id arithmetic never overflows'
- **Proposed fix** (local, effort S): Give row ids an upper bound in BackupValidator (for example DataLimits.MAX_ROW_ID = 1_000_000_000_000) and add a case to BackupLimitsTest; nothing the app writes comes near it.
- **Reviewer's evidence:** BackupReader.kt:117 'cinema.id <= 0 || !cinemaIds.add', 133, 169: no upper bound. Entities.kt:26,53,92 autoGenerate = true (Room creates AUTOINCREMENT tables). BackupRestorer.kt:132-149 replaceRows only deletes and inserts table rows, never touches sqlite_sequence; the test visitsAddedOnTheSameDeviceAfterAnImportGetFreshIds relies on the sequence surviving an import. SQLite documents SQLITE_FULL for AUTOINCREMENT once the largest rowid was used. Not run, traced by reading.

### data-backup-5 · 🟡 nice-to-have · Dates outside the range java.time and the pickers handle pass validation and crash screens

- **Location:** `core/data/src/main/kotlin/io/github/katznmeik/cinexperience/core/data/backup/BackupReader.kt:172-176,522-526`
- **Problem:** The manifest's exportedAt is accepted as any Instant and a visit's date as any LocalDate, but the preview dialog and the visit form convert them without guards.
- **Failure scenario / cost:** (a) A manifest with "exportedAt": "+1000000000-01-01T00:00:00Z" parses (Instant.MAX is in that year), prepareImport succeeds, and ImportPreviewDialog calls dateTimeText(preview.exportedAt, zone) -> instant.atZone(zone), which throws DateTimeException (year above 999999999) during composition: the app crashes every time that file is picked, before anything is confirmed. (b) A visit dated "+999999999-12-31" is imported; opening it and tapping the date calls date.toPickerMillis() -> Instant.toEpochMilli(), which throws ArithmeticException: crash. ShowingTime.qrModulesExpireAt (date.plusDays) throws for the same date. Only crafted files; the form itself writes years 1900-2100.
- **Rule or spec reference:** Task brief: 'a value the validator accepts must never crash a reader'; BackupReader KDoc: 'Throws BackupException for anything unexpected'
- **Proposed fix** (local, effort S): In parseManifest and BackupValidator accept only dates in a sane range (for example years 1 to 9999, wider than VisitDates.YEARS so data from any build still exports) and report others as 'invalid date'; add cases to BackupValidationTest.
- **Reviewer's evidence:** BackupReader.kt:522-526 only Instant.parse; SettingsScreen.kt:524-526 and core/ui/.../text/Dates.kt:25 'DATE_TIME.format(instant.atZone(zone))' unguarded. BackupReader.kt:172-176 only LocalDate.parse; VisitEditUiState.kt:65 'date = visit.date' (no VisitDates check for a stored visit), VisitEditScreen.kt:554 'date.toPickerMillis()', DatePickerDates.kt:10 'atStartOfDay(UTC).toInstant().toEpochMilli()'; ShowingTime.kt:48-49 plusDays. Checked Material3 1.4.0 bytecode: a year outside yearRange is tolerated, so years like 2200 do not crash, only the overflow range does. Whether TicketViewModel.kt:147 catches the DateTimeException was not traced.

### Refactoring proposals

- **One 'data was replaced' signal from the backup repository** (effort M)
  - Gain: Closes the gap of the first finding in one place: every window resets its navigation and transient state after an import, also a window that was in the background during the whole import and never saw isImporting. The same signal lets the reminders drop notifications posted for pre-import visit ids.
  - Touches: core:data BackupRepository + RoomBackupRepository (counter bumped in onCommitted), core:testing FakeBackupRepository, app CinexApp (reset to the start destination, clear saved tab stacks), BackupImportLockTest (the dialog test expects today's behaviour), optionally app reminders.
  - Risk: Unsaved input in another window is discarded after an import (intended, but new behaviour); the reset must not swallow a pending ticket-import or reminder intent, and must not fire in the Settings screen that ran the import before it has shown its result message.
- **Put BackupValidator, BackupMigrations and the entity mappers in files of their own** (effort S)
  - Gain: CLAUDE.md sends every schema or format change to BackupValidator and BackupMigrations, but they live inside BackupReader.kt (591 lines, also used by the writer) and BackupFormat.kt (490 lines: format constants, DTOs, migrations, mappers). Separate files make the three places a format change must touch findable by name.
  - Touches: core/data/.../backup/BackupReader.kt, BackupFormat.kt (moves only, all internal); no behaviour change.
  - Risk: None beyond merge conflicts with open branches that edit these files.

### Questions (not confirmed)

- BackupImportLockTest.aDialogOpenBeforeTheImportCantBeConfirmedUnderTheLock asserts that a 'Delete this visit?' dialog opened before an import deletes the visit with that id after the import. Is acting on the imported row intended, or only the test's way to show the window works again? (Finding 1 assumes it is not intended.)
- Reminder notifications posted before an import keep their visit id; ReminderNotifier.cancel is only called for a tapped one. After an import a tap opens EditVisitRoute or TicketRoute for whichever imported visit has that id. Intended? I did not trace IncomingReminderViewModel and ReminderIntents.
- The writer runs BackupValidator but not the reader's size limits (data.json at most 16 MB, 50,000 entries, 8 GB in total). Pretty-printed, data.json reaches 16 MB at very roughly 20,000 visits, or 4,000 visits with 4,000-character notes; such an export or safety copy could not be imported again. Out of reach for one person, or worth a check in BackupWriter.prepare?
- The v2 fixture was added in the same commit as backup version 3 (d851df7). Was it exported by a real v1.3 build or written by hand? It only matters for how much the fixture proves about real version 2 files.

### Coverage stated by the reviewer

The engine itself held up: I found no way for a failed import to touch data, lose the safety copy or leave isImporting set, and no table or column missing from the backup. Nothing was run (no Gradle); everything is traced by reading, plus one javap look at Material3 1.4.0's DatePicker state.

Read fully: core/data backup/ (BackupFormat.kt, BackupReader.kt incl. BackupValidator, BackupRestorer.kt, BackupWriter.kt, BackupStorage.kt, BackupRepository.kt, RoomBackupRepository.kt), json/LayoutJson.kt, json/TicketSeatsJson.kt, db/Entities.kt, db/Daos.kt, db/Mappers.kt, db/SeatIdMark.kt, db/CinexDatabase.kt, repository/RoomRepositories.kt, repository/RoomTicketRepository.kt, DataContainer.kt, seed/Seeder.kt, core/testing FakeBackupRepository.kt, feature/backup SettingsViewModel.kt, SettingsNavigation.kt, BackupTexts.kt, app BackupImportLock.kt, spec sections 6.7, 8 and 11.3.

Read in part: SettingsScreen.kt (lines 84-323 and 400-560), CinexApp.kt (lines 95-350), ReminderCoordinator.kt (the isImporting parts), ReminderNotifier.kt (cancel only), VisitEditViewModel.kt / VisitEditScreen.kt / VisitDraft.kt (load, save, delete, date picker), the file stores (names and path checks by grep), core:model Ticket.kt (first 150 lines), ShowingTime.kt, TextLimits.kt, ScreenSize.kt, the init checks of SeatLayout.kt, BackupImportLockTest.kt (two tests), BackupReviewRegressionTest.kt (one test), BackupTestSupport.kt (FileDocuments), the names of all core/data backup tests, backup_rules.xml and data_extraction_rules.xml (by grep), the head of the v2 fixture and the git history of both fixtures (only ever added, never changed).

Not read: the bodies of the other core/data backup tests, the v1 fixture's content, the exported Room schema JSONs and MigrationTest, feature/backup tests, ReminderSettingsViewModel.kt, ReminderRows.kt, the rest of SettingsScreen.kt, FakeRepositories.kt, ImportBackTest, MainActivity and the Incoming*ViewModels, AppContainer beyond onAppStart.

Checked and found in order: validation before any write; zip entry names, duplicates, ordering and size caps; free-space checks; safety copy sequence, reuse, deletion on failure and pruning; one transaction with the data-changed check; fresh file names and removal on failure; nothing after onCommitted can fail the import; migrations 1 to 2 to 3 and strict parsing; entity/DTO field parity for schema 3; seed snapshots and metadata restored and seeding run afterwards; validator limits equal to the repositories' limits for everything the app writes; membership card, reminder settings and program cache untouched; reminders rescheduled when isImporting falls (spec 11.3 rule 4).

## model: Domain model: seat layouts, numbering, scores, time rules

### model-1 · 🟡 nice-to-have · Block start numbers have no upper bound: seat numbers overflow and the layout editor crashes

- **Location:** `core/model/src/main/kotlin/io/github/katznmeik/cinexperience/core/model/SeatLayout.kt:188`
- **Problem:** LayoutRow, LayoutJson, BackupValidator and updateHall accept any non-negative block start, so a start near Int.MAX_VALUE makes seat numbers wrap negative and makes a normal paint in the editor throw an IllegalArgumentException that nothing catches.
- **Failure scenario / cost:** Only a crafted or damaged backup gets there (the editor allows 4 digits). A backup whose layout has a row "SSS" with "blockStarts":[2147483647] imports without complaint. The hall then shows seats 2147483647, -2147483648, -2147483647. Opening the editor and painting the row's first seat as a gap crashes the app: movedStarts hands the start on with the next seat's wrapped number, and LayoutRow.init refuses it with "Block start numbers must not be negative: [-2147483648]". LayoutEditorViewModel.edit catches only SeatIdsExhaustedException.
- **Rule or spec reference:** CLAUDE.md "Backup" (the validator's size limits; nothing imported may break the app) and "Seats" (the editor catches only SeatIdsExhaustedException, any other model exception is a bug)
- **Proposed fix** (local, effort S): Add one model limit, e.g. MAX_BLOCK_START = 9_999 (the editor's MAX_START_DIGITS), next to the SeatLayout limits. Check it in BackupValidator (refuse the backup) and in updateHall for changed layouts. A stored layout must stay readable, so do not put it in LayoutRow.init; instead make numbersByColumn and movedStarts safe (clamp or saturate) so no negative start can be built. Add a case to BackupLayoutAndScreenLimitsTest and a model test.
- **Reviewer's evidence:** Read LayoutRow.init (SeatLayout.kt:186-191, only >= 0), numbersByColumn (SeatLabels.kt:148-152, previous + 1), movedStarts (SeatLayout.kt:335), withBlockStartsKeyedOn (:277), LayoutJson.fromJson (LayoutJson.kt:247, passed through), BackupValidator (BackupReader.kt:148-162: decode, isPadded, row label length only), updateHall (RoomRepositories.kt:240-245) and LayoutEditorViewModel.edit (:636-641). Reproduced outside Gradle by compiling core/model's main sources into the scratchpad: starts [Int.MAX_VALUE] and [Int.MAX_VALUE - 1] on "SSS" give the labels above, and setCellType(0, 0, GAP) throws that IllegalArgumentException. LayoutJsonLimitsTest:97 already builds layouts with Int.MAX_VALUE starts, but only measures JSON length.

### model-2 · 🟡 nice-to-have · A seat painted back brings its old block start with it, replacing a start typed in the meantime

- **Location:** `core/model/src/main/kotlin/io/github/katznmeik/cinexperience/core/model/LayoutEditSession.kt:104`
- **Problem:** The ghost of a painted-away seat keeps the block start it was keyed on, and LayoutEditSession.updateRow leaves ghosts untouched, so restoring the seat overrides a start the user set or cleared after painting it away.
- **Failure scenario / cost:** Row "SSS" with start 5 reads 5 6 7. Paint column 0 as a gap: the row reads 6 7 (start 6). Type 10 into "Starts at": 10 11. Paint column 0 back as a seat: the row reads 5 6 7 again, so the typed 10 is gone and the two existing seats change number. A new (non-original) seat in the same place gives 9 10 11, as spec 4.3 describes. Clearing the start to automatic and painting the seat back also brings start 5 back. The numbers update live, so the owner can retype the start.
- **Rule or spec reference:** Spec 4.3 (a seat added in front of a block with an explicit start takes start - 1, so the existing seats keep theirs); CLAUDE.md "Seat numbering"
- **Proposed fix** (local, effort S): In LayoutEditSession.updateRow, when the transform changes the row's blockStartNumbers, numbering or numberingDirection, drop blockStart from that row's ghosts (keep id and fixed label). The restored seat then counts as new and takes start - 1. Add a case to LayoutEditSessionTest.
- **Reviewer's evidence:** Traced paint() (ghost captured at LayoutEditSession.kt:104, restored at :97 through LayoutChanges.setSeat), updateRow (:116-117, ghosts passed on unchanged), LayoutRow.withCells (SeatLayout.kt:310: anchors = blockStartAnchors() + movedStarts(changed) + restored, so the restored entry wins) and LayoutEditorViewModel.setBlockStart (:363-380 calls draft.updateRow). Ran the scenario on the compiled model sources: the outputs are exactly the rows quoted above.

### model-3 · 🟡 nice-to-have · A running paint stroke overwrites any other edit that lands during it

- **Location:** `feature/seatmap/src/main/kotlin/io/github/katznmeik/cinexperience/feature/seatmap/editor/LayoutEditorViewModel.kt:249`
- **Problem:** paintCells publishes PaintStroke.add's result, which is always rebuilt from the draft captured when the stroke started, so a draft change made by anything else during the stroke is silently dropped on the next move event.
- **Failure scenario / cost:** Pick a reference screenshot and start dragging a paint stroke before the copy has landed. The import calls edit { withReferenceImage(path) } and switches reference mode on. The next move event republishes the stroke's draft, which has no image. The screenshot disappears, undo and redo cannot bring it back (edits inside a gesture are not recorded), and its file is deleted at save or discard. The same happens to a stepper tapped with a second finger during a stroke (row or column count, screen size). The window is small and nothing stored is damaged.
- **Rule or spec reference:** Spec 6.5 (reference screenshot; undo/redo for every change)
- **Proposed fix** (local, effort S): Make the stroke carry only what it owns: publish current.copy(session = stroke.result.session), or end the stroke (endStroke(), so the next cells start a new PaintStroke on the current draft) whenever edit() runs for something other than the stroke while gestureBase is set. Add a ViewModel test: beginGesture, paintCells, import lands, paintCells, endGesture, then the image is still in the draft.
- **Reviewer's evidence:** Read PaintStroke.add (PaintStroke.kt:36-55: drafts start at base, the result is drafts.last()), LayoutEditorViewModel.paintCells (:241-251: edit { running.add(cells) } ignores the lambda's current draft), edit (:629-645: no history record while gestureBase is set), onReferenceImagePicked (:411-435) and deleteImports (:603-607). EditorCanvas.paintOrZoom (:755-798) picks the canvas mode at pointer-down, so the stroke keeps painting after referenceMode flips. ReferenceImportRaceTest covers save and discard races only. Traced by reading; not run.

### Spec conflicts (report only)

- **model-S1** · `core/model/src/main/kotlin/io/github/katznmeik/cinexperience/core/model/SeatLayout.kt:323` · spec 4.3 Split rows: "When that seat is removed, the start moves on to the next seat with that seat's number, so the remaining seats keep their numbers.": movedStarts hands a start on only inside its own block (KDoc: "A block left without numbered seats keeps nothing"). When a whole block with an explicit start is painted away in a continuous row, the automatic blocks after it lose their numbers. Run on the compiled model: "SS|SSSS" with start 3 reads 3 4 | 5 6 7 8; painting columns 0 and 1 as gaps gives 1 2 3 4. The same row without the aisle ("SSSSSS", start 3) keeps 5 6 7 8. "W.W|SSSS|W.W" with start 3 (3 4 | 5..8 | 9 10) reads 1..4 | 5 6 after the left wheelchair block is removed. The spec sentence has no "within its block" limit; BlockStartMovesOnTest covers an emptied block only where the next block has its own start. Painting the seats back in the same session restores the numbers.
- **model-S2** · `core/model/src/main/kotlin/io/github/katznmeik/cinexperience/core/model/Reminders.kt:280` · spec 11.3: "v1.4 implements e and f for upcoming visits (§4.4) that have a start time": ReminderSchedule.plan schedules "Rate your visit" for every unrated visit with a start time whose rate time is still ahead, whether or not the visit was ever upcoming. A visit saved after its showing started (e.g. logged at 21:00 for a 20:15 start, without ratings) still gets the notification about 30 minutes after the film's end. The KDoc reasons from "a visit stays upcoming until it starts", which covers visits saved before the start only. Rules 2 and 3 of 11.3 don't settle it.

### Refactoring proposals

- **Move the stroke order into core:model and property-test the numbering rules there** (effort M)
  - Gain: The spec 4.3 promise (existing seats keep their numbers whichever way the finger moves) depends on two halves in different modules: LayoutRow.withCells in core:model and LayoutDraft.strokeOrder in feature:seatmap. With the order inside LayoutEditSession (e.g. paintStroke(cells, type, kind)), every caller gets the order-independent result, and the invariants can be checked by a seeded random test in the pure JVM module: paint away and back gives the same row; seats in front of a block count back; removing the front seats hands the start on. I ran such a fuzz ad hoc and had to copy strokeOrder into it.
  - Touches: core/model LayoutEditSession.paintCells (its contract is "in the given order" today), feature/seatmap LayoutDraft.strokeOrder, paintStroke and paintOrdered, PaintStroke (it merges per row with base.strokeOrder), BlockStartStrokeDirectionTest, StrokeOrderLargeGridTest, LayoutEditSessionPaintCellsTest
  - Risk: Low to medium. Behaviour stays the same if the comparator moves verbatim. PaintStroke's incremental repaint relies on the order being computed against the stroke's base layout, so that must be kept.
- **Give the block start re-keying its own file in core:model** (effort S)
  - Gain: SeatLayout.kt (782 lines) mixes the grid type, id allocation, pattern coding and the densest logic in the module: blockStartAnchors, withBlockStartsKeyedOn, withCells, movedStarts and frontStarts, about 180 lines with long KDoc. Moving these to BlockStarts.kt, next to numbersByColumn which they depend on, makes the numbering rules readable and reviewable as one unit.
  - Touches: core/model SeatLayout.kt and SeatLabels.kt only; the functions become internal extensions on LayoutRow instead of members. No behaviour change.
  - Risk: Low: a pure move inside one module. The existing BlockStart*Test files cover it.

### Questions (not confirmed)

- ShowingTime.qrModulesExpireAt (ShowingTime.kt:47-50) throws DateTimeException for a date in the last days of year +999999999, which BackupValidator's LocalDate.parse accepts. TicketViewModel turns it into "Couldn't load the ticket" through the flow's catch, and NextVisitViewModel short-circuits without a QR code. I did not trace TicketImportViewModel.kt:233 or VisitEditViewModel.kt:515 and :762. Should the validator bound visit dates to a sane range, as ReminderSchedule.plan already guards?
- A wheelchair space with fixed label "R" painted as a gap and back with the standard-seat tool comes back as a standard seat labelled "R" (LayoutEditSession.kt:97, KDoc: "It gets the painted kind"). Is losing the kind intended, given that the id, the fixed label and the block start are restored?
- LayoutDraft.withColumnCount passes the screen width through ScreenRules.width (LayoutDraft.kt:139), so a stored width outside the editor's range, which LayoutDraft.start deliberately keeps, is snapped and limited by a column stepper tap even though the width control wasn't touched. Intended?

### Coverage stated by the reviewer

No critical or important problems found in this area; the three findings are all "nice", and one of them needs a crafted backup.

Read fully:
- core/model main, all 23 files: SeatLayout, SeatLabels, LayoutEditSession, LayoutEditImpact, Domain, Scores, Ratings, Rating, SeatColours, ViewingAngle, ScreenOffset, ScreenSize, ShowingTime, UpcomingVisits, Reminders, Ticket, TicketDraft, MembershipCard, TextLimits, DataLimits, VisitPrefillArgs, ReadModels, NameOrder.
- feature/seatmap editor: LayoutDraft.kt, PaintStroke.kt, UndoHistory.kt, LayoutEditorViewModel.kt.
- core/data json: LayoutJson.kt, TicketSeatsJson.kt.
- docs/SPEC.md sections 4, 5, 6.1 to 6.6 and 11.3.

Read in part, for the module boundaries:
- EditorCanvas.kt lines 500-800 (gesture handling) and EditorPanels.kt (block start fields).
- core/data Mappers.kt 1-150, RoomRepositories.updateHall (215-300), BackupValidator in BackupReader.kt (108-254), RoomBackupRepository.prepare, RoomTicketRepository.removeExpiredQrModules, DataContainer.removeExpiredQrModules.
- TicketViewModel and NextVisitViewModel, only around their qrModulesExpireAt calls.

Not read:
- EditorSteppers.kt, EditorDialogs.kt, LayoutEditorScreen.kt (grep only), the rest of EditorCanvas.kt, core:seatmapui.
- The test files, apart from test names and excerpts of BlockStartMovesOnTest and LayoutJsonLimitsTest.
- Spec sections 8 and 9, the Seeder, FakeRepositories.

Extra verification (no Gradle, nothing written to the repo): I compiled core/model's main sources with the Kotlin compiler jar from the Gradle cache into the scratchpad and ran about 100,000 random single-row cases (both directions, both numberings, explicit, zero and surplus starts, fixed labels). Harness: /tmp/claude-0/-home-user-Cinexperience/ba88c1f0-9819-5f21-a264-287ac117b290/scratchpad/fuzz/Fuzz.kt and /tmp/claude-0/-home-user-Cinexperience/ba88c1f0-9819-5f21-a264-287ac117b290/scratchpad/fuzz/Fuzz2.kt. The stroke order in it is a copy of LayoutDraft.strokeOrder, not the editor's own code.

What the fuzz confirmed, with 0 violations:
- Random paint, column and direction operations never throw.
- Painting seats away (gap or aisle) and back restores ids, fixed labels and every number, by stroke or by single taps in any order.
- Removing the first numbered seat of a block with an explicit start leaves all other numbers unchanged.
- One or several seats painted in front of such a block count back from the start, down to 1 or to just after the previous block.

Checked by reading only, no problems found: rating maths against spec 5 (means of non-empty values, overall scores, thresholds 4.0 and 2.5 in integer arithmetic, formatScore and ScoreOrder noise handling, no SQL maths), the Berlin time rules (gap and overlap, the 30-minute card window, 7-day QR expiry, lastPossibleAt, the 50-reminder cap, passed reminders not planned), seat id allocation and SeatIdsExhaustedException, ScreenOffsetRules and LayoutDraft.start snapping, LayoutJson decode failures (all IllegalArgumentException, so a bad layout reads as unreadable), equality of Ticket, QrModules and LayoutDraft, UndoHistory.

## untrusted-program: Untrusted input: program download, parser, network confinement

### untrusted-program-1 · 🟠 important · Network scan skips code the build still compiles: folders named build or hidden, relocated modules, included builds

- **Location:** `core/program/src/test/kotlin/io/github/katznmeik/cinexperience/core/program/NetworkConfinementTest.kt:783`
- **Problem:** The scan decides what to read by folder name and default layout, so sources in a folder named `build`, `node_modules` or `.x` at any depth, and modules that settings.gradle.kts places elsewhere, are compiled into the app but never scanned.
- **Failure scenario / cost:** Three concrete constructs pass all checks. (1) A source file at feature/x/src/main/kotlin/.../build/Foo.kt (a package folder named `build`) or under src/main/kotlin/.x/ is compiled by Gradle but skipped by `walk`, so java.net use there is not reported. (2) A module at tools/build/net/, or one moved with `project(":x").projectDir = file(".x")` or `file("../outside")`, is skipped whole: sources, manifest and build file, so `moduleBuildFileViolations` never requires the convention plugin and `forbidFileDependencies` is not installed. `buildFileName` has a narrower effect: a renamed build file is still scanned for libraries, file dependencies and repositories, but the convention-plugin requirement only looks at build.gradle(.kts), so that module can drop `forbidFileDependencies`. (3) A second `includeBuild(...)` in settings.gradle.kts with dependency substitution: its code lives outside the scanned tree, and `:app:checkReleaseDependencyGroups` does not see it because substituted components are project components. The merged manifest already holds INTERNET from core:program, so AppPermissionsTest stays green in all three cases.
- **Rule or spec reference:** spec §11.1 Network & security rule 3; CLAUDE.md "NetworkConfinementTest (core:program) scans every module's sources"
- **Proposed fix** (local, effort M): In `walk`, skip `build` only where it is a Gradle build directory (direct child of a folder holding a build or settings file) and never below `src/`. Skip hidden folders only at the repository root; a hidden or `build` folder below any `src/<set>/` is a violation. Add build-file rules for settings: refuse `projectDir`, `buildFileName` and any `includeBuild(` other than the exact `includeBuild("build-logic")`. Assert that every `include(":a:b")` in settings.gradle.kts has a scanned a/b/build.gradle.kts. Mirror the same excludes in core/program/build.gradle.kts (`networkConfinementSources`). Add self-tests for each case next to `theScanFindsAViolationInAnyModuleButTheAllowedPackage`.
- **Reviewer's evidence:** NetworkConfinementTest.kt:779-789: `walk` continues on `child.name.startsWith(".") || child.name in SKIPPED_DIRS` at every depth; SKIPPED_DIRS is at line 638. The self-tests cover only a module-level feature/x/build/ and a root-level .hidden/ (lines 518-519). `buildViolations` (687-691) with BUILD_CODE_RULES (590-600) and REPOSITORY (606-609) has no pattern for projectDir, buildFileName or includeBuild. `moduleBuildFileViolations` (712-717) only looks at files named build.gradle(.kts) that the walk reaches. build-logic CheckDependencyGroupsTask.kt:75 records groups only for `ModuleComponentIdentifier`. core/program/build.gradle.kts excludes `**/build/**` and `**/.*/**` from the test inputs too. No folder named build or hidden exists under any src/ today (checked with find), so nothing is hidden at present.

### untrusted-program-2 · 🟠 important · Network scan misses plain platform APIs that open connections without any matched word

- **Location:** `core/program/src/test/kotlin/io/github/katznmeik/cinexperience/core/program/NetworkConfinementTest.kt:547`
- **Problem:** SOURCE_RULES is a denylist of names with gaps: several ordinary Android and Kotlin calls reach the network from any module without matching a rule, and without any reflection or string tricks.
- **Failure scenario / cost:** Each of these, written in a feature module, gives no violation. (a) A URL obtained from the allowed `java.net.URI` without the text `.toURL(`: a method reference (`URI::toURL`) or an implicit receiver (`uri.run { toURL() }`), then Kotlin's `readText()` / `readBytes()` or `getContent()` on it, which never name openConnection or openStream. (b) `android.system.Os.socket` / `Os.connect` with an address from `android.net.InetAddresses`: a raw socket, no java.net text thanks to type inference. (c) `android.net.DnsResolver` queries for arbitrary names. (d) `android.media.MediaMetadataRetriever` or `MediaExtractor.setDataSource(url, …)`, which fetch over HTTP from the app process. (e) `java.nio.channels.spi.SelectorProvider.openSocketChannel()`. All need only the INTERNET permission the merged manifest already has (minSdk 34, so these APIs exist).
- **Rule or spec reference:** spec §11.1 Network & security rule 3 ("a test fails the build if any other module uses network APIs"); CLAUDE.md Program / network
- **Proposed fix** (local, effort S): Turn the platform rules into allowlists, as already done for java.net. Main code today uses only `android.net.Uri` and `android.media.ExifInterface`, so allow exactly those from `android.net.` and `android.media.` and forbid `android.system.` and `java.nio.channels.spi`. Match `\btoURL\b` as a word, like openConnection. Add each construct above to `sourceRulesCatchNetworkApis`.
- **Reviewer's evidence:** Rules at NetworkConfinementTest.kt:547-566: line 551 is `\bopenConnection\b|\bopenStream\b|\.toURL\s*\(`, which needs a literal dot and bracket; line 553 covers only `android.net.(Network|SSL\w*|TrafficStats|Proxy)`; line 558 needs Socket or Datagram directly after `java.nio.channels.`; there is no rule for android.system or android.media beyond MediaPlayer and VideoView. I ran the same patterns (Python re, equivalent for these ASCII cases) over one sample line per construct: zero matches for `URI(a).run { toURL() }.readText()`, `uri.let(URI::toURL)`, and imports of android.media.MediaMetadataRetriever, android.media.MediaExtractor, android.net.DnsResolver, android.net.InetAddresses, android.system.Os and java.nio.channels.spi.SelectorProvider. Grep of every src/main for `android.(net|media|system).` finds only android.net.Uri (6) and android.media.ExifInterface (1), so the allowlist costs nothing today. The CODE_LOADING_RULES at 574-575 already handle `::` references, which the toURL rule does not.

### untrusted-program-3 · 🟡 nice-to-have · Cache size limit is lower than what the parser accepts

- **Location:** `core/program/src/main/kotlin/io/github/katznmeik/cinexperience/core/program/ProgramCache.kt:234`
- **Problem:** The parser accepts up to 30,000 showings and 2,000 films, but the cache refuses anything above 4 MB, which is reached well below those limits.
- **Failure scenario / cost:** A program with roughly 15,000 showings or more (about 14 times today's; the code comment puts today's program at about 0.3 MB) parses and is shown, but `cache.write` returns false. Every download then ends with "The downloaded program couldn't be saved on the phone.", the old file stays, and after each app restart the Program tab downloads again because the cached program is stale or missing. The message points at the phone's storage, which is not the cause.
- **Rule or spec reference:** spec §11.1 rule 6 (limits far above today's numbers)
- **Proposed fix** (local, effort S): Make the two limits agree: either raise MAX_FILE_BYTES to cover a program at ProgramRules.MAX_FILMS / MAX_SHOWINGS, or have the parser reject a program whose cache form would exceed it (TOO_LARGE). Add a test that a program at the parser's limits can be written.
- **Reviewer's evidence:** ProgramCache.kt:61 `if (bytes.size > MAX_FILE_BYTES) return false`, limit at line 234 with the comment "Today's program is about 0.3 MB in this format". ProgramRules.kt:11-12: MAX_FILMS 2,000, MAX_SHOWINGS 30,000, with titles up to 200 and taglines up to 500 code points. DefaultProgramRepository.kt:236-247: the program is put into the state, then `Failed(STORAGE)` when `stored` is false. ProgramCacheTest.aProgramAboveTheLimitIsNotWrittenAndTheOldOneStays shows the refusal is intended; the mismatch with the parser limits is not tested.

### Spec conflicts (report only)

- **untrusted-program-S1** · `core/program/src/main/kotlin/io/github/katznmeik/cinexperience/core/program/net/ProgramDownloader.kt:216` · spec §11.1 Network & security, rule 2 ("One request only: GET https://www.yorck.de/en/films, with no parameters") and rule 3: `resolveRedirect` rebuilds a redirect target from the Location header's path and its query (`val query = target.rawQuery?.let { "?$it" }.orEmpty()`), and `fetch` then requests it. So after a redirect the app can send a GET to another path on www.yorck.de with parameters chosen by the server (never from the owner's data; host, scheme, port and user info are checked, at most 3 redirects). ProgramDownloaderTest.redirectTargetsAreRebuiltFromTheirCheckedParts shows this is deliberate. Rule 3 only refuses redirects away from https://www.yorck.de, so the two rules can be read either way; rule 2's literal text says no parameters.

### Refactoring proposals

- **Check the compiled release classes instead of (or in addition to) source text** (effort M)
  - Gain: A build task that reads the class files of the app's own modules on the release runtime classpath and fails on references to java.net (except URI), javax.net, android.net (except Uri), android.webkit, android.media (except ExifInterface), android.system and socket channels outside the core.program.net package. It sees exactly what is packaged, so it closes both scan findings at once: method references, implicit receivers, folders the text scan skips, relocated modules and included builds all end up as class files it reads. The text scan can then shrink to manifests, resources and build files.
  - Touches: build-logic (a new task type next to CheckDependencyGroupsTask), app/build.gradle.kts (register it and hang it on preBuild or the unit tests), NetworkConfinementTest (drop or keep the source rules as a fast first check), CLAUDE.md wording.
  - Risk: Needs the release classes compiled before the check, so the check moves later in the build and costs build time. Must be limited to project components (Maven libraries reference these packages legitimately and are covered by the group allowlist). Reflection with built-up strings still escapes, as it does today.

### Questions (not confirmed)

- Is the CI `-debug` APK (built when the keystore secret is absent) ever installed on the owner's phone? `:app:checkReleaseDependencyGroups` checks only `releaseRuntimeClasspath`, so a `debugImplementation` library from a group outside the allowlist is caught only by the name denylist in NetworkConfinementTest (BUILD_RULE), which does not list every network or HTML library.
- Android's built-in HTTP client can re-send a GET on its own (stale pooled connection, next IP address) without `isAllowed` being asked again. I could not confirm the platform's behaviour from the repository. Is that accepted as part of the one request, or should the transport disable it where the API allows?

### Coverage stated by the reviewer

Verified by reading only; no Gradle run. The scan-gap samples were checked by running the test's regexes in Python on one line per construct.

Result for the core of the area: I found no defect in the download, consent gate, redirect handling, size and time limits, watchdog, command/delete/switch-off races, parser bounds and field checks, start-time offsets, ProgramTime, links, cache handling, error handling (Errors included), logging (none), manifests, network security config, link opening (https://www.yorck.de/ prefix enforced in BrowserLinks.open) or isolate() use. The findings concern the enforcement scan and one limit mismatch.

Read fully:
- core/program main: net/ProgramTransport.kt, net/HttpsProgramTransport.kt, net/ProgramDownloader.kt, ProgramRepository.kt, DefaultProgramRepository.kt, ProgramSettings.kt, ProgramCache.kt, ProgramPageParser.kt, Program.kt (with ProgramTime), ProgramRules.kt (with ProgramText), ProgramLabels.kt, ProgramLinks.kt, YorckCinemas.kt, VisitPrefill.kt, AndroidManifest.xml, build.gradle.kts.
- core/program tests: NetworkConfinementTest.kt (all 798 lines), BuildLogicScanTest.kt.
- build-logic: FileDependencies.kt, CheckDependencyGroupsTask.kt, ProjectConfig.kt, GitBuildIdValueSource.kt, the Application, Library, Feature and JvmLibrary convention plugins, both settings files, convention/build.gradle.kts.
- app: build.gradle.kts, AndroidManifest.xml, res/xml/network_security_config.xml, backup_rules.xml, data_extraction_rules.xml, NetworkSecurityConfigTest.kt, AppPermissionsTest.kt.
- core/data AndroidManifest.xml (the third and last manifest).
- feature/program: ProgramComponents.kt, ShowingActions.kt, ProgramTexts.kt, ProgramLookup.kt, ProgramFilterStore.kt.
- scripts/maven-central-mirror.init.gradle; docs/SPEC.md §6.9, §11.1 and the Program lines of §8.

Skimmed (targeted sections or grep):
- feature/program ProgramViewModel.kt (downloads, onOpened, link handling), FilmViewModel.kt, ProgramScreen.kt, FilmScreen.kt, ProgramDialogs.kt, ProgramListing.kt (string templates and isolate only).
- feature/cinemas NowShowingSection.kt (row and title), feature/backup SettingsViewModel.kt (program commands, by grep), feature/visits prefill title cap (by grep).
- app AppContainer.kt (program wiring, scope).
- core/testing FakeProgramRepository.kt (lines 1-140).
- Test names only of ProgramDownloaderTest, HttpsProgramTransportTest, DefaultProgramRepositoryTest, ProgramWriteRaceTest, ProgramCacheTest; RealProgramPageTest body.

Not read:
- ProgramPageParserTest, ProgramPageParserEntryTest, ProgramModelTest, ProgramLinksTest, YorckCinemasTest, VisitPrefillTest, TestPages, FakeTransport, FakeHttpsConnection, the films-page.html fixture, and the bodies of the tests listed above.
- feature/program ProgramFilter.kt, ProgramNavigation.kt and the filter logic in ProgramListing.kt.
- CinexApp.kt wiring; the MergedManifest helper; BackupRulesTest.
- The Compose, Room, Roborazzi and Robolectric convention plugins; root build.gradle.kts; gradle.properties; libs.versions.toml beyond SDK and library versions.
- scripts/setup-android-sdk.sh; CI workflows.

## untrusted-ticket-privacy: Untrusted input and privacy: ticket PDF, membership card, intents, notifications

### untrusted-ticket-privacy-1 · 🟡 nice-to-have · Membership card screenshot read crashes on OutOfMemoryError instead of showing a message

- **Location:** `feature/tickets/src/main/kotlin/io/github/katznmeik/cinexperience/feature/tickets/membership/EditMembershipCardViewModel.kt:162`
- **Problem:** readImage() reads up to 20 MB into memory inside a try that catches only CancellationException and Exception, so an OutOfMemoryError from that read escapes viewModelScope and crashes the app.
- **Failure scenario / cost:** The owner picks an image close to the 20 MB limit on a phone with a small or already full heap. TicketFileBytes.readAtMost grows a ByteArrayOutputStream by doubling (a 32 MB array while the 16 MB one is still held, then a 20 MB toByteArray copy, about 50 MB at the peak). An OutOfMemoryError there is an Error, not an Exception: it passes the catch at line 162, leaves the coroutine launched at line 141 and ends the process. The ticket import, which reads only 2 MB, does catch it (TicketImportViewModel.kt:203 and :215), and MembershipCardImageReader.read catches it for the decoding step, so only this read is unguarded. A normal screenshot (1-3 MB) is not affected, hence 'nice'.
- **Rule or spec reference:** docs/SPEC.md §11.4 Security & privacy rule 1 and rule 4 ('the app shows a message and never crashes')
- **Proposed fix** (local, effort S): In readImage(), also catch OutOfMemoryError around the file read and return CardImageReadResult.Refused(CardImageError.TOO_LARGE), as TicketImportViewModel.readFile does. Add a ViewModel test with a TicketFileSource whose stream throws OutOfMemoryError.
- **Reviewer's evidence:** Read EditMembershipCardViewModel.kt fully: onImageChosen (l.137-152) launches readImage (l.155-166); the try at l.156-163 catches CancellationException and Exception only. Read TicketFileBytes.readAtMost (TicketFiles.kt:100-112): ByteArrayOutputStream plus toByteArray, no OOM handling. Compared with TicketImportViewModel.readFile (l.169-208, catches OutOfMemoryError at l.203) and MembershipCardImageReader.read (catches OutOfMemoryError). Did not run it.

### untrusted-ticket-privacy-2 · 🟡 nice-to-have · Booking number, QR text and the card's typed values are printed by generated toString() of plain-String carrier classes

- **Location:** `feature/tickets/src/main/kotlin/io/github/katznmeik/cinexperience/feature/tickets/membership/EditMembershipCardViewModel.kt:58`
- **Problem:** BookingNumber, QrModules, MembershipCard and TicketPdfField.Found hide their values in toString(), but several data classes carry the same values as plain Strings and print them.
- **Failure scenario / cost:** No value is logged today: I found no Log call in any feature module or in core:ticket, and none of these objects is put into an exception message. The cost is a future one: the first debug log, crash message or test failure output that interpolates one of these objects prints exactly what the model types were written to keep out of logs.
- EditMembershipCardUiState (name, memberNumber, membershipType): the owner's name and membership number.
- AddVisitRoute (feature/visits/.../VisitsNavigation.kt:55-68: bookingNumber, qrModules as encode() text) and TicketDraftData (feature/visits/.../edit/VisitDraft.kt:132-136): the booking number and the QR code text.
- PrintedSeat (core/ticket/.../TicketPdfResult.kt:126) and the internal TextSegment (core/ticket/.../text/TextLayout.kt:13, any text of the PDF, the booker's name included).
- **Rule or spec reference:** CLAUDE.md 'Ticket PDF' ("no toString() shows a value"), 'Membership card' ("toString() shows no value"), 'Reminders' ("No logs with visit data"); BookingNumber.toString and MembershipCard.toString in core:model
- **Proposed fix** (local, effort S): Give EditMembershipCardUiState, AddVisitRoute, TicketDraftData, PrintedSeat and TextSegment a toString() that names which fields are set but not their values (as MembershipCard.hidden does), or make them non-data classes where equality isn't needed. Add one test per class asserting the invented value doesn't appear in toString().
- **Reviewer's evidence:** Listed every 'override fun toString' in main sources (Ticket, BookingNumber, QrModules, MembershipCard, QrModuleGrid, TicketPdfField.Found only). Read the declarations of the classes named above: all are data classes without an override. Checked all Log.* calls in main sources (only core:data, with ids and counts) and that TicketPdfException messages hold no file text.

### untrusted-ticket-privacy-3 · 🟡 nice-to-have · TicketPdfReader.read(InputStream) duplicates the app's capped reader without its guard and is unused in production

- **Location:** `core/ticket/src/main/kotlin/io/github/katznmeik/cinexperience/core/ticket/TicketPdfReader.kt:36`
- **Problem:** The public stream overload has its own read-at-most loop, which only tests call; the app reads through TicketFileBytes.readAtMost, which has an extra guard this loop lacks.
- **Failure scenario / cost:** The loop at l.39-43 ends only on -1 or once more than 2 MB were read. A stream that keeps returning 0 (a misbehaving provider, the case TicketFileBytes.MAX_EMPTY_READS exists for) spins forever on the calling thread. The app never calls this overload (DefaultTicketReader uses read(ByteArray)), so nothing fails today; the cost is two size-capping loops for untrusted streams that can drift apart, and a public entry point that looks like the safe one but isn't the tested production path.
- **Rule or spec reference:** CLAUDE.md 'Ticket import' (at most TicketPdfLimits.MAX_FILE_BYTES, in memory only); spec §11.2 rule 1
- **Proposed fix** (local, effort S): Remove read(InputStream) and let its two tests use read(ByteArray), or move the one capped reader (with the empty-read guard) into core:ticket and have TicketImportViewModel, EditMembershipCardViewModel and this overload share it.
- **Reviewer's evidence:** Read TicketPdfReader.read(InputStream) (l.36-46) and TicketFileBytes.readAtMost (feature/tickets/.../importing/TicketFiles.kt:100-112). Grepped callers: production code only calls TicketPdfReader().read(bytes) (TicketFiles.kt:75); read(InputStream) appears only in TicketPdfReaderTest.kt:140 and TicketPdfRobustnessTest.kt:94-103.

### untrusted-ticket-privacy-4 · 🟡 nice-to-have · .gitignore protects tickets, cards and saved yorck.de pages, but not the seat-plan crawl data

- **Location:** `.gitignore:15`
- **Problem:** CLAUDE.md says the yorck.de crawl (hall-<name>.seatplan.json, .dom.json) and its screenshots are never committed, but no ignore rule covers them, unlike the three other kinds of never-commit material.
- **Failure scenario / cost:** Someone puts the crawl folder inside the working tree to run scripts/seed/yorck_seatplan_to_layout.py <crawl> <seed> <fixture>, then runs 'git add -A': the copyrighted crawl files are staged with nothing to stop them. Real tickets (/tickets/, *.ticket.pdf), cards (/cards/, *.card.*) and pages (yorck-pages/, *.yorck.html) each have a rule for exactly this mistake.
- **Rule or spec reference:** CLAUDE.md 'Generating seed layouts' ("never commit it", "Don't commit crawl data or screenshots")
- **Proposed fix** (local, effort S): Add '*.seatplan.json', '*.dom.json' and an anchored folder such as '/yorck-crawl/' to .gitignore, with a comment like the others. Check that the invented test fixtures are written to temp folders only (test_yorck_seatplan_to_layout.py does that).
- **Reviewer's evidence:** Read .gitignore fully; grepped it and scripts/seed for 'seatplan', 'dom.json' and 'crawl' (only the generator's tests, which write into temp dirs). 'git ls-files' shows no tracked PDF, JPEG, keystore or crawl file.

### Refactoring proposals

- **One plain-text cleaner in core:model instead of identical copies in core:program and core:ticket** (effort S)
  - Gain: ProgramText.clean/length (core/program/.../ProgramRules.kt:49-84) and TicketText (core/ticket/.../TicketText.kt) are the same code line for line; TicketText's comment says it was copied to avoid depending on the network module. Both modules already depend on core:model, which also holds MembershipCardText.clean and TicketSeat.isCleanLabel with overlapping character classes. One implementation means a change to the sanitising rule for untrusted text (e.g. another character class to drop) can't be applied to the program and forgotten for tickets.
  - Touches: core:model (new object, pure JVM), core:program ProgramRules.kt, core:ticket TicketText.kt and their tests; MembershipCardText could delegate its character classes to it.
  - Risk: Low: pure functions with existing tests on both sides. Keep MembershipCardText's different whitespace behaviour (it drops line separators and doesn't collapse spaces) as its own function.
- **One QR grid type: let core:ticket return core:model's QrModules** (effort S)
  - Gain: QrModuleGrid (core:ticket) and QrModules (core:model) are the same class twice (size, BooleanArray copy, isDark, equals, hidden toString, and the list 21/25/29/33 as QR_SIZES and SIZES). feature:tickets converts between them in two places, each with a try/catch for an IllegalArgumentException that can only happen if the two size lists drift (TicketFiles.kt:84-88, MembershipCardFiles.kt:25-32); the card's catch would then report NO_QR_CODE for a valid code.
  - Touches: core:ticket (QrGridReader, QrLocator, TicketPdfContent.qrCode, CardImageResult), feature:tickets TicketFiles.kt and MembershipCardFiles.kt, core:ticket tests.
  - Risk: Low to medium: QrModuleGrid's internal constructor documents 'only a checked grid is ever created', while QrModules is public and also built from stored text. If that guarantee should stay visible in the type, keep QrModuleGrid as a thin wrapper around QrModules instead of a copy.

### Questions (not confirmed)

- MainActivity is exported and hosts the NavHost. What stops another app from opening TicketRoute(visitId), EditVisitRoute or AddVisitRoute with its own ticket arguments through the extras 'android-support-nav:controller:deepLinkIds'/'deepLinkArgs' is a check inside Navigation 2.10.2, not app code. I confirmed it in the cached library's bytecode: NavController.handleDeepLink reads those extras only if shouldTrustIntent() holds (no EXTRA_REFERRER extras, and the calling package or referrer authority equals the app's package), and intent data is matched only against declared deep links (none), not the internal route link. No app test pins this, so the reminder token's promise ('another app's intent opens nothing') depends on it. Should an activity test launch MainActivity with those extras and assert the start screen, so a library change can't silently reopen it?
- The ticket import passes the provider's URI through a navigation argument (ImportTicketRoute.uri). All tests use 'content://com.example.mail.attachments/ticket/1'. Real attachment URIs carry percent-escapes, '@' in the path and a query string. By reading the Navigation 2.10.2 bytecode I believe the round trip is exact (Uri.encode once, getQueryParameters decodes once), but I could not run it. Has a share from the real mail app been checked on a device, and should TicketImportNavigationTest get such a URI?
- Android Auto Backup and device transfer include the whole database, so the ticket table's booking numbers and QR modules leave the phone that way, while the app's own backup file deliberately leaves the QR modules out and the membership card is excluded from Auto Backup. Spec §8 says Auto Backup covers the database, so I did not report it; is that asymmetry intended for the QR modules?

### Coverage stated by the reviewer

Result: no critical or important problem found in this area; four small findings, all 'nice'. Nothing was run (no Gradle); everything was verified by reading.

Read fully:
- docs/SPEC.md §4.7, §4.8, §6.10-6.13, §8, §11.2-11.4.
- core:ticket main sources, all 24 files: TicketPdfReader, TicketPdfLimits, TicketPdfResult, TicketFields, TicketPdfRules, TicketText, QrModuleGrid; pdf/ (PdfDocument, PdfObjectParser, PdfLexer, PdfObjects, Inflate, ParseBudget); text/ (ContentInterpreter, PdfFont, ToUnicodeCMap, TextLayout); qr/ (QrGridReader, QrCodewords, QrFormat, LuminanceImage, BitmapLuminanceDecoder); card/ (MembershipCardImageReader, QrLocator, BitmapCardImageDecoder).
- feature:tickets: importing/ (TicketIntents, TicketFiles, TicketImportViewModel, TicketMatching, TicketImportScreen), TicketsNavigation, ScreenBrightness, membership/ (MembershipCardFiles, EditMembershipCardViewModel, MembershipCardNavigation, MembershipCardViewModel, EditMembershipCardScreen).
- app: MainActivity, IncomingTicketViewModel, CinexApplication; reminders/ ReminderIntents, ReminderIntentToken, ReminderReceivers, ReminderNotifier, Reminders; CinexApp.kt lines 60-371.
- All three AndroidManifest.xml files, backup_rules.xml, data_extraction_rules.xml, network_security_config.xml, visit_photo_paths.xml, .gitignore.
- core:data: MembershipCardStore.kt; BackupReader.kt (zip unpacking, limits, validator).
- core:model: Ticket.kt, MembershipCard.kt, VisitPrefillArgs.kt, TicketDraft.kt.

Checked and found sound: the PDF limits on repeated references and error paths; read() catching TicketPdfException, OutOfMemoryError, StackOverflowError and RuntimeException; QR checks without content decoding; image bounds first, subsampling and the post-decode size check; content:-only URIs with the own-provider refusal (prefix check and resolveContentProvider); size caps while reading, nothing written to disk; immutable, explicit PendingIntents; the restart receiver's action check; the token (constant-time compare, never handed out unless stored); the notification's public version; Auto Backup exclusions (BackupRulesTest read); zip entry names, traversal check, per-entry and total caps; static user-facing error messages; no URI, booking number or card value in any Log call.

Skimmed or grepped only:
- ReminderScheduler.kt (alarm intents lines 60-135, PendingIntent flags); AppContainer.kt (file source and reader wiring).
- BackupFormat.kt (isFilePath, BackupLimits); RoomTicketRepository (restoreQrModules, findVisitId).
- feature:visits: VisitsNavigation.kt (AddVisitRoute) and VisitDraft.kt (TicketDraftData).
- feature:backup SettingsScreen (the ticket picker); ProgramRules.kt (ProgramText).
- TicketViewModel, and test file names plus the URIs the tests use.

Not read:
- ReminderCoordinator, ReminderRuntimes, ReminderSettingsStore.
- TicketScreen, QrCode.kt, MembershipCardScreen.
- VisitEditViewModel's handling of the ticket draft; BackupRestorer, BackupWriter, RoomBackupRepository, BackupStorage.
- The test sources themselves (TicketFieldsScopeTest, ResourceBudgetTest and the robustness tests were not opened), so 'each limit has a test' is unverified. The booker-name rules in TicketFields were traced by reading only, without a real ticket.

Outside the repository, read-only: I unpacked navigation-runtime and navigation-common 2.10.2 from the Gradle cache into the scratchpad and read NavController.handleDeepLink, shouldTrustIntent and NavDeepLink argument decoding with javap.

## lifecycle: Lifecycle and concurrency: reminders, ViewModels, repositories

### lifecycle-1 · 🟠 important · Visit form: a photo chosen or taken while the app's process was killed is silently dropped after the restore

- **Location:** `feature/visits/src/main/kotlin/io/github/katznmeik/cinexperience/feature/visits/edit/VisitEditViewModel.kt:390`
- **Problem:** After process death the picker's result reaches the restored form while it is still Loading, and both photo paths ignore a result unless the form is Ready.
- **Failure scenario / cost:** Add or edit a visit, tap "Choose photo", and Android kills the app's process while the photo picker is in front (or "Don't keep activities" is on). Pick a photo. The form comes back with every field restored but without the photo and without a message. This case is deterministic.

The camera case is a race. onCameraResult starts the import, but the imported photo is applied through editForm, which also requires Ready. If decoding the capture finishes before the cold database has delivered the cinemas, the photo is not put on the form. The capture file is deleted, and the imported copy stays in sessionPhotos until Save or Discard deletes it.

The class comment promises the opposite: "the edit also survives process death (e.g. while the camera app is in front)".
- **Rule or spec reference:** CLAUDE.md, App-private files (a restored visit form keeps its unsaved photos); VisitEditViewModel class comment
- **Proposed fix** (local, effort S): Do not gate activity results on Ready. Keep a result that arrives while the status is Loading (a pending picked URI, or the imported path) and apply it when derived() switches to Ready. In importPhoto, set the photo with a state update that only checks !isLeaving instead of editForm.

While there: restore() should set visitLoaded = false. For Add mode it starts true, so the comment at line 213 ("stays loading until its photos are checked") does not hold.

Add a test with a cinema repository that emits late. The existing restore tests run on UnconfinedTestDispatcher with fakes that emit synchronously, so the form is Ready at once and the gap is invisible.
- **Reviewer's evidence:** VisitEditViewModel.kt: status starts Loading and becomes Ready only in derived() (lines 723, 736) once cinemasLoaded is set by the observeCinemas collector (line 283). That collector reads a Room flow with flowOn (RoomRepositories.kt:70-76 prepared()), so it is always asynchronous. restore() (197-215) does not change that.

onGalleryPhotoPicked (390-397) returns when !canChangePhoto(), and canChangePhoto (449) requires status == Ready. onCameraResult (432-441) calls importPhoto (451-466), which calls editForm; editForm (710-711) returns unless Ready.

VisitEditScreen.kt:173-178: the launchers are registered with rememberLauncherForActivityResult in the same composition that creates the ViewModel (VisitsNavigation.kt composable<AddVisitRoute>). ActivityResultRegistry hands a pending result to the callback synchronously at registration, so the callback runs before any database emission can reach the main thread.

VisitEditViewModelTest.theEditSurvivesProcessDeathDuringTheCameraHandOff (544-577) passes only because of the unconfined dispatcher and synchronous fake. EditMembershipCardViewModel does not have the problem: it restores isLoading = false synchronously from saved state.

### lifecycle-2 · 🟠 important · A reminder tap can open a second form of the same visit above a stale one; saving the lower form later overwrites the newer data

- **Location:** `app/src/main/kotlin/io/github/katznmeik/cinexperience/ui/CinexApp.kt:143`
- **Problem:** The "already shown" check for a tapped reminder looks only at the top back stack entry, and the visit form loads its visit once and never notices that the stored visit changed.
- **Failure scenario / cost:** 1. The showtime reminder arrives; tapping its body opens the visit form (EditVisit V).
2. "Show ticket" at the entrance puts Ticket V on top of the form.
3. After the film, tapping "Rate your visit" pushes a second EditVisit V, because the top entry is the ticket.
4. Rate, maybe add a photo, Save. The second form closes.
5. Back from the ticket shows the first form with the data from before: no ratings, Save enabled.

Tapping Save there writes the whole stale row. The ratings and note just entered are gone. A photo added in step 4 becomes unreferenced and the start-up sweep deletes it after 7 days.

It also happens after process death in between: the lower form is restored from its saved draft (written right after loading), not from the database. Two windows on the same visit (a share window and the main window) have the same lost update.
- **Rule or spec reference:** spec §11.3 rule 7 (tapping the notification opens the visit); CLAUDE.md Reminders ("only navigate … not again if that screen is shown")
- **Proposed fix** (local, effort M): Make the Edit form notice a changed stored visit. Keep the visit as loaded and observe visitRepository.observeVisit(id). If it changes while the form has no unsaved changes, replace form and baseline. If it changes while there are unsaved changes, say so at Save (reload or overwrite) instead of writing silently.

Smaller alternative that covers only the reminder path: in CinexApp, if an EditVisitRoute for that visit is already in the back stack, pop back to it instead of pushing a second one.

Add a test for the sequence form → ticket → reminder.
- **Reviewer's evidence:** CinexApp.kt:140-147 navigates unless currentBackStackEntry.shows(target); shows() (349-356) compares only the current entry. ReminderIntentTest.theTicketShownAlreadyIsNotOpenedTwiceButAnotherScreenOpensOnTop (lines 186-199) confirms the form opens on top of the ticket.

ReminderNotifier.kt:157: the content intent of both reminder kinds is openVisit. The form's "Show ticket" pushes TicketRoute above the form (VisitsNavigation.kt onShowTicket; CinexApp.kt:242).

VisitEditViewModel.kt: loadVisit (260-278) is a one-shot getVisit followed by persist(), so every Edit form has a draft. init (162-166) prefers that draft over the database. save() (479-491) writes form.toVisit(visitId).

VisitEditUiState.kt:152: canSave does not require changes. RoomRepositories.kt:326-349 saveVisitInTransaction updates the whole row.

The layout editor already detects stale edits (updateHall → STALE_EDIT); the visit form has no counterpart.

### lifecycle-3 · 🟡 nice-to-have · The visit form and the export file name read the system date directly instead of AppContainer.clock

- **Location:** `feature/visits/src/main/kotlin/io/github/katznmeik/cinexperience/feature/visits/edit/VisitEditViewModel.kt:105`
- **Problem:** VisitEditViewModel's default date comes from today = LocalDate::now (device time zone, system clock), and its factory passes the container's clock but never a today.
- **Failure scenario / cost:** The default date of a new visit, and the fallback date of a restored draft, is the device's local date. Upcoming visits, the Next visit card and reminders use Berlin's date from the injected clock. With the phone in another time zone around midnight, a new visit defaults to a date that is yesterday or tomorrow in Berlin.

In the app tests FakeAppContainer's fixed clock (SampleProgram.FETCHED_AT) does not reach the form, so anything that shows the form's default date depends on the day the test runs.

SettingsViewModel.today (line 123, used for the export file name) has the same default; there it only affects the suggested file name.
- **Rule or spec reference:** CLAUDE.md, DI: "Screens that read the time get AppContainer.clock"
- **Proposed fix** (local, effort S): In VisitEditViewModel.factory pass today = { ShowingTime.today(clock.instant()) }, or drop the today parameter and derive it from clock. Do the same for SettingsViewModel by passing a clock to its factory from settingsScreen.
- **Reviewer's evidence:** VisitEditViewModel.kt:104-105 (clock and today are separate parameters), line 116 (date = prefill date ?: today()), lines 199 and 206 (fallbackDate = today()), factory 810-825 (passes clock only). SettingsViewModel.kt:123 and 172.

A search of main sources for now() and ::now finds only these two; every other screen takes container.clock (CinexApp.kt).

### Refactoring proposals

- **Split the photo session and the stale-data handling out of VisitEditViewModel** (effort M)
  - Gain: VisitEditViewModel is 829 lines and holds form state, baseline, draft persistence, the photo session (sessionPhotos, pendingCapture, import, clean-up), the ticket draft and the Ready gating in one class. Both important findings sit where these meet. A small VisitPhotoSession (owns session photos, the pending capture and queued activity results, with its own saved-state part) and a loaded-visit snapshot with change detection would make each rule testable on its own with late-emitting fakes.
  - Touches: feature/visits edit/ (VisitEditViewModel, VisitDraft, VisitEditScreen wiring) and their tests; no repository or schema change.
  - Risk: Medium. The saved draft format must stay readable (VisitDraft is decoded with ignoreUnknownKeys), and the photo clean-up rules (delete on save, discard, delete visit; the 7-day sweep) must stay exactly as they are. The existing in-flight and restore tests cover most of it.
- **One helper for the SharedPreferences-backed single-value stores** (effort S)
  - Gain: ReminderSettingsStore and MembershipCardStore repeat the same pattern (read once, Mutex, commit on IO, publish into a MutableStateFlow); ProgramFilterStore, ProgramSettings and LastExportStore are close. A shared helper would give one place for the commit-result handling and for making commit plus in-memory publish atomic against cancellation (see the first question).
  - Touches: core/data settings/ (two stores), optionally feature/program ProgramFilterStore; their tests and the fakes in core:testing stay as they are.
  - Risk: Low. Behaviour and file formats stay the same; the stores are small and well tested.

### Questions (not confirmed)

- MembershipCardStore.write (core/data/.../settings/MembershipCardStore.kt:82-98) and ReminderSettingsStore.update (ReminderSettingsStore.kt:63-84) set the in-memory value after withContext(io) { commit }. If the caller is cancelled while the commit runs, the disk has the change but the store keeps the old value until the process restarts (the store reads once per process). I could not construct a realistic trigger: the calling ViewModels outlive Back by the 300 ms transition. Is it worth wrapping commit and publish in NonCancellable anyway?
- BackupValidator accepts any ISO date for a visit (BackupReader.kt:173, LocalDate.parse only), while the form assumes 1900–2100 (VisitDates.YEARS, used as the date picker's yearRange at VisitEditScreen.kt:554). loadVisit puts the stored date into the form unchecked (VisitForm.of). Visit.qrModulesExpireAt throws for dates within days of LocalDate.MAX. Should the validator and saveVisit bound the year? I did not verify what Material's date picker does with an initial date outside yearRange; this needs crafted or damaged backup data.
- ProgramViewModel.onOpened (ProgramViewModel.kt:275-285) recognises a new open of the tab by object identity of the back stack entry. After an activity recreation (system dark mode or font size change; orientation is locked) the entry object is new while the ViewModel survives, so the temporary "Show all in Program" filter is dropped and "Not now" is reset. Is that acceptable? Traced by reading only, not run.
- The restored visit form's saved draft wins over the database even when the form had no unsaved changes (VisitEditViewModel.kt:162-166; the draft is written right after loading). Is that intended, or should a pristine form reload from the database after process death? This is the process-death half of the second finding.

### Coverage stated by the reviewer

Everything was verified by reading; Gradle and the tests were not run, and nothing in the repository was changed.

Read fully:
- app: CinexApplication, MainActivity, IncomingTicketViewModel, di/AppContainer, ui/CinexApp, ui/BackupImportLock, and all of reminders/ (ReminderCoordinator incl. leadingDebounce, ReminderScheduler incl. ReminderRetry, ReminderAlarms and AlarmReminderScheduler, ReminderReceivers, Reminders, ReminderNotifier, ReminderIntentToken, ReminderIntents incl. IncomingReminderViewModel, ReminderRuntimes).
- core/model: Reminders.kt, ShowingTime, UpcomingVisits.
- core/data: DataContainer, RoomRepositories, RoomTicketRepository, RoomBackupRepository, ReminderSettingsStore, MembershipCardStore, VisitPhotoStore, BackupRestorer (replace path).
- core/program: DefaultProgramRepository, ProgramRepository, ProgramDownloader, ProgramSettings.
- core/ui: flow/Flows, CloseOnce, DropUnlessResumed, ResultMessage, LoadedImage, MessageSnackbar.
- ViewModels: VisitEdit, TicketImport, Settings, ReminderSettings, EditMembershipCard, MembershipCard, NextVisit, AllVisits, ManageCinemas, plus CinemaEditor.
- Spec §11.3; VisitsNavigation, TicketsNavigation.

Read in part:
- ViewModel class bodies only (not the state types above them): LayoutEditor (not lines 330-400), HallDetail, CinemaDetail, Program (from line 150), Film, Rankings, Cinemas, Ticket.
- Entry composables only: VisitEditScreen, SettingsScreen, TicketImportScreen, ProgramScreen.
- CinemasNavigation, ProgramFilterStore, TicketIntents, ScreenBrightness, ProgramCache (read, write, delete), Seeder (entry point and class comment), CinexDatabase, Daos (ticket queries), ReferenceImageStore (import), the manifest.
- Tests: VisitEditRestoreTest, parts of VisitEditViewModelTest and ReminderIntentTest.

Not read:
- CinexTransitions, TopLevelDestination.
- BackupReader, BackupWriter, BackupFormat, BackupStorage (beyond single lines), LayoutJson, Mappers, Entities.
- ProgramPageParser, HttpsProgramTransport, ProgramTransport.
- core:ticket, core:seatmapui, most composable screens.
- The fakes in core:testing, so Room-versus-fake parity is unchecked.
- The reminder tests, apart from one section.

Areas where I found nothing to report: goAsync handling (finish always runs; 50 s / 8 s timeouts), ReminderRetry pauses and the stored failure count, exact-alarm fallback, passed alarms never cancelled, markPosted/isPosted under the scheduler's lock, the coordinator's debounce, single run and unlimited retry, intents handled once (savedInstanceState, launched-from-history, onNewIntent), the import lock and isImporting never sticking, NonCancellable sections in the backup and program repositories, CancellationException handling in catch blocks, double-save and double-navigation guards, and the start-up sweeps.

## architecture: Architecture and maintainability across all modules

### architecture-1 · 🟠 important · The plain-text rule exists in five places with three behaviours; the label checks miss format characters outside the BMP

- **Location:** `core/model/src/main/kotlin/io/github/katznmeik/cinexperience/core/model/SeatLayout.kt:116 (isValidFixedLabel); core/model/.../Ticket.kt:134 (isCleanLabel); core/model/.../MembershipCard.kt:63 (MembershipCardText); core/program/.../ProgramRules.kt:49 (ProgramText); core/ticket/.../TicketText.kt:13`
- **Problem:** "No control or format characters" is implemented five times: ProgramText.clean and TicketText.clean are byte-identical copies with no test tying them together, MembershipCardText.clean is a third variant, and the two label checks in core:model test UTF-16 chars instead of code points.
- **Failure scenario / cost:** What differs today: (1) isValidFixedLabel("R󠁁") (R + U+E0041, an invisible Cf tag character, 3 chars) and a label with a lone surrogate are both accepted, because Character.getType(char) of a surrogate is SURROGATE, not FORMAT; ProgramText/TicketText remove both. Such a label can enter through a backup's layout JSON (seatLabels) or a backup ticket's seats (BackupValidator -> TicketSeat.isValidRow/isValidSeat), and then looks like "R" on the map but never matches "R" in seatOf. (2) MembershipCardText drops a tab or U+2028 ("A\tB" -> "AB") and keeps runs of spaces, where the other two turn them into one space. (3) ProgramRules.kt:46 says "Same character classes as SeatLayout.Seat.isValidFixedLabel", which is not true for (1). Cost: spec 11.2 rule 2 binds the ticket's text rule to the program's; a change to one copy (e.g. also dropping private-use characters) silently leaves the other four behind.
- **Rule or spec reference:** Spec 11.1 rule 7, 11.2 rule 2; CLAUDE.md "Seat numbering" (fixed label: no control or format characters), "Ticket PDF" (cleaned like the program's texts)
- **Proposed fix** (refactoring, effort M): Put one code-point based rule into core:model (both core:program and core:ticket already depend on it; TicketText's KDoc only rules out depending on the network module), e.g. PlainText.clean / PlainText.isClean, and let ProgramText, TicketText, isValidFixedLabel and TicketSeat.isCleanLabel delegate to it. Keep MembershipCardText's stored-value rule as it is (or only widen it), and decide separately whether stored labels may be tightened: a stricter isValidFixedLabel would make a stored layout with such a label unreadable, and a stricter MembershipCardText.isValid would make a stored card count as none.
- **Reviewer's evidence:** Read ProgramRules.kt:42-84, TicketText.kt (whole file, identical clean/isSpace/isRemoved bodies), MembershipCard.kt:63-88, SeatLayout.kt:111-120, Ticket.kt:121-139, BackupReader.kt:194-205 (ticket seats checked only with TicketSeat.isValidRow/isValidSeat). Searched all modules for Character.FORMAT/getType/isISOControl: no other implementation, and no test references both ProgramText and TicketText. Confirmed on the JVM with a scratch Java file outside the repo: for "R󠁁" the char-level check passes, Character.getType(codePoint)=16 (FORMAT), Character.getType(high surrogate char)=19 (SURROGATE); a lone surrogate passes too.

### architecture-2 · 🟠 important · The production composition root and its start-up duties are executed by no test

- **Location:** `app/src/main/kotlin/io/github/katznmeik/cinexperience/di/AppContainer.kt:124-203 (DefaultAppContainer, onAppStart); app/src/test/kotlin/io/github/katznmeik/cinexperience/ui/TabScreenshotTest.kt:61-95 (FakeAppContainer)`
- **Problem:** DefaultAppContainer is never instantiated in a test, and FakeAppContainer inherits the interface's empty onAppStart() and builds its own copy of the Reminders wiring, so the only code that starts the file sweep, the QR-code expiry removal, the notification channels and ReminderCoordinator.start is untested.
- **Failure scenario / cost:** A change that drops or breaks one line of DefaultAppContainer.onAppStart (e.g. the launch of reminders.coordinator.start, or data.removeExpiredQrModules(clock.instant())) or of the lazy `reminders` wiring keeps every test green: MainActivity tests run with FakeAppContainer, whose onAppStart does nothing, and the reminder tests start the coordinator themselves. The result would be reminders that are no longer planned when a visit is saved (spec 11.3 rule 4) or QR codes kept past 7 days in the database (spec 4.7), which the owner would notice only on the phone, if at all. The same applies to a new store or repository added to the container: the test container is a second hand-written wiring that has to be kept in step.
- **Rule or spec reference:** CLAUDE.md "DI", "Tickets" (removeExpiredQrModules at app start), "Reminders" (ReminderCoordinator started in onAppStart); spec 4.7, 11.3 rule 4
- **Proposed fix** (local, effort M): Add one Robolectric test in app that builds DefaultAppContainer on the test application, calls onAppStart() and checks the observable effects (an expired ticket loses its QR modules, a saved upcoming visit with reminders on gets an alarm, the two channels exist, a second onAppStart does nothing). Move FakeAppContainer out of TabScreenshotTest.kt into its own file, and let both containers build Reminders through one factory function so the wiring exists once.
- **Reviewer's evidence:** grep for DefaultAppContainer over all modules: only CinexApplication.kt and AppContainer.kt. grep for onAppStart: MainActivity.kt:50, the interface default (AppContainer.kt:121) and the override (195). Read FakeAppContainer (TabScreenshotTest.kt:61-95): no onAppStart override; it constructs ReminderCoordinator and ReminderNotifier itself, as DefaultAppContainer.kt:167-183 does. TestCinexApplication returns the fake for every MainActivity test; ProgramStorageTest builds a DataContainer directly, not the app container.

### architecture-3 · 🟡 nice-to-have · The visit form's default date and the export file name read LocalDate.now(), not AppContainer.clock

- **Location:** `feature/visits/src/main/kotlin/io/github/katznmeik/cinexperience/feature/visits/edit/VisitEditViewModel.kt:105 and :818-823; feature/backup/src/main/kotlin/io/github/katznmeik/cinexperience/feature/backup/SettingsViewModel.kt:123 and :341`
- **Problem:** Both ViewModels take `today: () -> LocalDate = LocalDate::now` and their factory never passes it, so production reads the system clock in the device's time zone next to the `clock` the same ViewModel gets from the container.
- **Failure scenario / cost:** Add visit's default date is the device's date, while every other part treats a visit's date as a Berlin date (ShowingTime.today, UpcomingVisits). With the device set to a zone east of Berlin after its midnight (e.g. Tokyo 02:00 = Berlin 19:00 the day before), a visit added with the default date and no start time is Berlin's tomorrow: ShowingTime.isUpcoming(date, null, now) is true, so it appears as an upcoming visit and on the Next visit card. In the app tests, FakeAppContainer's fixed clock (15 Oct 2026) does not reach the form: Add visit opened through CinexApp shows the day the test runs. The same silent-default pattern makes other wiring mistakes compile: `clock: Clock = Clock.systemDefaultZone()` on cinemasScreen, cinemaDetailScreen, visitEditScreens, allVisitsScreen, programScreen and filmScreen (ticketScreen and ticketImportScreen require it), `ticketRepository: TicketRepository? = null` in VisitEditViewModel (with a null repository, Save stores an imported visit without its ticket and says nothing, line 487-491), and no-op defaults for onOpenImportedVisit, onImportTicket, onOpenMembershipCard and onRunsBackupImportChanged.
- **Rule or spec reference:** CLAUDE.md "DI": screens that read the time get AppContainer.clock; "Tickets": Visit dates and start times are Berlin values
- **Proposed fix** (local, effort S): In VisitEditViewModel.factory pass `today = { ShowingTime.today(clock.instant()) }` (or drop the parameter and derive it from clock); give settingsScreen/SettingsViewModel.factory the container's clock for the file name's date (device zone is fine there). Then remove the defaults from the NavGraphBuilder functions' clock and callback parameters and make ticketRepository non-null, so a missing wire is a compile error; tests pass the values explicitly.
- **Reviewer's evidence:** A search for Instant.now/LocalDate.now/currentTimeMillis found nothing in feature code, but a search for `::now` found these two. Read VisitEditViewModel.kt:97-127 (today() gives the new form's date) and its factory at 806-826 (passes ticketRepository and clock only); SettingsViewModel.kt:123, 172 (exportFileName) and 334-342. Traced the date through ShowingTime.isUpcoming (ShowingTime.kt:37-38) and UpcomingVisits.kt:40. All feature tests pass `today = { ... }` explicitly, which is why nothing fails. Read CinexApp.kt: every existing call does pass container.clock and the callbacks.

### architecture-4 · 🟡 nice-to-have · "The visit's showing in the cached program" is implemented twice with different title rules, and the name normalisation three times

- **Location:** `app/src/main/kotlin/io/github/katznmeik/cinexperience/reminders/ReminderRuntimes.kt:23-45; feature/tickets/src/main/kotlin/io/github/katznmeik/cinexperience/feature/tickets/importing/TicketMatching.kt:128-161; core/program/src/main/kotlin/io/github/katznmeik/cinexperience/core/program/YorckCinemas.kt:39-42`
- **Problem:** ReminderRuntimes.of and TicketMatching.screeningFormat both look up the program's showings by cinema seed key and Berlin start, each with its own copy of the lookup and of `normalised(name)` (a third identical copy is private in YorckCinemas), and they treat the film title differently.
- **Failure scenario / cost:** What differs today: the import uses a single showing at that time whatever its film, and falls back to all showings when none has the ticket's title; the reminders use only showings whose film title equals the visit's title and otherwise give no runtime. So a visit imported from a ticket whose printed title differs from the title on yorck.de/en gets its screening format from the showing but not its runtime ("Rate your visit" then uses start + 2.5 h). Both behaviours are tested, so this is not a bug report; the cost is that the next consumer of the same lookup (or a change such as accent-insensitive title comparison, which ProgramFilters.normalise already does for the search) has to be made in two modules and the app module cannot be reached from feature:tickets.
- **Rule or spec reference:** Spec 11.2 "Matching", 11.3 rule 2 (see specConflicts)
- **Proposed fix** (refactoring, effort S): Add the lookup once to core:program, e.g. `Program.showingsAt(seedKey, start)` plus one public name normalisation next to YorckCinemas, and express the two title rules as small, named functions on top of it (or one rule, once the owner has decided the spec conflict).
- **Reviewer's evidence:** Read ReminderRuntimes.kt and TicketMatching.kt in full, YorckCinemas.kt in full, ProgramFilter.kt:113-121. The three normalise bodies are character-identical (`trim().split(WHITESPACE).joinToString(" ").lowercase(Locale.ROOT)`). ReminderRuntimesTest has aShowingOfAnotherFilmSaysNothingAboutTheVisitsEnd, so the stricter rule there is deliberate.

### architecture-5 · 🟡 nice-to-have · README's feature list stops at v1.1, and two KDocs describe an older app

- **Location:** `README.md:15-49; feature/tickets/src/main/kotlin/io/github/katznmeik/cinexperience/feature/tickets/TicketsNavigation.kt:26-28; app/src/test/kotlin/io/github/katznmeik/cinexperience/ui/TabScreenshotTest.kt:98`
- **Problem:** The README describes the features of v1.0 and v1.1 only, although the app is at 1.5.0, and two comments are stale.
- **Failure scenario / cost:** A reader of the README (the owner on a new phone, or an agent starting from it) learns nothing about the ticket import (Share / Open with / Settings), the ticket screen with the QR code, the two reminders with their permissions (notifications, exact alarms, boot), or the membership card; the intro still presents the Program download as the app's only special behaviour. TicketRoute's KDoc says the ticket is opened "later" by the showtime reminder, which has existed since v1.4; TabScreenshotTest's KDoc speaks of "the three tab screens" (four since v1.1).
- **Rule or spec reference:** CLAUDE.md module map and spec 2 (Goals v1.4, v1.5) describe these features
- **Proposed fix** (local, effort S): Add "Features (v1.4)" and "Features (v1.5)" sections to README.md (ticket import and QR code, reminders and what they need, membership card and that it stays on the phone and out of every backup), and correct the two comments.
- **Reviewer's evidence:** Read README.md in full: "ticket", "reminder" and "membership" occur only in the lint notes of the Development section (lines 142-146). gradle.properties has cinexVersionName=1.5.0. Checked the other documented facts I could verify by reading and found them matching the code: database version 3, BACKUP_VERSION 3, LayoutJson.FORMAT_VERSION 3, 38 seed halls, the download limits, ticket and card limits, reminder timeouts, sweep ages, transition durations, CI steps and uploads, and every identifier CLAUDE.md names (about 90 checked by search).

### architecture-6 · 🟡 nice-to-have · Every Compose module depends on the Compose preview tooling, but no source file has a @Preview

- **Location:** `build-logic/convention/src/main/kotlin/AndroidComposeConventionPlugin.kt:24-25`
- **Problem:** The compose convention plugin adds `implementation(compose-ui-tooling-preview)` and `debugImplementation(compose-ui-tooling)` to the app, core:ui, core:seatmapui, core:testing and all seven feature modules, and nothing uses them.
- **Failure scenario / cost:** ui-tooling-preview is on the release runtime classpath (R8 is off, so its classes ship in the APK), and ui-tooling adds its PreviewActivity to every debug build, including the debug APK that CI uploads when the signing secret is missing. Both are unused weight in a build whose dependency list is otherwise reviewed line by line (checkReleaseDependencyGroups).
- **Rule or spec reference:** CLAUDE.md: build configuration lives in the convention plugins; spec 11.1 rule 4 (reviewed release dependencies)
- **Proposed fix** (local, effort S): Remove the two lines and the two catalog entries (compose-ui-tooling, compose-ui-tooling-preview), then run the full check once to confirm that the Roborazzi tests do not need ui-tooling (they use ui-test-manifest, which the Roborazzi plugin adds separately). Keep them only if the owner wants Android Studio previews; then say so in a comment.
- **Reviewer's evidence:** Searched all main and test sources of every module for "@Preview", "tooling" and "Preview\b": no use (only ImportPreview, IconPreviewTest and similar names). Read AndroidComposeConventionPlugin.kt and AndroidRoborazziConventionPlugin.kt. Not verified by a build (Gradle was off limits).

### architecture-7 · 🟡 nice-to-have · Feature screens rebuild text styles by hand instead of using tokens

- **Location:** `16 places, e.g. feature/backup/.../SettingsScreen.kt:352, feature/cinemas/.../CinemaDetailScreen.kt:313, feature/program/.../ProgramComponents.kt:209, feature/seatmap/.../HallDetailScreen.kt:580, feature/visits/.../VisitFormFields.kt:124; also feature/tickets/.../TicketScreen.kt:203-208 and membership/MembershipCardScreen.kt:172-185`
- **Problem:** `MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)` is written 16 times in five feature modules although it is exactly `labelLarge` (Instrument Sans SemiBold 15/20 sp), and three other styles are copied the same way.
- **Failure scenario / cost:** Changing the row-title look means either editing labelLarge (which also changes the 17 places that use it for buttons) or finding 16 copies; a change to bodyMedium's size silently changes them but not labelLarge. The same holds for `cardTitle.copy(fontSize = titleLarge.fontSize)` (4 places in feature:tickets and feature:visits) and for the big code text of the booking number and the membership number (`scoreLarge.copy(letterSpacing = 2.sp)` with a 14.sp minimum, declared once per screen).
- **Rule or spec reference:** CLAUDE.md "Theme": all colours and text styles come from core:ui
- **Proposed fix** (local, effort S): Add named styles to CinexTextStyles in core:ui (e.g. rowTitle, cardTitleLarge, codeLarge with its minimum size) and use them; no visual change, the screenshots must stay identical.
- **Reviewer's evidence:** Counted the `.copy(` overrides of typography and textStyles in all feature, app and seatmapui main sources (16 / 4 / 4 / 3 / 2 ...); read Type.kt:84-103 to compare bodyMedium and labelLarge field by field. No hard-coded colour (Color(0x…), Color.Red …) and no font family exists outside core:ui; the sp literals are the ones listed here plus three icon sizes derived from sp on purpose.

### architecture-8 · 🟡 nice-to-have · Small clean-ups: twin constants, types in files named after something else, one dead function

- **Location:** `core/program/.../Program.kt:238 vs core/model/.../ShowingTime.kt:21; core/data/.../db/CinexDatabase.kt:105-117 vs feature/visits/.../VisitsNavigation.kt:120-127; core/data/.../backup/BackupReader.kt:110, BackupFormat.kt:102 and :307, json/LayoutJson.kt:290-304; feature/seatmap/.../detail/HallDetailViewModel.kt:179`
- **Problem:** A handful of rules exist twice without a shared source, several types sit in files whose name points elsewhere, and one ViewModel function is called only by a test.
- **Failure scenario / cost:** (1) ProgramTime.ZONE and ShowingTime.ZONE are both ZoneId.of("Europe/Berlin") although core:program depends on core:model; both KDocs repeat the same gap/overlap rule. (2) A start time's "HH:mm" text is written by StartTimes.format (core:data, internal) and again by timeArgument (VisitsNavigation), and read by a strict regex in one place and by LocalTime.parse plus isWholeMinute in three others (VisitsNavigation, VisitDraft, the reminder alarm extras); they agree today, except that LocalTime.parse also accepts "20:15:00". (3) The 200-code-point film title limit is declared three times (VisitEditViewModel.MAX_FILM_LENGTH, ProgramRules.MAX_TITLE_LENGTH, TicketPdfRules.MAX_TITLE_LENGTH). (4) BackupValidator lives in BackupReader.kt, BackupMigrations and BackupLimits in BackupFormat.kt, the seed DTOs in LayoutJson.kt and StartTimes (used by the backup too) in CinexDatabase.kt, so a search by file name misses them. (5) HallDetailViewModel.clearSelection() is used only by HallDetailViewModelTest.
- **Rule or spec reference:** CLAUDE.md "Backup" (BackupValidator holds the data rules), "Tickets" (Berlin clock time via ShowingTime)
- **Proposed fix** (local, effort S): Let ProgramTime.ZONE be ShowingTime.ZONE; move the start-time text (format, parse) into ShowingTime in core:model and use it in core:data, the route and the draft; give BackupValidator, BackupMigrations, the seed DTOs and StartTimes their own files (pure moves); delete clearSelection and its test line. The title limit can stay three constants or move to core:model's TextLimits.
- **Reviewer's evidence:** Read ShowingTime.kt and Program.kt:226-248, CinexDatabase.kt:87-117, VisitsNavigation.kt in full, ReminderScheduler.kt:111-130 (extras), the type outline of every core:data file. Dead-code scan: a script over all 208 main-source Kotlin files listed every declaration whose name occurs only once in main sources; apart from Room converters, convention plugins and the test helpers of core:testing, only clearSelection (test-only use), QrLocator.patternsOf, AlarmReminderScheduler.postedReminders and seatNumbersShownWhenFitted (test probes, documented as such) remained. No unused version-catalog entry, resource or route was found.

### Spec conflicts (report only)

- **architecture-S1** · `app/src/main/kotlin/io/github/katznmeik/cinexperience/reminders/ReminderRuntimes.kt:36-38; feature/tickets/src/main/kotlin/io/github/katznmeik/cinexperience/feature/tickets/importing/TicketMatching.kt:128-139` · spec 11.2 "Matching" and 11.3 rule 2: The spec matches "the showing in the downloaded program by cinema and start time, for the screening format and the runtime", and rule 2 takes start + runtime "when the downloaded program has the showing (11.2 matching)". The code adds the film title, in two different ways: for the runtime a visit with a film title uses only showings whose film has exactly that title (otherwise start + 2.5 h), while for the screening format the title is only a tie-break between several showings at that time. CLAUDE.md documents the reminder rule ("the visit's own film only"); the spec does not mention the title at all.
- **architecture-S2** · `feature/visits/src/main/kotlin/io/github/katznmeik/cinexperience/feature/visits/edit/VisitEditViewModel.kt:105, 116` · spec 4.4 (date "defaults to today"; startTime is Berlin clock time; upcoming visits): The spec does not say whose "today" the default date is. The code uses the device's date (LocalDate.now()), while upcoming visits, the Next visit card and the reminders compare a visit's date with Berlin's date. The two only differ when the device is not on Berlin time; the owner should say which one is meant.

### Refactoring proposals

- **One plain-text rule in core:model** (effort M)
  - Gain: The security rule "cleaned plain text, no control or format characters" (spec 11.1 rule 7, 11.2 rule 2, 4.8) exists once, by code point, with one test suite; the program, the ticket, the membership card and the layout labels can no longer drift apart.
  - Touches: core:model (new PlainText, SeatLayout.isValidFixedLabel, TicketSeat.isCleanLabel, MembershipCardText), core:program ProgramRules.kt, core:ticket TicketText.kt and their tests.
  - Risk: Tightening a validity check changes what stored data is accepted: a stored layout with a now-invalid fixed label would become unreadable, a stored membership card would count as none, and BackupValidator would refuse backups it accepts today. Keep the stored-value checks as lenient as they are (or migrate), and only unify cleaning and the checks for new input.
- **Split the three largest feature files along seams they already have** (effort M)
  - Gain: EditorCanvas.kt (973 lines) already has three parts: pure geometry and hit-testing (lines 85-383, unit-tested), the composable with its gestures (399-625, 702-800) and the drawing classes (643-700, 804-973); three files make each part reviewable alone. VisitEditViewModel.kt (829 lines) mixes the form, the photo and camera session (about 150 lines with their own state: sessionPhotos, pendingCapture), the ticket section and the saved-state draft; a VisitPhotoSession helper would take the photo part out. SettingsScreen.kt (645) could give Program and Backup rows their own files as ReminderRows.kt already has. The other large files are fine as they are: SeatLayout.kt (782, one cohesive model), HallDetailScreen.kt (705, many small private composables), LayoutEditorViewModel.kt (696), BackupReader.kt (591, apart from the validator living in it), SeatMap.kt (544), ProgramScreen.kt (597), VisitEditScreen.kt (592).
  - Touches: feature:seatmap editor/, feature:visits edit/, feature:backup; no behaviour change, tests keep their names.
  - Risk: Low for the file moves (internal/private visibility has to be adjusted). Medium for the photo session: the order of persist() and file deletion in VisitEditViewModel is deliberate (discard, save, restore after process death) and is covered by VisitEditInFlightTest and VisitEditRestoreTest, which must stay green unchanged.
- **Rename feature:backup to feature:settings** (effort M)
  - Gain: The module is the whole Settings tab (SettingsRoute, SettingsScreen, reminder settings, program settings, the ticket file picker, the membership card entry); backup is one of five sections. The name sends a reader looking for Settings code to the wrong place and makes "feature:backup depends on core:program" look like a layering mistake.
  - Touches: settings.gradle.kts, app/build.gradle.kts, the module folder and package (namespace is derived from the path), imports in app, CLAUDE.md's module map; spec 13's module table names it too, which only the owner can change.
  - Risk: Low technically (no persisted class names: preferences and routes of other modules are untouched; SettingsRoute's serial name changes, which only matters for a saved back stack across an update). The spec table would be out of step until the owner edits it, so this needs the owner's go-ahead first.
- **Showing lookup and name normalisation in core:program** (effort S)
  - Gain: `Program.showingsAt(seedKey, start)` and one public normalisation replace the copies in app (ReminderRuntimes), feature:tickets (TicketMatching) and YorckCinemas; the title rule becomes one decision instead of two.
  - Touches: core:program Program.kt and YorckCinemas.kt, app ReminderRuntimes.kt, feature:tickets TicketMatching.kt and their tests.
  - Risk: Low if both title rules are kept as they are; changing either one changes reminder times or pre-filled formats and needs the owner's decision on the spec conflict first.

### Questions (not confirmed)

- ReminderRuntimes requires the visit's film title to equal the program's title before it uses the showing's runtime. Tickets print Yorck's German booking text and the program comes from yorck.de/en: do the titles normally match for an imported ticket, or does "Rate your visit" in practice mostly fall back to start + 2.5 h? I could not check this without a real ticket and page.
- Are the Compose preview dependencies (ui-tooling, ui-tooling-preview) kept on purpose for Android Studio previews the owner adds locally? No committed file uses them.
- TicketText's KDoc says the cleaner was copied "so that this module doesn't depend on the network module". Was core:model considered and rejected as the shared home, or just not thought of?
- feature:tickets uses the photo picker (androidx.activity) and feature:tickets and feature:program use androidx.core (toUri) without declaring activity-compose or core-ktx; they compile through navigation-compose's transitive dependencies, while feature:seatmap, feature:visits and feature:backup declare activity-compose with a comment. Is the explicit declaration a rule (then two modules miss it) or only a habit?
- I could not verify the README's count of 16 intentional lint warnings or anything else that needs a build: Gradle was off limits for this review.

### Coverage stated by the reviewer

Result in short: the module structure holds and the documented conventions are kept almost everywhere; I found no critical problem. Nothing in the repository was changed, and Gradle was not run.

Checked and found clean (by reading build files and by searches over all main sources):
- Module boundaries: no feature depends on another feature, no core module on a feature, core:model has no Android import, core:ui imports no other module, core:testing is used only as testImplementation. Each module's build file matches the CLAUDE.md module map.
- Build configuration: only in the convention plugins; the module build files hold only module-specific things (signing and version in app, the scan's test inputs in core:program).
- Conventions: no colour literal or font family outside core:ui; transitions only in CinexTransitions.kt (plus the documented bottom bar); all 16 destinations wrapped in ScreenBackground; no score maths in SQL (only COUNT and MAX of ids); Row/Seat texts come from SeatLabel; Yorck texts joined into strings go through isolate(); no sample data, TODO, AI model name or email address in main sources; QR expiry goes through ShowingTime everywhere.
- Dead code: a script over all 208 main-source Kotlin files; result in the last finding.

Read in full: settings.gradle.kts, the root and all 15 module build files, gradle.properties, gradle/libs.versions.toml, all 12 build-logic files; app: CinexApp.kt, CinexTransitions.kt, AppContainer.kt, MainActivity.kt, CinexApplication.kt, IncomingTicketViewModel.kt, TopLevelDestination.kt, BackupImportLock.kt, reminders/Reminders.kt, ReminderIntents.kt, ReminderRuntimes.kt, ReminderReceivers.kt, the manifest and the two backup rule files; core:data DataContainer.kt; core:model ShowingTime.kt, TextLimits.kt, ScreenSize.kt; core:program YorckCinemas.kt, VisitPrefill.kt; core:ticket TicketText.kt; core:ui Dates.kt, SafeText.kt, Flows.kt, Type.kt; feature navigation files of cinemas, visits, tickets and backup; TicketMatching.kt; README.md, CLAUDE.md; spec sections 4.4, 6 (intro), 6.4, 6.7, 7, 10, 11.2-11.4, 13-15.

Read in large parts: VisitEditViewModel.kt (about 650 of 829 lines), BackupReader.kt 74-292 (the validator), RoomRepositories.kt 160-422, ReminderNotifier.kt 30-213, EditorCanvas.kt 380-635, SettingsScreen.kt 86-215, Color.kt 1-120, Program.kt 1-110 and 215-248, ProgramRules.kt 30-90, MembershipCard.kt 40-110, Ticket.kt 62-160, FakeAppContainer in TabScreenshotTest.kt, AppClockTest.kt.

Only outlined or searched, not read: the rest of the 38,000 main-source lines, in particular core:ticket's PDF and QR internals, core:program's downloader, parser and cache, Seeder, BackupWriter/BackupRestorer/RoomBackupRepository, LayoutJson, SeatLayout's operations, core:seatmapui, ReminderCoordinator and ReminderScheduler, the program and membership navigation files, and the screens and ViewModels of cinemas, program, rankings, seatmap and tickets. Tests were only searched, except the few named above. Not looked at: scripts/, release.yml, the seed JSON's content, spec sections 4.1-4.3, 5, 6.1-6.3, 6.5-6.13 apart from 6.7, 8, 9, 11.1.

Largest main-source files (lines): EditorCanvas.kt 973, VisitEditViewModel.kt 829, SeatLayout.kt 782, HallDetailScreen.kt 705, LayoutEditorViewModel.kt 696, SettingsScreen.kt 645, ProgramScreen.kt 597, VisitEditScreen.kt 592, BackupReader.kt 591, SeatMap.kt 544, LayoutEditorScreen.kt 519, ProgramViewModel.kt 518, FakeRepositories.kt 500. My judgement is in the second refactoring proposal: EditorCanvas.kt and VisitEditViewModel.kt are worth splitting, the others are fine.
