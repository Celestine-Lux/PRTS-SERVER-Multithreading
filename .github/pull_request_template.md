## What this changes

<!-- One or two sentences: what is different after this change. -->

## Evidence

- [ ] The full test run on this branch: suites / cases / failures / errors / skipped.
- [ ] The cases that were red **before** the change, and the same cases green after it.
- [ ] The gates this repository asks for (comment lint, literal lint, package graph with its negative fixture, per-file comment density, the probe checks).
- [ ] Any reading that was **not** taken is named as not taken rather than left out.

## Boundaries

- [ ] Defaults, distribution switches, refusal names and entry-point counts are stated, whether or not they moved.
- [ ] Nothing that fails was loosened, retried away or skipped.
- [ ] No history was rewritten and no force push was used.

## Continuous integration

- [ ] This branch is based on the **current tip** of `base/new-kernel`.
- [ ] This head carries its **own** readable run: the build job, every step, the log and the conclusion.

<!-- A run that has not executed is not evidence; a run against an older tip does not stand in for a run against the reviewed tree. -->

## Authorship

<!-- Authorship stays with the contributor; the review record of this pull request is kept with it. -->
