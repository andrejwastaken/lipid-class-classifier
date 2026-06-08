import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent, RefObject } from 'react'
import './App.css'

type AuthMode = 'login' | 'register'
type Screen = 'upload' | 'result'
type JobStatus = 'PENDING' | 'PROCESSING' | 'DONE' | 'FAILED'

type User = {
  id: string
  email: string
}

type AuthResponse = {
  token: string
  user: User
}

type UploadResponse = {
  job_id: string
  status: JobStatus
}

type JobResponse = {
  job_id: string
  status: JobStatus
  original_filename: string
  predicted_class: string | null
  probability: number | null
  model_version: string | null
  error_message: string | null
  created_at: string
  updated_at: string
}

type Notice = {
  kind: 'info' | 'error' | 'success'
  text: string
}

const API_BASE = import.meta.env.VITE_API_BASE_URL ?? ''
const TOKEN_STORAGE_KEY = 'lipid-classifier-token'
const USER_STORAGE_KEY = 'lipid-classifier-user'
const ACTIVE_JOB_STORAGE_KEY = 'lipid-classifier-active-job'

function App() {
  const [authMode, setAuthMode] = useState<AuthMode>('login')
  const [screen, setScreen] = useState<Screen>('upload')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_STORAGE_KEY) ?? '')
  const [user, setUser] = useState<User | null>(() => {
    const stored = localStorage.getItem(USER_STORAGE_KEY)
    return stored ? (JSON.parse(stored) as User) : null
  })
  const [selectedFile, setSelectedFile] = useState<File | null>(null)
  const [activeJobId, setActiveJobId] = useState(
    () => localStorage.getItem(ACTIVE_JOB_STORAGE_KEY) ?? '',
  )
  const [job, setJob] = useState<JobResponse | null>(null)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [authLoading, setAuthLoading] = useState(false)
  const [uploadLoading, setUploadLoading] = useState(false)
  const [polling, setPolling] = useState(false)
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const isAuthenticated = Boolean(token && user)
  const isActiveJob = job?.status === 'PENDING' || job?.status === 'PROCESSING'
  const canUpload = isAuthenticated && selectedFile && !uploadLoading
  const probabilityLabel = useMemo(() => {
    if (job?.probability == null) {
      return 'Awaiting prediction'
    }
    return `${(job.probability * 100).toFixed(2)}%`
  }, [job?.probability])

  useEffect(() => {
    if (!token || !activeJobId) {
      return
    }

    let cancelled = false
    let timeoutId: number | undefined

    const loadJob = async () => {
      setPolling(true)
      try {
        const nextJob = await apiRequest<JobResponse>(`/api/jobs/${activeJobId}`, { token })
        if (cancelled) {
          return
        }

        setJob(nextJob)
        if (nextJob.status === 'DONE' || nextJob.status === 'FAILED') {
          setPolling(false)
          setScreen('result')
          return
        }

        timeoutId = window.setTimeout(loadJob, 3000)
      } catch (error) {
        if (!cancelled) {
          setNotice({ kind: 'error', text: errorMessage(error) })
          setPolling(false)
        }
      }
    }

    loadJob()

    return () => {
      cancelled = true
      if (timeoutId) {
        window.clearTimeout(timeoutId)
      }
    }
  }, [activeJobId, token])

  async function handleAuthSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setAuthLoading(true)
    setNotice(null)

    try {
      const response = await apiRequest<AuthResponse>(`/api/auth/${authMode}`, {
        method: 'POST',
        body: JSON.stringify({ email, password }),
        headers: { 'Content-Type': 'application/json' },
      })
      setToken(response.token)
      setUser(response.user)
      setScreen('upload')
      localStorage.setItem(TOKEN_STORAGE_KEY, response.token)
      localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(response.user))
      setNotice({
        kind: 'success',
        text: authMode === 'register' ? 'Account created.' : 'Logged in.',
      })
    } catch (error) {
      setNotice({ kind: 'error', text: errorMessage(error) })
    } finally {
      setAuthLoading(false)
    }
  }

  async function handleUploadSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!selectedFile || !token) {
      return
    }

    setUploadLoading(true)
    setNotice(null)
    setScreen('upload')

    try {
      const formData = new FormData()
      formData.append('file', selectedFile)
      const response = await apiRequest<UploadResponse>('/api/jobs/upload', {
        method: 'POST',
        body: formData,
        token,
      })
      setActiveJobId(response.job_id)
      localStorage.setItem(ACTIVE_JOB_STORAGE_KEY, response.job_id)
      setJob({
        job_id: response.job_id,
        status: response.status,
        original_filename: selectedFile.name,
        predicted_class: null,
        probability: null,
        model_version: null,
        error_message: null,
        created_at: new Date().toISOString(),
        updated_at: new Date().toISOString(),
      })
      setNotice({ kind: 'success', text: 'Upload accepted. The worker will process it from RabbitMQ.' })
    } catch (error) {
      setNotice({ kind: 'error', text: errorMessage(error) })
    } finally {
      setUploadLoading(false)
    }
  }

  function handleLogout() {
    setToken('')
    setUser(null)
    setJob(null)
    setSelectedFile(null)
    setActiveJobId('')
    setScreen('upload')
    localStorage.removeItem(TOKEN_STORAGE_KEY)
    localStorage.removeItem(USER_STORAGE_KEY)
    localStorage.removeItem(ACTIVE_JOB_STORAGE_KEY)
    setNotice({ kind: 'info', text: 'Logged out.' })
  }

  function handleNewUpload() {
    setSelectedFile(null)
    setJob(null)
    setActiveJobId('')
    setScreen('upload')
    localStorage.removeItem(ACTIVE_JOB_STORAGE_KEY)
  }

  return (
    <div className="flex min-h-screen flex-col bg-slate-50 text-slate-900">
      <Header isAuthenticated={isAuthenticated} user={user} onLogout={handleLogout} />

      <main className="mx-auto flex w-full max-w-6xl flex-1 flex-col gap-6 px-4 py-8 sm:px-6 lg:px-8">
        {notice ? <NoticeBanner notice={notice} /> : null}

        {!isAuthenticated ? (
          <AuthScreen
            authLoading={authLoading}
            authMode={authMode}
            email={email}
            onAuthModeChange={setAuthMode}
            onEmailChange={setEmail}
            onPasswordChange={setPassword}
            onSubmit={handleAuthSubmit}
            password={password}
          />
        ) : screen === 'result' && job ? (
          <ResultScreen job={job} probabilityLabel={probabilityLabel} onNewUpload={handleNewUpload} />
        ) : (
          <UploadScreen
            activeJobId={activeJobId}
            canUpload={Boolean(canUpload)}
            fileInputRef={fileInputRef}
            isActiveJob={Boolean(isActiveJob)}
            job={job}
            onFileChange={setSelectedFile}
            onSubmit={handleUploadSubmit}
            polling={polling}
            probabilityLabel={probabilityLabel}
            selectedFile={selectedFile}
            uploadLoading={uploadLoading}
          />
        )}
      </main>

      <Footer />
    </div>
  )
}

