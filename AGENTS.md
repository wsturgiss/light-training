When making coding changes, don't bother trying to build currently.  You are temporarily stuck in WSL2 and the build tools are over in android studio in windows.  Ask the user to do it.  

Available icons live in `sdk/ui/src/main/kotlin/com/thelightphone/sdk/ui/LightIcons.kt` (the `LightIcons` object). Check there before assuming an icon doesn't exist or adding a new drawable.

Actions that apply to the whole current page (not a specific row/item) should generally be icon buttons placed along the bottom edge (`LightBottomBar`), no more than 3 of them. If a page seems to need a page-level action somewhere else (e.g. top bar) or needs more than 3 bottom-bar actions, stop and discuss it with the user before deviating.
