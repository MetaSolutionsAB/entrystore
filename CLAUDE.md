# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EntryStore is the reference implementation of the Resource and Metadata Management Model (ReM3) - a Linked Data framework for managing resources and their metadata using "entries". Built with Java 25, RDF4J, and Solr. The REST layer uses Spring Boot 4.1 (Jetty 12).

## Build & Test

**Always use `./mvnw clean`** — stale artifacts from other branches cause subtle failures.

Maven is supplied by the Maven Wrapper (`./mvnw` / `mvnw.cmd`), pinned to 3.9.16 — no system Maven install required. **Java 25 required** — Lombok annotation processing is configured explicitly via `annotationProcessorPaths` (required since JDK 23+).

```bash
./mvnw clean install                                                # full build: unit + integration tests
./mvnw clean install -Dmaven.test.skip=true                         # quick build without tests (or: ./build.sh clean)
./mvnw clean install -pl core/core-impl                             # single module
./mvnw clean install -pl modules/rest/spring-boot -am -DskipTests   # single module plus its dependencies
./mvnw clean test                                                   # unit tests only (one class: -Dtest=EntryImplTest)
./mvnw clean verify -pl modules/rest/integration-test               # integration tests only (one class: -Dit.test=ProxyIT)
```

**Test structure:**
- Unit tests: `src/test/java/` with `*Test.java` pattern (JUnit 5)
- Integration tests: `modules/rest/integration-test/src/test/groovy/` with `*IT.groovy` pattern (Groovy/Spock)
- ITs extend `BaseSpec` which starts a Solr Testcontainer and the Spring Boot app on port 8181 (shared across all test classes)
- ITs that own their own Spring Boot lifecycle (start the app with non-default args) extend `KeycloakBaseSpec` — which adds a shared Keycloak testcontainer — and use a `Zzz*` class-name prefix so Failsafe's alphabetical `runOrder` schedules them after all shared-app ITs (see `ZzzSamlLoginIT`, `ZzzCasLoginIT`)
- `BaseSpec` also starts a shared **WireMock** server (dynamic port) for stubbing external HTTP services so ITs never depend on a live network. Today it stubs the reCAPTCHA `siteverify` endpoint with an always-success response; it requires corresponding `entrystore.auth.recaptcha.url` to be injected into the Spring Boot app via command-line args.
- IT config: `modules/rest/integration-test/src/test/resources/entrystore-it.properties`
- IT Spring config: `modules/rest/integration-test/src/test/resources/application.yaml` (imports entrystore-it.properties)
- Test HTTP client: `EntryStoreClient.groovy` — uses raw `HttpURLConnection`, cookie-based auth, `asUser=''` for guest

## Architecture

```
entrystore/
├── core/
│   ├── core-api/              # Interface definitions (Entry, Context, PrincipalManager, etc.)
│   └── core-impl/             # Implementations using RDF4J for storage
├── modules/
│   ├── rest/
│   │   ├── spring-boot/       # Spring Boot REST layer (active development)
│   │   └── integration-test/  # Groovy/Spock integration tests (Testcontainers)
│   ├── harvesting/            # OAI-PMH harvesting
│   ├── transforms/            # Data format transformations
│   └── benchmark/             # Benchmarks
└── templates/                 # HTML/CSS templates
```

**Domain model:** Entry = Resource + Metadata (RDF graphs), organized into Contexts. EntryType: Local, Link, Reference, LinkReference. GraphType: None, Context, List, User, Group, etc. Key managers: RepositoryManager, PrincipalManager (users/groups/auth), ContextManager.

