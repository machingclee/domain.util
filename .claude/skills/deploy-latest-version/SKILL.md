---
name: deploy-latest-version
description: >-
  Bump the Maven version in pom.xml (major, minor, or patch — user chooses),
  commit, tag, push, then locally `mvn -B clean deploy -DskipTests
  -Dgpg.passphrase=...` to Maven Central (do NOT pass
  -Dcentral.autoPublish=true). Use when the user asks to deploy, release,
  publish a new version, bump version, or run deploy-latest-version.
---

# Deploy latest version

Project skill for **this** repo (`domain-util`). Bumps `<version>` in root
`pom.xml`, commits, tags, pushes the `v*` tag, then deploys **locally** to
the Sonatype Central Publisher Portal.

This project no longer publishes to GitHub Packages. Destination is Maven
Central (`com.machingclee:domain-util`).

CI (`.github/workflows/publish.yml`) may still run on `v*` tags, but the
**canonical deploy in this skill is the local `mvn deploy`**. Do not treat a
green (or failed) Actions run as a substitute for the local deploy.

## When to use

- User runs `/deploy-latest-version` or says "deploy latest version"
- User asks to release / publish / bump version and push a deploy tag

## Preconditions

Before changing anything:

1. Working tree should be on `main` (or confirm with the user if not).
2. Read the current **domain-util** project version from root `pom.xml`:
   ```bash
   # Extract <version> that follows <artifactId>domain-util</artifactId>
   awk '/<artifactId>domain-util<\/artifactId>/{getline; gsub(/<\/?version>/,""); gsub(/^[[:space:]]+|[[:space:]]+$/,""); print; exit}' pom.xml
   ```
   This gets only the domain-util project version, never the
   `<parent><version>` (Spring Boot). Example output: `0.1.2`.
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

> Current version in `pom.xml` is **`X.Y.Z`**.  
> Increment which part? **major** / **minor** / **patch**?

Do not invent a default bump. Wait for the answer.

## Step 2 — Compute the next version

Parse current version as:

```text
MAJOR.MINOR.PATCH[-QUALIFIER]
```

Examples: `0.1.2`, `0.1.0`, `1.0.0`.

| Choice | Rule | Example (`0.1.2`) |
|--------|------|----------------------------|
| **major** | `MAJOR+1`, reset minor & patch to `0` | `1.0.0` |
| **minor** | `MINOR+1`, reset patch to `0` | `0.2.0` |
| **patch** | `PATCH+1` | `0.1.3` |

- **Preserve** any qualifier (e.g. ``) on the new version.
- Tag name is always `v` + new version (e.g. `v0.1.3`).
- Commit message is exactly: `deploy <new-version> version`  
  Example: `deploy 0.1.3 version`

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

## Step 4 — Update README.md consumer dependency version

Also update the Maven coordinates example under the **domain-util** section at
the top of `README.md` (the dependency snippet consumers copy).

In the block:

```xml
<dependency>
    <groupId>com.machingclee</groupId>
    <artifactId>domain-util</artifactId>
    <version>OLD</version>
</dependency>
```

Replace the version with `NEW` so it matches `pom.xml`:

```xml
    <version>NEW</version>
```

- Target **only** that domain-util dependency snippet (groupId
  `com.machingclee` / artifactId `domain-util`), not unrelated versions
  elsewhere in the README.
- Keep surrounding indentation; do not reformat the whole file.
- If the README version is already equal to `NEW`, leave it as-is.
- If the README version cannot be found or is ambiguous, stop and show the
  relevant lines before continuing.

## Step 5 — Git commit, tag, push

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
- Push **branch first**, then **tag**. The tag is the release marker; the
  actual Maven Central upload is the local `mvn deploy` in the next step.
