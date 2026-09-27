# Experimental Redfinger performance profile

## Behavior and compatibility

`LEGACY` is the default for new installs, missing preferences, and unknown stored values. The
overlay's **Experimental Redfinger Performance Profile** switch is the only opt-in mechanism;
resolution never selects the profile. Start Bot snapshots the selection for that run and Stop Bot
returns capture processing to Legacy. Existing gesture coordinates, templates, ROIs, thresholds,
scale lists, stage ordering, watchdog behavior, and latest-frame-only ownership remain unchanged.

The Redfinger profile currently makes two deliberately narrow changes:

1. It defers the expensive all-stage NO_MATCH score report during the existing eight-second
   GAME_START priority window. Detection itself and prolonged-NO_MATCH image capture continue.
2. It replaces `INTER_AREA` resize with `Mat.copyTo` only when the source and content rectangle are
   exactly `(0,0,1280,720)`. A copy is pixel-equivalent for this 1:1 geometry and retains independent
   destination ownership. Every crop, inset, offset, or other resolution uses the original path.

No OpenCV match is removed: device-independent inspection found that the detector already converts
each candidate set to grayscale once, loads pre-scaled templates once, and evaluates all seven
configured GAME_START/GAME_RELAY scales. Early exit would change the winning score/location, so it
is intentionally not used.

## Benchmark measurements

There is no Redfinger device attached to the build environment, so no phone FPS or CPU result is
claimed. The implementation records the measurements needed for an A/B run:

* `[CAPTURE PERF]`: plane copy, RGBA Mat population, normalization, publication, end-to-end capture
  preparation, actual rolling FPS, and whether the exact-size path qualified.
* `[MATCH PERF]`: per-scale and total matching time for all seven GAME_START and GAME_RELAY scales,
  independently sampled at most once per stage per five seconds.
* `[GAME_START_ATTEMPT]`: every real priority-window attempt (not a throttled proxy), frame age,
  detection duration, snapshot-clone duration, and skipped latest-frame generations.
* `[NO_MATCH PERF]`: scoring deferral or scoring/image-save duration. Each message explicitly states
  that the interval throttles diagnostics, not detection.
* `[FAST PATH]`: existing capture-to-publish, capture-to-detect, detect-to-action,
  capture-to-dispatch, and capture-to-accessibility-callback latency.

On the build host, all JVM tests complete and the debug APK compiles. Device measurements remain to
be collected by running identical scripted rounds in Legacy and Redfinger modes and comparing
median/p95 values from the tags above. Expected benefits on an exact-size Redfinger stream are one
fewer OpenCV resize per captured frame and uninterrupted GAME_START polling when a detailed
NO_MATCH report becomes due.

## Remaining risks and required device tests

* Verify normalized images byte-for-byte on an actual 1280x720 Redfinger capture; JVM tests cover
  profile/geometry gating, while `copyTo`'s pixel equivalence is an OpenCV contract requiring the
  device native library for an end-to-end comparison.
* Compare GAME_START/GAME_RELAY results, scores, locations, and all seven scale timings between
  profiles over representative transitions.
* Measure FPS, skipped generations, CPU utilization, frame age, and capture-to-gesture p50/p95.
* Exercise 2400x1080 in Legacy mode and confirm all existing physical gesture exceptions. No new
  Redfinger physical coordinate has been introduced.
* Verify rotation/cutout/non-exact geometry falls back and run the manual checks in
  `ANDROID_TEST_CHECKLIST.md`.
