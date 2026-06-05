---
description: Ensure that all responses include a continuation state summary in
  the specified format to maintain progress across sessions.
alwaysApply: true
---

When approaching context limits or after completing a meaningful step, output a CONTINUATION STATE section in the following format:

=== CONTINUATION STATE ===
Task: [What you were doing]
Current Focus: [What you are currently focusing on]
Files Involved: [List of files involved]
What has been completed: [Summary of completed tasks]
What is currently in progress: [Summary of ongoing tasks]
Next step: [Next exact step]
Open questions / risks: [Any pending decisions or risks]
=========================

This state must be sufficient to continue the work in a new chat without loss of progress.
