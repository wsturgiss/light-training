# Persistence, migrations, and backup

Why this tool stores data the way it does. Written 2026-10-08, while adding backup support.

Short version: logged sessions live in Room **and** as one JSON file per session on disk. The
files exist because that is the only shape LightOS's backup system can work with, and because
Light's sandbox makes Room migrations unusually risky to get wrong.

## Sessions are exported as files, not backed up as a database

LightOS's backup system ([light-backup](https://github.com/lightphone/light-backup)) runs
inside the OS, not in a tool. It is not a sync engine — one-way, append-only, no delete
propagation, no conflict resolution, no restore. The flow is a WorkManager job that asks each
tool a narrow set of questions, the important one being *"which of your files changed between
these two instants?"*, and then uploads whatever comes back into a per-window folder in the
user's cloud storage, alongside a `checksums.sha256` manifest.

A Room database is the worst possible answer to that question. `training.db` changes whenever
anything changes, so it would answer "all of it" to every window and re-upload the entire
history forever. It would also land in the user's Drive as an opaque blob, which contradicts
Light's stated goal that a backup be readable on arrival without the app that wrote it.

So `SessionExportStore` mirrors every session write to `filesDir/backup/sessions/` as a small
standalone JSON file. Three properties make the window query work, and all three are
deliberate:

- **A file's modification time is set to its session's `updatedAt`**, not to the moment it was
  written. Re-exporting an unchanged session is therefore invisible to the backup: the file
  stays in the window it already belongs to and is not uploaded twice.
- **Filenames are stable and unique per session.** Everything in one window is uploaded into a
  single remote folder, so two sessions must never collide. The full session id is in the name
  for that reason — an abbreviated one collides roughly never, and "roughly never" silently
  loses a workout when it happens.
- **Parts of the filename are separated by `__`, not `-`.** Session ids are UUIDs and dates are
  hyphenated, so a single hyphen leaves no way to recover the id from the name — and reading the
  id back out is how `removeAllExcept` decides what to keep. With `-` it kept nothing: the
  startup reconciliation pass deleted every export it had just written, on every launch.

Exercise names are resolved and embedded at export time rather than referenced by id, so a file
stands on its own in cloud storage. A consequence: renaming an exercise does **not** rewrite
past exports, so cloud copies keep the name as logged while the UI resolves names live. That is
arguably correct for an archive, but it is a choice, not an accident.

Deleting a session deletes its local export and nothing in the cloud. The remote copy is an
archive, not a mirror.

`TrainingBackupSource` holds the answers to LightOS's questions. It deliberately does not
implement light-backup's `BackupDataSource` interface, because as of 2026-10-08 the SDK exposes
no tool-facing backup API at all — the announced PRs are not open upstream, so there is nothing
to compile against. When they land, wiring it up should be one delegating method per question
with no logic moved.

## `updated_at` exists for backup windowing, not for display

`created_at` could not drive the window query, for two reasons:

- Rows predating `MIGRATION_6_7` have `created_at = 0`. They would bucket into a 1970 window and
  in practice never be reached.
- It is creation time, not modification time. Correcting a session logged last March would never
  change which window it falls in, so the fix would never be backed up.

`MIGRATION_9_10` adds the column and backfills it in order of preference: the row's `created_at`;
failing that, midnight UTC on its logged date; failing that, the time of the migration — so a
session is backed up late rather than silently skipped forever.

`syncExports()` reconciles files against the database on launch. It is what gets sessions logged
before this feature existed into the backup at all, and it self-heals after a write that failed
partway, which is why it runs every launch rather than once behind a "migrated" flag. Sessions
already up to date are skipped, so the cost is a directory listing and a timestamp comparison
per session.

## Migrations: what Light supports, and what it doesn't

Two real gaps in the SDK shape everything below. Both were verified against this repo, not
assumed.

**1. `buildDatabase` cannot accept migrations.** Upstream's helper is:

```kotlin
fun <T : RoomDatabase> SealedLightContext.buildDatabase(dbClass: Class<T>, dbName: String?): T =
    Room.databaseBuilder(androidContext.applicationContext, dbClass, dbName).build()
```

There is no migrations parameter, and no way to route around it from `tool/`:
`SealedLightContext.androidContext` is `internal` to `sdk:client`, and `android.content.Context`
is on the plugin's blocked-imports list, so tool code cannot obtain the `Context` that
`Room.databaseBuilder` requires. Room's migration machinery is entirely present and
`androidx.room` is allowlisted — what's missing is one `vararg` argument.

`sdk/client/.../LightDb.kt` is therefore locally patched to add `vararg migrations: Migration`.
This is a knowing violation of AGENTS.md's "do not hand-edit files under `sdk/`", and it does
**not** travel: `builder/lightbuilder/allowlist.py` shows Light's build service extracts only
`tool/src/main/{kotlin,java,res,assets}`, `tool/build.gradle.kts`, and `tool/lighttool.toml`,
with `sdk/` coming from Light's own checkout. An official build of this tool would fail to
compile until the parameter exists upstream. That costs nothing today — the SDK is unreleased
and there is no submission path yet — but it is the thing to fix first when there is.

