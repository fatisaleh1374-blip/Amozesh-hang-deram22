# AI Context

## Project

Handpan Numbers is an offline Android application for learning, practicing, recording patterns, acoustic pitch detection, and handpan sound synthesis. It uses Kotlin, Jetpack Compose, Room, coroutines, SoundPool, and a YIN-based pitch detector.

## Architecture

- `audio/`: playback, timing, scheduling, microphone analysis, acoustic evaluation, and recording.
- `model/`: pure musical, assessment, session, scoring, and learning contracts.
- `data/`: Room and repository persistence.
- `ui/`: Compose screens and `HandpanViewModel`.

The assessment pipeline is:

`PracticeEngine -> PracticeSessionContext -> PatternScheduler -> MusicalTarget -> AcousticPracticeEvaluator -> AudioAnalysisSession -> DetectedStrikeEvent -> AssessmentTimeline -> AssessmentSessionQuality -> SkillEvidenceCalculator`

The live microphone path also produces a deterministic `AudioFrameQuality` contract from PCM frames. It records RMS, peak, clipping ratio, noise floor, SNR, signal confidence, frame status, and monotonic capture-to-analysis timestamps. Only `VALID` frames may emit microphone strikes into assessment; silent, low-signal, noisy, overloaded, and invalid frames are excluded from successful strike evidence.

The acoustic practice path also exposes an in-memory `AudioCalibrationSession` contract. It maps observed frame quality to `LISTENING`, `READY`, `NO_SIGNAL`, `TOO_NOISY`, `OVERLOADED`, or `FAILED`, requires three consecutive valid frames by default, rejects out-of-order capture timestamps, and supports reset for retry. This is a deterministic readiness contract; it is not physical-device calibration validation and is not persisted.

Finalized valid assessments now have a durable boundary. `AcousticPracticeEvaluator` exposes a `FinalizedAssessment` only after canonical session finalization and valid evidence derivation. `HandpanRepository` persists assessment and evidence snapshots in Room keyed by `sessionId`, and projects session-derived practice progress idempotently in one transaction. Duplicate finalization of the same session is ignored; full assessment event timelines and active sessions remain in memory.

Assessment finalization preserves complete target context on `MISSED` events while excluding them from valid-event counts. This lets a session with sufficient valid strikes remain assessable without treating missed notes as successful evidence.

The deterministic audio benchmark covers silence, attack+sustain, repeated strikes, low/mid/high synthetic handpan-register tones, matching boundaries, and rejected quality states. The current fixture results are onset precision/recall `1.0` for silence and attack+sustain, `1.0` for four repeated strikes after warmup correction, and pitch error below `0.34` cents across 146.83 Hz, 220 Hz, and 440 Hz tones. These are synthetic PCM regression fixtures, not recordings from a physical instrument.

## Non-negotiable boundaries

Do not change UI, ViewModel, Room, repository, persistence, signing, Gradle versions, dependency versions, or `AudioEngine` while working on domain session lifecycle unless a direct build requirement is demonstrated. Do not mix `PerformanceRecorder` with assessment lifecycle. Do not run destructive Git commands or `clean` without explicit authorization.

## Real Handpan First

The real microphone handpan path is the primary practice experience. Virtual handpan playback is a fallback for users without a physical instrument. Do not silently replace acoustic assessment with synthetic input.

## Current phase

Phase 3F is implemented and validated. It provides a canonical session context, monotonic lifecycle timing, pause-aware active duration, finalization, derived session quality, signal aggregation, and a finalized-session evidence boundary.

## Before changing code

Read the relevant local contracts first:

- `PracticeAssessment.kt`
- `AssessmentSessionQuality.kt`
- `SkillEvidenceCalculator.kt`
- `PracticeEngine.kt`
- `AcousticPracticeEvaluator.kt`
- `AudioAnalysisSession.kt`
- `PatternScheduler.kt`
- `PracticeClock.kt`
- `LearningEngineTest.kt`

Run the Git snapshot and focused tests before making a change. Preserve existing user changes.

## AI CONTINUATION CONTEXT

### Project identity

This repository is an Android Handpan learning application. Its intended product scope includes beginner-to-professional lessons, numeric Handpan notation, rhythm training, practice studio, metronome, acoustic assessment, event-based performance recording, and local progress. The product goal is professional quality, but no 10/10 claim is currently justified by the available evidence.

### Governing principles

