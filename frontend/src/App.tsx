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

  return (
    <main className="container">
      <header className="header">
        <div className="badge">Milestone 1 • Project Foundation</div>
        <h1>MockAPILab</h1>
        <p className="subtitle">
          Intelligent, stateful mock backend engine for modern frontend & full-stack development.
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
            <li><strong>Frontend:</strong> React + TypeScript + Vite</li>
            <li><strong>Database:</strong> PostgreSQL (Contracts, Scenarios, Metadata)</li>
            <li><strong>State & Cache:</strong> Redis (High-speed runtime state)</li>
            <li><strong>Event Stream:</strong> Apache Kafka (Async simulation & telemetry)</li>
            <li><strong>AI Layer:</strong> Gemini API (API contract ingestion & schema synthesis)</li>
          </ul>
        </div>
      </section>

      <footer className="footer">
        <p>MockAPILab &copy; {new Date().getFullYear()} — Developer Productivity &amp; Stateful Mocking Platform</p>
      </footer>
    </main>
  )
}

export default App
