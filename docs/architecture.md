# MockAPILab - Architecture & Design Decisions

This document outlines the architectural principles, technology choices, and component responsibilities for **MockAPILab**.

---

## 1. Architectural Philosophy: Modular Monolith

MockAPILab is intentionally structured as a **Modular Monolith** rather than a distributed set of microservices.

### Why a Modular Monolith?
- **Domain Cohesion & Velocity:** Domain boundaries (identity, projects, contracts, state machines, scenarios, runtime dispatch, data generation, state management, asynchronous jobs) benefit from compile-time type safety, shared memory calls, and single-deployment coordination.
- **Operational Simplicity:** Avoids the overhead of service meshes, distributed tracing, network latency between internal components, and multi-service deployment synchronization.
- **Strict Boundary Enforcement:** Modules communicate via clean interfaces/DTOs within `com.mockapilab.modules.*`. When a module warrants independent scaling in the future, its clear package boundaries make extraction straightforward.

```
com.mockapilab
+-- common/                 # Cross-cutting: web exceptions, API envelopes, CORS, OpenAPI configuration
+-- modules/
    +-- auth/               # Identity, JWT issuance, password hashing, UserPrincipal (M2)
    +-- project/            # Workspace boundaries & owner isolation (M2)
    +-- contract/           # OpenAPI/Swagger parser, Normalized Contract Model, JSONB persistence (M3)
    +-- runtime/            # Stateful Mock Runtime Engine (M4/M5/M6/M7)
        +-- controller/     # Mock gateway (/mock/{runtimeId}/**), runtime controls, async job endpoints
        +-- engine/         # Route compiler, regex matching, request validator, dispatcher
        +-- state/          # Runtime state store abstraction, Redis & In-Memory implementations (M6)
        +-- config/         # Redis & Kafka topic / producer / consumer configuration (M6/M7)
        +-- generation/     # Realistic deterministic data generation engine (M5)
        +-- messaging/      # Kafka job producer, consumer worker, and event contracts (M7)
        +-- model/          # MockRuntime and GenerationJob entities (M4/M7)
        +-- repository/     # MockRuntimeRepository and GenerationJobRepository
        +-- service/        # RuntimeService and GenerationJobService
    +-- scenario/           # Stateful workflows, sequences, and rule conditions (Future)
    +-- ai/                 # Gemini AI-assisted contract extraction & normalization engine (M8)
        +-- config/         # AiProperties (gemini api-key, model, timeouts)
        +-- converter/      # AiCandidateConverter (maps candidate AST to NormalizedContract)
        +-- dto/            # AiExtractContractRequest, AiExtractContractResponse, ExtractionInputType
        +-- exception/      # AiConfigurationException, AiProviderException
        +-- model/candidate/# AiCandidateContract, AiCandidateEndpoint, AiCandidateSchema
        +-- prompt/         # AiExtractionPromptBuilder (structured system/user prompts)
        +-- provider/       # AiProvider interface & GeminiAiProvider (Google Generative Language REST)
        +-- service/        # AiService (orchestrates extraction -> validation -> normalization)
        +-- validation/     # AiCandidateValidator (deterministic schema & route validator)
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
        JobService["GenerationJobService (Queue & Status API)"]
        KafkaProducer["GenerationJobProducer"]
        KafkaConsumer["GenerationJobConsumer (Worker)"]
        DataEngine["Realistic Deterministic Data Engine\n(Seedable PRNG & Schema Evaluator)"]
        StateStore["RuntimeStateStore Interface\n(RedisRuntimeStateStore / InMemoryRuntimeStateStore)"]
    end

    subgraph Messaging ["Event & Stream Backbone"]
        KafkaTopic["Apache Kafka 3.7 (KRaft)\nTopic: mockapi.generation.jobs"]
    end

    subgraph Data ["Data & Storage Layer"]
        PG[("PostgreSQL 16 (System of Record)\nUsers, Projects, Contracts JSONB, Runtimes, Generation Jobs")]
        RedisStore[("Redis 7 (Shared Live Mock State)\nHashes & Collection Index Sets")]
    end

    UI -->|Authenticate, Manage Projects, Ingest Contracts, Start Runtimes| API
    UI -->|Submit Generation Job (HTTP 202) & Poll Status| API
    DevApp -->|Execute Public Mock Requests| RuntimeEngine

    API --> AuthModule
    API --> ProjectModule
    API --> ContractModule
    API --> RuntimeEngine
    API --> JobService

    JobService -->|1. Persist QUEUED Job| PG
    JobService -->|2. Publish Event| KafkaProducer
    KafkaProducer -->|3. Produce GenerationJobEvent| KafkaTopic
    KafkaTopic -->|4. Consume Event| KafkaConsumer
    KafkaConsumer -->|5. Execute Job| JobService
    JobService -->|6. Generate Data| DataEngine
    JobService -->|7. Write Entities| StateStore
    JobService -->|8. Mark COMPLETED| PG

    StateStore -.->|Production / Multi-Instance| RedisStore
    StateStore -.->|Test / Local Fallback| InMemoryMap["Process Memory"]
```

