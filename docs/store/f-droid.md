# F-Droid submission notes

`com.arceusvault.keel.yml` is the metadata file for the fdroiddata repository. To submit: fork
https://gitlab.com/fdroid/fdroiddata, copy the file to `metadata/com.arceusvault.keel.yml`, and
open a merge request titled "New app: Keel". Their pipeline builds the app from the tagged commit.

The build is reproducible: a clean build of a tag in another directory matches the signed release
APK in every file except the signature. With `Binaries` and `AllowedAPKSigningKeys` set, F-Droid
compares its build to the APK on the GitHub release and, if they match, publishes the APK signed
with Keel's own key. That keeps F-Droid, GitHub and Play installs interchangeable. If their build
differs (for example from a different JDK), the pipeline says so and the difference needs fixing
before the MR can merge.

For each release, update `versionName`, `versionCode` and `commit` (the full hash of the tag) under
`Builds`, plus `CurrentVersion` and `CurrentVersionCode`. After inclusion, F-Droid's update checker
picks up new `v*` tags on its own.

Keel's code was written with an AI model, which the README states. F-Droid had no rule against
that as of October 2026, but reviewers may ask. IzzyOnDroid rejects AI-written apps, so Keel isn't
submitted there.
