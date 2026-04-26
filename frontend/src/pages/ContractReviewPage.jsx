import { useState, useEffect } from 'react';
import {
  ShieldAlert, ClipboardList, FileText, CheckCircle2, AlertTriangle, XCircle,
  ChevronDown, ChevronUp, ChevronRight, Clock, Loader2, RefreshCw, MessageSquare,
  GitCompare, Download, X, Send, Link, User, Sparkles, Search,
} from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';
import SendForSignatureModal from '../components/SendForSignatureModal';

/* ── Shared helpers ──────────────────────────────────────────────── */

function timeAgo(isoString) {
  if (!isoString) return '';
  const d = new Date(isoString);
  const now = new Date();
  const diffMs = now - d;
  const mins = Math.floor(diffMs / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} minute${mins > 1 ? 's' : ''} ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs} hour${hrs > 1 ? 's' : ''} ago`;
  const days = Math.floor(hrs / 24);
  return `${days} day${days > 1 ? 's' : ''} ago`;
}

function SourceBadge({ source }) {
  if (source === 'DRAFT_ASYNC') return <span className="badge" style={{ fontSize: 10, padding: '1px 6px' }}>Draft</span>;
  if (source === 'CLOUD' || source === 'IMPORT') return <span className="badge" style={{ fontSize: 10, padding: '1px 6px' }}>Imported</span>;
  return <span className="badge low" style={{ fontSize: 10, padding: '1px 6px' }}>Uploaded</span>;
}

function RiskBadge({ level }) {
  const kind = level?.toLowerCase() === 'high' ? 'high' : level?.toLowerCase() === 'medium' ? 'med' : 'low';
  return <span className={`badge ${kind}`}>{level}</span>;
}

function Gauge({ score }) {
  const c = 2 * Math.PI * 70;
  const pct = (score || 0) / 100;
  const dash = c * pct;
  const color = score >= 80 ? 'var(--success-500)' : score >= 60 ? 'var(--warn-500)' : 'var(--danger-500)';
  const label = score >= 80 ? 'Low risk' : score >= 60 ? 'Medium risk' : 'High risk';
  return (
    <div className="gauge">
      <svg width="180" height="180">
        <circle cx="90" cy="90" r="70" fill="none" stroke="var(--bg-3)" strokeWidth="10" />
        <circle cx="90" cy="90" r="70" fill="none" stroke={color} strokeWidth="10"
                strokeLinecap="round" strokeDasharray={`${dash} ${c}`} style={{ transition: 'stroke-dasharray .6s' }} />
      </svg>
      <div className="v">
        <div className="score">{score || '--'}</div>
        <div className="total">/ 100</div>
        <div className="label-text" style={{ color }}>{label}</div>
      </div>
    </div>
  );
}

function CountBlock({ n, label, color }) {
  return (
    <div style={{ textAlign: 'center' }}>
      <div style={{ fontFamily: 'var(--font-doc)', fontSize: 22, fontWeight: 600, color }}>{n}</div>
      <div className="tiny muted" style={{ textTransform: 'uppercase', letterSpacing: '0.08em' }}>{label}</div>
    </div>
  );
}

/* ── Drilldown panel ────────────────────────────────────────────── */

function DrilldownPanel({ data }) {
  return (
    <div className="col" style={{ gap: 10, marginTop: 10 }}>
      {data.detailedRisk && (
        <div>
          <div className="tiny" style={{ fontWeight: 600, color: 'var(--danger-400)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 4 }}>What's at risk</div>
          <div className="small" style={{ color: 'var(--text-2)' }}>{data.detailedRisk}</div>
        </div>
      )}
      {data.businessImpact && (
        <div>
          <div className="tiny" style={{ fontWeight: 600, color: 'var(--warn-400)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 4 }}>Business impact</div>
          <div className="small" style={{ color: 'var(--text-2)' }}>{data.businessImpact}</div>
        </div>
      )}
      {data.howToFix && (
        <div>
          <div className="tiny" style={{ fontWeight: 600, color: 'var(--success-400)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 4 }}>How to fix</div>
          <div className="small" style={{ color: 'var(--text-2)' }}>{data.howToFix}</div>
        </div>
      )}
      {data.suggestedLanguage && (
        <div>
          <div className="tiny" style={{ fontWeight: 600, color: 'var(--brand-400)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 4 }}>Suggested language</div>
          <div className="mono small" style={{ padding: 10, background: 'var(--bg-2)', borderRadius: 'var(--r-md)', border: '1px solid var(--line-1)', color: 'var(--text-2)', whiteSpace: 'pre-wrap' }}>{data.suggestedLanguage}</div>
        </div>
      )}
    </div>
  );
}

/* ── Tab 1: Risk Assessment ─────────────────────────────────────── */

function RiskTab({ docId, result, setResult, loading, setLoading, error, setError, cached, setCached }) {
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
    if (isNowOpen && !drilldowns[cat.name] && (cat.rating === 'HIGH' || cat.rating === 'MEDIUM')) {
      fireDrilldown(cat);
    }
  };

  const run = async (regenerate = false) => {
    setLoading(true); setError(''); setResult(null); setDrilldowns({}); setExpanded({});
    try {
      const res = await api.post(`/ai/risk-assessment/${docId}${regenerate ? '?regenerate=true' : ''}`);
      setResult(res.data);
      setCached(!!res.data.cached);
      setExpanded({});
    } catch (e) {
      setError(e.response?.data?.message || 'Assessment failed');
    } finally { setLoading(false); }
  };

  // Auto-load cached result on mount
  useEffect(() => {
    if (!docId) { setResult(null); return; }
    setResult(null); setError(''); setCached(false);
    api.post(`/ai/risk-assessment/${docId}`)
      .then(r => {
        if (r.data?.cached) {
          setResult(r.data);
          setCached(true);
          setExpanded({});
        }
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId]);

  if (!docId) return <EmptyPrompt icon={ShieldAlert} text="Select a document above to assess its risk." />;

  const highCount = result?.categories?.filter(c => c.rating === 'HIGH').length || 0;
  const medCount = result?.categories?.filter(c => c.rating === 'MEDIUM').length || 0;
  const lowCount = result?.categories?.filter(c => c.rating === 'LOW').length || 0;

  return (
    <div>
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
        {cached && !loading && result?.generatedAt && (
          <span className="tiny muted" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <Clock size={12} /> Analysed {timeAgo(result.generatedAt)}
          </span>
        )}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {result && (
            <button onClick={() => run(true)} disabled={loading} className="btn">
              {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
              Re-analyse
            </button>
          )}
          {!result && (
            <button onClick={() => run(false)} disabled={loading} className="btn primary">
              {loading ? <Loader2 size={14} className="animate-spin" /> : <ShieldAlert size={14} />}
              {loading ? 'Analysing...' : 'Run Risk Assessment'}
            </button>
          )}
        </div>
      </div>

      {error && <ErrorCard msg={error} />}
      {loading && <LoadingSkeleton rows={8} />}

      {result && (
        <>
          {/* Top row: gauge + key findings + missing clauses */}
          <div style={{ display: 'grid', gridTemplateColumns: '320px 1fr 1fr', gap: 14, marginBottom: 16 }}>
            <div className="card" style={{ padding: 20, display: 'flex', flexDirection: 'column', alignItems: 'center' }}>
              <Gauge score={result.riskScore != null ? Math.round(result.riskScore) : (result.overallRisk === 'HIGH' ? 35 : result.overallRisk === 'MEDIUM' ? 65 : 85)} />
              <div style={{ display: 'flex', gap: 14, marginTop: 18 }}>
                <CountBlock n={highCount} label="High" color="var(--danger-400)" />
                <CountBlock n={medCount} label="Medium" color="var(--warn-400)" />
                <CountBlock n={lowCount} label="Low" color="var(--success-400)" />
              </div>
            </div>

            <div className="card" style={{ background: 'var(--warn-bg)', borderColor: 'rgba(216,154,58,0.3)' }}>
              <div className="card-header" style={{ borderColor: 'rgba(216,154,58,0.2)' }}>
                <AlertTriangle size={15} style={{ color: 'var(--warn-400)' }} />
                <h3 style={{ color: 'var(--warn-400)' }}>Key Findings</h3>
              </div>
              <div style={{ padding: 14 }}>
                {result.keyFindings?.length > 0 ? (
                  <ul style={{ margin: 0, paddingLeft: 16, lineHeight: 1.7, fontSize: 13 }}>
                    {result.keyFindings.map((f, i) => <li key={i}>{f}</li>)}
                  </ul>
                ) : (
                  <div className="small muted">No key findings identified.</div>
                )}
              </div>
            </div>

            <div className="card" style={{ background: 'var(--danger-bg)', borderColor: 'rgba(217,83,78,0.3)' }}>
              <div className="card-header" style={{ borderColor: 'rgba(217,83,78,0.2)' }}>
                <X size={15} style={{ color: 'var(--danger-400)' }} />
                <h3 style={{ color: 'var(--danger-400)' }}>Missing Clauses</h3>
                <span className="sub">{result.missingClauses?.length || 0} not found</span>
              </div>
              <div style={{ padding: 14, display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                {result.missingClauses?.length > 0 ? (
                  result.missingClauses.map(m => <span key={m} className="tag danger">{m}</span>)
                ) : (
                  <div className="small muted">All expected clauses found.</div>
                )}
              </div>
            </div>
          </div>

          {/* Per-clause grid */}
          <div className="row" style={{ justifyContent: 'space-between', marginBottom: 10 }}>
            <h3 style={{ margin: 0, fontSize: 14, fontWeight: 600 }}>Clause-by-clause analysis</h3>
          </div>
          <div className="grid-2">
            {result.categories?.map((cat, i) => {
              const dd = drilldowns[cat.name];
              const canDrilldown = cat.rating === 'HIGH' || cat.rating === 'MEDIUM';
              const isOpen = !!expanded[cat.name];
              const kind = cat.rating?.toLowerCase() === 'high' ? 'high' : cat.rating?.toLowerCase() === 'medium' ? 'med' : 'low';
              const clauseResult = result.clauseResults?.find(cr => cr.clauseType === cat.name);
              const hasQuestions = clauseResult?.questions?.length > 0;
              const expandable = canDrilldown || hasQuestions;

              return (
                <div key={i} className="card">
                  <div style={{ padding: 14, cursor: expandable ? 'pointer' : 'default', display: 'flex', alignItems: 'center', gap: 10 }} onClick={() => expandable && toggleExpand(cat)}>
                    {expandable && (
                      <ChevronRight size={14} style={{ color: 'var(--text-3)', transform: isOpen ? 'rotate(90deg)' : 'none', transition: 'transform .15s' }} />
                    )}
                    <div style={{ fontWeight: 600, fontSize: 14, flex: 1 }}>{cat.name}</div>
                    <span className={`badge ${kind}`}>{cat.rating}</span>
                  </div>
                  {cat.justification && (
                    <div style={{ padding: '0 14px 12px', fontSize: 12, color: 'var(--text-2)' }}>{cat.justification}</div>
                  )}
                  {isOpen && cat.clauseReference && !/^see contract$/i.test(cat.clauseReference.trim()) && (
                    <div style={{ padding: '0 14px 12px' }}>
                      <span className="mono tiny" style={{ color: 'var(--brand-400)' }}>{cat.clauseReference}</span>
                    </div>
                  )}

                  {/* Structured per-question results */}
                  {isOpen && clauseResult?.questions?.length > 0 && (
                    <div style={{ borderTop: '1px solid var(--line-1)', padding: 14, display: 'flex', flexDirection: 'column', gap: 10, background: 'var(--bg-0)' }}>
                      {clauseResult.questions.map((q, qi) => (
                        <div key={qi} style={{ display: 'flex', gap: 10 }}>
                          <div style={{ marginTop: 2 }}>
                            {q.answer === 'YES' && <span style={{ display: 'inline-grid', placeItems: 'center', width: 18, height: 18, borderRadius: '50%', background: 'var(--success-bg)', color: 'var(--success-400)', fontSize: 11, fontWeight: 700 }}>&#x2713;</span>}
                            {q.answer === 'NO' && <span style={{ display: 'inline-grid', placeItems: 'center', width: 18, height: 18, borderRadius: '50%', background: 'var(--danger-bg)', color: 'var(--danger-400)', fontSize: 11, fontWeight: 700 }}>&#x2717;</span>}
                            {q.answer !== 'YES' && q.answer !== 'NO' && <span style={{ display: 'inline-grid', placeItems: 'center', width: 18, height: 18, borderRadius: '50%', background: 'var(--bg-3)', color: 'var(--text-3)', fontSize: 11, fontWeight: 700 }}>?</span>}
                          </div>
                          <div style={{ flex: 1 }}>
                            <div className="small" style={{ fontWeight: 500 }}>{q.question || q.id}</div>
                            {q.quote && (
                              <div style={{
                                marginTop: 6, fontFamily: 'var(--font-doc)', fontSize: 12.5, fontStyle: 'italic',
                                color: 'var(--text-2)', paddingLeft: 10, borderLeft: '2px solid var(--line-2)',
                              }}>"{q.quote}"</div>
                            )}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}

                  {/* Legacy drilldown */}
                  {canDrilldown && isOpen && !clauseResult?.questions?.length && (
                    <div style={{ borderTop: '1px solid var(--line-1)', padding: 14, background: 'var(--bg-0)' }}>
                      {!dd || dd.loading ? (
                        <div className="row tiny muted" style={{ gap: 6, padding: '8px 0' }}>
                          <Loader2 size={12} className="animate-spin" /> Analysing...
                        </div>
                      ) : dd.error ? (
                        <div className="tiny" style={{ color: 'var(--danger-400)' }}>{dd.error}</div>
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

/* ── Tab 2: Summary ────────────────────────────────────────────── */

function SummaryTab({ docId, result, setResult, loading, setLoading, error, setError }) {
  const run = async (regenerate = false) => {
    setLoading(true); setError('');
    try {
      const res = await api.post(`/ai/summarize/${docId}${regenerate ? '?regenerate=true' : ''}`);
      setResult(res.data);
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Summary failed');
    } finally { setLoading(false); }
  };

  useEffect(() => {
    if (!docId) { setResult(null); return; }
    setResult(null); setError('');
    api.post(`/ai/summarize/${docId}`)
      .then(r => { if (r.data?.cached) setResult(r.data); })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId]);

  if (!docId) return <EmptyPrompt icon={FileText} text="Select a document above to summarise it." />;

  const cached = result?.cached;

  return (
    <div>
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
        {cached && !loading && (
          <span className="tiny muted" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <Clock size={12} /> Generated {result.generatedAt ? new Date(result.generatedAt).toLocaleString() : ''}
          </span>
        )}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {result && (
            <button onClick={() => run(true)} disabled={loading} className="btn">
              {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
              Regenerate
            </button>
          )}
          {!result && (
            <button onClick={() => run(false)} disabled={loading} className="btn primary">
              {loading ? <Loader2 size={14} className="animate-spin" /> : <FileText size={14} />}
              {loading ? 'Summarising...' : 'Generate Summary'}
            </button>
          )}
        </div>
      </div>

      {error && <ErrorCard msg={error} />}
      {loading && !result && <LoadingSkeleton rows={8} />}

      {result?.summary && (
        <div className="card" style={{ padding: 28, maxWidth: 900 }}>
          <div
            style={{ fontFamily: 'var(--font-doc)', fontSize: 15, lineHeight: 1.75 }}
            dangerouslySetInnerHTML={{ __html: markdownToHtml(result.summary) }}
          />
        </div>
      )}
    </div>
  );
}

/* ── Tab 3: Clause Checklist ────────────────────────────────────── */

const STATUS_ORDER = { MISSING: 0, WEAK: 1, PRESENT: 2 };

function ChecklistTab({ docId, result, setResult, loading, setLoading, error, setError, cached, setCached }) {
  const run = async (regenerate = false) => {
    setLoading(true); setError(''); setResult(null);
    try {
      const res = await api.post(`/review${regenerate ? '?regenerate=true' : ''}`, { documentId: docId });
      setResult(res.data);
      setCached(!!res.data.cached);
    } catch (e) {
      setError(e.response?.data?.message || 'Review failed');
    } finally { setLoading(false); }
  };

  // Auto-load cached result on mount
  useEffect(() => {
    if (!docId) { setResult(null); return; }
    setResult(null); setError(''); setCached(false);
    api.post('/review', { documentId: docId })
      .then(r => {
        if (r.data?.cached) {
          setResult(r.data);
          setCached(true);
        }
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [docId]);

  if (!docId) return <EmptyPrompt icon={ClipboardList} text="Select a document above to run a clause checklist." />;

  const sorted = result?.clauses
    ? [...result.clauses].sort((a, b) => (STATUS_ORDER[a.status] ?? 2) - (STATUS_ORDER[b.status] ?? 2))
    : [];

  return (
    <div>
      <div className="row" style={{ justifyContent: 'space-between', marginBottom: 16 }}>
        {cached && !loading && result?.generatedAt && (
          <span className="tiny muted" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <Clock size={12} /> Reviewed {timeAgo(result.generatedAt)}
          </span>
        )}
        <div style={{ marginLeft: 'auto', display: 'flex', gap: 8 }}>
          {result && (
            <button onClick={() => run(true)} disabled={loading} className="btn">
              {loading ? <Loader2 size={14} className="animate-spin" /> : <RefreshCw size={14} />}
              Re-run Checklist
            </button>
          )}
          {!result && (
            <button onClick={() => run(false)} disabled={loading} className="btn primary">
              {loading ? <Loader2 size={14} className="animate-spin" /> : <ClipboardList size={14} />}
              {loading ? 'Reviewing...' : 'Run Clause Checklist'}
            </button>
          )}
        </div>
      </div>

      {error && <ErrorCard msg={error} />}
      {loading && <LoadingSkeleton rows={8} />}

      {result && (
        <>
          {/* Summary bar */}
          <div className="card" style={{ padding: 16, marginBottom: 16, display: 'flex', alignItems: 'center', gap: 20, flexWrap: 'wrap' }}>
            <div style={{ flex: 1, minWidth: 200 }}>
              <div className="row tiny muted" style={{ justifyContent: 'space-between', marginBottom: 6 }}>
                <span>Clause Coverage</span>
                <span>{result.clausesPresent ?? result.presentCount} / {((result.clausesPresent ?? result.presentCount) || 0) + ((result.clausesMissing ?? result.missingCount) || 0) + ((result.clausesWeak ?? result.weakCount) || 0)} present</span>
              </div>
              <div className="progress-track" style={{ height: 6 }}>
                <div className="progress-fill" style={{
                  width: `${((result.clausesPresent ?? result.presentCount) || 0) / Math.max(1, ((result.clausesPresent ?? result.presentCount) || 0) + ((result.clausesMissing ?? result.missingCount) || 0) + ((result.clausesWeak ?? result.weakCount) || 0)) * 100}%`,
                  height: 6,
                }} />
              </div>
            </div>
            <div className="row" style={{ gap: 16, fontSize: 12 }}>
              <span style={{ color: 'var(--success-400)', fontWeight: 500 }}>{result.clausesPresent ?? result.presentCount} Present</span>
              <span style={{ color: 'var(--warn-400)', fontWeight: 500 }}>{result.clausesWeak ?? result.weakCount} Weak</span>
              <span style={{ color: 'var(--danger-400)', fontWeight: 500 }}>{result.clausesMissing ?? result.missingCount} Missing</span>
            </div>
            <RiskBadge level={result.overallRisk} />
          </div>

          {/* Critical missing */}
          {(result.criticalMissingClauses ?? result.criticalMissing)?.length > 0 && (
            <div className="card" style={{ borderColor: 'rgba(217,83,78,0.3)', background: 'var(--danger-bg)', padding: 14, marginBottom: 14 }}>
              <div className="row tiny" style={{ fontWeight: 600, color: 'var(--danger-400)', marginBottom: 8 }}>
                <XCircle size={12} /> Critical clauses missing
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                {(result.criticalMissingClauses ?? result.criticalMissing).map(c => (
                  <span key={c} className="tag danger">{c}</span>
                ))}
              </div>
            </div>
          )}

          {/* Checklist table */}
          <div className="card" style={{ overflow: 'hidden' }}>
            <table className="table">
              <thead>
                <tr>
                  <th style={{ width: '30%' }}>Clause</th>
                  <th>Assessment</th>
                  <th style={{ width: 120 }}>Status</th>
                  <th style={{ width: 100 }}>Risk</th>
                </tr>
              </thead>
              <tbody>
                {sorted.map((clause, i) => (
                  <ClauseRow key={i} clause={clause} />
                ))}
              </tbody>
            </table>
          </div>

          {/* Recommendations */}
          {result.recommendations?.length > 0 && (
            <div className="card" style={{ padding: 16, marginTop: 14 }}>
              <div style={{ fontWeight: 600, fontSize: 13, marginBottom: 10 }}>Top Recommendations</div>
              <ol style={{ margin: 0, paddingLeft: 20 }}>
                {result.recommendations.map((r, i) => (
                  <li key={i} style={{ fontSize: 13, color: 'var(--text-2)', marginBottom: 6, lineHeight: 1.5 }}>
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

function ClauseRow({ clause }) {
  const [open, setOpen] = useState(clause.status !== 'PRESENT');

  return (
    <>
      <tr onClick={() => setOpen(o => !o)} style={{ cursor: 'pointer' }}>
        <td style={{ fontWeight: 500 }}>{clause.clauseName}</td>
        <td style={{ color: 'var(--text-2)', fontSize: 12 }}>{clause.assessment ? clause.assessment.slice(0, 120) + (clause.assessment.length > 120 ? '...' : '') : '--'}</td>
        <td>
          {clause.status === 'PRESENT' && <span className="badge low"><CheckCircle2 size={10} /> Found</span>}
          {clause.status === 'WEAK' && <span className="badge med"><AlertTriangle size={10} /> Weak</span>}
          {clause.status === 'MISSING' && <span className="badge high"><XCircle size={10} /> Missing</span>}
        </td>
        <td><RiskBadge level={clause.riskLevel} /></td>
      </tr>
      {open && (clause.foundText || clause.recommendation) && (
        <tr>
          <td colSpan={4} style={{ background: 'var(--bg-0)', padding: '12px 14px' }}>
            {clause.foundText && (
              <div style={{ fontFamily: 'var(--font-doc)', fontSize: 12.5, fontStyle: 'italic', color: 'var(--text-2)', paddingLeft: 10, borderLeft: '2px solid var(--line-2)', marginBottom: 8 }}>
                "{clause.foundText}"
              </div>
            )}
            {clause.recommendation && (
              <div className="small" style={{ display: 'flex', gap: 6, color: 'var(--warn-400)' }}>
                <AlertTriangle size={12} style={{ marginTop: 2, flexShrink: 0 }} /> {clause.recommendation}
              </div>
            )}
          </td>
        </tr>
      )}
    </>
  );
}

/* ── Shared micro-components ────────────────────────────────────── */

function EmptyPrompt({ icon: Icon, text }) {
  return (
    <div className="card" style={{ textAlign: 'center', padding: '48px 24px' }}>
      <Icon size={40} style={{ color: 'var(--text-4)', margin: '0 auto 12px', display: 'block' }} />
      <div className="small muted">{text}</div>
    </div>
  );
}

function ErrorCard({ msg }) {
  return (
    <div className="card" style={{ borderLeftWidth: 3, borderLeftColor: 'var(--danger-500)', padding: 14, marginBottom: 14 }}>
      <div className="small" style={{ color: 'var(--danger-400)' }}>{msg}</div>
    </div>
  );
}

function Spinner() {
  return <Loader2 size={14} className="animate-spin" />;
}

// Minimal markdown to HTML
function markdownToHtml(md) {
  if (!md) return '';
  const escape = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const lines = md.split(/\r?\n/);
  const out = [];
  let inList = false;
  const closeList = () => { if (inList) { out.push('</ul>'); inList = false; } };
  for (const raw of lines) {
    const line = raw.trim();
    if (!line) { closeList(); continue; }
    const h2 = line.match(/^##\s+(.+)$/);
    if (h2) { closeList(); out.push(`<h2 style="font-size:15px;font-weight:700;text-transform:uppercase;letter-spacing:0.05em;margin:22px 0 8px;">${escape(h2[1])}</h2>`); continue; }
    const li = line.match(/^[-*]\s+(.+)$/);
    if (li) {
      if (!inList) { out.push('<ul style="padding-left:20px;margin-bottom:12px;">'); inList = true; }
      out.push(`<li style="margin-bottom:4px;">${escape(li[1])}</li>`);
      continue;
    }
    closeList();
    out.push(`<p style="margin-bottom:10px;">${escape(line)}</p>`);
  }
  closeList();
  return out.join('\n');
}

/* ── Main page ──────────────────────────────────────────────────── */

const TABS = [
  { id: 'summary', label: 'Summary', icon: FileText },
  { id: 'risk', label: 'Risk Assessment', icon: ShieldAlert },
  { id: 'checklist', label: 'Checklist', icon: ClipboardList },
];

export default function ContractReviewPage() {
  const [grouped, setGrouped] = useState(null);
  const [allDocs, setAllDocs] = useState([]);
  const [docId, setDocId] = useState('');
  const [tab, setTab] = useState('summary');

  // Summary tab state
  const [summaryResult, setSummaryResult] = useState(null);
  const [summaryLoading, setSummaryLoading] = useState(false);
  const [summaryError, setSummaryError] = useState('');
  const [showSignModal, setShowSignModal] = useState(false);

  // Risk tab state
  const [riskResult, setRiskResult] = useState(null);
  const [riskLoading, setRiskLoading] = useState(false);
  const [riskError, setRiskError] = useState('');
  const [riskCached, setRiskCached] = useState(false);

  // Checklist tab state
  const [checklistResult, setChecklistResult] = useState(null);
  const [checklistLoading, setChecklistLoading] = useState(false);
  const [checklistError, setChecklistError] = useState('');
  const [checklistCached, setChecklistCached] = useState(false);

  useEffect(() => {
    api.get('/documents/grouped').then(r => {
      setGrouped(r.data);
      // Flatten all docs for lookups
      const flat = [];
      (r.data.matters || []).forEach(m => (m.documents || []).forEach(d => flat.push(d)));
      (r.data.unassigned || []).forEach(d => flat.push(d));
      (r.data.drafts || []).forEach(d => flat.push(d));
      setAllDocs(flat);
    }).catch(() => {
      // Fallback to flat list
      api.get('/documents?size=100').then(r => {
        const docs = r.data.content || [];
        setAllDocs(docs);
      }).catch(() => {});
    });
  }, []);

  useEffect(() => {
    setRiskResult(null); setRiskError(''); setRiskCached(false);
    setChecklistResult(null); setChecklistError(''); setChecklistCached(false);
    setSummaryResult(null); setSummaryError('');
  }, [docId]);

  const selectedDoc = allDocs.find(d => d.id === docId);

  return (
    <div className="page">
      {/* Document header */}
      <div className="page-header" style={{ alignItems: 'center' }}>
        <div style={{ display: 'flex', gap: 14, alignItems: 'center' }}>
          <div style={{
            width: 44, height: 54, background: '#fafaf7', border: '1px solid var(--line-2)',
            borderRadius: 4, display: 'grid', placeItems: 'center',
            color: '#1a2330', fontFamily: 'var(--font-doc)', fontSize: 10, fontWeight: 700,
          }}>
            {selectedDoc?.fileName?.endsWith('.pdf') ? 'PDF' : selectedDoc ? 'DOCX' : 'DOC'}
          </div>
          <div>
            <h1 className="page-title" style={{ fontSize: 20 }}>Contract Review</h1>
            <div className="row small muted" style={{ gap: 10 }}>
              {selectedDoc ? (
                <>
                  <span>{selectedDoc.fileName}</span>
                  {selectedDoc.clientName && <><span>&middot;</span><span>{selectedDoc.clientName}</span></>}
                </>
              ) : (
                <span>Select a document to begin analysis</span>
              )}
            </div>
          </div>
        </div>
        <div className="row" style={{ gap: 8 }}>
          <select
            value={docId}
            onChange={e => setDocId(e.target.value)}
            className="select"
            style={{ maxWidth: 360, minWidth: 220 }}
          >
            <option value="">Choose a contract...</option>
            {grouped ? (
              <>
                {allDocs.length > 0 && (
                  <optgroup label="All Documents">
                    {allDocs.map(d => (
                      <option key={`all-${d.id}`} value={d.id}>
                        {d.fileName}
                      </option>
                    ))}
                  </optgroup>
                )}
                {(grouped.matters || []).map(m => (
                  <optgroup key={m.matterId} label={m.matterName}>
                    {(m.documents || []).map(d => (
                      <option key={d.id} value={d.id}>
                        {d.fileName}
                      </option>
                    ))}
                  </optgroup>
                ))}
                {(grouped.unassigned || []).length > 0 && (
                  <optgroup label="Unassigned">
                    {grouped.unassigned.map(d => (
                      <option key={d.id} value={d.id}>
                        {d.fileName}
                      </option>
                    ))}
                  </optgroup>
                )}
                {(grouped.drafts || []).length > 0 && (
                  <optgroup label="My Drafts">
                    {grouped.drafts.map(d => (
                      <option key={d.id} value={d.id}>
                        {d.fileName}
                      </option>
                    ))}
                  </optgroup>
                )}
              </>
            ) : (
              allDocs.map(d => (
                <option key={d.id} value={d.id}>
                  {d.fileName}
                </option>
              ))
            )}
          </select>
          {selectedDoc && <SourceBadge source={selectedDoc.source} />}
          {docId && (
            <button className="btn primary" onClick={() => setShowSignModal(true)} style={{ marginLeft: 8 }}>
              <Send size={14} /> Send for Signature
            </button>
          )}
        </div>
      </div>

      {showSignModal && (
        <SendForSignatureModal
          docId={docId}
          docName={selectedDoc?.fileName}
          matterId={selectedDoc?.matterId}
          parties={{
            partyA: checklistResult?.partyA || riskResult?.partyA,
            partyB: checklistResult?.partyB || riskResult?.partyB,
          }}
          onClose={() => setShowSignModal(false)}
          onSent={() => {}}
        />
      )}

      {/* Tab bar */}
      <div className="tabs">
        {TABS.map(({ id, label, icon: Icon }) => (
          <button
            key={id}
            className={`tab ${tab === id ? 'active' : ''}`}
            onClick={() => setTab(id)}
          >
            <Icon size={14} />
            {label}
            {id === 'risk' && riskResult && <span className="count">{(riskResult.categories?.length || 0)}</span>}
            {id === 'checklist' && checklistResult && <span className="count">{(checklistResult.clauses?.length || 0)}</span>}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div style={{ display: tab === 'summary' ? 'block' : 'none' }}>
        <SummaryTab
          docId={docId}
          result={summaryResult}
          setResult={setSummaryResult}
          loading={summaryLoading}
          setLoading={setSummaryLoading}
          error={summaryError}
          setError={setSummaryError}
        />
      </div>
      <div style={{ display: tab === 'risk' ? 'block' : 'none' }}>
        <RiskTab
          docId={docId}
          result={riskResult}
          setResult={setRiskResult}
          loading={riskLoading}
          setLoading={setRiskLoading}
          error={riskError}
          setError={setRiskError}
          cached={riskCached}
          setCached={setRiskCached}
        />
      </div>
      <div style={{ display: tab === 'checklist' ? 'block' : 'none' }}>
        <ChecklistTab
          docId={docId}
          result={checklistResult}
          setResult={setChecklistResult}
          loading={checklistLoading}
          setLoading={setChecklistLoading}
          error={checklistError}
          setError={setChecklistError}
          cached={checklistCached}
          setCached={setChecklistCached}
        />
      </div>
    </div>
  );
}
