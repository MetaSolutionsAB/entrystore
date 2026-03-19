# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EntryStore is the reference implementation of the Resource and Metadata Management Model (ReM3) - a Linked Data framework for managing resources and their metadata using "entries". Built with Java 25, RDF4J, and Solr. The REST layer is being migrated from Restlet to Spring Boot (Jetty 12).

## Build Commands

**Always use `mvn clean`** — stale artifacts from other branches cause subtle failures.

**Java 25 required** — Lombok annotation processing is configured explicitly via `annotationProcessorPaths` (required since JDK 23+).

```bash
# Quick build (skip tests)
mvn clean install -Dmaven.test.skip=true
# or use the provided script:
./build.sh install

# Full build with all tests
mvn clean install

# Build specific module
mvn clean install -pl core/core-impl
mvn clean install -pl modules/rest-standalone/spring-boot

# Build Spring Boot module and all its dependencies
mvn clean install -pl modules/rest-standalone/spring-boot -am -DskipTests
```

## Testing

```bash
# Run all tests (unit + integration)
mvn clean install

# Unit tests only
mvn clean test

# Integration tests only (runs all ITs)
mvn clean verify -pl modules/rest-standalone/integration-test

# Run a specific integration test class
mvn clean verify -pl modules/rest-standalone/integration-test -Dtest=ProxyIT

# Run specific unit test
mvn clean test -Dtest=EntryImplTest
```

**Test structure:**
- Unit tests: `src/test/java/` with `*Test.java` pattern (JUnit 5)
- Integration tests: `modules/rest-standalone/integration-test/src/test/groovy/` with `*IT.groovy` pattern (Groovy/Spock)
- ITs extend `BaseSpec` which starts a Solr Testcontainer and the Spring Boot app on port 8181 (shared across all test classes)
- IT config: `modules/rest-standalone/integration-test/src/test/resources/entrystore-it.properties`
- IT Spring config: `modules/rest-standalone/integration-test/src/test/resources/application.yaml` (imports entrystore-it.properties)
- Test HTTP client: `EntryStoreClient.groovy` — uses raw `HttpURLConnection`, cookie-based auth, `asUser=''` for guest

## Architecture

```
entrystore/
├── core/
│   ├── core-api/              # Interface definitions (Entry, Context, PrincipalManager, etc.)
│   └── core-impl/             # Implementations using RDF4J for storage
├── modules/
│   ├── rest/                  # Legacy REST API (Restlet) — being migrated to Spring Boot
│   ├── rest-standalone/
│   │   ├── spring-boot/       # Spring Boot REST layer (active development)
│   │   └── integration-test/  # Groovy/Spock integration tests (Testcontainers)
│   ├── harvesting/            # OAI-PMH harvesting
│   └── transforms/            # Data format transformations
└── templates/                 # HTML/CSS templates
```

**Domain model:** Entry = Resource + Metadata (RDF graphs), organized into Contexts. EntryType: Local, Link, Reference, LinkReference. GraphType: None, Context, List, User, Group, etc. Key managers: RepositoryManager, PrincipalManager (users/groups/auth), ContextManager.

**URI conventions:**
- Each Entry has three distinct URIs: entry URI (`{base}/{ctx}/entry/{id}`), resource URI (`{base}/{ctx}/resource/{id}`), and metadata URI (`{base}/{ctx}/metadata/{id}`)
- A **principal's URI** (used in ACLs) is the **resource** URI of their entry, not the entry URI. Example: guest user URI = `{baseUrl}/_principals/resource/_guest` (not `_principals/entry/_guest`)
- A **context's resource URI** is `{baseUrl}/{contextId}` (the context itself), not `{baseUrl}/_contexts/resource/{contextId}`
- ACL triples use `es:read`/`es:write` predicates with the principal's resource URI as the object, and the entry's metadata/resource URI as the subject (e.g., `<metadataURI> es:read <principalResourceURI>` grants ReadMetadata)

### Spring Boot Module (`modules/rest-standalone/spring-boot/`)

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
- Controllers: `@RestController` + `@RequiredArgsConstructor` (Lombok) + `@Operation` (Swagger)
- Services: `@Slf4j` + `@Service` + `@RequiredArgsConstructor`
- Config: `application.yaml` imports `entrystore.properties` (same config format as legacy)
- Default port: 8080 (production), 8181 (integration tests)
- Embedded server: Jetty 12 (`spring-boot-starter-jetty`), not Tomcat

**REST routes** (Spring Boot controllers):
`/{context-id}/entry/{entry-id}`, `/{context-id}/resource/{entry-id}`, `/{context-id}/metadata/{entry-id}`, `/{context-id}/relation/{entry-id}`, `/search`, `/proxy`, `/{context-id}/proxy`, `/echo`, `/auth/*`, `/management/*`

