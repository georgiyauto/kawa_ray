# Desktop 0.4.18: navigation and globe corrections

The owner's recording and screenshot show a selected navigation lens offset
from its button and a globe that rotates in the wrong horizontal direction.

Root causes:
- The lens assumed four equal cells, while each button used its own text-driven
  intrinsic layout width. Icons also mixed a filled gauge with outline glyphs.
- Globe rendering was capped at 25 Hz, performed on the UI thread, and rotated
  opposite the horizontal drag. Euler pitch was clamped. Input coordinates
  covered the containing rectangle rather than the visible square globe.
- An unsolicited idle spin restarted eight seconds after interaction.

Fix:
- Give the buttons and lens the same explicit width and spacing. Center each
  icon/label group, align their heights, and hide the lens on auxiliary pages.
- Use four original 24-unit SVGs with a consistent 1.7-unit rounded stroke.
- Map the visible sphere to a quaternion arcball, so the grabbed surface follows
  the pointer across the full sphere. Stop immediately on release. No idle spin.
- Rasterize on a dedicated worker. Allow only one frame in flight, including
  presentation, so old frames never queue up. A 16 ms scheduling interval targets
  60 Hz without blocking input. Wall-clock time controls the route particle.
- Precompute the static limb; compute the rotation matrix once per frame.
- Keep reduced-motion behavior and a short eased reset to the original view.

Validation: real owner-store screenshots at desktop, narrow and wide sizes;
quaternion direction/length/stationary tests; native release frame timing probe;
existing GUI and core regression tests; signed public update download check.
