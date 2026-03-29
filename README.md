# Loom Link - Sovereign Intelligence Layer

**Nordic Energy Matchbox 2026 | Challenge 01: Maintenance Optimization**
**Equinor & Aker BP | Stavanger Demo - April 22, 2026**

---

## The Problem

North Sea platforms generate thousands of free-text maintenance notifications daily. Technicians under field conditions write entries like *"pump making loud grinding noise, bearing temp 82C"* into SAP. These unstructured descriptions create a 30% man-hour overhead in manual classification and prevent automated predictive maintenance.

## The Solution

Loom Link is a Sovereign Intelligence Layer that intercepts free-text SAP notifications at the point of creation, classifies them into ISO 14224 failure codes using a locally-hosted LLM, validates every decision through a deterministic governance gate, and writes structured codes back to SAP - all with zero cloud dependency.

## Architecture

```
                    SOVEREIGN NODE (HM90 Ryzen 9)
                    ================================
                    100% Local | Zero Data Egress

    SAP OData Stream                              SAP BAPI Write-Back
         |                                              ^
         v                                              |
  +------------------+    +-------------------+    +------------------+
  | 1. NOTIFICATION  |    | 2. SEMANTIC CACHE |    | 5. SAP GATEWAY   |
  |    INTAKE        |--->|    (pgvector)     |--->|    (BAPI Sim)    |
  |                  |    |    >95% = bypass  |    |    + DLQ Retry   |
  +------------------+    +-------------------+    +------------------+
                               |  miss               ^
                               v                     |
                    +-------------------+    +------------------+
                    | 3. SEMANTIC       |    | 4. REFLECTOR     |
                    |    ENGINE         |--->|    GATE           |
                    |    (Mistral 7B)   |    |    >= 0.85 pass  |
                    |    + JSON Schema  |    |    < 0.85 reject |
                    +-------------------+    +------------------+
                                                   |  reject
                                                   v
                                            +------------------+
                                            | EXCEPTION INBOX  |
                                            | (Human Review)   |
                                            | RBAC-Protected   |
                                            +------------------+

         Every decision logged to IMMUTABLE AUDIT TRAIL
```

## Architectural Pillars

| Pillar | Implementation |
|--------|---------------|
| **100% Local Execution** | Mistral 7B on Ollama, PostgreSQL with pgvector, all on HM90 Ryzen 9. No cloud, no data egress. |
| **SAP Clean Core** | Integration via certified BAPIs (BAPI_ALM_NOTIF_DATA_MODIFY). Zero custom ABAP. Zero ERP modifications. |
| **Deterministic Governance** | Reflector Gate enforces binary pass/fail at 0.85 confidence. No probabilistic middle ground. |
| **Full Auditability** | Immutable audit log for every classification: original text, model version, confidence, reasoning, gate verdict, SAP write-back status. |

## Pipeline Flow

1. **Notification Intake** - Intercept free-text from SAP OData notification stream
2. **Semantic Cache Lookup** - Two-tier cache (exact match + trigram >95% similarity) bypasses LLM (~50ms vs 2-8s)
3. **Semantic Engine** - Mistral 7B classifies free-text to ISO 14224 failure codes with JSON schema validation + 1-retry fallback
4. **Reflector Gate** - Deterministic governance: confidence >= 0.85 AND valid failure code required for SAP write-back
5. **SAP Write-Back** - Structured code written via BAPI simulation with Dead Letter Queue for failed attempts
6. **Audit Trail** - Every decision logged immutably with full chain-of-custody

## Features

### Core Pipeline
- Free-text to ISO 14224 classification via local LLM
- JSON Schema Validator with 1-retry fallback for LLM output
- Reflector Gate deterministic governance (configurable threshold)
- SAP BAPI write-back simulation (Clean Core compliant)

### Predictive Intelligence
- 72-hour RUL (Remaining Useful Life) forecasting
- ISO 10816 vibration severity assessment
- Fleet sibling statistical analysis
- Arrhenius temperature degradation modeling

### Prescriptive Actions
- Next Best Action recommendations with parts/tools lists
- Smart Swap (1oo2 redundancy assessment)
- Priority-based maintenance scheduling

### Enterprise Architecture
- Semantic Cache with pgvector (>95% trigram match bypasses LLM)
- Dead Letter Queue with exponential backoff retry (30s to 480s)
- Role-Based Access Control (SENIOR_ENGINEER, OPERATOR, TECHNICIAN, ADMIN)
- Immutable Audit Trail with full LLM reasoning chain + CSV export
- Exception Inbox for human review of rejected classifications
- Batch Processing for shift-level notification ingestion
- Pipeline Analytics with operational savings metrics
- Sovereign Node Health Monitor

### Production-Grade Patterns
- **Experience Bank Feedback Loop** — Human corrections in Exception Inbox are promoted to semantic cache, enabling continuous improvement without LLM retraining
- **Swagger/OpenAPI** — Interactive API documentation at `/swagger-ui.html`
- **Prometheus Metrics** — Micrometer counters/timers at `/actuator/prometheus` for Grafana dashboards
- **Correlation ID Tracing** — Every request gets a unique `X-Trace-Id` header for distributed tracing
- **Global Exception Handler** — Structured JSON error responses, no stack traces leak to consumers
- **Startup Health Validation** — Validates Ollama, PostgreSQL, and pgvector connectivity on boot
- **CSV Audit Export** — One-click compliance export with Oslo timezone timestamps
- **Custom Pipeline Metrics** — gate pass/reject counters, cache hit/miss rates, inference latency percentiles

