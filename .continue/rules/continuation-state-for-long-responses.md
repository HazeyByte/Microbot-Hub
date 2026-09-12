---
description: Ensure that all long responses include a continuation state summary
  to maintain progress across sessions.
alwaysApply: true
---

When work is in progress and the response may be long, always create a CONTINUATION STATE before finishing. If you are approaching a context limit or cannot complete the task in one response, you MUST output a CONTINUATION STATE section. The CONTINUATION STATE must include:
- current task
- progress made so far
- files being worked on
- exact next step
- any decisions or unresolved issues
