# logicgrid-android

An Android app for solving logic grid puzzles — the Penny Dell kind, where a
handful of clues and a blank chart are enough to pin down who grew which marrow
and in what month.

The puzzles come from [logicgrid-puzzles][content], generated and proved unique
by the `logicgrid` Python package (not published). Fifty of them ship inside the
APK, so a fresh install with no network has the full set.

[content]: https://github.com/fishhead2567/logicgrid-puzzles

## Status

**Early, but real.** It builds, installs, and solves puzzles on a device. There
is no release, no Play listing, and no signing config in this repo.

What works today: the library screen, the composite grid with pan/zoom and
sticky headers on both axes, selection with O/X/clear, forced elimination,
opt-in assist, mistake checking, reveal, and progress that survives the process
being killed.

Not built yet: content sync over the network (assets only for now), the
settings screen, and narrative clue text — the manifest records which puzzles
have a narration and the library shows it, but the solve screen renders the
canonical wording in every case. [`ROADMAP.md`](ROADMAP.md) has the full plan
and the measurements behind the UI decisions; read it as intent, not as a
description of what is here.

## Building

```
./gradlew :core:test :app:assembleDebug     # tests, then a debug APK
```

The wrapper is committed and pins Gradle 8.14.3 by SHA-256, so a clone needs
only a JDK: the first run downloads and verifies the distribution itself.
Point `JAVA_HOME` at a JDK 21. Android Studio works too — open the project and
run the `:app` configuration.

Requirements, as declared in the build files:

| | |
|---|---|
| Android Gradle Plugin | 8.9.1 |
| Kotlin | 2.1.0 |
| JDK toolchain | 21, emitting Java 17 bytecode |
| `compileSdk` / `targetSdk` | 36 |
| `minSdk` | 26 (Android 8.0) |
| UI | Jetpack Compose + Material 3 |

`:core` is pure Kotlin/JVM, so its tests need only a JDK — no emulator, no
device:

```
:core:test
```

Release builds have R8 on from the start (`isMinifyEnabled = true`), because
shrinker breakage only ever shows up on a device and finding it at the end is
how releases slip.

## Layout

```
core/   pure Kotlin/JVM. No Android dependencies at all.
        Model, the 14-kind clue IR, the Board closure, JSON, the manifest.
app/    Compose UI, ViewModels, SharedPreferences-backed progress,
        and the bundled content in src/main/assets/content/.
```

The split is not ceremony. The Python version put every bit of solving logic in
a UI-agnostic `board.py`, and that is exactly why its terminal player was cheap
to write and cheap to change. `:core` is the same bet: the rules of the game
stay testable in seconds.

## The two things worth knowing

### `Board` stores only the player's own marks

Everything else is recomputed from them. Not storing derived marks is what
makes editing sound — change your mind about a cell and the consequences of the
old answer vanish with it, instead of lingering as X's that nothing supports
any more. It also makes undo a snapshot and makes toggling assist a pure
recompute.

Two levels of inference, deliberately separated:

- **always on** — a circled cell rules out the rest of its row and column.
  That is the pencil-work the rules force, not a deduction.
- **assist, opt-in** — transitivity (A=B and B=C, so A=C) and
  last-cell-standing. Those *are* deductions, so doing them unasked would be
  solving the puzzle for you.

### The port is proved against the Python, not against my reading of it

Hand-porting the reference test suite would test whether those tests were
re-read correctly, which is not the question. Instead, a script in the generator
repo walks real puzzles applying a deterministic pseudo-random sequence of marks
and records the complete board state after every single step. The Kotlin `Board`
replays those traces and must agree at every one.

`core/src/test/resources/fixtures/` holds **12 puzzles × 30 steps × 2 assist
modes** — 720 recorded states. A divergence in forced elimination, in assist,
or in conflict detection is caught at the step it first appears, in the shape it
first appears. `GridGeometry`, the chart's coordinate maths, gets 24 tests of
its own for the same reason: pan, zoom and hit-testing are easy to get subtly
wrong and miserable to debug through a touchscreen.

## Progress is keyed on `pid`, never the filename

`pid` is a hash of a puzzle's content. Filenames move whenever the set is
regenerated — they have already moved several times during development — so
keying progress on the file stem would silently mark the wrong puzzles complete
after a content update. In-progress state is local to the device that made it:
no account, no backend, nothing synced.

## Licence

MIT — see [`LICENSE`](LICENSE).
