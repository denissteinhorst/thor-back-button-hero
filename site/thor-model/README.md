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

## Intro preview

The viewer initially selects **Intro-Vorschau**, paused at the closed-device
starting pose supplied by the owner. **Play / Pause**, **Stop** and the horizontal
timeline share an absolute, deterministic animation clock. Scrubbing pauses at
that time; Play resumes there, Stop resets to zero, and playback stops at the
last frame. Arrow keys on the timeline advance in 1/60-second increments. The
frame readout is a 60 fps reference, not a claim about real-time rendering speed.

Default duration is **30 seconds**, adjustable from 2 to 30 seconds. The
advertisement uses an explicit edit clock (`intro-ad-clock.js`) over the existing
rig cues, preserving absolute seek reconstruction and all supplied camera poses:

- 0–11.2 s: opening, Back reach demonstration, Home-as-Back option, mirrored reach
  demonstration and the move into the frontal pose. No configuration card is
  visible during that last transition.
- 11.2–14.2 s: the five pause combinations, with physical key presses and eased
  rows; monochrome logo and pause bars only during each press.
- 14.2–18 s: possible-hotkey counts, synchronized button highlights and stick
  movement, followed by the smaller logo and **Available Functions**.
- 18–26 s: eight one-second feature beats. Each uses one large headline and a
  brief supporting line. **Almost no touch input needed.** stays visible above
  the device. Highlights cover swapping apps, bottom-screen app switching,
  screenshots, gameplay recording with push-to-talk, macro recording, controller
  focus, volume/brightness, and toggling screens/more.
- 26–30 s: a still, pause-friendly poster. A branded **33 functions.** header,
  compact product image, touch-input benefit and all six full function groups
  appear together in a three-column, two-row grid. There are no NEW badges or
  rotating side lists. This is intended as a readable paused frame or screenshot
  for sharing, rather than expecting every function to be read during playback.

The black outside-lid title rotates away as the hinge flicks open. The display
background is fully visible from 100°. **Support for both hands** and **Use the
Home button as Back too** are movie captions; they never change app preferences.
The duration input rescales the entire finished edit and preserves relative
playback progress. `introStoryTiming` / `sampleIntroStory` retain the underlying
47-second rig coordinates; public `introTiming` / `sampleIntro` expose the ad's
actual playback time. Earlier frame numbers therefore move in the shorter cut.

Images are fully decoded, painted and uploaded to GPU textures before the player
is enabled. Display content uses an opaque, depth-biased surface and fades its
emitted colour, preserving real lid occlusion while avoiding transparent-layer
competition with the OLED glass. Animated textures use stable linear sampling
without regenerating mipmaps on each upload.

The landscape drifts and gently zooms; a mask derived from the existing image's
warm pixels softly pulses the lava without tinting the surrounding rocks. Five
independently drawn bird silhouettes emerge from behind the upper-screen logo
as it fades in, with staggered paths and wingbeats. They start a 0.9-second flight
at the logo reveal and leave before the wireframe sweep.
These effects use absolute timeline seconds, so pause freezes them and seeking
backward restores the same image. No extra image, animation library or asset is
required. `intro-atmosphere.js` owns the independent motion samples and birds.

All six exact camera keyframes and the original 732:442 viewport ratio are retained.
The intro canvas preserves that ratio at other window widths. Titles and display
content are rendered inside WebGL, so future canvas capture can include them.
This step supplies a local preview, without video encoding, audio or publication.
The app version and landing page are unchanged.

**Modell bearbeiten** returns to the previous model view, hinge angle, design mode
and button/stick state; its dark displays remain available for geometry work.
The intro locks orbit and model controls to preserve the specified camera path.
The pose inspector continues to report the actual rendered camera and hinge.
It also reports the current animation time, wireframe/radial progress, reach
flash strength and Back pressure.

