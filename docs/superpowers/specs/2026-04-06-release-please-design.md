# Phase 1.2: Conventional Commits + release-please — Design Spec

## Goal

Automate version bumps, changelog generation, and release tagging using Google's release-please GitHub Action. Eliminates manual version editing and release note writing.

## How It Works

1. Developers push commits using conventional format (`feat:`, `fix:`, `docs:`, etc.)
2. release-please GitHub Action runs on every push to `phase1-modernization`
3. It accumulates unreleased commits and creates/updates a "Release PR" with auto-generated CHANGELOG.md
4. When the Release PR is merged, release-please:
   - Bumps `versionName` and `versionCode` in `app/build.gradle.kts`
   - Commits the version bump + CHANGELOG.md
   - Creates a git tag (`v3.0.14`, `v3.1.0`, etc.)
   - The tag triggers `release.yml` → build, sign, deploy to digiscope.me

## Commit Convention

| Prefix | Meaning | Version Bump |
|--------|---------|-------------|
| `feat:` | New feature | Minor (3.0.x → 3.1.0) |
| `fix:` | Bug fix | Patch (3.0.13 → 3.0.14) |
| `feat!:` or `BREAKING CHANGE:` | Breaking change | Major (3.x → 4.0.0) |
| `security:` | Security fix | Patch |
| `test:` | Test changes | None (included in changelog) |
| `docs:` | Documentation | None (included in changelog) |
| `chore:` | Maintenance | None (included in changelog) |
| `refactor:` | Code refactor | None (included in changelog) |

Scopes are optional but encouraged: `feat(sync):`, `fix(keystore):`, `test(security):`.

## Files

### `.github/workflows/release-please.yml`

Runs on push to `phase1-modernization`. Creates/updates the Release PR. On merge, tags and updates version files.

### `release-please-config.json`

Configuration:
- Package name: `digibyte-wallet`
- Release type: `simple` (not a specific language ecosystem — we handle build.gradle.kts via extra-files)
- Bump strategy: patch for `fix:`, minor for `feat:`, major for `feat!:`
- Extra files to update: `app/build.gradle.kts` (versionName and versionCode)
- Changelog sections: Features, Bug Fixes, Security, Tests, Documentation

### `.release-please-manifest.json`

Tracks current version. Single entry: `".": "3.0.13"`. Updated by release-please on each release.

### `CHANGELOG.md`

Auto-generated. Sections per release with commit messages grouped by type. Example:

```markdown
## [3.0.14](https://github.com/JohnnyLawDGB/digibytewallet-android/compare/v3.0.13...v3.0.14) (2026-04-06)

### Bug Fixes

* sync starts immediately after wallet creation + PIN setup ([5d4169c](https://github.com/...))
* pin_setup crash when startDestination — onboarding not on back stack ([333570a](https://github.com/...))
```

## Version Code Strategy

`versionCode` must be strictly incrementing for Play Store. release-please updates `versionName` but we need a formula for `versionCode`:

Formula: `MAJOR * 10000 + MINOR * 100 + PATCH`
- 3.0.13 → 30013
- 3.0.14 → 30014
- 3.1.0 → 30100
- 4.0.0 → 40000

This is handled by a custom updater in the release-please config that parses versionName and computes versionCode.

## Integration with release.yml

The existing `release.yml` triggers on `tags: ['v*']`. release-please creates tags in this format (`v3.0.14`). No changes needed to `release.yml` — it picks up the tag automatically.

## What's NOT in Scope

- Pre-commit hooks enforcing conventional commits (can add later with commitlint)
- Branch protection rules
- Automated PR labeling
