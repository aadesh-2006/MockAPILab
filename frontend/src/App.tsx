import { useState, useEffect } from 'react'
import './App.css'

interface SystemStatus {
  status: string
  service: string
  version: string
  javaVersion: string
  springBootVersion: string
  activeProfiles: string[]
  environment: string
  timestamp: string
}

interface ActuatorHealth {
  status: string
  components?: {
    db?: { status: string }
    livenessState?: { status: string }
    readinessState?: { status: string }
    runtimeStateStore?: { status: string; details?: { stateStoreType?: string; implementation?: string; status?: string } }
    redis?: { status: string }
  }
}

interface GenerationJobInfo {
  jobId: string
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED'
  collection: string
  count: number
  requestedSeed?: number | null
  effectiveSeed?: number | null
  errorMessage?: string | null
  createdAt?: string
  startedAt?: string
  completedAt?: string
}

interface ScenarioItem {
  id: string
  runtimeId: string
  projectId: string
  name: string
  description?: string
  status: 'ACTIVE' | 'DISABLED'
  pathPattern: string
  httpMethod?: string | null
  action: 'FORCE_STATUS' | 'DELAY' | 'RANDOM_FAILURE'
  statusCode?: number | null
  delayMs?: number | null
  probabilityPercent?: number | null
  maxExecutions?: number | null
  executionCount: number
  createdAt: string
  updatedAt: string
}

interface DriftChange {
  id: string
  changeType: string
  classification: 'BREAKING' | 'NON_BREAKING' | 'INFORMATIONAL'
  severity: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  path?: string | null
  method?: string | null
  location?: string | null
  oldValue?: string | null
  newValue?: string | null
  message: string
}

interface DriftReport {
  id: string
  projectId: string
  contractId: string
  fromVersionId: string
  toVersionId: string
  fromVersionNumber: number
  toVersionNumber: number
  breakingChangeCount: number
  nonBreakingChangeCount: number
  informationalChangeCount: number
  overallSeverity: 'NONE' | 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  createdAt: string
  changes: DriftChange[]
}

