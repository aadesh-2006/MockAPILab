# MockAPILab

> Intelligent, stateful mock backend engine for modern frontend and full-stack development.

[![Backend Build](https://img.shields.io/badge/backend-Spring%20Boot%203%20%7C%20Java%2021-brightgreen.svg)](backend/)
[![Frontend Build](https://img.shields.io/badge/frontend-React%2018%20%7C%20TypeScript%20%7C%20Vite-blue.svg)](frontend/)
[![Architecture](https://img.shields.io/badge/architecture-Modular%20Monolith-orange.svg)](docs/architecture.md)
[![Database](https://img.shields.io/badge/database-PostgreSQL%2016%20%7C%20JSONB%20%7C%20Flyway-blue.svg)](backend/src/main/resources/db/migration/)
[![OpenAPI](https://img.shields.io/badge/OpenAPI-3.x%20Normalized%20Engine-brightgreen.svg)](docs/examples/sample-users-api.yaml)

---

## 1. What is MockAPILab?

**MockAPILab** is a developer productivity platform that transforms API contracts, OpenAPI specifications, or backend controller/model definitions into a locally runnable, realistic, and **stateful** mock backend. 

Unlike traditional static mock servers that only return fixed JSON snippets, MockAPILab maintains contextual state, simulates multi-step business workflows (e.g., Create $\rightarrow$ Update $\rightarrow$ Query $\rightarrow$ Delete), introduces controlled network latencies and failure scenarios, and accelerates schema onboarding using AI.

---

## 2. The Problem It Solves

Modern development teams frequently face blocking dependencies between frontend and backend workflows:

- **Backend Bottlenecks:** Frontend teams are delayed waiting for backend APIs to be designed, deployed, and stabilized.
- **Unrealistic Static Mocks:** Existing mocking tools return static, stateless fixtures. They fail to test real-world scenarios such as pagination state, entity mutation, conditional errors, or race conditions.
- **Contract Drift:** Hand-written mock configurations drift rapidly from changing backend specifications.
- **Manual Overhead:** Writing mock routes and state machines by hand is tedious and error-prone.

**MockAPILab bridges this gap** by automating contract ingestion (including AI-powered extraction from code snippets), compiling stateful routes, and providing isolated, persistent or ephemeral mock environments for developers and automated tests.

---

## 3. High-Level Architecture

MockAPILab is built as a clean **Modular Monolith** designed for high throughput, operational simplicity, and clear module separation.

```mermaid
flowchart TD
    subgraph Client ["Client Layer"]
        UI["React + TypeScript UI\n(Management & Scenario Studio)"]
        DevApp["Frontend App Under Dev\n(Calling Mock Endpoints)"]
    end

    subgraph Backend ["MockAPILab Backend (Spring Boot 3 + Java 21)"]
        API["REST & Admin API"]
        AuthModule["Auth & Security Engine (JWT)"]
        ProjectModule["Project Workspace Engine"]
        ContractModule["Contract Engine (Parser & Normalizer)"]
        Runtime["Stateful Mock Dispatch Engine (Future)"]
        AILayer["Contract Extractor (Gemini API) (Future)"]
        ScenarioManager["Scenario & State Engine (Future)"]
    end

    subgraph Infrastructure ["Infrastructure Layer"]
        PG[("PostgreSQL 16\n(Users, Projects, Contracts JSONB)")]
        Redis[("Redis 7\n(Stateful Mock State & Caching) (Future)")]
        Kafka[("Apache Kafka\n(Event Stream & Telemetry) (Future)")]
    end

    UI -->|Authenticate, Manage Projects & Ingest Contracts| API
    DevApp -->|Execute Mock Requests| Runtime
    API --> AuthModule
    API --> ProjectModule
    API --> ContractModule
    ContractModule --> PG
    ProjectModule --> PG
    AuthModule --> PG
    Runtime --> Redis
    Runtime --> Kafka
    AILayer -.->|Infers Schemas & Seed Data| ContractModule
    ScenarioManager --> Redis
```

---

## 4. Normalized Contract Engine (Milestone 3)

The core architectural foundation of MockAPILab is its **Canonical Normalized Contract Model**. Downstream mocking engines, dynamic data generators, and stateful scenario managers never depend directly on external OpenAPI formats.

```
OpenAPI 3.x (JSON/YAML) ──┐
                          │
Spring/Express AST + AI ──┼──> [OpenApi / AST Parser] ──> NormalizedContract ──> JSONB Storage
                          │                                        │
Natural Language Specs ───┘                                        ├──> Dynamic Mock Engine (M4)
                                                                   ├──> Stateful Scenarios (M4)
                                                                   └──> Contract Diffing (Future)
```

### Supported OpenAPI 3.x Subset
- **Formats:** JSON and YAML
- **Operations:** `GET`, `POST`, `PUT`, `DELETE`, `PATCH`, `OPTIONS`, `HEAD`
- **Parameters:** `PATH`, `QUERY`, `HEADER`, `COOKIE` (with types, formats, required flags)
- **Request Body & Responses:** Multi-media type content (`application/json`), status codes, headers
- **Schemas:** Primitive types (`string`, `integer`, `number`, `boolean`), formatted types (`uuid`, `email`, `date-time`), nested `object` properties, `array` with item schemas, `enum` constants, and local `#/components/schemas/*` `$ref` resolution.

---

## 5. Available REST API Endpoints

| Category | Method | Endpoint | Access | Description |
|---|---|---|---|---|
| **Health** | `GET` | `/api/v1/status` | Public | System status and service health |
| **Docs** | `GET` | `/swagger-ui.html` | Public | Interactive Swagger API Documentation |
| **Docs** | `GET` | `/v3/api-docs` | Public | OpenAPI specification for MockAPILab |
| **Auth** | `POST` | `/api/v1/auth/register` | Public | Register a new user and receive a JWT token |
| **Auth** | `POST` | `/api/v1/auth/login` | Public | Authenticate user credentials and receive a JWT token |
| **Auth** | `GET` | `/api/v1/auth/me` | Protected | Retrieve authenticated user profile |
| **Projects** | `POST` | `/api/v1/projects` | Protected | Create a new project workspace (owner assigned) |
| **Projects** | `GET` | `/api/v1/projects` | Protected | List all project workspaces owned by caller |
| **Projects** | `GET` | `/api/v1/projects/{id}` | Protected | Retrieve project workspace details (owner only) |
| **Projects** | `PUT` | `/api/v1/projects/{id}` | Protected | Update project name / description (owner only) |
| **Projects** | `DELETE` | `/api/v1/projects/{id}` | Protected | Delete project workspace (owner only) |
| **Contracts**| `POST` | `/api/v1/projects/{projectId}/contracts` | Protected | Ingest OpenAPI 3.x contract & create version 1 |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts` | Protected | List contracts belonging to project (owner only) |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts/{contractId}` | Protected | Get contract details & latest version stats |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts/{contractId}/versions` | Protected | List all versions for a contract |
| **Contracts**| `GET` | `/api/v1/projects/{projectId}/contracts/{contractId}/versions/{versionNumber}` | Protected | Retrieve full `NormalizedContract` JSON definition |

> **Security Note:** All protected endpoints require a valid Bearer token in the `Authorization` header: `Authorization: Bearer <jwt_token>`. Project and contract access is strictly isolated per owner on the server side (attempting to access another user's project/contract returns `403 Forbidden`).

---

## 6. Technology Stack

| Layer | Technologies | Purpose |
|---|---|---|
| **Core Backend** | Java 21, Spring Boot 3.4, Maven | Modular monolith backend runtime, REST API, mock engine |
| **Security & Auth** | Spring Security 6, JJWT 0.12, BCrypt | Stateless JWT authentication, role & ownership authorization |
| **Contract Engine** | SwaggerParser 2.1, Jackson YAML, SpringDoc OpenAPI | OpenAPI 3.x parser, validation, schema normalizer, Swagger UI |
| **Database & ORM** | PostgreSQL 16, Spring Data JPA, Hibernate (JSONB) | Relational persistence + JSONB normalized contract snapshots |
| **Schema Migrations**| Flyway Migration Engine | Deterministic, version-controlled database migrations (`V1`, `V2`) |
| **Frontend** | React 18, TypeScript, Vite | Developer dashboard, schema visualizer, contract ingestion preview |
| **State & Cache** | Redis 7 (Planned M4) | High-speed transient state storage for dynamic mocks |
| **Event Streaming**| Apache Kafka 3.7 (KRaft) (Planned M4/M6) | Asynchronous invocation telemetry and event-driven mocking |
| **AI Integration** | Google Gemini API (Planned M5) | Automated contract and schema extraction from raw source code |
| **Containers** | Docker, Docker Compose | Reproducible local and CI/CD development environment |
| **Testing** | JUnit 5, Mockito, MockMvc, H2 | Comprehensive automated testing suite (34 tests) |

---

## 7. Current Milestone & Status

### 📍 Milestone 1: Project Foundation (Complete)
- [x] Initialized clean project repository structure (`backend/`, `frontend/`, `infrastructure/`, `docs/`).
- [x] Established Java 21 Spring Boot 3 modular monolith base and package structure.
- [x] Configured Docker Compose infrastructure definitions for PostgreSQL, Redis, and Kafka.

### 📍 Milestone 2: Identity + Project Management (Complete)
- [x] Integrated PostgreSQL with Spring Data JPA (`User` and `Project` entities with UUIDs).
- [x] Configured Flyway database migrations (`V1__init_users_and_projects.sql`).
- [x] Implemented stateless JWT authentication and BCrypt password hashing (`/register`, `/login`, `/me`).
- [x] Implemented project workspace CRUD with strict server-side owner isolation.

### 📍 Milestone 3: Contract Ingestion + Normalized Contract Engine (Complete)
- [x] Designed canonical `NormalizedContract` model (`endpoints`, `schemas`, `parameters`, `requestBody`, `responses`).
- [x] Implemented OpenAPI 3.x Parser supporting JSON & YAML, local `$ref` resolution, enums, and nested types.
- [x] Added contract validation rejecting malformed specs, non-OpenAPI 3.x versions, and broken references.
- [x] Created Flyway migration `V2__init_contracts_and_versions.sql` storing normalized definitions in `JSONB`.
- [x] Built contract ingestion and versioned retrieval REST APIs (`/api/v1/projects/{projectId}/contracts/**`).
- [x] Added sample OpenAPI specification in [`docs/examples/sample-users-api.yaml`](docs/examples/sample-users-api.yaml).
- [x] Exposed OpenAPI/Swagger documentation at `/swagger-ui.html`.
- [x] Built 34 automated unit and integration tests.

---

## 8. Planned Development Phases

```
┌─────────────────────────────────────────────────────────────┐
│ Milestone 1: Project Foundation (Complete)                  │
├─────────────────────────────────────────────────────────────┤
│ Milestone 2: Identity + Project Management (Complete)       │
├─────────────────────────────────────────────────────────────┤
│ Milestone 3: Contract Ingestion & Normalization (Complete)  │
├─────────────────────────────────────────────────────────────┤
│ Milestone 4: Dynamic Mock Generation & Redis Stateful State │
├─────────────────────────────────────────────────────────────┤
│ Milestone 5: AI-Assisted Contract Extraction (Gemini API)   │
├─────────────────────────────────────────────────────────────┤
│ Milestone 6: Interactive Frontend Studio & Telemetry Stream │
└─────────────────────────────────────────────────────────────┘
```

For detailed architectural rationale and module breakdowns, see [docs/architecture.md](docs/architecture.md).

---

## 9. Quick Start (Local Development)

### Prerequisites
- **Java 21+** (JDK 21 or higher)
- **Maven 3.9+**
- **Node.js 20+** and **npm**
- **Docker & Docker Compose** (optional for local database container)

### Environment Configuration
Copy the template environment file:
```bash
cp .env.example .env
```
Ensure `JWT_SECRET` is set to a secure string of at least 32 characters.

### Running Backend Tests
```bash
cd backend
mvn clean test
```

### Running the Backend Service
Start the PostgreSQL container:
```bash
docker compose -f infrastructure/docker-compose.yml up -d postgres
```
Run the Spring Boot application:
```bash
cd backend
mvn spring-boot:run
```
- Health endpoint: `http://localhost:8080/api/v1/status`
- Swagger UI: `http://localhost:8080/swagger-ui.html`

### Ingesting Sample Contract
You can ingest the provided sample contract [`docs/examples/sample-users-api.yaml`](docs/examples/sample-users-api.yaml) via Swagger UI or cURL:
```bash
# 1. Register or login to obtain JWT token
# 2. Create project to obtain projectId
# 3. Ingest contract:
curl -X POST http://localhost:8080/api/v1/projects/<projectId>/contracts \
  -H "Authorization: Bearer <jwt_token>" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "Users Management API",
    "description": "User CRUD specification",
    "content": "'"$(cat docs/examples/sample-users-api.yaml)"'"
  }'
```

### Running the Frontend
```bash
cd frontend
npm install
npm run dev
```
*Frontend UI:* `http://localhost:5173`