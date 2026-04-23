# EntryStore REST Integration Tests

Groovy/Spock integration tests for the Spring Boot REST layer. Tests drive real HTTP against a Spring Boot app running on port **8181**, backed by a Solr testcontainer (and, for SAML/CAS tests, a Keycloak testcontainer).

## Requirements

- **Java 25** (`mvn -v` must report JDK 25)
- **Docker** with the daemon running — used by [Testcontainers](https://www.testcontainers.org/) to spin up Solr and Keycloak images
- **Maven 3**
- Environment variable `TESTCONTAINERS_RYUK_DISABLED=true` in CI (see `bitbucket-pipelines.yml`); optional locally — without it, Testcontainers launches a Ryuk reaper container to clean up dangling containers on JVM exit

No local Solr or Keycloak installation is needed — the tests pull and start images automatically.

## Running

From the repository root:

```bash
# All integration tests
mvn clean verify -pl modules/rest/integration-test

# A single IT class
mvn clean verify -pl modules/rest/integration-test -Dtest=ProxyIT

# Skip OWASP dependency-check for faster local runs
mvn clean verify -pl modules/rest/integration-test -DskipDependencyCheck=true
```

Configuration:
- `src/test/resources/entrystore-it.properties` — EntryStore runtime properties
- `src/test/resources/application.yaml` — Spring Boot config (imports `entrystore-it.properties`)

## Container topology

| Container | Image | Lifecycle | Shared with |
|---|---|---|---|
| Solr | `solr:9.10.1` | Started once in `BaseSpec`, reused by every IT | All ITs |
| Keycloak | `quay.io/keycloak/keycloak:26.5.6` (via `dasniko/testcontainers-keycloak:4.1.1`) | Constructed as a static field in `KeycloakBaseSpec`; started on first use via `startKeycloakIfNeeded()` in a lifecycle-owning IT; reused thereafter | `ZzzSamlLoginIT`, `ZzzCasLoginIT` |

Keycloak loads `src/test/resources/test-realm-keycloak.json`, a single realm containing both the SAML client (`EntrystoreDev1`) and the CAS client (`http://localhost:8181/auth/cas`). The CAS protocol provider jar under `src/test/resources/libs/` is mounted unconditionally, so both protocols work from the same container.

## Test execution order

Failsafe is configured with `<runOrder>alphabetical</runOrder>` in this module's `pom.xml`. Test classes fall into two buckets:

1. **Shared-app ITs** (the vast majority) — extend `BaseSpec`. They run against a single Spring Boot app started once in `BaseSpec.setupSpec` (shared Solr, no SAML/CAS profile).
2. **Lifecycle-owning ITs** — extend `KeycloakBaseSpec` and own the Spring Boot lifecycle themselves (they close the shared app and start their own with SAML/CAS-specific args + Keycloak container). They must sort alphabetically **after** every shared-app IT, so they carry a `Zzz*` class-name prefix.

One CI run:

```
Starting EntryStoreApp                 ← BaseSpec, 1st start (for all shared-app ITs)
...shared-app ITs run...
Stopping pre-existing ES instance      ← ZzzCasLoginIT closes the shared app
Started Keycloak container             ← ZzzCasLoginIT starts Keycloak (one-time)
Starting EntryStoreApp with CAS        ← 2nd start
...ZzzCasLoginIT runs...
Stopping pre-existing ES instance      ← ZzzSamlLoginIT closes the CAS app
Reusing Keycloak container             ← ZzzSamlLoginIT reuses the container
Starting EntryStoreApp with SAML       ← 3rd start
...ZzzSamlLoginIT runs...
JVM exits                              ← Spring Boot shutdown hook closes the last app
```

Net: **3 Spring Boot starts, 1 Keycloak boot** per CI run. See [ENTRYSTORE-1019](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1019).

### Lifecycle invariants

Encoded in `BaseSpec.groovy` as comments alongside `appStarted`. Summary:

1. Lifecycle-owning ITs **must** be alphabetically after every shared-app IT (today enforced by the `Zzz*` prefix — do not add shared-app ITs whose simple name sorts at or after `Zzz`).
2. Lifecycle-owning ITs must set `appStarted = true` in `setupSpec` after starting their own app and must never reset it to false. `BaseSpec.setupSpec` guards the shared-app init block on `if (!appStarted)`; if a lifecycle-owning IT leaks `appStarted = false`, the guard re-runs the init block between lifecycle-owning ITs, adding an extra Spring Boot start per CI run.

`BaseSpec.setupSpec` asserts the two valid `(appStarted, appInstance)` state pairs to fail the affected spec loudly when someone violates the invariant.

## Writing a new IT

**Standard IT** (reuses the shared Spring Boot app):

```groovy
package org.entrystore.rest.it

class FooIT extends BaseSpec {
    def "GET /foo should return ..."() {
        when:
        def conn = EntryStoreClient.getRequest('/foo')
        then:
        conn.getResponseCode() == HTTP_OK
        // ...
    }
}
```

- File name: `FooIT.groovy`, pattern `*IT.groovy`
- Class name: any unique name **sorting strictly before `Zzz*`** (i.e. anything starting `A..Y` is fine)
- Extend `BaseSpec`
- Use `EntryStoreClient` for HTTP; `asUser=''` sends requests as guest
- If the test creates entries that other ITs might observe via SPARQL/Solr, isolate them with a test-specific predicate or context id (see `SearchIT.MARKER_PREDICATE_IRI`)

**Lifecycle-owning IT** (starts its own Spring Boot with non-default args — SAML, CAS, or other profile):

```groovy
package org.entrystore.rest.it

import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.springframework.boot.SpringApplication
import spock.lang.Stepwise

@Stepwise
class ZzzMyFlowIT extends KeycloakBaseSpec {
    def setupSpec() {
        stopPreexistingAppIfRunning()   // closes shared or previous lifecycle app
        startKeycloakIfNeeded()         // starts or reuses the shared Keycloak
        def args = [...] as String[]
        appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
        appStarted = true               // REQUIRED — see invariant #2 in BaseSpec
    }

    // No cleanupSpec needed: the next lifecycle-owning IT's stopPreexistingAppIfRunning
    // handles the handoff; the last one's app is closed by the JVM shutdown hook.
}
```

- File and class name: `Zzz<something>IT.groovy`
- Extend `KeycloakBaseSpec`
- Helpers on `KeycloakBaseSpec`: `startKeycloakIfNeeded()`, `getKeycloakSamlRealmUrl()`, `getKeycloakCasRealmUrl()`, `stopPreexistingAppIfRunning()`
- Do **not** set `appStarted = false` anywhere (breaks invariant #2)
- Do **not** add a `cleanupSpec` that closes `appInstance` — the next IT's `stopPreexistingAppIfRunning` handles it, and skipping saves a few ms per IT