function Header({
  isAuthenticated,
  user,
  onLogout,
}: {
  isAuthenticated: boolean
  user: User | null
  onLogout: () => void
}) {
  return (
    <header className="border-b border-slate-200 bg-white">
      <div className="mx-auto flex max-w-6xl items-center justify-between gap-4 px-4 py-4 sm:px-6 lg:px-8">
        <div>
          <p className="text-xs font-bold uppercase text-teal-700">MS/MS lipid analysis</p>
          <h1 className="text-xl font-bold tracking-tight text-slate-950">Lipid Class Classifier</h1>
        </div>
        {isAuthenticated ? (
          <div className="flex items-center gap-3">
            <span className="hidden max-w-64 truncate text-sm text-slate-600 sm:inline">{user?.email}</span>
            <button
              className="rounded-md border border-slate-300 px-3 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-100"
              onClick={onLogout}
              type="button"
            >
              Logout
            </button>
          </div>
        ) : null}
      </div>
    </header>
  )
}

function AuthScreen({
  authLoading,
  authMode,
  email,
  onAuthModeChange,
  onEmailChange,
  onPasswordChange,
  onSubmit,
  password,
}: {
  authLoading: boolean
  authMode: AuthMode
  email: string
  onAuthModeChange: (mode: AuthMode) => void
  onEmailChange: (value: string) => void
  onPasswordChange: (value: string) => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  password: string
}) {
  return (
    <section className="grid gap-8 lg:grid-cols-[1fr_420px] lg:items-center">
      <div className="max-w-2xl">
        <p className="mb-3 text-sm font-bold uppercase text-teal-700">Baseline ML workflow</p>
        <h2 className="text-4xl font-bold tracking-tight text-slate-950 sm:text-5xl">
          Classify lipid spectra from mzML files.
        </h2>
        <p className="mt-4 text-base leading-7 text-slate-600">
          Upload a mass spectrometry file, let the RabbitMQ worker run the m/z-only model, and
          view the final lipid class prediction with its probability score.
        </p>
      </div>

      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-6 grid grid-cols-2 rounded-md border border-slate-200 bg-slate-100 p-1">
          <button
            className={`rounded px-3 py-2 text-sm font-semibold ${
              authMode === 'login' ? 'bg-slate-900 text-white' : 'text-slate-600'
            }`}
            onClick={() => onAuthModeChange('login')}
            type="button"
          >
            Login
          </button>
          <button
            className={`rounded px-3 py-2 text-sm font-semibold ${
              authMode === 'register' ? 'bg-slate-900 text-white' : 'text-slate-600'
            }`}
            onClick={() => onAuthModeChange('register')}
            type="button"
          >
            Register
          </button>
        </div>

        <form className="grid gap-4" onSubmit={onSubmit}>
          <label className="grid gap-2 text-sm font-semibold text-slate-700">
            Email
            <input
              autoComplete="email"
              className="rounded-md border border-slate-300 px-3 py-2 text-slate-950 outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-100"
              inputMode="email"
              onChange={(event) => onEmailChange(event.target.value)}
              placeholder="student@example.com"
              required
              type="email"
              value={email}
            />
          </label>
          <label className="grid gap-2 text-sm font-semibold text-slate-700">
            Password
            <input
              autoComplete={authMode === 'login' ? 'current-password' : 'new-password'}
              className="rounded-md border border-slate-300 px-3 py-2 text-slate-950 outline-none focus:border-teal-600 focus:ring-2 focus:ring-teal-100"
              minLength={8}
              onChange={(event) => onPasswordChange(event.target.value)}
              placeholder="At least 8 characters"
              required
              type="password"
              value={password}
            />
          </label>
          <button
            className="rounded-md bg-teal-700 px-4 py-2.5 text-sm font-bold text-white hover:bg-teal-800 disabled:cursor-not-allowed disabled:opacity-60"
            disabled={authLoading}
            type="submit"
          >
            {authLoading ? 'Please wait...' : authMode === 'login' ? 'Login' : 'Create account'}
          </button>
        </form>
      </div>
    </section>
  )
}

