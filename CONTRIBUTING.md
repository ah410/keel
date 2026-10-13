# Contributing

Thanks for helping. A few things keep the project easy to maintain.

Open an issue before starting anything large, so we can agree on the approach first. Bug fixes
and small improvements can go straight to a pull request.

Commits use single-line [Conventional Commits](https://www.conventionalcommits.org/), for example
`fix(apps): keep the log view scrolled to the bottom`. CI checks this on every pull request.

Before opening a pull request, make sure these pass:

```sh
./gradlew testDebugUnitTest lintDebug assembleRelease
```

If you add or update a dependency, regenerate `gradle/verification-metadata.xml` as described in
the README and explain why the dependency is needed. New dependencies must be under a license
compatible with Apache 2.0 and must not phone home.

Any feature that changes something on the server needs a role check in `data/Actions.kt` and the
right confirmation tier. When in doubt, pick the stricter tier.

By contributing you agree that your contribution is licensed under the Apache License 2.0.
