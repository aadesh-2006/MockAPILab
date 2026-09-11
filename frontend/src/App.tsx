import { useState, useEffect } from 'react'
import './App.css'

interface SystemStatus {
  service: string
  version: string
  milestone: string
  status: string
  javaVersion?: string
  architecture?: string
}

function App() {
  const [status, setStatus] = useState<SystemStatus | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  // Contract Ingestion Tester State
  const [jwtToken, setJwtToken] = useState('')
  const [projectId, setProjectId] = useState('')
  const [contractName, setContractName] = useState('Sample Users API')
  const [contractContent, setContractContent] = useState(`openapi: 3.0.3
info:
  title: Users Service API
  version: 1.0.0
paths:
  /users:
    get:
      summary: List users
      responses:
        '200':
          description: Success
    post:
      summary: Create user
      requestBody:
        required: true
        content:
          application/json:
            schema:
              type: object
              required:
                - name
              properties:
                name:
                  type: string
                email:
                  type: string
                  format: email
      responses:
        '201':
          description: User created
  /users/{id}:
    get:
      summary: Get user by ID
      responses:
        '200':
          description: User details
    delete:
      summary: Delete user
      responses:
        '204':
          description: User deleted`)
  const [ingestStatus, setIngestStatus] = useState<string | null>(null)
  const [ingestLoading, setIngestLoading] = useState(false)
  const [ingestedContractId, setIngestedContractId] = useState<string | null>(null)

  // Runtime Management State
  const [runtimeId, setRuntimeId] = useState('')
  const [runtimeStatus, setRuntimeStatus] = useState<string | null>(null)
  const [runtimeLoading, setRuntimeLoading] = useState(false)

  // Live Mock Dispatch Tester State
  const [mockPath, setMockPath] = useState('/users')
  const [mockMethod, setMockMethod] = useState('GET')
  const [mockBody, setMockBody] = useState('{\n  "name": "Jane Doe",\n  "email": "jane@example.com"\n}')
  const [mockResponse, setMockResponse] = useState<string | null>(null)
  const [mockLoading, setMockLoading] = useState(false)

  useEffect(() => {
    fetch('/api/v1/status')
      .then((res) => {
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

      let parsed: string
      try {
        parsed = JSON.stringify(JSON.parse(text), null, 2)
      } catch {
        parsed = text
      }

      setMockResponse(`Status: ${res.status} ${res.statusText}\n\n${parsed}`)
    } catch (err: unknown) {
      const errorMessage = err instanceof Error ? err.message : String(err)
      setMockResponse(`Request Error: ${errorMessage}`)
    } finally {
      setMockLoading(false)
    }
  }

  return (
    <main className="container">
      <header className="header">
        <div className="badge">Milestone 4 • Stateful Mock Runtime Engine</div>
        <h1>MockAPILab</h1>
        <p className="subtitle">
          Intelligent, stateful mock backend engine for modern frontend &amp; full-stack development.
        </p>
      </header>

      <section className="card-grid">
        <div className="card">
          <h2>Backend Status</h2>
          {loading && <p className="status-badge loading">Connecting to backend...</p>}
          {error && (
            <p className="status-badge error">
              Offline (Start backend on port 8080)
            </p>
          )}
          {status && (
            <div className="status-details">
              <p><strong>Service:</strong> {status.service}</p>
              <p><strong>Status:</strong> <span className="status-badge up">{status.status}</span></p>
              <p><strong>Version:</strong> {status.version}</p>
              <p><strong>Architecture:</strong> {status.architecture}</p>
            </div>
          )}
        </div>

        <div className="card">
          <h2>Architecture Blueprint</h2>
          <ul className="feature-list">
            <li><strong>Core:</strong> Java 21 + Spring Boot (Modular Monolith)</li>
            <li><strong>Contract Engine:</strong> OpenAPI 3.x Parser $\rightarrow$ Normalized Contract Model</li>
            <li><strong>Mock Runtime:</strong> Stateful in-process runtime (/mock/&#123;runtimeId&#125;/**)</li>
            <li><strong>Isolation:</strong> Thread-safe In-Memory State Store per Runtime</li>
            <li><strong>Security:</strong> Stateless JWT for Management, Public for Mock Gateways</li>
            <li><strong>Database:</strong> PostgreSQL 16 + Flyway V1-V3 (JSONB Snapshots)</li>
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
        <h2>2. Dynamic Mock Server Runtime Controller</h2>
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
        <h2>3. Live Mock Request Dispatcher Tester</h2>
        <p style={{ marginBottom: '1rem', color: '#94a3b8' }}>
          Execute public HTTP calls directly against <code>/mock/&#123;runtimeId&#125;</code> and observe real stateful responses.
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

      <footer className="footer">
        <p>MockAPILab &copy; {new Date().getFullYear()} — Developer Productivity &amp; Stateful Mocking Platform</p>
      </footer>
    </main>
  )
}

export default App