function App() {
  const [status, setStatus] = useState<SystemStatus | null>(null)
  const [actuatorHealth, setActuatorHealth] = useState<ActuatorHealth | null>(null)
  const [lastCorrelationId, setLastCorrelationId] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Auth & Project Context State
  const [jwtToken, setJwtToken] = useState('')
  const [projectId, setProjectId] = useState('')

  // Contract Ingestion State (M3)
  const [contractName, setContractName] = useState('Users API')
  const [contractContent, setContractContent] = useState(`openapi: 3.0.3
info:
  title: Users Service API
  version: 1.0.0
paths:
  /users:
    get:
      summary: List all users
      responses:
        '200':
          description: A list of users
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/User'
    post:
      summary: Create a user
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/User'
      responses:
        '201':
          description: User created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/User'
  /users/{id}:
    get:
      summary: Get user by ID
      parameters:
        - name: id
          in: path
          required: true
          schema:
            type: string
      responses:
        '200':
          description: User found
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/User'
        '404':
          description: User not found
components:
  schemas:
    User:
      type: object
      required:
        - name
        - email
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        email:
          type: string
          format: email
        phone:
          type: string
        role:
          type: string
          enum: [ADMIN, DEVELOPER, USER]
`)
  const [ingestStatus, setIngestStatus] = useState<string | null>(null)
  const [ingestLoading, setIngestLoading] = useState(false)
  const [ingestedContractId, setIngestedContractId] = useState<string | null>(null)

  // AI-Assisted Contract Extraction State (M8)
  const [aiInputType, setAiInputType] = useState<'DESCRIPTION' | 'SPRING_BOOT_CODE'>('DESCRIPTION')
  const [aiContractName, setAiContractName] = useState('AI Extracted Service')
  const [aiInput, setAiInput] = useState(`Create a user management API.

GET /users - Returns a list of users.
GET /users/{id} - Returns one user by UUID id.
POST /users - Creates a new user with name, email, phone, and role (enum: ADMIN, DEVELOPER, USER).
DELETE /users/{id} - Deletes a user by UUID id.

User model:
id: uuid (required)
name: string (required)
email: email (required)
phone: string
role: enum [ADMIN, DEVELOPER, USER]`)
  const [aiStatus, setAiStatus] = useState<string | null>(null)
  const [aiLoading, setAiLoading] = useState(false)
  const [aiExtractedSummary, setAiExtractedSummary] = useState<{ endpoints: number; schemas: number; title: string } | null>(null)

  // Runtime State (M4/M6)
  const [runtimeId, setRuntimeId] = useState('')
  const [runtimeStatus, setRuntimeStatus] = useState<string | null>(null)
  const [runtimeLoading, setRuntimeLoading] = useState(false)

  // Asynchronous Data Generation State (M7 Kafka Jobs)
  const [genCollection, setGenCollection] = useState('/users')
  const [genCount, setGenCount] = useState(5)
  const [genSeed, setGenSeed] = useState('42')
  const [genStatus, setGenStatus] = useState<string | null>(null)
  const [genLoading, setGenLoading] = useState(false)
  const [activeJob, setActiveJob] = useState<GenerationJobInfo | null>(null)

  // Scenario Studio & Failure Injector State (M9)
  const [scenarios, setScenarios] = useState<ScenarioItem[]>([])
  const [scenarioName, setScenarioName] = useState('Auth 401 Unauthorized')
  const [scenarioDesc, setScenarioDesc] = useState('Always return 401 for /users endpoint')
  const [scenarioPath, setScenarioPath] = useState('/users')
  const [scenarioMethod, setScenarioMethod] = useState('GET')
  const [scenarioAction, setScenarioAction] = useState<'FORCE_STATUS' | 'DELAY' | 'RANDOM_FAILURE'>('FORCE_STATUS')
  const [scenarioStatusCode, setScenarioStatusCode] = useState<number>(401)
  const [scenarioDelayMs, setScenarioDelayMs] = useState<number>(2000)
  const [scenarioProbability, setScenarioProbability] = useState<number>(25)
  const [scenarioMaxExecutions, setScenarioMaxExecutions] = useState<string>('')
  const [scenarioLoading, setScenarioLoading] = useState(false)
  const [scenarioStatus, setScenarioStatus] = useState<string | null>(null)

  // Contract Drift Detection State (M10)
  const [driftContractId, setDriftContractId] = useState('')
  const [driftFromVersion, setDriftFromVersion] = useState<number>(1)
  const [driftToVersion, setDriftToVersion] = useState<number>(2)
  const [driftLoading, setDriftLoading] = useState(false)
  const [driftStatus, setDriftStatus] = useState<string | null>(null)
  const [currentDriftReport, setCurrentDriftReport] = useState<DriftReport | null>(null)
  const [pastDriftReports, setPastDriftReports] = useState<DriftReport[]>([])
  const [driftFilter, setDriftFilter] = useState<'ALL' | 'BREAKING' | 'NON_BREAKING' | 'INFORMATIONAL'>('ALL')
  const [newVersionContent, setNewVersionContent] = useState(`openapi: 3.0.3
info:
  title: Users Service API
  version: 2.0.0
paths:
  /users:
    get:
      summary: List all users
      parameters:
        - name: apiKey
          in: header
          required: true
          schema:
            type: string
      responses:
        '200':
          description: A list of users
          content:
            application/json:
              schema:
                type: array
                items:
                  $ref: '#/components/schemas/User'
    post:
      summary: Create a user
      requestBody:
        required: true
        content:
          application/json:
            schema:
              $ref: '#/components/schemas/User'
      responses:
        '201':
          description: User created
          content:
            application/json:
              schema:
                $ref: '#/components/schemas/User'
  /orders:
    get:
      summary: List orders
      responses:
        '200':
          description: List of orders
components:
  schemas:
    User:
      type: object
      required:
        - name
        - email
      properties:
        id:
          type: string
          format: uuid
        name:
          type: string
        email:
          type: string
          format: email
        department:
          type: string
        role:
          type: string
          enum: [ADMIN, DEVELOPER, USER, GUEST]
`)
  const [newVersionLoading, setNewVersionLoading] = useState(false)

  // Live Mock Dispatch Tester State
  const [mockPath, setMockPath] = useState('/users')
  const [mockMethod, setMockMethod] = useState('GET')
  const [mockBody, setMockBody] = useState('{\n  "name": "Priya Sharma",\n  "email": "priya.sharma@example.com",\n  "phone": "+91-9876543210",\n  "role": "ADMIN"\n}')
  const [mockResponse, setMockResponse] = useState<string | null>(null)
  const [mockLoading, setMockLoading] = useState(false)

  useEffect(() => {
    fetch('/api/v1/status')
      .then((res) => {
        const reqId = res.headers.get('X-Request-Id')
        if (reqId) setLastCorrelationId(reqId)
        if (!res.ok) {
          throw new Error(`HTTP error! status: ${res.status}`)
        }
        return res.json()
      })
      .then((data) => {
        setStatus(data.data)
        setLoading(false)
      })
      .catch((err) => {
        setError(err.message)
        setLoading(false)
      })

    fetch('/actuator/health')
      .then((res) => res.json())
      .then((data) => setActuatorHealth(data))
      .catch(() => {})
  }, [])

  const handleIngest = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId || !contractContent || !contractName) {
      setIngestStatus('Please provide Project ID, Contract Name, and OpenAPI content.')
      return
    }

    setIngestLoading(true)
    setIngestStatus(null)

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }

      const res = await fetch(`/api/v1/projects/${projectId.trim()}/contracts`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          name: contractName,
          content: contractContent,
          sourceType: 'OPENAPI',
        }),
      })

      const data = await res.json()
      if (res.ok) {
        setIngestedContractId(data.data.id)
        setDriftContractId(data.data.id)
        setIngestStatus(`Success: Ingested "${data.data.name}" (ID: ${data.data.id}, Version ${data.data.latestVersion}, ${data.data.totalEndpoints} endpoints)`)
      } else {
        setIngestStatus(`Error (${res.status}): ${data.message || JSON.stringify(data.data)}`)
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setIngestStatus(`Network Error: ${errorMessage}`)
    } finally {
      setIngestLoading(false)
    }
  }

  const handleSwitchAiPreset = (type: 'DESCRIPTION' | 'SPRING_BOOT_CODE') => {
    setAiInputType(type)
    if (type === 'DESCRIPTION') {
      setAiContractName('Users API (AI)')
      setAiInput(`Create a user management API.

GET /users - Returns a list of users.
GET /users/{id} - Returns one user by UUID id.
POST /users - Creates a new user with name, email, phone, and role (enum: ADMIN, DEVELOPER, USER).
DELETE /users/{id} - Deletes a user by UUID id.

User model:
id: uuid (required)
name: string (required)
email: email (required)
phone: string
role: enum [ADMIN, DEVELOPER, USER]`)
    } else {
      setAiContractName('Product Catalog API (AI)')
      setAiInput(`@RestController
@RequestMapping("/products")
public class ProductController {

    @GetMapping
    public List<Product> listProducts() {
        return List.of();
    }

    @GetMapping("/{id}")
    public Product getProduct(@PathVariable UUID id) {
        return null;
    }

    @PostMapping
    public Product createProduct(@RequestBody CreateProductRequest request) {
        return null;
    }

    @DeleteMapping("/{id}")
    public void deleteProduct(@PathVariable UUID id) {
    }
}

public record Product(UUID id, String title, Double price, String category, Boolean inStock) {}
public record CreateProductRequest(String title, Double price, String category) {}`)
    }
  }

  const handleAiExtract = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId || !aiInput) {
      setAiStatus('Please provide Project ID and input content.')
      return
    }

    setAiLoading(true)
    setAiStatus(null)
    setAiExtractedSummary(null)

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }

      const res = await fetch(`/api/v1/projects/${projectId.trim()}/contracts/ai-extract`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          input: aiInput,
          inputType: aiInputType,
          name: aiContractName,
        }),
      })

      const data = await res.json()
      if (res.ok && data.data) {
        const result = data.data
        setIngestedContractId(result.contractId)
        setDriftContractId(result.contractId)
        setContractName(result.name)
        setAiExtractedSummary({
          endpoints: result.extractedEndpointsCount,
          schemas: result.extractedSchemasCount,
          title: result.candidateTitle || result.name,
        })
        setAiStatus(`Success: AI extracted "${result.name}" (ID: ${result.contractId}, Version ${result.version}, ${result.extractedEndpointsCount} endpoints, ${result.extractedSchemasCount} schemas)`)
      } else {
        setAiStatus(`Error (${res.status}): ${data.message || JSON.stringify(data.data)}`)
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setAiStatus(`Network Error: ${errorMessage}`)
    } finally {
      setAiLoading(false)
    }
  }

  const handleStartRuntime = async () => {
    if (!projectId || !ingestedContractId) {
      setRuntimeStatus('Please ingest a contract first or provide Project ID and Contract ID.')
      return
    }

    setRuntimeLoading(true)
    setRuntimeStatus(null)

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }

      const res = await fetch(`/api/v1/projects/${projectId.trim()}/contracts/${ingestedContractId}/versions/1/runtime`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          name: `${contractName} Runtime`,
        }),
      })

      const data = await res.json()
      if (res.ok) {
        setRuntimeId(data.data.id)
        setRuntimeStatus(`Runtime RUNNING! Base URL: ${data.data.mockBaseUrl} (${data.data.endpointsCount} endpoints compiled)`)
      } else {
        setRuntimeStatus(`Error (${res.status}): ${data.message || JSON.stringify(data.data)}`)
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setRuntimeStatus(`Network Error: ${errorMessage}`)
    } finally {
      setRuntimeLoading(false)
    }
  }

  const pollJobStatus = async (jobId: string, pId: string, rId: string, token?: string) => {
    const headers: Record<string, string> = {}
    if (token) {
      headers['Authorization'] = `Bearer ${token.trim()}`
    }

    try {
      const res = await fetch(`/api/v1/projects/${pId}/runtimes/${rId}/generation-jobs/${jobId}`, {
        headers,
      })
      const json = await res.json()
      if (res.ok && json.data) {
        const job: GenerationJobInfo = json.data
        setActiveJob(job)

        if (job.status === 'COMPLETED') {
          setGenStatus(`Success: Job COMPLETED! Generated ${job.count} entities in "${job.collection}" (Effective Seed: ${job.effectiveSeed})`)
          setGenLoading(false)
        } else if (job.status === 'FAILED') {
          setGenStatus(`Failed: Job execution error - ${job.errorMessage || 'Worker processing failed'}`)
          setGenLoading(false)
        } else {
          // Status is QUEUED or RUNNING -> continue polling
          setGenStatus(`Job Status: ${job.status}... (Job ID: ${job.jobId})`)
          setTimeout(() => pollJobStatus(jobId, pId, rId, token), 1000)
        }
      } else {
        setGenStatus(`Error polling job status (${res.status}): ${json.message || 'Unknown'}`)
        setGenLoading(false)
      }
    } catch (err: unknown) {
      const errMsg = err instanceof Error ? err.message : String(err)
      setGenStatus(`Polling error: ${errMsg}`)
      setGenLoading(false)
    }
  }

  const handleGenerateData = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId || !runtimeId || !genCollection) {
      setGenStatus('Please provide Project ID, Runtime ID, and Collection path.')
      return
    }

    setGenLoading(true)
    setGenStatus(null)
    setActiveJob(null)

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }

      const payload: { collection: string; count: number; seed?: number } = {
        collection: genCollection,
        count: genCount,
      }
      if (genSeed && genSeed.trim() !== '') {
        payload.seed = parseInt(genSeed.trim(), 10)
      }

      const res = await fetch(`/api/v1/projects/${projectId.trim()}/runtimes/${runtimeId.trim()}/data/generate`, {
        method: 'POST',
        headers,
        body: JSON.stringify(payload),
      })

      const data = await res.json()
      if (res.status === 202 || res.ok) {
        const job = data.data
        setActiveJob(job)
        setGenStatus(`Job Queued: ${job.status} (Job ID: ${job.jobId}). Dispatching to Kafka worker...`)
        // Start polling job status
        pollJobStatus(job.jobId, projectId.trim(), runtimeId.trim(), jwtToken)
      } else {
        setGenStatus(`Error (${res.status}): ${data.message || JSON.stringify(data.data)}`)
        setGenLoading(false)
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setGenStatus(`Network Error: ${errorMessage}`)
      setGenLoading(false)
    }
  }

  const handleSendMockRequest = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!runtimeId) {
      setMockResponse('Please start or specify an active Runtime ID first.')
      return
    }

    setMockLoading(true)
    setMockResponse(null)

    try {
      const normalizedSubPath = mockPath.startsWith('/') ? mockPath : `/${mockPath}`
      const url = `/mock/${runtimeId.trim()}${normalizedSubPath}`

      const options: RequestInit = {
        method: mockMethod,
        headers: {
          'Content-Type': 'application/json',
        },
      }

      if (['POST', 'PUT', 'PATCH'].includes(mockMethod) && mockBody) {
        options.body = mockBody
      }

      const res = await fetch(url, options)
      const text = await res.text()

      let parsed: unknown = text
      try {
        parsed = JSON.parse(text)
      } catch {
        // Leave as raw text
      }

      const formatted = typeof parsed === 'object' ? JSON.stringify(parsed, null, 2) : text
      setMockResponse(`HTTP ${res.status} ${res.statusText}\n\n${formatted}`)
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setMockResponse(`Request Failed: ${errorMessage}`)
    } finally {
      setMockLoading(false)
    }
  }

  const fetchScenarios = async (pId: string, rId: string, token?: string) => {
    if (!pId || !rId) return
    try {
      const headers: Record<string, string> = {}
      if (token) {
        headers['Authorization'] = `Bearer ${token.trim()}`
      }
      const res = await fetch(`/api/v1/projects/${pId.trim()}/runtimes/${rId.trim()}/scenarios`, { headers })
      const data = await res.json()
      if (res.ok && data.data) {
        setScenarios(data.data)
      }
    } catch (err) {
      console.error('Failed to fetch scenarios:', err)
    }
  }

  const handleApplyScenarioPreset = (preset: 'AUTH_401' | 'RATE_LIMIT_429' | 'FLAKY_500' | 'LATENCY_2000') => {
    switch (preset) {
      case 'AUTH_401':
        setScenarioName('Auth 401 Unauthorized')
        setScenarioDesc('Always return 401 Unauthorized for /users')
        setScenarioPath('/users')
        setScenarioMethod('GET')
        setScenarioAction('FORCE_STATUS')
        setScenarioStatusCode(401)
        setScenarioMaxExecutions('')
        break
      case 'RATE_LIMIT_429':
        setScenarioName('Rate Limit 429 Too Many Requests')
        setScenarioDesc('Return 429 for first 5 requests on /users')
        setScenarioPath('/users')
        setScenarioMethod('GET')
        setScenarioAction('FORCE_STATUS')
        setScenarioStatusCode(429)
        setScenarioMaxExecutions('5')
        break
      case 'FLAKY_500':
        setScenarioName('Server Degradation 500 (25%)')
        setScenarioDesc('Inject 500 Internal Error for 25% of requests on /users')
        setScenarioPath('/users')
        setScenarioMethod('GET')
        setScenarioAction('RANDOM_FAILURE')
        setScenarioStatusCode(500)
        setScenarioProbability(25)
        setScenarioMaxExecutions('')
        break
      case 'LATENCY_2000':
        setScenarioName('Network Latency 2000ms')
        setScenarioDesc('Add 2000ms latency to /users endpoint')
        setScenarioPath('/users')
        setScenarioMethod('GET')
        setScenarioAction('DELAY')
        setScenarioDelayMs(2000)
        setScenarioMaxExecutions('')
        break
    }
  }

  const handleCreateScenario = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId || !runtimeId || !scenarioName || !scenarioPath) {
      setScenarioStatus('Please specify Project ID, Runtime ID, Scenario Name, and Path.')
      return
    }

    setScenarioLoading(true)
    setScenarioStatus(null)

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }

      const payload: Record<string, unknown> = {
        name: scenarioName.trim(),
        description: scenarioDesc.trim(),
        status: 'ACTIVE',
        pathPattern: scenarioPath.trim(),
        httpMethod: scenarioMethod && scenarioMethod !== 'ALL' ? scenarioMethod.trim() : null,
        action: scenarioAction,
      }

      if (scenarioAction === 'FORCE_STATUS') {
        payload.statusCode = scenarioStatusCode
      } else if (scenarioAction === 'DELAY') {
        payload.delayMs = scenarioDelayMs
      } else if (scenarioAction === 'RANDOM_FAILURE') {
        payload.statusCode = scenarioStatusCode
        payload.probabilityPercent = scenarioProbability
      }

      if (scenarioMaxExecutions && scenarioMaxExecutions.trim() !== '') {
        const parsed = parseInt(scenarioMaxExecutions.trim(), 10)
        if (!isNaN(parsed) && parsed > 0) {
          payload.maxExecutions = parsed
        }
      }

      const res = await fetch(`/api/v1/projects/${projectId.trim()}/runtimes/${runtimeId.trim()}/scenarios`, {
        method: 'POST',
        headers,
        body: JSON.stringify(payload),
      })

      const data = await res.json()
      if (res.ok && data.data) {
        setScenarioStatus(`Success: Scenario "${data.data.name}" created (ID: ${data.data.id})`)
        fetchScenarios(projectId, runtimeId, jwtToken)
      } else {
        setScenarioStatus(`Error (${res.status}): ${data.message || JSON.stringify(data.data)}`)
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setScenarioStatus(`Network Error: ${errorMessage}`)
    } finally {
      setScenarioLoading(false)
    }
  }

  const handleToggleScenario = async (scenarioId: string, currentStatus: 'ACTIVE' | 'DISABLED') => {
    if (!projectId || !runtimeId) return
    const endpoint = currentStatus === 'ACTIVE' ? 'disable' : 'enable'
    try {
      const headers: Record<string, string> = {}
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }
      const res = await fetch(`/api/v1/projects/${projectId.trim()}/runtimes/${runtimeId.trim()}/scenarios/${scenarioId}/${endpoint}`, {
        method: 'POST',
        headers,
      })
      if (res.ok) {
        fetchScenarios(projectId, runtimeId, jwtToken)
      }
    } catch (err) {
      console.error('Failed to toggle scenario status:', err)
    }
  }

  const handleDeleteScenario = async (scenarioId: string) => {
    if (!projectId || !runtimeId) return
    try {
      const headers: Record<string, string> = {}
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }
      const res = await fetch(`/api/v1/projects/${projectId.trim()}/runtimes/${runtimeId.trim()}/scenarios/${scenarioId}`, {
        method: 'DELETE',
        headers,
      })
      if (res.ok) {
        fetchScenarios(projectId, runtimeId, jwtToken)
      }
    } catch (err) {
      console.error('Failed to delete scenario:', err)
    }
  }

  const handleAnalyzeDrift = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId || !driftContractId) {
      setDriftStatus('Please provide Project ID and Contract ID.')
      return
    }

    setDriftLoading(true)
    setDriftStatus(null)

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }

      const res = await fetch(`/api/v1/projects/${projectId.trim()}/contracts/${driftContractId.trim()}/drift`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          fromVersion: driftFromVersion,
          toVersion: driftToVersion,
        }),
      })

      const data = await res.json()
      if (res.ok && data.data) {
        setCurrentDriftReport(data.data)
        setDriftStatus(`Drift analysis completed successfully (Severity: ${data.data.overallSeverity})`)
        fetchDriftReports(projectId, driftContractId, jwtToken)
      } else {
        setDriftStatus(`Drift analysis failed: ${data.message || JSON.stringify(data.data)}`)
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setDriftStatus(`Request error: ${errorMessage}`)
    } finally {
      setDriftLoading(false)
    }
  }

  const fetchDriftReports = async (pId: string, cId: string, token?: string) => {
    if (!pId || !cId) return
    try {
      const headers: Record<string, string> = {}
      if (token) {
        headers['Authorization'] = `Bearer ${token.trim()}`
      }
      const res = await fetch(`/api/v1/projects/${pId.trim()}/contracts/${cId.trim()}/drift`, { headers })
      const data = await res.json()
      if (res.ok && data.data) {
        setPastDriftReports(data.data)
      }
    } catch (err) {
      console.error('Failed to fetch drift reports:', err)
    }
  }

  const handlePublishNewVersion = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!projectId || !driftContractId || !newVersionContent) {
      setDriftStatus('Please provide Project ID, Contract ID, and Version content.')
      return
    }

    setNewVersionLoading(true)
    setDriftStatus(null)

    try {
      const headers: Record<string, string> = {
        'Content-Type': 'application/json',
      }
      if (jwtToken) {
        headers['Authorization'] = `Bearer ${jwtToken.trim()}`
      }

      const res = await fetch(`/api/v1/projects/${projectId.trim()}/contracts/${driftContractId.trim()}/versions`, {
        method: 'POST',
        headers,
        body: JSON.stringify({
          name: `${contractName} v${driftToVersion}`,
          content: newVersionContent,
          sourceType: 'OPENAPI',
        }),
      })

      const data = await res.json()
      if (res.ok && data.data) {
        setDriftStatus(`Success: Ingested new Contract Version ${data.data.versionNumber}! You can now run drift analysis.`)
      } else {
        setDriftStatus(`Error ingesting version (${res.status}): ${data.message || JSON.stringify(data.data)}`)
      }
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setDriftStatus(`Network Error: ${errorMessage}`)
    } finally {
      setNewVersionLoading(false)
    }
  }

  const filteredChanges = currentDriftReport
    ? currentDriftReport.changes.filter((c) => {
        if (driftFilter === 'ALL') return true
        return c.classification === driftFilter
      })
    : []

  return (
    <main className="container">
      <header className="header">
        <h1>MockAPILab Dashboard</h1>
        <p className="subtitle">
          Intelligent, stateful mock backend engine for modern full-stack development
        </p>
      </header>

      <section className="card">
        <h2>System Status</h2>
        {loading && <p className="status-text">Checking backend connection...</p>}
        {error && (
          <div className="status-box alert">
            <p><strong>Connection Error:</strong> {error}</p>
            <p className="hint">Ensure the Spring Boot backend is running on port 8080.</p>
          </div>
        )}
        {status && (
          <div className="status-box success">
            <div className="status-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '0.75rem' }}>
              <div><p><strong>Service:</strong> {status.service}</p></div>
              <div><p><strong>Status:</strong> <span style={{ color: '#4ade80', fontWeight: 700 }}>● {status.status}</span></p></div>
              <div><p><strong>Version:</strong> {status.version}</p></div>
              <div><p><strong>Architecture:</strong> Modular Monolith</p></div>
              <div><p><strong>Spring Boot:</strong> {status.springBootVersion} (Java {status.javaVersion})</p></div>
              <div><p><strong>State Store:</strong> {actuatorHealth?.components?.runtimeStateStore?.details?.implementation || 'Operational'}</p></div>
              <div><p><strong>Actuator Probes:</strong> Liveness: {actuatorHealth?.components?.livenessState?.status || 'UP'} | Readiness: {actuatorHealth?.components?.readinessState?.status || 'UP'}</p></div>
              <div><p><strong>Correlation ID:</strong> <code style={{ fontSize: '0.78rem', background: '#0f172a', padding: '2px 6px', borderRadius: '4px' }}>{lastCorrelationId || 'active'}</code></p></div>
            </div>
          </div>
        )}
      </section>

      <section className="card architecture-card">
        <h2>Architecture Overview</h2>
        <div className="arch-info">
          <p>
            <strong>MockAPILab</strong> runs as a high-performance modular monolith with Redis-backed shared state and Kafka asynchronous worker jobs:
          </p>
          <ul className="arch-list">
            <li><strong>PostgreSQL 16:</strong> System of Record for Users, Projects, Contracts (JSONB), and Generation Jobs</li>
            <li><strong>Redis 7:</strong> High-performance live mutable mock entity store &amp; collection index sets</li>
            <li><strong>Apache Kafka:</strong> Asynchronous generation job queue &amp; worker event streaming</li>
            <li><strong>Data Engine:</strong> Seedable PRNGs with schema heuristics &amp; India-friendly curated datasets</li>
            <li><strong>Security:</strong> Stateless JWT for Management, Public for Mock Gateways</li>
          </ul>
        </div>
      </section>

      <section className="card ingest-card">
        <h2>1. Contract Ingestion &amp; Setup</h2>
        <form onSubmit={handleIngest} className="ingest-form">
          <div className="form-row">
            <div className="form-group">
              <label htmlFor="jwtToken">JWT Bearer Token:</label>
              <input
                id="jwtToken"
                type="text"
                placeholder="Paste JWT token from /api/v1/auth/login"
                value={jwtToken}
                onChange={(e) => setJwtToken(e.target.value)}
              />
            </div>
            <div className="form-group">
              <label htmlFor="projectId">Project UUID:</label>
              <input
                id="projectId"
                type="text"
                placeholder="Target Project ID"
                value={projectId}
                onChange={(e) => setProjectId(e.target.value)}
              />
            </div>
          </div>
          <div className="form-group">
            <label htmlFor="contractName">Contract Name:</label>
            <input
              id="contractName"
              type="text"
              placeholder="e.g. Users Service API"
              value={contractName}
              onChange={(e) => setContractName(e.target.value)}
            />
          </div>
          <div className="form-group">
            <label htmlFor="contractContent">OpenAPI 3.x Specification (JSON / YAML):</label>
            <textarea
              id="contractContent"
              rows={5}
              value={contractContent}
              onChange={(e) => setContractContent(e.target.value)}
            />
          </div>
          <button type="submit" disabled={ingestLoading} className="submit-btn">
            {ingestLoading ? 'Ingesting...' : 'Ingest Contract'}
          </button>
        </form>

        {ingestStatus && (
          <div className={`status-box ${ingestStatus.startsWith('Success') ? 'success' : 'alert'}`}>
            {ingestStatus}
          </div>
        )}
      </section>

      <section className="card ingest-card">
        <h2>2. Gemini AI-Assisted Contract Extraction (M8)</h2>
        <p style={{ marginBottom: '1rem', color: '#94a3b8' }}>
          Extract, validate, and normalize API contracts from natural-language specs or Spring Boot controller source code using Gemini AI.
        </p>

        <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem' }}>
          <button
            type="button"
            onClick={() => handleSwitchAiPreset('DESCRIPTION')}
            className="submit-btn"
            style={{
              flex: 1,
              background: aiInputType === 'DESCRIPTION' ? '#3b82f6' : '#1e293b',
              border: aiInputType === 'DESCRIPTION' ? '1px solid #60a5fa' : '1px solid #334155',
              padding: '0.5rem 1rem',
              fontSize: '0.85rem'
            }}
          >
            Natural Language Spec
          </button>
          <button
            type="button"
            onClick={() => handleSwitchAiPreset('SPRING_BOOT_CODE')}
            className="submit-btn"
            style={{
              flex: 1,
              background: aiInputType === 'SPRING_BOOT_CODE' ? '#3b82f6' : '#1e293b',
              border: aiInputType === 'SPRING_BOOT_CODE' ? '1px solid #60a5fa' : '1px solid #334155',
              padding: '0.5rem 1rem',
              fontSize: '0.85rem'
            }}
          >
            Spring Boot Controller Code
          </button>
        </div>

        <form onSubmit={handleAiExtract} className="ingest-form">
          <div className="form-group">
            <label htmlFor="aiContractName">Target Contract Name:</label>
            <input
              id="aiContractName"
              type="text"
              placeholder="e.g. Users API (AI)"
              value={aiContractName}
              onChange={(e) => setAiContractName(e.target.value)}
            />
          </div>

          <div className="form-group">
            <label htmlFor="aiInput">
              {aiInputType === 'DESCRIPTION' ? 'Informal API Description:' : 'Spring Boot Controller / Model Source:'}
            </label>
            <textarea
              id="aiInput"
              rows={6}
              value={aiInput}
              onChange={(e) => setAiInput(e.target.value)}
              style={{ fontFamily: 'monospace', fontSize: '0.85rem' }}
            />
          </div>

          <button type="submit" disabled={aiLoading || !projectId} className="submit-btn">
            {aiLoading ? 'Extracting via Gemini AI...' : `Extract Contract via Gemini (${aiInputType})`}
          </button>
        </form>

        {aiExtractedSummary && (
          <div style={{ marginTop: '1rem', padding: '0.75rem 1rem', background: '#0f172a', borderRadius: '8px', border: '1px solid #334155' }}>
            <p style={{ margin: '0.25rem 0', fontSize: '0.9rem' }}>
              <strong>Extracted Title:</strong> {aiExtractedSummary.title} | <strong>Endpoints:</strong> {aiExtractedSummary.endpoints} | <strong>Schemas:</strong> {aiExtractedSummary.schemas}
            </p>
            <p style={{ margin: '0.25rem 0', fontSize: '0.85rem', color: '#10b981' }}>
              &check; Ready! Contract ID is now set for Runtime startup below.
            </p>
          </div>
        )}

        {aiStatus && (
          <div className={`status-box ${aiStatus.startsWith('Success') ? 'success' : 'alert'}`}>
            {aiStatus}
          </div>
        )}
      </section>

      <section className="card ingest-card">
        <h2>3. Dynamic Mock Server Runtime Controller</h2>
        <p style={{ marginBottom: '1rem', color: '#94a3b8' }}>
          Launch an in-process, stateful mock server from your ingested contract version.
        </p>

        <div className="form-row" style={{ alignItems: 'flex-end' }}>
          <div className="form-group" style={{ flex: 1 }}>
            <label htmlFor="runtimeIdInput">Active Runtime ID:</label>
            <input
              id="runtimeIdInput"
              type="text"
              placeholder="Start a runtime below or paste existing runtime ID"
              value={runtimeId}
              onChange={(e) => setRuntimeId(e.target.value)}
            />
          </div>
          <button
            type="button"
            onClick={handleStartRuntime}
            disabled={runtimeLoading || !ingestedContractId}
            className="submit-btn"
            style={{ width: 'auto', padding: '0.75rem 1.5rem', marginBottom: '0.5rem' }}
          >
            {runtimeLoading ? 'Starting...' : 'Start Mock Runtime'}
          </button>
        </div>

        {runtimeStatus && (
          <div className={`status-box ${runtimeStatus.startsWith('Runtime RUNNING') ? 'success' : 'alert'}`}>
            {runtimeStatus}
          </div>
        )}
      </section>

      <section className="card ingest-card">
        <h2>4. Asynchronous Mock Data Generation (Kafka Jobs + M5 Engine)</h2>
        <p style={{ marginBottom: '1rem', color: '#94a3b8' }}>
          Queue an asynchronous background generation job dispatched via Kafka to populate the Redis mock state store.
        </p>

        <form onSubmit={handleGenerateData} className="ingest-form">
          <div className="form-row">
            <div className="form-group" style={{ flex: 2 }}>
              <label htmlFor="genCollection">Collection Path:</label>
              <input
                id="genCollection"
                type="text"
                placeholder="/users"
                value={genCollection}
                onChange={(e) => setGenCollection(e.target.value)}
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label htmlFor="genCount">Count (1-100):</label>
              <input
                id="genCount"
                type="number"
                min={1}
                max={100}
                value={genCount}
                onChange={(e) => setGenCount(parseInt(e.target.value, 10) || 1)}
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label htmlFor="genSeed">Seed (Optional):</label>
              <input
                id="genSeed"
                type="text"
                placeholder="42 (or blank for auto)"
                value={genSeed}
                onChange={(e) => setGenSeed(e.target.value)}
              />
            </div>
          </div>

          <button type="submit" disabled={genLoading || !runtimeId} className="submit-btn">
            {genLoading ? 'Queueing Job...' : 'Queue Generation Job'}
          </button>
        </form>

        {activeJob && (
          <div style={{ marginTop: '1rem', padding: '0.75rem 1rem', background: '#0f172a', borderRadius: '8px', border: '1px solid #334155' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.5rem' }}>
              <span style={{ fontSize: '0.85rem', color: '#94a3b8' }}>Job ID: <code>{activeJob.jobId}</code></span>
              <span style={{
                padding: '0.2rem 0.6rem',
                borderRadius: '4px',
                fontSize: '0.75rem',
                fontWeight: 'bold',
                background: activeJob.status === 'COMPLETED' ? '#065f46' : activeJob.status === 'FAILED' ? '#991b1b' : '#1e40af',
                color: '#fff'
              }}>
                {activeJob.status}
              </span>
            </div>
            <p style={{ margin: '0.25rem 0', fontSize: '0.9rem' }}>
              <strong>Collection:</strong> {activeJob.collection} | <strong>Count:</strong> {activeJob.count}
              {activeJob.effectiveSeed ? ` | Effective Seed: ${activeJob.effectiveSeed}` : ''}
            </p>
          </div>
        )}

        {genStatus && (
          <div className={`status-box ${genStatus.startsWith('Success') ? 'success' : genStatus.startsWith('Failed') ? 'alert' : ''}`}>
            {genStatus}
          </div>
        )}
      </section>

      <section className="card ingest-card">
        <h2>5. Scenario Studio &amp; Failure Injector (M9)</h2>
        <p style={{ marginBottom: '1rem', color: '#94a3b8' }}>
          Configure dynamic scenarios, forced HTTP errors (401, 429, 500), probabilistic failures, and latency injection without altering contract schemas or mutating mock state.
        </p>

        <div style={{ marginBottom: '1rem' }}>
          <label style={{ display: 'block', marginBottom: '0.5rem', fontSize: '0.85rem', color: '#94a3b8' }}>
            Quick Scenario Presets:
          </label>
          <div style={{ display: 'flex', gap: '0.5rem', flexWrap: 'wrap' }}>
            <button
              type="button"
              onClick={() => handleApplyScenarioPreset('AUTH_401')}
              className="submit-btn"
              style={{ flex: 1, minWidth: '150px', background: '#1e293b', border: '1px solid #334155', padding: '0.4rem 0.8rem', fontSize: '0.8rem' }}
            >
              Auth 401 Unauthorized
            </button>
            <button
              type="button"
              onClick={() => handleApplyScenarioPreset('RATE_LIMIT_429')}
              className="submit-btn"
              style={{ flex: 1, minWidth: '150px', background: '#1e293b', border: '1px solid #334155', padding: '0.4rem 0.8rem', fontSize: '0.8rem' }}
            >
              Rate Limit 429 (5 reqs)
            </button>
            <button
              type="button"
              onClick={() => handleApplyScenarioPreset('FLAKY_500')}
              className="submit-btn"
              style={{ flex: 1, minWidth: '150px', background: '#1e293b', border: '1px solid #334155', padding: '0.4rem 0.8rem', fontSize: '0.8rem' }}
            >
              Flaky Server 500 (25%)
            </button>
            <button
              type="button"
              onClick={() => handleApplyScenarioPreset('LATENCY_2000')}
              className="submit-btn"
              style={{ flex: 1, minWidth: '150px', background: '#1e293b', border: '1px solid #334155', padding: '0.4rem 0.8rem', fontSize: '0.8rem' }}
            >
              Network Latency 2000ms
            </button>
          </div>
        </div>

        <form onSubmit={handleCreateScenario} className="ingest-form" style={{ marginBottom: '1.5rem' }}>
          <div className="form-row">
            <div className="form-group" style={{ flex: 2 }}>
              <label htmlFor="scenarioNameInput">Scenario Name:</label>
              <input
                id="scenarioNameInput"
                type="text"
                placeholder="e.g. Auth 401 Failure"
                value={scenarioName}
                onChange={(e) => setScenarioName(e.target.value)}
              />
            </div>
            <div className="form-group" style={{ flex: 2 }}>
              <label htmlFor="scenarioPathInput">Path Pattern:</label>
              <input
                id="scenarioPathInput"
                type="text"
                placeholder="e.g. /users or /users/*"
                value={scenarioPath}
                onChange={(e) => setScenarioPath(e.target.value)}
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label htmlFor="scenarioMethodSelect">Method:</label>
              <select
                id="scenarioMethodSelect"
                value={scenarioMethod}
                onChange={(e) => setScenarioMethod(e.target.value)}
                style={{ padding: '0.65rem', borderRadius: '6px', background: '#1e293b', color: '#f8fafc', border: '1px solid #334155' }}
              >
                <option value="GET">GET</option>
                <option value="POST">POST</option>
                <option value="PUT">PUT</option>
                <option value="DELETE">DELETE</option>
                <option value="ALL">ALL (Any)</option>
              </select>
            </div>
          </div>

          <div className="form-row">
            <div className="form-group" style={{ flex: 2 }}>
              <label htmlFor="scenarioActionSelect">Scenario Action:</label>
              <select
                id="scenarioActionSelect"
                value={scenarioAction}
                onChange={(e) => setScenarioAction(e.target.value as 'FORCE_STATUS' | 'DELAY' | 'RANDOM_FAILURE')}
                style={{ padding: '0.65rem', borderRadius: '6px', background: '#1e293b', color: '#f8fafc', border: '1px solid #334155' }}
              >
                <option value="FORCE_STATUS">FORCE_STATUS (Inject Error Code)</option>
                <option value="DELAY">DELAY (Inject Latency)</option>
                <option value="RANDOM_FAILURE">RANDOM_FAILURE (Probabilistic Failure)</option>
              </select>
            </div>

            {scenarioAction === 'FORCE_STATUS' && (
              <div className="form-group" style={{ flex: 1 }}>
                <label htmlFor="scenarioStatusCode">Status Code:</label>
                <input
                  id="scenarioStatusCode"
                  type="number"
                  min={100}
                  max={599}
                  value={scenarioStatusCode}
                  onChange={(e) => setScenarioStatusCode(parseInt(e.target.value, 10) || 500)}
                />
              </div>
            )}

            {scenarioAction === 'DELAY' && (
              <div className="form-group" style={{ flex: 1 }}>
                <label htmlFor="scenarioDelayMs">Delay (ms):</label>
                <input
                  id="scenarioDelayMs"
                  type="number"
                  min={0}
                  max={30000}
                  value={scenarioDelayMs}
                  onChange={(e) => setScenarioDelayMs(parseInt(e.target.value, 10) || 0)}
                />
              </div>
            )}

            {scenarioAction === 'RANDOM_FAILURE' && (
              <>
                <div className="form-group" style={{ flex: 1 }}>
                  <label htmlFor="scenarioProbStatusCode">Status Code:</label>
                  <input
                    id="scenarioProbStatusCode"
                    type="number"
                    min={100}
                    max={599}
                    value={scenarioStatusCode}
                    onChange={(e) => setScenarioStatusCode(parseInt(e.target.value, 10) || 500)}
                  />
                </div>
                <div className="form-group" style={{ flex: 1 }}>
                  <label htmlFor="scenarioProbability">Probability %:</label>
                  <input
                    id="scenarioProbability"
                    type="number"
                    min={0}
                    max={100}
                    value={scenarioProbability}
                    onChange={(e) => setScenarioProbability(parseInt(e.target.value, 10) || 0)}
                  />
                </div>
              </>
            )}

            <div className="form-group" style={{ flex: 1 }}>
              <label htmlFor="scenarioMaxExec">Max Execs:</label>
              <input
                id="scenarioMaxExec"
                type="text"
                placeholder="Optional (e.g. 5)"
                value={scenarioMaxExecutions}
                onChange={(e) => setScenarioMaxExecutions(e.target.value)}
              />
            </div>
          </div>

          <button type="submit" disabled={scenarioLoading || !runtimeId || !projectId} className="submit-btn">
            {scenarioLoading ? 'Creating Scenario...' : 'Create Scenario Rule'}
          </button>
        </form>

        {scenarioStatus && (
          <div className={`status-box ${scenarioStatus.startsWith('Success') ? 'success' : 'alert'}`}>
            {scenarioStatus}
          </div>
        )}

        <div style={{ marginTop: '1rem' }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: '0.75rem' }}>
            <h3 style={{ fontSize: '1rem', margin: 0 }}>Active Scenarios for Runtime</h3>
            <button
              type="button"
              onClick={() => fetchScenarios(projectId, runtimeId, jwtToken)}
              disabled={!projectId || !runtimeId}
              style={{ background: 'transparent', border: '1px solid #334155', color: '#94a3b8', padding: '0.3rem 0.6rem', borderRadius: '4px', cursor: 'pointer', fontSize: '0.75rem' }}
            >
              &circlearrowright; Refresh
            </button>
          </div>

          {scenarios.length === 0 ? (
            <p style={{ fontSize: '0.85rem', color: '#64748b' }}>No scenarios configured for this runtime yet.</p>
          ) : (
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #334155', textAlign: 'left', color: '#94a3b8' }}>
                    <th style={{ padding: '0.5rem' }}>Name</th>
                    <th style={{ padding: '0.5rem' }}>Match Target</th>
                    <th style={{ padding: '0.5rem' }}>Action</th>
                    <th style={{ padding: '0.5rem' }}>Execs</th>
                    <th style={{ padding: '0.5rem' }}>Status</th>
                    <th style={{ padding: '0.5rem' }}>Controls</th>
                  </tr>
                </thead>
                <tbody>
                  {scenarios.map((sc) => (
                    <tr key={sc.id} style={{ borderBottom: '1px solid #1e293b' }}>
                      <td style={{ padding: '0.5rem' }}>
                        <strong>{sc.name}</strong>
                      </td>
                      <td style={{ padding: '0.5rem' }}>
                        <code>{sc.httpMethod || 'ALL'} {sc.pathPattern}</code>
                      </td>
                      <td style={{ padding: '0.5rem' }}>
                        <span style={{
                          padding: '0.15rem 0.4rem',
                          borderRadius: '4px',
                          fontSize: '0.75rem',
                          background: sc.action === 'FORCE_STATUS' ? '#7f1d1d' : sc.action === 'DELAY' ? '#78350f' : '#312e81',
                          color: '#fff'
                        }}>
                          {sc.action} {sc.statusCode ? `(${sc.statusCode})` : ''} {sc.delayMs ? `(${sc.delayMs}ms)` : ''} {sc.probabilityPercent ? `(${sc.probabilityPercent}%)` : ''}
                        </span>
                      </td>
                      <td style={{ padding: '0.5rem' }}>
                        {sc.executionCount} / {sc.maxExecutions ? sc.maxExecutions : '∞'}
                      </td>
                      <td style={{ padding: '0.5rem' }}>
                        <span style={{
                          padding: '0.15rem 0.4rem',
                          borderRadius: '4px',
                          fontSize: '0.75rem',
                          fontWeight: 'bold',
                          background: sc.status === 'ACTIVE' ? '#065f46' : '#334155',
                          color: '#fff'
                        }}>
                          {sc.status}
                        </span>
                      </td>
                      <td style={{ padding: '0.5rem' }}>
                        <div style={{ display: 'flex', gap: '0.3rem' }}>
                          <button
                            type="button"
                            onClick={() => handleToggleScenario(sc.id, sc.status)}
                            style={{
                              background: sc.status === 'ACTIVE' ? '#475569' : '#059669',
                              border: 'none',
                              color: '#fff',
                              padding: '0.2rem 0.5rem',
                              borderRadius: '4px',
                              cursor: 'pointer',
                              fontSize: '0.75rem'
                            }}
                          >
                            {sc.status === 'ACTIVE' ? 'Disable' : 'Enable'}
                          </button>
                          <button
                            type="button"
                            onClick={() => handleDeleteScenario(sc.id)}
                            style={{
                              background: '#dc2626',
                              border: 'none',
                              color: '#fff',
                              padding: '0.2rem 0.5rem',
                              borderRadius: '4px',
                              cursor: 'pointer',
                              fontSize: '0.75rem'
                            }}
                          >
                            Delete
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </section>

      <section className="card ingest-card">
        <h2>6. Live Mock Request Dispatcher Tester</h2>
        <p style={{ marginBottom: '1rem', color: '#94a3b8' }}>
          Execute public HTTP calls directly against <code>/mock/&#123;runtimeId&#125;</code> and observe stateful responses.
        </p>

        <form onSubmit={handleSendMockRequest} className="ingest-form">
          <div className="form-row">
            <div className="form-group" style={{ maxWidth: '140px' }}>
              <label htmlFor="mockMethod">HTTP Method:</label>
              <select
                id="mockMethod"
                value={mockMethod}
                onChange={(e) => setMockMethod(e.target.value)}
                style={{ padding: '0.65rem', borderRadius: '6px', background: '#1e293b', color: '#f8fafc', border: '1px solid #334155' }}
              >
                <option value="GET">GET</option>
                <option value="POST">POST</option>
                <option value="PUT">PUT</option>
                <option value="DELETE">DELETE</option>
                <option value="PATCH">PATCH</option>
              </select>
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label htmlFor="mockPath">Sub-path:</label>
              <input
                id="mockPath"
                type="text"
                placeholder="/users or /users/1"
                value={mockPath}
                onChange={(e) => setMockPath(e.target.value)}
              />
            </div>
          </div>

          {['POST', 'PUT', 'PATCH'].includes(mockMethod) && (
            <div className="form-group">
              <label htmlFor="mockBody">JSON Request Body:</label>
              <textarea
                id="mockBody"
                rows={4}
                value={mockBody}
                onChange={(e) => setMockBody(e.target.value)}
              />
            </div>
          )}

          <button type="submit" disabled={mockLoading || !runtimeId} className="submit-btn">
            {mockLoading ? 'Sending Request...' : `Send ${mockMethod} Request`}
          </button>
        </form>

        {mockResponse && (
          <div className="status-box" style={{ background: '#0f172a', fontFamily: 'monospace', whiteSpace: 'pre-wrap' }}>
            {mockResponse}
          </div>
        )}
      </section>

      <section className="card ingest-card">
        <h2>7. Contract Drift Detection &amp; Analysis (M10)</h2>
        <p style={{ marginBottom: '1rem', color: '#94a3b8' }}>
          Compare two immutable NormalizedContract versions to deterministically detect breaking changes, additions, schema drift, and severity risk.
        </p>

        {/* Publish v2 Version Helper */}
        <details style={{ marginBottom: '1.5rem', background: '#0f172a', border: '1px solid #334155', borderRadius: '8px', padding: '0.75rem 1rem' }}>
          <summary style={{ cursor: 'pointer', fontWeight: 600, color: '#60a5fa', fontSize: '0.9rem' }}>
            &plus; Ingest New Version (v2, v3...) for Existing Contract
          </summary>
          <form onSubmit={handlePublishNewVersion} style={{ marginTop: '1rem' }} className="ingest-form">
            <div className="form-group">
              <label htmlFor="newVerContent">New Version Specification (OpenAPI JSON/YAML):</label>
              <textarea
                id="newVerContent"
                rows={5}
                value={newVersionContent}
                onChange={(e) => setNewVersionContent(e.target.value)}
                style={{ fontFamily: 'monospace', fontSize: '0.8rem' }}
              />
            </div>
            <button type="submit" disabled={newVersionLoading || !driftContractId} className="submit-btn">
              {newVersionLoading ? 'Publishing Version...' : 'Publish New Contract Version'}
            </button>
          </form>
        </details>

        {/* Drift Analysis Trigger Form */}
        <form onSubmit={handleAnalyzeDrift} className="ingest-form" style={{ marginBottom: '1.5rem' }}>
          <div className="form-row">
            <div className="form-group" style={{ flex: 2 }}>
              <label htmlFor="driftContractInput">Contract UUID:</label>
              <input
                id="driftContractInput"
                type="text"
                placeholder="Target Contract ID"
                value={driftContractId}
                onChange={(e) => setDriftContractId(e.target.value)}
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label htmlFor="driftFromVer">Base Version:</label>
              <input
                id="driftFromVer"
                type="number"
                min={1}
                value={driftFromVersion}
                onChange={(e) => setDriftFromVersion(parseInt(e.target.value, 10) || 1)}
              />
            </div>
            <div className="form-group" style={{ flex: 1 }}>
              <label htmlFor="driftToVer">Target Version:</label>
              <input
                id="driftToVer"
                type="number"
                min={1}
                value={driftToVersion}
                onChange={(e) => setDriftToVersion(parseInt(e.target.value, 10) || 1)}
              />
            </div>
          </div>

          <div style={{ display: 'flex', gap: '0.5rem' }}>
            <button type="submit" disabled={driftLoading || !driftContractId || !projectId} className="submit-btn" style={{ flex: 2 }}>
              {driftLoading ? 'Analyzing Contract Drift...' : `Run Drift Analysis (v${driftFromVersion} \u2192 v${driftToVersion})`}
            </button>
            <button
              type="button"
              onClick={() => fetchDriftReports(projectId, driftContractId, jwtToken)}
              disabled={!projectId || !driftContractId}
              className="submit-btn"
              style={{ flex: 1, background: '#1e293b', border: '1px solid #334155' }}
            >
              Fetch History
            </button>
          </div>
        </form>

        {driftStatus && (
          <div className={`status-box ${driftStatus.startsWith('Success') || driftStatus.startsWith('Drift analysis completed') ? 'success' : 'alert'}`}>
            {driftStatus}
          </div>
        )}

        {/* Drift Report Metrics Cards */}
        {currentDriftReport && (
          <div style={{ marginTop: '1.5rem' }}>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(180px, 1fr))', gap: '1rem', marginBottom: '1.5rem' }}>
              <div style={{ background: '#1e293b', border: '1px solid #dc2626', borderRadius: '8px', padding: '1rem', textAlign: 'center' }}>
                <div style={{ fontSize: '1.75rem', fontWeight: 800, color: '#f87171' }}>
                  {currentDriftReport.breakingChangeCount}
                </div>
                <div style={{ fontSize: '0.8rem', color: '#fca5a5', fontWeight: 600, marginTop: '0.25rem' }}>
                  BREAKING CHANGES
                </div>
              </div>

              <div style={{ background: '#1e293b', border: '1px solid #10b981', borderRadius: '8px', padding: '1rem', textAlign: 'center' }}>
                <div style={{ fontSize: '1.75rem', fontWeight: 800, color: '#34d399' }}>
                  {currentDriftReport.nonBreakingChangeCount}
                </div>
                <div style={{ fontSize: '0.8rem', color: '#6ee7b7', fontWeight: 600, marginTop: '0.25rem' }}>
                  NON-BREAKING CHANGES
                </div>
              </div>

              <div style={{ background: '#1e293b', border: '1px solid #3b82f6', borderRadius: '8px', padding: '1rem', textAlign: 'center' }}>
                <div style={{ fontSize: '1.75rem', fontWeight: 800, color: '#60a5fa' }}>
                  {currentDriftReport.informationalChangeCount}
                </div>
                <div style={{ fontSize: '0.8rem', color: '#93c5fd', fontWeight: 600, marginTop: '0.25rem' }}>
                  INFORMATIONAL
                </div>
              </div>

              <div style={{ background: '#1e293b', border: '1px solid #64748b', borderRadius: '8px', padding: '1rem', textAlign: 'center' }}>
                <div style={{
                  fontSize: '1.1rem',
                  fontWeight: 800,
                  marginTop: '0.35rem',
                  color: currentDriftReport.overallSeverity === 'CRITICAL' ? '#ef4444'
                    : currentDriftReport.overallSeverity === 'HIGH' ? '#f87171'
                    : currentDriftReport.overallSeverity === 'MEDIUM' ? '#fbbf24'
                    : currentDriftReport.overallSeverity === 'LOW' ? '#60a5fa' : '#34d399'
                }}>
                  {currentDriftReport.overallSeverity}
                </div>
                <div style={{ fontSize: '0.8rem', color: '#94a3b8', fontWeight: 600, marginTop: '0.5rem' }}>
                  OVERALL SEVERITY
                </div>
              </div>
            </div>

            {/* Filter Tabs */}
            <div style={{ display: 'flex', gap: '0.5rem', marginBottom: '1rem', borderBottom: '1px solid #334155', paddingBottom: '0.5rem' }}>
              {(['ALL', 'BREAKING', 'NON_BREAKING', 'INFORMATIONAL'] as const).map((filter) => (
                <button
                  key={filter}
                  type="button"
                  onClick={() => setDriftFilter(filter)}
                  style={{
                    background: driftFilter === filter ? '#334155' : 'transparent',
                    border: 'none',
                    color: driftFilter === filter ? '#f8fafc' : '#94a3b8',
                    padding: '0.4rem 0.8rem',
                    borderRadius: '4px',
                    cursor: 'pointer',
                    fontSize: '0.8rem',
                    fontWeight: driftFilter === filter ? 600 : 400
                  }}
                >
                  {filter} ({filter === 'ALL' ? currentDriftReport.changes.length : currentDriftReport.changes.filter(c => c.classification === filter).length})
                </button>
              ))}
            </div>

            {/* Changes List */}
            {filteredChanges.length === 0 ? (
              <p style={{ fontSize: '0.85rem', color: '#64748b', textAlign: 'center', margin: '2rem 0' }}>
                No changes matching the selected filter.
              </p>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                {filteredChanges.map((change, idx) => (
                  <div
                    key={change.id || idx}
                    style={{
                      background: '#0f172a',
                      border: `1px solid ${change.classification === 'BREAKING' ? '#7f1d1d' : change.classification === 'NON_BREAKING' ? '#065f46' : '#1e3a8a'}`,
                      borderRadius: '8px',
                      padding: '1rem'
                    }}
                  >
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '0.5rem', flexWrap: 'wrap', gap: '0.5rem' }}>
                      <div style={{ display: 'flex', gap: '0.5rem', alignItems: 'center' }}>
                        <span style={{
                          padding: '0.2rem 0.5rem',
                          borderRadius: '4px',
                          fontSize: '0.75rem',
                          fontWeight: 700,
                          background: change.classification === 'BREAKING' ? '#dc2626' : change.classification === 'NON_BREAKING' ? '#059669' : '#2563eb',
                          color: '#fff'
                        }}>
                          {change.classification}
                        </span>
                        <span style={{
                          padding: '0.2rem 0.4rem',
                          borderRadius: '4px',
                          fontSize: '0.7rem',
                          background: '#1e293b',
                          color: '#94a3b8',
                          border: '1px solid #334155'
                        }}>
                          {change.changeType}
                        </span>
                        {change.method && change.path && (
                          <code style={{ fontSize: '0.85rem', color: '#38bdf8' }}>
                            {change.method} {change.path}
                          </code>
                        )}
                      </div>
                      <span style={{
                        fontSize: '0.75rem',
                        color: change.severity === 'CRITICAL' || change.severity === 'HIGH' ? '#f87171' : change.severity === 'MEDIUM' ? '#fbbf24' : '#60a5fa',
                        fontWeight: 600
                      }}>
                        Severity: {change.severity}
                      </span>
                    </div>

                    <p style={{ margin: '0.35rem 0', fontSize: '0.9rem', color: '#e2e8f0' }}>
                      {change.message}
                    </p>

                    {change.location && (
                      <p style={{ margin: '0.2rem 0', fontSize: '0.75rem', color: '#64748b' }}>
                        Location: <code>{change.location}</code>
                      </p>
                    )}

                    {(change.oldValue || change.newValue) && (
                      <div style={{ display: 'flex', gap: '1rem', marginTop: '0.5rem', fontSize: '0.8rem' }}>
                        {change.oldValue && (
                          <div style={{ flex: 1, background: '#1c1917', border: '1px solid #7f1d1d', borderRadius: '4px', padding: '0.4rem 0.6rem' }}>
                            <span style={{ color: '#ef4444', fontWeight: 600, display: 'block', fontSize: '0.7rem' }}>OLD VALUE</span>
                            <code style={{ color: '#fca5a5', textDecoration: 'line-through' }}>{change.oldValue}</code>
                          </div>
                        )}
                        {change.newValue && (
                          <div style={{ flex: 1, background: '#022c22', border: '1px solid #065f46', borderRadius: '4px', padding: '0.4rem 0.6rem' }}>
                            <span style={{ color: '#10b981', fontWeight: 600, display: 'block', fontSize: '0.7rem' }}>NEW VALUE</span>
                            <code style={{ color: '#6ee7b7' }}>{change.newValue}</code>
                          </div>
                        )}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {/* Historical Drift Reports */}
        {pastDriftReports.length > 0 && (
          <div style={{ marginTop: '2rem' }}>
            <h3 style={{ fontSize: '1rem', marginBottom: '0.75rem' }}>Historical Drift Reports for Contract</h3>
            <div style={{ overflowX: 'auto' }}>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: '0.85rem' }}>
                <thead>
                  <tr style={{ borderBottom: '1px solid #334155', textAlign: 'left', color: '#94a3b8' }}>
                    <th style={{ padding: '0.5rem' }}>Version Diff</th>
                    <th style={{ padding: '0.5rem' }}>Breaking</th>
                    <th style={{ padding: '0.5rem' }}>Non-Breaking</th>
                    <th style={{ padding: '0.5rem' }}>Severity</th>
                    <th style={{ padding: '0.5rem' }}>Analyzed At</th>
                    <th style={{ padding: '0.5rem' }}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {pastDriftReports.map((r) => (
                    <tr key={r.id} style={{ borderBottom: '1px solid #1e293b' }}>
                      <td style={{ padding: '0.5rem' }}>
                        <strong>v{r.fromVersionNumber} &rarr; v{r.toVersionNumber}</strong>
                      </td>
                      <td style={{ padding: '0.5rem', color: r.breakingChangeCount > 0 ? '#f87171' : '#94a3b8' }}>
                        {r.breakingChangeCount}
                      </td>
                      <td style={{ padding: '0.5rem', color: '#34d399' }}>
                        {r.nonBreakingChangeCount}
                      </td>
                      <td style={{ padding: '0.5rem' }}>
                        <span style={{
                          padding: '0.15rem 0.4rem',
                          borderRadius: '4px',
                          fontSize: '0.75rem',
                          fontWeight: 'bold',
                          background: r.overallSeverity === 'CRITICAL' ? '#7f1d1d' : r.overallSeverity === 'HIGH' ? '#991b1b' : r.overallSeverity === 'MEDIUM' ? '#78350f' : '#065f46',
                          color: '#fff'
                        }}>
                          {r.overallSeverity}
                        </span>
                      </td>
                      <td style={{ padding: '0.5rem', color: '#64748b' }}>
                        {new Date(r.createdAt).toLocaleString()}
                      </td>
                      <td style={{ padding: '0.5rem' }}>
                        <button
                          type="button"
                          onClick={() => {
                            setCurrentDriftReport(r)
                            setDriftFromVersion(r.fromVersionNumber)
                            setDriftToVersion(r.toVersionNumber)
                          }}
                          style={{ background: '#334155', border: 'none', color: '#f8fafc', padding: '0.2rem 0.5rem', borderRadius: '4px', cursor: 'pointer', fontSize: '0.75rem' }}
                        >
                          View
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        )}
      </section>

      <footer className="footer">
        <p>MockAPILab &copy; {new Date().getFullYear()} - Developer Productivity &amp; Stateful Mocking Platform</p>
      </footer>
    </main>
  )
}

export default App