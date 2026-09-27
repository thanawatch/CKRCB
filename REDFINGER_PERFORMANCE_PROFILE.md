# Experimental Redfinger performance profile

## Behavior and compatibility

`LEGACY` is the default for new installs, missing preferences, and unknown stored values. The
overlay's **Experimental Redfinger Performance Profile** switch is the only opt-in mechanism;
resolution never selects the profile. Start Bot snapshots the selection for that run and Stop Bot
returns capture processing to Legacy. Legacy gesture coordinates and dispatch paths, templates, ROIs,
thresholds, scale lists, stage ordering, watchdog behavior, and latest-frame-only ownership remain
unchanged.

The Redfinger profile currently makes three deliberately narrow changes:

1. It defers the expensive all-stage NO_MATCH score report throughout `IN_GAME`, including the
   period waiting for GAME_RELAY after Fast Start. The detailed rescan previously occupied the
   detector thread for roughly 0.8–2 seconds per report in a Redfinger run. Detection itself,
   the lightweight deferral log, and prolonged-NO_MATCH image capture continue. Legacy and
   detailed PRE_GAME/POST_GAME score reports are unchanged.
2. It replaces `INTER_AREA` resize with `Mat.copyTo` only when the source and content rectangle are
   exactly `(0,0,1280,720)`. A copy is pixel-equivalent for this 1:1 geometry and retains independent
   destination ownership. Every crop, inset, offset, or other resolution uses the original path.
3. It refuses to dispatch the uncalibrated raw-physical `POST_RELAY_1/2/3`, `TIME_DEAL`, and
   `PARTY_RUN_SEASON_REWARDS` targets. It logs `UNCALIBRATED_PHYSICAL_TARGET` and safely skips
   the special action. `TIME_DEAL` and `PARTY_RUN_SEASON_REWARDS` explicitly publish
   `WAITING_FOR_REDFINGER_CALIBRATION` and remain blocked while the popup is onscreen; the operator
   must manually clear the popup, after which detection safely resumes when a different state is
   observed. The optional post-relay sequence is instead recorded as `SKIPPED_UNPERFORMED` and play
   continues in `IN_GAME`; it is not a popup and does not request manual clearance. No proportional
   or replacement Redfinger coordinate is inferred.
   These actions require separate device calibration. Legacy retains its original fire-once flag and
   all three original raw dispatch attempts, including their acceptance-independent sequencing.

No OpenCV match is removed: device-independent inspection found that the detector already converts
each candidate set to grayscale once, loads pre-scaled templates once, and evaluates all seven
configured GAME_START/GAME_RELAY scales. Early exit would change the winning score/location, so it
is intentionally not used.

## Benchmark measurements

There is no Redfinger device attached to the build environment, so no phone FPS or CPU result is
claimed. The implementation records the measurements needed for an A/B run:

* `[CAPTURE PERF]`: plane copy, RGBA Mat population, normalization, publication, end-to-end capture
  preparation, actual rolling FPS, and whether the exact-size path qualified.
* `[MATCH PERF]`: per-scale and total matching time for GAME_START and GAME_RELAY, sampled only in
  the optimized profile at most once per stage per five seconds. Actual evaluated scales are retained
  with each sample, and Logcat publication runs on a separate executor.
* `[GAME_START_ATTEMPT_SUMMARY]`: an asynchronous aggregate at the first applicable lifecycle
  boundary (window end, GAME_START completion, round end, new round, or Stop) with attempt count,
  min/average/max detection duration, and skipped latest-frame generations. Window membership and
  the priority candidate list share one pre-detection decision, so an attempt crossing the deadline
  is still counted and no attempt is attributed to a list that did not evaluate GAME_START first.
* `[NO_MATCH PERF]`: scoring deferral or scoring/image-save duration. Each message explicitly states
  that the interval throttles diagnostics, not detection.
* `[FAST PATH]`: existing capture-to-publish, capture-to-detect, detect-to-action,
  capture-to-dispatch, and capture-to-accessibility-callback latency.

No GitHub check runs existed for reviewed SHA `a9fefb9fd5`. The commands and results for this follow-up
are reported in the PR comment/description rather than inferred from CI. Device measurements remain to
be collected by running identical scripted rounds in Legacy and Redfinger modes and comparing
median/p95 values from the tags above. The hypothesized benefits on an exact-size Redfinger stream
are one fewer OpenCV resize per captured frame and uninterrupted GAME_START polling when a detailed
NO_MATCH report becomes due; neither latency nor CPU improvement has been measured on a device.

## Remaining risks and required device tests

* Verify normalized images byte-for-byte on an actual 1280x720 Redfinger capture; JVM tests cover
  profile/geometry gating, while `copyTo`'s pixel equivalence is an OpenCV contract requiring the
  device native library for an end-to-end comparison.
* Run identical scripted rounds in each profile (same device, resolution, route, and round count).
  Compare GAME_START/GAME_RELAY results, scores, locations, attempt-summary min/average/max and the
  optimized diagnostic scale samples; use Android Studio CPU profiling for comparable detector CPU.
* Export capture FPS, skipped generations, frame age, and capture-to-gesture latency; calculate p50/p95
  for each profile outside the app. Optimized-only instrumentation is not a symmetric baseline, so use
  external tracing/profiling for the Legacy-versus-optimized latency conclusion.
* Exercise 2400x1080 in Legacy mode and confirm all existing physical gesture exceptions. No new
  Redfinger physical coordinate has been introduced.
* In Redfinger mode, trigger `TIME_DEAL` and `PARTY_RUN_SEASON_REWARDS`; confirm status changes to
  `WAITING_FOR_REDFINGER_CALIBRATION`, no gesture is dispatched, and automation resumes only after
  the operator manually clears the popup and the detector observes another state. Trigger relay and
  confirm the unperformed post-relay sequence is logged as skipped, never completed, and does not
  leave a calibration-blocked popup status during gameplay.
* Compare GAME_RELAY detection latency and skipped generations before and after the priority window,
  including rounds where synchronous detailed NO_MATCH scoring becomes due. This follow-up does not
  change NO_MATCH matching semantics; device evidence is required before considering another change.
* Verify rotation/cutout/non-exact geometry falls back and run the manual checks in
  `ANDROID_TEST_CHECKLIST.md`.
