# AYN Thor Rainbow model study

Local, standalone Three.js viewer. From the repository root run
`python3 -m http.server 8765 --bind 127.0.0.1 --directory site` and open
`http://127.0.0.1:8765/thor-model/`.

Drag to orbit, scroll to zoom, or focus the viewer and use arrow keys. The hinge
slider covers closed (0°) to flat (180°). The landing page remains unchanged.
The hinge snaps to 120°, 150° and 180° within a ±6° capture zone. Three direct
buttons select those detents; the initial preview opens at 150°. Intermediate
angles remain available, including through keyboard arrows. Only the viewer
snaps: `setAngle(degrees)` continues to accept exact angles for animation code.
**Schulterdetail** frames the left shoulder and underside together; orbit from
there to inspect the side profile. The pressure inspector uses the same rig.

## Wireframe design view

**Wireframe** toggles a quiet navy/cyan design view. It draws contours and
significant form edges; large coplanar panels have no internal triangle lines.
The subtle background grid and subdued case keep the controls in focus.
**Tasten im Originalstil** independently retains the original caps, legends,
stick shafts/grips and shoulder paddles. Disable it for a complete wire design.
All input pressures, stick tilts and hinge poses continue working in either style.

This is a display-only simplified mesh, not a diagnostic view of every original
triangle. The renderer clusters curved meshes and filters edges below 25°.
Large thin panels retain their exact geometry so aperture boundaries cannot
turn into stray lines across the front. Antialiased screen-space contours stay
approximately 1.8 CSS pixels wide across zoom levels and pixel densities. The actual
model geometry, materials and input state are retained and restored on exit.
Wire overlays follow each part's local transform, do not intercept picking,
and are cached for repeat toggles. No additional dependency or asset is used.

Run `node site/thor-model/check-design.mjs` for quiet flat-panel contours,
independent control styling, instanced stick grips, pose preservation, cache
reuse, exact thin panels, contour pixel density and restoration/disposal checks.

## Geometry and fidelity

Project-owned JavaScript and viewer content are independently authored under the
repository's 0BSD license. All geometry is generated locally from original parametric surfaces and primitives; no STL,
CAD, mesh, texture, wallpaper, or code from Printables has been downloaded or
incorporated. The linked rough model's license could not be verified, so it is
not used. This is not a claim to ownership of AYN's industrial design or branding.

- Nominal closed exterior: 150 × 94 × 25.6 mm.
- Upper active display: approximately 132.8 × 74.7 mm (6-inch, 16:9).
- Lower active display: approximately 75.1 × 65.4 mm (3.92-inch, 1240:1080).
- Control centres are registered against a near-frontal hardware photo. Shell
  cross-sections, radii, ports and hinge details remain photo-based estimates,
  not measured CAD. Not suitable for manufacturing. See [the reconstruction
  audit](RECONSTRUCTION.md) for the discrepancy list, sources and evidence gaps.
- Rainbow shell and controls are visually approximated from the user's product
  photo. No measured color data is available. Lighting changes perceived colors.
- Displays are powered off; reflections use an original procedural studio environment.
- Frontal view uses an orthographic camera and opens the hinge to 180°.
- Run `node site/thor-model/check-model.mjs` for geometric integrity checks (Node 24).

Reference sources (research only; no reference imagery shipped):

- https://www.ayntec.com/products/ayn-thor (manufacturer, display sizes)
- https://retrodb.info/console/thor (nominal external dimensions)
- https://droix.net/blogs/wp-content/uploads/2025/08/AYN-Thor-Rainbow-Colour.jpg

## Third-party component

`vendor/` contains unmodified Three.js 0.180.0 files from
https://registry.npmjs.org/three/-/three-0.180.0.tgz:
`build/three.module.js`, `build/three.core.js`,
`examples/jsm/controls/OrbitControls.js`, and
`examples/jsm/geometries/RoundedBoxGeometry.js` (flattened paths only).

Three.js is separately licensed under MIT, copyright © 2010–2025 three.js
authors. Its complete original notice is retained in `vendor/LICENSE` and
linked from the viewer. It is not relicensed as 0BSD. There are no additional
runtime dependencies, external requests, or CDN requirements.

## Programmatic button and stick poses

