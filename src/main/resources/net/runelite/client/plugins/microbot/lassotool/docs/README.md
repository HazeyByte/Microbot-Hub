# Lasso Tool

A developer/debugging tool for Microbot that lets you draw freehand "lasso" areas directly on the game screen (inspired by Photoshop's lasso tool).

## Features
- Toggle lasso drawing mode with a configurable hotkey.
- Left-click + drag to draw an arbitrary polygon area on screen.
- Live preview of enclosed tiles (yellow highlight) while drawing and immediately after release.
- Tile selection uses precise screen-space point-in-polygon test on projected tile centers (no live point snapping of the lasso curve).
- On release: computes the set of WorldTiles inside the lasso.
- Captures and reports:
  - All tile objects (with ID, name, location) inside the area.
  - Nearby players.
- Exports:
  - Auto-copies a ready-to-paste Java `HashSet<WorldPoint>` mesh to clipboard (perfect for `MLMMiningSpot` or custom areas).
  - Can be used to replace hardcoded arrays in plugins like Motherload Mine.
- Visual overlay shows your current lasso + highlighted tiles + previously captured areas.

## Usage (especially for MLM)
1. Go to the area you want to define (e.g. upper west/east chamber in Motherlode Mine).
2. Press your configured hotkey to enter lasso mode.
3. Drag around the walkable floor + wall veins you care about.
4. Release mouse — tiles are calculated.
5. The mesh code is auto-copied (or press capture hotkey / use in-game).
6. Paste the generated `Set<WorldPoint> mesh = ...` into your custom spot definition or update `MLMMiningSpot`.
7. The `contains` logic will now use your precise drawn area instead of rectangles or hand-listed points.

This solves the "lots of state arrays of locations" problem for irregular areas.

## Config
- **Toggle Lasso Mode**: Hotkey to enter/exit drawing.
- **Lasso Color / Fill Color**: Visual customization.
- **Auto Copy to Clipboard**: On finish, immediately copy the Java mesh snippet.
- **Snap to Tiles**: (Reserved for future; current behavior uses raw freehand + exact center-in-polygon tile inclusion for maximum accuracy and thread safety).

## Technical Notes
- Uses `Perspective.getCanvasTilePoly` + screen-space point-in-polygon tests to determine enclosed tiles.
- Leverages Microbot's `Rs2TileObjectCache` / player caches for fast "grab all data" queries.
- Pure client-side visual + data export tool. No automation on its own.
- Works great alongside the agent server / hot-reload for defining test regions on the fly.

## Future Ideas
- Named saved areas persisted to config.
- Full side panel with captured entity table + filters.
- "Apply to current script" runtime support (e.g. for MLM dynamic spots).
- Export as JSON for scripts.
- Support for multi-plane or 3D (height) lassos.

## Credits
Built following patterns from RuneLite's Screen Markers / Ground Markers + Microbot's ShortestPath / DevTools overlays and Queryable API.

Version: 1.0.1
