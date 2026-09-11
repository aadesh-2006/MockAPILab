# MockAPILab — Architecture & Design Decisions

This document outlines the architectural principles, technology choices, and component responsibilities for **MockAPILab**.

---

## 1. Architectural Philosophy: Modular Monolith

MockAPILab is intentionally structured as a **Modular Monolith** rather than a distributed set of microservices.

### Why a Modular Monolith?
- **Domain Cohesion & Velocity:** At this stage of development, domain boundaries (identity, projects, contracts, state machines, scenarios, runtime dispatch) benefit from compile-time type safety, shared memory calls, and single-deployment coordination.
- **Operational Simplicity:** Avoids the overhead of service meshes, distributed tracing, network latency between internal components, and multi-service deployment synchronization.
- **Strict Boundary Enforcement:** Modules communicate via clean interfaces/DTOs within `com.mockapilab.modules.*`. When a module (e.g., dynamic runtime dispatch) warrants independent scaling in the future, its clear package boundaries make extraction straightforward.

```
com.mockapilab
├── common/             # Cross-cutting: web exceptions, API envelopes, CORS
└── modules/
    ├── auth/           # Identity, JWT issuance, password hashing, UserPrincipal (M2)
    ├── project/        # Workspace boundaries & owner isolation (M2)
    ├── contract/       # OpenAPI/Swagger, JSON Schema, controller AST parsers (M3)
    ├── generation/     # Deterministic and random mock payload generators (M3)
    ├── scenario/       # Stateful workflows, sequences, and rule conditions (M4)
    ├── runtime/        # High-throughput mock HTTP request matching engine (M4)
    └── ai/             # Gemini-assisted schema ingestion & contract extraction (M5)
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
        API["REST & Admin API"]
        AuthModule["Auth & Security (JWT)"]
        ProjectModule["Project Workspace Engine"]
        Runtime["Mock Dispatch Engine (Future)"]
        AILayer["Contract Extractor (Gemini) (Future)"]
        ScenarioEngine["State & Scenario Manager (Future)"]
    end

    subgraph Data ["Data & Messaging Layer"]
        PG[("PostgreSQL 16\n(Users, Projects, Contracts)")]
        Redis[("Redis 7\n(Stateful Mock State) (Future)")]
        Kafka[("Apache Kafka\n(Event Stream & Telemetry) (Future)")]
    end

    UI -->|Authenticate & Manage Projects| API
    DevApp -->|Execute Mock Requests| Runtime
    API --> AuthModule
    API --> ProjectModule
    ProjectModule --> PG
    AuthModule --> PG
    Runtime --> Redis
    Runtime --> Kafka
    AILayer -->|Infers Schemas| API
    ScenarioEngine --> Redis
```

---

## 3. Detailed Component Roles

### 3.1 Spring Boot 3 + Java 21 (Core Backend)
- **Role:** High-performance, type-safe application framework serving both the configuration API and the high-throughput mock request dispatcher.
- **Why Java 21 & Spring Boot:**
  - Modern Java features (Virtual Threads / Project Loom for handling high-concurrency mock traffic, Records for immutable DTOs, Pattern Matching).
  - Robust ecosystem for contract parsing, schema validation, and enterprise-grade testing.
  - Native integration capabilities for SQL, Redis, and Kafka.

### 3.2 PostgreSQL (System of Record)
- **Role:** Persistent relational data store.
- **Responsibilities:**
  - Users and authentication credentials.
  - Project and workspace configurations.
  - Imported API contracts, parsed endpoint schemas, route definitions (Milestone 3).
  - Configured stateful scenarios, failure rules, latency profiles (Milestone 4).

### 3.3 Redis (Runtime State Machine & Ephemeral Store - Planned M4)
- **Role:** Ultra-low latency, in-memory data store for live mock interactions.
- **Responsibilities:**
  - Maintaining transient entity states during scenario runs (e.g. `POST /cart/items` modifies state which `GET /cart` reflects).
  - Session-isolated mock states (allowing multiple developers to test against the same mock backend independently).
  - Token-bucket rate limiting and temporary chaos injection flags.

