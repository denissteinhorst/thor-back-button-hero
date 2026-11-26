# Website, App-Simulation, film and 3D model

Everything outside the Android app that is published or built from this repository: the GitHub
Pages site in `site/`, the self-contained App-Simulation, the 30-second 2.0 film and the 3D model
editor the film is built on. None of it has a build step, a CDN request or a package manager.

## Contents

- [Layout](#layout)
- [Previewing and deploying](#previewing-and-deploying)
- [The App-Simulation](#the-app-simulation)
- [The 2.0 film (`site/thor-ad-v2/`)](#the-20-film-sitethor-ad-v2)
- [The 3D model editor (`site/model-editor/`)](#the-3d-model-editor-sitemodel-editor)
- [Model fidelity and sources](#model-fidelity-and-sources)
- [Checks](#checks)
- [Provenance and licenses](#provenance-and-licenses)

## Layout

| Path | What it is |
|---|---|
| `site/index.html`, `style.css`, `script.js` | The production homepage. |
| `site/guide.html`, `guide.css` | The single content source for actions, FAQ, setup, compatibility, screenshots and release summaries. The homepage reads it for its dialogs; on its own it is a script-free guide. |
| `site/hotkey-layers.js`, `hotkey-layer-state.js` | The homepage's Back/Home layer illustration. |
| `site/app-simulation.html` | Symlink to [`docs/app-simulation.html`](app-simulation.html), so Pages serves the same file the release ships. |
| `site/gesture-explainer.html` | Redirect from the App-Simulation's former name, so links in earlier posts keep working. |
| `site/assets/` | Screenshots, app/device illustrations and the 1.5.0 video and still linked by the README. |
| `site/thor-ad-v2/` | The 30-second 2.0 film, its embed and export player. |
| `site/model-editor/` | The standalone Three.js model editor and the shared Thor model and renderer. |

The homepage reads the current release and APK download from the GitHub API at load time, so
publishing a release never needs a site change. Each page load costs one unauthenticated API call
(60 per hour per IP), so avoid reload loops while testing.

## Previewing and deploying

```sh
python3 -m http.server 8766 --bind 127.0.0.1 --directory site
```

Then open `http://127.0.0.1:8766/` (homepage), `/thor-ad-v2/` (film, `?autoplay` starts it) or
`/model-editor/`. `.github/workflows/pages.yml` deploys `site/` to
`https://denissteinhorst.github.io/thor-back-button-hero/` on every push to `main` that touches
`site/**` or `docs/app-simulation.html`.

## The App-Simulation

[`app-simulation.html`](app-simulation.html) is a clickable device diagram that opens directly on
every function of the app. It is fully self-contained: `thor-base.png` and the app icon are
embedded as base64 data URIs, so the single file works offline after download. The master image
stays in [`assets/`](assets/); re-embed it there if it ever changes.

- It is served from Pages (`/app-simulation.html`, linked by the README and the homepage) and
  attached to every release by `release.yml`
  (`releases/latest/download/app-simulation.html`). Raw repository URLs would serve it as plain
  text, which is why neither link points into the repository.
- Parameters: `?autoplay` is the recording mode (light theme, intro, no Skip button);
  `?theme=light` or `?theme=dark` forces a theme. Both must keep working.
- The homepage embeds it with `?theme=light`.

## The 2.0 film (`site/thor-ad-v2/`)

The owner-approved film: 720 × 1280, silent, a 60 fps timeline, 30 seconds. It imports the model,
geometry and contour renderer from `../model-editor/`.

| Time | Content |
|---|---|
| 0–4.4 s | Closed device opens, centred app name and 2.0, feedback line. |
| 4.4–8 s | Back press, wireframe reveal and pink shortcut layer. |
| 8–13 s | Home as Back, one shared shortcut layer. |
| 13–19 s | The independent Home layer rises in green (#66ff00). |
| 19–26 s | Nine feature statements. |
| 26–28 s | Device exits, the endcard appears. |
| 28–30 s | Static endcard for reading. |

The player offers play/pause, frame stepping (arrow keys), chapters, scrubbing, fullscreen and
still-frame download. Video export requests 60 fps at 14 Mbit/s; the *Social Media* option
requests 30 fps at 2 Mbit/s (about 7.5 MB, file name suffix `-social`). Both prefer MP4 and fall
back to WebM; actual size depends on browser and hardware. All bindings shown are examples.

Before public distribution, verify the download-count claim and the depicted features against the
release that will ship. The browser source is not a broadcaster master.

## The 3D model editor (`site/model-editor/`)

A standalone Three.js viewer of an independently reconstructed AYN Thor.

- **Viewing:** drag to orbit, scroll to zoom, arrow keys when focused. The hinge slider covers 0°
  (closed) to 180° (flat) and snaps to 120°, 150° and 180° within ±6°; `setAngle(degrees)` still
  takes exact angles for animation code. *Schulterdetail* frames the left shoulder and underside.
- **Keyframes:** *Aktuelle Position* shows live JSON, *Position kopieren* copies a snapshot with
  camera type, position, quaternion, up vector, OrbitControls target, zoom, clipping, projection,
  viewport size, model transform, hinge angle, design mode and input state (millimetres and
  degrees). It neither persists nor imports poses.
- **Wireframe design view:** *Wireframe* draws contours and significant edges (below 25° filtered,
  about 1.8 CSS px wide) in navy/cyan; *Tasten im Originalstil* keeps the original control styling.
  It is a display-only simplification; geometry and input state are restored on exit.
- **Inputs API:** `createThor()` returns `{ root, hinge, setAngle, inputs }`; the viewer exposes
  `inputs` as `window.thorInputs`. Setters apply immediately and chain:

  ```js
  thor.inputs.setButton('A', 0.5);                            // pressure 0…1 or true/false
  thor.inputs.setDpad(0, 1);                                  // axes −1…1, x right, y away
  thor.inputs.setStick('left', { x: 0.6, y: 0.8, press: 1 }); // tilt and L3, partial updates allowed
  thor.inputs.releaseAll();
  ```

  Buttons: `A B X Y select start home back AYN power volumeDown volumeUp L1 R1 L2 R2 L3 R3
  dpadUp dpadDown dpadLeft dpadRight`. Opposing D-pad or volume inputs cancel; unknown controls
  or non-finite values throw without changing the pose. `getState()` returns a snapshot and
  `subscribe(fn)` reports it on every change (returns an unsubscribe function). The D-pad rocks
  up to 5°, sticks tilt up to 16° and descend 0.45 mm for L3/R3, L2/R2 rock up to 18°; these
  travels are illustrative. No real input events are emitted. The *Tasten und Sticks testen*
  inspector drives the same API.

## Model fidelity and sources

The model is generated from parametric surfaces and primitives written for this project. No STL,
CAD, mesh, texture or code from third parties is used; a rough community model was rejected
because its license could not be verified. This is not a claim to AYN's industrial design or
branding.

- Nominal closed exterior 150 × 94 × 25.6 mm; top display about 132.8 × 74.7 mm (6″, 16:9);
  bottom display about 75.1 × 65.4 mm (3.92″, 1240:1080).
- Control centres were registered by a projective transform from a near-frontal hardware photo
  (glass quad mapped to deck corners ±73.6 × −34.8…46 mm). These are image estimates, not
  measurements; lens distortion and pixel selection remain error sources.
- Shell cross-sections, radii, ports, hinge, trigger curvature and the Rainbow colours are
  photo-based estimates. No manufacturer CAD or calibrated scan was available; not suitable for
  manufacturing. Underside lettering is locally typeset, not traced logo artwork.
- Research sources (no imagery shipped): the AYN product page (ayntec.com/products/ayn-thor),
  retrodb.info (nominal dimensions), and hands-on photo reviews by techtactician.com,
  notebookcheck and retrohandhelds.gg.

## Checks

All checks use only Node's standard library (Node 24).

```sh
node docs/check-simulation.mjs            # App-Simulation contract
node site/thor-ad-v2/check-film.mjs       # film framing, press order, reveal, static endcard
node site/model-editor/check-model.mjs    # finite geometry, shell size, apertures, hinge clearance
node site/model-editor/check-inputs.mjs   # every input channel, D-pad topology, L3/R3, invalid input
node site/model-editor/check-hinge.mjs    # detent capture, intermediate angles, direction
node site/model-editor/check-closure.mjs  # case-junction probes, shoulder profiles, trigger sweep
node site/model-editor/check-design.mjs   # wireframe contours, styling, pose and cache restoration
```

They establish geometric and structural properties, not visual likeness or perceived video
quality. They are not part of `./tools/verify.sh`.

## Provenance and licenses

Project-owned HTML, CSS, JavaScript, copy, illustrations, motion and the Thor model are
independently authored under the repository's 0BSD license. Early design exploration used public
sites only as high-level references for hierarchy and interaction, without taking code, copy or
artwork. `site/model-editor/vendor/` holds unmodified Three.js 0.180.0 files
(`three.module.js`, `three.core.js`, `OrbitControls.js`, `RoundedBoxGeometry.js`). Three.js stays
MIT licensed, is not relicensed as 0BSD, and its notice is kept in `vendor/LICENSE` and linked from
the homepage, the film and the editor. No fonts or other third-party assets are included.
