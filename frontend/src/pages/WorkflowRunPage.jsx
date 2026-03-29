import { useState, useEffect, useRef } from 'react';
import { useParams, useSearchParams, useNavigate } from 'react-router-dom';
import {
  CheckCircle2, XCircle, Loader2, Clock, ChevronDown, ChevronUp,
  ArrowLeft, ShieldAlert, Key, ClipboardList, Workflow, FileText,
  Sparkles, Download, Briefcase, SkipForward, RefreshCw, PenLine,
  Globe, Mail, Zap, AlertTriangle, MessageSquare
} from 'lucide-react';
import api from '../api/client';

const STEP_ICONS = {
  EXTRACT_KEY_TERMS: Key,
  RISK_ASSESSMENT: ShieldAlert,
  CLAUSE_CHECKLIST: ClipboardList,
  GENERATE_SUMMARY: FileText,
  REDLINE_SUGGESTIONS: Sparkles,
  DRAFT_CLAUSE: PenLine,
};

const RISK_COLOR = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'success' };

function StepCard({ step, status, result, iteration }) {
  const [open, setOpen] = useState(false);
  const Icon = STEP_ICONS[step.type] || Workflow;

  const borderColor = status === 'done' ? 'border-success'
    : status === 'running' || status === 'refining' ? 'border-warning'
    : status === 'error' ? 'border-danger'
    : status === 'skipped' ? 'border-border'
    : 'border-border';

  const statusIcon = status === 'done' ? <CheckCircle2 className="w-5 h-5 text-success" />
    : status === 'refining' ? <RefreshCw className="w-5 h-5 text-primary animate-spin" />
    : status === 'running' ? <Loader2 className="w-5 h-5 text-warning animate-spin" />
    : status === 'error' ? <XCircle className="w-5 h-5 text-danger" />
    : status === 'skipped' ? <SkipForward className="w-5 h-5 text-text-muted" />
    : <Clock className="w-5 h-5 text-text-muted" />;

  return (
    <div className={`card border-l-4 ${borderColor} !p-0 overflow-hidden ${status === 'skipped' ? 'opacity-50' : ''}`}>
      <button
        onClick={() => result && setOpen(o => !o)}
        className="flex items-center gap-3 w-full px-4 py-3 text-left"
        disabled={!result}
      >
        <div className="w-8 h-8 rounded-lg bg-surface-el flex items-center justify-center shrink-0">
          <Icon className="w-4 h-4 text-text-secondary" />
        </div>
        <div className="flex-1">
          <p className="text-sm font-medium text-text-primary">{step.label}</p>
          <p className="text-xs text-text-muted flex items-center gap-2">
            <span>{step.type.replace(/_/g, ' ')}</span>
            {status === 'skipped' && <span>— skipped (condition not met)</span>}
            {status === 'refining' && iteration && (
              <span className="inline-flex items-center gap-1 text-primary font-medium">
                <RefreshCw className="w-3 h-3" /> Refining pass {iteration.current}/{iteration.max}…
              </span>
            )}
          </p>
        </div>
        {statusIcon}
        {result && (open ? <ChevronUp className="w-4 h-4 text-text-muted ml-1 shrink-0" /> : <ChevronDown className="w-4 h-4 text-text-muted ml-1 shrink-0" />)}
      </button>

      {open && result && (
        <div className="border-t border-border/50 px-4 pb-4 pt-3">
          <ResultPreview type={step.type} result={result} />
        </div>
      )}
    </div>
  );
}

