import { useState, useEffect } from 'react';
import { ShieldAlert, ClipboardList, CheckCircle2, AlertTriangle, XCircle, ChevronDown, ChevronUp, Clock, ChevronRight, Loader2 } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

/* ─── shared helpers ─────────────────────────────────────────────── */

const RISK_COLOR = { HIGH: 'danger', MEDIUM: 'warning', LOW: 'success' };

function RiskBadge({ level }) {
  return <span className={`badge-${level?.toLowerCase() || 'medium'}`}>{level}</span>;
}

function RiskGauge({ rating }) {
  const angles = { LOW: -60, MEDIUM: 0, HIGH: 60 };
  const angle = angles[rating] || 0;
  return (
    <div className="w-32 h-20 mx-auto relative">
      <svg viewBox="0 0 100 60" className="w-full h-full">
        <defs>
          <linearGradient id="gauge-bg" x1="0" y1="0" x2="1" y2="0">
            <stop offset="0%" stopColor="#10B981" />
            <stop offset="50%" stopColor="#F59E0B" />
            <stop offset="100%" stopColor="#EF4444" />
          </linearGradient>
        </defs>
        <path d="M 10 55 A 45 45 0 0 1 90 55" fill="none" stroke="url(#gauge-bg)" strokeWidth="8" strokeLinecap="round" />
        <line
          x1="50" y1="55"
          x2={50 + 30 * Math.cos(((angle - 90) * Math.PI) / 180)}
          y2={55 + 30 * Math.sin(((angle - 90) * Math.PI) / 180)}
          stroke="#F9FAFB" strokeWidth="2" strokeLinecap="round"
          style={{ transition: 'all 0.8s ease-out' }}
        />
        <circle cx="50" cy="55" r="3" fill="#F9FAFB" />
      </svg>
    </div>
  );
}

/* ─── Drilldown panel ────────────────────────────────────────────── */

function DrilldownPanel({ data }) {
  return (
    <div className="space-y-3 mt-3">
      <div>
        <p className="text-[10px] font-semibold text-danger uppercase tracking-wide mb-1">What's at risk</p>
        <p className="text-xs text-text-secondary leading-relaxed">{data.detailedRisk}</p>
      </div>
      {data.businessImpact && (
        <div>
          <p className="text-[10px] font-semibold text-warning uppercase tracking-wide mb-1">Business impact</p>
          <p className="text-xs text-text-secondary leading-relaxed">{data.businessImpact}</p>
        </div>
      )}
      {data.howToFix && (
        <div>
          <p className="text-[10px] font-semibold text-success uppercase tracking-wide mb-1">How to fix</p>
          <p className="text-xs text-text-secondary leading-relaxed">{data.howToFix}</p>
        </div>
      )}
      {data.suggestedLanguage && (
        <div>
          <p className="text-[10px] font-semibold text-primary uppercase tracking-wide mb-1">Suggested language</p>
          <p className="text-xs font-mono leading-relaxed bg-surface-el rounded-lg p-2.5 border border-border text-text-secondary whitespace-pre-wrap">{data.suggestedLanguage}</p>
        </div>
      )}
    </div>
  );
}

/* ─── Tab 1: Risk Assessment ─────────────────────────────────────── */

