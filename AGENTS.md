# AGENTS.md

Nextflow plugin exposing TypeSafe System One (Jev) judgments as the functions `noul`, `choice`,
`score` and `jev`. See `README.md` for usage and `SPEC.md` for the design.

## Layout

- `src/main/groovy/nextflowio/plugin/`: plugin sources (`JevExtension` holds the functions,
  `JevClient` the HTTP call, `JevConfig` and `JevProvider` the `jev` config scope)
- `src/test/groovy/`: Spock tests; none make a live API call
- `examples/`: pipelines that CI runs against the live API

## Commands

```bash
make assemble   # build
make test       # unit tests
make install    # install into the local Nextflow plugins dir
make release    # publish to the Nextflow Registry (token from .env)
```

## Release

1. Bump `version` in `build.gradle` and the `nf-jev@x.y.z` reference in `README.md`.
2. Prepend the new version to `changelog.txt`, with one line per commit since the last release
   and no docs-only commits.
3. Commit as `[release] version x.y.z` and push to master. The `release` job in
   `.github/workflows/build.yml` then publishes to the registry and creates the `vx.y.z` tag and
   GitHub release, with that version's changelog block as the notes.
