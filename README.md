# Homedesign — Planora for Android

Planora is a 2D + 3D house planner (multi-floor, basement, tunnels, balconies).
This repo wraps it in a native Android WebView app that works fully offline.

- `web/index.html` — the original web app (uses CDN for Three.js)
- `app/src/main/assets/` — offline copy (`index.html` + bundled `three.min.js`)
- GitHub Actions builds the APK on every push: download it from **Releases → Planora (latest APK)** or the workflow's artifacts, then install on your phone.

Local build: `gradle :app:assembleDebug` (needs Android SDK).
