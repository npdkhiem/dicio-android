# Change tracking

This fork starts from Dicio 4.2 (`fe675ede`, tag `v4.2` upstream) and records local changes on top of that.

## 2026-11-04 — Remove notification reading

Play Protect hard-blocked the official 4.2 APK when it was installed from a file manager. The message was: "This app can request access to sensitive data. This can increase the risk of identity theft or financial fraud." There was no "Install anyway" button.

Google applies that block to sideloaded apps that declare a notification listener, SMS access, or an accessibility service. Dicio declared `BIND_NOTIFICATION_LISTENER_SERVICE` for the "What are my notifications?" skill.

Removed:

- The notification-listener permission and the `NotifyHandler` service from `AndroidManifest.xml`.
- The notify skill (`NotifyInfo`, `NotifySkill`, `NotifyOutput`, `NotifyHandler`) and its registration in `SkillHandler`.
- The unused `MediaNotificationListener` class. Media controls already use media key events and did not depend on it.
- The `PERMISSION_NOTIFICATION_LISTENER` helper in `PermissionUtils.kt`.

Still present:

- Wake word listening and speech to text.
- The app's own "microphone is on" notification (`POST_NOTIFICATIONS`). That is not the notification reader.
- Contacts, phone calls, and the installed-app list, used by the calling and open-app skills.

The built APK was checked with `aapt dump permissions`. `BIND_NOTIFICATION_LISTENER_SERVICE` is not in the package.

## Build published with this change

| Item | Value |
|---|---|
| APK | `Dicio-no-notification-reader.apk` on the GitHub release |
| Package | `org.stypox.dicio.main` |
| App name on the phone | Dicio-main |
| Version | 4.2-main (debug build of upstream versionName 4.2) |
| Build type | Debug, signed with the debug key |

`Dicio_4.2.apk` in the repository root is the unmodified official build. It still contains the notification reader. Install the release APK instead.

## Other local edit

`settings.gradle.kts` now resolves `gradle/libs.versions.toml` from `settingsDir`. The previous relative path failed when Gradle's working directory was not the project root.