function UploadScreen({
  activeJobId,
  canUpload,
  fileInputRef,
  isActiveJob,
  job,
  onFileChange,
  onSubmit,
  polling,
  probabilityLabel,
  selectedFile,
  uploadLoading,
}: {
  activeJobId: string
  canUpload: boolean
  fileInputRef: RefObject<HTMLInputElement | null>
  isActiveJob: boolean
  job: JobResponse | null
  onFileChange: (file: File | null) => void
  onSubmit: (event: FormEvent<HTMLFormElement>) => void
  polling: boolean
  probabilityLabel: string
  selectedFile: File | null
  uploadLoading: boolean
}) {
  return (
    <section className="grid gap-6 lg:grid-cols-[1fr_380px]">
      <div className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
        <div className="mb-6">
          <p className="text-sm font-bold uppercase text-teal-700">Upload</p>
          <h2 className="mt-1 text-2xl font-bold text-slate-950">Submit an mzML file</h2>
          <p className="mt-2 text-sm leading-6 text-slate-600">
            The backend stores the file, creates a job, and publishes the RabbitMQ message that
            the ML worker consumes.
          </p>
        </div>

        <form className="grid gap-4" onSubmit={onSubmit}>
          <input
            accept=".mzML"
            className="hidden"
            onChange={(event) => onFileChange(event.target.files?.[0] ?? null)}
            ref={fileInputRef}
            type="file"
          />
          <button
            className="w-fit rounded-md border border-slate-300 px-4 py-2 text-sm font-semibold text-slate-700 hover:bg-slate-100"
            onClick={() => fileInputRef.current?.click()}
            type="button"
          >
            Choose .mzML file
          </button>

          <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-4">
            <p className="font-semibold text-slate-950">{selectedFile?.name ?? 'No file selected'}</p>
            <p className="mt-1 text-sm text-slate-500">
              {selectedFile ? formatBytes(selectedFile.size) : 'Only .mzML files are accepted.'}
            </p>
          </div>

          <button
            className="rounded-md bg-teal-700 px-4 py-2.5 text-sm font-bold text-white hover:bg-teal-800 disabled:cursor-not-allowed disabled:opacity-60"
            disabled={!canUpload}
            type="submit"
          >
            {uploadLoading ? 'Uploading...' : 'Upload and start job'}
          </button>
        </form>
      </div>

      <StatusPanel
        activeJobId={activeJobId}
        isActiveJob={isActiveJob}
        job={job}
        polling={polling}
        probabilityLabel={probabilityLabel}
      />
    </section>
  )
}

