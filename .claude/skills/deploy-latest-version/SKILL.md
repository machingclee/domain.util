---
name: deploy-latest-version
description: >-
  Bump the Maven version in pom.xml (major, minor, or patch — user chooses),
  commit, tag, and push to origin so the tag triggers CI/CD publish.
  Use when the user asks to deploy, release, publish a new version, bump
  version, or run deploy-latest-version.
---

# Deploy latest version

Project skill for **this** repo (`domain-util`). Bumps `<version>` in root
`pom.xml`, commits, tags, and pushes so GitHub Actions publishes on the tag.

CI trigger (do not change): `.github/workflows/publish.yml` runs on
`push` of tags matching `v*`.

## When to use

- User runs `/deploy-latest-version` or says "deploy latest version"
- User asks to release / publish / bump version and push a deploy tag

## Preconditions

Before changing anything:

1. Working tree should be on `main` (or confirm with the user if not).
2. Read the current version from root `pom.xml`:
   ```bash
   # Prefer xmllint/python; fallback to grep
   grep -m1 '<version>' pom.xml
   ```
   Project coordinates version is the **first** top-level `<version>` under
   `<project>` (not the parent Spring Boot version). Example: `0.1.2-SNAPSHOT`.
3. Show status so the user knows what will be committed:
   ```bash
   git status
   git diff --stat
   ```
4. If there are unrelated uncommitted changes, warn the user. Default is still
   `git add .` (everything), unless they ask to stage only `pom.xml`.

## Step 1 — Ask which part to increment

**Always ask** (unless the user already said major / minor / patch in the same
message). Use a short question:

> Current version in `pom.xml` is **`X.Y.Z[-SNAPSHOT]`**.  
> Increment which part? **major** / **minor** / **patch**?

Do not invent a default bump. Wait for the answer.

## Step 2 — Compute the next version

Parse current version as:

```text
MAJOR.MINOR.PATCH[-QUALIFIER]
```

Examples: `0.1.2-SNAPSHOT`, `0.1.0`, `1.0.0-SNAPSHOT`.

| Choice | Rule | Example (`0.1.2-SNAPSHOT`) |
|--------|------|----------------------------|
| **major** | `MAJOR+1`, reset minor & patch to `0` | `1.0.0-SNAPSHOT` |
| **minor** | `MINOR+1`, reset patch to `0` | `0.2.0-SNAPSHOT` |
| **patch** | `PATCH+1` | `0.1.3-SNAPSHOT` |

- **Preserve** any qualifier (e.g. `-SNAPSHOT`) on the new version.
- Tag name is always `v` + new version (e.g. `v0.1.3-SNAPSHOT`).
- Commit message is exactly: `deploy <new-version> version`  
  Example: `deploy 0.1.3-SNAPSHOT version`

Confirm with the user before writing files if anything looks ambiguous
(e.g. non-semver version string). Otherwise proceed after they chose major/minor/patch.

## Step 3 — Update pom.xml

Edit only the **project** version element (not `<parent><version>`).

Replace:

```xml
    <version>OLD</version>
```

with:

```xml
    <version>NEW</version>
```

Keep surrounding indentation and do not reformat the whole POM.

## Step 4 — Git commit, tag, push

Run in order. Use the real `NEW` version everywhere below.

```bash
git add .
git commit -m "deploy NEW version"
git tag "vNEW"
git push origin main
git push origin "vNEW"
```

Notes:

- Commit message must match the pattern: `deploy <version> version` (no `v` prefix in the message unless the version itself includes it — it should not).
- Tag **must** start with `v` so CI matches `v*`.
- Push **branch first**, then **tag**. The tag push is what starts CI/CD publish.
- If `main` is not the current branch, push the current branch only after confirming with the user; prefer being on `main`.
- Do **not** force-push. Do **not** delete or move existing tags unless the user explicitly asks.
- If the tag already exists locally or on remote, stop and ask the user (do not overwrite).

## Step 5 — Report

After successful pushes, tell the user:

1. Old version → new version
2. Commit hash / message
3. Tag name
4. That `git push origin vNEW` should have triggered **Publish to GitHub Packages**
5. Optional: link shape  
   `https://github.com/machingclee/domain.util/actions`

## Failure handling

| Problem | Action |
|---------|--------|
| Dirty tree / merge conflicts | Stop; show `git status`; do not commit half-done |
| Not on `main` | Ask before continuing |
| Tag `vNEW` already exists | Stop; ask for a different bump or delete strategy |
| Push rejected (non-fast-forward) | Stop; do not force; report remote status |
| `pom.xml` version not found / multiple candidates | Stop; show the ambiguous lines |

## Example (full)

Current: `0.1.2-SNAPSHOT`, user chooses **patch**.

1. New version: `0.1.3-SNAPSHOT`
2. Tag: `v0.1.3-SNAPSHOT`
3. Commands:

```bash
# after editing pom.xml <version>0.1.3-SNAPSHOT</version>
git add .
git commit -m "deploy 0.1.3-SNAPSHOT version"
git tag "v0.1.3-SNAPSHOT"
git push origin main
git push origin "v0.1.3-SNAPSHOT"
```

## Out of scope

- Changing Java / Spring Boot parent versions
- Maven `release:prepare` / `versions:set` plugins (manual POM edit is fine)
- Creating GitHub Releases UI assets
- Auto-choosing major vs minor vs patch without asking
