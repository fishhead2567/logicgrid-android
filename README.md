# logicgrid-android

An Android app for solving logic grid puzzles — the Penny Dell kind, where a
handful of clues and a blank chart are enough to pin down who grew which marrow
and in what month.

The puzzles come from [logicgrid-puzzles][content], generated and proved unique
by the [`logicgrid`][generator] Python package. Fifty of them ship inside the
APK, so a fresh install with no network has the full set.

[content]: https://github.com/fishhead2567/logicgrid-puzzles
[generator]: https://github.com/fishhead2567/logicgrid-generator

## Status

**Early, but real.** It builds, installs, and solves puzzles on a device. There
is no release, no Play listing, and no signing config in this repo.

What works today: the library screen, the composite grid with pan/zoom and
sticky headers on both axes, selection with O/X/clear, forced elimination,
opt-in assist, a check that says whether you have gone wrong without saying
where, progress that survives the process being killed, and content updates
fetched in the background.

Dark mode follows the system, chart and all.

Not built yet: a way to reveal the answer
(`SolveViewModel` has `revealSolution()`, but nothing in the UI calls it); and
narrative clue text — the manifest records which puzzles have a narration and
the library shows it, but the solve screen renders the canonical wording in
every case, and the published set currently has no narratives at all.
[`ROADMAP.md`](ROADMAP.md) has the full plan and the measurements behind the UI
decisions; read it as intent, not as a description of what is here.

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
        Model, the 14-kind clue IR, the Board closure, JSON, the manifest,
        and the content sync — HTTP and files, but nothing Android.
app/    Compose UI, ViewModels, SharedPreferences-backed progress,
        and the bundled content in src/main/assets/content/.
```

The split is not ceremony. The Python version put every bit of solving logic in
a UI-agnostic `board.py`, and that is exactly why its terminal player was cheap
to write and cheap to change. `:core` is the same bet: the rules of the game
stay testable in seconds.

## The palette is data, and the contrast is a test

Material 3's `colorScheme` dresses the chrome, but a logic chart is drawn on a
Canvas and the things it draws are not Material roles: "cell border", "category
separator", "the unused half of the triangle", "the O", "the X". Those live in
`ChartColours` in `Theme.kt`, named for what they draw, defined once per scheme
and reached through a `CompositionLocal`.

Dark is not light inverted. A chart is mostly empty space with thin strokes on
it, and a thin stroke behaves differently on a dark ground — pure black with
white lines glares, and a hairline that read as grey on white vanishes
completely — so the paper is a near-black with some blue in it and the rules
come *up* in value rather than down.

Two rules hold it together, both enforced:

- **No screen writes a colour literal.** A test scans every source file bar
  `Theme.kt` for `Color(0x…)`. A literal is a colour that cannot follow the
  scheme, and it will be the one thing still glowing white on a dark phone.
- **Contrast is arithmetic, not opinion.** The same test computes WCAG ratios
  for every mark and label against its own paper: 4.5:1 for the 9sp labels and
  the secondary text, 3:1 for the bold O/X glyphs, 4.5:1 for banner text on its
  fill. The shipped light-mode green is 1.4:1 on a near-black chart, which
  looks fine in a screenshot taken at noon and is a smudge on a phone at night;
  the test says `dark/yes on paper: 1.52:1` and fails.

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

**Check my work** is the same line drawn again. It answers one question — can
what you have still be right? — and nothing else. Not how many marks are wrong,
because a player told "one" knows it was recent; not which, because that hands
over that much of the answer. It is what the back of a puzzle magazine gives
you: your grid is not the answer, so go back to the clue you trust most. The
verdict is cleared by the next mark or undo, since a stale "no mistakes so far"
is worse than no check at all.

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

## An update knows what it will cost before it costs it

The app ships the full set in `assets/`, so a fresh install with no network has
every puzzle. On launch it fetches `manifest.json` — 22 KiB — in the background
and works out what an update would mean **from the manifest alone**, before
downloading anything else.

That is possible because progress is keyed on `pid` and the manifest carries
every `pid`. So the two kinds of update are told apart for the price of one
request:

| | |
|---|---|
| prose was fixed, puzzles unchanged | the pids hold, so every grid survives. Silent: changed files are fetched, the rest copied forward, and a line under the title says so. |
| the set was regenerated | the pids all move, so no grid in progress can be carried over. A notice names what it will cost, and the update applies once it has been read. |

Both happened to this project's content repo inside one afternoon, which is why
the distinction is built in rather than guessed at.

There is no decline. Puzzles leave because they no longer exist upstream, so
there is nothing to keep the old set for; what the notice guarantees is that
nobody loses a half-solved grid to something they never saw happen. **The
solved count survives regardless** — `solvedEver` is only ever added to, so
"47 all time" outlives the puzzles that earned it even when their grids cannot
be kept.

Downloads run six at a time. Sequentially a 50-file reseed took 6.2 s against
the real repo on a wired connection — most of a minute on a thin mobile one —
and nearly all of it was waiting for round trips rather than reading bytes; six
at a time brings the same update to 0.9 s. The first failure cancels the rest,
so a set that is going to be discarded does not finish downloading first.

An update the player has acknowledged holds the library behind a progress
dialog until it lands. That is not ceremony: tapping through a notice and
getting a library that looks untouched reads as a dead button, and the rows on
screen describe the set being replaced, so opening one of them mid-swap is a
puzzle that is about to stop existing. A silent update has nothing to interrupt
and gets a line of text instead.

A new set is built in a staging directory and swapped in whole, with the
manifest written last. Writing it in place would be the subtle bug: filenames
are reused between generations, so a half-applied update leaves the old
manifest describing new content — every checksum still verifying, every `pid`,
title and rating attached to a puzzle that is no longer in that file. Any
failure at all — no network, a captive portal, a truncated file, a hash that
disagrees — keeps the old set and shows the player nothing.

## Progress is keyed on `pid`, never the filename

`pid` is a hash of a puzzle's content. Filenames move whenever the set is
regenerated — they have already moved several times during development — so
keying progress on the file stem would silently mark the wrong puzzles complete
after a content update. In-progress state is local to the device that made it:
no account, no backend, nothing synced.

## Licence

MIT — see [`LICENSE`](LICENSE).