### Component Breakdown & Architectural Separation of Concerns
| Layer / Component | Technology | Primary Architectural Responsibility |
| :--- | :--- | :--- |
| **Client Frontend** | React 18, TypeScript, Vite, TailwindCSS | User dashboard for authentication, project/contract management, runtime lifecycle, async job tracking, and live polling. |
| **Backend Core** | Java 21, Spring Boot 3.4.x | Modular monolith hosting REST management APIs, route compilers, dispatch engine, job orchestrator, and Kafka workers. |
| **Security & Identity** | Spring Security, jjwt (HMAC-SHA256), BCrypt | Stateless JWT verification, password hashing, workspace ownership enforcement. |
| **Contract Ingestion** | SwaggerParser 2.1.x, Jackson | OpenAPI 3.x document parsing, dereferencing, normalization into canonical internal AST. |
| **System of Record** | PostgreSQL 16, Flyway, Spring Data JPA | Relational storage for users, projects, normalized contracts (JSONB), runtimes, and durable `GenerationJob` history. |
| **Event Transport** | Apache Kafka 3.7 (KRaft mode), Spring Kafka | Asynchronous event backbone decoupling expensive dataset generation from HTTP request threads (`mockapi.generation.jobs`). |
| **Live Runtime State** | Redis 7, Spring Data Redis (`StringRedisTemplate`) | Shared, partitioned mutable mock entity store across backend instances with atomic hashing and collection indexing. |
| **Fallback State** | In-Memory (`ConcurrentHashMap` + `LinkedHashMap`) | Fast thread-safe isolated state store for test profiles. |
| **Data Generation** | Pure Java PRNG (`Random(seed)`), Curated datasets | Deterministic, reproducible, schema-aware mock data generation with zero AI dependency. |

---

## 3. Normalized Contract Architecture (Milestone 3 Core)

### 3.1 The Canonical Normalized Model Principle
To decouple MockAPILab from the quirks of specific input formats (OpenAPI 2, OpenAPI 3.0, OpenAPI 3.1, code-first annotations, or future natural language inputs), the system normalizes all schemas and endpoints into a single internal AST (`NormalizedContract`).

```
OpenAPI 3.0/3.1 YAML/JSON ---+
                             |
Code Annotations (Future) ---+---> [ Parser & Normalizer ] ---> NormalizedContract (JSONB in PG)
                             |                                         |
Natural Language Specs ---+                                        +--> Dynamic Mock Engine (M4)
                                                                   +--> Deterministic Data Engine (M5)
                                                                   +--> Kafka Generation Worker (M7)
                                                                   +--> Stateful Scenarios (Future)
```

### 3.2 Normalized Contract Structure
The normalized model (`com.mockapilab.modules.contract.model.normalized`) encapsulates:
- **`ContractMetadata`:** Title, description, version, canonical format version.
- **`NormalizedEndpoint`:** Path, HTTP method, summary, description, operationId, normalized parameters, request body, and response definitions.
- **`NormalizedParameter`:** Location (`PATH`, `QUERY`, `HEADER`, `COOKIE`), name, required flag, description, and schema.
- **`NormalizedRequestBody` & `NormalizedResponse`:** Status codes, descriptions, media type mappings (e.g. `application/json`), header specifications.
- **`NormalizedSchema`:** Type (`string`, `integer`, `number`, `boolean`, `array`, `object`), format (`uuid`, `email`, `date-time`, etc.), properties, required properties, array items, enum constants, nullable flags, examples, default values, minimum, maximum, minLength, maxLength, pattern, and component references (`$ref`).

