# Infrastructure Foundation

This directory manages the containerized infrastructure required for MockAPILab development and deployment.

## Services Overview

| Service | Technology | Port | Purpose in Architecture |
|---|---|---|---|
| **PostgreSQL** | PostgreSQL 16 (Alpine) | `5432` | Relational system of record (Projects, Contracts, Endpoints, Scenarios) |
| **Redis** | Redis 7 (Alpine) | `6379` | Fast in-memory state store for stateful mock execution and rate limiting |
| **Kafka** | Apache Kafka 3.7 (KRaft) | `9092` | Event streaming engine for async simulation telemetry and mock invocations |
| **Backend** | Java 21 + Spring Boot 3 | `8080` | Modular monolith backend runtime (profile: `full-stack`) |
| **Frontend** | React 18 + Vite + TS | `5173` | Interactive configuration and mock monitoring dashboard (profile: `full-stack`) |

## Quickstart

### 1. Start Supporting Data Services Only (Recommended for local dev)
To run PostgreSQL, Redis, and Kafka in containers while running the Backend and Frontend locally:
```bash
docker compose -f infrastructure/docker-compose.yml up -d postgres redis kafka
```

### 2. Verify Running Containers
```bash
docker compose -f infrastructure/docker-compose.yml ps
```

### 3. Stop Infrastructure
```bash
docker compose -f infrastructure/docker-compose.yml down
```

### 4. Stop Infrastructure and Remove Volumes
```bash
docker compose -f infrastructure/docker-compose.yml down -v
```
