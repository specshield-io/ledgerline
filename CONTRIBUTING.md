# Contributing to Ledgerline

Thanks for helping. Ledgerline is a SpecShield Labs internal demonstration: **synthetic data only**, never real
cardholder or personal data, including in tests, fixtures, logs, screenshots and issues.

## Before you start

Read [AGENTS.md](AGENTS.md). It holds the architecture rules, the testing rules and the Definition of Done, and it
applies equally to people and AI agents. For a significant design change, write an ADR in `docs/adr/`
([ADR 0001](docs/adr/0001-record-architecture-decisions.md) explains how).

## Workflow

1. Branch from `main`. Keep a pull request to one change.
2. Every behaviour change ships with tests. Use a catalogue ID (`LL-XXX`) in the test name when one exists for the
   behaviour; do not invent IDs.
3. Run the checks CI runs:

   ```bash
   ./mvnw spotless:apply && ./mvnw verify          # format, unit + integration tests, coverage gate
   cd ops-console && npm run lint && npm run typecheck && npm test && npm run build   # console changes
   ```

4. If the API changed, commit the regenerated `docs/openapi.json` and `ops-console/src/api/schema.d.ts`. A
   backward-incompatible change needs a new API version.
5. Update the README or [docs/reference.md](docs/reference.md) where behaviour changed, and add a line under
   `[Unreleased]` in [CHANGELOG.md](CHANGELOG.md).
6. Open the pull request. Explain *why*, and say why for any new dependency. CI must be green, and a maintainer
   listed in [CODEOWNERS](.github/CODEOWNERS) reviews it.

## Honesty rule

Any number you write (throughput, latency, coverage, mutation score) must come from a run anyone can reproduce from
this repository, and you must say how it was measured. If it was not measured, do not write it.

## Releases

Maintainers release by moving `[Unreleased]` in the changelog to a new version section, merging, then pushing a tag:

```bash
git tag -a v1.1.0 -m "Ledgerline 1.1.0" && git push origin v1.1.0
```

The [release workflow](.github/workflows/release-sbom.yml) creates the GitHub release from that changelog section and
attaches the CycloneDX SBOM.

## Security

Do not open a public issue for a vulnerability; follow [SECURITY.md](SECURITY.md).