---

## 4. Stateful Mock Runtime Engine (Milestone 4 Core)

### 4.1 In-Process Dynamic Dispatch Architecture
Rather than provisioning separate OS processes or Docker containers per mock, MockAPILab hosts dynamic mock backends in-process:
- **Public Gateway:** Intercepts `/mock/{runtimeId}/**` without requiring JWT tokens.
- **Route Compilation:** `RouteCompiler` compiles `NormalizedContract` paths (e.g. `/pets/{petId}`) into prioritized regex matchers with specificity weighting (exact paths prioritized over parameter variables).
- **Request Validation:** `MockRequestValidator` validates incoming JSON payloads against schema rules (required fields, primitive type checks, enum constraints) returning `400 Bad Request` with structured error details upon violation.
- **Stateful REST Semantics:**
  - `POST /collection`: Generates/preserves ID, inserts entity into runtime collection state, returns `201 Created`.
  - `GET /collection`: Returns current stored entities (returns empty `[]` when no entities exist). **Never silently mutates state**.
  - `GET /collection/{id}`: Looks up entity by ID, returns `200 OK` or `404 Not Found`.
  - `PUT/PATCH /collection/{id}`: Merges/updates stored entity, returns `200 OK` or `404 Not Found`.
  - `DELETE /collection/{id}`: Removes entity, returns `204 No Content` / `200 OK`; subsequent `GET` returns `404`.
  - `Generic / RPC endpoints`: Returns deterministic mock response conforming to `NormalizedResponse`.

---

## 5. Realistic Deterministic Data Engine (Milestone 5 Core)

### 5.1 Architecture & Separation of Concerns
The data generation engine (`com.mockapilab.modules.runtime.generation`) is decoupled from HTTP dispatching:

```
com.mockapilab.modules.runtime.generation/
+-- MockDataGenerator.java          # Primary facade interface
+-- DefaultMockDataGenerator.java   # Coordinates schema evaluation & collection generation
+-- SchemaDataGenerator.java        # Core recursive schema evaluator
+-- DataGenerationContext.java      # State context (seed, depth, path, cycle detection, array size)
+-- GenerationSeed.java             # PRNG state with deterministic branching
+-- generators/
    +-- StringValueGenerator.java   # Realistic names, emails, UUIDs, addresses, cities, phone numbers
    +-- NumberValueGenerator.java   # Integers, floats, doubles with property heuristics and min/max bounds
    +-- BooleanValueGenerator.java  # Deterministic booleans
    +-- DateTimeValueGenerator.java # Valid ISO-8601 timestamps and calendar dates
    +-- CollectionValueGenerator.java # Nested objects and arrays of configurable size
```

### 5.2 Determinism & Seed Reproducibility
- **Invariant:** Same seed + same schema + same property path = identical generated output across executions.
- **Seed Branching:** `GenerationSeed.branch(propertyKey)` creates isolated child seeds for sub-properties, guaranteeing that property re-ordering in schemas does not perturb other fields' random streams.
- **Explicit Seed Exposure:** When a generation job completes, its effective seed is stored in `GenerationJob.effective_seed` and returned in `GenerationJobResponse.effectiveSeed` for exact future reproducibility.

### 5.3 Priority Evaluation Order
Values are evaluated according to a strict priority hierarchy:
$$\text{Explicit Example} > \text{Default Value} > \text{Enum Constants} > \text{Generated Value}$$
- When multiple `enumConstants` are defined, values are chosen deterministically based on the seed rather than statically picking the first item.

---

## 6. Redis-Backed Runtime State Engine (Milestone 6 Core)

### 6.1 State Architecture & Division of Responsibility
State in MockAPILab is divided into two distinct tiers:

1. **System of Record (PostgreSQL):**
   - User identity, workspace projects, normalized contract versions (JSONB), runtime definitions, and durable generation jobs.
   - Strong relational integrity, foreign keys, transaction guarantees, and audit history.
2. **Live Mutable Runtime State (Redis):**
   - Transient, high-throughput, mutable mock entities created via mock `POST`/`PUT` endpoints or asynchronous data generation workers.
   - Shared across all backend application instances behind a load balancer.

