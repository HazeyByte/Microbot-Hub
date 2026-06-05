# Architecture Learnings

## Microbot Architecture:
- Microbot is designed as a modular system with plugins that can be easily added or removed.
- Plugins interact with the game client through a Queryable API.
- The ClientThread ensures thread safety and proper execution of GameTick events.
- State-machine design patterns are used for bot behavior management.

## RuneLite Plugin Systems:
- RuneLite uses a plugin-based architecture where each plugin is responsible for specific functionality.
- Plugins are loaded and managed by the RuneLite client.
- Plugins can interact with the game client through various hooks and APIs.
```