- Preserve `REAL_HANDPAN` as the canonical input and assessment path.
- `VIRTUAL_HANDPAN` is explicit fallback/teaching support only.
- Never create evidence, assessment success, or calibration success from virtual notes.
- Do not change DSP thresholds or audio architecture without a focused hypothesis, deterministic tests, and regression validation.
- Do not reset, clean, checkout, rebase, merge, overwrite, or delete existing work.

### Toolchain and portability

Verified environment:

- Java: OpenJDK `25.0.2` Microsoft LTS
- `JAVA_HOME`: `/usr/local/sdkman/candidates/java/current`
- Gradle Wrapper: `9.3.1`
- AGP: `9.1.1`
- Kotlin: `2.2.10`
- KSP: `2.3.5`
- Android SDK: `/home/codespace/android-sdk`
- compileSdk: `36`, with `minorApiLevel = 1`
- targetSdk: `36`
- minSdk: `24`
- Build Tools: `36.0.0`
- Platform Tools / adb: `37.0.1`

Transferable through Git: source, tests, Gradle Wrapper/configuration, version catalog, Room migrations/schema, resources, manifest, and documentation. Machine-local and not guaranteed to transfer: SDK/JDK installations, Gradle/Android Studio caches, emulator/device state, signing keys, credentials, secrets, ignored files, and environment variables. In a new shell, SDK variables may need to be exported again.

### Repository state at handoff

- Branch: `main`
- HEAD: `ac7b1d0` (`انجام شد بیستودوم`)
- Remotes: `origin` points to `alikakai048-web/Amozesh-hang-deram21`; `upstream` points to `aventurinngallery-del/Amozesh-hang-deram`.
- The latest snapshot before this documentation edit was clean and aligned with `origin/main`.
- Existing implementation from the earlier calibration and durable-persistence phases is intentional. **DO NOT RESET EXISTING WORK. DO NOT RUN `git reset --hard`. DO NOT RUN `git clean -fd`.**

### Architecture and executable path

```text
MainActivity
 -> HomeScreen / ExerciseLibraryScreen / OnboardingDialog
 -> PracticeScreen
 -> PracticeEngine
 -> PracticeSessionContext
 -> PatternScheduler -> MusicalTarget
 -> AcousticPracticeEvaluator
 -> AudioAnalysisSession
 -> PitchDetector -> AudioRecord
 -> AudioFrameQuality
 -> OnsetAndPitchMatcher -> DetectedStrikeEvent
 -> AssessmentTimeline
 -> AssessmentSessionValidator
 -> SkillEvidenceCalculator
 -> FinalizedAssessment
 -> HandpanViewModel
 -> HandpanRepository
 -> Room
```

`MainActivity` owns composition, permission request, manual screen dispatch, and back behavior. `PracticeEngine` owns playback phases, count-in, loop, pause/resume/restart, natural completion, and timing. `PracticeSessionContext` owns canonical session identity and monotonic lifecycle. `PatternScheduler` creates target identities. `AcousticPracticeEvaluator` owns acoustic matching, scoring, timeline events, and finalization. `AudioAnalysisSession` owns detector subscriptions and microphone lease. `HandpanRepository` owns mapping to Room and durable writes. Settings use `SharedPreferences`.

### Audio pipeline

- `AudioRecord`, mono PCM 16-bit, `22050 Hz`, buffer size `2048` samples (about `92 ms`).
- `PitchDetector` analyzes on a background coroutine, computes RMS, calls `AudioFrameQualityAnalyzer`, runs `OnsetAndPitchMatcher`, and marshals UI callbacks to Main.
- `YinPitchDetector` implements squared difference, CMNDF, threshold/local-minimum search, and parabolic interpolation over approximately `80..900 Hz`.
- `AdaptiveOnsetDetector` uses existing RMS/noise-floor thresholds: silence RMS `0.004`, minimum signal RMS `0.012`, noise SNR threshold `6 dB`, clipping level `0.999`, overload clipping ratio `0.01`, plus adaptive noise/rise thresholds.
- `AudioFrameQuality` reports RMS, peak, clipping ratio, noise floor, SNR, signal confidence, status, capture timestamp, analysis start/end, capture-to-analysis latency, and analysis duration.
- Only `VALID` frames emit microphone strikes to assessment. Silent, low-signal, noisy, overloaded, and invalid frames are not successful strike evidence.
- `AudioAnalysisSession` owns subscriptions and microphone lease. `PitchDetector` uses a generation token and cleanup to reject stale callbacks and release `AudioRecord`.
- This audio path is implemented and covered by synthetic/JVM tests, but physical Handpan, room, device, and end-to-end latency validation are not complete.

### Practice and session lifecycle

