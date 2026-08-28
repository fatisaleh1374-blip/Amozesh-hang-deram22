# Project Handoff

## Project overview

An offline Kotlin Android handpan learning app centered on numeric handpan notation, acoustic practice, pattern scheduling, sound synthesis, and local progress.

## Architecture map

`PracticeEngine` controls playback and practice phases. `PracticeSessionContext` owns canonical session identity and monotonic lifecycle state. `PatternScheduler` creates musical targets. `AcousticPracticeEvaluator` matches microphone strikes to targets. `AudioAnalysisSession` owns detector subscriptions. `AssessmentTimeline` stores expected and detected assessment events. `AssessmentSessionValidator` derives quality. `SkillEvidenceCalculator` accepts only finalized valid session evidence through the session-based API.

Assessment integrity is enforced at both boundaries: timeline events must have matching `sessionId` and `assessmentSessionId`, and the evaluator ignores detected strikes from stale sessions before target matching. This preserves restart isolation and prevents prior-session events from entering a new assessment.

When pending targets are finalized as `MISSED`, their scheduler context is retained and no detected timestamp is fabricated. Missed notes affect scoring and valid-event counts but do not invalidate otherwise contextual evidence by themselves.

Before acoustic practice feedback, `AudioCalibrationSession` consumes the existing `AudioFrameQuality` contract. It reports listening, ready, no-signal, noisy, overloaded, and failed states, requires three consecutive valid frames by default, rejects out-of-order capture timestamps, and can be reset for retry. This readiness path is deterministic and in memory; it has not been validated with physical handpan hardware and is not persisted.

Deterministic audio regression fixtures verify zero false positives for silence, one onset for attack+sustain, four of four repeated strikes with zero false positives/negatives, and YIN pitch errors below `0.34` cents for low, mid, and high synthetic tones. The benchmark does not replace physical-device or real-room validation.

Room and repository persistence are intentionally separate from the pure assessment contract. UI and ViewModel consume state but do not own session identity.

The finalized assessment path is now durable: a valid finalized `FinalizedAssessment` is mapped to `AssessmentEntity` and `EvidenceEntity`, both keyed by `sessionId`, and persisted by `HandpanRepository` in a Room transaction. The first insert also updates existing practice aggregate progress; duplicate finalization is ignored. This persists summary/evidence metrics and counts, not the full event timeline.

## Completed phases

- Core handpan notation, pattern model, timing, scheduling, audio synthesis, and acoustic detection.
- Assessment timeline, scoring, target context, and learning evidence foundations.
- Toolchain stabilization: compile, unit tests, aggregate tests, and lint pass in the recorded environment.
- Phase 3F: canonical session lifecycle and derived assessment quality.

## Incomplete or intentionally deferred

- Persistent calibration/setup history and richer frame-level noise, clipping, and rejected-audio aggregation.
- Migration/removal of legacy caller-supplied quality APIs.
- Full persistence of assessment event timelines and active sessions.
- Device-specific microphone calibration and latency compensation.

## Known limitations

The legacy `AssessmentSessionValidator.validate(timeline, durationMs, signalQuality, restartCount)` and `calculateValidEvidence(timeline, quality)` APIs remain deprecated for compatibility. New code must use `PracticeSessionContext` plus `AssessmentTimeline` and the derived quality path.

`AudioAnalysisSession` retains a legacy acquire overload for recording and existing isolated callers; the assessment evaluator binds the canonical session ID before acquisition.

## Next recommended tasks

1. Add explicit integration tests for natural completion, restart isolation, and detector-emitted event identity.
2. Decide and document the removal timeline for deprecated quality APIs.
3. Validate calibration/readiness with physical handpan recordings and real Android microphone devices.
4. Add full assessment-event persistence only in a separately authorized phase.
