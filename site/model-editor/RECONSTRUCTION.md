# Rainbow reconstruction audit — 18 November 2026

This records the first model's errors, the second revision's changes and the
remaining evidence gaps. It is not a declaration of a perfect or measured replica.

## Photo evidence inspected

Research images are kept outside the repository; none are shipped as textures.

1. AYN product renders, including front, outside lid and hinge:
   https://www.ayntec.com/products/ayn-thor
2. Original Rainbow render supplied by the owner:
   https://droix.net/blogs/wp-content/uploads/2025/08/AYN-Thor-Rainbow-Colour.jpg
3. Near-frontal physical unit, ports, side profile, bumper and trigger close-ups:
   https://techtactician.com/ayn-thor-hands-on-impressions-and-tests/
4. Rainbow hardware close-ups (AYN photos reproduced by Notebookcheck):
   https://www.notebookcheck-hu.com/Elo-fotok-az-AYN-Thorrol-hogy-gyoenyoerkoedhessenek-a-szemedben.1113128.0.html
5. Four physical units seen from underneath, including Rainbow:
   https://www.notebookcheck.net/AYN-Thor-poses-for-the-camera-in-new-demo-videos-and-live-photos.1119930.0.html
6. Physical Rainbow shoulder close-up:
   https://retrohandhelds.gg/ayn-thor-review/

## Observed discrepancies and corrections

| Area | Initial error | Revision |
| --- | --- | --- |
| Main controls | Sticks/D-pad/ABXY too far apart along the depth axis; system buttons guessed | Control centres registered from the near-frontal hardware photo |
| Base silhouette | Generic rounded cuboid, planar/thickness radii coupled | Custom loft: tall flat walls, independent plan-view corner radius, rolled underside |
| Control deck | Opaque flat cap, no real bores, no glass coat | One contiguous glass sheet with drilled openings and front-centre notch |
| Stick wells | Raised discs suggested a recess | Open circular deck apertures with inward-facing funnel geometry |
| Sticks | Tall cylindrical pegs, oversized cyan light ring | Lower stems, concave rubber crowns, fine perimeter knurling, unlit LED rings |
| D-pad | Four rectangular blocks and an incorrect centre disc | Four tapered, chamfered segments with open diagonal centre gaps |
| ABXY | Simplified discs, weak inlay and lettering | Rounded low caps, circular underlay, explicit X/Y/A/B placement |
| System controls | Plus/minus and placeholder symbols | Select/start/home/back line symbols with collars and shell-coloured caps |
| Front centre | No actual glass notch | Recessed bridge, AYN text button, two round standoffs |
| Upper lid | Thin generic box with incorrectly limited corner radii | Broad-radius shallow shell, gasket, glass and separately sized active OLED area |
| Hinge | Basic cylinders, questionable lid clearance | Segmented full-width barrel, fixed pivot, corrected closed clearance |
| Shoulder controls | Unlabelled rectangular blocks | Separate narrow L1/R1 strips and rounded L2/R2 paddles in sockets |
| Front ports | Black shapes over an unbroken wall | Perforated fascia, USB tongue, audio rim, microSD seam, volume/power markings |
| Underside | Incorrect centred straight slots and a label rectangle | Offset circular inlet, split grille slots, three exhaust openings, horizontal ribs, AYN text, screw locations |
| Materials | Flat colours, little glass reflection | Separate matte shell/rubber/glass surfaces; independent procedural studio reflections |
| Screens | Invented decorative wallpaper | Powered-off OLED surfaces for physical-object comparison |
| Inspection | Perspective-only views hid proportions | Orthographic frontal (180°) and underside views, plus free orbit |

## Control registration

Reference: `ayn-thor-120hz-mode-top-view-handheld.webp`, the photo linked from source 3.
The glass quad was manually identified in its 1152 × 771 pixel image as
(277,418), (856,417), (851,729), (274,735). A projective transform maps those
points to deck corners (-73.6,-34.8), (73.6,-34.8), (73.6,46), (-73.6,46) mm.
The deck extent is itself a photo-based estimate within the nominal 150 × 94 mm shell.

| Control | Photo centre (px) | Registered x,z (mm, rounded) |
| --- | --- | --- |
| Left stick | 345,505 | -56.3,-12.6 |
| D-pad | 350,615 | -54.8,15.5 |
| Right stick | 791,614 | 57.6,16.0 |
| ABXY group | 791,503 | 57.2,-12.7 |
| Select | 387,442 | -45.9,-28.6 |
| Start | 750,442 | 46.4,-28.4 |
| Home | 388,694 | -45.0,35.8 |
| Back | 746,690 | 46.3,35.6 |

