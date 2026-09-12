# Anti-patterns

## Common Pitfalls:
- Avoid hardcoding values that can change dynamically.
- Do not use global variables or shared state across plugins.
- Ensure that all interactions with the game client are thread-safe.
- Avoid blocking the main thread with long-running operations.
```
