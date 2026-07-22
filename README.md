# identity-service

**Enterprise SSO for a REST API, powered by Keycloak — the app never sees a password, only a JWT it can cryptographically verify.**

![Java](https://img.shields.io/badge/Java-25-orange)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.0.6-brightgreen)
![Keycloak](https://img.shields.io/badge/Keycloak-25.0.6-blue)
![Maven](https://img.shields.io/badge/Build-Maven-red)
![Docker](https://img.shields.io/badge/Container-Docker-2496ED)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

> **Learning Track:** `springboot-keycloak-sso-demo` (Project 4 of 17)
> **Real-World Service Name:** `identity-service`

---

## Table of Contents

1. [Project Overview](#1-project-overview)
2. [Architecture](#2-architecture)
3. [Tech Stack](#3-tech-stack)
4. [Configuration Explained](#4-configuration-explained)
5. [Project Structure Explained](#5-project-structure-explained)
6. [Getting Started](#6-getting-started)
7. [API Documentation](#7-api-documentation)
8. [Testing](#8-testing)
9. [Docker](#9-docker)
10. [Interview Preparation](#10-interview-preparation)
11. [License](#11-license)

---

## 1. Project Overview

### The problem

Every service that needs to know "who is calling, and what are they allowed to do" faces the same set of hard problems: securely storing passwords, issuing and rotating tokens, handling password resets and MFA, session/logout semantics, brute-force protection, and — the moment you have more than one service — making sure *all of them* trust the same identity without duplicating that logic everywhere.

`identity-service` demonstrates the standard enterprise answer: **don't build any of that**. Delegate identity entirely to **Keycloak**, an open-source Identity and Access Management (IAM) server, and make the Spring Boot application a pure **OAuth2 Resource Server** — a service whose only security responsibility is validating a signed token and checking the roles inside it.

### Why Keycloak instead of rolling your own auth

This roadmap already has a project dedicated to hand-rolled JWT auth (`springboot-jwt-auth-demo`, project 2) and one for social login (`springboot-oauth2-login-demo`, project 3). Project 4 exists to show the step *beyond* those: what a real enterprise reaches for once it needs more than "one app, one login form."

| Concern | Hand-rolled JWT | Keycloak |
|---|---|---|
| Password storage & hashing | You own the risk | Keycloak owns it |
| Login UI, MFA, password reset, brute-force lockout | You build it | Built in |
| Multiple apps trusting one identity (SSO) | You wire it yourself, per app | Native: one login, every app trusts the same realm |
| Social login, LDAP/Active Directory, SAML federation | Custom integration per provider | Configured, not coded |
| Central place to disable a compromised user across *every* app | Doesn't exist unless you build it | Disable once in Keycloak, every app is instantly locked out |
| Auditing who logged in where | Custom | Built-in admin console + events |

The trade-off: you now depend on an external IAM server and must get token validation (signature, expiry, issuer) exactly right. That subtlety — specifically the Docker networking issue where "the address a token was issued from" and "the address a container reaches Keycloak at" differ — is the central engineering lesson of this project (see [Interview Preparation](#10-interview-preparation)).

### Where this is used in real companies

This exact pattern (Spring Boot resource server + Keycloak realm) is common in:
- **Internal enterprise tools** (HR portals, admin dashboards, internal APIs) where a company already runs Keycloak/Active Directory/Okta as its single sign-on provider and every new microservice just plugs into it.
- **Multi-tenant B2B platforms**, using one Keycloak *realm per tenant* for isolation.
- **Microservice architectures**, where dozens of services all trust the same realm instead of each maintaining its own user table — exactly the direction this 17-project roadmap is building toward.

### The business domain

To keep the demo concrete, the protected resource is a **Document** — a stand-in for an internal knowledge-base article, HR policy, or onboarding doc. The interesting part isn't the CRUD; it's the authorization layered on top of it via Keycloak **realm roles**:

- **`USER`** — can create and read documents
- **`ADMIN`** — can create, read, update, and delete documents

---

## 2. Architecture

### High-Level Design (HLD)

```
                        ┌─────────────────────────────┐
                        │           Client            │
                        │   (curl / Postman / SPA)     │
                        └───────────────┬──────────────┘
                                        │
                    (1) POST /realms/identity-realm/protocol/openid-connect/token
                        username + password + client_id + client_secret
                                        │
                                        ▼
                        ┌─────────────────────────────┐
                        │           Keycloak           │
                        │   realm: identity-realm      │
                        │   - client: identity-service │
                        │   - roles: USER, ADMIN        │
                        │   - users: admin-user,        │
                        │            regular-user       │
                        └───────────────┬──────────────┘
                                        │
                       (2) signed JWT access_token
                           (iss, sub, preferred_username,
                            realm_access.roles, exp, ...)
                                        │
                                        ▼
                        ┌─────────────────────────────┐
                        │           Client              │
                        └───────────────┬──────────────┘
                                        │
                (3) GET/POST/PUT/DELETE /api/v1/documents
                     Authorization: Bearer <access_token>
                                        │
                                        ▼
                        ┌─────────────────────────────┐
                        │       identity-service        │
                        │  (OAuth2 Resource Server)      │
                        │                                 │
                        │  - Verify JWT signature via     │
                        │    Keycloak's public JWK set    │
                        │  - Verify `iss` (issuer) claim   │
                        │  - Verify `exp` (not expired)     │
                        │  - Map realm_access.roles ->      │
                        │    ROLE_USER / ROLE_ADMIN          │
                        │  - Enforce @PreAuthorize per        │
                        │    endpoint                          │
                        └───────────────┬──────────────────────┘
                                        │
                                        ▼
                             ┌───────────────────┐
                             │   H2 (in-memory)   │
                             │   documents table   │
                             └───────────────────┘
```

**Critical property:** identity-service *never* talks to Keycloak to check "is this password right" — it never even sees a password. It only ever fetches Keycloak's **public keys** (JWKs) once, and then verifies token signatures locally, offline, for every request. This is what makes JWT-based SSO horizontally scalable: any number of resource servers can validate tokens without a network round-trip to the identity provider per request.

### Low-Level Design (LLD) — request flow inside the app

```
HTTP Request
    │
    ▼
Spring Security Filter Chain (SecurityConfig)
    │
    ├─ BearerTokenAuthenticationFilter
    │     extracts "Authorization: Bearer <token>"
    │
    ├─ JwtDecoder (hand-built NimbusJwtDecoder bean)
    │     - fetches signing keys from jwk-set-uri (Docker-internal address)
    │     - verifies signature
    │     - verifies `iss` claim against issuer-uri (external address)
    │     - verifies `exp` / `nbf`
    │     → throws JwtException -> RestAuthenticationEntryPoint -> 401 JSON
    │
    ├─ KeycloakRealmRoleConverter
    │     reads jwt.claim("realm_access").roles
    │     maps ["ADMIN","USER"] -> [ROLE_ADMIN, ROLE_USER] GrantedAuthority
    │
    ├─ Method Security (@PreAuthorize on each controller method)
    │     hasRole('ADMIN') / hasAnyRole('USER','ADMIN')
    │     → throws AccessDeniedException -> RestAccessDeniedHandler -> 403 JSON
    │
    ▼
DocumentController
    │
    ▼
DocumentService (business logic, @Transactional)
    │
    ▼
DocumentRepository (Spring Data JPA)
    │
    ▼
H2 in-memory database
```

### Folder structure

```
identity-service/
├── pom.xml
├── Dockerfile
├── docker-compose.yml
├── keycloak/
│   └── realm-import.json          # realm + client + roles + test users, auto-provisioned
├── src/
│   ├── main/
│   │   ├── java/com/medha/identityservice/
│   │   │   ├── IdentityServiceApplication.java
│   │   │   ├── config/
│   │   │   │   ├── SecurityConfig.java        # resource server + JwtDecoder + method security
│   │   │   │   └── OpenApiConfig.java         # Swagger UI Bearer auth
│   │   │   ├── controller/
│   │   │   │   ├── DocumentController.java    # CRUD, role-gated
│   │   │   │   └── UserInfoController.java    # /me whoami endpoint
│   │   │   ├── dto/
│   │   │   │   ├── DocumentRequest.java
│   │   │   │   ├── DocumentResponse.java
│   │   │   │   ├── ErrorResponse.java
│   │   │   │   └── UserInfoResponse.java
│   │   │   ├── entity/
│   │   │   │   └── Document.java
│   │   │   ├── exception/
│   │   │   │   ├── GlobalExceptionHandler.java
│   │   │   │   └── ResourceNotFoundException.java
│   │   │   ├── repository/
│   │   │   │   └── DocumentRepository.java
│   │   │   ├── security/
│   │   │   │   ├── KeycloakRealmRoleConverter.java
│   │   │   │   ├── RestAuthenticationEntryPoint.java   # 401 JSON body
│   │   │   │   └── RestAccessDeniedHandler.java        # 403 JSON body
│   │   │   └── service/
│   │   │       ├── DocumentService.java
│   │   │       └── DocumentServiceImpl.java
│   │   └── resources/
│   │       └── application.yml
│   └── test/
│       └── java/com/medha/identityservice/
│           ├── IdentityServiceApplicationTests.java
│           ├── controller/DocumentControllerSecurityTest.java
│           └── service/DocumentServiceImplTest.java
```

### Request flow: token acquisition + validation, end to end

1. **Provisioning (startup, once):** `docker compose up` starts Keycloak with `--import-realm`. Keycloak reads `keycloak/realm-import.json` and creates the `identity-realm` realm, the `identity-service-client` client, the `USER`/`ADMIN` realm roles, and two users (`admin-user`, `regular-user`) — fully automatically, no console clicking.
2. **Token acquisition:** A client (curl, Postman, a future SPA) POSTs credentials to Keycloak's token endpoint (`/realms/identity-realm/protocol/openid-connect/token`) using the [Resource Owner Password Credentials grant](#10-interview-preparation) and receives a signed JWT `access_token`.
3. **Calling the API:** The client sends that token as `Authorization: Bearer <token>` to `identity-service`.
4. **Validation:** identity-service's resource-server filter chain verifies the token's signature (against Keycloak's public JWKs), issuer, and expiry — all **without contacting Keycloak per request** (the JWKs are cached after the first fetch).
5. **Authorization:** the realm roles embedded in the token (`realm_access.roles`) are mapped to Spring Security authorities and checked against each endpoint's `@PreAuthorize` rule.
6. **Business logic:** if authorized, the request reaches `DocumentController` → `DocumentService` → `DocumentRepository` → H2.

---

## 3. Tech Stack

| Component | Choice | Why |
|---|---|---|
| Language | Java 25 | Current release, migrated from Java 21 |
| Framework | Spring Boot 4.0.6 | Parent for all starters/versions (Spring Framework 7 / Spring Security 7 underneath) |
| Identity Provider | Keycloak 25.0.6 (`quay.io/keycloak/keycloak`) | Free, open-source, industry-standard OIDC/SAML IAM server |
| Security | `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server` | Resource-server-only; no login form, no session, no password ever touches this app |
| Persistence | `spring-boot-starter-data-jpa` + H2 (in-memory) | Minimal on purpose — MySQL/JPA fundamentals already covered in project 1 (`springboot-jpa-crud-demo`); this project isolates Keycloak |
| Validation | `spring-boot-starter-validation` (Jakarta Bean Validation) | `@NotBlank`/`@Size` on request DTOs |
| API docs | springdoc-openapi 3.0.3 (Swagger UI) | Interactive docs with Bearer-token "Try it out"; the 2.8.x line still targets Spring Security 6/Spring Boot 3, so 3.x is required here |
| JSON | Jackson 3 (`tools.jackson.databind.ObjectMapper`) | Spring Boot 4's default JSON engine; the hand-built `RestAccessDeniedHandler`/`RestAuthenticationEntryPoint` error envelopes use it directly |
| Boilerplate reduction | Lombok 1.18.44 | `@Data`/`@Builder` on the entity; pinned explicitly because JDK 25 needs Lombok >= 1.18.42 for annotation processing to work at all, and the Maven Compiler Plugin needs an explicit `annotationProcessorPaths` entry too (javac's implicit classpath-based processor discovery silently no-ops on this JDK) |
| Observability | `spring-boot-starter-actuator` | `/actuator/health` |
| Testing | JUnit 5, Mockito, `spring-boot-starter-webmvc-test`, `spring-boot-starter-security-test` | Unit tests for the service layer, `@WebMvcTest` + `SecurityMockMvcRequestPostProcessors.jwt()` for the security matrix (Spring Boot 4 split `@WebMvcTest` and the Security test auto-configuration out of the generic `spring-boot-starter-test` into their own per-technology test starters) |
| Build | Maven | Multi-module-free single build |
| Containers | Docker (`maven:3.9.16-eclipse-temurin-25` / `eclipse-temurin:25-jre-jammy`) + Docker Compose | Keycloak + app, zero manual setup |

---

## 4. Configuration Explained

`src/main/resources/application.yml`:

```yaml
server:
  port: 8081
```
The app listens on 8081 (Keycloak takes the conventional 8080).

```yaml
spring:
  application:
    name: identity-service

  datasource:
    url: jdbc:h2:mem:identitydb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
```
An in-memory H2 database. `DB_CLOSE_DELAY=-1` keeps the database alive for the lifetime of the JVM (otherwise H2 destroys it the moment the last connection closes, which happens between requests with a connection pool).

```yaml
  h2:
    console:
      enabled: true
      path: /h2-console
```
Enables H2's web console at `/h2-console` for inspecting the in-memory `documents` table during local development (JDBC URL: `jdbc:h2:mem:identitydb`, user `sa`, empty password).

```yaml
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
    properties:
      hibernate:
        format_sql: true
    show-sql: false
```
`ddl-auto: update` lets Hibernate create/update the schema from the `@Entity` — fine for a demo, never for production (see [Interview Preparation](#10-interview-preparation)). `open-in-view: false` disables the Open Session In View anti-pattern so lazy-loading issues surface at the service boundary, not silently in the view layer.

```yaml
security:
  jwt:
    issuer-uri: ${KEYCLOAK_ISSUER_URI:http://localhost:8080/realms/identity-realm}
    jwk-set-uri: ${KEYCLOAK_JWK_SET_URI:http://localhost:8080/realms/identity-realm/protocol/openid-connect/certs}
```
These are **custom** properties (not Spring Boot's built-in `spring.security.oauth2.resourceserver.jwt.issuer-uri`), consumed directly by `SecurityConfig`'s hand-built `JwtDecoder` bean. Two separate values exist because of the classic Docker-networking issue explained in depth in [Section 10](#10-interview-preparation):

- **`issuer-uri`** — the address a client used to *obtain* the token from Keycloak (embedded inside the token's `iss` claim). Validated against, not fetched from.
- **`jwk-set-uri`** — the address this app uses to *fetch Keycloak's public signing keys*. Inside Docker Compose this is the internal service name `keycloak`, not `localhost`.

Both default to `localhost:8080` for running the app directly on the host (outside Docker); `docker-compose.yml` overrides them via environment variables so the containerized app can actually reach Keycloak.

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
  endpoint:
    health:
      show-details: when-authorized
```
Exposes only `/actuator/health` and `/actuator/info` — deliberately not `/actuator/env` or `/actuator/beans`, which would leak configuration/secrets in a real deployment.

```yaml
springdoc:
  swagger-ui:
    path: /swagger-ui.html
  api-docs:
    path: /v3/api-docs
```
Standard springdoc paths; both are explicitly permitted in `SecurityConfig` so the docs themselves don't require a token to *view* (calling the actual endpoints from "Try it out" still does).

```yaml
logging:
  level:
    org.springframework.security: INFO
    com.medha.identityservice: DEBUG
```
`org.springframework.security: DEBUG` is invaluable while debugging token validation (uncomment locally to see exactly why a JWT was rejected) — kept at INFO by default to avoid leaking token contents into logs continuously.

---

## 5. Project Structure Explained

| Path | Why it exists |
|---|---|
| `IdentityServiceApplication.java` | Standard Spring Boot entry point. Javadoc explicitly calls out that this app delegates *all* authentication to Keycloak. |
| `config/SecurityConfig.java` | The heart of the project. Builds the `JwtDecoder` by hand (issuer/JWK split), wires the custom role converter, registers custom 401/403 handlers, and defines the permit-all allow-list (`/actuator/health`, Swagger). |
| `config/OpenApiConfig.java` | Adds a Bearer-token security scheme to the generated OpenAPI spec so Swagger UI's "Authorize" button works. |
| `controller/DocumentController.java` | The protected CRUD API. Each method carries its own `@PreAuthorize`, making the authorization rule visible right next to the endpoint it protects. |
| `controller/UserInfoController.java` | A `/me` "whoami" endpoint that echoes back exactly the claims Keycloak put in the caller's token — makes the "no local user table" property tangible. |
| `dto/DocumentRequest.java` | Inbound payload, carries Bean Validation annotations. Kept separate from the entity so persistence concerns don't leak into the wire contract. |
| `dto/DocumentResponse.java` | Outbound representation; a static `from(Document)` factory keeps the mapping in one obvious place. |
| `dto/ErrorResponse.java` | One JSON shape for every error in the app — 400, 401, 403, 404, 500 all look the same to a client. |
| `dto/UserInfoResponse.java` | Response shape for `/me`. |
| `entity/Document.java` | The JPA entity. Lombok `@Data`/`@Builder` remove getter/setter/builder boilerplate; `@PrePersist`/`@PreUpdate` manage timestamps automatically. |
| `exception/GlobalExceptionHandler.java` | `@RestControllerAdvice` for everything that reaches the servlet layer: validation errors (400), not-found (404), method-security denials (403), and a catch-all (500). Its Javadoc explains *why* 401 is deliberately **not** handled here (see next row). |
| `exception/ResourceNotFoundException.java` | Thrown by the service layer when an id doesn't exist. |
| `repository/DocumentRepository.java` | A plain `JpaRepository<Document, Long>` — no custom queries needed for this demo's scope. |
| `security/KeycloakRealmRoleConverter.java` | Translates Keycloak's `realm_access.roles` claim into `ROLE_*` Spring Security authorities. This is the single most important adapter class in the project — without it, `hasRole("ADMIN")` would never match anything, because Keycloak doesn't shape its claims the way Spring Security expects by default. |
| `security/RestAuthenticationEntryPoint.java` | Fires when a request has no/invalid/expired token. Registered directly on the filter chain (not `@ExceptionHandler`) because token validation happens *before* the DispatcherServlet. |
| `security/RestAccessDeniedHandler.java` | Fires when a *valid* token lacks the required role. Same filter-chain-level reasoning as above. |
| `service/DocumentService.java` / `DocumentServiceImpl.java` | Interface + implementation split so the controller and its tests depend on an abstraction, not Hibernate specifics. `@Transactional` at the class level, `readOnly = true` on the two read methods. |
| `keycloak/realm-import.json` | The entire Keycloak realm as data: roles, client, and two test users. Keycloak imports this file automatically on first boot — this is what makes the whole stack reproducible with zero manual console clicks. |
| `docker-compose.yml` | Runs Keycloak (with the realm auto-imported) and the app together, wired with the issuer/JWK split described above. |
| `Dockerfile` | Multi-stage build: compiles with Maven in a throwaway build stage, ships only the runtime JRE + jar in the final image, runs as a non-root user. |

---

## 6. Getting Started

### Prerequisites

- Docker + Docker Compose
- (Optional, for local dev outside Docker) JDK 25 and Maven

### 1. Start everything

```bash
git clone https://github.com/JNikhilSakthi/identity-service.git
cd identity-service
docker compose up -d --build
```

This starts two containers:
- **`identity-service-keycloak`** on `localhost:8080` — boots in dev mode and, thanks to `--import-realm` plus the mounted `keycloak/realm-import.json`, automatically creates:
  - realm `identity-realm`
  - realm roles `USER` and `ADMIN`
  - client `identity-service-client` (confidential, secret `identity-service-secret`, direct-access-grants enabled so curl can request tokens directly)
  - user `admin-user` / password `admin123` — roles `ADMIN`, `USER`
  - user `regular-user` / password `user123` — role `USER`
- **`identity-service-app`** on `localhost:8081` — the Spring Boot API

Keycloak's dev-mode startup + realm import takes roughly 15-25 seconds. Confirm it's ready:

```bash
curl -s http://localhost:8080/realms/identity-realm/.well-known/openid-configuration | head -c 200
```
A JSON response (not a connection error) means the realm is live.

### 2. Get a test token

```bash
curl -s -X POST http://localhost:8080/realms/identity-realm/protocol/openid-connect/token \
  -d "client_id=identity-service-client" \
  -d "client_secret=identity-service-secret" \
  -d "grant_type=password" \
  -d "username=admin-user" \
  -d "password=admin123" | python3 -m json.tool
```

Copy the `access_token` value from the response — that's your bearer token.

```bash
export TOKEN="<paste access_token here>"
```

### 3. Call the protected API

```bash
curl -s http://localhost:8081/api/v1/documents \
  -H "Authorization: Bearer $TOKEN"
```

### 4. Explore interactively

Swagger UI: [http://localhost:8081/swagger-ui.html](http://localhost:8081/swagger-ui.html) — click **Authorize** and paste a raw access token to try every endpoint from the browser.

Keycloak admin console: [http://localhost:8080](http://localhost:8080) — login `admin` / `admin` (this is Keycloak's *own* bootstrap admin, unrelated to the `identity-realm` test users above).

### 5. Shut down

```bash
docker compose down
```

---

## 7. API Documentation

Base URL: `http://localhost:8081`

All endpoints below (except the two health/docs ones) require `Authorization: Bearer <access_token>`.

| Method | Path | Required role | Description |
|---|---|---|---|
| POST | `/api/v1/documents` | `USER` or `ADMIN` | Create a document |
| GET | `/api/v1/documents` | `USER` or `ADMIN` | List all documents |
| GET | `/api/v1/documents/{id}` | `USER` or `ADMIN` | Get one document |
| PUT | `/api/v1/documents/{id}` | `ADMIN` | Update a document |
| DELETE | `/api/v1/documents/{id}` | `ADMIN` | Delete a document |
| GET | `/api/v1/users/me` | any authenticated user | Return the caller's identity claims from their JWT |
| GET | `/actuator/health` | none | Liveness probe |
| GET | `/swagger-ui.html` | none (calling endpoints from it still needs a token) | Interactive API docs |

### Create a document (USER or ADMIN)

```bash
curl -s -X POST http://localhost:8081/api/v1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Onboarding Guide","content":"Welcome to the company"}'
```
```json
{
  "id": 1,
  "title": "Onboarding Guide",
  "content": "Welcome to the company",
  "ownerUsername": "admin-user",
  "createdAt": "2026-07-22T16:52:47.16Z",
  "updatedAt": "2026-07-22T16:52:47.16Z"
}
```

### List documents

```bash
curl -s http://localhost:8081/api/v1/documents -H "Authorization: Bearer $TOKEN"
```

### Update a document (ADMIN only)

```bash
curl -s -X PUT http://localhost:8081/api/v1/documents/1 \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"title":"Onboarding Guide v2","content":"Updated content"}'
```
Calling this with a `regular-user` (USER-only) token instead returns:
```json
{
  "timestamp": "2026-07-22T16:52:47.27Z",
  "status": 403,
  "error": "Forbidden",
  "message": "Your token is valid but lacks the realm role required for this operation",
  "path": "/api/v1/documents/1",
  "details": []
}
```

### Delete a document (ADMIN only)

```bash
curl -s -X DELETE http://localhost:8081/api/v1/documents/1 -H "Authorization: Bearer $ADMIN_TOKEN"
# HTTP 204 No Content
```

### Whoami

```bash
curl -s http://localhost:8081/api/v1/users/me -H "Authorization: Bearer $TOKEN"
```
```json
{
  "subject": "9457f6dc-da64-4057-ae68-077dd7652d1a",
  "preferredUsername": "regular-user",
  "email": "regular-user@identity-service.local",
  "realmRoles": ["USER"]
}
```

### No token

```bash
curl -s http://localhost:8081/api/v1/documents
```
```json
{
  "timestamp": "2026-07-22T16:52:46.98Z",
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing, expired, or invalid Keycloak access token: Full authentication is required to access this resource",
  "path": "/api/v1/documents",
  "details": []
}
```

---

## 8. Testing

Run the full suite:

```bash
mvn test
```

16 tests, all passing, in three classes:

- **`DocumentServiceImplTest`** (7 tests) — pure Mockito unit tests of the service layer: create persists with the correct owner, `findAll` maps every entity, `findById`/`update`/`delete` all throw `ResourceNotFoundException` for a missing id and never touch the repository's write methods when they do, `update` applies new field values correctly.
- **`DocumentControllerSecurityTest`** (8 tests) — the security-focused integration test this project is really about, using `@WebMvcTest` + `SecurityMockMvcRequestPostProcessors.jwt()` to fabricate pre-validated JWTs with specific authorities (no real Keycloak needed to run this test):
  - anonymous request → 401
  - `ROLE_USER` can list and create → 200/201
  - `ROLE_USER` attempting update/delete → 403
  - `ROLE_ADMIN` can update and delete → 200/204
  - invalid payload (blank title/content) → 400 with field-level error details
- **`IdentityServiceApplicationTests`** (1 test) — a full `@SpringBootTest` context-load smoke test, proving the entire app (H2 datasource, JPA, the hand-built `JwtDecoder` bean, method security) wires up cleanly with **no Keycloak instance running** — possible because `NimbusJwtDecoder` builds a lazy JWK client that only reaches out on first token validation, never at startup.

This whole matrix was also re-verified against a **live** `docker compose up` stack (real Keycloak, real tokens, real HTTP calls) while building this project — see the request/response examples in [Section 7](#7-api-documentation), which are taken directly from that run.

---

## 9. Docker

### `Dockerfile` (multi-stage)

```dockerfile
# ---- Build stage ----
FROM maven:3.9.16-eclipse-temurin-25 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage ----
FROM eclipse-temurin:25-jre-jammy AS runtime
WORKDIR /app
RUN addgroup --system spring && adduser --system --ingroup spring spring
COPY --from=build /workspace/target/identity-service.jar app.jar
RUN chown spring:spring app.jar
USER spring
EXPOSE 8081
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-jar", "/app/app.jar"]
```
- **Build stage**: uses the full Maven+JDK image, copies `pom.xml` first and runs `dependency:go-offline` before copying source, so Docker's layer cache keeps dependencies cached across rebuilds that only change Java code.
- **Runtime stage**: uses a slim JRE-only base image (no compiler, no Maven — smaller attack surface and smaller image), copies only the built jar, and runs as a dedicated non-root `spring` user.

### `docker-compose.yml`

Two services on a shared bridge network (`identity-net`):

- **`keycloak`** — `quay.io/keycloak/keycloak:25.0.6`, started with `start-dev --import-realm`. `KC_HOSTNAME: localhost` pins the `iss` claim of every issued token to `http://localhost:8080/...`, matching what a host-side curl/Postman/browser actually used to reach it. The realm-import file is bind-mounted read-only into Keycloak's auto-import directory (`/opt/keycloak/data/import/`).
- **`identity-service`** — built from the `Dockerfile` above, given two environment variables that solve the issuer-vs-JWKs Docker networking split explained in [Section 10](#10-interview-preparation):
  - `KEYCLOAK_ISSUER_URI=http://localhost:8080/realms/identity-realm` (validate against — matches tokens' `iss`)
  - `KEYCLOAK_JWK_SET_URI=http://keycloak:8080/realms/identity-realm/protocol/openid-connect/certs` (fetch keys from — Docker-internal DNS name)

### `keycloak/realm-import.json`

A complete, versioned description of the realm: roles, one confidential client with direct-access-grants enabled (so this README's curl examples work without a browser redirect flow), and two seeded users with passwords set as permanent (`"temporary": false`) so first login doesn't force a password reset. Because this file is checked into source control, the entire identity configuration is reproducible and code-reviewable — the same principle as "infrastructure as code," applied to IAM.

---

## 10. Interview Preparation

**Q: What's the difference between authentication and authorization, and where does each happen in this project?**
Authentication ("who are you") happens entirely inside Keycloak when the user submits credentials to the token endpoint. Authorization ("what can you do") happens partly in Keycloak (which roles get assigned to which user) and partly in this app (which roles each endpoint requires, via `@PreAuthorize`). The app never authenticates anyone — it only authorizes, based on a token it trusts because Keycloak signed it.

**Q: How does the resource server actually verify a JWT without calling Keycloak on every request?**
Keycloak publishes its public signing keys at a well-known JWK Set endpoint (`/realms/{realm}/protocol/openid-connect/certs`). The resource server fetches and caches that key set (via `NimbusJwtDecoder`), then verifies each token's signature locally using ordinary asymmetric cryptography (RS256 by default in Keycloak). It also checks the `exp` (expiry) and `iss` (issuer) claims locally. No network call to Keycloak is needed per API request — only when the key set needs refreshing (e.g. after key rotation).

**Q: What is a realm role vs a client role in Keycloak?**
A **realm role** (e.g. `ADMIN`, `USER` in this project) is global to the whole realm — any client/application in that realm can check it. A **client role** is scoped to one specific client/application (e.g. an `identity-service-client`-only role that wouldn't mean anything to a different app in the same realm). This project deliberately uses realm roles because the roadmap frames Keycloak as *enterprise-wide* SSO — one identity, meaningful across every future service, not siloed per app.

**Q: Why does this project build a custom `JwtDecoder` bean instead of just setting `spring.security.oauth2.resourceserver.jwt.issuer-uri`?**
Because Spring Boot's single-property auto-configuration assumes the issuer URI is reachable *and* is where the JWKs live. That's false in this Docker Compose setup: Keycloak's issuer, embedded in every token, is `http://localhost:8080/...` (what the human/curl used from the host), but `identity-service`'s own container can't reach `localhost:8080` and land on Keycloak — `localhost` inside a container means *that container itself*. The fix: fetch keys from the Docker-internal address (`http://keycloak:8080/...`) while still validating the `iss` claim against the external one. This is one of the most common real-world Keycloak-in-Docker mistakes, and the reason for `SecurityConfig`'s hand-built `JwtDecoder`.

**Q: What happens to a token after it expires? What's a refresh token for?**
The `access_token` (300 seconds / 5 minutes in this realm's config) is short-lived by design — if it leaks, the exposure window is small. Once expired, the resource server rejects it with 401, and the client must use its **refresh token** (issued alongside the access token, much longer-lived) to obtain a new access token from Keycloak's token endpoint without asking the user to log in again. This project's curl examples don't show refresh-token exchange for brevity, but production clients (SPAs, mobile apps) always implement it.

**Q: Why does this API use the Resource Owner Password Credentials (ROPC) grant in the README examples? Isn't that deprecated?**
Yes — ROPC (`grant_type=password`) requires the client to handle the user's raw password directly, which defeats much of the point of SSO, and OAuth 2.1 removes it entirely. It's used here purely as a **local testing convenience** — it lets this README's curl examples fetch a token in one command without standing up a browser-based login flow. A real frontend would use the **Authorization Code flow with PKCE** instead, where the user is redirected to Keycloak's own hosted login page and never enters credentials into the client application at all.

**Q: What's the single most common mistake when hooking Spring Boot up to Keycloak?**
Exactly the issuer/JWKs split covered above — configuring a single `issuer-uri` that works when testing against localhost from a laptop, then having everything break the moment the app is containerized because the container can't resolve `localhost` back to the Keycloak container. A close second: forgetting that Keycloak's roles live under the *nested* `realm_access.roles` claim rather than the flat `scope` claim Spring Security's `JwtAuthenticationConverter` expects by default — without a custom converter (`KeycloakRealmRoleConverter` here), every `@PreAuthorize("hasRole(...))")` check silently fails, and everyone gets 403 forever.

**Q: How would you run Keycloak in production, differently from this demo?**
This demo uses `start-dev`, an in-memory H2-backed Keycloak instance meant only for local development — it loses all data on restart and is explicitly unsupported for production. Production Keycloak requires: `kc.sh start` (production mode) backed by a real database (Postgres/MySQL) for persistent realm/user storage; running multiple Keycloak nodes behind a load balancer for HA, with either a distributed cache (Infinispan clustering) or a shared external cache for session replication; TLS termination (Keycloak refuses non-HTTPS traffic outside dev mode by default); and a proper `KC_HOSTNAME` matching the real public domain so issued tokens' `iss` claims are stable across every node.

**Q: Why H2 instead of MySQL/Postgres here, given the roadmap's "MySQL where relevant" constraint?**
Project 1 (`springboot-jpa-crud-demo` / `employee-service`) already dedicates itself to MySQL + JPA fundamentals. This project's whole purpose is isolating Keycloak/SSO as a single skill — adding a second, unrelated persistence concern here would dilute that focus and duplicate project 1's learning goal. H2 in-memory exists purely so the `Document` CRUD resource has somewhere to live.

**Q: What does `ddl-auto: update` mean, and why is it wrong for production?**
It tells Hibernate to inspect the entity classes at startup and alter the database schema to match — convenient for a demo with a from-scratch H2 database, but dangerous in production: Hibernate's auto-migration can silently drop columns, guess wrong data types, or lock a large table during a rolling deploy. Production systems use versioned, reviewed migration tools (Flyway/Liquibase) with `ddl-auto: validate` or `none` so schema changes are explicit and auditable.

---

## 11. License

MIT — see [LICENSE](./LICENSE).
