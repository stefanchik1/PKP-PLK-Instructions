# PKP PLK Instructions — Android 1.2.0

Independent Android catalogue app for PKP PLK instruction categories.

## Features
- 13 official subject categories.
- Search by symbol, title, or subject.
- Dark/light theme toggle.
- Locally saved favourites.
- Manual live refresh of metadata from all 13 official PLK category pages.
- Locally cached last-refresh metadata and timestamp.
- Official publisher links open the original PLK pages/documents.

## Important legal and operational notes
PKP PLK states on its official instruction catalogue that copyright belongs to PKP PLK and that copying or distributing the instructions requires prior permission. This app therefore does not bundle, mirror, or redistribute the PDF files. It stores catalogue metadata/favourites locally and links to the publisher's original pages. Offline access is limited to the app interface and cached catalogue metadata; PDF offline storage is intentionally not implemented without appropriate authorization.

The refresh is user-initiated and checks the official category pages when the user taps the refresh button. It is not a background daily monitor. The app is independent and is not an official PKP PLK product. Always verify the currently applicable document and effective date on the publisher's page before relying on it.

## Build
Open this folder in Android Studio (Giraffe or newer) with Android SDK Platform 35 installed. Let Gradle sync, then choose **Build > Build Bundle(s) / APK(s) > Build APK(s)**. The resulting debug APK is normally at `app/build/outputs/apk/debug/app-debug.apk`.

Gradle wrapper is not included. Android Studio can use its configured Gradle installation.

## Build APK automatically with GitHub Actions

1. Upload this project folder to a GitHub repository (include the `.github` folder).
2. Open the repository's **Actions** tab and select **Build Android APK**.
3. Choose **Run workflow**. After the run succeeds, open the run and download the artifact named `PKP-PLK-Instructions-debug-apk`.
4. Extract `app-debug.apk` and install it on the Android device. Android may ask you to allow installation from the browser/file manager used to open it.

The workflow compiles a debug APK using JDK 17, Gradle 8.9 and Android SDK 35. This repository does not contain a signed release key; the generated APK is a debug build.
