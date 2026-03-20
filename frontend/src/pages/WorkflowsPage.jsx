import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import { Workflow, Play, Clock, CheckCircle2, XCircle, Loader2, Plus, ChevronRight, BarChart3, Users, Trash2, Zap, Search, X } from 'lucide-react';
import api from '../api/client';

const STATUS_STYLES = {
  COMPLETED: 'text-success', FAILED: 'text-danger',
  RUNNING: 'text-warning', PENDING: 'text-text-muted', CANCELLED: 'text-text-muted',
};
const STATUS_ICON = {
  COMPLETED: CheckCircle2, FAILED: XCircle, RUNNING: Loader2, PENDING: Clock, CANCELLED: Clock,
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

  const selectedDef = definitions.find(d => d.id === defId);
  const isDraftWorkflow = selectedDef?.steps?.some(s => s.type === 'DRAFT_CLAUSE');

  const handleRun = async () => {
    if (!defId) { setError('Select a workflow'); return; }
    if (!isDraftWorkflow && !docId) { setError('Select a document'); return; }
    setLoading(true); setError('');
    try { await onStarted(defId, docId || null); }
    catch (e) { setError(e.message || 'Failed to start'); setLoading(false); }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60">
      <div className="card w-full max-w-md space-y-4">
        <h2 className="text-lg font-bold">Run Workflow</h2>

        <div>
          <label className="text-xs text-text-muted mb-1 block">Workflow</label>
          <select value={defId} onChange={e => { setDefId(e.target.value); setDocId(''); }} className="input-field w-full text-sm">
            <option value="">Choose a workflow…</option>
            {definitions.map(d => (
              <option key={d.id} value={d.id}>
                {d.predefined ? '⚡ ' : d.team ? '👥 ' : '🔧 '}{d.name}
              </option>
            ))}
          </select>
          {selectedDef && <p className="text-xs text-text-muted mt-1">{selectedDef.description}</p>}
        </div>

        <div>
          <label className="text-xs text-text-muted mb-1 block">
            Document {isDraftWorkflow && <span className="text-primary ml-1">(optional — draft runs without a document)</span>}
          </label>
          {isDraftWorkflow && !docId && (
            <div className="bg-primary/5 border border-primary/20 rounded-lg px-3 py-2 mb-2 text-xs text-primary">
              This workflow drafts new content from scratch using the firm's clause library and corpus — no existing document needed. Optionally select one to use as deal context.
            </div>
          )}
          <select value={docId} onChange={e => setDocId(e.target.value)} className="input-field w-full text-sm">
            <option value="">{isDraftWorkflow ? 'No document (draft from scratch)' : 'Choose a document…'}</option>
            {docs.map(d => (
              <option key={d.id} value={d.id}>{d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}</option>
            ))}
          </select>
        </div>

        {selectedDef && (
          <div className="bg-surface-el rounded-lg p-3 space-y-1">
            <p className="text-xs text-text-muted font-medium mb-2">{selectedDef.steps.length} steps</p>
            {selectedDef.steps.map((step, i) => (
              <div key={i} className="flex items-center gap-2 text-xs text-text-secondary">
                <span className="w-5 h-5 rounded-full bg-primary/10 text-primary flex items-center justify-center text-[10px] font-bold shrink-0">{i + 1}</span>
                <span className="flex-1">{step.label}</span>
                {step.condition && (
                  <span className="text-[9px] bg-warning/10 text-warning border border-warning/20 px-1.5 py-0.5 rounded-full">conditional</span>
                )}
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

function WorkflowCard({ def, onRun, onDelete, onPromote, canAdmin }) {
  const isCustom = !def.predefined;
  const tagColor = def.predefined ? 'text-primary bg-primary/5 border-primary/15'
    : def.team ? 'text-success bg-success/5 border-success/15'
    : 'text-gold bg-gold/5 border-gold/15';

  return (
    <div className="card hover:border-primary/30 transition-colors flex flex-col">
      <div className="flex items-start justify-between mb-2">
        <div className="w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center">
          <Workflow className="w-4 h-4 text-primary" />
        </div>
        <div className="flex items-center gap-1">
          <span className={`text-[10px] border px-2 py-0.5 rounded-full ${tagColor}`}>
            {def.predefined ? '⚡ System' : def.team ? '👥 Team' : '🔧 Custom'}
          </span>
          <span className="text-xs text-text-muted">{def.steps?.length} steps</span>
        </div>
      </div>
      <div className="flex items-center gap-1.5 mb-1">
        <h3 className="font-semibold text-text-primary">{def.name}</h3>
        {def.autoTrigger && (
          <span className="flex items-center gap-0.5 text-[9px] bg-warning/10 text-warning border border-warning/20 px-1.5 py-0.5 rounded-full shrink-0" title="Auto-runs on document upload">
            <Zap className="w-2.5 h-2.5" /> Auto
          </span>
        )}
      </div>
      <p className="text-xs text-text-muted mb-3 flex-1">{def.description}</p>
      <div className="flex flex-wrap gap-1 mb-3">
        {def.steps?.map((s, i) => (
          <div key={i} className="flex items-center gap-0.5">
            <span className="text-[10px] bg-surface-el text-text-secondary border border-border/50 px-1.5 py-0.5 rounded-full">{s.label}</span>
            {s.condition && <span className="text-[9px] text-warning">*</span>}
          </div>
        ))}
      </div>
      <div className="flex gap-1.5">
        <button onClick={onRun} className="btn-primary flex-1 text-xs flex items-center justify-center gap-1.5">
          <Play className="w-3.5 h-3.5" /> Run
        </button>
        {isCustom && canAdmin && !def.team && (
          <button onClick={onPromote} className="btn-secondary text-xs px-2" title="Share with team">
            <Users className="w-3.5 h-3.5" />
          </button>
        )}
        {isCustom && (
          <button onClick={onDelete} className="text-text-muted hover:text-danger transition-colors px-2" title="Delete">
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        )}
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
  const [preselectedDef, setPreselectedDef] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [matterFilter, setMatterFilter] = useState('');
  const [runsLoading, setRunsLoading] = useState(false);

  useEffect(() => {
    Promise.all([
      api.get('/workflows/definitions'),
      api.get('/workflows/runs'),
      api.get('/documents?size=100'),
    ]).then(([defs, runsRes, docsRes]) => {
      setDefinitions(defs.data);
      setRuns(runsRes.data);
      setDocs(docsRes.data.content || []);
    }).catch(e => setError(e.response?.data?.message || 'Failed to load workflows'))
      .finally(() => setLoading(false));
  }, []);

  const fetchRuns = useCallback((filter) => {
    setRunsLoading(true);
    const params = filter ? `?matterRef=${encodeURIComponent(filter)}` : '';
    api.get(`/workflows/runs${params}`)
      .then(r => setRuns(r.data))
      .catch(() => {})
      .finally(() => setRunsLoading(false));
  }, []);

  useEffect(() => {
    if (!loading) fetchRuns(matterFilter);
  }, [matterFilter, loading, fetchRuns]);

  const handleStarted = async (defId, docId) => {
    setShowModal(false);
    const params = new URLSearchParams({ definitionId: defId });
    if (docId) params.set('documentId', docId);
    navigate(`/workflows/run?${params.toString()}`);
  };

  const handleDelete = async (id) => {
    try {
      await api.delete(`/workflows/definitions/${id}`);
      setDefinitions(prev => prev.filter(d => d.id !== id));
    } catch (e) {
      alert(e.response?.data?.message || 'Delete failed');
    }
  };

  const handlePromote = async (id) => {
    try {
      const res = await api.patch(`/workflows/definitions/${id}/promote`);
      setDefinitions(prev => prev.map(d => d.id === id ? res.data : d));
    } catch (e) {
      alert(e.response?.data?.message || 'Promote failed');
    }
  };

  const formatDate = (ts) => ts ? new Date(ts).toLocaleString() : '—';
  const getDuration = (run) => {
    if (!run.completedAt) return run.status === 'RUNNING' ? 'Running…' : '—';
    const ms = new Date(run.completedAt) - new Date(run.startedAt);
    if (ms < 60000) return `${Math.round(ms / 1000)}s`;
    return `${Math.round(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
  };

  const predefined = definitions.filter(d => d.predefined);
  const team = definitions.filter(d => !d.predefined && d.team);
  const custom = definitions.filter(d => !d.predefined && !d.team);

  if (loading) return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Workflows</h1>
      <div className="grid grid-cols-3 gap-4">
        {[1,2,3].map(i => <div key={i} className="card h-40 animate-pulse bg-surface-el" />)}
      </div>
    </div>
  );

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Workflows</h1>
        <div className="flex gap-2">
          <button onClick={() => navigate('/workflows/analytics')} className="btn-secondary flex items-center gap-2 text-sm">
            <BarChart3 className="w-4 h-4" /> Analytics
          </button>
          <button onClick={() => navigate('/workflows/builder')} className="btn-secondary flex items-center gap-2 text-sm">
            <Plus className="w-4 h-4" /> Build
          </button>
          <button onClick={() => { setPreselectedDef(null); setShowModal(true); }} className="btn-primary flex items-center gap-2 text-sm">
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
        <h2 className="text-xs font-semibold text-text-muted uppercase tracking-wide mb-3">System Workflows</h2>
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          {predefined.map(def => (
            <WorkflowCard key={def.id} def={def} onRun={() => { setPreselectedDef(def.id); setShowModal(true); }} canAdmin={false} />
          ))}
        </div>
      </section>

      {/* Team workflows */}
      {team.length > 0 && (
        <section className="mb-8">
          <h2 className="text-xs font-semibold text-text-muted uppercase tracking-wide mb-3">Team Workflows</h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {team.map(def => (
              <WorkflowCard key={def.id} def={def}
                onRun={() => { setPreselectedDef(def.id); setShowModal(true); }}
                onDelete={() => handleDelete(def.id)}
                onPromote={() => handlePromote(def.id)}
                canAdmin={true} />
            ))}
          </div>
        </section>
      )}

      {/* Custom workflows */}
      {custom.length > 0 && (
        <section className="mb-8">
          <h2 className="text-xs font-semibold text-text-muted uppercase tracking-wide mb-3">My Workflows</h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            {custom.map(def => (
              <WorkflowCard key={def.id} def={def}
                onRun={() => { setPreselectedDef(def.id); setShowModal(true); }}
                onDelete={() => handleDelete(def.id)}
                onPromote={() => handlePromote(def.id)}
                canAdmin={true} />
            ))}
          </div>
        </section>
      )}

      {/* Recent runs */}
      <section>
        <div className="flex items-center justify-between mb-3">
          <h2 className="text-xs font-semibold text-text-muted uppercase tracking-wide">Recent Runs</h2>
          <div className="relative">
            <Search className="absolute left-2.5 top-1/2 -translate-y-1/2 w-3.5 h-3.5 text-text-muted pointer-events-none" />
            <input
              value={matterFilter}
              onChange={e => setMatterFilter(e.target.value)}
              placeholder="Filter by matter ref…"
              className="input-field text-xs pl-8 pr-7 py-1.5 w-52"
            />
            {matterFilter && (
              <button onClick={() => setMatterFilter('')} className="absolute right-2 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-primary">
                <X className="w-3.5 h-3.5" />
              </button>
            )}
          </div>
        </div>
        {runsLoading ? (
          <div className="card text-center py-10">
            <Loader2 className="w-6 h-6 text-text-muted mx-auto animate-spin" />
          </div>
        ) : runs.length === 0 ? (
          <div className="card text-center py-10">
            <Workflow className="w-10 h-10 text-text-muted mx-auto mb-3" />
            <p className="text-text-muted text-sm">{matterFilter ? `No runs found for matter "${matterFilter}".` : 'No runs yet. Run a workflow to get started.'}</p>
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
                  <th className="text-left px-4 py-3 font-medium">Matter</th>
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
                    <td className="px-4 py-3 text-text-muted text-xs">{run.matterRef || '—'}</td>
                    <td className="px-4 py-3 text-text-muted">{formatDate(run.startedAt)}</td>
                    <td className="px-4 py-3">
                      <button onClick={() => navigate(`/workflows/run/${run.id}`)} className="text-primary hover:underline text-xs flex items-center gap-0.5">
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