function RiskTab({ docId, result, setResult, loading, setLoading, error, setError, cached }) {
  const [drilldowns, setDrilldowns] = useState({});
  const [expanded, setExpanded] = useState({});

  const fireDrilldown = (cat) => {
    setDrilldowns(prev => ({ ...prev, [cat.name]: { loading: true, data: null, error: null } }));
    api.post(`/ai/risk-drilldown/${docId}`, {
      categoryName: cat.name,
      rating: cat.rating,
      justification: cat.justification,
      sectionRef: cat.clauseReference,
    }).then(r => {
      setDrilldowns(prev => ({ ...prev, [cat.name]: { loading: false, data: r.data, error: null } }));
    }).catch(e => {
      setDrilldowns(prev => ({ ...prev, [cat.name]: { loading: false, data: null, error: e.response?.data?.message || 'Analysis failed' } }));
    });
  };

  const toggleExpand = (cat) => {
    const isNowOpen = !expanded[cat.name];
    setExpanded(prev => ({ ...prev, [cat.name]: isNowOpen }));
    // Fire drilldown on first open if not already loaded
    if (isNowOpen && !drilldowns[cat.name] && (cat.rating === 'HIGH' || cat.rating === 'MEDIUM')) {
      fireDrilldown(cat);
    }
  };

  const run = async () => {
    setLoading(true); setError(''); setResult(null); setDrilldowns({}); setExpanded({});
    try {
      const res = await api.post(`/ai/risk-assessment/${docId}`);
      setResult(res.data);
      // Auto-expand and drilldown HIGH risk items
      const initialExpanded = {};
      (res.data.categories || []).forEach(c => { if (c.rating === 'HIGH') initialExpanded[c.name] = true; });
      setExpanded(initialExpanded);
      (res.data.categories || []).filter(c => c.rating === 'HIGH').forEach(cat => fireDrilldown(cat));
    } catch (e) {
      setError(e.response?.data?.message || 'Assessment failed');
    } finally { setLoading(false); }
  };

  if (!docId) return <EmptyPrompt icon={ShieldAlert} text="Select a document above to assess its risk." />;

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        {cached && !loading && (
          <p className="text-xs text-text-muted flex items-center gap-1">
            <Clock className="w-3 h-3" /> Cached result — click to re-analyse
          </p>
        )}
        <div className="ml-auto">
          <button onClick={run} disabled={loading} className="btn-primary flex items-center gap-2 text-sm">
            {loading ? <Spinner /> : <ShieldAlert className="w-4 h-4" />}
            {loading ? 'Analysing…' : cached ? 'Re-analyse' : 'Run Risk Assessment'}
          </button>
        </div>
      </div>

      {error && <ErrorCard msg={error} />}
      {loading && <LoadingSkeleton rows={8} />}

      {result && (
        <>
          <div className="card text-center py-8 mb-6">
            <h3 className="text-text-muted text-sm mb-3">Overall Risk</h3>
            <RiskGauge rating={result.overallRisk} />
            <p className={`text-2xl font-bold mt-4 text-${RISK_COLOR[result.overallRisk] || 'text-muted'}`}>
              {result.overallRisk}
            </p>
            <p className="text-text-muted text-sm mt-1">
              {result.categories?.filter(c => c.rating === 'HIGH').length || 0} High ·{' '}
              {result.categories?.filter(c => c.rating === 'MEDIUM').length || 0} Medium ·{' '}
              {result.categories?.filter(c => c.rating === 'LOW').length || 0} Low
            </p>
          </div>

          <div className="grid grid-cols-2 gap-4">
            {result.categories?.map((cat, i) => {
              const dd = drilldowns[cat.name];
              const canDrilldown = cat.rating === 'HIGH' || cat.rating === 'MEDIUM';
              const isOpen = !!expanded[cat.name];
              const sectionRef = cat.clauseReference && !/^see contract$/i.test(cat.clauseReference.trim())
                ? cat.clauseReference : null;
              return (
                <div key={i} className={`card border-t-4 border-${RISK_COLOR[cat.rating] || 'border'} !p-0 overflow-hidden`}>
                  <button
                    onClick={() => canDrilldown && toggleExpand(cat)}
                    className={`w-full flex items-start gap-3 px-4 py-3 text-left ${canDrilldown ? 'cursor-pointer hover:bg-white/5' : 'cursor-default'}`}
                  >
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between gap-2 mb-1">
                        <h4 className="font-semibold text-text-primary">{cat.name}</h4>
                        <RiskBadge level={cat.rating} />
                      </div>
                      {cat.justification && (
                        <p className="text-sm text-text-secondary">{cat.justification}</p>
                      )}
                      {sectionRef && (
                        <p className="font-mono text-xs text-primary mt-1">{sectionRef}</p>
                      )}
                    </div>
                    {canDrilldown && (
                      <div className="shrink-0 mt-0.5">
                        {isOpen
                          ? <ChevronUp className="w-4 h-4 text-text-muted" />
                          : <ChevronDown className="w-4 h-4 text-text-muted" />}
                      </div>
                    )}
                  </button>

                  {canDrilldown && isOpen && (
                    <div className="border-t border-border/60 px-4 pb-4">
                      {!dd || dd.loading ? (
                        <div className="flex items-center gap-2 text-xs text-text-muted py-3">
                          <Loader2 className="w-3.5 h-3.5 animate-spin" />
                          Analysing…
                        </div>
                      ) : dd.error ? (
                        <p className="text-xs text-danger pt-3">{dd.error}</p>
                      ) : (
                        <DrilldownPanel data={dd.data} />
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        </>
      )}

      {!result && !loading && !error && (
        <EmptyPrompt icon={ShieldAlert} text="Click Run Risk Assessment to analyse this document." />
      )}
    </div>
  );
}

/* ─── Tab 2: Clause Checklist ────────────────────────────────────── */

const STATUS_ICON = {
  PRESENT: <CheckCircle2 className="w-4 h-4 text-success shrink-0" />,
  WEAK: <AlertTriangle className="w-4 h-4 text-warning shrink-0" />,
  MISSING: <XCircle className="w-4 h-4 text-danger shrink-0" />,
};

const STATUS_ORDER = { MISSING: 0, WEAK: 1, PRESENT: 2 };

function ClauseRow({ clause }) {
  const [open, setOpen] = useState(clause.status !== 'PRESENT');
  const borderColor = clause.status === 'MISSING' ? 'border-danger' : clause.status === 'WEAK' ? 'border-warning' : 'border-success';

  return (
    <div className={`card border-l-4 ${borderColor} !p-0 overflow-hidden`}>
      <button
        onClick={() => setOpen(o => !o)}
        className="flex items-center gap-3 w-full px-4 py-3 text-left"
      >
        {STATUS_ICON[clause.status] || STATUS_ICON.PRESENT}
        <span className="flex-1 text-sm font-medium text-text-primary">{clause.clauseName}</span>
        <RiskBadge level={clause.riskLevel} />
        <span className="text-xs text-text-muted ml-2">{clause.sectionRef || ''}</span>
        {open ? <ChevronUp className="w-4 h-4 text-text-muted ml-1 shrink-0" /> : <ChevronDown className="w-4 h-4 text-text-muted ml-1 shrink-0" />}
      </button>

      {open && (
        <div className="px-4 pb-4 space-y-2 border-t border-border/50">
          {clause.assessment && (
            <p className="text-sm text-text-secondary mt-3">{clause.assessment}</p>
          )}
          {clause.foundText && (
            <p className="text-xs font-mono bg-surface-el rounded p-2 text-text-secondary leading-relaxed">
              "{clause.foundText}"
            </p>
          )}
          {clause.recommendation && (
            <p className="text-xs text-warning flex items-start gap-1.5 mt-1">
              <AlertTriangle className="w-3.5 h-3.5 shrink-0 mt-0.5" />
              {clause.recommendation}
            </p>
          )}
        </div>
      )}
    </div>
  );
}

function ChecklistTab({ docId, result, setResult, loading, setLoading, error, setError, cached }) {
  const run = async () => {
    setLoading(true); setError(''); setResult(null);
    try {
      const res = await api.post('/review', { documentId: docId });
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || 'Review failed');
    } finally { setLoading(false); }
  };

  if (!docId) return <EmptyPrompt icon={ClipboardList} text="Select a document above to run a clause checklist." />;

  const sorted = result?.clauses
    ? [...result.clauses].sort((a, b) => (STATUS_ORDER[a.status] ?? 2) - (STATUS_ORDER[b.status] ?? 2))
    : [];

  return (
    <div>
      <div className="flex items-center justify-between mb-4">
        {cached && !loading && (
          <p className="text-xs text-text-muted flex items-center gap-1">
            <Clock className="w-3 h-3" /> Cached result — click to re-run
          </p>
        )}
        <div className="ml-auto">
          <button onClick={run} disabled={loading} className="btn-primary flex items-center gap-2 text-sm">
            {loading ? <Spinner /> : <ClipboardList className="w-4 h-4" />}
            {loading ? 'Reviewing…' : cached ? 'Re-run Checklist' : 'Run Clause Checklist'}
          </button>
        </div>
      </div>

      {error && <ErrorCard msg={error} />}
      {loading && <LoadingSkeleton rows={8} />}

      {result && (
        <>
          {/* Summary bar */}
          <div className="card mb-5 flex items-center gap-6 flex-wrap">
            <div className="flex-1">
              <div className="flex items-center justify-between text-xs text-text-muted mb-1.5">
                <span>Clause Coverage</span>
                <span>{result.clausesPresent ?? result.presentCount} / {((result.clausesPresent ?? result.presentCount) || 0) + ((result.clausesMissing ?? result.missingCount) || 0) + ((result.clausesWeak ?? result.weakCount) || 0)} present</span>
              </div>
              <div className="h-2 bg-surface-el rounded-full overflow-hidden">
                <div
                  className="h-full bg-primary rounded-full transition-all duration-700"
                  style={{
                    width: `${((result.clausesPresent ?? result.presentCount) || 0) /
                      Math.max(1, ((result.clausesPresent ?? result.presentCount) || 0) + ((result.clausesMissing ?? result.missingCount) || 0) + ((result.clausesWeak ?? result.weakCount) || 0)) * 100}%`
                  }}
                />
              </div>
            </div>
            <div className="flex gap-4 text-xs shrink-0">
              <span className="text-success font-medium">{result.clausesPresent ?? result.presentCount} Present</span>
              <span className="text-warning font-medium">{result.clausesWeak ?? result.weakCount} Weak</span>
              <span className="text-danger font-medium">{result.clausesMissing ?? result.missingCount} Missing</span>
            </div>
            <div className="shrink-0">
              <span className="text-xs text-text-muted mr-1">Overall:</span>
              <RiskBadge level={result.overallRisk} />
            </div>
          </div>

          {/* Critical missing */}
          {(result.criticalMissingClauses ?? result.criticalMissing)?.length > 0 && (
            <div className="card border border-danger/30 bg-danger/5 mb-4">
              <p className="text-xs font-semibold text-danger mb-2 flex items-center gap-1.5">
                <XCircle className="w-3.5 h-3.5" /> Critical clauses missing
              </p>
              <div className="flex flex-wrap gap-2">
                {(result.criticalMissingClauses ?? result.criticalMissing).map(c => (
                  <span key={c} className="text-xs bg-danger/10 text-danger border border-danger/20 px-2 py-0.5 rounded-full">{c}</span>
                ))}
              </div>
            </div>
          )}

          {/* Per-clause rows — MISSING first */}
          <div className="space-y-2">
            {sorted.map((clause, i) => <ClauseRow key={i} clause={clause} />)}
          </div>

          {/* Top recommendations */}
          {result.recommendations?.length > 0 && (
            <div className="card mt-5">
              <p className="text-sm font-semibold mb-3">Top Recommendations</p>
              <ol className="space-y-2">
                {result.recommendations.map((r, i) => (
                  <li key={i} className="text-sm text-text-secondary flex gap-2">
                    <span className="text-primary font-medium shrink-0">{i + 1}.</span>
                    {r}
                  </li>
                ))}
              </ol>
            </div>
          )}
        </>
      )}

      {!result && !loading && !error && (
        <EmptyPrompt icon={ClipboardList} text="Click Run Clause Checklist to audit each standard clause." />
      )}
    </div>
  );
}

/* ─── Shared micro-components ────────────────────────────────────── */

function EmptyPrompt({ icon: Icon, text }) {
  return (
    <div className="card text-center py-12">
      <Icon className="w-12 h-12 text-text-muted mx-auto mb-4" />
      <p className="text-text-muted text-sm">{text}</p>
    </div>
  );
}

function ErrorCard({ msg }) {
  return (
    <div className="card border-l-4 border-danger bg-danger/5 mb-4">
      <p className="text-danger text-sm">{msg}</p>
    </div>
  );
}

function Spinner() {
  return <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />;
}

/* ─── Main page ──────────────────────────────────────────────────── */

const TABS = [
  { id: 'risk', label: 'Risk Assessment', icon: ShieldAlert },
  { id: 'checklist', label: 'Clause Checklist', icon: ClipboardList },
];

export default function ContractReviewPage() {
  const [docs, setDocs] = useState([]);
  const [docId, setDocId] = useState('');
  const [tab, setTab] = useState('risk');

  // Risk tab state — lifted to parent so it survives tab switches
  const [riskResult, setRiskResult] = useState(null);
  const [riskLoading, setRiskLoading] = useState(false);
  const [riskError, setRiskError] = useState('');
  const [riskCached, setRiskCached] = useState(false);

  // Checklist tab state — lifted to parent
  const [checklistResult, setChecklistResult] = useState(null);
  const [checklistLoading, setChecklistLoading] = useState(false);
  const [checklistError, setChecklistError] = useState('');
  const [checklistCached, setChecklistCached] = useState(false);

  useEffect(() => {
    api.get('/documents?size=100').then(r => setDocs(r.data.content || [])).catch(() => {});
  }, []);

  // Clear results when document changes
  useEffect(() => {
    setRiskResult(null); setRiskError(''); setRiskCached(false);
    setChecklistResult(null); setChecklistError(''); setChecklistCached(false);
  }, [docId]);

  const handleRiskResult = (result) => { setRiskResult(result); setRiskCached(false); };
  const handleChecklistResult = (result) => { setChecklistResult(result); setChecklistCached(false); };

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Contract Review</h1>

      {/* Shared document selector */}
      <div className="card mb-6">
        <label className="text-xs text-text-muted mb-1 block">Select Document</label>
        <select value={docId} onChange={e => setDocId(e.target.value)} className="input-field w-full text-sm">
          <option value="">Choose a contract…</option>
          {docs.map(d => (
            <option key={d.id} value={d.id}>
              {d.fileName}{d.clientName ? ` — ${d.clientName}` : ''}
            </option>
          ))}
        </select>
      </div>

      {/* Tab bar */}
      <div className="flex gap-1 mb-6 border-b border-border">
        {TABS.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            onClick={() => setTab(id)}
            className={`flex items-center gap-2 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors -mb-px ${
              tab === id
                ? 'border-primary text-primary'
                : 'border-transparent text-text-muted hover:text-text-primary'
            }`}
          >
            <Icon className="w-4 h-4" />
            {label}
            {id === 'risk' && riskResult && <span className="w-1.5 h-1.5 rounded-full bg-primary ml-1" />}
            {id === 'checklist' && checklistResult && <span className="w-1.5 h-1.5 rounded-full bg-primary ml-1" />}
          </button>
        ))}
      </div>

      {/* Tab content — both always mounted to preserve state */}
      <div style={{ display: tab === 'risk' ? 'block' : 'none' }}>
        <RiskTab
          docId={docId}
          result={riskResult}
          setResult={handleRiskResult}
          loading={riskLoading}
          setLoading={setRiskLoading}
          error={riskError}
          setError={setRiskError}
          cached={riskCached}
        />
      </div>
      <div style={{ display: tab === 'checklist' ? 'block' : 'none' }}>
        <ChecklistTab
          docId={docId}
          result={checklistResult}
          setResult={handleChecklistResult}
          loading={checklistLoading}
          setLoading={setChecklistLoading}
          error={checklistError}
          setError={setChecklistError}
          cached={checklistCached}
        />
      </div>
    </div>
  );
}
