# Desktop dot design

The user's four October 20 mobile screenshots supersede the previous liquid-glass reference for this change. Preserve the existing Rust/Slint application, server configuration, subscriptions, account flows, settings, routing, updater and tray.

## Presentation

- Black canvas, monochrome glass surfaces and white pill buttons.
- Original orthographic dot globe on the left in a wide window; stacked above the connection panel in a narrow window.
- Original Cyrillic/Latin dot-matrix font for the selected server and session timer.
- Real selected server, flag, protocol, connection state and latency from existing bindings.
- Connect/disconnect and change-server actions use the existing callbacks.
- Bottom navigation: Home, Servers, Statistics and Settings. Account, routing and reports stay available from the header.
- Server list keeps its filters, subscriptions, import, editing, refresh, favorite and ping controls.
- No imitation Discord/Gemini controls: those features are not implemented by this client.

## Traffic

Read only the current connection's TUN interface counters, once per second. Windows uses `GetIfEntry2`; Linux reads sysfs interface statistics. Download is received bytes, upload is sent bytes. Compute deltas using actual elapsed time. Discard the first sample and samples following interface changes or counter resets. Show a dash when unavailable rather than inventing zero activity. Keep 32 actual samples for the speed sparkline. Subscription totals remain separate from instantaneous speed.

## Validation

Use an isolated copy of the actual user store, with automatic probing and updates disabled during visual QA. `KAWA_DOT_QA` captures home, servers, settings, narrow and large windows without connecting/disconnecting the owner's running VPN. Unit tests cover elapsed-time rates, counter resets, interface changes and formatting. Build and screenshot validation are required before delivery.
