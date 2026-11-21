# GitHub Pages site

`index.html`, `style.css` and `script.js` are the production homepage. The selected
fourth concept now lives here; comparison pages and their unused assets are removed.
`guide.html` is the single source for actions, FAQ, setup, compatibility, screenshots
and release summaries. The homepage reads it for its dialogs; it also works as a
standalone, script-free guide with `guide.css`.

- `hotkey-layers.js` and `hotkey-layer-state.js`: the Back/Home layer illustration.
- `model-editor/`: standalone 3D editor, shared Thor model and renderer.
- `thor-ad-v2/`: 30-second product film, playback embed and export player.
- `gesture-explainer.html`: symlink to the self-contained app simulation in `docs/`.
- `assets/`: current screenshots, app/device illustrations and the 1.5.0 video still
  linked by the README and its historical release-post draft.

Preview from the repository root:

```sh
python3 -m http.server 8766 --bind 127.0.0.1 --directory site
```

Open `http://127.0.0.1:8766/`. GitHub Pages deploys `site/` on pushes to `main`.
No build step, downloaded fonts or new dependencies are required. The current
published release and APK download are read from GitHub; 2.0 is not published by
updating the website.

## Provenance and licenses

Project-owned HTML, CSS, JavaScript, copy, illustrations and the Thor model are
independently authored work under 0BSD. The earlier design exploration used
OpenLogi, Vue School, Landearly and the Awwwards catalogue as high-level references
for hierarchy and interaction, without incorporating their code, copy or artwork.
The retained page uses repository-owned media. Three.js remains separately MIT
licensed; its notice stays in `model-editor/vendor/LICENSE` and is linked from the
homepage, film and editor. No additional dependency or third-party asset is added.

## Checks

```sh
node docs/check-simulation.mjs
node site/thor-ad-v2/check-film.mjs
```

The model's geometry/input checks are documented in `model-editor/README.md`.
Browser checks cover the three layer modes, all information dialogs, search,
screenshot navigation, film and simulation embeds, and standalone editor/player.