`PracticeSessionContext` transitions `ACTIVE -> PAUSED -> ACTIVE -> FINALIZED`. It records `sessionId`, `patternId`, monotonic start/end timestamps, restart count, paused duration, elapsed duration, and active duration. `PracticeEngine` creates the context when acoustic assessment starts, pauses/closes analysis on pause, resumes analysis on resume, finalizes pending targets on stop, and handles natural completion. Restart creates a new context identity with isolation from the old session. Active-session/process-death recovery is not implemented.

Canonical identity is preserved as:

```text
PracticeSessionContext.sessionId
 -> AcousticPracticeEvaluator
 -> MusicalTargetIdentity
 -> AudioAnalysisSession
 -> DetectedStrikeEvent
 -> AssessmentTimeline
 -> FinalizedAssessment
 -> AssessmentEntity / EvidenceEntity / progress projection
```

Timeline event IDs are unique, timeline session IDs are validated, target registry consumes event IDs once, evaluator rejects stale session IDs, and detector generation rejects old callbacks.

### Assessment and evidence semantics

- `CORRECT`: target and note match; timing is scored; retained in timeline and canonical evidence.
- `WRONG`: target timing context exists but detected note is wrong; retained and scored, never correct evidence.
- `UNKNOWN`: onset exists without a valid/matched pitch; retained and scored as non-correct, never correct evidence.
- `MISSED`: pending target expires or finalization closes it; no detected timestamp is fabricated; retained for scoring/context but excluded from valid evidence count.
- `EXTRA`: strike has no eligible target; retained for false-strike scoring but excluded from target evidence.

`AssessmentSessionValidator.derive(session, timeline)` is the canonical validator. `SkillEvidenceCalculator.calculateValidEvidence(session, timeline)` requires a finalized valid session and produces `CanonicalAssessmentMetrics` (timing, pitch, note accuracy, completion, miss/false-strike rates, consistency/confidence, and optional rhythm/dynamics/speed/technique metrics). Deprecated caller-supplied quality overloads remain for compatibility and should not be used for new code.

### Durable persistence and Room schema

Room is currently version `5`:

- Existing `patterns`: custom pattern storage, primary key `id`.
- Existing `practice_progress`: primary key `patternId`; aggregate practice count, timestamps, BPM, duration, and completed rounds.
- Existing `lesson_progress`: primary key `lessonId`; completion, stars, best score, attempts, and last-practiced time.
- Existing `recording_tracks`: primary key `id`; event-based performance tracks and serialized timeline metadata.
- `assessments`: primary key `sessionId`; finalized assessment summary, pattern/BPM, completion time, duration, validity, quality/signal metrics, event counts, score metrics, and consistency.
- `assessment_evidence`: primary key `sessionId`; canonical evidence snapshot and metrics linked to the assessment identity.
- `processed_assessments`: primary key `sessionId`; session-derived projection/idempotency ledger.

Migration history:

- `1 -> 2`: adds `lesson_progress` and `recording_tracks`.
- `2 -> 3`: adds recording BPM and time signature.
- `3 -> 4`: adds recording `timelineEventsJson`.
- `4 -> 5`: adds `assessments`, `assessment_evidence`, and `processed_assessments`.

Room entities remain outside domain logic. `FinalizedAssessment` is the domain contract; Room entities map from it. Assessment/evidence snapshots are durable, but full assessment event timelines and active sessions are still not persisted.

### Idempotency and progress

`HandpanRepository.persistFinalizedAssessment()` requires a valid finalized assessment and matching evidence session ID, inserts with `IGNORE`, and performs assessment, evidence, ledger, and existing aggregate progress projection in one Room transaction. Same `sessionId` is ignored on a second finalize; a new session ID with the same pattern is independent. Running, invalid, stale, or non-finalized sessions do not produce `FinalizedAssessment` and are not durably stored.

The existing aggregate fields remain pattern-based and are not themselves session history. Lesson progress is lesson-derived and still has no durable assessment session key. Recommendation, streak, weak-area, skill graph, and adaptive difficulty projections are not implemented.

### Calibration

`AudioCalibrationSession` is runtime-only. It consumes existing `AudioFrameQuality` and exposes `NOT_STARTED`, `LISTENING`, `READY`, `NO_SIGNAL`, `TOO_NOISY`, `OVERLOADED`, and `FAILED`. Three consecutive valid frames are required by default for `READY`; invalid input and out-of-order capture timestamps fail the session; `reset()` returns to `NOT_STARTED`. No device-specific calibration profile or calibration persistence exists. JVM/synthetic tests do not constitute real microphone or physical Handpan validation.

