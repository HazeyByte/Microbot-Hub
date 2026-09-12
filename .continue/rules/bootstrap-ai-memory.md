---
globs: "**/*.md"
description: |-
  Initialize the .ai memory system using ONLY repository files.
  DO NOT rely on session context.
  Steps:
  1. Scan repository for:
  - Java plugin implementations
  - Microbot-Hub plugin modules
  - architecture documentation
  - threading rules
  - Queryable API usage patterns
  2. Extract stable knowledge into:
  - .ai/memory/architecture-learnings.md
  - .ai/memory/threading-lessons.md
  - .ai/memory/plugin-patterns.md
  - .ai/memory/queryable-api-rules.md
  - .ai/memory/anti-patterns.md
  3. If no session context exists:
  - infer structure from codebase directly
  - prioritize Microbot-Hub plugin implementations
  - use MICROBOT_SYSTEM_CONSTITUTION.md as grounding source
  4. Never ask the user for missing context.
  Always derive from repository.
alwaysApply: false
---

Initialize AI memory system using ONLY repository files.