**2. Migrations cannot be tested anywhere in this ecosystem.** Android unit tests run on the
desktop JVM against `android.jar`, a stub jar whose platform method bodies throw `"Stub!"`.
Real SQLite is part of the device OS. The three standard ways around that each need a
dependency the plugin rejects — confirmed by trying it:

| Approach | Needs | Allowlisted |
| --- | --- | --- |
| Instrumented test on device | `androidx.test` runner | no |
| Robolectric | `org.robolectric` | no |
| Room 2.7 JVM `MigrationTestHelper` | `androidx.sqlite:sqlite-bundled` | no |

```
> Light SDK: build configuration violations detected:
    testImplementation: org.robolectric:robolectric:4.14
```

The allowlist governs test-only scopes because the *build script* is extracted even though
`src/test/` is not, so one rule covers both. Fixing this is an allowlist entry, not an API
change, and it is worth raising upstream separately from the `vararg` — it stays true even after
the `vararg` lands.

For reference, Light's own Room example (`examples/authenticator/TotpDatabase.kt`) is
`version = 1, exportSchema = false` with no migrations. "Light standard" for Room is *don't
migrate*, which is not reachable for a tool already on version 10.

### What was done about it

**One migration list, one construction site.** Every screen used to open the database itself,
each repeating the migration list inline. The repository is a lazy singleton, so whichever
screen the user happened to open first was the one that opened the database — and a screen
whose copy of the list had fallen behind would hit the destructive fallback below and drop
everything. Which screen "wins" depends on navigation, so it would not even have reproduced
consistently. The list now exists only as `TrainingDatabase.MIGRATIONS`, and screens call
`lightContext.trainingRepository()`.

**`fallbackToDestructiveMigration()` removed.** The local `LightDb.kt` patch had added it
alongside the `vararg`. It means "if no migration path can be found, drop every table and
recreate them empty" — no exception, no log, no prompt, just an app that opens with the user's
training history gone. Upstream's unpatched version fails *safe*: a missing migration throws
`IllegalStateException` at open time. The patch is what turned a missing feature into a
data-loss risk, so only the `vararg` half is kept. A missing migration now crashes, which on a
single-user tool is a bug you fix in minutes rather than data you cannot recover.

Note that the migrations start at 2→3; there is no 1→2. A database still at version 1 now
crashes instead of being wiped. That is the intended trade.

**`exportSchema = true`.** Room writes a JSON snapshot of each schema version to
`tool/schemas/`, configured by the `room.schemaLocation` KSP argument in `build.gradle.kts`.
This is a KSP argument, not a dependency, so the allowlist does not apply — it is legal under
Light's rules today, and it is the only check on a migration that is.

It matters because the most common Room migration bug is "the migration's output doesn't match
what Room derives from the entities", and with `exportSchema = false` the expected schema was
written down nowhere, so there was nothing to check against by hand or otherwise. The snapshots
make it a committed file: bumping to version 11 produces a diff showing exactly what the
migration has to produce.

It earned its keep immediately. `MIGRATION_9_10` writes
`ALTER TABLE … ADD COLUMN updated_at INTEGER NOT NULL DEFAULT 0`, and `10.json` confirms Room
expects exactly `updated_at INTEGER NOT NULL DEFAULT 0` on both tables — previously that
agreement rested on having been careful rather than on evidence.

Only `10.json` exists. Versions 1–9 would require checking out old commits and rebuilding each,
and since migration tests are impossible regardless there is no payoff. The value starts here.

## Testing

`SessionExportStore` is deliberately plain `java.io.File` work with no Android or Room surface,
so the component that decides what LightOS uploads is testable on the JVM with nothing but
`kotlin.test` — which is all Light's examples use, and all the allowlist permits. Most of
`SessionExportStoreTest` is about timestamps, because the backup runner never asks "what
changed?" but "what changed between these two instants?", and the whole answer comes from the
modification times this class sets.

Run with `./gradlew :tool:assembleDebug :tool:testDebugUnitTest`.

Gradle itself must launch on a JDK that AGP 8.12.3 supports; JDK 26 fails in
`JdkImageTransform`. On this machine that means prefixing with
`JAVA_HOME=~/.local/share/mise/installs/java/zulu-21.52.203.0`, or setting `org.gradle.java.home`
in `~/.gradle/gradle.properties`.

## Open questions

- Should renaming an exercise or muscle group cascade a re-export of past sessions? Currently no.
- There is no restore path. light-backup has none either, but the exports are half of one: the
  JSON files survive a destructive Room wipe untouched, and `syncExports()` currently only
  pushes database → files. Adding the inverse would make a wipe recoverable and would also be
  the groundwork for restore. It would additionally require exporting the exercise library,
  muscle groups, and interval presets, which currently exist only in Room.
