# salp Linux Mobile PC v0.1.0

Android / XREAL Beam Pro向けのPC風デスクトップ環境プロトタイプ。

## v0.1.0 features
- PC-style desktop
- Start menu and taskbar
- Browser window
- Files mock window
- Offline text editor
- Terminal-style shell mock
- salp Tools external launcher
- Touch / mouse / keyboard friendly layout

## Build
Android Studio 2024+ または GitHub Actions でビルド可能。

```bash
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

## Next target v0.2.0
- Android file open/save using Storage Access Framework
- Better browser behavior
- Draggable/resizable windows
- Android app launcher bridge
- Fullscreen / external display improvements