function StatusPanel({
  activeJobId,
  isActiveJob,
  job,
  polling,
  probabilityLabel,
}: {
  activeJobId: string
  isActiveJob: boolean
  job: JobResponse | null
  polling: boolean
  probabilityLabel: string
}) {
  return (
    <aside className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="mb-5 flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-bold uppercase text-teal-700">Status</p>
          <h2 className="mt-1 text-2xl font-bold text-slate-950">Job updates</h2>
        </div>
        <StatusPill status={job?.status ?? null} />
      </div>

      {isActiveJob || polling ? (
        <div className="mb-4 flex items-center gap-3 rounded-md bg-amber-50 p-3 text-sm font-semibold text-amber-800">
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-amber-300 border-t-amber-800" />
          Processing through RabbitMQ worker
        </div>
      ) : null}

      <div className="grid gap-3 text-sm">
        <InfoRow label="Job ID" value={job?.job_id ?? (activeJobId || 'No active job')} />
        <InfoRow label="File" value={job?.original_filename ?? 'Awaiting upload'} />
        <InfoRow label="Prediction" value={job?.predicted_class ?? 'Pending'} />
        <InfoRow label="Probability" value={probabilityLabel} />
      </div>
    </aside>
  )
}

function ResultScreen({
  job,
  probabilityLabel,
  onNewUpload,
}: {
  job: JobResponse
  probabilityLabel: string
  onNewUpload: () => void
}) {
  const failed = job.status === 'FAILED'

  return (
    <section className="rounded-lg border border-slate-200 bg-white p-6 shadow-sm">
      <div className="mb-6 flex flex-col justify-between gap-4 sm:flex-row sm:items-start">
        <div>
          <p className="text-sm font-bold uppercase text-teal-700">Result</p>
          <h2 className="mt-1 text-3xl font-bold text-slate-950">
            {failed ? 'Prediction failed' : 'Prediction complete'}
          </h2>
          <p className="mt-2 text-sm text-slate-600">{job.original_filename}</p>
        </div>
        <StatusPill status={job.status} />
      </div>

      {failed ? (
        <div className="rounded-lg border border-red-200 bg-red-50 p-4 text-sm font-semibold text-red-800">
          {job.error_message ?? 'The worker failed while processing this job.'}
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-3">
          <ResultCard label="Predicted class" value={job.predicted_class ?? 'Unknown'} />
          <ResultCard label="Probability" value={probabilityLabel} />
          <ResultCard label="Model version" value={job.model_version ?? 'Unknown'} />
        </div>
      )}

      <div className="mt-6 grid gap-3 rounded-lg bg-slate-50 p-4 text-sm md:grid-cols-2">
        <InfoRow label="Job ID" value={job.job_id} />
        <InfoRow label="Updated" value={new Date(job.updated_at).toLocaleString()} />
      </div>

      <button
        className="mt-6 rounded-md bg-teal-700 px-4 py-2.5 text-sm font-bold text-white hover:bg-teal-800"
        onClick={onNewUpload}
        type="button"
      >
        Upload another file
      </button>
    </section>
  )
}