### 6.2 Redis Data Model & Key Structure
All runtime keys use a strictly namespaced, hierarchical key format:

```
mockapi:runtime:{runtimeId}:collection:{collectionPath}  -->  Redis HASH
    Field: {entityId}
    Value: JSON serialized Map<String, Object>

mockapi:runtime:{runtimeId}:collections                  -->  Redis SET
    Members: ["/pets", "/orders", "/users", ...]
```

### 6.3 Collection Existence & Lifecycle Semantics
1. **Source of Truth:** The Collection Index Set (`mockapi:runtime:{runtimeId}:collections`) is the single source of truth for collection existence.
2. **Empty Collection Semantics:** `hasCollection(runtimeId, collectionPath)` evaluates membership in the Redis Set index (`SISMEMBER`). An explicitly initialized empty collection evaluates to `true` even if its entity hash contains zero entries.
3. **Deletion Retention Semantics:** When `deleteEntity` removes the final entity from a collection hash (`HDEL`), the collection path remains registered in the index set. Entity count becomes 0, but `hasCollection` remains `true`.
4. **Runtime Teardown:** `clearRuntime(runtimeId)` queries the collection index set, deletes all corresponding collection hashes (`DEL`), and deletes the collection index set itself.

---

## 7. Kafka-Backed Asynchronous Generation Engine (Milestone 7 Core)

### 7.1 Why Apache Kafka for Mock Generation?
Generating large, realistic, nested mock datasets (e.g. 100+ entities with relational sub-objects, constraints, and validation) is computationally intensive. In synchronous execution, HTTP client request threads would block, leading to gateway timeouts, connection exhaustion, and poor developer experience.

By introducing Apache Kafka:
- **Non-Blocking Ingestion:** The generation request returns `HTTP 202 Accepted` immediately.
- **Worker Decoupling:** Generation execution is handled asynchronously by worker consumers without stalling web request threads.
- **Durable Scheduling:** Job metadata and status live durably in PostgreSQL while Kafka acts as the scalable transport backbone.

```
Client (React / HTTP)
    │  POST .../data/generate
    ▼
RuntimeController / GenerationJobService
    │  1. Validate runtime & project ownership
    │  2. Save GenerationJob (Status: QUEUED) to PostgreSQL
    │  3. Publish GenerationJobEvent to Kafka topic 'mockapi.generation.jobs'
    │  4. Return HTTP 202 Accepted + GenerationJobResponse
    ▼
Kafka Topic: mockapi.generation.jobs (Key: jobId)
    │
    ▼
GenerationJobConsumer (Kafka Worker)
    │  5. Receive GenerationJobEvent
    │  6. Idempotency check: Skip if COMPLETED / FAILED
    │  7. Update GenerationJob (Status: RUNNING, startedAt: now)
    │  8. Execute M5 MockDataGenerator.generateCollection(...)
    │  9. Write entities into RedisRuntimeStateStore
    │ 10. Update GenerationJob (Status: COMPLETED, completedAt: now)
```

### 7.2 Three-Tier Storage Model
```
┌───────────────────────────────────────────────────────────────┐
│                          Storage Tier                         │
├──────────────────┬────────────────────┬───────────────────────┤
│ PostgreSQL 16    │ Durable SoR        │ Users, Projects,      │
│                  │                    │ Contracts (JSONB),    │
│                  │                    │ Runtimes, Jobs        │
├──────────────────┼────────────────────┼───────────────────────┤
│ Apache Kafka 3.7 │ Event Backbone     │ Asynchronous Job      │
│                  │                    │ Commands & Events     │
├──────────────────┼────────────────────┼───────────────────────┤
│ Redis 7          │ Live Mutable State │ Mock entity hashes &  │
│                  │                    │ collection index sets │
└──────────────────┴────────────────────┴───────────────────────┘
```

### 7.3 Event Contract & Schema
Topic: `mockapi.generation.jobs`  
Payload: `GenerationJobEvent` (JSON serialized):
```json
{
  "jobId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "runtimeId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "projectId": "a1b2c3d4-e5f6-7a8b-9c0d-1e2f3a4b5c6d",
  "collection": "/users",
  "count": 50,
  "requestedSeed": 42
}
```

