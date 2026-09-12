# MockAPILab

> Intelligent, stateful mock backend engine for modern frontend and full-stack development.

[![Backend Build](https://img.shields.io/badge/backend-Spring%20Boot%203%20%7C%20Java%2021-brightgreen.svg)](backend/)
[![Frontend Build](https://img.shields.io/badge/frontend-React%2018%20%7C%20TypeScript%20%7C%20Vite-blue.svg)](frontend/)
[![Architecture](https://img.shields.io/badge/architecture-Modular%20Monolith-orange.svg)](docs/architecture.md)
[![Database](https://img.shields.io/badge/database-PostgreSQL%2016%20%7C%20JSONB%20%7C%20Flyway-blue.svg)](backend/src/main/resources/db/migration/)
[![State Store](https://img.shields.io/badge/State%20Store-Redis%207%20%7C%20In--Memory-red.svg)](backend/src/main/java/com/mockapilab/modules/runtime/state/)
[![Event Streaming](https://img.shields.io/badge/Event%20Streaming-Apache%20Kafka%203.7%20%28KRaft%29-red.svg)](backend/src/main/java/com/mockapilab/modules/runtime/messaging/)
[![AI Engine](https://img.shields.io/badge/AI%20Engine-Gemini%20Contract%20Extraction-blueviolet.svg)](backend/src/main/java/com/mockapilab/modules/ai/)
[![OpenAPI](https://img.shields.io/badge/OpenAPI-3.x%20Normalized%20Engine-brightgreen.svg)](docs/examples/sample-users-api.yaml)
[![Mock Runtime](https://img.shields.io/badge/Mock%20Runtime-Stateful%20REST%20Engine-blueviolet.svg)](backend/src/main/java/com/mockapilab/modules/runtime/)
[![Data Engine](https://img.shields.io/badge/Data%20Engine-Deterministic%20Realistic%20Generator-teal.svg)](backend/src/main/java/com/mockapilab/modules/runtime/generation/)

---

## 1. What is MockAPILab?

**MockAPILab** is a developer productivity platform that transforms API contracts, OpenAPI specifications, informal natural-language API descriptions, or Spring Boot controller/model source code into a locally runnable, realistic, and **stateful** mock backend. 

Unlike traditional static mock servers that only return fixed JSON fixtures, MockAPILab:
- Extracts high-fidelity candidate contracts from plain text or Spring Boot code using **Gemini AI**, deterministically validated and normalized before persistence.
- Maintains shared, high-performance mock state across instances backed by **Redis 7** (with in-memory fallback for testing).
- Dispatches heavy mock collection generation asynchronously via **Apache Kafka (KRaft)** to dedicated background workers (`HTTP 202 Accepted`).
- Executes realistic multi-step REST CRUD lifecycles (`POST` $\rightarrow$ `GET collection` $\rightarrow$ `GET item` $\rightarrow$ `PUT` $\rightarrow$ `DELETE` $\rightarrow$ `404`).
- Generates **realistic, schema-aware, deterministic mock data** (names, emails, India-friendly phones, companies, timestamps, addresses) governed by seed reproducibility.
- Enforces strict request body validation (rejecting invalid schemas with `400 Bad Request`).
- Provides isolated state spaces per runtime instance without silent mutations on read operations.

---

## 2. The Problem It Solves

Modern development teams frequently face blocking dependencies between frontend and backend workflows:

- **Backend Bottlenecks:** Frontend teams are delayed waiting for backend APIs to be designed, deployed, and stabilized.
- **Unrealistic Static Mocks:** Existing mocking tools return static, stateless fixtures. They fail to test real-world scenarios such as entity mutation, schema validation failures, or resource lifecycles.
- **Contract Drift & Informal Specs:** Writing OpenAPI YAML by hand from scratch or from informal specs is slow and error-prone.
- **Manual Data Seeding & Slow HTTP Generations:** Crafting realistic mock datasets manually is tedious, while synchronous generation of large collections causes HTTP connection timeouts.

**MockAPILab bridges this gap** by combining AI-assisted contract extraction with dynamic in-process mock backends, Redis-backed shared state, and Kafka-powered asynchronous background generation jobs.

---

## 3. High-Level Architecture

MockAPILab is built as a clean **Modular Monolith** designed for high throughput, operational simplicity, and clear module separation.

```mermaid
flowchart TD
    subgraph Client ["Client Layer"]
        UI["React + TypeScript UI\n(Management, AI Extraction & Mock Studio)"]
        DevApp["Frontend App Under Dev\n(Calling Mock Endpoints)"]
    end

    subgraph Backend ["MockAPILab Backend (Spring Boot 3 + Java 21)"]
        API["REST & Admin API"]
        AuthModule["Auth & Security Engine (JWT)"]
        ProjectModule["Project Workspace Engine"]
        ContractModule["Contract Engine (Parser & Normalizer)"]
        AiModule["AI Engine (Gemini Extraction & Validator)"]
        RuntimeEngine["Stateful Mock Runtime Engine\n(/mock/{runtimeId}/**)"]
        JobService["GenerationJobService (Queue & Status API)"]
        KafkaWorker["GenerationJobConsumer (Worker)"]
        DataEngine["Realistic Deterministic Data Engine\n(Seedable PRNG & Schema Evaluator)"]
        StateStore["RuntimeStateStore Abstraction\n(Redis / In-Memory)"]
    end

    subgraph ExternalAI ["External AI Services"]
        GeminiAPI["Google Gemini Generative Language API\n(gemini-1.5-flash / gemini-2.0-flash)"]
    end

    subgraph Messaging ["Messaging Layer"]
        KafkaTopic["Apache Kafka 3.7\nmockapi.generation.jobs"]
    end

    subgraph Infrastructure ["Infrastructure Layer"]
        PG[("PostgreSQL 16 (System of Record)\nUsers, Projects, Contracts JSONB, Runtimes, Generation Jobs")]
        RedisStore[("Redis 7\n(Shared Live Mutable Mock State)")]
    end

    UI -->|Authenticate, Ingest Contracts, Run AI Extraction, Start Runtimes| API
    UI -->|Submit Generation Job (202) & Poll Status| API
    DevApp -->|Execute Public Mock Requests| RuntimeEngine

    API --> AuthModule
    API --> ProjectModule
    API --> ContractModule
    API --> RuntimeEngine
    API --> JobService

    ContractModule -->|AI Extraction Request| AiModule
    AiModule -->|Generate Candidate Contract| GeminiAPI
    AiModule -->|Validate & Normalize Candidate| ContractModule

    JobService -->|1. Persist QUEUED Job| PG
    JobService -->|2. Dispatch Event| KafkaTopic
    KafkaTopic -->|3. Consume Event| KafkaWorker
    KafkaWorker -->|4. Generate Data| DataEngine
    KafkaWorker -->|5. Populate State| StateStore
    KafkaWorker -->|6. Mark COMPLETED| PG

    StateStore -.->|Production| RedisStore
    StateStore -.->|Test Fallback| MemStore["Process Memory"]
```

---

## 4. Key Capabilities & Module Boundaries

### 4.1 Modular Monolith Package Layout
```
com.mockapilab
+-- common/                 # Global error envelopes, exception handlers, security filters, CORS
+-- modules/
    +-- auth/               # User registration, login, JWT validation, UserPrincipal
    +-- project/            # Workspace isolation, ownership verification, project CRUD
    +-- contract/           # OpenAPI 3.x parser, NormalizedContract model, JSONB storage, AI ingestion
    +-- ai/                 # Gemini AI-assisted contract extraction subsystem
        +-- config/         # AiProperties (gemini.api-key, model, timeout)
        +-- converter/      # AiCandidateConverter (transforms candidate to NormalizedContract)
        +-- dto/            # AiExtractContractRequest/Response, ExtractionInputType
        +-- exception/      # AiConfigurationException, AiProviderException
        +-- model/          # AiCandidateContract, Endpoint, Parameter, RequestBody, Schema
        +-- prompt/         # AiExtractionPromptBuilder (structured schema prompts)
        +-- provider/       # AiProvider interface, GeminiAiProvider (RestClient)
        +-- service/        # AiService (orchestrates prompt, provider, validator, converter)
        +-- validation/     # AiCandidateValidator (deterministic schema & path validation)
    +-- runtime/            # Stateful Mock Runtime subsystem
        +-- controller/     # Mock Gateway (/mock/{runtimeId}/**), runtime controls, generation job API
        +-- engine/         # Route compilation, regex dispatch, OpenAPI request validation
        +-- state/          # RuntimeStateStore, RedisRuntimeStateStore, InMemoryRuntimeStateStore
        +-- config/         # Redis & Kafka topic / producer configuration
        +-- generation/     # Deterministic schema evaluator, PRNG seeds, curated datasets
        +-- messaging/      # Kafka generation job producer, consumer worker, event contracts
        +-- model/          # MockRuntime and GenerationJob entities
        +-- repository/     # MockRuntimeRepository and GenerationJobRepository
        +-- service/        # RuntimeService and GenerationJobService
    +-- scenario/           # Multi-step stateful workflows & sequence conditions (Future)
```

### 4.2 Core Features
1. **Stateless JWT Security:** Strong authentication with BCrypt hashing and server-enforced workspace authorization.
2. **Canonical Contract Model (`NormalizedContract`):** Decouples external API contract formats (OpenAPI 3.0/3.1, YAML/JSON, Natural Language, Spring Boot code) from runtime execution.
3. **Gemini AI-Assisted Contract Extraction:**
   - Converts natural-language API specs or Spring Boot controller/model code into candidate contracts.
   - Deterministic structural and schema validation (`AiCandidateValidator`) prevents AI hallucinations from reaching live runtimes.
   - Converts to canonical `NormalizedContract` and persists as versioned contract with source type `NATURAL_LANGUAGE` or `AI_CONTROLLER`.
4. **In-Process Dynamic Route Dispatch:** Compiles normalized endpoints into prioritized regex matchers with specificity weighting.
5. **Redis-Backed Shared State (`RedisRuntimeStateStore`):**
   - Live mock state stored in Redis Hashes (`mockapi:runtime:{id}:collection:{path}`).
   - Registered collections tracked via Redis Sets (`mockapi:runtime:{id}:collections`).
   - Atomic field operations (`HSET`, `HGET`, `HDEL`, `HLEN`, `HGETALL`).
6. **Kafka-Powered Asynchronous Mock Data Generation:**
   - Generation endpoints return `HTTP 202 Accepted` with durable `GenerationJob` tracking in PostgreSQL.
   - Decoupled worker processing via Kafka topic `mockapi.generation.jobs`.
   - Built-in idempotency protecting against duplicate deliveries.
   - Seed reproducibility exposed in `effectiveSeed`.

---

## 5. API Reference

| Module | Method | Endpoint | Access | Description |
|---|---|---|---|---|
| **Auth** | `POST` | `/api/v1/auth/register` | Public | Register a new user account |
| **Auth** | `POST` | `/api/v1/auth/login` | Public | Authenticate and obtain JWT access token |
| **Auth** | `GET` | `/api/v1/auth/me` | Protected | Retrieve current authenticated user profile |
| **Projects** | `POST` | `/api/v1/projects` | Protected | Create a new project workspace |
| **Projects** | `GET` | `/api/v1/projects` | Protected | List all workspaces owned by current user |
| **Projects** | `GET` | `/api/v1/projects/{id}` | Protected | Get workspace details by ID (owner only) |
| **Projects** | `PUT` | `/api/v1/projects/{id}` | Protected | Update project name / description (owner only) |
| **Projects** | `DELETE` | `/api/v1/projects/{id}` | Protected | Delete project workspace (owner only) |
| **Contracts**| `POST` | `/api/v1/projects/{projectId}/contracts` | Protected | Ingest OpenAPI 3.x contract & create version 1 |
| **Contracts**| `POST` | `/api/v1/projects/{projectId}/contracts/ai-extract` | Protected | **Extract candidate contract via Gemini AI & ingest (`HTTP 201`)** |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts` | Protected | List contracts belonging to project (owner only) |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts/{contractId}` | Protected | Get contract details & latest version stats |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts/{contractId}/versions` | Protected | List all versions for a contract |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts/{contractId}/versions/{versionNumber}` | Protected | Retrieve full `NormalizedContract` JSON definition |
| **Runtimes** | `POST` | `/api/v1/projects/{projectId}/contracts/{contractId}/versions/{versionNumber}/runtime` | Protected | Start in-process stateful mock runtime |
| **Runtimes** | `GET` | `/api/v1/projects/{projectId}/runtimes` | Protected | List all mock runtimes in project |
| **Runtimes** | `GET` | `/api/v1/projects/{projectId}/runtimes/{runtimeId}` | Protected | Get runtime details and status |
| **Runtimes** | `GET` | `/api/v1/projects/{projectId}/runtimes/{runtimeId}/status` | Protected | Get live runtime statistics, entities count & uptime |
| **Data Gen** | `POST` | `/api/v1/projects/{projectId}/runtimes/{runtimeId}/data/generate` | Protected | **Queue asynchronous mock collection generation job (`HTTP 202`)** |
| **Data Gen** | `GET` | `/api/v1/projects/{projectId}/runtimes/{runtimeId}/generation-jobs/{jobId}` | Protected | **Get generation job status & metadata** |
| **Data Gen** | `GET` | `/api/v1/projects/{projectId}/runtimes/{runtimeId}/generation-jobs` | Protected | **List all generation jobs for runtime** |
| **Runtimes** | `POST` | `/api/v1/projects/{projectId}/runtimes/{runtimeId}/stop` | Protected | Stop active mock runtime |
| **Runtimes** | `DELETE` | `/api/v1/projects/{projectId}/runtimes/{runtimeId}` | Protected | Stop, clear state, and delete runtime |
| **Mock Gateway** | `ALL` | `/mock/{runtimeId}/**` | **Public** | **Execute dynamic stateful mock API calls** |

---

## 6. Technology Stack

| Layer | Technologies | Purpose |
|---|---|---|
| **Core Backend** | Java 21, Spring Boot 3.4, Maven | Modular monolith backend runtime, REST API, mock engine |
| **Security & Auth** | Spring Security 6, JJWT 0.12, BCrypt | Stateless JWT authentication, role & ownership authorization |
| **AI Contract Engine** | Google Gemini API, Spring `RestClient`, Jackson | Extract candidate contracts from natural language & Spring Boot code |
| **Contract Engine** | SwaggerParser 2.1, Jackson YAML, SpringDoc OpenAPI | OpenAPI 3.x parser, validation, schema normalizer, Swagger UI |
| **Mock Runtime** | Dynamic Regex Compiler, Schema Validator | Stateful REST simulation, route dispatch engine |
| **Live State Store** | Redis 7, Spring Data Redis (`StringRedisTemplate`) | Shared live mutable mock entity store & collection index |
| **Event Streaming** | Apache Kafka 3.7 (KRaft), Spring Kafka | Asynchronous generation job queue & worker messaging |
| **Fallback State** | In-Memory `ConcurrentHashMap` + `LinkedHashMap` | Fast thread-safe isolated state store for test profiles |
| **Data Engine** | Seedable PRNG, Curated Reference Sets | Deterministic realistic data generation engine |
| **Database & ORM** | PostgreSQL 16, Spring Data JPA, Hibernate (JSONB) | Relational persistence + JSONB contracts + durable generation jobs |
| **Schema Migrations**| Flyway Migration Engine | Deterministic database migrations (`V1`, `V2`, `V3`, `V4`) |
| **Frontend** | React 18, TypeScript, Vite, TailwindCSS | Developer dashboard, AI extraction, runtime control & job tracker |
| **Containers** | Docker, Docker Compose | Reproducible local and CI/CD development environment |
| **Testing** | JUnit 5, Mockito, MockMvc, H2 | Comprehensive automated testing suite (110 tests) |

---

## 7. Current Milestone & Status

### ✅ Milestone 1: Project Foundation (Complete)
- [x] Initialized clean project repository structure (`backend/`, `frontend/`, `infrastructure/`, `docs/`).
- [x] Established Java 21 Spring Boot 3 modular monolith base and package structure.
- [x] Configured Docker Compose infrastructure definitions for PostgreSQL, Redis, and Kafka.

### ✅ Milestone 2: Identity + Project Management (Complete)
- [x] Integrated PostgreSQL with Spring Data JPA (`User` and `Project` entities with UUIDs).
- [x] Configured Flyway database migrations (`V1__init_users_and_projects.sql`).
- [x] Implemented stateless JWT authentication and BCrypt password hashing (`/register`, `/login`, `/me`).
- [x] Implemented project workspace CRUD with strict server-side owner isolation.

### ✅ Milestone 3: Contract Ingestion + Normalized Contract Engine (Complete)
- [x] Designed canonical `NormalizedContract` model (`endpoints`, `schemas`, `parameters`, `requestBody`, `responses`).
- [x] Implemented OpenAPI 3.x Parser supporting JSON & YAML, local `$ref` resolution, enums, and nested types.
- [x] Created Flyway migration `V2__init_contracts_and_versions.sql` storing normalized definitions in `JSONB`.
- [x] Built contract ingestion and versioned retrieval REST APIs (`/api/v1/projects/{projectId}/contracts/**`).

### ✅ Milestone 4: Stateful Mock Runtime Engine (Complete)
- [x] Created Flyway migration `V3__init_runtimes.sql` for runtime persistence and lifecycle tracking.
- [x] Implemented route compiler with regex pattern extraction, specificity scoring, and REST operation inference.
- [x] Built schema-driven request validator checking required fields, JSON structures, primitive types, and enums.
- [x] Implemented thread-safe `RuntimeStateStore` providing isolated in-memory state per runtime.
- [x] Built full stateful CRUD semantics (POST creates, GET lists, GET by ID retrieves, PUT updates, DELETE removes and produces 404).
- [x] Built public `/mock/{runtimeId}/**` gateway allowing unauthenticated mock traffic.
- [x] Built runtime management APIs (`/runtime`, `/status`, `/stop`, `DELETE`) with owner verification.

### ✅ Milestone 5: Realistic Deterministic Data Engine (Complete)
- [x] Eliminated silent state mutations on `GET` requests (empty collections return `[]`).
- [x] Created dedicated `modules/runtime/generation/` engine with seedable PRNGs and deterministic branching.
- [x] Built schema-aware value generators for strings (names, emails, India-friendly phones, addresses), numbers, booleans, dates, nested objects, and arrays.
- [x] Enforced evaluation priority: explicit example > default > enum > generated value.
- [x] Extended `NormalizedSchema` with constraint fields (`minimum`, `maximum`, `minLength`, `maxLength`, `pattern`).

### ✅ Milestone 6: Redis-Backed Runtime State Engine (Complete)
- [x] Added `spring-boot-starter-data-redis` dependency and externalized configuration properties.
- [x] Implemented `RedisRuntimeStateStore` implementing `RuntimeStateStore` with atomic Redis Hashes and Set collection indexes.
- [x] Implemented exact collection existence semantics via `mockapi:runtime:{id}:collections` Redis Set as single source of truth.
- [x] Enforced collection retention when deleting the final entity from a collection.
- [x] Maintained `InMemoryRuntimeStateStore` as fallback for testing via `@ConditionalOnProperty`.
- [x] Created non-silent `RuntimeStateException` mapped to HTTP 503 `SERVICE_UNAVAILABLE`.

### ✅ Milestone 7: Kafka + Asynchronous Generation Jobs (Complete)
- [x] Integrated `spring-kafka` and defined topic `mockapi.generation.jobs`.
- [x] Created Flyway migration `V4__init_generation_jobs.sql` and durable `GenerationJob` domain entity.
- [x] Refactored generation API to return `HTTP 202 Accepted` with `GenerationJobResponse`.
- [x] Implemented `GenerationJobProducer` and `GenerationJobConsumer` (worker).
- [x] Implemented job status (`GET .../generation-jobs/{jobId}`) and list (`GET .../generation-jobs`) APIs with project ownership checks.
- [x] Built consumer-level idempotency ignoring duplicate deliveries for terminal `COMPLETED` and `FAILED` jobs.
- [x] Updated React UI with live status badge polling and background job tracking.

### ✅ Milestone 8: Gemini AI-Assisted Contract Extraction (Complete)
- [x] Designed candidate contract domain model (`AiCandidateContract`, `AiCandidateEndpoint`, `AiCandidateSchema`).
- [x] Implemented pluggable `AiProvider` with `GeminiAiProvider` invoking Gemini Generative Language REST API.
- [x] Built strict deterministic validator (`AiCandidateValidator`) and converter (`AiCandidateConverter`) to `NormalizedContract`.
- [x] Implemented synchronous ingestion endpoint `POST /api/v1/projects/{projectId}/contracts/ai-extract` (`HTTP 201 Created`).
- [x] Added comprehensive error handling for missing keys and provider failures (`HTTP 503 Service Unavailable`).
- [x] Updated React UI with natural-language and Spring Boot controller extraction studio and presets.
- [x] Created comprehensive automated test suite (110 tests total, 100% pass rate).

---

## 8. Planned Development Phases

```
+-------------------------------------------------------------+
│ Milestone 1: Project Foundation (Complete)                  │
+-------------------------------------------------------------+
│ Milestone 2: Identity + Project Management (Complete)       │
+-------------------------------------------------------------+
│ Milestone 3: Contract Ingestion & Normalization (Complete)  │
+-------------------------------------------------------------+
│ Milestone 4: Stateful Mock Runtime Engine (Complete)        │
+-------------------------------------------------------------+
│ Milestone 5: Realistic Deterministic Data Engine (Complete) │
+-------------------------------------------------------------+
│ Milestone 6: Redis-Backed Runtime State Engine (Complete)   │
+-------------------------------------------------------------+
│ Milestone 7: Kafka + Asynchronous Generation Jobs (Complete)│
+-------------------------------------------------------------+
│ Milestone 8: Gemini AI Contract Extraction (Complete)       │
+-------------------------------------------------------------+
│ Milestone 9: Interactive Scenario Engine & Failure Injector │
+-------------------------------------------------------------+
```

---

## 9. Quick Start (Local Development)

### Prerequisites
- **Java 21+** (JDK 21 or higher)
- **Maven 3.9+**
- **Node.js 20+** and **npm**
- **Docker & Docker Compose** (for PostgreSQL, Redis, and Kafka containers)

### Environment Configuration
Copy the template environment file:
```bash
cp .env.example .env
```
Set your environment variables (e.g. `JWT_SECRET`, and optionally `GEMINI_API_KEY` for AI contract extraction).

### Running Backend Tests
```bash
cd backend
mvn clean test
```

### Running the Backend Service with Docker Services
Start the PostgreSQL, Redis, and Kafka containers:
```bash
docker compose -f infrastructure/docker-compose.yml up -d postgres redis kafka
```
Run the Spring Boot application:
```bash
cd backend
mvn spring-boot:run
```
- Health endpoint: `http://localhost:8080/api/v1/status`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

### Running the Frontend
```bash
cd frontend
npm install
npm run dev
```
- Frontend UI: `http://localhost:5173`