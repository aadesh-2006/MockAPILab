# MockAPILab — Architecture & Design Decisions

This document outlines the architectural principles, technology choices, and component responsibilities for **MockAPILab**.

---

## 1. Architectural Philosophy: Modular Monolith

MockAPILab is intentionally structured as a **Modular Monolith** rather than a distributed set of microservices.

### Why a Modular Monolith?
- **Domain Cohesion & Velocity:** Domain boundaries (identity, projects, contracts, state machines, scenarios, runtime dispatch) benefit from compile-time type safety, shared memory calls, and single-deployment coordination.
- **Operational Simplicity:** Avoids the overhead of service meshes, distributed tracing, network latency between internal components, and multi-service deployment synchronization.
- **Strict Boundary Enforcement:** Modules communicate via clean interfaces/DTOs within `com.mockapilab.modules.*`. When a module warrants independent scaling in the future, its clear package boundaries make extraction straightforward.

```
com.mockapilab
+-- common/             # Cross-cutting: web exceptions, API envelopes, CORS, OpenAPI configuration
+-- modules/
    +-- auth/           # Identity, JWT issuance, password hashing, UserPrincipal (M2)
    +-- project/        # Workspace boundaries & owner isolation (M2)
    +-- contract/       # OpenAPI/Swagger parser, Normalized Contract Model, JSONB persistence (M3)
    +-- runtime/        # Stateful Mock Runtime Engine: route compilation, validation, isolated state store (M4)
    +-- scenario/       # Stateful workflows, sequences, and rule conditions (Future)
    +-- ai/             # Gemini-assisted schema ingestion & contract extraction (M5)
```

---

## 2. Technology Stack & Component Responsibilities

```mermaid
flowchart TD
    subgraph Client ["Client Layer"]
        UI["React + TypeScript Dashboard"]
        DevApp["Frontend App Under Development"]
    end

    subgraph Backend ["MockAPILab Backend (Spring Boot 3 - Java 21)"]
        API["REST & Admin Management API"]
        AuthModule["Auth & Security (JWT)"]
        ProjectModule["Project Workspace Engine"]
        ContractModule["Contract Engine (Parser + Normalizer)"]
        RuntimeEngine["Stateful Mock Runtime Engine\n(/mock/{runtimeId}/**)"]
        StateStore["Thread-Safe RuntimeStateStore"]
        AILayer["Contract Extractor (Gemini) (Future)"]
    end

    subgraph Data ["Data Layer"]
        PG[("PostgreSQL 16\n(Users, Projects, Contracts JSONB, Runtimes)")]
    end

    UI -->|Authenticate, Manage Projects, Ingest Contracts, Start Runtimes| API
    DevApp -->|Execute Public Mock Requests| RuntimeEngine
    API --> AuthModule
    API --> ProjectModule
    API --> ContractModule
    API --> RuntimeEngine
    ContractModule --> PG
    ProjectModule --> PG
    AuthModule --> PG
    RuntimeEngine --> PG
    RuntimeEngine --> StateStore
    AILayer -->|Infers Schemas| ContractModule
```

---

## 3. Normalized Contract Architecture (Milestone 3 Core)

### 3.1 The Canonical Normalized Model Principle
The central architectural insight of MockAPILab is that **downstream systems (mock runtime, dynamic data generator, scenario engine, contract diffing) must NOT depend on third-party OpenAPI or Swagger object models.**

All incoming API specifications are transformed into a unified **`NormalizedContract`**:

```
OpenAPI 3.x (JSON/YAML) --+
                          ¦
Spring/Express AST + AI --+--> [OpenApi / AST Parser] --> NormalizedContract --> JSONB Storage
                          ¦                                        ¦
Natural Language Specs ---+                                        +--> Dynamic Mock Engine (M4)
                                                                   +--> Stateful Scenarios (Future)
                                                                   +--> Contract Diffing (Future)
```