function ResultPreview({ type, result }) {
  if (type === 'EXTRACT_KEY_TERMS') {
    const fields = [
      ['Party A', result.partyA], ['Party B', result.partyB],
      ['Effective Date', result.effectiveDate], ['Expiry Date', result.expiryDate],
      ['Contract Value', result.contractValue], ['Liability Cap', result.liabilityCap],
      ['Governing Law', result.governingLaw],
      ['Notice Period', result.noticePeriodDays ? `${result.noticePeriodDays} days` : null],
      ['Arbitration Venue', result.arbitrationVenue],
    ].filter(([, v]) => v);
    return (
      <div className="grid grid-cols-2 gap-2">
        {fields.map(([label, value]) => (
          <div key={label}>
            <p className="text-xs text-text-muted">{label}</p>
            <p className="text-sm text-text-primary font-medium">{value}</p>
          </div>
        ))}
      </div>
    );
  }

  if (type === 'RISK_ASSESSMENT') {
    return (
      <div>
        <div className="flex items-center gap-2 mb-3">
          <span className="text-sm text-text-muted">Overall:</span>
          <span className={`badge-${RISK_COLOR[result.overallRisk] || 'medium'} font-bold`}>{result.overallRisk}</span>
        </div>
        <div className="space-y-1.5">
          {result.categories?.slice(0, 5).map((cat, i) => (
            <div key={i} className="flex items-start gap-2">
              <span className={`badge-${RISK_COLOR[cat.rating] || 'medium'} text-xs shrink-0 mt-0.5`}>{cat.rating}</span>
              <div>
                <p className="text-xs font-medium text-text-primary">{cat.name}</p>
                <p className="text-xs text-text-muted">{cat.justification}</p>
              </div>
            </div>
          ))}
          {result.categories?.length > 5 && (
            <p className="text-xs text-text-muted">+{result.categories.length - 5} more</p>
          )}
        </div>
      </div>
    );
  }

  if (type === 'CLAUSE_CHECKLIST') {
    const present = result.clauses?.filter(c => c.status === 'PRESENT').length || 0;
    const weak = result.clauses?.filter(c => c.status === 'WEAK').length || 0;
    const missing = result.clauses?.filter(c => c.status === 'MISSING').length || 0;
    return (
      <div>
        <div className="flex gap-4 mb-2">
          <span className="text-success text-sm font-medium">{present} Present</span>
          <span className="text-warning text-sm font-medium">{weak} Weak</span>
          <span className="text-danger text-sm font-medium">{missing} Missing</span>
        </div>
        {result.criticalMissingClauses?.length > 0 && (
          <div className="flex flex-wrap gap-1">
            {result.criticalMissingClauses.map(c => (
              <span key={c} className="text-xs bg-danger/10 text-danger border border-danger/20 px-2 py-0.5 rounded-full">{c}</span>
            ))}
          </div>
        )}
      </div>
    );
  }

  if (type === 'GENERATE_SUMMARY') {
    return (
      <div className="space-y-3">
        {result.executiveSummary && (
          <p className="text-sm text-text-secondary">{result.executiveSummary}</p>
        )}
        {result.topConcerns?.length > 0 && (
          <div>
            <p className="text-xs font-semibold text-text-muted mb-1">Top Concerns</p>
            <ul className="space-y-0.5">
              {result.topConcerns.map((c, i) => (
                <li key={i} className="text-xs text-text-secondary flex gap-1.5">
                  <span className="text-warning shrink-0">•</span>{c}
                </li>
              ))}
            </ul>
          </div>
        )}
        {result.redFlags?.length > 0 && (
          <div>
            <p className="text-xs font-semibold text-danger mb-1">Red Flags</p>
            <ul className="space-y-0.5">
              {result.redFlags.map((f, i) => (
                <li key={i} className="text-xs text-danger flex gap-1.5">
                  <span className="shrink-0">⚠</span>{f}
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    );
  }

  if (type === 'REDLINE_SUGGESTIONS') {
    return (
      <div className="space-y-3">
        {result.suggestions?.length === 0 && (
          <p className="text-xs text-text-muted">No weak or missing clauses to redline.</p>
        )}
        {result.suggestions?.map((s, i) => (
          <div key={i} className="border border-border rounded-lg p-3">
            <p className="text-xs font-semibold text-text-primary mb-1">{s.clauseName}</p>
            <p className="text-xs text-danger mb-2">Issue: {s.issue}</p>
            <div className="bg-success/5 border border-success/20 rounded p-2 mb-2">
              <p className="text-xs text-text-muted mb-0.5 font-medium">Suggested Language:</p>
              <p className="text-xs text-text-primary leading-relaxed">{s.suggestedLanguage}</p>
            </div>
            <p className="text-xs text-text-muted">{s.rationale}</p>
          </div>
        ))}
      </div>
    );
  }

  if (type === 'DRAFT_CLAUSE') {
    return (
      <div className="space-y-2">
        {result.clauseType && (
          <p className="text-xs font-semibold text-text-muted">Clause type: {result.clauseType}</p>
        )}
        <div className="bg-surface-el rounded-lg p-3 max-h-64 overflow-y-auto">
          <pre className="text-xs text-text-secondary whitespace-pre-wrap leading-relaxed">{result.content}</pre>
        </div>
      </div>
    );
  }

  return <pre className="text-xs text-text-muted overflow-auto max-h-40">{JSON.stringify(result, null, 2)}</pre>;
}

const CONNECTOR_ICONS = { WEBHOOK: Globe, EMAIL: Mail, SLACK: MessageSquare };

function ConnectorStatusPanel({ connectors, connectorLogs, overallStatus }) {
  if (!connectors || connectors.length === 0) return null;

  // Map connector logs by type for quick lookup (last entry wins if multiple of same type)
  const logByIndex = {};
  connectorLogs?.forEach((log, i) => { logByIndex[i] = log; });

  const isComplete = overallStatus === 'done' || overallStatus === 'error';

  return (
    <div className="card mb-6">
      <div className="flex items-center gap-2 mb-3">
        <Zap className="w-4 h-4 text-primary" />
        <h3 className="text-sm font-semibold">Output Connectors</h3>
        <span className="text-[10px] bg-primary/15 text-primary px-1.5 py-0.5 rounded-full font-medium">
          {connectors.length}
        </span>
      </div>

      <div className="space-y-2">
        {connectors.map((c, i) => {
          const Icon = CONNECTOR_ICONS[c.type] || Zap;
          const log = connectorLogs?.find(l => l.type === c.type);
          let label;
          try {
            label = c.type === 'WEBHOOK'
              ? (c.config?.url ? new URL(c.config.url).hostname : 'Webhook')
              : (c.config?.recipients || 'Email');
          } catch {
            label = c.config?.url || 'Webhook';
          }

          let statusBadge;
          if (!isComplete) {
            statusBadge = <span className="text-[10px] text-text-muted bg-surface-el px-2 py-0.5 rounded-full">Pending</span>;
          } else if (log?.status === 'SUCCESS') {
            statusBadge = <span className="text-[10px] text-success bg-success/10 border border-success/20 px-2 py-0.5 rounded-full flex items-center gap-1"><CheckCircle2 className="w-3 h-3" /> Fired</span>;
          } else if (log?.status === 'FAILED') {
            statusBadge = <span className="text-[10px] text-danger bg-danger/10 border border-danger/20 px-2 py-0.5 rounded-full flex items-center gap-1"><XCircle className="w-3 h-3" /> Failed</span>;
          } else if (isComplete) {
            statusBadge = <span className="text-[10px] text-text-muted bg-surface-el px-2 py-0.5 rounded-full flex items-center gap-1"><Loader2 className="w-3 h-3 animate-spin" /> Firing…</span>;
          }

          return (
            <div key={i} className="flex items-start gap-3 p-2.5 bg-surface-el rounded-lg">
              <div className="w-7 h-7 rounded bg-primary/10 flex items-center justify-center shrink-0 mt-0.5">
                <Icon className="w-3.5 h-3.5 text-primary" />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2">
                  <p className="text-xs font-medium text-text-primary">{c.type === 'WEBHOOK' ? 'Webhook' : 'Email'}</p>
                  {statusBadge}
                </div>
                <p className="text-[11px] text-text-muted truncate">{label}</p>
                {log?.error && (
                  <p className="text-[10px] text-danger mt-0.5 flex items-center gap-1">
                    <AlertTriangle className="w-3 h-3 shrink-0" /> {log.error}
                  </p>
                )}
                {log?.firedAt && log?.status === 'SUCCESS' && (
                  <div className="text-[10px] text-text-muted mt-0.5">
                    <span>{new Date(log.firedAt).toLocaleTimeString()}</span>
                    {log.recipients && (
                      <span> — sent to {Array.isArray(log.recipients) ? log.recipients.join(', ') : log.recipients}</span>
                    )}
                    {log.from && (
                      <span> (from: {log.from})</span>
                    )}
                    {log.url && (
                      <span> — {log.url}</span>
                    )}
                  </div>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

export default function WorkflowRunPage() {
  const { id } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const [workflowName, setWorkflowName] = useState('AI Agent');
  const [runId, setRunId] = useState(id || null);
  const [steps, setSteps] = useState([]);
  const [stepStatuses, setStepStatuses] = useState({});
  const [stepResults, setStepResults] = useState({});
  const [overallStatus, setOverallStatus] = useState('running');
  const [error, setError] = useState('');
  const [logs, setLogs] = useState([]);
  const [matterRef, setMatterRef] = useState('');
  const [associating, setAssociating] = useState(false);
  const [matterSaved, setMatterSaved] = useState(false);
  const [matterError, setMatterError] = useState('');
  const [stepIterations, setStepIterations] = useState({}); // stepIndex → {current, max}
  const [connectors, setConnectors] = useState([]);         // from workflow definition
  const [connectorLogs, setConnectorLogs] = useState([]);   // fire results from run
  const [refinementInfo, setRefinementInfo] = useState(null); // {reason, draftStepIndex}

  const definitionId      = searchParams.get('definitionId');
  const documentId        = searchParams.get('documentId');
  const partyA            = searchParams.get('partyA');
  const partyB            = searchParams.get('partyB');
  const jurisdiction      = searchParams.get('jurisdiction');
  const dealBrief         = searchParams.get('dealBrief');
  const runtimeConnectors = searchParams.get('runtimeConnectors');

  const addLog = (msg) => setLogs(l => [...l, { ts: new Date().toLocaleTimeString(), msg }]);

  useEffect(() => {
    if (id) {
      // View existing run — fetch run + definition to reconstruct step list
      api.get(`/workflows/runs/${id}`).then(async r => {
        const run = r.data;
        setWorkflowName(run.workflowName);
        setRunId(run.id);
        setMatterRef(run.matterRef || '');

        const status = run.status === 'COMPLETED' ? 'done'
          : run.status === 'FAILED' ? 'error' : 'running';
        setOverallStatus(status);

        // Reconstruct step list from definition
        try {
          const defRes = await api.get(`/workflows/definitions`);
          const def = defRes.data.find(d => d.id === run.workflowDefinitionId);
          if (def?.steps) {
            setSteps(def.steps);
            // Mark all steps up to currentStep as completed, rest as pending
            const statuses = {};
            def.steps.forEach((_, i) => {
              if (status === 'done') statuses[i] = 'completed';
              else if (i < run.currentStep) statuses[i] = 'completed';
              else if (i === run.currentStep && status === 'running') statuses[i] = 'running';
              else statuses[i] = 'pending';
            });
            setStepStatuses(statuses);
          }
          if (def?.connectors) setConnectors(def.connectors);
        } catch { /* definition fetch failed, steps won't show */ }

        if (run.connectorLogs) setConnectorLogs(run.connectorLogs);

        // Map results keyed by step type to keyed by step index
        if (run.results) {
          setStepResults(run.results);
        }
      });
      return;
    }

    if (!definitionId) return;

    const controller = new AbortController();

    const runParams = new URLSearchParams({ definitionId });
    if (documentId)        runParams.set('documentId',        documentId);
    if (partyA)            runParams.set('partyA',             partyA);
    if (partyB)            runParams.set('partyB',             partyB);
    if (jurisdiction)      runParams.set('jurisdiction',       jurisdiction);
    if (dealBrief)         runParams.set('dealBrief',          dealBrief);
    if (runtimeConnectors) runParams.set('runtimeConnectors',  runtimeConnectors);

    fetch(`/api/v1/workflows/runs?${runParams.toString()}`, {
      method: 'POST',
      credentials: 'include',
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    }).then(response => {
      if (!response.ok) { setError(`HTTP ${response.status}`); setOverallStatus('error'); return; }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      let event = null;

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) return;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop();

          for (const line of lines) {
            if (line.startsWith('event:')) {
              event = line.slice(6).trim();
            } else if (line.startsWith('data:') && event) {
              try {
                const data = JSON.parse(line.slice(5).trim());
                handleEvent(event, data);
              } catch { /* ignore malformed */ }
              event = null;
            }
          }
          read();
        }).catch(e => {
          if (e.name !== 'AbortError') { setError('Stream disconnected'); setOverallStatus('error'); }
        });
      }
      read();
    }).catch(e => {
      if (e.name !== 'AbortError') { setError(e.message); setOverallStatus('error'); }
    });

    return () => controller.abort();
  }, [definitionId, documentId, partyA, partyB, jurisdiction, dealBrief, runtimeConnectors, id]);

  function handleEvent(event, data) {
    if (event === 'workflow_start') {
      setWorkflowName(data.workflowName);
      setRunId(data.runId);
      addLog(`Started: ${data.workflowName} (${data.totalSteps} steps)`);
      // Fetch connectors from definition so we can show them in the connector panel
      if (definitionId) {
        api.get('/workflows/definitions').then(r => {
          const def = r.data.find(d => d.id === definitionId);
          if (def?.connectors?.length) setConnectors(def.connectors);
        }).catch(() => {});
      }
    } else if (event === 'workflow_refinement') {
      setRefinementInfo({ reason: data.reason, draftStepIndex: data.draftStepIndex });
      addLog(`Re-drafting: ${data.reason}`);
    } else if (event === 'step_start') {
      setSteps(prev => { const next = [...prev]; next[data.stepIndex] = { type: data.stepType, label: data.label }; return next; });
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'running' }));
      addLog(`Step ${data.stepIndex + 1}: ${data.label} — started`);
    } else if (event === 'step_iteration') {
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'refining' }));
      setStepIterations(prev => ({ ...prev, [data.stepIndex]: { current: data.iteration, max: data.maxIterations } }));
      addLog(`Step ${data.stepIndex + 1}: refining pass ${data.iteration}/${data.maxIterations}…`);
    } else if (event === 'step_complete') {
      setSteps(prev => { const next = [...prev]; next[data.stepIndex] = { type: data.stepType, label: data.label }; return next; });
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'done' }));
      setStepIterations(prev => { const n = { ...prev }; delete n[data.stepIndex]; return n; });
      setStepResults(prev => ({ ...prev, [data.stepType]: data.result }));
      addLog(`Step ${data.stepIndex + 1}: ${data.label} — done`);
    } else if (event === 'step_skipped') {
      setSteps(prev => { const next = [...prev]; next[data.stepIndex] = { type: data.stepType, label: data.label }; return next; });
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'skipped' }));
      addLog(`Step ${data.stepIndex + 1}: ${data.label} — skipped (${data.reason})`);
    } else if (event === 'step_error') {
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'error' }));
      addLog(`Step ${data.stepIndex + 1} failed: ${data.error}`);
    } else if (event === 'workflow_complete') {
      setOverallStatus('done');
      addLog('Workflow completed');
      // Fetch run details after delay to pick up async connector logs (connectors fire after SSE closes)
      const rid = data?.runId || runId;
      if (rid) {
        setTimeout(() => {
          api.get(`/workflows/runs/${rid}`).then(r => {
            if (r.data.connectorLogs) setConnectorLogs(r.data.connectorLogs);
            if (r.data.connectors) setConnectors(r.data.connectors);
          }).catch(() => {});
        }, 3000);
      }
    } else if (event === 'workflow_error') {
      setOverallStatus('error');
      setError(data.error);
      addLog(`Failed: ${data.error}`);
    } else if (event === 'workflow_cancelled') {
      setOverallStatus('cancelled');
      addLog(`Cancelled: ${data.reason || 'by user'}`);
    }
  }

  const handleExport = () => {
    if (!runId) return;
    api.get(`/workflows/runs/${runId}/export`)
      .then(r => {
        const blob = new Blob([JSON.stringify(r.data, null, 2)], { type: 'application/json' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `workflow-run-${runId}.json`;
        a.click();
        URL.revokeObjectURL(url);
      })
      .catch(() => {});
  };

  const handleAssociateMatter = async () => {
    if (!runId || !matterRef.trim()) return;
    setAssociating(true); setMatterSaved(false); setMatterError('');
    try {
      await api.patch(`/workflows/runs/${runId}/matter?matterRef=${encodeURIComponent(matterRef)}`);
      setMatterSaved(true);
      setTimeout(() => setMatterSaved(false), 3000);
    } catch (e) {
      setMatterError(e.response?.data?.message || 'Failed to save matter reference');
    } finally {
      setAssociating(false);
    }
  };

  const [cancelling, setCancelling] = useState(false);

  const handleCancel = async () => {
    if (!runId || cancelling) return;
    setCancelling(true);
    try {
      await api.post(`/workflows/runs/${runId}/cancel`);
      setOverallStatus('cancelled');
      addLog('Cancelled by user');
    } catch (e) {
      // ignore — might already be done
    } finally {
      setCancelling(false);
    }
  };

  const doneCount = Object.values(stepStatuses).filter(s => s === 'done').length;
  const progressPct = steps.length === 0 ? 0 : Math.round(doneCount / steps.length * 100);

  return (
    <div>
      <button onClick={() => navigate('/workflows')} className="flex items-center gap-1.5 text-text-muted hover:text-text-primary text-sm mb-4">
        <ArrowLeft className="w-4 h-4" /> Back to Workflows
      </button>

      <div className="flex items-start justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">{workflowName}</h1>
          <p className="text-text-muted text-sm mt-0.5">
            {overallStatus === 'running' && 'Running…'}
            {overallStatus === 'done' && 'Completed successfully'}
            {overallStatus === 'error' && 'Failed'}
            {overallStatus === 'cancelled' && 'Cancelled'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {overallStatus === 'done' && runId && (
            <button onClick={handleExport} className="btn-secondary flex items-center gap-1.5 text-xs">
              <Download className="w-3.5 h-3.5" /> Export JSON
            </button>
          )}
          {overallStatus === 'running' && (
            <button onClick={handleCancel} disabled={cancelling}
              className="btn-secondary text-xs text-danger flex items-center gap-1.5">
              {cancelling ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <XCircle className="w-3.5 h-3.5" />}
              {cancelling ? 'Cancelling...' : 'Cancel'}
            </button>
          )}
          {overallStatus === 'running' && <Loader2 className="w-6 h-6 text-warning animate-spin" />}
          {overallStatus === 'done' && <CheckCircle2 className="w-6 h-6 text-success" />}
          {overallStatus === 'error' && <XCircle className="w-6 h-6 text-danger" />}
          {overallStatus === 'cancelled' && <XCircle className="w-6 h-6 text-text-muted" />}
        </div>
      </div>

      {/* Progress bar */}
      {steps.length > 0 && (
        <div className="card mb-6">
          <div className="flex items-center justify-between text-xs text-text-muted mb-2">
            <span>Progress</span>
            <span>{doneCount} / {steps.length} steps</span>
          </div>
          <div className="h-2 bg-surface-el rounded-full overflow-hidden">
            <div
              className="h-full bg-primary rounded-full transition-all duration-500"
              style={{ width: `${progressPct}%` }}
            />
          </div>
        </div>
      )}

      {error && (
        <div className="card border-l-4 border-danger bg-danger/5 mb-4">
          <p className="text-danger text-sm">{error}</p>
        </div>
      )}

      {/* Steps */}
      <div className="space-y-3 mb-6">
        {steps.length === 0 && overallStatus === 'running' && (
          <div className="card text-center py-8">
            <Loader2 className="w-8 h-8 text-primary animate-spin mx-auto mb-2" />
            <p className="text-text-muted text-sm">Initializing…</p>
          </div>
        )}
        {steps.map((step, i) => (
          <StepCard
            key={i}
            step={step}
            status={stepStatuses[i] || 'pending'}
            result={stepResults[step.type]}
            iteration={stepIterations[i]}
          />
        ))}
      </div>

      {/* Refinement banner */}
      {refinementInfo && (
        <div className="card border-l-4 border-primary bg-primary/5 mb-4">
          <div className="flex items-center gap-2">
            <RefreshCw className="w-4 h-4 text-primary shrink-0" />
            <div>
              <p className="text-sm font-medium text-text-primary">Risk-driven re-draft</p>
              <p className="text-xs text-text-muted">{refinementInfo.reason}</p>
            </div>
          </div>
        </div>
      )}

      {/* Connectors */}
      <ConnectorStatusPanel
        connectors={connectors}
        connectorLogs={connectorLogs}
        overallStatus={overallStatus}
      />

      {/* Matter association */}
      {(overallStatus === 'done' || id) && (
        <div className="card mb-6">
          <div className="flex items-center gap-2 mb-3">
            <Briefcase className="w-4 h-4 text-text-muted" />
            <h3 className="text-sm font-semibold">Link to Matter</h3>
          </div>
          <div className="flex gap-2">
            <input
              value={matterRef}
              onChange={e => { setMatterRef(e.target.value); setMatterSaved(false); setMatterError(''); }}
              placeholder="e.g. 2024-CORP-001"
              className="input-field flex-1 text-sm"
            />
            <button
              onClick={handleAssociateMatter}
              disabled={associating || !matterRef.trim()}
              className="btn-secondary text-sm whitespace-nowrap"
            >
              {associating ? 'Saving…' : matterSaved ? 'Saved ✓' : 'Save'}
            </button>
          </div>
          {matterError && <p className="text-danger text-xs mt-1">{matterError}</p>}
        </div>
      )}

      {/* Activity log */}
      {logs.length > 0 && (
        <div className="card">
          <p className="text-xs font-semibold text-text-muted mb-2">Activity Log</p>
          <div className="space-y-1">
            {logs.map((log, i) => (
              <div key={i} className="flex gap-2 text-xs">
                <span className="text-text-muted shrink-0 font-mono">{log.ts}</span>
                <span className="text-text-secondary">{log.msg}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
