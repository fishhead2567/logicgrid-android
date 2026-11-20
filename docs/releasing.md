# Releasing

There is no release yet and no Play listing. This is what is in place so that
there can be, and what is deliberately not in the repo.

## The shrunk build already works

```
./gradlew :app:assembleRelease
```

R8 and resource shrinking are on, and **no keep rules are needed**:

| | |
|---|---|
| debug | 9.8 MB |
| release, R8 only | 1.4 MB |
| release, resources shrunk too | **1.0 MB** |

That is unusual for an app that parses JSON, and it is not luck.
`core/PuzzleJson.kt` is hand-written against `JsonElement` rather than
`@Serializable` data classes, so no serializer is ever looked up by name and
R8 has nothing to break. The one classic release-day failure for this stack —
shrink the app, ship it, watch it die parsing its own content on a device —
cannot happen here. `app/proguard-rules.pro` says the same thing at the point
someone would go looking.

Resource shrinking does not touch `assets/`, so the bundled content set is
untouched -- checked rather than assumed: the release APK holds the manifest
and all 50 puzzle files, same as the debug one.

Shrinking has been on since the first commit for exactly this reason: finding
it at the end is how releases slip.

## Signing: the key is not here, and should not be

`app/build.gradle.kts` reads `keystore.properties` from the repo root if it
exists:

```properties
storeFile=../logicgrid-upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

That file and `*.jks` / `*.keystore` are gitignored. Without them the release
build still runs and produces `app-release-unsigned.apk`, which is the right
failure: an unsigned APK that says so, not a build that stops with an error
about a file the person cloning has no way to produce.

**Generating the key is a human decision, not a build step.** An upload key is
a long-lived credential: lose it and you cannot publish an update to a listing
under that identity again without Play's key-reset process. So nothing here
creates one. When you want one:

```
keytool -genkeypair -v -keystore logicgrid-upload.jks \
  -keyalg RSA -keysize 4096 -validity 10000 -alias upload
```

Keep it outside the repo, back it up somewhere that is not this machine, and
put its passwords in `keystore.properties`.

## The launcher icon needs no bitmaps

`res/mipmap-anydpi-v26/ic_launcher.xml` is an adaptive icon over three vector
layers: a flat colour background, the drawing, and a monochrome layer for
Android 13's themed icons. There is no `mipmap-mdpi/ic_launcher.png` or any of
its four siblings, and that is not an omission — adaptive icons arrived in API
26 and `minSdk` here is 26, so the bitmap fallbacks exist for devices this app
cannot be installed on.

There is no `android:roundIcon` either. It was added in API 25 so a launcher
could request a circular bitmap; an adaptive icon makes it redundant, because
the launcher masks the layers into whatever shape it wants. A launcher that
looks for it falls back to `android:icon`.

And there is no `values-night` variant: a launcher icon is one drawing. The
system masks and tints it, but never swaps it for a dark-mode version the way
the app's own surfaces do.

## Versioning

`versionCode` is a monotonic integer Play orders updates by; `versionName` is
for people. Both live in `defaultConfig`.

| | |
|---|---|
| 1 / 0.1.0 | the solvable app: library, chart, marks, assist, progress |
| 2 / 0.2.0 | content sync, the solved tally, "check my work" |
| 3 / 0.3.0 | dark mode, the settings screen, the launcher icon |

Only `v0.3.0` is tagged. 0.1.0 and 0.2.0 were version *names* that existed in
the build file for a while and were never released, and inventing tags for
them after the fact would put two releases in the history that nobody could
ever have installed. F-Droid builds from tags, so from here each release gets
one.

## Listing metadata lives in this repo

`fastlane/metadata/android/en-US/` holds the title, the short and full
descriptions, a per-`versionCode` changelog and a 512×512 icon. F-Droid reads
that directory straight out of the repo, and so do several other tools, which
is better than keeping the listing text in a store console: it changes in the
same commit as the thing it describes.

[`docs/fdroid.md`](fdroid.md) has the F-Droid submission checklist.

## What is still missing before a real release

- Screenshots. `fastlane/metadata/android/en-US/images/phoneScreenshots/` is
  the one real gap in the listing metadata; the development screenshots show
  an app that predates dark mode, the settings screen and two content
  regenerations.
- An `aab` and a Play listing, if it is ever going to a store at all; a
  sideloaded signed APK needs neither.
- A privacy statement. There is nothing to say beyond "nothing leaves the
  device except an unauthenticated GET for public puzzle files", but a listing
  will ask for it in writing.
- `targetSdk = 36` has been compiled against but never tested on a device
  running it.
