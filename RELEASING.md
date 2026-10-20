# Releasing jfxmap to Maven Central

Every `jfxmap-*` module (the BOM included) is released together, under the group id `io.github.quyvu01` and one
version. A tag `v<version>` runs [.github/workflows/release.yml](.github/workflows/release.yml): it builds and tests
everything, signs the artifacts, uploads them to the [Central Portal](https://central.sonatype.com) and waits until the
Portal has validated them. The deployment then waits for **Publish** on
<https://central.sonatype.com/publishing/deployments> (set the repository variable `CENTRAL_PUBLISHING_TYPE` to
`AUTOMATIC` to publish with no click). A version on Maven Central can never be changed or deleted.

## Once

1. Sign in to <https://central.sonatype.com> with the GitHub account `quyvu01`. The namespace `io.github.quyvu01` is
   then verified with no further step (Namespaces page).
2. Make a GPG key and publish its public half, so Maven Central can check the signatures:

   ```sh
   gpg --quick-gen-key "Quy Vu <you@example.com>" rsa4096 sign 3y
   gpg --list-secret-keys --keyid-format long          # the id after rsa4096/
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
   gpg --keyserver keys.openpgp.org --send-keys <KEY_ID>
   ```

3. On the Central Portal: your account > **Generate User Token**. It gives a username and a password.
4. On GitHub, quyvu01/jfxmap > Settings > Secrets and variables > Actions, add four repository secrets:

   | Secret | Value |
   | --- | --- |
   | `CENTRAL_TOKEN_USERNAME` | the user token's username |
   | `CENTRAL_TOKEN_PASSWORD` | the user token's password |
   | `SIGNING_KEY` | `gpg --armor --export-secret-keys <KEY_ID>`, all of it, BEGIN and END lines included |
   | `SIGNING_KEY_PASSWORD` | the key's passphrase |

## Each release

```sh
git checkout main && git pull
git tag v0.1.0
git push origin v0.1.0
```

Then press **Publish** on the deployment once the release workflow is green. The artifacts can be downloaded within
about half an hour, and appear in search on central.sonatype.com later. `gradle.properties` keeps the development
version (`0.1.0-SNAPSHOT`); bump it after a release.

## Locally

```sh
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=<passphrase>
./gradlew verifyCentralBundle -Pversion=0.1.0      # build/central-bundle.zip, checked
```

`build/central-bundle.zip` can also be uploaded by hand on the Portal (Publish Component). `centralBundle` refuses a
SNAPSHOT version and unsigned artifacts; `verifyCentralBundle` fails when a module misses its POM, jar, sources,
javadoc, a signature or a checksum. CI builds and checks the bundle on every push, signed with a throwaway key.
