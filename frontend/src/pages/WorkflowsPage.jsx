import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { Workflow, Play, Clock, CheckCircle2, XCircle, Loader2, Plus, ChevronRight } from 'lucide-react';
import api from '../api/client';

const STATUS_STYLES = {
  COMPLETED: 'text-success',
  FAILED: 'text-danger',
  RUNNING: 'text-warning',
  PENDING: 'text-text-muted',
  CANCELLED: 'text-text-muted',
};

const STATUS_ICON = {
  COMPLETED: CheckCircle2,
  FAILED: XCircle,
  RUNNING: Loader2,
  PENDING: Clock,
  CANCELLED: Clock,
};

function StatusBadge({ status }) {
  const Icon = STATUS_ICON[status] || Clock;
  return (
    <span className={`flex items-center gap-1 text-xs font-medium ${STATUS_STYLES[status] || ''}`}>
      <Icon className={`w-3.5 h-3.5 ${status === 'RUNNING' ? 'animate-spin' : ''}`} />
      {status}
    </span>
  );
}

function RunWorkflowModal({ definitions, docs, onClose, onStarted }) {
  const [defId, setDefId] = useState('');
  const [docId, setDocId] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleRun = async () => {
    if (!defId || !docId) { setError('Select a workflow and document'); return; }
    setLoading(true); setError('');
    try {
      await onStarted(defId, docId);
    } catch (e) {
      setError(e.message || 'Failed to start');
      setLoading(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="card w-full max-w-md space-y-4">
        <h2 className="text-lg font-bold">Run Workflow</h2>

        <div>
          <label className="text-xs text-text-muted mb-1 block">Workflow</label>
          <select value={defId} onChange={e => setDefId(e.target.value)} className="input-field w-full text-sm">
            <option value="">Choose a workflow…</option>
            {definitions.map(d => (
              <option key={d.id} value={d.id}>
                {d.predefined ? '⚡ ' : '🔧 '}{d.name}
              </option>
            ))}
          </select>
          {defId && (
            <p className="text-xs text-text-muted mt-1">
              {definitions.find(d => d.id === defId)?.description}
            </p>
          )}
        </div>

        <div>
          <label className="text-xs text-text-muted mb-1 block">Document</label>
          <select value={docId} onChange={e => setDocId(e.target.value)} className="input-field w-full text-sm">
            <option value="">Choose a document…</option>
            {docs.map(d => (
              <option key={d.id} value={d.id}>{d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}</option>
            ))}
          </select>
        </div>

        {defId && (
          <div className="bg-surface-el rounded-lg p-3 space-y-1">
            <p className="text-xs text-text-muted font-medium mb-2">Steps</p>
            {definitions.find(d => d.id === defId)?.steps.map((step, i) => (
              <div key={i} className="flex items-center gap-2 text-xs text-text-secondary">
                <span className="w-5 h-5 rounded-full bg-primary/10 text-primary flex items-center justify-center text-[10px] font-bold shrink-0">{i + 1}</span>
                {step.label}
              </div>
            ))}
          </div>
        )}

        {error && <p className="text-danger text-sm">{error}</p>}

        <div className="flex gap-2 justify-end">
          <button onClick={onClose} className="btn-secondary text-sm">Cancel</button>
          <button onClick={handleRun} disabled={loading} className="btn-primary flex items-center gap-2 text-sm">
            {loading ? <Loader2 className="w-4 h-4 animate-spin" /> : <Play className="w-4 h-4" />}
            {loading ? 'Starting…' : 'Run'}
          </button>
        </div>
      </div>
    </div>
  );
}

export default function WorkflowsPage() {
  const navigate = useNavigate();
  const [definitions, setDefinitions] = useState([]);
  const [runs, setRuns] = useState([]);
  const [docs, setDocs] = useState([]);
  const [showModal, setShowModal] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    Promise.all([
      api.get('/workflows/definitions'),
      api.get('/workflows/runs'),
      api.get('/documents?size=100'),
    ]).then(([defs, runsRes, docsRes]) => {
      setDefinitions(defs.data);
      setRuns(runsRes.data);
      setDocs(docsRes.data.content || []);
    }).catch(e => {
      setError(e.response?.data?.message || 'Failed to load workflows');
    }).finally(() => setLoading(false));
  }, []);

  const handleStarted = async (defId, docId) => {
    navigate(`/workflows/run?definitionId=${defId}&documentId=${docId}`);
  };

  const formatDate = (ts) => ts ? new Date(ts).toLocaleString() : '—';
  const getDuration = (run) => {
    if (!run.completedAt) return run.status === 'RUNNING' ? 'Running…' : '—';
    const ms = new Date(run.completedAt) - new Date(run.startedAt);
    if (ms < 60000) return `${Math.round(ms / 1000)}s`;
    return `${Math.round(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
  };

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Workflows</h1>
        <div className="space-y-3">
          {[1, 2, 3].map(i => <div key={i} className="card h-16 animate-pulse bg-surface-el" />)}
        </div>
      </div>
    );
  }

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Workflows</h1>
        <div className="flex gap-2">
          <button onClick={() => navigate('/workflows/builder')} className="btn-secondary flex items-center gap-2 text-sm">
            <Plus className="w-4 h-4" /> New Workflow
          </button>
          <button onClick={() => setShowModal(true)} className="btn-primary flex items-center gap-2 text-sm">
            <Play className="w-4 h-4" /> Run Workflow
          </button>
        </div>
      </div>

      {error && (
        <div className="card border-l-4 border-danger bg-danger/5 mb-4">
          <p className="text-danger text-sm">{error}</p>
        </div>
      )}

      {/* Predefined workflows */}
      <section className="mb-8">
        <h2 className="text-sm font-semibold text-text-muted uppercase tracking-wide mb-3">Predefined Workflows</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
          {definitions.filter(d => d.predefined).map(def => (
            <div key={def.id} className="card hover:border-primary/30 transition-colors">
              <div className="flex items-start justify-between mb-2">
                <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
                  <Workflow className="w-4 h-4 text-primary" />
                </div>
                <span className="text-xs text-text-muted bg-surface-el px-2 py-0.5 rounded-full">
                  {def.steps.length} steps
                </span>
              </div>
              <h3 className="font-semibold text-text-primary mb-1">{def.name}</h3>
              <p className="text-xs text-text-muted mb-3">{def.description}</p>
              <div className="flex flex-wrap gap-1 mb-3">
                {def.steps.map((s, i) => (
                  <span key={i} className="text-[10px] bg-primary/5 text-primary border border-primary/15 px-2 py-0.5 rounded-full">
                    {s.label}
                  </span>
                ))}
              </div>
              <button
                onClick={() => { setShowModal(true); }}
                className="btn-primary w-full text-xs flex items-center justify-center gap-1.5"
              >
                <Play className="w-3.5 h-3.5" /> Run
              </button>
            </div>
          ))}
        </div>
      </section>

      {/* Custom workflows */}
      {definitions.filter(d => !d.predefined).length > 0 && (
        <section className="mb-8">
          <h2 className="text-sm font-semibold text-text-muted uppercase tracking-wide mb-3">Custom Workflows</h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {definitions.filter(d => !d.predefined).map(def => (
              <div key={def.id} className="card hover:border-primary/30 transition-colors">
                <div className="flex items-start justify-between mb-2">
                  <div className="w-8 h-8 rounded-lg bg-gold/10 flex items-center justify-center">
                    <Workflow className="w-4 h-4 text-gold" />
                  </div>
                  <span className="text-xs text-text-muted bg-surface-el px-2 py-0.5 rounded-full">
                    {def.steps.length} steps
                  </span>
                </div>
                <h3 className="font-semibold text-text-primary mb-1">{def.name}</h3>
                <p className="text-xs text-text-muted mb-3">{def.description || 'Custom workflow'}</p>
                <div className="flex flex-wrap gap-1 mb-3">
                  {def.steps.map((s, i) => (
                    <span key={i} className="text-[10px] bg-gold/5 text-gold border border-gold/15 px-2 py-0.5 rounded-full">
                      {s.label}
                    </span>
                  ))}
                </div>
                <button
                  onClick={() => setShowModal(true)}
                  className="btn-primary w-full text-xs flex items-center justify-center gap-1.5"
                >
                  <Play className="w-3.5 h-3.5" /> Run
                </button>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* Recent runs */}
      <section>
        <h2 className="text-sm font-semibold text-text-muted uppercase tracking-wide mb-3">Recent Runs</h2>
        {runs.length === 0 ? (
          <div className="card text-center py-10">
            <Workflow className="w-10 h-10 text-text-muted mx-auto mb-3" />
            <p className="text-text-muted text-sm">No runs yet. Run a workflow above to get started.</p>
          </div>
        ) : (
          <div className="card !p-0 overflow-hidden">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-border text-xs text-text-muted">
                  <th className="text-left px-4 py-3 font-medium">Workflow</th>
                  <th className="text-left px-4 py-3 font-medium">Status</th>
                  <th className="text-left px-4 py-3 font-medium">Progress</th>
                  <th className="text-left px-4 py-3 font-medium">Duration</th>
                  <th className="text-left px-4 py-3 font-medium">Started</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {runs.map(run => (
                  <tr key={run.id} className="hover:bg-surface-el/50">
                    <td className="px-4 py-3 font-medium text-text-primary">{run.workflowName}</td>
                    <td className="px-4 py-3"><StatusBadge status={run.status} /></td>
                    <td className="px-4 py-3 text-text-muted">{run.currentStep}/{run.totalSteps}</td>
                    <td className="px-4 py-3 text-text-muted">{getDuration(run)}</td>
                    <td className="px-4 py-3 text-text-muted">{formatDate(run.startedAt)}</td>
                    <td className="px-4 py-3">
                      <button
                        onClick={() => navigate(`/workflows/run/${run.id}`)}
                        className="text-primary hover:underline text-xs flex items-center gap-0.5"
                      >
                        View <ChevronRight className="w-3 h-3" />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>

      {showModal && (
        <RunWorkflowModal
          definitions={definitions}
          docs={docs}
          onClose={() => setShowModal(false)}
          onStarted={handleStarted}
        />
      )}
    </div>
  );
}
