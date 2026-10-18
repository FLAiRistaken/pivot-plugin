Closes: <!-- #N, or owner/repo#N for an issue in another repo. One "Closes" per issue. Release PRs: leave blank. -->
Phase: <!-- e.g. 3C, from pivot-docs status/roadmap.yaml. Copied from the issue as the phase: label if the PR has none. -->

## What and why
<!-- One or two sentences. Link the brief batch, roadmap phase or plan. -->

## Checks
<!-- Paste actual output, not just "passed". -->
- [ ] `mvn test` (paste summary):
- [ ] Tested on (server software + MC versions):
- [ ] Payload changes match `EVENT_SCHEMAS.md`: yes | n/a
- [ ] Acceptance checks from the plan (paste output):

## Release
- [ ] Target: `develop` (`main` only for releases)
- [ ] Migrations, config or env changes: none | listed here
- [ ] Release order or gates (other repos first, deploy gates): none | listed here

## Docs
- [ ] pivot-docs updated (`API_CONTRACT.md` version, `EVENT_SCHEMAS.md`, `MEMORY.md`): done | not needed
