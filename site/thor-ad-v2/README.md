# Thor Back Button Hero 2.0 — advertisement

The owner-approved 30-second film: 720 × 1280, silent, with a 60 fps timeline.
The 3D model editor is separate at `../model-editor/`; the film imports its model,
geometry and design renderer. Both use the same model files and MIT-licensed
Three.js distribution, without a build step or CDN.

From the repository root:

```sh
python3 -m http.server 8766 --bind 127.0.0.1 --directory site
```

Open `http://127.0.0.1:8766/thor-ad-v2/` or add `?autoplay`.
Play/pause, frame stepping with arrow keys, chapter selection, scrubbing,
fullscreen and still-frame download remain available. Video recording requests
60 fps and offers MP4 or WebM according to browser support. Actual delivery rate
depends on browser and hardware; there is no audio track.

## Film structure

- 0–4.4 s: closed device opens, centred app name and 2.0, feedback line.
- 4.4–8 s: Back press, wireframe reveal and pink shortcut layer.
- 8–13 s: Home as Back, one shared shortcut layer.
- 13–19 s: independent Home layer rises in Kawasaki green (#66ff00).
- 19–26 s: nine feature statements with eased motion, typography and focus.
- 26–28 s: device exits and the complete endcard appears.
- 28–30 s: opaque, unchanged endcard for reading.

The display logo fades into the screen content and becomes a cyan monochrome
watermark in the design view. Corner branding is circular. Back/Home labels
remain attached to the corresponding controls. All bindings are examples.
Push-to-talk is explicitly optional for gameplay recording.

## Verification

```sh
node site/thor-ad-v2/check-film.mjs
```

Checks cover visible-pane framing, separation, modifier presses, reveal order,
the extended intro, state-specific headlines, product exit and the static final
two seconds. They do not certify perceived image quality or capture frame rate.
Model geometry/input checks are documented in `../model-editor/README.md`.

The film's visual edit is approved. Before public distribution, verify the
2,000+ GitHub download claim and the depicted features against the release that
will ship. This local browser source is not a broadcaster-specific master.

## Provenance and licenses

Project-owned code, geometry, illustrations and motion are independently authored
under the repository's 0BSD license. The existing project icon is reused. No new
font, external model, generated landscape or runtime dependency is included.
Three.js remains separately MIT licensed; its full notice is preserved at
`../model-editor/vendor/LICENSE` and linked from the player and model editor.