`createThor()` now returns `inputs` in addition to `root`, `hinge`, and `setAngle`.
The viewer exposes the same API as `window.thorInputs`. Setters apply the pose
immediately; animation code can supply intermediate values without a separate clock.
They return the API for chaining.

```js
const thor = createThor();
scene.add(thor.root);

thor.inputs.setButton('A', 1);                 // fully pressed
thor.inputs.setButton('A', 0.5);               // half travel
thor.inputs.setButton('A', 0);                 // released
thor.inputs.setButton('L2', 0.8);              // analog trigger
thor.inputs.setDpad(0, 1);                     // rock upward as one rigid part
thor.inputs.setStick('left', { x: 0.6, y: 0.8, press: 1 }); // tilt AND L3
thor.inputs.setButton('L3', 0);                // release click, keep that tilt
thor.inputs.setStick('right', { x: -1, y: 0, press: 0.5 });
thor.inputs.releaseAll();                     // every axis and press neutral
```

Button names (`inputs.buttons`):

- `A`, `B`, `X`, `Y`, `select`, `start`, `home`, `back`, `AYN`, `power`
- `volumeDown`, `volumeUp` (opposite ends of one shared rocker)
- `L1`, `R1`, `L2`, `R2`, `L3`, `R3`
- `dpadUp`, `dpadDown`, `dpadLeft`, `dpadRight` (one shared D-pad pivot)

Pressures accept finite numbers clamped to 0…1, or `true`/`false`. Axes accept
finite numbers clamped to −1…1: x positive = right, y positive = up/away from the
player. Diagonals are normalized to a circular limit. Opposing D-pad or volume
inputs cancel mechanically. Unknown controls and non-finite values throw without
partially changing the pose.

`setStick('left' | 'right', { x?, y?, press? })` accepts partial updates. Its `press`
is the same state as `setButton('L3' | 'R3', …)`; changing one cannot reset or
contradict the other. Left and right sticks remain independent.

`getState()` returns a separate snapshot containing `buttons`, resolved `dpad`
axes, and `sticks.left/right` including their click pressure. `subscribe(fn)`
immediately reports that snapshot and subsequent input changes; it returns an
unsubscribe function. Shell, inlays, wells, collars and control legends retain
their intended attachment: only the cap/shaft and its own marks move.

The D-pad is one connected closed mesh with shallow diagonal grooves. It rocks
up to 5° around a common pivot. Sticks tilt up to 16° in any direction and can
independently descend 0.45 mm for L3/R3. Other travels and trigger angles are
illustrative photo-based values, not measurements of the real switch mechanism.
The AYN cap uses the dark stick plastic and depresses along its inclined mount.
Its bridge has broad moulded side ramps and a slight (3°) forward decline.
L2/R2 rock up to 18° around internal pivots: the finger edge enters the housing
while the opposite edge counter-moves. Their thin contact paddles overhang
narrower moving stems. The underside and sidewall form two rounded shoulder
domes, approximately 5.2 mm above the broad underside in the reconstruction.
These details were revised against the owner's motion video and rear photos;
the previous lower-edge pivot was an incorrect interpretation of still photos.
Poses are intended for an open device; the lid does not constrain or reset held
inputs automatically. No real keyboard, gamepad or Android events are emitted.

The collapsible **Tasten und Sticks testen** inspector exercises this same API.
It allows simultaneous presses, gradual pressure and independent stick axes.
**Alles loslassen** resets the model. Changing the selected control does not
release other held inputs; **Stick zentrieren** preserves its L3/R3 click.

Run `node site/thor-model/check-inputs.mjs` to check every button channel, the
D-pad's connected topology and rocking directions, cardinal/diagonal stick poses,
independent L3/R3 clicks, pressed-label visibility, exact release, and invalid
input handling. Existing static geometry checks remain in `check-model.mjs`.
Run `node site/thor-model/check-hinge.mjs` for the detent capture boundaries,
continuous intermediate angles and opening/closing direction checks.
Run `node site/thor-model/check-closure.mjs` for 537 case-junction probes,
rounded shoulder profiles, paddle/stem proportions and the trigger pressure
sweep. These check both individual parts and assembled seams. The fascia's
upper wall, a continuous deck flange and shared underside boundary close the
previously exposed junctions while retaining intended ports and vents. Sidewall
and underside share welded vertices and normals through the shoulder domes.