### 7.4 Idempotency & Duplicate Delivery Protection
Kafka at-least-once delivery semantics can deliver duplicate events. `GenerationJobService.processJob` enforces strict idempotency:
- If job `status == COMPLETED` or `status == FAILED`, the consumer logs the duplicate and immediately ignores it.
- If job `status == RUNNING`, concurrent duplicate execution is safely suppressed.
- Re-delivering an event never duplicates entities or corrupts Redis collection state.

### 7.5 Explicit Failure Semantics
1. **Kafka Publish Failure:** If Kafka broker publishing fails during submission, the job is immediately marked `FAILED`, the root cause is persisted in `errorMessage`, and an HTTP 503 error is returned to the client.
2. **Worker Processing Failure:** If data generation fails (e.g. invalid schema, cyclic dependency, Redis error), the worker catches the error, sets status to `FAILED`, records `errorMessage`, and persists `completedAt` timestamp.

---

## 8. Gemini AI-Assisted Contract Extraction Engine

### 8.1 Architecture & Boundaries
The AI contract extraction module bridges informal developer input (natural-language API specifications or raw Spring Boot controller/model source code) and MockAPILab's core canonical contract engine.

```
┌───────────────────────────────────────────────────────────┐
│                     Extraction Flow                       │
├───────────────────────────────────────────────────────────┤
│ 1. Client (Developer UI / REST Client)                    │
│    POST /api/v1/projects/{projectId}/contracts/ai-extract │
│    Payload: { name, inputType, content }                  │
│    │                                                      │
│    ▼                                                      │
│ 2. ContractController (Auth & Workspace Verification)     │
│    │                                                      │
│    ▼                                                      │
│ 3. AiService.extractAndNormalize(inputType, content)     │
│    │                                                      │
│    ├─► 4. AiExtractionPromptBuilder                       │
│    │      Constructs structured extraction prompt         │
│    │                                                      │
│    ├─► 5. AiProvider (GeminiAiProvider)                   │
│    │      Invokes Gemini Generative Language REST API     │
│    │      Parses JSON into AiCandidateContract            │
│    │                                                      │
│    ├─► 6. AiCandidateValidator                           │
│    │      Deterministic structural & schema validation    │
│    │                                                      │
│    └─► 7. AiCandidateConverter                           │
│           Transforms candidate to NormalizedContract      │
│    │                                                      │
│    ▼                                                      │
│ 8. ContractService.createVersionedContract(...)           │
│    Persists Contract + ContractVersion (SoR: PostgreSQL)  │
│    (sourceType: NATURAL_LANGUAGE / AI_CONTROLLER)         │
│    │                                                      │
│    ▼                                                      │
│ 9. Downstream Consumption                                 │
│    M4 Runtimes / M5 Deterministic Gen / M6 Redis /        │
│    M7 Kafka Async Jobs                                    │
└───────────────────────────────────────────────────────────┘
```

### 8.2 Candidate Proposal vs Canonical Contract
To maintain strict determinism and reliability:
- **AI is an untrusted proposer:** Gemini extracts an intermediate representation (`AiCandidateContract`).
- **Engine is the authority:** `AiCandidateValidator` checks HTTP methods, path format, path variable presence, schema property types, and `$ref` references. If validation fails, extraction is rejected before persistence.
- **Canonical normalization:** `AiCandidateConverter` converts the validated candidate into the canonical `NormalizedContract`. Downstream systems (runtimes, data generators, job workers) interact *only* with `NormalizedContract`.

### 8.3 Untrusted Code Execution Policy
Spring Boot controller source code submitted for AI extraction is treated strictly as plain text. The backend:
- Never compiles the code.
- Never dynamically classloads or invokes user classes.
- Never executes user code in sandboxes or runtimes.

### 8.4 Pluggable AI Provider Abstraction
The `AiProvider` interface decouples the extraction logic from specific LLM vendors:
```java
public interface AiProvider {
    AiCandidateContract extractContract(String systemPrompt, String userPrompt);
}
```
`GeminiAiProvider` implements `AiProvider` using Spring `RestClient` to call Gemini 1.5/2.0 models via standard REST protocols.

