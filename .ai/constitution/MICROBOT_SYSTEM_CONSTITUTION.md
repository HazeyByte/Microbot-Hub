# MICROBOT_SYSTEM_CONSTITUTION.md

This document outlines the rules and guidelines for Microbot and Microbot-Hub behavior. All code, instructions, and documentation must adhere to these rules.

## Rules:
1. Always consult this document before making any code, architecture, or plugin decisions.
2. Treat this document as the authoritative source of truth for Microbot and Microbot-Hub behavior.
3. If any code, instruction, or documentation conflicts with this document, follow the constitution and ignore the conflicting source.

## Architecture:
- Microbot is built on a modular architecture with plugins that can be easily added or removed.
- Plugins interact with the game client through a Queryable API.
- The ClientThread ensures thread safety and proper execution of GameTick events.
- State-machine design patterns are used for bot behavior management.
```