function StatusPill({ status }: { status: JobStatus | null }) {
  const classes =
    status === 'DONE'
      ? 'border-green-200 bg-green-50 text-green-700'
      : status === 'FAILED'
        ? 'border-red-200 bg-red-50 text-red-700'
        : status === 'PENDING' || status === 'PROCESSING'
          ? 'border-amber-200 bg-amber-50 text-amber-700'
          : 'border-slate-200 bg-slate-50 text-slate-500'

  return (
    <span className={`rounded-full border px-3 py-1 text-xs font-bold uppercase ${classes}`}>
      {status ?? 'No job'}
    </span>
  )
}

function ResultCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-slate-50 p-5">
      <p className="text-xs font-bold uppercase text-slate-500">{label}</p>
      <p className="mt-2 break-words text-2xl font-bold text-slate-950">{value}</p>
    </div>
  )
}

function InfoRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs font-bold uppercase text-slate-500">{label}</p>
      <p className="mt-1 break-words font-semibold text-slate-900">{value}</p>
    </div>
  )
}

function NoticeBanner({ notice }: { notice: Notice }) {
  const classes =
    notice.kind === 'success'
      ? 'border-green-200 bg-green-50 text-green-800'
      : notice.kind === 'error'
        ? 'border-red-200 bg-red-50 text-red-800'
        : 'border-sky-200 bg-sky-50 text-sky-800'

  return <div className={`rounded-lg border px-4 py-3 text-sm font-semibold ${classes}`}>{notice.text}</div>
}

function Footer() {
  return (
    <footer className="border-t border-slate-200 bg-white">
      <div className="mx-auto max-w-6xl px-4 py-4 text-sm text-slate-500 sm:px-6 lg:px-8">
        m/z-only baseline classifier using Spring Boot, RabbitMQ, PostgreSQL, and a Python worker.
      </div>
    </footer>
  )
}

async function apiRequest<T>(
  path: string,
  options: RequestInit & { token?: string } = {},
): Promise<T> {
  const headers = new Headers(options.headers)
  if (options.token) {
    headers.set('Authorization', `Bearer ${options.token}`)
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  })

  if (!response.ok) {
    const text = await response.text()
    throw new Error(parseErrorText(text) || `Request failed with HTTP ${response.status}`)
  }

  return response.json() as Promise<T>
}

function parseErrorText(text: string): string {
  if (!text) {
    return ''
  }
  try {
    const parsed = JSON.parse(text) as { message?: string; error?: string; detail?: string }
    return parsed.message ?? parsed.detail ?? parsed.error ?? text
  } catch {
    return text
  }
}

function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : 'Unexpected request failure'
}

function formatBytes(bytes: number): string {
  if (bytes === 0) {
    return '0 B'
  }
  const units = ['B', 'KB', 'MB', 'GB']
  const unitIndex = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  const value = bytes / 1024 ** unitIndex
  return `${value.toFixed(value >= 10 ? 0 : 1)} ${units[unitIndex]}`
}

export default App
