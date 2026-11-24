# Desktop metrics and flags — 0.4.23

User request: match the flag to the real connected exit, replace the unexplained K badge, add a main-screen ping for the active tunnel, and transition only changed numeric cells without shaking.

Implementation:

- Resolve connected profile metadata by its stable connection key, independent of the currently selected subscription and the cleaned display name.
- Use the verified exit-country flag on the dashboard when available. Keep profile flags in the server list. Profiles without a country use a neutral network icon rather than an invented location.
- Main-screen ping makes one bounded HTTPS request through the existing authenticated SOCKS connection. It does not start Xray, modify routes, reconnect the VPN, or run a batch of server probes. Ignore replies from an obsolete connection generation.
- Persistent fixed-width character cells crossfade only their changed values. There is no vertical translation, whole-clock fade, or animated cell width. Honor the existing reduced-motion setting.
- Retry failed exit-country lookups with a bounded schedule and reject results from obsolete connection epochs.

Verification pending:

1. Rebuild the final source and run the native suite.
2. Screenshot real stored server rows, home, and narrow/wide layouts. Check the AI profile's neutral icon.
3. Use the isolated motion fixture to compare unchanged hour/minute pixels during second changes; verify changed seconds transition and reduced motion.
4. Verify the active-tunnel request and stale reply handling without disconnecting the owner's VPN.
5. Package and verify the installer before publishing the previously authorized public update.

Blocker at 22:12 Moscow time: Germany host 203.0.113.18 stopped answering SSH and VPN ports again, also from the independent Netherlands host. The final refinements are local and have not been rebuilt. Do not publish the previous intermediate installer or describe these changes as released.
