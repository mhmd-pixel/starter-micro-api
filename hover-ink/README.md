# Hover Ink

Draw with the S Pen **without touching the screen**: hold the pen just above
the display and Hover Ink draws wherever it points. Built for the Galaxy S24
Ultra; works on any Android 11+ device whose stylus reports hover.

## Hover modes (the chip on the left of the toolbar)

| Mode | How hovering draws |
| --- | --- |
| **Hover** | Always. Pull the pen away from the screen to lift it and start a new line. |
| **Tap** | Tap the screen with the pen to switch hover ink on or off. |
| **Button** | Hold the S Pen side button while hovering. |

Touching with the pen also draws, with pressure. Fingers are ignored (palm
rejection) unless "Fingers can draw" is on.

## Features

Pen and eraser, 18 colours, brush size, steadiness (smooths hand shake while
hovering), undo/redo, undoable clear, paper/grid/dark backgrounds, save to
Gallery (Pictures/Hover Ink), share, full-screen with the screen kept on, and
the drawing is kept when you leave the app.

## Build and test

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # Robolectric tests that feed simulated S Pen hover events
```

If hovering does nothing, turn on Settings › Advanced features › S Pen › Air view.
If the side button opens Air command, use Hover or Tap mode, or stop the pen
button from opening Air command in the S Pen settings.
