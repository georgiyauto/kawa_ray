# Kawa VPN desktop 0.4.17

The user explicitly requested a richer desktop product: independent statistics,
liquid glass navigation, a globe they can rotate and a connection route, plus
better server management and settings. They delegated the visual direction.

Keep Rust/Slint and all VPN, subscription, routing and update behavior.
Use black #000000, charcoal #171717, soft border #ffffff18, white #f2f2f2,
muted #999999 and connection green #43c79e. Segoe UI for controls; Kawa Dots
for the selected server and home timer. The rotating globe is the visual focus.

- Home: orthographic 3D projection of an original sampled globe mesh. Mouse
  dragging changes camera yaw and pitch. Depth clipping hides the back surface.
  A reset control restores the initial view. A great-circle connection arc and
  moving particle link country markers when connected or connecting.
- Origin: one HTTPS Cloudflare trace request, before this client's connection,
  with a seven second timeout. Only the country code is retained in memory.
  It represents the network visible to the app; another VPN can be that network.
  No precise device coordinates are requested or invented. Unknown/aggregate
  countries have no map marker. Destination is the active profile's country.
- Navigation: a single animated glass lens moves underneath four accessible
  buttons. Hover, press and keyboard focus remain. Honor Windows reduced motion;
  stop automatic globe motion and particle animation when disabled. Rendering
  runs at most 25 fps and stops while other pages are visible.
- Statistics: independent session time, actual TUN byte deltas, download peak
  and 32-second activity trace, separated from provider subscription counters.
  Counters reset on disconnect, interface change or decreasing OS counters.
- Servers: compact filters, search, readable protocol details, subtle selected
  state and unchanged import, refresh, ping, favorites, connect and editing.
- Settings: five categories; all controls and actions retain their bindings.
  Narrow windows use a category selector instead of a side rail.

Validate projection, spherical interpolation and counter accounting with unit
tests. Screenshot all pages and settings categories using an isolated copy of
the actual owner's store. Verify 1000x640, 480x640 and 1376x900. Never stop the
owner's active VPN to take screenshots. Keep screenshot-only hooks out of normal
launch behavior. Review both static layouts and changed globe frames.