These figures are reproducible image estimates, **not sub-millimetre measurements**.
Lens distortion, pixel selection and the estimated registration plane remain sources
of error. The small inferred asymmetries must be checked against a physical unit.

## Unresolved before calling this exact

- No manufacturer CAD, dimensional drawing, calibrated multi-view scan, or measured
  physical sample was available. Shell cross-sections, wall fillets, hinge cheek
  transitions, trigger curvature and port recess depths are inferred.
- The nominal overall dimensions and display diagonals do not determine these details.
- Colour cannot be calibrated from web photographs with unknown exposure, white
  balance, lighting and display processing. No official RGB/Lab or material sample.
- The underside lettering is locally typeset AYN text, not traced manufacturer logo artwork.
- Small vent spacing, screw recess depth, speaker mesh, lanyard recesses, mould marks
  and tiny engraved legends need macro-scale comparison. Lanyard side recesses are
  surface geometry, not a reconstructed internal channel.
- Visual comparison of the rendered revision against the photos remains pending;
  repository instructions require owner permission before capturing screenshots.

## Verification

`node site/model-editor/check-model.mjs` checks finite vertices and indices, nominal
shell width/depth, real deck/port apertures, outward-facing cap and inward-facing
bowl surfaces, stable hinge pivot and closed-lid control clearance. It does not
prove visual identity. Browser checks cover module loading, console errors,
view changes and the hinge range. Android's `./tools/verify.sh` is a regression gate,
not a test of the 3D model's resemblance to the hardware.

## Button and hinge correction pass

Owner feedback identified specific remaining defects. This pass changes:

- Shoulder caps now replace cut-out patches of the rear shell; the old unbroken
  wall hid them. The oversized rectangular grey socket has been removed. Narrow
  crowned bumper strips and separate rounded trigger paddles wrap forward at the
  outer corners. Their L1/L2/R1/R2 legends sit in front of the cap surfaces.
- The AYN area is a raised, shell-coloured bridge. Both round standoffs now meet
  the closed upper bezel, while the AYN switch has independent clearance.
- The D-pad uses a light grey circular seat, sharing the ABXY inlay material;
  the previous dark cavity disc incorrectly darkened its entire background.
- Legend textures are alpha-cropped. Their physical dimensions now describe the
  visible letters/icons instead of including most of a transparent atlas. The
  system icons, ABXY letters, power symbol, volume minus/plus, TF CARD and shoulder
  legends have explicitly sized marks.
- Volume and power controls use true semicircular capsule ends, a wide shell
  recess, a narrow dark mechanical clearance and slightly recessed caps. The
  volume rocker remains one continuous cap with separate minus and plus marks.
- The two outer hinge cheeks have rounded noses. The central barrel and its
  joints now meet without the former roughly 1 mm gaps. A solid full-width rear
  saddle closes the uncovered strip between the barrel and the control deck.

Focused checks additionally verify closed-glass contact at both standoffs,
AYN switch clearance, unoccluded shoulder faces, light D-pad/ABXY material parity,
rounded outer hinge profiles, coverage across the hinge/deck strip and label
visibility relative to the underlying solid geometry. The checks establish these
geometric properties, not photographic identity; the dimensional and colour
uncertainties listed above still apply.

## Shoulder closure and stick-shadow correction

The preceding centre-ray checks did not establish closure of the whole shoulder
assembly. Owner inspection found remaining holes beside the caps and broken-looking
faces. This pass corrects both underlying constructions:

- The rear shell is no longer cut away at the shoulder corners. It is smoothly
  recessed behind the controls as a continuous backing wall, closing the former
  gaps between the cut boundaries and the independently shaped pocket liners.
- Cap and pocket geometry is welded and adaptively subdivided along shared edges
  **before** applying the curved surface deformation. Previously a few large
  front-face triangles spanned the bend, producing faceted/creased caps. Shared
  subdivisions preserve closed solids without T-junctions at the fillets.
- Checks sweep both shoulder backings at 0.5 mm intervals and require every
  sample to hit a rear wall. Each cap and liner must also have exactly two
  incident triangles at every undirected edge.

