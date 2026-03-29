import { useState, useEffect, useRef, useCallback } from 'react';
import { useParams, Link } from 'react-router-dom';
import { ArrowLeft, Shield, AlertTriangle, Loader2, FileText, Play, Wand2, Type, ClipboardCheck, Replace, Gauge } from 'lucide-react';
import api from '../api/client';

export default function DocumentEditorPage() {
  const { id } = useParams();
  const editorRef = useRef(null);
  const editorInstance = useRef(null);
  const [config, setConfig] = useState(null);
  const [doc, setDoc] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [findings, setFindings] = useState([]);
  const [analyzing, setAnalyzing] = useState(false);
  const [activePanel, setActivePanel] = useState('selection');
  const [selectedText, setSelectedText] = useState('');
  const [aiResult, setAiResult] = useState(null);
  const [aiLoading, setAiLoading] = useState(null); // tracks which action key is loading
  const [coverage, setCoverage] = useState(null);
  const [coverageLoading, setCoverageLoading] = useState(false);

  // Load document info first, editor config separately (may fail if no stored file)
  useEffect(() => {
    api.get(`/documents/${id}`)
      .then(r => {
        setDoc(r.data);
        // Try to get editor config — may fail if file not stored
        return api.get(`/editor/${id}/config`).then(c => setConfig(c.data)).catch(() => {
          // No stored file — editor won't load but AI panel still works
        });
      })
      .catch(() => setError('Document not found'))
      .finally(() => setLoading(false));
  }, [id]);

  // Initialize ONLYOFFICE editor
  useEffect(() => {
    if (!config || !editorRef.current) return;

    const script = document.createElement('script');
    script.src = config.onlyofficeUrl + '/web-apps/apps/api/documents/api.js';
    script.onload = () => {
      if (window.DocsAPI) {
        editorInstance.current = new window.DocsAPI.DocEditor('onlyoffice-editor', {
          document: config.document,
          editorConfig: {
            ...config.editorConfig,
            customization: {
              toolbarNoTabs: true,
              compactHeader: true,
            },
          },
          documentType: config.documentType,
          height: '100%',
          width: '100%',
          events: {
            onSelectionChange: (e) => {
              // ONLYOFFICE fires this when selection changes
              // We'll poll for selection instead since event support varies
            },
          },
        });
      }
    };
    script.onerror = () => setError('Could not connect to document editor. Is ONLYOFFICE running?');
    document.head.appendChild(script);
    return () => { document.head.removeChild(script); };
  }, [config]);

  // Poll for selected text every 500ms when selection tab is active
  const connectorRef = useRef(null);
  useEffect(() => {
    if (activePanel !== 'selection' || !editorInstance.current) return;
    // Create connector once for this effect
    try {
      if (!connectorRef.current) {
        connectorRef.current = editorInstance.current.createConnector();
      }
    } catch { return; }

    const interval = setInterval(() => {
      try {
        connectorRef.current.executeMethod('GetSelectedText', null, (text) => {
          if (text && text.trim()) setSelectedText(text.trim());
        });
      } catch { /* editor not ready */ }
    }, 500);
    return () => clearInterval(interval);
  }, [activePanel]);

  // Load findings
  useEffect(() => {
    if (!doc?.matter?.id) return;
    api.get(`/matters/${doc.matter.id}/findings`)
      .then(r => setFindings((r.data || []).filter(f => f.documentId === id)))
      .catch(() => {});
  }, [doc, id]);

  // AI actions on selected text
  const handleSelectionAction = async (action) => {
    const text = selectedText.trim();
    if (!text) { alert('Select text in the document first'); return; }
    setAiLoading(action);
    setAiResult(null);

    try {
      if (action === 'improve') {
        const res = await api.post('/ai/refine-clause', {
          selectedText: text,
          instruction: 'Suggest improved, more precise legal language',
        });
        setAiResult({ type: 'improve', ...res.data });
      } else if (action === 'risk') {
        const res = await api.post('/ai/refine-clause', {
          selectedText: text,
          instruction: 'Assess the legal risk of this clause. Identify issues and rate as HIGH, MEDIUM, or LOW risk.',
        });
        setAiResult({ type: 'risk', ...res.data });
      } else if (action === 'playbook') {
        const res = await api.post('/ai/refine-clause', {
          selectedText: text,
          instruction: 'Compare this clause against standard commercial contract best practices. Identify deviations and suggest improvements.',
        });
        setAiResult({ type: 'playbook', ...res.data });
      } else if (action === 'simplify') {
        const res = await api.post('/ai/refine-clause', {
          selectedText: text,
          instruction: 'Simplify this legal language while maintaining legal precision. Make it clearer and more readable.',
        });
        setAiResult({ type: 'simplify', ...res.data });
      }
    } catch (e) {
      setAiResult({ type: 'error', reasoning: e.response?.data?.message || 'Analysis failed' });
    } finally {
      setAiLoading(null);
    }
  };

  // Insert AI suggestion into document
  const handleInsert = () => {
    if (!aiResult?.improvedText) return;

    // No ONLYOFFICE editor — replace the pasted text in the textarea
    if (!editorInstance.current) {
      setSelectedText(aiResult.improvedText);
      setAiResult(null);
      return;
    }

    try {
      // Use ONLYOFFICE Connector API to paste at selection
      const connector = editorInstance.current.createConnector();
      connector.executeMethod('PasteText', [aiResult.improvedText]);
    } catch {
      // Fallback: copy to clipboard
      navigator.clipboard.writeText(aiResult.improvedText);
      alert('Improved text copied to clipboard. Paste it into the document.');
    }
  };

  // Whole document analysis
  const handleAnalyze = async (task) => {
    setAnalyzing(true);
    try {
      switch (task) {
        case 'risk': await api.post(`/ai/risk-assessment/${id}`); break;
        case 'extract': await api.post(`/ai/extract/${id}`); break;
        case 'review': await api.post('/review', { documentId: id }); break;
      }
      if (doc?.matter?.id) {
        const res = await api.get(`/matters/${doc.matter.id}/findings`);
        setFindings((res.data || []).filter(f => f.documentId === id));
      }
    } catch (e) { alert(e.response?.data?.message || 'Analysis failed'); }
    finally { setAnalyzing(false); }
  };

  if (loading) return (
    <div className="flex items-center justify-center h-screen"><Loader2 className="w-8 h-8 animate-spin text-text-muted" /></div>
  );

  if (error) return (
    <div className="flex flex-col items-center justify-center h-screen gap-4">
      <AlertTriangle className="w-10 h-10 text-warning" />
      <p className="text-text-muted text-sm max-w-md text-center">{error}</p>
      <Link to="/documents" className="btn-secondary text-sm">Back to Documents</Link>
    </div>
  );

  const SEVERITY = {
    HIGH: { dot: '🔴', bg: 'bg-danger/10', border: 'border-danger/20' },
    MEDIUM: { dot: '🟡', bg: 'bg-warning/10', border: 'border-warning/20' },
    LOW: { dot: '🟢', bg: 'bg-success/10', border: 'border-success/20' },
  };

  const TABS = [
    { id: 'selection', label: 'Selection', icon: Wand2 },
    { id: 'coverage', label: 'Coverage', icon: Gauge },
    { id: 'findings', label: 'Findings', icon: Shield },
    { id: 'analyze', label: 'Analyze', icon: Play },
  ];

  return (
    <div className="h-screen flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2 bg-surface border-b border-border/50 shrink-0">
        <div className="flex items-center gap-3">
          <Link to={doc?.matter?.id ? `/matters/${doc.matter.id}` : '/documents'}
            className="text-text-muted hover:text-text-primary">
            <ArrowLeft className="w-4 h-4" />
          </Link>
          <div>
            <h1 className="text-sm font-semibold text-text-primary font-display">{doc?.fileName}</h1>
            <p className="text-[10px] text-text-muted">{doc?.documentType} · {doc?.contentType}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {selectedText && (
            <span className="text-[10px] bg-primary/10 text-primary px-2 py-0.5 rounded-full">
              {selectedText.length > 30 ? selectedText.slice(0, 30) + '...' : selectedText} selected
            </span>
          )}
          {findings.length > 0 && (
            <span className="text-[10px] bg-warning/10 text-warning px-2 py-0.5 rounded-full">
              {findings.length} findings
            </span>
          )}
        </div>
      </div>

      {/* Split view */}
      <div className="flex flex-1 overflow-hidden">
        {/* Editor or fallback */}
        <div className="flex-1" ref={editorRef}>
          {config ? (
            <div id="onlyoffice-editor" className="h-full bg-white" />
          ) : (
            <div className="h-full flex flex-col items-center justify-center bg-surface-el p-8">
              <FileText className="w-12 h-12 text-text-muted mb-4" />
              <h2 className="text-lg font-semibold text-text-primary mb-2">{doc?.fileName}</h2>
              <p className="text-sm text-text-muted text-center max-w-md mb-4">
                Document editor not available — original file was uploaded before file storage was enabled.
                Re-upload the file to enable in-browser editing.
              </p>
              <p className="text-xs text-text-muted mb-6">You can still use the AI panel on the right to analyze this document.</p>
              <div className="flex gap-3">
                <Link to={`/intelligence?documentId=${id}`} className="btn-primary text-sm">
                  Analyze in Intelligence
                </Link>
              </div>
            </div>
          )}
        </div>

        {/* AI Panel */}
        <div className="w-80 bg-surface border-l border-border/50 flex flex-col shrink-0">
          {/* Tabs */}
          <div className="flex border-b border-border/50 shrink-0">
            {TABS.map(tab => (
              <button key={tab.id} onClick={() => setActivePanel(tab.id)}
                className={`flex-1 flex items-center justify-center gap-1 py-2.5 text-[11px] font-medium border-b-2 transition-colors ${
                  activePanel === tab.id ? 'border-primary text-primary' : 'border-transparent text-text-muted'
                }`}>
                <tab.icon className="w-3.5 h-3.5" /> {tab.label}
              </button>
            ))}
          </div>

          {/* Panel content */}
          <div className="flex-1 overflow-y-auto p-3">

            {/* ── Selection AI Tab ──────────────────────────────────── */}
            {activePanel === 'selection' && (
              <div className="space-y-3">
                {/* Selected text preview */}
                {selectedText ? (
                  <div className="card p-3 !bg-surface-el">
                    <p className="text-[10px] text-text-muted mb-1 font-medium">SELECTED TEXT</p>
                    <p className="text-xs text-text-primary leading-relaxed">
                      {selectedText.length > 200 ? selectedText.slice(0, 200) + '...' : selectedText}
                    </p>
                    <p className="text-[9px] text-text-muted mt-1">{selectedText.split(/\s+/).length} words</p>
                  </div>
                ) : (
                  <div className="text-center py-6">
                    <Type className="w-8 h-8 text-text-muted mx-auto mb-2" />
                    <p className="text-xs text-text-muted">Select text in the document to use AI actions</p>
                    <p className="text-[10px] text-text-muted mt-1">Or paste text below</p>
                    <textarea
                      placeholder="Paste clause text here..."
                      value={selectedText}
                      onChange={e => setSelectedText(e.target.value)}
                      rows={4}
                      className="input-field w-full text-xs mt-3 resize-none"
                    />
                  </div>
                )}

                {/* AI Actions */}
                {selectedText && (
                  <div className="space-y-1.5">
                    <p className="text-[10px] text-text-muted font-medium">AI ACTIONS</p>
                    {[
                      { key: 'improve', label: 'Suggest Improvement', desc: 'Better legal language', icon: Wand2 },
                      { key: 'risk', label: 'Assess Risk', desc: 'Rate risk level of this clause', icon: Shield },
                      { key: 'playbook', label: 'Check Best Practices', desc: 'Compare against standards', icon: ClipboardCheck },
                      { key: 'simplify', label: 'Simplify Language', desc: 'Clearer, more readable', icon: Type },
                    ].map(action => (
                      <button key={action.key} onClick={() => handleSelectionAction(action.key)}
                        disabled={!!aiLoading}
                        className="w-full text-left card p-2.5 hover:border-primary/30 transition-colors !bg-surface-el">
                        <div className="flex items-center gap-2.5">
                          <action.icon className="w-4 h-4 text-primary shrink-0" />
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-medium text-text-primary">{action.label}</p>
                            <p className="text-[10px] text-text-muted">{action.desc}</p>
                          </div>
                          {aiLoading === action.key && <Loader2 className="w-3.5 h-3.5 animate-spin text-text-muted shrink-0" />}
                        </div>
                      </button>
                    ))}
                  </div>
                )}

                {/* AI Result */}
                {aiResult && (
                  <div className="space-y-2 pt-2 border-t border-border/50">
                    <p className="text-[10px] text-text-muted font-medium">AI RESULT</p>

                    {aiResult.reasoning && (
                      <div className="insight-card p-3">
                        <p className="text-xs text-text-secondary leading-relaxed">{aiResult.reasoning}</p>
                      </div>
                    )}

                    {aiResult.improvedText && (
                      <div className="space-y-2">
                        <div className="card p-3 !bg-success/5 border-success/20">
                          <p className="text-[10px] text-success font-medium mb-1">SUGGESTED TEXT</p>
                          <p className="text-xs text-text-primary leading-relaxed">{aiResult.improvedText}</p>
                        </div>
                        <div className="flex gap-2">
                          <button onClick={handleInsert}
                            className="btn-primary text-xs flex-1 flex items-center justify-center gap-1.5">
                            <Replace className="w-3.5 h-3.5" /> Insert into Document
                          </button>
                          <button onClick={() => { navigator.clipboard.writeText(aiResult.improvedText); }}
                            className="btn-secondary text-xs px-3">
                            Copy
                          </button>
                        </div>
                      </div>
                    )}

                    {aiResult.type === 'error' && (
                      <div className="card p-3 !bg-danger/5 border-danger/20">
                        <p className="text-xs text-danger">{aiResult.reasoning}</p>
                      </div>
                    )}
                  </div>
                )}
              </div>
            )}

            {/* ── Coverage Tab ──────────────────────────────────────── */}
            {activePanel === 'coverage' && (
              <CoveragePanel documentId={id} coverage={coverage} setCoverage={setCoverage}
                loading={coverageLoading} setLoading={setCoverageLoading} />
            )}

            {/* ── Findings Tab ──────────────────────────────────────── */}
            {activePanel === 'findings' && (
              <div className="space-y-2">
                {findings.length === 0 ? (
                  <p className="text-text-muted text-xs text-center py-8">No findings for this document.</p>
                ) : (
                  findings.map(f => {
                    const sev = SEVERITY[f.severity] || SEVERITY.MEDIUM;
                    return (
                      <div key={f.id} className={`${sev.bg} border ${sev.border} rounded-lg p-2.5`}>
                        <div className="flex items-start gap-1.5">
                          <span className="text-xs">{sev.dot}</span>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-medium text-text-primary">{f.title}</p>
                            <p className="text-[10px] text-text-secondary mt-0.5">{f.description}</p>
                            {f.sectionRef && <p className="text-[10px] text-text-muted mt-0.5">§ {f.sectionRef}</p>}
                          </div>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            )}

            {/* ── Analyze Tab ───────────────────────────────────────── */}
            {activePanel === 'analyze' && (
              <div className="space-y-3">
                <p className="text-xs text-text-muted">Run full-document AI analysis.</p>
                {[
                  { key: 'risk', label: 'Risk Assessment', desc: 'Assess risk across 7 categories' },
                  { key: 'extract', label: 'Extract Key Terms', desc: 'Party names, dates, values' },
                  { key: 'review', label: 'Clause Checklist', desc: 'Check 12 standard clauses' },
                ].map(task => (
                  <button key={task.key} onClick={() => handleAnalyze(task.key)} disabled={analyzing}
                    className="w-full text-left card p-3 hover:border-primary/30 transition-colors">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-xs font-medium text-text-primary">{task.label}</p>
                        <p className="text-[10px] text-text-muted">{task.desc}</p>
                      </div>
                      {analyzing ? <Loader2 className="w-4 h-4 animate-spin text-text-muted" /> : <Play className="w-4 h-4 text-primary" />}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Coverage Panel Component ──────────────────────────────────────────

const CLAUSE_CATEGORIES = {
  'Core Terms': ['LIABILITY_LIMIT', 'INDEMNITY', 'PAYMENT_TERMS'],
  'Termination & Exit': ['TERMINATION_CONVENIENCE', 'TERMINATION_CAUSE', 'ASSIGNMENT'],
  'Risk & Protection': ['FORCE_MAJEURE', 'CONFIDENTIALITY', 'DATA_PROTECTION'],
  'Governance': ['GOVERNING_LAW', 'DISPUTE_RESOLUTION', 'IP_OWNERSHIP'],
};

const STATUS_STYLES = {
  PRESENT: { color: 'text-success', bg: 'bg-success/10', label: 'Present' },
  WEAK: { color: 'text-warning', bg: 'bg-warning/10', label: 'Weak' },
  MISSING: { color: 'text-danger', bg: 'bg-danger/10', label: 'Missing' },
};

function CoveragePanel({ documentId, coverage, setCoverage, loading, setLoading }) {
  const [expanded, setExpanded] = useState(null);

  const runCoverage = async () => {
    setLoading(true);
    try {
      const res = await api.post('/review', { documentId });
      setCoverage(res.data);
    } catch (e) {
      alert(e.response?.data?.message || 'Coverage analysis failed');
    } finally { setLoading(false); }
  };

  if (!coverage && !loading) {
    return (
      <div className="text-center py-8">
        <Gauge className="w-10 h-10 text-text-muted mx-auto mb-3" />
        <p className="text-xs text-text-muted mb-3">Analyze contract coverage against 12 standard clause types</p>
        <button onClick={runCoverage} className="btn-primary text-xs">
          Run Coverage Analysis
        </button>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="text-center py-8">
        <Loader2 className="w-8 h-8 animate-spin text-primary mx-auto mb-2" />
        <p className="text-xs text-text-muted">Analyzing contract coverage...</p>
      </div>
    );
  }

  const clauses = coverage?.clauses || [];
  const present = clauses.filter(c => c.status === 'PRESENT').length;
  const weak = clauses.filter(c => c.status === 'WEAK').length;
  const missing = clauses.filter(c => c.status === 'MISSING').length;
  const total = clauses.length || 12;
  const coveragePct = Math.round(((present + weak * 0.5) / total) * 100);

  // Determine coverage label
  const coverageLabel = coveragePct >= 80 ? 'Strong' : coveragePct >= 50 ? 'Partial' : 'Weak';
  const coverageColor = coveragePct >= 80 ? 'text-success' : coveragePct >= 50 ? 'text-warning' : 'text-danger';
  const gaugeColor = coveragePct >= 80 ? '#34d399' : coveragePct >= 50 ? '#fbbf24' : '#f87171';

  return (
    <div className="space-y-4">
      {/* Coverage gauge */}
      <div className="text-center py-3">
        <div className="relative w-32 h-16 mx-auto mb-2 overflow-hidden">
          <svg viewBox="0 0 120 60" className="w-full h-full">
            {/* Background arc */}
            <path d="M 10 55 A 50 50 0 0 1 110 55" fill="none" stroke="#222a3d" strokeWidth="8" strokeLinecap="round" />
            {/* Filled arc */}
            <path d="M 10 55 A 50 50 0 0 1 110 55" fill="none" stroke={gaugeColor} strokeWidth="8" strokeLinecap="round"
              strokeDasharray={`${coveragePct * 1.57} 157`} />
          </svg>
        </div>
        <p className={`text-2xl font-bold font-display ${coverageColor}`}>{coverageLabel}</p>
        <p className="text-xs text-text-muted">Coverage</p>
      </div>

      {/* Stats row */}
      <div className="flex justify-center gap-4">
        <div className="text-center">
          <p className="text-lg font-bold text-text-primary font-display">{total}</p>
          <p className="text-[10px] text-text-muted">All Terms</p>
        </div>
        <div className="text-center">
          <p className="text-lg font-bold text-success font-display">{present}</p>
          <p className="text-[10px] text-text-muted">Present</p>
        </div>
        <div className="text-center">
          <p className="text-lg font-bold text-warning font-display">{weak}</p>
          <p className="text-[10px] text-text-muted">Weak</p>
        </div>
        <div className="text-center">
          <p className="text-lg font-bold text-danger font-display">{missing}</p>
          <p className="text-[10px] text-text-muted">Missing</p>
        </div>
      </div>

      {/* Category drilldown */}
      <div className="space-y-1.5">
        {Object.entries(CLAUSE_CATEGORIES).map(([category, clauseIds]) => {
          const categoryClauses = clauses.filter(c => clauseIds.includes(c.clauseId || c.clause_id));
          const catPresent = categoryClauses.filter(c => c.status === 'PRESENT').length;
          const catTotal = clauseIds.length;
          const hasIssues = categoryClauses.some(c => c.status === 'MISSING' || c.status === 'WEAK');

          return (
            <div key={category}>
              <button onClick={() => setExpanded(expanded === category ? null : category)}
                className="w-full flex items-center justify-between px-3 py-2 rounded-lg hover:bg-surface-el transition-colors">
                <div className="flex items-center gap-2">
                  <span className="text-xs">{expanded === category ? '▾' : '▸'}</span>
                  <span className="text-xs font-medium text-text-primary">{category}</span>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-text-muted">{catPresent}/{catTotal}</span>
                  {hasIssues && <span className="w-2 h-2 rounded-full bg-danger" />}
                </div>
              </button>

              {expanded === category && (
                <div className="ml-6 space-y-1 mt-1">
                  {categoryClauses.length > 0 ? categoryClauses.map((c, i) => {
                    const st = STATUS_STYLES[c.status] || STATUS_STYLES.MISSING;
                    return (
                      <div key={i} className={`${st.bg} rounded-lg px-3 py-2`}>
                        <div className="flex items-center justify-between">
                          <span className="text-[11px] text-text-primary">
                            {(c.clauseId || c.clause_id || '').replace(/_/g, ' ')}
                          </span>
                          <span className={`text-[10px] font-medium ${st.color}`}>{st.label}</span>
                        </div>
                        {c.finding && <p className="text-[10px] text-text-secondary mt-0.5">{c.finding}</p>}
                      </div>
                    );
                  }) : (
                    <p className="text-[10px] text-text-muted px-3 py-1">No data — run analysis first</p>
                  )}
                </div>
              )}
            </div>
          );
        })}
      </div>

      {/* Uncategorized clauses */}
      {clauses.filter(c => !Object.values(CLAUSE_CATEGORIES).flat().includes(c.clauseId || c.clause_id)).length > 0 && (
        <div>
          <p className="text-[10px] text-text-muted font-medium px-3 mb-1">OTHER CLAUSES</p>
          <div className="space-y-1">
            {clauses.filter(c => !Object.values(CLAUSE_CATEGORIES).flat().includes(c.clauseId || c.clause_id)).map((c, i) => {
              const st = STATUS_STYLES[c.status] || STATUS_STYLES.MISSING;
              return (
                <div key={i} className={`${st.bg} rounded-lg px-3 py-2`}>
                  <div className="flex items-center justify-between">
                    <span className="text-[11px] text-text-primary">{(c.clauseId || c.clause_id || c.clauseName || '').replace(/_/g, ' ')}</span>
                    <span className={`text-[10px] font-medium ${st.color}`}>{st.label}</span>
                  </div>
                  {c.finding && <p className="text-[10px] text-text-secondary mt-0.5">{c.finding}</p>}
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Re-run button */}
      <button onClick={runCoverage} disabled={loading} className="btn-secondary w-full text-xs flex items-center justify-center gap-1.5">
        {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Gauge className="w-3.5 h-3.5" />}
        Re-analyze Coverage
      </button>
    </div>
  );
}
