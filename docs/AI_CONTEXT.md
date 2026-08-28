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
