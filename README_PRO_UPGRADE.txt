JA1 Church Presenter Pro Upgrade — Source Project

UPGRADE YOUR EXISTING GITHUB REPOSITORY
1. Back up the working project and Export Library JSON from the installed app.
2. Replace app/src/main/assets/index.html and app/src/main/java/org/ja1/presenter/MainActivity.java.
3. Replace app/src/main/AndroidManifest.xml (adds INTERNET permission for remote control).
4. Keep your existing .github/workflows/build-apk.yml and build again with GitHub Actions.
5. Install the new debug APK as an update. Keep the same package name and signing key to preserve app data. If installation requires uninstalling, export the library first.

FEATURES
- Native projector-only lyrics output remains, with MP4 looping video and image background.
- Native fade transitions, Clear Lyrics and Blackout, next-slide preview.
- Playlist-based Next/Previous order, move songs up/down and persistent playlists.
- Save and reuse image background presets (limited by Android WebView local storage quota).
- Bible verse entry and save-as-song. No copyrighted Bible text database included.
- Announcement slides and countdown.
- Wi-Fi remote: on the tablet tap Start Wi-Fi Remote. Open displayed http://TABLET-IP:8765/ on a phone on the same trusted Wi-Fi, enter the 6-digit PIN. Commands: Next, Previous, Clear, Blackout, Live, Next Song, Previous Song. Stop the server after service. Port 8765 must be reachable; guest Wi-Fi client isolation can block it.

KNOWN LIMITATIONS
- Android APK compilation and behavior on Huawei MatePad 12 X have not been verified in this environment.
- Image background presets stored in localStorage may fill storage; prefer smaller images.
- Video on projector is saved as one last-selected file; this is not a multi-video preset library.
- Wireless remote uses unencrypted HTTP on local Wi-Fi with a PIN; use only trusted Wi-Fi. It is not an internet remote.
- Bible verses must be entered manually; no bundled Bible translation.
- Native fade animation may need tuning for rapid consecutive taps.
