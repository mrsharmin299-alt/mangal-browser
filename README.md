# Mangal Browser

A starter project for the Mangal Browser concept: a black/red Mars-themed home screen shared by Android and desktop.

## Included
- Android app using Kotlin + Android WebView
- Shared responsive HTML/CSS/JS home screen
- Desktop shell using Electron
- GitHub Actions workflows for Android APK and desktop packages

## Important scope notes
This is a functional starter, not a finished production browser engine. Android uses Android System WebView. Electron uses Chromium for desktop. Chrome Web Store compatibility varies by extension and Electron version; this starter does not claim full Chrome Web Store compatibility. The extension store screen is a UI placeholder and does not install arbitrary extensions yet.
Only download content you own or are authorized to download. This project does not bypass DRM or site restrictions.

## Android build
Open the `android/` folder in Android Studio, or push the repository to GitHub and run the **Android APK** workflow. The APK will be uploaded as a workflow artifact.

## Desktop build
From `desktop/`:
```bash
npm install
npm start
```
Package locally:
```bash
npm run dist
```
GitHub Actions has a separate desktop packaging workflow.

## Repository layout
- `android/` Android app
- `web/` shared home screen assets
- `desktop/` Electron desktop shell
- `.github/workflows/` build workflows

## Optional Google/Gmail account
The home screen includes an optional Google sign-in UI. Guest browsing remains available. See `AUTH_SETUP.md` for Firebase setup and platform-specific production OAuth requirements. Real authentication requires your own Firebase project configuration.