### 3.4 Apache Kafka (Event Streaming & Telemetry - Planned M4/M6)
- **Role:** Asynchronous event bus and telemetry stream.
- **Responsibilities:**
  - Emitting mock invocation events and audit logs without degrading mock response latency.
  - Simulating asynchronous webhook delivery and event-driven backend workflows.
  - Feeding live traffic analytics into the frontend dashboard.

### 3.5 Gemini AI (AI-Assisted Contract Extraction Layer - Planned M5)
- **Role:** Input acceleration and schema inference.
- **Architectural Boundary (Crucial Design Rule):**
  - **AI is NOT the core runtime.** The mock request/response dispatch engine is deterministic, fast, and executed entirely in code.
  - **AI is an extraction and synthesis assistant:** It parses unformatted backend controller files, unstructured API documentation, or code snippets to automatically generate OpenAPI contracts and realistic seed data schemas.

### 3.6 React + TypeScript + Vite (Frontend Application)
- **Role:** Intuitive, responsive web workspace for developers and QA engineers.
- **Responsibilities:**
  - Uploading contracts (OpenAPI files, controller snippets).
  - Inspecting and editing generated mock routes and schemas.
  - Visual scenario designer (graphing state transitions and conditional responses).
  - Real-time mock traffic logger and state inspector.

---

## 4. Milestone 2 Architectural Decisions (ADRs)

### 4.1 Stateless JWT Authentication
- **Decision:** Use stateless JSON Web Tokens (HMAC-SHA256) signed with a securely configured secret key.
- **Rationale:** Stateless tokens eliminate the need for distributed session replication across server instances, allowing seamless scale-out of MockAPILab instances when serving mock traffic.

### 4.2 Strong Cryptographic Password Hashing (BCrypt)
- **Decision:** Store only BCrypt salted hashes (`BCryptPasswordEncoder`) with a configurable work factor.
- **Rationale:** Protects user credentials against offline dictionary and rainbow table attacks. Plaintext passwords never touch persistent storage or logs.

### 4.3 DTO Encapsulation & Field Filtering
- **Decision:** Controllers exclusively consume and return Record DTOs (`RegisterRequest`, `UserResponse`, `CreateProjectRequest`, `ProjectResponse`).
- **Rationale:** Prevents mass-assignment vulnerabilities, decouples the API surface from database schema evolution, and guarantees sensitive fields (such as `passwordHash`) are never leaked in responses.

### 4.4 Server-Side Project Ownership Isolation
- **Decision:** Every project operation verifies that `project.owner.id == authenticatedPrincipal.id` at the service and repository boundary.
- **Rationale:** Client-side filtering is insufficient for multi-tenant developer platforms. Server-side authorization ensures User A can never inspect, modify, or delete User B's workspaces even if project UUIDs are known or brute-forced.

### 4.5 Version-Controlled Database Migrations (Flyway)
- **Decision:** Manage PostgreSQL schema exclusively through Flyway SQL migration scripts (`db/migration/V1__...`) and set Hibernate's `ddl-auto` to `validate`.
- **Rationale:** Automatic Hibernate schema updates (`update`/`create-drop`) are non-deterministic and hazardous for production data integrity. Flyway ensures deterministic, reproducible database migrations across local dev, CI/CD, and production environments.

### 4.6 Modular Monolith Auth Boundary
- **Decision:** The `auth` module provides the security filter, JWT validator, and `UserPrincipal`. Other modules (e.g. `project`) reference users only through IDs and read-only entity relationships without cyclic dependencies.

---

## 5. Architectural Invariants
1. **Determinism over Hallucination:** Runtime mock responses must follow user-defined or schema-derived rules deterministically.
2. **Zero Hardcoded Secrets:** All credentials, keys, and environment variables are strictly externalized.
3. **Module Independence:** Business modules in the backend must avoid circular dependencies and communicate via designated service interfaces.
