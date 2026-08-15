When making coding changes, don't bother trying to build currently.  You are temporarily stuck in WSL2 and the build tools are over in android studio in windows.  Ask the user to do it.  

Available icons live in `sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/LightIcons.kt` (the `LightIcons` object). Check there before assuming an icon doesn't exist or adding a new drawable.

Actions that apply to the whole current page (not a specific row/item) should generally be icon buttons placed along the bottom edge (`LightBottomBar`), no more than 3 of them. If a page seems to need a page-level action somewhere else (e.g. top bar) or needs more than 3 bottom-bar actions, stop and discuss it with the user before deviating.

## Working with the `sdk/` directory

`sdk/` is not our code — it's vendored from Light Phone's own `light-sdk` repo (see the `upstream` git remote) and periodically synced in. `light-keyboard` (and any other `libs.light.*` dependency) is Light Phone code too, but consumed as a compiled binary dependency rather than vendored source.

- **Do not hand-edit files under `sdk/`.** Any local patch there will conflict with (or silently diverge from) the next `upstream` sync, creating repeated manual merge work.
- **Using the SDK's public API is completely normal and expected** — call `sdk/ui`, `sdk/client`, `light-keyboard`, etc. from `tool/`/`examples/` freely, the same as any other dependency. The concern is only ever about *modifying* `sdk/` source, never about *consuming* it.
- If `tool/` (or an example app) needs something the SDK doesn't currently expose publicly, solve it inside `tool/`/the example app itself rather than patching `sdk/`. If that's not reasonably possible, stop and raise it with the user — the options are usually: work around it locally, or flag it to be contributed/requested upstream in `light-sdk`.