**URI conventions:**
- Each Entry has three distinct URIs: entry URI (`{base}/{ctx}/entry/{id}`), resource URI (`{base}/{ctx}/resource/{id}`), and metadata URI (`{base}/{ctx}/metadata/{id}`)
- A **principal's URI** (used in ACLs) is the **resource** URI of their entry, not the entry URI. Example: guest user URI = `{baseUrl}/_principals/resource/_guest` (not `_principals/entry/_guest`)
- A **context's resource URI** is `{baseUrl}/{contextId}` (the context itself), not `{baseUrl}/_contexts/resource/{contextId}`
- ACL triples use `es:read`/`es:write` predicates with the principal's resource URI as the object, and the entry's metadata/resource URI as the subject (e.g., `<metadataURI> es:read <principalResourceURI>` grants ReadMetadata)

### Spring Boot Module (`modules/rest/spring-boot/`)

```
springboot/
├── configuration/         # App config, MVC config, content negotiation, SAML, request logging
├── controller/            # REST controllers + AppExceptionHandler (@ControllerAdvice)
├── model/
│   ├── api/               # Request/response records (e.g., GetEntryResponse, ErrorResponse)
│   ├── auth/              # Auth-related models
│   ├── dto/               # Internal DTOs (e.g., ProxyResponse, QueryResultsDto)
│   ├── exception/         # Custom exception classes (BadRequestException, ForbiddenException, etc.)
│   └── serializer/        # Custom JSON serializers
├── security/              # Spring Security config, filters, SAML handlers, UserDetailsService
├── service/               # Business logic services
└── util/                  # Utility classes
```

**Key patterns:**
- Spring Boot **4.1** (`spring-boot-starter-parent` version in root `pom.xml`) on embedded Jetty 12 (`spring-boot-starter-jetty`), not Tomcat. Verify APIs against Boot 4.1, not Boot 3.x — 4.x relocated/renamed packages. Prefer `RestClient` over the deprecated `RestTemplate`.
- Controllers: `@RestController` + `@RequiredArgsConstructor` (Lombok) + `@Operation` (Swagger)
- Services: `@Slf4j` + `@Service` + `@RequiredArgsConstructor`
- For new services, read config via Spring Boot mechanisms (`@Value("${prop:default}")` or `@ConfigurationProperties`). Some services still call `repositoryManager.getConfiguration().get*` directly — migrate when touching them.
- `@ConfigurationProperties` records must declare only the canonical constructor — a second constructor silently makes nested binding return empty values (no error).
- Config: `application.yaml` imports `entrystore.properties` via `spring.config.import` — every `entrystore.*` key is bound to the Spring `Environment` and is readable via `@Value`, `@ConfigurationProperties`, and `@ConditionalOnProperty`, not only via the legacy `Config` wrapper
- JSON: Jackson 3 — use `tools.jackson.*` (not `com.fasterxml.jackson.*`); annotations stay in `com.fasterxml.jackson.annotation.*` **except** `@JsonSerialize`/`@JsonDeserialize` (`tools.jackson.databind.annotation.*`). Custom serializers extend `ValueSerializer`/`ValueDeserializer`; mappers are immutable (`JsonMapper.builder()...build()`).

**REST routes** (one controller per route family in `controller/`):
`/{context-id}` (the context itself, plus `/{context-id}/export`, `/{context-id}/import`), `/{context-id}/entry/{entry-id}`, `/{context-id}/resource/{entry-id}`, `/{context-id}/metadata/{entry-id}`, `/{context-id}/relations/{entry-id}`, `/{context-id}/merge`, `/{context-id}/execute`, `/search`, `/sparql`, `/lookup`, `/proxy` (each of `/sparql`, `/lookup`, `/proxy` also exists context-scoped, e.g. `/{context-id}/proxy`), `/message`, `/echo`, `/validator`, `/_principals/groups`, `/auth/*`, `/management/*`

**Spring bean dependency rules:**
- **Avoid and verify that no circular bean dependencies are introduced.** A `@Configuration` class is a wiring spec, not an actor — if it needs to do something beyond constructing objects, that logic belongs in a separate `@Service` or `@Component`. A common violation: a `@Configuration` class injecting a bean it itself produces (via constructor / `@RequiredArgsConstructor`), creating a self-referencing cycle that prevents startup.