### 3.2 Normalized Contract Structure
The normalized model (`com.mockapilab.modules.contract.model.normalized`) encapsulates:
- **`ContractMetadata`:** Title, description, version, canonical format version.
- **`NormalizedEndpoint`:** Path, HTTP method, summary, description, operationId, normalized parameters, request body, and response definitions.
- **`NormalizedParameter`:** Location (`PATH`, `QUERY`, `HEADER`, `COOKIE`), name, required flag, description, and schema.
- **`NormalizedRequestBody` & `NormalizedResponse`:** Status codes, descriptions, media type mappings (e.g. `application/json`), header specifications.
- **`NormalizedSchema`:** Type (`string`, `integer`, `number`, `boolean`, `array`, `object`), format (`uuid`, `email`, `date-time`, etc.), properties, required properties, array items, enum constants, nullable flags, examples, and component references (`$ref`).

---

## 4. Stateful Mock Runtime Engine (Milestone 4 Core)

### 4.1 In-Process Dynamic Dispatch Architecture
Rather than provisioning separate OS processes or Docker containers per mock, MockAPILab hosts dynamic mock backends in-process:
- **Public Gateway:** Intercepts `/mock/{runtimeId}/**` without requiring JWT tokens.
- **Route Compilation:** `RouteCompiler` compiles `NormalizedContract` paths (e.g. `/pets/{petId}`) into prioritized regex matchers with specificity weighting (exact paths prioritized over parameter variables).
- **Request Validation:** `MockRequestValidator` validates incoming JSON payloads against schema rules (required fields, primitive type checks, enum constraints) returning `400 Bad Request` with structured error details upon violation.
- **Stateful REST Semantics:**
  - `POST /collection`: Generates/preserves ID, inserts entity into runtime collection state, returns `201 Created`.
  - `GET /collection`: Returns list of stored entities (with automatic schema-driven initial mock seeding if empty).
  - `GET /collection/{id}`: Looks up entity by ID, returns `200 OK` or `404 Not Found`.
  - `PUT/PATCH /collection/{id}`: Merges/updates stored entity, returns `200 OK` or `404 Not Found`.
  - `DELETE /collection/{id}`: Removes entity, returns `204 No Content` / `200 OK`; subsequent `GET` returns `404`.
  - `Generic / RPC endpoints`: Returns deterministic mock response conforming to `NormalizedResponse`.

### 4.2 State Storage & Isolation
- `RuntimeStateStore` abstracts state storage per `runtimeId` and collection path.
- `InMemoryRuntimeStateStore` provides thread-safe partitioned storage using `ConcurrentHashMap` and synchronized `LinkedHashMap` to preserve insertion order.
- Each runtime instance is strictly isolated: mutations in Runtime A do not affect Runtime B.

---

## 5. Architectural Decision Records (ADRs)

### 5.1 Stateless JWT Authentication (ADR-001)
- **Decision:** Stateless HMAC-SHA256 tokens for identity and workspace authorization.

### 5.2 Strong Password Hashing (ADR-002)
- **Decision:** BCrypt with salting (`BCryptPasswordEncoder`).

### 5.3 Strict DTO Boundaries (ADR-003)
- **Decision:** All controller APIs expose and consume Java Record DTOs.

### 5.4 Server-Side Workspace & Contract Isolation (ADR-004)
- **Decision:** All project, contract, and runtime management queries verify project ownership on the server side (`project.owner.id == currentPrincipal.id`).

### 5.5 Flyway Version-Controlled Migrations (ADR-005)
- **Decision:** Flyway scripts (`V1__...`, `V2__...`, `V3__...`) manage all schema evolution with `hibernate.ddl-auto=validate`.

### 5.6 Dedicated OpenAPI Parser & Reference Resolver (ADR-006)
- **Decision:** `OpenApiContractParser` encapsulates SwaggerParser, validates OpenAPI 3.x compliance, resolves local `$ref` pointers, and isolates the rest of the application from Swagger/OpenAPI internal classes.

### 5.7 In-Process Stateful Mock Runtime Engine (ADR-007)
- **Decision:** Dynamic mock request dispatching via Spring MVC wildcards (`/mock/{runtimeId}/**`) backed by in-memory route compilation and partitioned state store.
- **Rationale:** High throughput, low latency, zero infrastructure overhead per mock server instance, and instant startup/teardown.

---

## 6. Architectural Invariants
1. **Determinism over Hallucination:** Runtime mock responses and data generation must strictly follow schema rules deterministically.
2. **Zero Hardcoded Secrets:** All credentials, tokens, and database secrets are externalized via environment variables.
3. **Module Independence:** Business modules communicate through designated services and DTOs without cyclic dependencies.