### 8.5 Failure Modes and HTTP Semantics
1. **Missing or Unconfigured API Key:** Throws `AiConfigurationException` $\rightarrow$ HTTP 503 Service Unavailable (`AI_SERVICE_UNAVAILABLE`).
2. **Provider Timeout / Rate Limit / HTTP Error:** Throws `AiProviderException` $\rightarrow$ HTTP 503 Service Unavailable (`AI_SERVICE_UNAVAILABLE`).
3. **Invalid AI Output or Schema Validation Failure:** Throws `ValidationException` $\rightarrow$ HTTP 400 Bad Request (`VALIDATION_ERROR`).
4. **Invalid Input Request:** Throws `ValidationException` $\rightarrow$ HTTP 400 Bad Request (`VALIDATION_ERROR`).

---

## 9. Architectural Decision Records (ADRs)

### 9.1 Stateless JWT Authentication (ADR-001)
Stateless HMAC-SHA256 tokens for identity and workspace authorization.

### 9.2 Strong Password Hashing (ADR-002)
BCrypt with salting (`BCryptPasswordEncoder`).

### 9.3 Strict DTO Boundaries (ADR-003)
All controller APIs expose and consume Java Record DTOs.

### 9.4 Server-Side Workspace & Contract Isolation (ADR-004)
All project, contract, and runtime management queries verify project ownership on the server side (`project.owner.id == currentPrincipal.id`).

### 9.5 Flyway Version-Controlled Migrations (ADR-005)
Flyway scripts (`V1`, `V2`, `V3`, `V4`) manage all schema evolution with `hibernate.ddl-auto=validate`.

### 9.6 Dedicated OpenAPI Parser & Reference Resolver (ADR-006)
`OpenApiContractParser` encapsulates SwaggerParser, validates OpenAPI 3.x compliance, resolves local `$ref` pointers, and isolates the rest of the application from Swagger/OpenAPI internal classes.

### 9.7 In-Process Stateful Mock Runtime Engine (ADR-007)
Dynamic mock request dispatching via Spring MVC wildcards (`/mock/{runtimeId}/**`) backed by in-memory route compilation and partitioned state store.

### 9.8 Decoupled Deterministic Data Generation Engine (ADR-008)
Dedicated `generation/` subsystem using seedable PRNGs, schema heuristics, and curated datasets without online dependencies or hidden GET mutations.

### 9.9 Redis-Backed Shared Runtime State Engine (ADR-009)
`RedisRuntimeStateStore` implementing `RuntimeStateStore` using Spring Data Redis (`StringRedisTemplate` + Jackson) with atomic hashes and set collection indexes.

### 9.10 Asynchronous Mock Generation with Apache Kafka (ADR-010)
- **Decision:** Asynchronous collection generation via Kafka topic `mockapi.generation.jobs`, durable `GenerationJob` in PostgreSQL, and background worker consumers.
- **Rationale:** Prevents HTTP client timeouts during large dataset generation, cleanly decouples API management from heavy CPU workloads, and maintains strict idempotency across worker deliveries.

### 9.11 Gemini AI-Assisted Contract Extraction (ADR-011)
- **Decision:** Use Google Gemini Generative Language REST API via a pluggable `AiProvider` to extract candidate contracts (`AiCandidateContract`), followed by deterministic validation (`AiCandidateValidator`) and conversion into canonical `NormalizedContract`.
- **Rationale:** Enables developers to quickly create mock APIs from informal natural-language specifications or existing Spring Boot controller code, without allowing AI hallucinations to directly dictate runtime execution or bypass schema validation.

---

## 10. Architectural Invariants
1. **Determinism over Hallucination:** Runtime mock responses and data generation must strictly follow schema rules deterministically.
2. **Zero Hidden State Mutations:** Read operations (`GET`) never mutate runtime state; population is performed via explicit REST APIs or stateful mutations (`POST`, `PUT`).
3. **Zero Hardcoded Secrets:** All credentials, tokens, and database secrets are externalized via environment variables.
4. **Module Independence:** Business modules communicate through designated services and DTOs without cyclic dependencies.
5. **Collection Index as Source of Truth:** Collection existence is governed strictly by the collection index set, maintaining stability even when entity count reaches zero.
6. **Three-Tier Storage Separation:** PostgreSQL is the durable system of record; Kafka is the event backbone; Redis is the live mock state.
7. **AI Proposes, Engine Disposes:** AI models propose candidate contracts (`AiCandidateContract`); only deterministically validated and converted `NormalizedContract` instances are persisted and executed. AI never directly accesses, executes, or mutates live runtimes.