## Code Style

**Use Java 25 features** when writing new code or refactoring. Prefer modern idioms (records, sealed classes, pattern matching for `instanceof` and `switch`, text blocks, enhanced `switch` expressions, virtual threads, sequenced collections, scoped values) over legacy patterns equivalent to them.

**Prefer Lombok annotations over hand-written boilerplate.**
Reach for the annotation first; only write the expansion manually when Lombok can't express the intent.

Formatting is IntelliJ-based, defined in `.editorconfig` (with `ij_*` properties) and `.idea/codeStyles/`. No Eclipse formatter is used.

From `.editorconfig`:
- Java/Groovy/XML: tabs for indentation
- JSON/YAML: 2-space indent
- Default: 4-space indent
- Max line length: 120
- Continuation indent: 8 spaces (Java), 4 spaces (Groovy)
- Avoid wildcard imports (import-on-demand threshold set to 99)

**License header:** All Java and Groovy source files must include an Apache 2.0 license header. When editing a file, ensure the header is present and that the year range is current (ends with the current year). Use the current year from `date +%Y`. Format:
```java
/*
 * Copyright (c) 2007-YYYY MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * [include the standard Apache 2.0 boilerplate text — copy verbatim from any existing source file in this repo for the canonical wording]
 */
```

## Testing Guidelines

- **No tautological tests.** Every test must validate real behaviour, not assertions over values the test itself set up via mocks. A test that only verifies mocked return values proves nothing.
- **Keep tests DAMP** (Descriptive And Meaningful Phrases). Default to one `@Test` (or Spock `def`) per case so each test reads as a complete recipe; helper methods in a `given:` block or a test setup are allowed. This is not a hard rule: a `@ParameterizedTest` / `@CsvSource` / `@MethodSource` (JUnit) or `where:` table (Spock) is fine — and preferred — when **at least 4** structurally-identical cases share the exact same `when:`/`then:` shape and only the inputs/expected outputs vary. Keep a descriptive `name = "{0}"` template (or equivalent display names) so each row still documents itself.

## Exception Conventions (Spring Boot REST Layer)

- **Never throw `org.entrystore.AuthorizationException` directly from the Spring Boot REST layer** (controllers or services in `modules/rest/spring-boot/`). `AuthorizationException` belongs to the core layer and should only be thrown by core code (e.g., `PrincipalManager.checkAuthenticatedUserAuthorized()`).
- In the REST layer, use the application-specific exceptions from `org.entrystore.rest.springboot.model.exception.*`:
  - `ForbiddenException` — for explicit policy denials (returns 401 for anonymous users, 403 for authenticated users via `AppExceptionHandler`).
  - `BadRequestException` — for invalid input (400)
  - `EntityNotFoundException` — for missing resources (404)
  - `CustomResponseException` — for any other HTTP status (e.g., 504 Gateway Timeout)
- `AuthorizationException` thrown by core code (e.g., from `PrincipalManager`, `ContextImpl`) is handled by `AppExceptionHandler` and does not need to be caught/re-thrown in the REST layer. Anonymous callers get **404 Not Found** (not 401) to prevent entry-existence enumeration (CWE-204); authenticated callers get 403.
- **Never throw application exceptions from servlet filters.** `AppExceptionHandler` (`@ControllerAdvice`) only catches exceptions from controllers — filters run before the DispatcherServlet. Instead, write the error response directly: `response.setStatus(...)`, `response.setContentType(...)`, `response.getWriter().write(...)`, then `return` (do not call `filterChain.doFilter`).
- **Don't leak internal details in exception messages.** `AppExceptionHandler` returns `ex.getMessage()` to the client for custom exceptions in `org.entrystore.rest.springboot.model.exception.*` (e.g. `BadRequestException`, `EntityNotFoundException`, `ForbiddenException`, `CustomResponseException`). Keep these messages user-facing. Never include `e.getMessage()` from third-party libraries (RDF4J, Jackson, Spring internals) in exceptions thrown to the client — use a generic message and preserve the original cause via the `(String, Throwable)` constructor for server-side debugging.
- **Don't log before throwing exceptions handled by `AppExceptionHandler`.** The handler already logs everything it handles. Exception: add an explicit `log.error` / `log.warn` when the specific exception handler logs that exception at a lower level and the underlying event should be visible in the logs.
- **Use `HttpUtil.setLastModifiedAndETag(HttpHeaders, Date)`** to set Last-Modified and ETag response headers. For `ResponseEntity.HeadersBuilder` contexts (e.g., 204 No Content), use `HttpUtil.updateResponseWithModificationDateAndETag()` which delegates to the same logic. Do not set these headers manually.

