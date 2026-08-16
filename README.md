# Simpleton's Shader Loader (SSL)

Open-source shader loader for Minecraft 1.12.2. Supports OptiFine-format shader packs.

Minecraft 1.12.2, Forge. No license currently declared in `mcmod.info`.

Known recurring issue: MC's offset-678 buffer clear wipes the composite pass — worked around
via `RenderGameOverlayEvent.Pre` plus a `needsBlit` fallback for F1 (hide-HUD) toggling.
