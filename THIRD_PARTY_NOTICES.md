# Third-party notices

The local MTProto-to-WebSocket proxy runtime in
`app/src/main/java/app/oneclick/tgproxy/proxy` is derived from
[crim50n/tg-ws-proxy-android](https://github.com/crim50n/tg-ws-proxy-android),
commit `b7ef40e`, and from its upstream projects
[ihtfw/tg-ws-proxy-android](https://github.com/ihtfw/tg-ws-proxy-android)
and [Flowseal/tg-ws-proxy](https://github.com/Flowseal/tg-ws-proxy).

All three codebases are used under the MIT License. Their copyright and
license texts are reproduced in `licenses/` and packaged in the APK assets.

Local changes include the single-button UI, foreground-service integration,
per-install secret persistence, no persistent traffic logs, strict system TLS
certificate and hostname verification (the imported trust-all TLS behavior is
not used), and a local-only listener on `127.0.0.1`.

The 1.9.0 WebSocket bridge lifecycle was aligned with Flowseal/tg-ws-proxy
commit `307a384ab7f522e34bb1a045884d0bd90f40bee5` (retrieved 2026-10-23).
The native Android service and UI remain local adaptations. HTTP/2 media,
Cloudflare Worker configuration, Fake TLS and PROXY protocol are not ported.
