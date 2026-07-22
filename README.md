# identity-service

Enterprise SSO groundwork: a Spring Boot resource server designed to sit behind Keycloak as the single source of truth for authentication and authorization.

![Java](https://img.shields.io/badge/Java-21-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.3.4-brightgreen)
![Keycloak](https://img.shields.io/badge/Keycloak-25.0.6-blue)
![MySQL](https://img.shields.io/badge/MySQL-8-lightblue)
![Flyway](https://img.shields.io/badge/Flyway-DB%20Migrations-red)
![License](https://img.shields.io/badge/license-MIT-lightgrey)

> **Learning Track:** `springboot-keycloak-sso-demo` (Project 4 of 17)
> **Real-World Service Name:** `identity-service`

---

## Status: Work in Progress

This repository is being built in stages. **What you're looking at right now is Stage 1: the build skeleton** — the Maven project definition, dependency set, and container image build for the service. The Spring Boot application source (security config, controllers, entities, `application.yml`, Keycloak realm import, `docker-compose.yml`) is the next stage and will land in a follow-up commit.

Documenting this honestly (rather than inventing endpoints/config that don't exist yet) is deliberate: this README describes exactly what is in the repository today, and flags clearly what's still to come, so nothing below misleads you about the actual state of the code.

---

## 1. Project Overview

### The problem this service solves

In a multi-application enterprise (HR portal, billing dashboard, internal admin tools, partner APIs, ...), letting every application own its own username/password store is a liability: password resets, MFA, session revocation, and audit trails all have to be reimplemented per app, and a breach in the weakest app's login form compromises the same credentials everywhere.

**Single Sign-On (SSO)** solves this by centralizing authentication in one identity provider. A user logs in once; every downstream application trusts a signed token issued by that identity provider instead of managing its own password database.

### Why Keycloak

Keycloak is an open-source Identity and Access Management (IAM) server built on the OpenID Connect (OIDC) / OAuth2 and SAML2 standards. It gives you, out of the box:

- A hosted login page, user federation (LDAP/AD), and social login, with no custom auth code.
- Realm-based multi-tenancy — one Keycloak instance can serve many independent applications/organizations ("realms").
- Fine-grained authorization: realm roles, client roles, and group-based role mapping.
- Standards-compliant tokens (JWT access tokens, refresh tokens, ID tokens) that any OAuth2/OIDC-aware framework — including Spring Security's `oauth2-resource-server` — can validate without talking to Keycloak on every request (signature + JWKS-based validation).

`identity-service`'s role in this architecture is **not** to replace Keycloak — it's the Spring Boot side of the handshake: a resource server that validates the JWTs Keycloak issues, plus (via the `keycloak-admin-client` dependency already in the POM) an administrative layer for programmatically managing users/roles in Keycloak from application code (e.g. self-service registration flows, provisioning users from an internal HR system).

### Where this pattern shows up in real companies

- **Internal enterprise SSO**: employees log into Okta/Keycloak/Azure AD once and get access to Jira, Confluence, internal dashboards, VPN portals, etc., without re-entering credentials.
- **B2B SaaS platforms**: a vendor exposes "Login with your company SSO" so each enterprise customer can federate through their own identity provider (SAML/OIDC) instead of the vendor managing per-customer passwords.
- **Microservice ecosystems**: instead of every microservice validating a username/password, each service is a stateless OAuth2 resource server that only checks a JWT's signature and scopes/roles — this is precisely the `spring-boot-starter-oauth2-resource-server` pattern used here.
- **Regulated industries (banking, healthcare)**: centralizing auth in a hardened IAM product (rather than bespoke code) is often a compliance requirement (SOC 2, HIPAA, PCI-DSS) because audit, MFA enforcement, and session/token revocation live in one place.

---

## 2. Architecture

### High-Level Design (target architecture for this service)

```
                                   ┌─────────────────────────┐
                                   │        Keycloak          │
                                   │  (Identity Provider /    │
                                   │   Authorization Server)  │
                                   │                           │
                                   │  Realm: enterprise-sso    │
                                   │  - Users / Roles / Groups │
                                   │  - Login UI, MFA          │
                                   │  - Token issuance (JWT)   │
                                   └────────────┬──────────────┘
                                                │
                        1. Login redirect        │  4. JWKS (public keys)
                        2. Auth code → token      │     for signature verification
                                                │
        ┌───────────────┐   3. Bearer JWT   ┌────▼─────────────────┐
        │   Client App   │ ────────────────► │   identity-service    │
        │ (Web/Mobile/   │ ◄──────────────── │  (Spring Boot          │
        │  Partner API)  │   5. API response │   Resource Server)     │
        └───────────────┘                    │                        │
                                              │ - Validates JWT sig    │
                                              │ - Enforces roles/scopes│
                                              │ - Keycloak Admin Client│
                                              │   for user provisioning│
                                              └───────────┬────────────┘
                                                          │
                                                          │ JDBC (Flyway-migrated)
                                                          ▼
                                                  ┌───────────────┐
                                                  │     MySQL      │
                                                  │ (app-owned data,│
                                                  │ NOT credentials)│
                                                  └───────────────┘
```

Key point: MySQL here stores **application data** (e.g. profile extensions, audit logs, provisioning records) — never passwords. Credentials, sessions, and MFA state live entirely inside Keycloak's own store.

### Low-Level Design (request flow, once security config lands)

```
Client                identity-service                Keycloak
  │  GET /api/... + Bearer <JWT>                          │
  ├───────────────────────►│                              │
  │                        │  JwtDecoder fetches/caches   │
  │                        │  JWKS from Keycloak realm    │
  │                        │  issuer-uri endpoint         │
  │                        ├─────────────────────────────►│
  │                        │◄─────────────────────────────┤
  │                        │  (public signing keys)       │
  │                        │                              │
  │                        │  Validate signature, exp,    │
  │                        │  iss claim                   │
  │                        │  Map realm_access.roles →    │
  │                        │  GrantedAuthority             │
  │                        │  SecurityFilterChain enforces │
  │                        │  @PreAuthorize / role rules   │
  │                        │                              │
  │◄───────────────────────┤  200 OK / 403 Forbidden       │
```

### Folder structure (current state of this repository)

```
identity-service/
├── pom.xml              # Maven build definition — dependencies, plugins, versions
├── Dockerfile           # Multi-stage build → runnable container image
├── .dockerignore        # Keeps the Docker build context small
├── .gitignore           # Keeps build output / IDE files out of git
└── README.md            # This file
```

There is currently no `src/` directory. Once application code is added it will follow the standard Maven layout: `src/main/java/com/medha/identityservice/...` (config, controller, service, entity, repository packages), `src/main/resources/application.yml`, and `src/main/resources/db/migration/` for Flyway SQL scripts.

### Database design

Not yet defined — no entities or Flyway migration scripts exist in this commit. The POM already includes `flyway-core` and `flyway-mysql`, so migrations under `src/main/resources/db/migration/V1__*.sql` are expected in the next stage rather than JPA `ddl-auto` schema generation (Flyway is deliberately chosen over Hibernate auto-DDL for anything touching production-shaped data — it keeps schema changes reviewable and repeatable across environments).

---

## 3. Tech Stack

| Layer | Technology | Version | Purpose |
|---|---|---|---|
| Language | Java | 21 | LTS release; used for records, virtual threads eligibility, pattern matching |
| Framework | Spring Boot | 3.3.4 | Application framework (parent POM) |
| Web | Spring Boot Starter Web | (managed by parent) | REST controllers, embedded Tomcat |
| Persistence | Spring Boot Starter Data JPA | (managed by parent) | Repository/entity layer for app-owned data |
| Security | Spring Boot Starter Security | (managed by parent) | Core security filter chain |
| Auth | Spring Boot Starter OAuth2 Resource Server | (managed by parent) | Validates Keycloak-issued JWTs |
| Identity Provider | Keycloak | 25.0.6 | Externalized IAM — login, tokens, realms, roles |
| Admin SDK | keycloak-admin-client | 25.0.6 | Programmatic user/role management against Keycloak's Admin REST API |
| Validation | Spring Boot Starter Validation | (managed by parent) | Bean Validation (`@Valid`, `@NotNull`, etc.) |
| Observability | Spring Boot Starter Actuator | (managed by parent) | Health/metrics endpoints |
| DB Migrations | Flyway (`flyway-core`, `flyway-mysql`) | (managed by parent) | Versioned, reviewable schema migrations |
| Database | MySQL (`mysql-connector-j`) | 8.x runtime driver | Relational store for application-owned data |
| API Docs | springdoc-openapi-starter-webmvc-ui | 2.6.0 | OpenAPI 3 spec + Swagger UI |
| Boilerplate reduction | Lombok | (managed by parent, optional) | Getters/setters/builders via annotations |
| Testing | spring-boot-starter-test, spring-security-test | (managed by parent) | Unit/integration test support, security test helpers |
| Testing (integration) | Testcontainers (`junit-jupiter`, `mysql`), BOM 1.20.1 | 1.20.1 | Real MySQL instance in Docker for integration tests |
| Packaging | spring-boot-maven-plugin | (managed by parent) | Builds the executable fat JAR, excludes Lombok from the runtime jar |
| Containerization | Docker (multi-stage: `maven:3.9.9-eclipse-temurin-21` → `eclipse-temurin:21-jre-jammy`) | — | Reproducible build + minimal JRE-only runtime image |

---

## 4. Configuration Explained

No `application.yml` / `application.properties` file exists in the repository yet — that lands with the application source code in the next stage. What **is** already fixed by the build configuration:

- **`pom.xml` → `<parent>` `spring-boot-starter-parent` `3.3.4`**: pins every Spring Boot managed dependency (Spring Framework, Tomcat, Jackson, etc.) to versions tested together, so you never hand-pick a mismatched combination.
- **`<java.version>21</java.version>`**: tells `spring-boot-starter-parent` to compile with `--release 21`, and matches the JDK used in both Docker stages (`eclipse-temurin:21`) so the build and runtime JVMs agree exactly.
- **`<keycloak.version>25.0.6</keycloak.version>`**: pins the `keycloak-admin-client` version explicitly (Spring Boot's BOM doesn't manage Keycloak artifacts), matched to the Keycloak server version this service is expected to talk to — the admin client's REST contract is version-sensitive, so client and server versions should stay aligned.
- **`<springdoc.version>2.6.0</springdoc.version>`**: pinned separately because springdoc-openapi is also not part of Spring Boot's dependency management; 2.6.0 is the version compatible with Spring Boot 3.3.x/Spring Framework 6.1.x.
- **`<finalName>identity-service</finalName>`** (in `<build>`): forces the packaged artifact to be `identity-service.jar` regardless of the Maven version coordinate, which is what the `Dockerfile`'s `COPY --from=build /workspace/target/identity-service.jar app.jar` line depends on — if this were removed, the jar name would become `identity-service-1.0.0.jar` and the Docker build would break.
- **Lombok excluded from the repackaged jar** (`spring-boot-maven-plugin` → `<excludes>`): Lombok is a compile-time-only annotation processor; shipping it in the runtime fat jar would be dead weight, so it's explicitly excluded from repackaging while still being `optional` on the classpath for compilation.
- **Testcontainers BOM `1.20.1`** (`<dependencyManagement>`): centralizes the version for `testcontainers-junit-jupiter` and `testcontainers-mysql` so both testing artifacts stay compatible with each other.

When `application.yml` is added, expect (and this is the intended design, to be implemented next) properties such as:
- `spring.datasource.url/username/password` — MySQL connection, environment-variable-driven so secrets never live in the file.
- `spring.jpa.hibernate.ddl-auto: validate` — Hibernate should only *validate* the schema Flyway already migrated, never auto-generate it.
- `spring.flyway.locations` — where migration scripts live.
- `spring.security.oauth2.resourceserver.jwt.issuer-uri` — the Keycloak realm URL (e.g. `http://keycloak:8080/realms/enterprise-sso`) Spring Security uses to auto-discover the JWKS endpoint and validate token signatures/issuer.
- `keycloak.admin.*` (custom properties) — server URL, realm, and service-account client credentials for the `keycloak-admin-client` to authenticate against Keycloak's Admin REST API.

---

## 5. Project Structure Explained

| Path | Why it exists |
|---|---|
| `pom.xml` | Single source of truth for the module's identity (`com.medha:identity-service`), Java version, every dependency (web, security, JPA, OAuth2 resource server, Flyway, MySQL driver, Keycloak admin client, springdoc, Lombok, test/Testcontainers), and the build plugin that produces the runnable jar. |
| `Dockerfile` | Multi-stage build: stage 1 (`maven:3.9.9-eclipse-temurin-21`) compiles and packages the app inside a throwaway builder image so the host machine doesn't need Maven/JDK installed; stage 2 (`eclipse-temurin:21-jre-jammy`) copies only the final jar into a slim JRE-only image, dramatically shrinking the shipped image and its attack surface. |
| `.dockerignore` | Prevents `target/`, IDE metadata, `.git/`, and markdown files from being sent into the Docker build context — keeps `docker build` fast and the context free of irrelevant/sensitive files. |
| `.gitignore` | Keeps compiled classes (`target/`, `*.class`), logs, and IDE-specific files (`.idea/`, `*.iml`, `.vscode/`) out of version control. |
| `README.md` | This document. |

Not yet present, expected in the next commit: `src/main/java/...` (application code), `src/main/resources/application.yml`, `src/main/resources/db/migration/` (Flyway SQL), `src/test/java/...` (tests), and a `docker-compose.yml` wiring together this service, Keycloak, and MySQL for local development.

---

## 6. Getting Started

### Prerequisites

- Java 21 (JDK) — only needed if building/running outside Docker.
- Maven 3.9+ (or use the Docker multi-stage build, which bundles Maven — no local Maven install required).
- Docker + Docker Compose.

### What you can run today

There is no `docker-compose.yml` in this commit yet (it ships with the Keycloak + MySQL wiring in the next stage). Right now you can build and run the bare container image on its own:

```bash
# Clone the repository
git clone https://github.com/JNikhilSakthi/identity-service.git
cd identity-service

# Build the image (multi-stage: compiles with Maven, ships a slim JRE runtime)
docker build -t identity-service:local .

# Run it (it will fail fast without a database/Keycloak since there's no
# application code or datasource configured yet — this simply proves the
# image builds and the JVM starts)
docker run --rm -p 8081:8081 identity-service:local
```

### Building the jar directly with Maven

```bash
mvn -B clean package
java -jar target/identity-service.jar
```

### Coming next

A `docker-compose.yml` that brings up:
1. `mysql:8` with a persisted volume and health check.
2. `quay.io/keycloak/keycloak` in dev mode with a pre-provisioned `enterprise-sso` realm import.
3. `identity-service` itself, wired to both via environment variables, with `depends_on` health-check gating.

so that `docker compose up` gives you a fully working local SSO environment in one command.

---

## 7. API Documentation

No REST controllers exist in this commit, so there are no live endpoints to document yet. Once the security config and controllers land, springdoc-openapi (already on the classpath) will auto-generate:

- OpenAPI 3 spec at `/v3/api-docs`
- Interactive Swagger UI at `/swagger-ui.html`

Planned endpoints for this service (to be implemented, listed here so the intended contract is clear rather than fabricated):

| Method | Path | Purpose | Auth |
|---|---|---|---|
| `GET` | `/api/users/me` | Return the authenticated user's profile, derived from JWT claims | Bearer JWT (any authenticated user) |
| `POST` | `/api/users/provision` | Create a user in Keycloak via the admin client (e.g. from an internal HR feed) | Bearer JWT (`ADMIN` role) |
| `GET` | `/api/users/{id}/roles` | List a user's realm/client roles | Bearer JWT (`ADMIN` role) |
| `GET` | `/actuator/health` | Liveness/readiness probe | Public (or restricted per environment) |

These will be documented with real request/response JSON once implemented — no sample payloads are included here to avoid documenting behavior that doesn't exist yet.

---

## 8. Testing

No test sources exist yet (`src/test/java` is not present in this commit). The POM already declares the intended testing stack, which dictates the testing strategy for the next stage:

- **`spring-boot-starter-test`**: unit tests and `@SpringBootTest` slice tests (JUnit 5, AssertJ, Mockito).
- **`spring-security-test`**: lets security-focused tests use `@WithMockUser` / `SecurityMockMvcRequestPostProcessors.jwt()` to simulate authenticated Keycloak tokens without standing up a real Keycloak server for every test.
- **`testcontainers` (`junit-jupiter`, `mysql`)** with the Testcontainers BOM `1.20.1`: integration tests will spin up a real, throwaway MySQL container so repository/Flyway-migration behavior is verified against the actual database engine rather than an in-memory substitute (e.g. H2), which is important because Flyway's `flyway-mysql` dialect handling and MySQL-specific SQL should be tested against MySQL itself.

Once tests exist, they'll be run the standard Maven way:

```bash
mvn test
```

---

## 9. Docker

### `Dockerfile` walkthrough

```dockerfile
# ---- Build stage ----
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests
```
- Uses an image that bundles Maven 3.9.9 with Eclipse Temurin JDK 21, so the container itself does the compiling — no dependency on the host machine's tooling.
- `COPY pom.xml .` followed by `dependency:go-offline` **before** `COPY src ./src` is a deliberate Docker layer-caching optimization: as long as `pom.xml` doesn't change, Docker reuses the cached dependency-download layer even when application source changes, making rebuilds much faster.
- `-DskipTests` in the image build keeps the container build focused on producing an artifact; tests are expected to run in CI, not inside the image build.

```dockerfile
# ---- Runtime stage ----
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app
RUN addgroup --system spring && adduser --system --ingroup spring spring
COPY --from=build /workspace/target/identity-service.jar app.jar
RUN chown spring:spring app.jar
USER spring
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
```
- Switches to a **JRE-only** base image (`eclipse-temurin:21-jre-jammy`) for runtime — no JDK, no Maven, no source — which minimizes final image size and removes build tooling from the attack surface.
- Creates a dedicated non-root `spring` system user/group and switches to it with `USER spring` before running the app — a standard container-hardening practice so a compromised app process doesn't run as root inside the container.
- Only the built jar is copied across stages (`COPY --from=build ...`), so none of the Maven cache, source tree, or build tooling from stage 1 ends up in the shipped image.
- `EXPOSE 8081` documents (rather than fabricates) the port this service listens on — matching the `-p 8081:8081` used in the run command above.
- `-XX:+UseContainerSupport` explicitly enables the JVM's container-aware memory/CPU detection (default-on in modern JDKs, but kept explicit here) so heap sizing respects the container's cgroup limits rather than the host's full resources.

### `.dockerignore`

Excludes `target/`, IDE metadata, `.git/`, `.gitignore`, markdown files, and the `Dockerfile`/`docker-compose.yml` themselves from the build context sent to the Docker daemon — keeping builds fast and avoiding accidentally baking documentation or VCS metadata into a layer.

### docker-compose

Not present yet in this commit. It will be added alongside the application source in the next stage, wiring Keycloak + MySQL + this service together for one-command local startup.

---

## 10. Interview Preparation

### Keycloak-specific FAQs

**Q: What's the difference between a realm, a client, and a role in Keycloak?**
A realm is an isolated tenant (its own users, roles, clients, login theme). A client is an application registered inside a realm (e.g. this Spring Boot service, or a frontend SPA) that requests tokens on behalf of users. A role is a permission label — either a *realm role* (global to the realm) or a *client role* (scoped to one client) — assigned to users or groups and surfaced inside the JWT's `realm_access.roles` / `resource_access.<client>.roles` claims.

**Q: How does a Spring Boot resource server validate a Keycloak-issued JWT without calling Keycloak on every request?**
`spring-boot-starter-oauth2-resource-server`, configured with `spring.security.oauth2.resourceserver.jwt.issuer-uri` pointing at the realm, fetches Keycloak's JWKS (JSON Web Key Set) once (and caches/refreshes it periodically), then validates each incoming JWT's signature locally against those public keys, plus checks standard claims (`exp`, `iss`). This makes token validation stateless and fast — no network round-trip to Keycloak per request.

**Q: Why use `keycloak-admin-client` instead of hand-rolling REST calls to Keycloak's Admin API?**
The admin client is a typed Java SDK over Keycloak's Admin REST API — it handles auth (service account token acquisition), retries, and gives typed representations (`UserRepresentation`, `RoleRepresentation`, etc.) instead of hand-built JSON, reducing the chance of drifting out of sync with the Admin API's actual contract across Keycloak versions.

**Q: Access token vs. refresh token vs. ID token — what's each for?**
Access token: presented to resource servers (like this service) to authorize API calls; short-lived. Refresh token: exchanged for a new access token without re-prompting login; longer-lived, must be stored/handled more carefully. ID token: OIDC-specific, carries identity claims about the authenticated user for the *client application* itself (not for calling APIs).

### Common mistakes

- **Trusting unsigned/unverified claims from the token without checking the signature and issuer** — always let the resource-server library do signature+issuer validation; never manually decode a JWT's payload and trust it without verification.
- **Storing Keycloak client secrets in `application.yml` committed to git** — these must come from environment variables or a secrets manager, never hardcoded.
- **Using `ddl-auto: update`/`create` against a production-shaped schema instead of Flyway migrations** — this project deliberately includes `flyway-core`/`flyway-mysql` precisely so schema changes are explicit, versioned SQL files instead of Hibernate's inferred DDL.
- **Conflating authentication (who you are — handled by Keycloak) with authorization (what you can do — enforced in this service via role/scope checks)** — a valid JWT proves identity, not permission; endpoint-level role checks are still required.
- **Long-lived access tokens** to avoid dealing with refresh flows — this widens the blast radius of a leaked token; short expiry + refresh tokens is the standard tradeoff.

### Production considerations

- Run Keycloak in production mode (not `start-dev`), behind TLS, with a production-grade database backing Keycloak itself (separate from this service's MySQL instance).
- Rotate signing keys periodically; the resource server's JWKS caching should respect `Cache-Control`/refresh intervals so key rotation doesn't cause a validation outage.
- Externalize all Keycloak/DB credentials via environment variables or a vault, never in version control — reinforced by the fact that no secrets are (or should be) present in `pom.xml`/`Dockerfile` here.
- Health-check and readiness-gate this service's startup on both MySQL and Keycloak being reachable, so it doesn't accept traffic before its dependencies are up (relevant once `docker-compose.yml`'s `depends_on: condition: service_healthy` is added).
- Keep the JRE-only runtime image (as this Dockerfile already does) and run as a non-root user (as this Dockerfile already does) — both reduce the container's attack surface.

### Performance notes

- JWT validation is local (signature check against cached JWKS), so this service's authorization overhead per request is CPU-bound (crypto verification) rather than network-bound — it does *not* call Keycloak per request, which is what makes the resource-server pattern scale horizontally without hammering the identity provider.
- `-XX:+UseContainerSupport` (already set in the `Dockerfile`) ensures the JVM sizes its heap based on the container's actual memory limit rather than the host's, which matters once this image runs under Kubernetes/Docker Compose resource limits.
- Flyway migrations run once at startup, not per-request, so their cost is a one-time startup latency cost, not a steady-state performance concern.

---

## License

MIT — see [LICENSE](LICENSE).