**Spring bean dependency rules:**
- **Avoid and verify that no circular bean dependencies are introduced.** A `@Configuration` class is a wiring spec, not an actor — if it needs to do something beyond constructing objects, that logic belongs in a separate `@Service` or `@Component`. A common violation: a `@Configuration` class injecting a bean it itself produces (via constructor / `@RequiredArgsConstructor`), creating a self-referencing cycle that prevents startup.

## Code Style

**Use Java 25 features** when writing new code or refactoring: records, sealed classes, pattern matching (`instanceof`, `switch`), text blocks, enhanced `switch` expressions, etc. Prefer modern idioms over legacy patterns.

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
 * ...
 */
```

## Testing Guidelines

- **No tautological tests:** Every test must validate real behavior, not merely assert values that were set up by mocks. A test that only verifies mocked return values proves nothing — ensure tests exercise actual logic, integration points, or side effects that could genuinely fail.

## Exception Conventions (Spring Boot REST Layer)

- **Never throw `org.entrystore.AuthorizationException` directly from the Spring Boot REST layer** (controllers or services in `modules/rest-standalone/spring-boot/`). `AuthorizationException` belongs to the core layer and should only be thrown by core code (e.g., `PrincipalManager.checkAuthenticatedUserAuthorized()`).
- In the REST layer, use the application-specific exceptions from `org.entrystore.rest.standalone.springboot.model.exception.*`:
  - `ForbiddenException` — for authorization failures (returns 401 for anonymous users, 403 for authenticated users via `AppExceptionHandler`)
  - `BadRequestException` — for invalid input (400)
  - `EntityNotFoundException` — for missing resources (404)
  - `CustomResponseException` — for any other HTTP status (e.g., 504 Gateway Timeout)
- `AuthorizationException` thrown by core code (e.g., from `PrincipalManager`) is handled by `AppExceptionHandler` and does not need to be caught/re-thrown in the REST layer.
- **Never throw application exceptions from servlet filters.** `AppExceptionHandler` (`@ControllerAdvice`) only catches exceptions from controllers — filters run before the DispatcherServlet. Instead, write the error response directly: `response.setStatus(...)`, `response.setContentType(...)`, `response.getWriter().write(...)`, then `return` (do not call `filterChain.doFilter`).
- **Don't leak internal details in exception messages.** `AppExceptionHandler` returns `ex.getMessage()` to the client for custom exceptions (`BadRequestException`, `EntityNotFoundException`, etc.). Keep these messages user-facing. Never include `e.getMessage()` from third-party libraries (RDF4J, Jackson, Spring internals) in exceptions thrown to the client — use a generic message and preserve the original cause via the `(String, Throwable)` constructor for server-side debugging.
- **Use `HttpUtil.setLastModifiedAndETag(HttpHeaders, Date)`** to set Last-Modified and ETag response headers. For `ResponseEntity.HeadersBuilder` contexts (e.g., 204 No Content), use `HttpUtil.updateResponseWithModificationDateAndETag()` which delegates to the same logic. Do not set these headers manually.

## CI/CD

Bitbucket Pipelines with Maven 3 + Eclipse Temurin 21. Integration tests use Docker (TestContainers with `TESTCONTAINERS_RYUK_DISABLED=true`).

**Build optimization flags:**
```bash
# Skip OWASP dependency check (faster local builds)
mvn clean install -DskipDependencyCheck=true

# Debug mode
mvn clean install -X
```

## Running EntryStore

```bash
# Build the executable jar (version from VERSION.txt, e.g. 6.0-SNAPSHOT)
mvn clean package -DskipTests

# Run (-exec classifier is the executable fat jar)
java -jar modules/rest-standalone/spring-boot/target/entrystore-rest-standalone-spring-boot-*-exec.jar
```

**Configuration:** The app imports `entrystore.properties` from the working directory via `spring.config.import` in `application.yaml`. Three ways to provide config:

1. **Properties file** — place `entrystore.properties` in the working directory
2. **Remote URL** — set `ENTRYSTORE_CONFIG_URI` env var (`http://`, `https://`, `file://`, or local path)
3. **CLI args** — override individual properties: `--entrystore.solr.url=http://... --server.port=8080`

Example config: `modules/rest/src/main/resources/entrystore.properties_example`

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

- Commit messages reference JIRA issues: `ENTRYSTORE-####: Description`
- Do not include AI/agent attribution in commit messages (no Co-Authored-By or similar)
- Issue tracker: https://metasolutions.atlassian.net/browse/ENTRYSTORE-*
- **JIRA priorities:** Blocker, Critical, Major, Minor, Trivial
- **Spring Boot migration epic:** ENTRYSTORE-857 — create JIRA issues related to the Spring Boot REST layer (`modules/rest-standalone/spring-boot/`) under this epic