### UI state

`PracticeScreen` displays real/virtual mode, microphone availability, calibration status, live pitch/timing feedback, count-in, pause, restart, loop, metronome, and summary. There is no assessment history screen, recommendation UI, or complete accessibility announcement model. Navigation is manual enum-based screen switching rather than a persisted navigation back stack.

### Test strategy and current gates

Tests are primarily JUnit/Robolectric/in-memory Room and deterministic synthetic audio tests. They cover timing/scheduling, session identity, lifecycle, scoring, evidence rules, Room basics, calibration states, and durable assessment readback/idempotency. They do not prove physical microphone behavior, room noise robustness, device latency, Bluetooth behavior, TalkBack behavior, or release signing.

The five gates were green on the implementation state before this documentation-only edit and were not rerun for documentation changes:

- `:app:compileDebugUnitTestKotlin`: PASS, exit `0`
- `:app:testDebugUnitTest`: PASS, exit `0`
- `test`: PASS, exit `0`
- `lint`: PASS, exit `0`
- `assembleDebug`: PASS, exit `0`

### APK baseline

The last validated debug artifact from the durable-persistence implementation was `app/build/outputs/apk/debug/app-debug.apk`, about `19 MB`, package `com.sharn.handpan`, versionName `1.0.0`, versionCode `1`, minSdk `24`, targetSdk `36`, compileSdk `36`, with `RECORD_AUDIO` and `VIBRATE` permissions. The recorded SHA-256 was `9d1cf76047b2089fd36659cad5ea676626488424bd3a94c461045e3af3396883`. Verify the artifact again if the current APK timestamp or source commit differs.

### Completed phases

1. Core Handpan notation, patterns, scheduling, synthesis, Compose screens, and local progress foundations.
2. Acoustic analysis foundation: AudioRecord path, YIN pitch detection, adaptive onset/matching, quality metrics, and synthetic regression coverage.
3. Canonical assessment lifecycle: PracticeSessionContext, monotonic timing, pause-aware duration, target identity, finalization, validation, and evidence boundary.
4. Real Handpan setup/readiness slice: AudioCalibrationSession, quality-derived readiness states, reset/order checks, and Practice HUD exposure. No hardware validation was claimed.
5. Durable Assessment -> Evidence -> Progress slice: FinalizedAssessment, Room v5 entities/DAOs/migration, session-keyed idempotency, repository transaction, readback test, and documentation sync.

### Remaining gaps and risks

- Full timeline/event persistence and active-session/process-death recovery.
- Durable calibration/device profile and physical Handpan validation.
- Device matrix, room-noise, weak-strike, sustain, overlap, Bluetooth, and latency validation.
- Recommendation engine, adaptive difficulty, skill graph, weak-area analysis, streaks, and professional curriculum progression.
- History UI and richer progress projections.
- Accessibility semantics/live announcements and responsive landscape/tablet validation.
- Per-frame allocation/backpressure/GC and end-to-end latency measurement.
- Release signing, R8/shrinker validation, release APK, install/upgrade testing.
- Deprecated API cleanup and session-aware lesson progress.

Known non-blocking warnings from prior validation include missing `google-services.json` under the configured WARN strategy, an unstripped `libandroidx.graphics.path.so`, and existing deprecated API warnings. Do not treat green JVM gates as device or release evidence.

### Next vertical slice (proposal only)

The next recommended slice is **physical-device validation and calibration persistence design**, beginning with an audit and no schema change until the device test matrix is known. Alternative product work should not start before deciding whether full assessment timeline persistence or device evidence is the next correctness priority.

### CONTINUATION PROTOCOL FOR NEXT AI

1. Do not restart repository discovery; read this file first.
2. Read `docs/PROJECT_HANDOFF.md`.
3. Inspect `git status --short --branch` and treat existing dirty changes as intentional until proven otherwise.
4. Never run `git reset --hard` or `git clean -fd`.
5. Verify the environment minimally; do not reinstall the toolchain unnecessarily.
6. Do not repeat completed phases or duplicate existing entities/contracts.
7. Preserve Real Handpan First and keep virtual input out of acoustic evidence.
8. Start with a compact audit and state one falsifiable hypothesis.
9. Choose the smallest vertical slice and keep domain logic out of Compose.
10. Run focused tests before the five gates.
11. After implementation run compile, unit tests, aggregate tests, lint, and assembleDebug.
12. Sync documentation only after behavior is verified.
13. Keep synthetic/JVM evidence explicitly separate from physical-device evidence.