`intro-wireframe.js` renders complementary scissored normal/wire views with a
narrow additive glint, including controls and displays. It uses the existing
design geometry and prewarms its contour cache. Initially only Back returns to the normal
style, via `design.setSolidControl('back')`; its actual input rig remains pressed.
The upper wireframe display retains the existing logo as a centred monochrome
watermark: cyan `#82b6c4`, 20% opacity, 28% of the display width. The shader keeps
the original logo's luminance detail and circular silhouette. It follows the
actual lid and is revealed by the same wipe, remains throughout the held-Back
sequence, and is also present in the modelling viewer's native wireframe mode.
It uses the existing logo at runtime without a modified image asset. The glint
pass excludes the watermark so its rectangular plane never flashes.
`intro-back-hint.js` adds two ring pulses and a scene-space caption/leader for
Back and Home, including the two-line Home label. `intro-home-option.js` renders
the enlarged settings row and animated switch using project-owned wording.
`intro-callout.js` directly anchors the caption's top-right corner to the
lower-left front corner of the SD-slot cover, with only a narrow visual gap.
It extends down-left from that port (7–8 o'clock) and caches layout for a fixed
camera. Automatic silhouette avoidance must not push this close caption away.
This external placement is the convention for subsequent button explanations.

`intro-radial.js` adds a camera-facing laser with a short fading trail originating at Back. Its ray crosses
the actual projected positions of Home, the four D-pad directions, both sticks,
all four shoulders, Start, Select and all four facebuttons. Spatial angle sets
the encounter order. Each target immediately takes its reach colour on contact,
holds that colour until the laser finishes, then fades to its original style.
The same cubic ease-in-out and its inverse determine both contact times and the age of
the trailing illumination, which decays over 0.18 seconds instead of leaving a
filled fan. Rings appear only during the initial flyover. The design mode's
explicit solid-control list retains the revealed original geometry. The D-pad uses a clipped
original-material overlay and individual legends until all four quadrants are
revealed; its connected physical mesh and input state are preserved.

The reach palette is green for left controls and all shoulders, green-yellow
for Start, yellow for Select/facebuttons and red for the right stick. After the
colour fade, the original button materials remain visible without further flashing. Shoulder cues include depth-independent copies of the actual shoulder
geometry and readable L1/L2/R1/R2 markings, visible through the housing only while
the shoulder colour is lit. Tint/emissive materials are private clones; original materials are restored
after the fade and on exit. Other buttons remain unpressed, while Back stays
held. The watermark and external Hold Back caption remain visible. Absolute
timeline sampling reconstructs the fan, reveals and colour fades when seeking in
either direction. The fan/highlight geometry stays outside the device root so
it cannot change the existing camera framing or wireframe sweep bounds.

`intro-reach-gauge.js` draws a slim, gently curved reach scale in the bottom
viewport margin, outside the product. A subdued green/yellow/red track gains
colour behind a small progress marker. Easy/hard captions reverse for the Home
view. There are no instrument ticks or enclosing tachometer arc. Its composition
is independent of the held key's position; the laser still crosses the actual
projected buttons. The owner's Alamy image was a concept reference only; no stock
artwork is incorporated. Geometry, shaders and labels are original project code.

The pause and hotkey-count sequences share absolute input samples, so both
seeking directions release keys and centre sticks outside their cues. Both
analog sticks demonstrate their seven assignments in parallel. The screen table
counts four physical system keys, four D-pad directions, seven assignments per
stick, four shoulder keys and four face keys. The final catalogue represents all
33 actions supplied by the owner, grouped by benefit; configuration sub-options
are deliberately omitted. `intro-feature-catalog.js` owns that copy and
`intro-hotkey-demo.js` owns the large feature headlines and final poster.

`intro-timeline.js` owns timing/transport, `intro-player.js` owns playback controls,
and `intro-screens.js` adds screen/title content without changing the model
geometry or OLED materials. The nature background is an original Imagegen asset;
see [asset provenance and the exact prompt](assets/PROVENANCE.md).
Run `node site/thor-model/check-intro.mjs` for poses, fade sequencing, the 100°
threshold, shortened logo stand, duration changes, reverse seeking and playback controls.
Run `node site/thor-model/check-intro-screens.mjs` for lid/base attachment, title
orientation and occlusion, UV coordinates, captions and preservation of model materials.
The same checks cover texture readiness, opaque colour fades, depth bias, stopped
texture uploads while paused, background margins and deterministic flock positions.
Run `node site/thor-model/check-intro-feature.mjs` for complementary render passes,
renderer state restoration, Back-only styling, outside caption placement and
two pulses without releasing Back. Visual and automated checks are now owner-led:
do not run them unless the owner explicitly requests them.

## Copying animation keyframes

**Aktuelle Position** shows live JSON; **Position kopieren** copies a snapshot.
If clipboard access is unavailable, the field is selected for manual copying.
The snapshot uses full numeric precision and includes the active camera type,
position, quaternion (x/y/z/w), up vector, OrbitControls target, zoom, clipping
planes and projection parameters, plus the viewport dimensions. Model position,
quaternion, scale and the actual snapped hinge angle are included, together with
the design mode and button/stick state. Positions use model millimetres and the
hinge angle uses degrees. Orbiting moves the camera around the model; the root
model transform normally stays at its identity. Reproduce the viewport aspect
ratio as well as the camera projection for the same framing. The field updates
after rendering, including while orbit damping settles. It does not store poses
across reloads or import keyframes.

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