### Dashboard (8 Tabs)
- Fleet Overview with RUL forecasts for all assets
- Asset Detail with vibration trends (Chart.js)
- Pipeline Test console with sample notifications
- Exception Inbox with approve/reclassify/dismiss workflow + feedback loop
- Scale & Resilience monitoring (cache, DLQ, RBAC, feedback stats)
- Audit Trail viewer with filtering + CSV export
- Analytics KPIs (pass rate, latency, cache efficiency, man-hours saved)
- Sovereign Health status (LLM, PostgreSQL, SAP Gateway, config)

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Runtime | Java 21, Spring Boot 3.2.5 |
| LLM Integration | LangChain4j 0.35.0 + Ollama |
| LLM Model | Mistral 7B (local on HM90) |
| Database | PostgreSQL with pgvector |
| Standards | ISO 14224 (failure taxonomy), ISO 10816 (vibration) |
| SAP Integration | BAPI simulation (production: JCo/OData) |
| API Docs | SpringDoc OpenAPI 2.3.0 (Swagger UI) |
| Observability | Micrometer + Prometheus, MDC correlation IDs |
| Frontend | Vanilla JS, Chart.js, CSS Grid |
| Infrastructure | Docker Compose, HM90 Ryzen 9 |

## Quick Start

### Prerequisites
- Java 21
- Docker & Docker Compose
- Ollama with Mistral 7B pulled

### Start PostgreSQL
```bash
docker-compose up -d
```

### Pull the model
```bash
ollama pull mistral:7b
```

### Run the application
```bash
./mvnw spring-boot:run
```

### Access the system
- Dashboard: http://localhost:8080
- Swagger UI: http://localhost:8080/swagger-ui.html
- Prometheus: http://localhost:8080/actuator/prometheus

## API Endpoints

### Pipeline
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/notifications` | Process a single notification |
| POST | `/api/v1/pipeline/batch` | Process a batch of notifications |

### Dashboard
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/dashboard/assets` | Fleet asset list |
| GET | `/api/v1/dashboard/rul/{tag}` | RUL forecast for asset |
| GET | `/api/v1/dashboard/rul` | All RUL forecasts |
| GET | `/api/v1/dashboard/prescriptive/{tag}` | Next Best Action |
| GET | `/api/v1/dashboard/vibration/{tag}` | Vibration trend data |
| GET | `/api/v1/dashboard/failures/{tag}` | Failure history |
| GET | `/api/v1/dashboard/stats` | Experience Bank statistics |
| POST | `/api/v1/dashboard/sensor/vibration` | Ingest sensor reading |

### Exception Inbox
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/exceptions` | List exceptions (filterable) |
| GET | `/api/v1/exceptions/stats` | Inbox statistics |
| POST | `/api/v1/exceptions/{id}/approve` | Approve classification |
| POST | `/api/v1/exceptions/{id}/reclassify` | Reclassify with new code |
| POST | `/api/v1/exceptions/{id}/dismiss` | Dismiss notification |

### Audit & Analytics
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/audit/logs` | Recent audit records |
| GET | `/api/v1/audit/stats` | Audit statistics |
| GET | `/api/v1/analytics/kpis` | Pipeline KPI metrics |

### Resilience & Health
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/resilience/cache/stats` | Semantic cache metrics |
| GET | `/api/v1/resilience/dlq/stats` | Dead Letter Queue metrics |
| GET | `/api/v1/resilience/dlq/items` | DLQ item list |
| GET | `/api/v1/resilience/rbac/users` | RBAC user roles |
| GET | `/api/v1/resilience/feedback/stats` | Feedback loop metrics |
| GET | `/api/v1/health/sovereign` | Sovereign node health |

### Compliance
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/audit/export/csv` | Export audit trail as CSV |

### Demo Control
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/demo/status` | Demo mode status |
| POST | `/api/v1/demo/toggle` | Toggle demo mode |

## Testing

```bash
./mvnw test
```

Test coverage includes:
- Reflector Gate deterministic governance (12 test cases)
- LLM Response Validator JSON schema enforcement (16 test cases)
- RBAC access control verification (8 test cases)

## Project Structure

```
src/main/java/com/loomlink/edge/
|-- LoomLinkEdgeApplication.java
|-- config/           # OpenAPI, CORS, metrics, tracing, error handling, startup validation
|-- controller/       # REST API endpoints (9 controllers)
|-- domain/
|   |-- enums/        # FailureModeCode, EquipmentClass, NotificationStatus
|   |-- model/        # JPA entities (10 domain models)
|-- gateway/          # SAP BAPI integration
|-- repository/       # Spring Data JPA repositories (7 repos)
|-- service/          # Business logic (11 services incl. feedback loop)
```

## License

Proprietary - Nordic Energy Matchbox 2026 Competition Entry
