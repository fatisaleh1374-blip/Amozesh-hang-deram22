# Architecture Decisions

## Real Handpan First

Acoustic microphone input and evaluation are the primary practice path. The product is designed around a physical handpan, timing feedback, pitch matching, and signal quality.

## Virtual mode is fallback

Virtual playback is useful when no physical instrument is available, but it must not silently stand in for acoustic evidence. This preserves the meaning of assessment data.

## Canonical session identity

A practice assessment needs one identity from start through scheduler targets, detector events, and timeline records. `PracticeSessionContext` owns the identity and lifecycle. Scheduler and evaluator receive it; `AudioAnalysisSession` is bound to it for assessment acquisition.

## Monotonic lifecycle timing

Session timestamps use `PracticeClock`. Active duration is elapsed duration minus accumulated paused duration. Wall-clock timestamps are not valid for assessment timing.

## Persistence remains separate

Room and repository persistence are outside the pure session contract. This prevents storage concerns from deciding lifecycle semantics and keeps the assessment pipeline testable without persistence.

## AudioEngine remains outside the session contract

`AudioEngine` plays synthesized or loaded samples. It does not own microphone assessment identity, detector events, pause accounting, or evidence validity, so Phase 3F leaves it unchanged.

## Deprecated compatibility

Legacy quality APIs remain only as deprecated compatibility surfaces. New evidence must be derived from a finalized `PracticeSessionContext` and its `AssessmentTimeline`.
