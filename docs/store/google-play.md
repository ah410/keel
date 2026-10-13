# Google Play submission notes

The answers below match what Keel actually does, so the Play Console forms can be filled in from
this page. Listing text and graphics live in `fastlane/metadata/android/en-US/`.

## Account

A personal developer account costs a one-time $25 and needs identity verification. New personal
accounts must run a closed test with at least 12 testers who stay opted in for 14 days in a row
before production access can be requested. Registering also covers Android developer
verification, which Google is rolling out for apps installed outside Play (four countries since
September 30, 2026, everywhere from 2027), so GitHub and F-Droid installs keep working too.

## App signing

Choose "Use existing app signing key" so Play builds share the signature of the GitHub and
F-Droid builds (certificate SHA-256 `47:BA:2B:A3:...:60:3E:DA`). The console shows a command
using Google's `pepk.jar` and an encryption key; run it against
`~/.android/keys/truenas-companion-release.jks` with the alias from `keystore.properties`, and
upload the encrypted output. The same keystore can serve as the upload key.

Upload `app/build/outputs/bundle/release/app-release.aab` from `./gradlew bundleRelease`.

## Store listing

| Field | Value |
|---|---|
| App name | Keel |
| Short description | `fastlane/.../short_description.txt` |
| Full description | `fastlane/.../full_description.txt` (HTML is allowed) |
| App icon | `fastlane/.../images/icon.png` (512×512) |
| Feature graphic | `fastlane/.../images/featureGraphic.png` (1024×500) |
| Phone screenshots | `fastlane/.../images/phoneScreenshots/1.png` to `4.png` |
| Category | Tools |
| Contact email | your support address |
| Privacy policy | https://github.com/ah410/keel/blob/main/PRIVACY.md |

## App content

App access: "All or some functionality is restricted." Instructions for reviewers:

> Keel manages a TrueNAS server the user owns, reached over their LAN or VPN, so no shared login
> exists. Tap "Try a demo server" on the first screen to use every feature with built-in sample
> data. The demo makes no network connections.

Ads: no ads.

Content rating (IARC questionnaire): category "Utility, productivity, communication or other";
answer no to violence, sexual content, language, controlled substances, gambling, user
interaction and sharing location. Expected rating: Everyone / PEGI 3.

Target audience: 18 and over. Not designed for children.

Data safety: the app collects no data and shares no data. Everything it sends goes to the server
the user configured, under the user's control, which Google doesn't count as collection by the
developer. Encryption in transit: yes (TLS is mandatory). Data deletion: users can delete
everything by choosing "Forget this server" or uninstalling.

Government app, financial features, health, news, COVID-19: not applicable.

Permissions declarations: none of Keel's permissions need a declaration form. Notifications and
boot completed are only used by the optional alert check.
