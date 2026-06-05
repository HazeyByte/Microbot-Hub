---
description: Ensure that all responses include a continuation state summary to
  maintain progress across sessions.
alwaysApply: true
---

When approaching context limits or after completing a meaningful step, output a CONTINUATION STATE section summarising:
- what you were doing
- what files are involved
- current progress
- next exact step
- any pending decisions
This state must be sufficient to continue the work in a new chat without loss of progress.
