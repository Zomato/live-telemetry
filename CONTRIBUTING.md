# Contributing

Thanks for helping improve Live Telemetry!

## Workflow

1. Fork the repo and branch off `dev`. `dev` is the default branch, and `master` only ever holds
   released code.
2. Make your change. Keep the library dependency-free beyond AndroidX and Compose, and keep the
   per-second / per-frame work cheap: this runs inside other people's apps.
3. Run `./gradlew build` (library + sample, lint, unit tests).
4. If you changed behaviour visible in the overlay, try it in the sample app
   (`./gradlew :sample:installDebug`) and attach before/after screenshots to the PR.
5. Open a pull request against `dev`.

## Public API

The library is built in Kotlin explicit-API mode: everything public is a deliberate, documented
part of the API and follows semantic versioning. Prefer `internal` for anything consumers do not
need. A change that removes or changes a public declaration needs a major version bump.

## Releases (maintainers)

1. Run the **Version Bump** workflow (patch / minor / major). It opens a `release/vX.Y.Z` PR
   against `master`.
2. Merging that PR runs **Publish Release**: it publishes to Maven Central, tags `vX.Y.Z`, creates
   the GitHub release and opens a `master → dev` sync PR.
3. Merge the sync PR.

## Code of conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating you agree
to uphold it.