- If `main` is not the current branch, push the current branch only after confirming with the user; prefer being on `main`.
- Do **not** force-push. Do **not** delete or move existing tags unless the user explicitly asks.
- If the tag already exists locally or on remote, stop and ask the user (do not overwrite).
- Staged changes should include both `pom.xml` and `README.md` when the README
  version was updated.

## Step 6 — Local Maven Central deploy

After the tag is pushed, publish from this machine. `pom.xml` defaults
`<central.autoPublish>` to `false` — **leave it that way**.

### Command

```bash
export GPG_TTY=$(tty)
mvn -B clean deploy -DskipTests -Dgpg.passphrase='OUR_GPG_PASSPHRASE'
```

Rules:

- **Do not** pass `-Dcentral.autoPublish=true` (or any other override that
  flips `central.autoPublish`). The POM default (`false`) must stand so the
  bundle is uploaded as a Portal draft. The user reviews and clicks
  **Publish** at https://central.sonatype.com
- **Do not** write the GPG passphrase into the repo, the skill, `pom.xml`,
  or `settings.xml`. Pass it only as `-Dgpg.passphrase=...` (or ask the user
  to run the command if they do not want it in the agent transcript).
- If the passphrase is not already known in this conversation, **ask**
  before deploying. Do not invent one.
- `~/.m2/settings.xml` must have server id `central` (Portal user token).
  GPG key id is `gpg.keyname` in `pom.xml` (`0F69925B825FA48F`).
- Wait for `BUILD SUCCESS`. A successful run stages/uploads the bundle but
  does **not** make the version live on repo1 until the user publishes in
  the Portal.

If `mvn deploy` fails (GPG pinentry, missing token, validation), stop and
show the error. Do not retry with `-Dcentral.autoPublish=true`.

## Step 7 — Report

After successful git pushes **and** `mvn deploy`:

1. Old version → new version
2. That `pom.xml` and the README domain-util `<version>` were updated
3. Commit hash / message
4. Tag name
5. That local `mvn -B clean deploy -DskipTests -Dgpg.passphrase=…` ran
   **without** `-Dcentral.autoPublish=true`
6. That the user should open the [Central Publisher Portal](https://central.sonatype.com)
   and **Publish** the draft deployment (it will not appear on Maven Central
   until they do)
7. Optional: Actions URL (informational only — not the publish path)  
   `https://github.com/machingclee/domain.util/actions`

## Failure handling

| Problem | Action |
|---------|--------|
| Dirty tree / merge conflicts | Stop; show `git status`; do not commit half-done |
| Not on `main` | Ask before continuing |
| Tag `vNEW` already exists | Stop; ask for a different bump or delete strategy |
| Push rejected (non-fast-forward) | Stop; do not force; report remote status |
| `pom.xml` version not found / multiple candidates | Stop; show the ambiguous lines |
| README domain-util version not found / ambiguous | Stop; show the relevant README lines |
| `mvn deploy` / GPG / Central upload fails | Stop; show the Maven error; do **not** retry with `-Dcentral.autoPublish=true` |

## Example (full)

Current: `0.1.2`, user chooses **patch**.

1. New version: `0.1.3`
2. Tag: `v0.1.3`
3. Commands:

```bash
# after editing:
# - pom.xml project <version>0.1.3</version>
# - README.md domain-util dependency <version>0.1.3</version>
git add .
git commit -m "deploy 0.1.3 version"
git tag "v0.1.3"
git push origin main
git push origin "v0.1.3"
export GPG_TTY=$(tty)
mvn -B clean deploy -DskipTests -Dgpg.passphrase='OUR_GPG_PASSPHRASE'
```

Then tell the user to Publish the draft in the Central Portal.

## Out of scope

- Changing Java / Spring Boot parent versions
- Maven `release:prepare` / `versions:set` plugins (manual POM edit is fine)
- Creating GitHub Releases UI assets
- Auto-choosing major vs minor vs patch without asking
- Passing `-Dcentral.autoPublish=true`
- Publishing to GitHub Packages
