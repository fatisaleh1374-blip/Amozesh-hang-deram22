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
