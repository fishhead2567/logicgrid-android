# Getting this onto F-Droid

Not submitted. This is the checklist and the reasoning, written down while it
was fresh so that picking it up later is not a research project.

## The thing to understand first

**F-Droid builds from source. You never upload an APK.** They compile it on
their own buildserver and sign it with F-Droid's key. What you submit is a
*metadata file*, as a merge request against
[`gitlab.com/fdroid/fdroiddata`](https://gitlab.com/fdroid/fdroiddata).

That has a consequence this repo already satisfies by accident. `app/build.gradle.kts`
signs the release build only if `keystore.properties` exists, and it does not
on a clone — so the release comes out **unsigned** rather than failing. That
was done so a clone would not break on a missing file; it is also exactly what
the buildserver needs.

## Where this app stands against the gates

| | |
|---|---|
| Licence | MIT, with `LICENSE` in the repo root ✅ |
| All dependencies free software | ✅ — checked, below |
| No tracking, analytics or advertising | ✅ none, and none is planned |
| No proprietary blobs | ✅ no Play Services, no Firebase |
| Builds from a tag | ✅ since `v0.3.0` |
| Listing metadata in-repo | ✅ `fastlane/metadata/android/en-US/` |
| Screenshots | ❌ still missing |

### The dependency check, done rather than assumed

`./gradlew :app:dependencies --configuration releaseRuntimeClasspath` resolves
to androidx, Jetpack Compose, kotlin and kotlinx, JetBrains `annotations`, and
Guava's `listenablefuture`. Every one of those is Apache 2.0. Nothing from
Google Play Services, nothing from Firebase, no closed SDK of any kind.

Worth re-running before submitting, since it is a dependency away from being
untrue.

## The question a reviewer will ask

**The content sync fetches from `raw.githubusercontent.com`, and GitHub is not
free software.** F-Droid tags apps with "anti-features", one of which is
`NonFreeNet` — depending on a non-free network service.

The answer is already true rather than something that would need building:

- the app ships all fifty puzzles in `assets/` and is fully playable with the
  `INTERNET` permission denied;
- the network is never on the path to playing, and every failure is silent;
- the content fetched is MIT-licensed JSON from
  [logicgrid-puzzles](https://github.com/fishhead2567/logicgrid-puzzles),
  which anyone can mirror;
- nothing is gated behind the service, and no account exists to have.

Whether that clears `NonFreeNet` is a judgement call by a reviewer, not a rule
with an obvious reading, so expect the conversation rather than assuming it
away. If it ever became a blocker, the sync could point at any static host —
`ContentSync.DEFAULT_BASE` is one constant.

## The metadata file to submit

`metadata/dev.logicgrid.app.yml` in a fork of `fdroiddata`:

```yaml
Categories:
  - Games
License: MIT
AuthorName: fishhead2567
SourceCode: https://github.com/fishhead2567/logicgrid-android
IssueTracker: https://github.com/fishhead2567/logicgrid-android/issues

RepoType: git
Repo: https://github.com/fishhead2567/logicgrid-android.git

Builds:
  - versionName: 0.3.0
    versionCode: 3
    commit: v0.3.0
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.3.0
CurrentVersionCode: 3
```

No `Summary` or `Description` keys: F-Droid reads those from
`fastlane/metadata/android/en-US/` in this repo, which is better, because the
listing text then lives with the thing it describes and changes in the same
commit.

Before opening the merge request, test it locally with the `fdroidserver`
tools:

```
fdroid lint dev.logicgrid.app
fdroid build -l dev.logicgrid.app
```

Review is done by volunteers. Expect weeks, not days.

## The quicker route

[IzzyOnDroid](https://apt.izzysoft.de/fdroid/) is a third-party F-Droid
repository that accepts APKs **you** build, from a public repo, with a far
shorter turnaround. Many apps land there first and graduate to F-Droid proper
later.

It wants the same tag and the same fastlane metadata, so none of the
preparation is wasted whichever way round you do it. It does mean the APK
carries your signature rather than F-Droid's, which needs the upload key that
`docs/releasing.md` deliberately does not create for you.

## What is left before either

1. **Screenshots.** The only real gap. See
   `fastlane/metadata/android/en-US/images/phoneScreenshots/README.md` — the
   ones taken during development show an app that no longer exists.
2. **A 512×512 icon** is already in `fastlane/.../images/icon.png`, rendered
   from the same vector the app uses.
3. **Tag each release.** `UpdateCheckMode: Tags` means F-Droid watches for new
   ones; a release that is not tagged does not exist as far as they are
   concerned.

## Releasing, from here

```
# bump versionCode and versionName in app/build.gradle.kts
# write fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
git commit -am "Release 0.4.0"
git tag -a v0.4.0 -m "0.4.0"
git push origin main --follow-tags
```

`versionCode` must only ever go up, and the changelog file is named after it,
not after the version name.
