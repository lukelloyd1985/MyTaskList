# Play Store listing assets

Ready-to-upload assets for Play Console's Store listing (see the main
[README](../../README.md#publishing-to-google-play) step 4):

| File | Use | Size |
| --- | --- | --- |
| `icon-512.png` | App icon | 512×512 |
| `feature-graphic-1024x500.png` | Feature graphic | 1024×500 |
| `screenshot-1-sign-in.png` | Phone screenshot | 1080×1920 |
| `screenshot-2-lists.png` | Phone screenshot | 1080×1920 |
| `screenshot-3-list-detail.png` | Phone screenshot | 1080×1920 |

The icon and feature graphic are built from the app's actual launcher
icon glyph and brand colors (`GreenPrimary` etc. in
`ui/theme/Color.kt`). The three screenshots are recreations of the real
Compose screens (`LoginScreen.kt`, `ListsScreen.kt`,
`ListDetailScreen.kt`) with representative sample data, built as HTML/CSS
rather than captured from a running device/emulator - swap in real
device screenshots once you have some if you'd rather use those instead;
these exist so the Store listing isn't blocked on capturing them.

## Regenerating

Source files are in `src/`. To re-render after editing one:

```
npm install -g playwright   # if not already available
node src/render.js          # writes the PNGs into this directory
```

`src/render.js` opens each HTML file at its exact target resolution in
headless Chromium and screenshots it - see the file for the
file → output → dimensions mapping.