The old viewer orbited a camera around a device with a world-fixed key light;
its shadow therefore stayed at the same position on the device. The black disc
at the well floor strengthened that impression. The floor and bowl now share
an illuminated neutral material, and the shadow-casting studio key follows the
viewing frame. Shadow maps are rendered normally on each frame. A geometric
shadow-projection check verifies different cap/stem shadow footprints on the well
floor when inspecting the device from opposite sides. This is deliberate viewer
lighting, not an animated or painted shadow.

## Rendered shoulder-transition comparison

The owner authorized screenshots for the remaining model comparison. Rear and
side renders were compared with the physical-unit close-ups listed above, with
both closed and open lids. This revealed defects which closed-solid checks missed:

- The case join was still a filled slab following the original, unrecessed outline.
  Its edge protruded beside the shoulder caps as thin fins. It is now a closed,
  narrow perimeter band sampled along the same shoulder contour as the shell.
- The underside did not meet the recessed bottom edge. The recess now blends back
  to zero at the bottom rim, preserving the existing underside boundary.
- The rear/side wall had too few vertical samples through the recess transition.
  Denser shared rings smooth the transition without leaving large deformed quads.
- The lateral recess pulled the housing inward too far. Reducing that offset
  aligns the case cheek with the outer button skirts.
- The old bumper/trigger skirts stopped short of the sidewall. They now extend
  into the housing while retaining the rear contact faces and legends.
- The inspection camera now recentres on the actual device bounds when changing
  the opening angle, making the closed shoulder area easier to inspect.

Regression checks compare the actual join and wall intersections at the shoulder
and verify that both button skirts reach the lateral housing. Screenshots of the
revised rear and side views were inspected after the changes. This resolves the
identified transition defects in those views; it is not a dimensional certification
or a claim that all remaining photo-based details are exact.

## Articulated input model

The owner clarified that the D-pad is a single mechanical component. Its former
four separate cap meshes have been replaced with one connected, closed cross
with surface grooves. A shared pivot rocks the entire part and its legends;
pressing one edge lowers it while lifting the opposite edge.

All 22 button channels now have programmatic pressure values through `inputs`.
ABXY, system buttons and AYN translate into the deck; power moves into the front
fascia; volume uses a shared rocker; L1/R1 translate and L2/R2 pivot. Sticks have
independent continuous x/y tilt and L3/R3 axial travel. Static wells, collars,
inlays, case and hinge remain fixed. The input API is documented in README.md.

Dynamic visual checking exposed filled fixed plates behind the AYN button and
shoulder caps which hid the depressed faces. They are now open frames in the
movement area. Regression checks include visibility of legends at full pressure,
as well as closed-solid topology for the shoulder frames and D-pad.

Travel distances, pivot positions and limits remain independently authored visual
approximations; the new articulation does not establish measured hardware travel.

## Owner hardware photos: bridge and trigger motion

The owner supplied IMG_0569.JPG (AYN close-up), IMG_0567.JPG (low frontal bridge
profile), IMG_0570.JPG (trigger at rest) and IMG_0571.JPG (trigger pressed).
These private photographs were inspected locally as references only; they are
not bundled, used as textures or uploaded as part of the viewer.

- The AYN cap now shares the dark grey stick plastic, with a darker logo mark.
- The bridge is a closed loft with broad side ramps, rounded transitions and
  a gently inclined top. Its fixed surround and moving cap share that incline;
  the switch depresses normal to the face. The circular lid contacts retain
  their closed-lid contact height and sit on the revised bridge surface.
- The old high trigger pivot swung the lower cap toward the front. Both L2/R2
  now pivot near their lower ends: the upper contact edge folds rearward and
  downward. Cap and legend move as one rigid part, independently on each side.

The inferred 3° bridge incline and 20° trigger sweep are visual estimates from
perspective photographs, not measured mechanism angles. Geometry checks cover
the bridge's closed topology, ramps, top slope and lid-contact seating. Input
checks cover the trigger's entire pressure sweep and AYN's inclined travel.

The owner also specified hinge detents at approximately 120°, 150° and 180°.
The viewer applies a crisp ±6° snap near each detent and offers direct buttons.
The raw slider position is retained during adjustment so keyboard arrows can
leave a detent. Programmatic `setAngle` remains exact for future animations.

## Motion-video and rear-shell correction