## CI/CD

Bitbucket Pipelines (`bitbucket-pipelines.yml`) with Eclipse Temurin 25, building via `./mvnw`. Integration tests use Docker (TestContainers with `TESTCONTAINERS_RYUK_DISABLED=true`).

## Running EntryStore

```bash
# Build the executable jar (version from VERSION.txt, e.g. 6.0-SNAPSHOT)
./mvnw clean package -DskipTests

# Run (-exec classifier is the executable fat jar)
java -jar modules/rest/spring-boot/target/entrystore-rest-spring-boot-*-exec.jar
```

**Configuration:** The app imports `entrystore.properties` from the working directory via `spring.config.import` in `application.yaml`. Three ways to provide config:

1. **Properties file** — place `entrystore.properties` in the working directory
2. **Remote URL** — set `ENTRYSTORE_CONFIG_URI` env var (`http://`, `https://`, `file://`, or local path)
3. **CLI args** — override individual properties: `--entrystore.solr.url=http://... --server.port=8080`

Example config: `modules/rest/spring-boot/src/main/resources/entrystore.properties_example`

**Default port:** 8080 (production), 8181 (integration tests)

**Data directories** (configure paths in `entrystore.properties`):

| Property | Default | Content |
|---|---|---|
| `entrystore.repository.store.path` | - | RDF triple store |
| `entrystore.data.folder` | - | Uploaded files |
| `entrystore.solr.url` | - | Solr URL (external) or index path |
| `entrystore.backup.folder` | - | Backups |

**Key properties:** `entrystore.baseurl.folder`, `entrystore.repository.store.type` (memory/native/http/sparql), `entrystore.repository.store.path`, `entrystore.data.folder`, `entrystore.solr`, `entrystore.auth.*`, `entrystore.cors`

**JVM tuning:** use `JAVA_TOOL_OPTIONS` env var (e.g., `JAVA_TOOL_OPTIONS="-Xmx2g -Xms512m"`)

## Git Conventions

- **PR target branch:** `develop-spring` (not `develop`, until the Spring Boot migration merges back)
- Commit messages reference JIRA issues: `ENTRYSTORE-####: Description`
- Do not include AI/agent attribution in commit messages (no Co-Authored-By or similar)
- Issue tracker: https://metasolutions.atlassian.net/browse/ENTRYSTORE-*
- **JIRA priorities:** Blocker, Critical, Major, Minor, Trivial

## Model Compatibility

This project targets the latest Claude models — no pinned version. Write prompt content (agents, skills, instruction files) to be safe under the most literal instruction interpretation, so it works unchanged across model upgrades:

- Use inspectable predicates (file-pattern matches, regex, explicit enum lists) instead of vague adjectives like "relevant" or "appropriate"
- State subagent-launch rules explicitly (which agents, one Task call each, same assistant message)
- Avoid `etc.`, open-ended `...`, and trailing ellipses in instruction text
- Replace `...` in output templates with `[repeat per X]` directives
- When stating an absolute rule (`always`, `never`), co-locate the concrete exception if one exists, rather than contradicting it in a later paragraph
