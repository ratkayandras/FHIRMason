# Releasing FHIRMason

## Prerequisites

- GPG key configured and uploaded to a public keyserver (e.g., `keys.openpgp.org`)
- GitHub repository secrets set:
  - `MAVEN_CENTRAL_USERNAME` — Sonatype Central Portal username
  - `MAVEN_CENTRAL_PASSWORD` — Sonatype Central Portal password
  - `GPG_PRIVATE_KEY` — armored GPG private key (`gpg --armor --export-secret-keys <key-id>`)
  - `GPG_PASSPHRASE` — passphrase for the GPG key

## Release Steps

1. **Ensure the branch is clean and all tests pass.**
   ```bash
   mvn test
   ```
   All modules must be green with zero failures.

2. **Update `CHANGELOG.md`.**
   Add a new section for the version being released (e.g., `## [0.1.0] — YYYY-MM-DD`) and document all changes since the previous release.

3. **Bump the version in `pom.xml`** (root, not a SNAPSHOT).
   ```bash
   mvn versions:set -DnewVersion=X.Y.Z -DgenerateBackupPoms=false
   ```
   Then verify every child module picked up the new version:
   ```bash
   grep -r "<version>X.Y.Z" --include="pom.xml"
   ```
   Update the Maven dependency snippets in `README.md` to reference the new version.

4. **Commit the release.**
   ```bash
   git add pom.xml */pom.xml CHANGELOG.md README.md
   git commit -m "chore: prepare release vX.Y.Z"
   git push origin <branch>
   ```

5. **Merge to `develop` (or `main`) via pull request** and wait for CI to go green.

6. **Tag the release.**
   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```
   Pushing the tag triggers the `publish.yml` workflow, which signs the artifacts with GPG and deploys them to Maven Central.

7. **Verify the release on Maven Central.**
   Allow up to 30 minutes for the artifacts to become searchable. Confirm all four artifacts are present:
   - `dev.ratkay:fhirmason-hapi-api:X.Y.Z`
   - `dev.ratkay:fhirmason-hapi-r4:X.Y.Z`
   - `dev.ratkay:fhirmason-hapi-dstu3:X.Y.Z`
   - `dev.ratkay:fhirmason-hapi-spring-r4:X.Y.Z`

8. **Create a GitHub Release** from the tag and paste the relevant `CHANGELOG.md` section as the release notes.

## Post-Release

- Bump the version in `pom.xml` to the next development SNAPSHOT (e.g., `0.2.0-SNAPSHOT`) and commit on the development branch.