IMG_0572.MOV shows the trigger from the side at roughly 0–3 seconds, from
the back at 4–8 seconds and the surrounding shell at 9–11 seconds. Frames were
decoded and compared locally. The footage corrects the preceding still-photo
interpretation: the trigger rocks around an internal fulcrum; the finger edge
enters the housing while the other edge moves in the opposite direction.
The lower-edge pivot incorrectly levered the paddle outward. Both mirrored
L2/R2 assemblies now use an internal axis and an estimated 18° sweep. L3/R3
remain the independent analog-stick clicks; the shoulder context was interpreted
as L2/R2 rather than a request to change the stick click.

The video and the owner's additional back photographs show a thin overhanging
paddle, a narrower stem and a shell dome flowing into the underside. The paddle
is now a single closed loft with a roughly 0.9 mm lip and a narrower stem. Its
fixed moulded cup blends into the rear wall. Two smooth domes are shaped into
both the underside and sidewall; the broad underside is approximately 3.1 mm
inward of their peaks, retaining the overall corner thickness. This remains a
photo-based geometric estimate, not a physical measurement.

Additional owner-supplied reference images, inspected locally and not shipped:

- https://i.redd.it/s0z3ubn0a1lf1.jpg
- https://encrypted-tbn0.gstatic.com/images?q=tbn:ANd9GcTka2Ek5Y9mFNfufsR9uQSuI6RhIbR6tLb7rErgoFGkF3DzamLZ5BPUi5AE&s=10
- https://i.redd.it/uek7xm6waj4h1.jpeg

The owner's report of see-through joints exposed gaps which the earlier
closed-part checks missed. The front-wall cutout extended above the port fascia;
the shell and glass perimeter also had different footprints without a joining
flange. The upper wall is now retained above the fascia, a continuous flange
joins the deck to the sidewall and the reshaped underside overlaps the lower
wall. Intended apertures remain open with their own recesses/interiors.
The separate underside plate has since been replaced with a cap triangulated
against the wall's exact lower boundary. They are one mesh with shared vertices
and normals through both domes; the vents retain inward-facing aperture walls.
This removes the former cracked-looking seam between separately shaded plates.

`check-closure.mjs` probes those actual assembled surfaces along 537 rays,
including shallow angles, and checks dome contours, lip/stem depths and pressed
legend visibility. `check-inputs.mjs` now checks both paddle ends throughout the
pressure sweep, rather than only one endpoint. The shoulder-backing sweep
follows the reshaped wall so it does not mistake air below the thinner central
underside for a hole. A dedicated **Schulterdetail** camera supports visual
comparison of the joint, dome and moving cap together.

## Stronger shoulder domes after owner comparison

The owner found the preceding dome too shallow against the same rear photos.
The crown is now centred 4 mm farther into the back panel, with an estimated
5.2 mm relief relative to its flat area (previously 3.1 mm). The broad panel stays
fixed; the extra volume is local to the shoulders. This increases the model's
maximum corner thickness and remains a photo-based estimate, not certified
150 × 94 × 25.6 mm CAD. Underside triangles are refined across their full span
so the stronger crown does not create long planar bridges across the markings.
The dome-height regression now requires more than 4.8 mm of actual relief.

## Quiet wireframe design view

The owner requested a wire design with independently solid, original-style
controls, then clarified that large surfaces should be quiet and the buttons
remain the focus. The viewer therefore provides one design net: subdued form
edges on dark surfaces, without coplanar triangle diagonals or a dense original
mesh selector. The background grid is deliberately faint. Moving control groups
are tagged explicitly so their full assemblies and legends can retain original
materials while the case uses the design style.

The renderer keeps the source geometry and materials intact. Its simplified
display geometry and contour lines are cached, follow the existing joints and
are removed/restored cleanly on mode exit or disposal. This view does not verify
physical gaps or change the reconstructed surfaces. The input and hinge rigs
continue to act on the same objects. Automated checks cover both styling choices,
flat-panel diagonal suppression, instanced grips and reversibility.

The owner then requested stronger device contours and removal of four stray
lines in frontal view. Thin perforated panels now retain their exact geometry:
clustering their cap vertices had created false open boundaries. Body contours
use independently written screen-space ribbon shaders with antialiasing and a
1.8 CSS-pixel width, keeping them readable while the large surfaces remain quiet.
There are no new runtime dependencies. Checks cover unmodified thin-panel
vertices, four-edge rectangular outlines and viewport/pixel-density updates.
