# Google/Gmail Sign-In Setup (Optional)

Mangal Browser allows guest browsing. Signing in is optional and does not block the home screen or search.

## 1. Create Firebase project
1. Open https://console.firebase.google.com/ and create a project.
2. In **Authentication → Sign-in method**, enable **Google**.
3. In **Authentication → Settings → Authorized domains**, add the real web domain you host the app on.
4. In Project settings, register a **Web app** and copy its web config values into `web/firebase-config.js`.
5. Never add a service-account key or OAuth client secret to the client app or GitHub repository.

The Firebase web config is intended to be visible to clients. Apply Firebase security rules to any database you add; never trust client-side UI for authorization.

## 2. Web test
Serve the `web/` directory from localhost or an HTTPS origin. Do not open `index.html` as a `file://` URL for Firebase authentication. Firebase popup auth is designed for supported web origins and may not work in embedded WebViews or Electron's embedded Chromium.

## 3. Android
The included Android shell currently loads the shared UI in Android System WebView. The shared page uses Firebase's compat scripts to avoid JavaScript-module loading issues from local assets, but **do not assume the web popup sign-in is production-ready in a WebView.** For production Android, use Firebase Authentication with Google's native sign-in flow (Credential Manager / Google ID) and pass the resulting ID token to Firebase Auth. Register the Android app in Firebase, use the exact package name `com.mangal.browser`, add the generated `google-services.json` to `android/app/`, configure SHA-1/SHA-256 fingerprints, and implement the native Credential Manager bridge before release. Do not embed OAuth client secrets in the APK.

## 4. Desktop
For production Electron builds, Google generally blocks OAuth inside embedded user agents. Use the system browser for OAuth and a registered loopback/custom-protocol callback, then validate state/PKCE and exchange credentials through the supported Firebase/OAuth flow. The current shared UI uses Firebase `signInWithPopup` as a web-origin starter and displays setup guidance if unconfigured; it is **not a finished native Electron OAuth flow**.

## 5. Privacy and account data
- Guests can browse without creating an account.
- Ask for only the profile/email scopes needed.
- Provide a privacy policy and account deletion path before public release.
- Do not sync browsing history by default without clear opt-in and disclosure.
- Use Firebase Auth state for identity. Store only necessary profile metadata; never store Gmail passwords.
- Add rate limiting/abuse protection and review Firebase security rules before launch.

## Current starter limitations
This package adds the optional Google sign-in UI and Firebase Web SDK wiring. Real authentication requires completing the Firebase project setup and platform-specific Android/Electron OAuth implementation described above. No account sign-in has been tested with a real Firebase project because no project credentials were supplied.
