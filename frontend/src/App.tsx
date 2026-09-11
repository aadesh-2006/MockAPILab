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
          description: Success`)
  const [ingestStatus, setIngestStatus] = useState<string | null>(null)
  const [ingestLoading, setIngestLoading] = useState(false)

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
        setIngestStatus(`Success: Ingested "${data.data.name}" (Version ${data.data.latestVersion}, ${data.data.totalEndpoints} endpoints, ${data.data.totalSchemas} schemas)`)
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

  return (
    <main className="container">
      <header className="header">
        <div className="badge">Milestone 3 • Contract Ingestion &amp; Normalization</div>
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
            <li><strong>Security:</strong> Stateless JWT + Project Ownership Isolation</li>
            <li><strong>Database:</strong> PostgreSQL 16 + Flyway (JSONB Normalized Snapshots)</li>
            <li><strong>State &amp; Cache:</strong> Redis (Planned M4)</li>
            <li><strong>Event Stream:</strong> Apache Kafka (Planned M4/M6)</li>
            <li><strong>AI Layer:</strong> Gemini API Contract Extraction (Planned M5)</li>
          </ul>
        </div>
      </section>

      <section className="card ingest-card">
        <h2>OpenAPI Contract Ingestion Tester</h2>
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
              rows={6}
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

      <footer className="footer">
        <p>MockAPILab &copy; {new Date().getFullYear()} — Developer Productivity &amp; Stateful Mocking Platform</p>
      </footer>
    </main>
  )
}

export default App
