import { useState, useEffect, useRef } from 'react';
import { useParams, useSearchParams, useNavigate } from 'react-router-dom';
import {
  CheckCircle2, XCircle, Loader2, Clock, ChevronDown, ChevronUp,
  ArrowLeft, ShieldAlert, Key, ClipboardList, Workflow
} from 'lucide-react';

const STEP_ICONS = {
  EXTRACT_KEY_TERMS: Key,
  RISK_ASSESSMENT: ShieldAlert,
  CLAUSE_CHECKLIST: ClipboardList,
};

const RISK_COLOR = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'success' };

function StepCard({ step, status, result }) {
  const [open, setOpen] = useState(false);
  const Icon = STEP_ICONS[step.type] || Workflow;

  const statusIcon = status === 'done'
    ? <CheckCircle2 className="w-5 h-5 text-success" />
    : status === 'running'
    ? <Loader2 className="w-5 h-5 text-warning animate-spin" />
    : status === 'error'
    ? <XCircle className="w-5 h-5 text-danger" />
    : <Clock className="w-5 h-5 text-text-muted" />;

  return (
    <div className={`card border-l-4 ${
      status === 'done' ? 'border-success' :
      status === 'running' ? 'border-warning' :
      status === 'error' ? 'border-danger' :
      'border-border'
    } !p-0 overflow-hidden`}>
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
          <p className="text-xs text-text-muted">{step.type.replace(/_/g, ' ')}</p>
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
      ['Governing Law', result.governingLaw], ['Notice Period', result.noticePeriodDays ? `${result.noticePeriodDays} days` : null],
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
          {result.categories?.slice(0, 4).map((cat, i) => (
            <div key={i} className="flex items-start gap-2">
              <span className={`badge-${RISK_COLOR[cat.rating] || 'medium'} text-xs shrink-0 mt-0.5`}>{cat.rating}</span>
              <div>
                <p className="text-xs font-medium text-text-primary">{cat.name}</p>
                <p className="text-xs text-text-muted">{cat.justification}</p>
              </div>
            </div>
          ))}
          {result.categories?.length > 4 && (
            <p className="text-xs text-text-muted">+{result.categories.length - 4} more categories</p>
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
        <div className="flex gap-4 mb-3">
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

  return <pre className="text-xs text-text-muted overflow-auto">{JSON.stringify(result, null, 2)}</pre>;
}

export default function WorkflowRunPage() {
  const { id } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();

  const [workflowName, setWorkflowName] = useState('Running Workflow');
  const [steps, setSteps] = useState([]);
  const [stepStatuses, setStepStatuses] = useState({});   // index -> 'pending'|'running'|'done'|'error'
  const [stepResults, setStepResults] = useState({});     // stepType -> result
  const [overallStatus, setOverallStatus] = useState('running'); // 'running'|'done'|'error'
  const [error, setError] = useState('');
  const [logs, setLogs] = useState([]);
  const esRef = useRef(null);

  const definitionId = searchParams.get('definitionId');
  const documentId = searchParams.get('documentId');
  const runId = id; // if viewing an existing run

  const addLog = (msg) => setLogs(l => [...l, { ts: new Date().toLocaleTimeString(), msg }]);

  useEffect(() => {
    if (runId) {
      // Viewing existing run — fetch from API
      fetch(`/api/v1/workflows/runs/${runId}`, { credentials: 'include' })
        .then(r => r.json())
        .then(run => {
          setWorkflowName(run.workflowName);
          setOverallStatus(
            run.status === 'COMPLETED' ? 'done' :
            run.status === 'FAILED' ? 'error' :
            'running'
          );
          if (run.results) {
            setStepResults(run.results);
          }
        });
      return;
    }

    if (!definitionId || !documentId) return;

    // Start SSE stream
    const url = `/api/v1/workflows/runs?definitionId=${definitionId}&documentId=${documentId}`;
    const controller = new AbortController();

    fetch(url, {
      method: 'POST',
      credentials: 'include',
      headers: { Accept: 'text/event-stream' },
      signal: controller.signal,
    }).then(response => {
      if (!response.ok) throw new Error(`HTTP ${response.status}`);
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';

      function read() {
        reader.read().then(({ done, value }) => {
          if (done) return;
          buffer += decoder.decode(value, { stream: true });
          const lines = buffer.split('\n');
          buffer = lines.pop();

          let event = null;
          for (const line of lines) {
            if (line.startsWith('event:')) {
              event = line.slice(6).trim();
            } else if (line.startsWith('data:')) {
              const data = JSON.parse(line.slice(5).trim());
              handleEvent(event, data);
            }
          }
          read();
        }).catch(e => {
          if (e.name !== 'AbortError') {
            setError('Stream disconnected');
            setOverallStatus('error');
          }
        });
      }
      read();
    }).catch(e => {
      if (e.name !== 'AbortError') {
        setError(e.message);
        setOverallStatus('error');
      }
    });

    return () => controller.abort();
  }, [definitionId, documentId, runId]);

  function handleEvent(event, data) {
    if (event === 'workflow_start') {
      setWorkflowName(data.workflowName);
      addLog(`Started: ${data.workflowName} (${data.totalSteps} steps)`);
    } else if (event === 'step_start') {
      setSteps(prev => {
        const next = [...prev];
        next[data.stepIndex] = { type: data.stepType, label: data.label };
        return next;
      });
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'running' }));
      addLog(`Step ${data.stepIndex + 1}: ${data.label} — started`);
    } else if (event === 'step_complete') {
      setSteps(prev => {
        const next = [...prev];
        next[data.stepIndex] = { type: data.stepType, label: data.label };
        return next;
      });
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'done' }));
      setStepResults(prev => ({ ...prev, [data.stepType]: data.result }));
      addLog(`Step ${data.stepIndex + 1}: ${data.label} — done`);
    } else if (event === 'step_error') {
      setStepStatuses(prev => ({ ...prev, [data.stepIndex]: 'error' }));
      addLog(`Step ${data.stepIndex + 1} failed: ${data.error}`);
    } else if (event === 'workflow_complete') {
      setOverallStatus('done');
      addLog('Workflow completed successfully');
    } else if (event === 'workflow_error') {
      setOverallStatus('error');
      setError(data.error);
      addLog(`Workflow failed: ${data.error}`);
    }
  }

  const progressPct = steps.length === 0 ? 0
    : Math.round(Object.values(stepStatuses).filter(s => s === 'done').length / steps.length * 100);

  return (
    <div>
      <button onClick={() => navigate('/workflows')} className="flex items-center gap-1.5 text-text-muted hover:text-text-primary text-sm mb-4">
        <ArrowLeft className="w-4 h-4" /> Back to Workflows
      </button>

      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-2xl font-bold">{workflowName}</h1>
          <p className="text-text-muted text-sm mt-0.5">
            {overallStatus === 'running' && 'Running…'}
            {overallStatus === 'done' && 'Completed successfully'}
            {overallStatus === 'error' && 'Failed'}
          </p>
        </div>
        <div>
          {overallStatus === 'running' && <Loader2 className="w-6 h-6 text-warning animate-spin" />}
          {overallStatus === 'done' && <CheckCircle2 className="w-6 h-6 text-success" />}
          {overallStatus === 'error' && <XCircle className="w-6 h-6 text-danger" />}
        </div>
      </div>

      {/* Progress bar */}
      {steps.length > 0 && (
        <div className="card mb-6">
          <div className="flex items-center justify-between text-xs text-text-muted mb-2">
            <span>Progress</span>
            <span>{Object.values(stepStatuses).filter(s => s === 'done').length} / {steps.length} steps</span>
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

      {/* Step cards */}
      <div className="space-y-3 mb-6">
        {steps.length === 0 && overallStatus === 'running' && (
          <div className="card text-center py-8">
            <Loader2 className="w-8 h-8 text-primary animate-spin mx-auto mb-2" />
            <p className="text-text-muted text-sm">Initializing workflow…</p>
          </div>
        )}
        {steps.map((step, i) => (
          <StepCard
            key={i}
            step={step}
            status={stepStatuses[i] || 'pending'}
            result={stepResults[step.type]}
          />
        ))}
      </div>

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
