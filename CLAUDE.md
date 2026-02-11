# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EntryStore is the reference implementation of the Resource and Metadata Management Model (ReM3) - a Linked Data framework for managing resources and their metadata using "entries". Built with Java 21, Restlet, RDF4J, and Solr.

## Build Commands

```bash
# Quick build (skip tests)
mvn install -Dmaven.test.skip=true
# or use the provided script:
./build.sh install

# Full build with all tests
mvn clean install

# Build specific module
mvn clean install -pl core/core-impl
mvn clean install -pl modules/rest
```

## Testing

```bash
# Run all tests (unit + integration)
mvn clean install

# Unit tests only
mvn test

# Integration tests only
mvn verify

# Run specific unit test
mvn test -Dtest=EntryImplTest

# Run specific integration test
mvn verify -Dit.test=CookieLoginResourceIT

# Integration tests in specific module
mvn verify -pl modules/rest-standalone/integration-test
```

**Test structure:**
- Unit tests: `src/test/java/` with `*Test.java` pattern (JUnit 5)
- Integration tests: `src/test/groovy/` with `*IT.groovy` pattern (Groovy/Spock)
- ITs extend `BaseSpec` (in-memory EntryStore). Config: `modules/rest-standalone/integration-test/src/test/resources/entrystore-it.properties`

## Architecture

```
entrystore/
├── core/
│   ├── core-api/          # Interface definitions (Entry, Metadata, Context, Resource)
│   └── core-impl/         # Implementations using RDF4J for storage
├── modules/
│   ├── rest/              # REST API (Restlet), packaged as WAR
│   ├── rest-standalone/   # Standalone deployments
│   │   ├── common/        # Shared REST code
│   │   ├── jetty/         # Embedded Jetty (production artifact)
│   │   └── integration-test/  # Full REST API integration tests
│   ├── harvesting/        # OAI-PMH harvesting
│   │   ├── factory/
│   │   ├── oaipmh-harvester/
│   │   └── oaipmh-target/
│   └── transforms/        # Data format transformations
│       ├── rowstore/
│       └── tabular/
└── templates/             # HTML/CSS templates
```

**Domain model:** Entry = Resource + Metadata (RDF graphs), organized into Contexts. EntryType: Local, Link, Reference, LinkReference. GraphType: None, Context, List, User, Group, etc. Key managers: RepositoryManager, PrincipalManager (users/groups/auth), ContextManager.

**REST routes** (mapped in `EntryStoreApplication.createInboundRoot()`):
`/{ctx}/entry/{id}`, `/{ctx}/resource/{id}`, `/{ctx}/metadata/{id}`, `/search`, `/sparql`, `/lookup`, `/auth/*`, `/management/*`

## Code Style

From `.editorconfig`:
- Java/Groovy/XML: tabs for indentation
- JSON/YAML: 2-space indent
- Max line length: 120
- Avoid wildcard imports (IntelliJ settings enforce single imports)

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

`./modules/rest-standalone/jetty/target/dist/bin/entrystore -c file:/path/to/entrystore.properties -p 8181`

Config via: `-c` flag, `ENTRYSTORE_CONFIG_URI` env var, or `ENTRYSTORE_CONFIG_PROPERTIES` (comma-separated key=value overrides). Example: `modules/rest/src/main/resources/entrystore.properties_example`

**Key properties:** `entrystore.baseurl.folder`, `entrystore.repository.store.type` (memory/native/http/sparql), `entrystore.repository.store.path`, `entrystore.data.folder`, `entrystore.solr`, `entrystore.auth.*`, `entrystore.cors`

## Git Conventions

- Commit messages reference JIRA issues: `ENTRYSTORE-####: Description`
- Do not include AI/agent attribution in commit messages (no Co-Authored-By or similar)
- Issue tracker: https://metasolutions.atlassian.net/browse/ENTRYSTORE-*
