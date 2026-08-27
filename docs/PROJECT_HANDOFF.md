# Project Handoff

## Project overview

An offline Kotlin Android handpan learning app centered on numeric handpan notation, acoustic practice, pattern scheduling, sound synthesis, and local progress.

## Architecture map

`PracticeEngine` controls playback and practice phases. `PracticeSessionContext` owns canonical session identity and monotonic lifecycle state. `PatternScheduler` creates musical targets. `AcousticPracticeEvaluator` matches microphone strikes to targets. `AudioAnalysisSession` owns detector subscriptions. `AssessmentTimeline` stores expected and detected assessment events. `AssessmentSessionValidator` derives quality. `SkillEvidenceCalculator` accepts only finalized valid session evidence through the session-based API.

Assessment integrity is enforced at both boundaries: timeline events must have matching `sessionId` and `assessmentSessionId`, and the evaluator ignores detected strikes from stale sessions before target matching. This preserves restart isolation and prevents prior-session events from entering a new assessment.

When pending targets are finalized as `MISSED`, their scheduler context is retained and no detected timestamp is fabricated. Missed notes affect scoring and valid-event counts but do not invalidate otherwise contextual evidence by themselves.

Deterministic audio regression fixtures verify zero false positives for silence, one onset for attack+sustain, four of four repeated strikes with zero false positives/negatives, and YIN pitch errors below `0.34` cents for low, mid, and high synthetic tones. The benchmark does not replace physical-device or real-room validation.

Room and repository persistence are intentionally separate from the pure assessment contract. UI and ViewModel consume state but do not own session identity.

## Completed phases

- Core handpan notation, pattern model, timing, scheduling, audio synthesis, and acoustic detection.
- Assessment timeline, scoring, target context, and learning evidence foundations.
- Toolchain stabilization: compile, unit tests, aggregate tests, and lint pass in the recorded environment.
- Phase 3F: canonical session lifecycle and derived assessment quality.

## Incomplete or intentionally deferred

- Frame-level noise, clipping, and rejected-audio aggregation.
- Migration/removal of legacy caller-supplied quality APIs.
- Full persistence of assessment summaries.
- Device-specific microphone calibration and latency compensation.

## Known limitations

The legacy `AssessmentSessionValidator.validate(timeline, durationMs, signalQuality, restartCount)` and `calculateValidEvidence(timeline, quality)` APIs remain deprecated for compatibility. New code must use `PracticeSessionContext` plus `AssessmentTimeline` and the derived quality path.

`AudioAnalysisSession` retains a legacy acquire overload for recording and existing isolated callers; the assessment evaluator binds the canonical session ID before acquisition.

## Next recommended tasks

1. Add explicit integration tests for natural completion, restart isolation, and detector-emitted event identity.
2. Decide and document the removal timeline for deprecated quality APIs.
3. Design frame-level audio quality metrics separately from the session contract.
4. Add assessment-summary persistence only in a separately authorized phase.
