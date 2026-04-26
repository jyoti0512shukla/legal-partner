import { useState, useEffect, useRef, useCallback } from 'react';
import {
  FileEdit, Loader2, Download, Lightbulb, Sparkles, CloudUpload, Check, X,
  FileText, Save, FolderOpen, Pencil, AlertTriangle, Briefcase, Clock,
  ChevronRight, ChevronLeft, ArrowRight, Lock, Plus, RefreshCw, Wand2, Send,
} from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import api from '../api/client';
import SaveToCloudModal from '../components/SaveToCloudModal';
import SendForSignatureModal from '../components/SendForSignatureModal';
import DraftsRecentStrip from '../components/DraftsRecentStrip';

function stripHtml(html) {
  const div = document.createElement('div');
  div.innerHTML = html || '';
  return div.innerText || div.textContent || '';
}

function extractBodyContent(html) {
  if (!html) return '';
  const m = html.match(/<body[^>]*>([\s\S]*?)<\/body>/i);
  return m ? m[1] : html;
}

const JURISDICTIONS = ['California', 'New York', 'Delaware', 'Texas', 'Illinois', 'Florida', 'Massachusetts'];

const CLAUSE_SPECS_LABELS = {
  DEFINITIONS: 'Definitions',
  SERVICES: 'Services',
  PAYMENT: 'Fees and Payment',
  CONFIDENTIALITY: 'Confidentiality',
  IP_RIGHTS: 'Intellectual Property Rights',
  LIABILITY: 'Liability and Indemnity',
  TERMINATION: 'Termination',
  FORCE_MAJEURE: 'Force Majeure',
  REPRESENTATIONS_WARRANTIES: 'Representations and Warranties',
  DATA_PROTECTION: 'Data Protection and Privacy',
  GOVERNING_LAW: 'Governing Law and Dispute Resolution',
  GENERAL_PROVISIONS: 'General Provisions',
};

/* ── Step indicator ──────────────────────────────────────────────── */
function StepHeader({ step, setStep, genDone }) {
  const steps = [
    { n: 1, label: 'Input' },
    { n: 2, label: 'Preview' },
    { n: 3, label: 'Draft' },
  ];
  return (
    <div style={{ display: 'flex', gap: 0, marginBottom: 20, borderBottom: '1px solid var(--line-1)', paddingBottom: 14 }}>
      {steps.map((s, i) => {
        const active = step === s.n;
        const complete = step > s.n || (s.n === 3 && genDone);
        return (
          <div key={s.n} style={{ display: 'flex', alignItems: 'center', gap: 8, flex: i < steps.length - 1 ? 1 : undefined }}>
            <div
              style={{
                display: 'flex', alignItems: 'center', gap: 8,
                cursor: s.n <= step ? 'pointer' : 'default',
                opacity: s.n > step ? 0.5 : 1,
              }}
              onClick={() => s.n <= step && setStep(s.n)}
            >
              <div style={{
                width: 22, height: 22, borderRadius: '50%',
                border: `1.5px solid ${active || complete ? 'var(--brand-500)' : 'var(--line-3)'}`,
                background: complete ? 'var(--brand-500)' : active ? 'var(--brand-50)' : 'transparent',
                color: complete ? '#fff' : active ? 'var(--brand-300)' : 'var(--text-3)',
                display: 'grid', placeItems: 'center',
                fontSize: 11, fontWeight: 600,
              }}>
                {complete ? <Check size={11} /> : s.n}
              </div>
              <span style={{ fontSize: 12, fontWeight: 500, color: active ? 'var(--text-1)' : 'var(--text-3)' }}>{s.label}</span>
            </div>
            {i < steps.length - 1 && <div style={{ flex: 1, height: 1, background: 'var(--line-2)', margin: '0 10px' }} />}
          </div>
        );
      })}
    </div>
  );
}

export default function DraftPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const matterIdFromUrl = searchParams.get('matterId');

  const [templates, setTemplates] = useState([]);
  const [docs, setDocs] = useState([]);
  const [matters, setMatters] = useState([]);
  const [refMode, setRefMode] = useState('manual');
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState(null);
  const [error, setError] = useState('');
  const [generatingStatus, setGeneratingStatus] = useState(null);
  const [refining, setRefining] = useState(false);
  const [refiningAt, setRefiningAt] = useState(null);
  const [showSaveToCloud, setShowSaveToCloud] = useState(false);
  const [showSignModal, setShowSignModal] = useState(false);
  const [selectionInfo, setSelectionInfo] = useState(null);
  const [pendingRefinement, setPendingRefinement] = useState(null);
  const [qaWarnings, setQaWarnings] = useState({});
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [submittingAsync, setSubmittingAsync] = useState(false);
  const [submitToast, setSubmitToast] = useState('');
  const [validationResult, setValidationResult] = useState(null);
  const [previewClauses, setPreviewClauses] = useState([]);
  const [activeAsyncId, setActiveAsyncId] = useState(null);
  const [showParams, setShowParams] = useState(false);
  const stripRef = useRef(null);
  const previewRef = useRef(null);
  const floatRef = useRef(null);

  // Derive step from state
  const step = validationResult ? 2 : (draft || loading) ? 3 : 1;

  const [form, setForm] = useState({
    templateId: '',
    contractTypeName: '',
    partyA: '',
    partyB: '',
    partyAAddress: '',
    partyBAddress: '',
    partyARep: '',
    partyBRep: '',
    effectiveDate: new Date().toISOString().slice(0, 10),
    jurisdiction: 'California',
    agreementRef: '',
    termYears: '3',
    noticeDays: '30',
    survivalYears: '5',
    practiceArea: 'CORPORATE',
    counterpartyType: 'vendor',
    dealBrief: '',
    clientPosition: 'NEUTRAL',
    industry: 'GENERAL',
    draftStance: 'BALANCED',
    matterId: matterIdFromUrl || '',
  });

  useEffect(() => {
    api.get('/ai/templates').then(r => setTemplates(r.data || [])).catch(() => setTemplates([]));
    api.get('/documents?size=100&sort=uploadDate,desc').then(r => setDocs(r.data.content || [])).catch(() => setDocs([]));
    api.get('/matters').then(r => {
      const list = r.data?.content || r.data || [];
      setMatters(Array.isArray(list) ? list : []);
    }).catch(() => setMatters([]));
  }, []);

  useEffect(() => {
    if (!matterIdFromUrl || matters.length === 0) return;
    const matter = matters.find(m => m.id === matterIdFromUrl);
    if (matter) {
      setForm(f => ({
        ...f,
        matterId: matter.id,
        partyA: f.partyA || matter.clientName || '',
        practiceArea: f.practiceArea || matter.practiceArea || 'CORPORATE',
      }));
    }
  }, [matterIdFromUrl, matters]);

  const handlePreviewMouseUp = useCallback(() => {
    const sel = window.getSelection();
    const text = sel?.toString()?.trim();
    if (text && previewRef.current?.contains(sel.anchorNode)) {
      const range = sel.getRangeAt(0);
      const rect = range.getBoundingClientRect();
      setSelectionInfo({ text, x: rect.left + rect.width / 2, y: rect.bottom + 8 });
      setPendingRefinement(null);
    } else {
      setSelectionInfo(null);
    }
  }, []);

  useEffect(() => {
    const onMouseDown = (e) => {
      if (!previewRef.current?.contains(e.target) && !floatRef.current?.contains(e.target)) {
        setSelectionInfo(null);
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, []);

  // Step 1: Validate and show preview
  const handleGenerateAsync = async () => {
    if (!form.templateId) return;
    if (form.templateId === 'custom' && !form.contractTypeName.trim()) return;
    setError('');
    setSubmittingAsync(true);
    try {
      const v = await api.post('/ai/draft/validate', form);
      setValidationResult(v.data);
      setPreviewClauses(v.data.plannedClauses?.map(c => ({ ...c })) || []);
    } catch (e) {
      confirmGenerate();
    } finally {
      setSubmittingAsync(false);
    }
  };

  // Step 2: User confirms preview -> actually generate
  const confirmGenerate = async () => {
    setSubmittingAsync(true);
    setError('');
    const selectedKeys = previewClauses.filter(c => c.enabled).map(c => c.key);
    const payload = { ...form, selectedClauses: selectedKeys.length > 0 ? selectedKeys : undefined };
    try {
      await api.post('/ai/draft/async', payload);
      if (stripRef.current?.refresh) await stripRef.current.refresh();
      setSubmitToast('Draft started -- watch it in "Your recent drafts" above.');
      setTimeout(() => setSubmitToast(''), 6000);
      setValidationResult(null);
      setPreviewClauses([]);
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Failed to submit draft');
    } finally {
      setSubmittingAsync(false);
    }
  };

  const toggleClause = (key) => {
    setPreviewClauses(prev => prev.map(c => c.key === key ? { ...c, enabled: !c.enabled } : c));
  };

  // Poll async draft
  useEffect(() => {
    if (!activeAsyncId) return;
    let cancelled = false;

    const poll = async () => {
      try {
        const r = await api.get(`/ai/draft/async/${activeAsyncId}`);
        if (cancelled) return;
        const d = r.data;
        setDraft({ id: d.id, fileName: d.fileName, draftHtml: d.draftHtml || '', suggestions: [], draftParametersHtml: d.draftParametersHtml || '', durationSeconds: d.durationSeconds });

        if (d.status === 'PENDING') {
          setGeneratingStatus({ label: 'Queued -- waiting for capacity', index: 0, total: d.totalClauses || 0 });
          setLoading(true);
        } else if (d.status === 'PROCESSING') {
          setGeneratingStatus({ label: d.currentClauseLabel || 'Generating', index: d.completedClauses || 0, total: d.totalClauses || 9 });
          setLoading(true);
        } else if (d.status === 'INDEXED') {
          setGeneratingStatus(d.durationSeconds
            ? { label: `Completed in ${Math.round(d.durationSeconds / 60)}m ${d.durationSeconds % 60}s`, done: true }
            : null);
          setLoading(false);
          return 'done';
        } else if (d.status === 'FAILED') {
          setGeneratingStatus(null);
          setLoading(false);
          setError(d.errorMessage || 'Draft failed');
          return 'done';
        }
      } catch (e) {
        // swallow transient polling errors
      }
      return null;
    };

    let intervalId = null;
    (async () => {
      const first = await poll();
      if (first === 'done' || cancelled) return;
      intervalId = setInterval(async () => {
        const r = await poll();
        if (r === 'done' && intervalId) { clearInterval(intervalId); intervalId = null; }
      }, 3000);
    })();

    return () => {
      cancelled = true;
      if (intervalId) clearInterval(intervalId);
    };
  }, [activeAsyncId]);

  const handleGenerate = async () => {
    if (!form.templateId) return;
    if (form.templateId === 'custom' && !form.contractTypeName.trim()) return;
    setLoading(true);
    setError('');
    setDraft(null);
    setGeneratingStatus(null);
    setSelectionInfo(null);
    setPendingRefinement(null);
    setQaWarnings({});
    setActiveAsyncId(null);
    try {
      const response = await fetch('/api/v1/ai/draft/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'include',
        body: JSON.stringify(form),
      });
      if (!response.ok) {
        const err = await response.json().catch(() => ({}));
        throw new Error(err.message || `Error ${response.status}`);
      }
      const reader = response.body.getReader();
      const decoder = new TextDecoder();
      let buffer = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop();
        for (const line of lines) {
          if (!line.startsWith('data:')) continue;
          const json = line.slice(5).trim();
          if (!json) continue;
          let event;
          try { event = JSON.parse(json); } catch { continue; }
          if (event.type === 'planning') {
            setGeneratingStatus({ label: 'Planning sections...', index: 0, total: 0 });
          } else if (event.type === 'start') {
            setGeneratingStatus({ label: 'Preparing...', index: 0, total: event.totalClauses });
            setDraft({ draftHtml: event.partialHtml, suggestions: [] });
          } else if (event.type === 'clause_start') {
            setGeneratingStatus({ label: event.label, index: event.index, total: event.totalClauses });
          } else if (event.type === 'clause_retry') {
            setGeneratingStatus(prev => ({ ...prev, label: prev?.label, retrying: `QA issues found -- fixing: ${event.fixing}` }));
          } else if (event.type === 'clause_done') {
            setGeneratingStatus({ label: event.label, index: event.index, total: event.totalClauses, done: true });
            setDraft(prev => ({ ...(prev || {}), draftHtml: event.partialHtml }));
            if (event.qaWarnings?.length > 0) {
              setQaWarnings(prev => ({ ...prev, [event.clauseType]: event.qaWarnings }));
            }
          } else if (event.type === 'complete') {
            setDraft({ draftHtml: event.draftHtml, suggestions: event.suggestions, draftParametersHtml: event.draftParametersHtml || '' });
            if (event.qaWarnings && Object.keys(event.qaWarnings).length > 0) {
              setQaWarnings(event.qaWarnings);
            }
            setLoading(false);
            setGeneratingStatus(null);
          } else if (event.type === 'error') {
            setError(event.message || 'Generation failed');
            setLoading(false);
            setGeneratingStatus(null);
          }
        }
      }
    } catch (e) {
      setError(e.message || 'Draft generation failed');
      setLoading(false);
      setGeneratingStatus(null);
    }
  };

  const handleDownloadDocx = async () => {
    if (!draft?.id) return;
    try {
      const res = await api.get(`/api/v1/ai/draft/async/${draft.id}/docx`, { responseType: 'blob' });
      const blob = new Blob([res.data], { type: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = draft.fileName?.replace('.html', '.docx') || 'draft-contract.docx';
      a.click();
      URL.revokeObjectURL(url);
    } catch (err) {
      const win = window.open('', '_blank');
      const title = draft.fileName?.replace('.html', '') || 'Draft-Contract';
      win.document.write(`<!DOCTYPE html><html><head><title>${title}</title>
        <style>body{font-family:Georgia,serif;font-size:12pt;line-height:1.6;margin:2cm;color:#000;}
        h1{font-size:18pt;text-align:center;}h2{font-size:14pt;margin-top:18pt;}
        p{margin-bottom:10pt;text-align:justify;}@media print{body{margin:2cm;}}</style>
        </head><body>${draft.draftHtml}</body></html>`);
      win.document.close();
      win.focus();
      setTimeout(() => win.print(), 500);
    }
  };

  const handleImproveSelection = async () => {
    if (!selectionInfo) return;
    const { text, x, y } = selectionInfo;
    setSelectionInfo(null);
    setRefiningAt({ x, y });
    setRefining(true);
    setError('');
    const markedHtml = `<mark data-ai="refining">${text}</mark>`;
    setDraft(prev => ({ ...prev, draftHtml: prev.draftHtml.replace(text, markedHtml) }));
    try {
      const docContext = stripHtml(draft.draftHtml).slice(0, 4000);
      const res = await api.post('/ai/refine-clause', {
        selectedText: text,
        documentContext: docContext,
        instruction: 'Improve for clarity, legal precision, and US law compliance.',
      });
      const improved = res.data?.improvedText || res.data?.improved_text;
      if (improved) {
        setPendingRefinement({ originalText: text, markedHtml, improvedText: improved, reasoning: res.data?.reasoning, x, y });
      }
    } catch (e) {
      setDraft(prev => ({ ...prev, draftHtml: prev.draftHtml.replace(markedHtml, text) }));
      setError(e.response?.data?.message || e.message || 'Refine failed');
    } finally {
      setRefining(false);
      setRefiningAt(null);
    }
  };

  const handleAcceptRefinement = () => {
    if (!pendingRefinement) return;
    const { markedHtml, improvedText, reasoning } = pendingRefinement;
    const acceptedMark = `<mark data-ai="accepted">${improvedText}</mark>`;
    setDraft(prev => ({
      ...prev,
      draftHtml: prev.draftHtml.replace(markedHtml, acceptedMark),
      suggestions: [...(prev.suggestions || []), { clauseRef: 'Refined selection', suggestion: improvedText, reasoning }],
    }));
    setTimeout(() => {
      setDraft(prev => ({ ...prev, draftHtml: prev.draftHtml.replace(acceptedMark, improvedText) }));
    }, 3000);
    setPendingRefinement(null);
  };

  const handleRejectRefinement = () => {
    if (pendingRefinement) {
      setDraft(prev => ({ ...prev, draftHtml: prev.draftHtml.replace(pendingRefinement.markedHtml, pendingRefinement.originalText) }));
    }
    setPendingRefinement(null);
  };

  const handleSaveDraft = async () => {
    if (!draft?.draftHtml) return;
    setSaving(true);
    setSaveSuccess(false);
    try {
      await api.post('/ai/draft/save', {
        draftHtml: draft.draftHtml,
        matterId: form.matterId || null,
        contractTypeName: form.contractTypeName || form.templateId,
        partyA: form.partyA || null,
        partyB: form.partyB || null,
        jurisdiction: form.jurisdiction || null,
      });
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 4000);
    } catch (e) {
      const status = e.response?.status;
      if (status === 403) {
        setError('You do not have access to that matter.');
      } else {
        setError(e.response?.data?.message || e.message || 'Save failed');
      }
    } finally {
      setSaving(false);
    }
  };

  const genDone = draft && !loading && !generatingStatus;

  return (
    <div style={{ display: 'grid', gridTemplateColumns: '360px 1fr', height: '100%' }}>
      {/* ── LEFT RAIL ────────────────────────────────────────────── */}
      <div style={{ borderRight: '1px solid var(--line-1)', background: 'var(--bg-1)', padding: '20px 18px', overflow: 'auto' }}>
        <StepHeader
          step={step}
          setStep={(n) => {
            if (n === 1) { setValidationResult(null); setPreviewClauses([]); }
          }}
          genDone={genDone}
        />

        {/* Step 1: Input form */}
        {step === 1 && (
          <div className="col" style={{ gap: 14 }}>
            <div className="field">
              <label>Contract type</label>
              <select
                className="select"
                value={form.templateId}
                onChange={e => setForm({ ...form, templateId: e.target.value, contractTypeName: '' })}
              >
                <option value="">Select contract type...</option>
                {templates.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
              </select>
              {form.templateId && templates.find(t => t.id === form.templateId)?.description && (
                <div className="hint">{templates.find(t => t.id === form.templateId).description}</div>
              )}
              {form.templateId === 'custom' && (
                <input
                  className="input"
                  value={form.contractTypeName}
                  onChange={e => setForm({ ...form, contractTypeName: e.target.value })}
                  placeholder="e.g. Joint Venture Agreement, Franchise Agreement..."
                  style={{ marginTop: 6 }}
                />
              )}
            </div>

            <div className="grid-2">
              <div className="field">
                <label>Party A</label>
                <input className="input" value={form.partyA} onChange={e => setForm({ ...form, partyA: e.target.value })} placeholder="Company name" />
              </div>
              <div className="field">
                <label>Party B</label>
                <input className="input" value={form.partyB} onChange={e => setForm({ ...form, partyB: e.target.value })} placeholder="Company name" />
              </div>
            </div>

            <div className="grid-2">
              <div className="field">
                <label>Party A Address</label>
                <input className="input" value={form.partyAAddress} onChange={e => setForm({ ...form, partyAAddress: e.target.value })} placeholder="Registered office" />
              </div>
              <div className="field">
                <label>Party B Address</label>
                <input className="input" value={form.partyBAddress} onChange={e => setForm({ ...form, partyBAddress: e.target.value })} placeholder="Registered office" />
              </div>
            </div>

            <div className="grid-2">
              <div className="field">
                <label>Party A Rep</label>
                <input className="input" value={form.partyARep} onChange={e => setForm({ ...form, partyARep: e.target.value })} placeholder="e.g. Mr. X, Director" />
              </div>
              <div className="field">
                <label>Party B Rep</label>
                <input className="input" value={form.partyBRep} onChange={e => setForm({ ...form, partyBRep: e.target.value })} placeholder="e.g. Ms. Y, MD" />
              </div>
            </div>

            <div className="grid-2">
              <div className="field">
                <label>Effective Date</label>
                <input type="date" className="input" value={form.effectiveDate} onChange={e => setForm({ ...form, effectiveDate: e.target.value })} />
              </div>
              <div className="field">
                <label>Jurisdiction</label>
                <select className="select" value={form.jurisdiction} onChange={e => setForm({ ...form, jurisdiction: e.target.value })}>
                  {JURISDICTIONS.map(j => <option key={j} value={j}>{j}</option>)}
                </select>
              </div>
            </div>

            <div className="grid-2">
              <div className="field">
                <label>Term (years)</label>
                <input className="input" value={form.termYears} onChange={e => setForm({ ...form, termYears: e.target.value })} placeholder="3" />
              </div>
              <div className="field">
                <label>Notice (days)</label>
                <input className="input" value={form.noticeDays} onChange={e => setForm({ ...form, noticeDays: e.target.value })} placeholder="30" />
              </div>
            </div>

            <div className="field">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <label>Agreement Ref (optional)</label>
                <div className="row" style={{ gap: 4 }}>
                  <button type="button" onClick={() => setRefMode('system')}
                    className={`btn sm ${refMode === 'system' ? 'primary' : ''}`} style={{ height: 22, fontSize: 10, padding: '0 8px' }}>
                    <FolderOpen size={10} /> System
                  </button>
                  <button type="button" onClick={() => setRefMode('manual')}
                    className={`btn sm ${refMode === 'manual' ? 'primary' : ''}`} style={{ height: 22, fontSize: 10, padding: '0 8px' }}>
                    <Pencil size={10} /> Manual
                  </button>
                </div>
              </div>
              {refMode === 'manual' ? (
                <input className="input" value={form.agreementRef} onChange={e => setForm({ ...form, agreementRef: e.target.value })} placeholder="e.g. NDA-2025-001" />
              ) : (
                <div className="row" style={{ gap: 6 }}>
                  <select className="select" value={form.agreementRef} onChange={e => setForm({ ...form, agreementRef: e.target.value })} style={{ flex: 1 }}>
                    <option value="">Select a document...</option>
                    {docs.map(d => <option key={d.id} value={d.fileName}>{d.fileName}</option>)}
                  </select>
                  <button type="button" onClick={() => navigate('/documents')} className="btn sm">+ Upload</button>
                </div>
              )}
            </div>

            {/* Deal context section */}
            <div style={{ borderTop: '1px solid var(--line-1)', paddingTop: 14, marginTop: 4 }}>
              <div className="nav-section" style={{ padding: '0 0 8px' }}>Negotiation Context</div>
              <div className="col" style={{ gap: 12 }}>
                <div className="field">
                  <label>Deal Brief <span style={{ color: 'var(--text-4)' }}>(optional)</span></label>
                  <textarea
                    className="textarea"
                    value={form.dealBrief}
                    onChange={e => setForm({ ...form, dealBrief: e.target.value })}
                    placeholder="Describe the deal in 2-4 sentences..."
                    rows={4}
                  />
                  <div className="hint row" style={{ justifyContent: 'space-between' }}>
                    <span>Describe parties, fees, term, key positions.</span>
                    <span className="mono">{form.dealBrief.length} chars</span>
                  </div>
                </div>
                <div className="grid-2">
                  <div className="field">
                    <label>We represent</label>
                    <select className="select" value={form.clientPosition} onChange={e => setForm({ ...form, clientPosition: e.target.value })}>
                      <option value="NEUTRAL">Neutral</option>
                      <option value="PARTY_A">Party A</option>
                      <option value="PARTY_B">Party B</option>
                    </select>
                  </div>
                  <div className="field">
                    <label>Draft stance</label>
                    <select className="select" value={form.draftStance} onChange={e => setForm({ ...form, draftStance: e.target.value })}>
                      <option value="FIRST_DRAFT">First draft</option>
                      <option value="BALANCED">Balanced</option>
                      <option value="FINAL_OFFER">Final offer</option>
                    </select>
                  </div>
                </div>
                <div className="field">
                  <label>Industry</label>
                  <select className="select" value={form.industry} onChange={e => setForm({ ...form, industry: e.target.value })}>
                    <option value="GENERAL">General / Not specified</option>
                    <option value="IT_SERVICES">IT Services</option>
                    <option value="FINTECH">Fintech</option>
                    <option value="PHARMA">Pharma / Healthcare</option>
                    <option value="MANUFACTURING">Manufacturing</option>
                  </select>
                </div>
              </div>
            </div>

            <button className="btn primary lg" onClick={handleGenerateAsync} disabled={submittingAsync || !form.templateId} style={{ width: '100%', justifyContent: 'center' }}>
              {submittingAsync ? <Loader2 size={14} className="animate-spin" /> : <Sparkles size={14} />}
              {submittingAsync ? 'Analysing...' : 'Analyse brief'}
            </button>
            <div className="small muted" style={{ textAlign: 'center' }}>
              <Lock size={11} style={{ verticalAlign: 'text-top', marginRight: 4 }} />
              Not used for training
            </div>
          </div>
        )}

        {/* Step 2: Preview / validation */}
        {step === 2 && validationResult && (
          <div className="col" style={{ gap: 16 }}>
            {/* Extracted deal terms */}
            {validationResult.extracted && (
              <div className="card">
                <div className="card-header">
                  <h3>Extracted deal terms</h3>
                  <span className="sub">Auto-parsed from brief</span>
                </div>
                <div style={{ padding: 14 }}>
                  {validationResult.extracted.partyA && <KV k={validationResult.partyRoles?.[0] || 'Party A'} v={validationResult.extracted.partyA} />}
                  {validationResult.extracted.partyB && <KV k={validationResult.partyRoles?.[1] || 'Party B'} v={validationResult.extracted.partyB} />}
                  {validationResult.extracted.jurisdiction && <KV k="Jurisdiction" v={validationResult.extracted.jurisdiction} />}
                  {validationResult.extracted.licenseFee && <KV k="Fee" v={`$${Number(validationResult.extracted.licenseFee).toLocaleString()}`} />}
                  {validationResult.extracted.licenseType && <KV k="License" v={validationResult.extracted.licenseType} />}
                  {validationResult.extracted.users && <KV k="Users" v={validationResult.extracted.users} />}
                  {validationResult.extracted.slaResponseHours && <KV k="SLA" v={`${validationResult.extracted.slaResponseHours}h response`} />}
                </div>
              </div>
            )}

            {/* Planned clauses */}
            {previewClauses.length > 0 && (
              <div className="card">
                <div className="card-header">
                  <h3>Planned clauses</h3>
                  <span className="sub">{previewClauses.filter(c => c.enabled).length} selected</span>
                </div>
                <div style={{ padding: 8 }}>
                  {previewClauses.map(c => (
                    <div key={c.key} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '7px 8px', borderRadius: 4, cursor: 'pointer' }} onClick={() => toggleClause(c.key)}>
                      <div className={`checkbox ${c.enabled ? 'checked' : ''}`} />
                      <span className="small" style={{ flex: 1, textDecoration: c.enabled ? 'none' : 'line-through', color: c.enabled ? 'var(--text-1)' : 'var(--text-4)' }}>{c.title}</span>
                    </div>
                  ))}
                  {validationResult.availableClauses?.filter(c => !previewClauses.some(p => p.key === c.key)).length > 0 && (
                    <select
                      className="select"
                      style={{ marginTop: 8, fontSize: 12, color: 'var(--brand-400)', borderStyle: 'dashed' }}
                      value=""
                      onChange={e => {
                        const key = e.target.value;
                        if (!key) return;
                        const clause = validationResult.availableClauses.find(c => c.key === key);
                        if (clause) setPreviewClauses(prev => [...prev, { key: clause.key, title: clause.title, enabled: true }]);
                      }}
                    >
                      <option value="">+ Add clause...</option>
                      {validationResult.availableClauses.filter(c => !previewClauses.some(p => p.key === c.key)).map(c => (
                        <option key={c.key} value={c.key}>{c.title}</option>
                      ))}
                    </select>
                  )}
                </div>
              </div>
            )}

            {/* Missing required */}
            {validationResult.missingRequired?.length > 0 && (
              <div className="card" style={{ borderColor: 'rgba(217,83,78,0.3)', background: 'var(--danger-bg)' }}>
                <div style={{ padding: 14 }}>
                  <div className="row" style={{ color: 'var(--danger-400)', fontWeight: 600, fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                    <AlertTriangle size={14} /> {validationResult.missingRequired.length} missing required field{validationResult.missingRequired.length > 1 ? 's' : ''}
                  </div>
                  <ul className="small" style={{ margin: '6px 0 0', paddingLeft: 18 }}>
                    {validationResult.missingRequired.map(f => <li key={f.field}>{f.label}</li>)}
                  </ul>
                </div>
              </div>
            )}

            {/* Missing recommended */}
            {validationResult.missingRecommended?.length > 0 && (
              <div className="card" style={{ borderColor: 'rgba(216,154,58,0.3)', background: 'var(--warn-bg)' }}>
                <div style={{ padding: 14 }}>
                  <div className="row" style={{ color: 'var(--warn-400)', fontWeight: 600, fontSize: 12, textTransform: 'uppercase', letterSpacing: '0.06em' }}>
                    <AlertTriangle size={14} /> {validationResult.missingRecommended.length} recommended
                  </div>
                  <ul className="small" style={{ margin: '6px 0 0', paddingLeft: 18 }}>
                    {validationResult.missingRecommended.map(f => <li key={f.field}>{f.label} (will use placeholder if skipped)</li>)}
                  </ul>
                </div>
              </div>
            )}

            <div className="row" style={{ gap: 8 }}>
              <button className="btn" onClick={() => { setValidationResult(null); setPreviewClauses([]); }}>
                <ChevronLeft size={14} /> Edit details
              </button>
              <button className="btn primary grow" onClick={confirmGenerate} disabled={submittingAsync} style={{ justifyContent: 'center' }}>
                {submittingAsync ? <Loader2 size={14} className="animate-spin" /> : null}
                Confirm & generate <ArrowRight size={14} />
              </button>
            </div>
          </div>
        )}

        {/* Step 3: Generation sidebar */}
        {step === 3 && (
          <div className="col" style={{ gap: 14 }}>
            <div className="card">
              <div className="card-header">
                <h3>{loading ? 'Generating...' : genDone ? 'Draft ready' : 'Ready to generate'}</h3>
                {genDone && generatingStatus?.label && (
                  <span className="badge low" style={{ marginLeft: 'auto' }}><Check size={10} /> {generatingStatus.label}</span>
                )}
              </div>
              <div style={{ padding: 14 }}>
                {loading && generatingStatus && (
                  <>
                    <div className="row small" style={{ justifyContent: 'space-between', marginBottom: 8 }}>
                      <span>{generatingStatus.label}</span>
                      <span className="mono muted">{generatingStatus.index}/{generatingStatus.total}</span>
                    </div>
                    <div className="progress-track">
                      <div className="progress-fill" style={{ width: `${generatingStatus.total > 0 ? (generatingStatus.index / generatingStatus.total) * 100 : 0}%` }} />
                    </div>
                    {generatingStatus.retrying && (
                      <div className="tiny" style={{ color: 'var(--warn-400)', marginTop: 6 }}>
                        <AlertTriangle size={10} style={{ verticalAlign: 'middle', marginRight: 4 }} />
                        {generatingStatus.retrying}
                      </div>
                    )}
                  </>
                )}
                {genDone && (
                  <>
                    <div className="small" style={{ marginBottom: 10 }}>
                      Draft complete.{draft?.durationSeconds ? ` Generated in ${Math.round(draft.durationSeconds / 60)}m ${draft.durationSeconds % 60}s.` : ''}
                    </div>
                    {/* Matter selector */}
                    <div className="field" style={{ marginBottom: 10 }}>
                      <label>Save to Matter</label>
                      <select
                        className="select"
                        value={form.matterId || ''}
                        onChange={(e) => setForm(f => ({ ...f, matterId: e.target.value }))}
                      >
                        <option value="">-- No matter (standalone) --</option>
                        {matters.map(m => (
                          <option key={m.id} value={m.id}>{m.name} {m.matterRef ? `(${m.matterRef})` : ''}</option>
                        ))}
                      </select>
                    </div>
                    <div className="col" style={{ gap: 6 }}>
                      <button className="btn" onClick={handleDownloadDocx}>
                        <Download size={14} /> Download Word
                      </button>
                      <button className="btn" onClick={handleSaveDraft} disabled={saving}>
                        {saving ? <Loader2 size={14} className="animate-spin" /> : saveSuccess ? <Check size={14} /> : <Save size={14} />}
                        {saving ? 'Saving...' : saveSuccess ? 'Saved!' : (form.matterId ? 'Save to Matter' : 'Save')}
                      </button>
                      <button className="btn" onClick={() => setShowSaveToCloud(true)}>
                        <CloudUpload size={14} /> Save to Cloud
                      </button>
                      {draft?.id && (
                        <button className="btn primary" onClick={() => setShowSignModal(true)}>
                          <Send size={14} /> Send for Signature
                        </button>
                      )}
                    </div>
                  </>
                )}
              </div>
            </div>

            {submitToast && (
              <div className="card" style={{ padding: 12, borderLeftColor: 'var(--success-500)', borderLeftWidth: 3 }}>
                <div className="small" style={{ color: 'var(--success-400)' }}>
                  <Clock size={12} style={{ verticalAlign: 'middle', marginRight: 6 }} />
                  {submitToast}
                </div>
              </div>
            )}

            {genDone && (
              <button className="btn ghost" onClick={() => { setDraft(null); setGeneratingStatus(null); setValidationResult(null); setPreviewClauses([]); setActiveAsyncId(null); setError(''); }}>
                <RefreshCw size={14} /> Start another draft
              </button>
            )}
          </div>
        )}
      </div>

      {/* ── RIGHT PANE ───────────────────────────────────────────── */}
      <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0, background: 'var(--bg-0)' }}>
        <DraftsRecentStrip
          activeId={activeAsyncId}
          onSelect={(id) => {
            setActiveAsyncId(id);
            setError('');
            setDraft(null);
            setValidationResult(null);
            setPreviewClauses([]);
            setGeneratingStatus({ label: 'Loading...', index: 0, total: 0 });
            setLoading(true);
          }}
          onReady={(stripApi) => { stripRef.current = stripApi; }}
        />

        <div style={{ flex: 1, overflow: 'auto', padding: 24 }}>
          {error && (
            <div className="card" style={{ borderLeftColor: 'var(--danger-500)', borderLeftWidth: 3, padding: 14, marginBottom: 14 }}>
              <div className="small" style={{ color: 'var(--danger-400)' }}>{error}</div>
            </div>
          )}

          {/* QA Warnings */}
          {Object.keys(qaWarnings).length > 0 && (
            <div className="card" style={{ borderLeftColor: 'var(--warn-500)', borderLeftWidth: 3, padding: 14, marginBottom: 14 }}>
              <div className="row" style={{ color: 'var(--warn-400)', fontWeight: 600, fontSize: 12, marginBottom: 8 }}>
                <AlertTriangle size={14} /> QA Warnings -- Review Required
              </div>
              <div className="col" style={{ gap: 6 }}>
                {Object.entries(qaWarnings).map(([clauseKey, warnings]) => (
                  <div key={clauseKey} style={{ padding: 10, borderRadius: 'var(--r-md)', background: 'var(--warn-bg)', border: '1px solid rgba(216,154,58,0.2)' }}>
                    <div className="tiny" style={{ fontWeight: 600, color: 'var(--warn-400)', marginBottom: 4 }}>{CLAUSE_SPECS_LABELS[clauseKey] || clauseKey}</div>
                    {warnings.map((w, i) => (
                      <div key={i} className="tiny" style={{ color: 'var(--text-2)', display: 'flex', gap: 6, marginTop: 2 }}>
                        <span style={{ color: 'var(--warn-400)' }}>&bull;</span> {w}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Suggestions */}
          {draft?.suggestions?.length > 0 && (
            <div className="card" style={{ padding: 14, marginBottom: 14 }}>
              <div className="row" style={{ marginBottom: 10 }}>
                <Lightbulb size={14} style={{ color: 'var(--warn-400)' }} />
                <span style={{ fontWeight: 600, fontSize: 13 }}>AI Suggestions</span>
              </div>
              <div className="col" style={{ gap: 8 }}>
                {draft.suggestions.map((s, i) => (
                  <div key={i} style={{ padding: 10, background: 'var(--bg-2)', borderRadius: 'var(--r-md)', border: '1px solid var(--line-1)' }}>
                    <div className="tiny muted">{s.clauseRef}</div>
                    <div className="small" style={{ marginTop: 4 }}>{s.suggestion}</div>
                    {s.reasoning && <div className="tiny muted" style={{ marginTop: 6 }}>{s.reasoning}</div>}
                  </div>
                ))}
              </div>
            </div>
          )}

          {/* Draft document preview */}
          {draft?.draftHtml && (
            <div style={{ maxWidth: 820, margin: '0 auto' }}>
              {/* Action bar */}
              <div style={{
                display: 'flex', alignItems: 'center', gap: 10,
                padding: '12px 14px', background: 'var(--bg-1)', border: '1px solid var(--line-1)',
                borderRadius: 8, marginBottom: 14,
              }}>
                <FileText size={16} style={{ color: 'var(--text-3)' }} />
                <div>
                  <div style={{ fontWeight: 600, fontSize: 13 }}>{draft.fileName || 'Draft Contract'}</div>
                  <div className="tiny muted">{form.jurisdiction} &middot; {new Date().toLocaleDateString()}</div>
                </div>
                {genDone && (
                  <div className="row" style={{ marginLeft: 'auto', gap: 6 }}>
                    <button className="btn sm" onClick={handleDownloadDocx}><Download size={12} /> Word</button>
                    <button className="btn sm" onClick={handleSaveDraft}><Save size={12} /> Save</button>
                  </div>
                )}
                {loading && generatingStatus && (
                  <div className="row" style={{ marginLeft: 'auto', gap: 10 }}>
                    <div className="small muted">{generatingStatus.label} ({generatingStatus.index}/{generatingStatus.total})</div>
                    <div style={{ width: 140 }}>
                      <div className="progress-track">
                        <div className="progress-fill" style={{ width: `${generatingStatus.total > 0 ? (generatingStatus.index / generatingStatus.total) * 100 : 0}%` }} />
                      </div>
                    </div>
                  </div>
                )}
              </div>

              {/* Rendered HTML doc */}
              <div
                ref={previewRef}
                onMouseUp={handlePreviewMouseUp}
                className="doc"
                style={{ cursor: 'text', userSelect: 'text' }}
                dangerouslySetInnerHTML={{ __html: extractBodyContent(draft.draftHtml) }}
              />

              {/* Parameters */}
              {draft.draftParametersHtml && (
                <details className="collapsible" style={{ marginTop: 14 }} open={showParams} onToggle={e => setShowParams(e.target.open)}>
                  <summary>
                    <ChevronRight size={14} className="chev" />
                    <span>Draft generation parameters</span>
                    <span className="tiny muted" style={{ marginLeft: 'auto' }}>Metadata -- not part of the document</span>
                  </summary>
                  <div className="body" dangerouslySetInnerHTML={{ __html: draft.draftParametersHtml }} />
                </details>
              )}
            </div>
          )}

          {/* Loading placeholder */}
          {loading && !draft?.draftHtml && (
            <div style={{
              maxWidth: 820, margin: '0 auto', padding: 60,
              background: '#fafaf7', borderRadius: 8, minHeight: '60%',
              display: 'grid', placeItems: 'center', opacity: 0.6,
            }}>
              <div style={{ textAlign: 'center', color: 'var(--text-3)' }}>
                <Loader2 size={32} className="animate-spin" style={{ margin: '0 auto 12px' }} />
                <div className="small">Generating your draft...</div>
              </div>
            </div>
          )}

          {/* Empty state */}
          {!draft && !loading && !validationResult && (
            <div style={{
              maxWidth: 820, margin: '0 auto', padding: 60,
              background: '#fafaf7', borderRadius: 8, minHeight: '60%',
              display: 'grid', placeItems: 'center', opacity: 0.5,
              position: 'relative',
            }}>
              <div style={{ textAlign: 'center', color: 'var(--text-3)' }}>
                <FileText size={40} style={{ opacity: 0.4, margin: '0 auto 10px' }} />
                <div className="small">Fill in the brief and generate to see the draft.</div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* ── Floating UI elements ─────────────────────────────────── */}
      {showSaveToCloud && (
        <SaveToCloudModal
          content={draft?.draftHtml}
          defaultFileName={`draft-${form.templateId}-${form.effectiveDate || 'draft'}.html`}
          onClose={() => setShowSaveToCloud(false)}
          onSaved={() => setShowSaveToCloud(false)}
        />
      )}
      {showSignModal && draft?.id && (
        <SendForSignatureModal
          docId={draft.id}
          docName={draft.fileName || `${form.templateId}-draft`}
          matterId={form.matterId}
          parties={{ partyA: form.partyA, partyB: form.partyB }}
          onClose={() => setShowSignModal(false)}
          onSent={() => {}}
        />
      )}

      {/* Floating improve button */}
      {selectionInfo && !refining && (
        <div ref={floatRef} className="fixed z-50 -translate-x-1/2" style={{ left: selectionInfo.x, top: selectionInfo.y }}>
          <button onClick={handleImproveSelection} className="btn primary sm" style={{ borderRadius: 999 }}>
            <Sparkles size={12} /> Improve with AI
          </button>
        </div>
      )}

      {/* Refining progress */}
      {refiningAt && (
        <div className="fixed z-50 -translate-x-1/2" style={{ left: refiningAt.x, top: refiningAt.y }}>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6, padding: '8px 12px', background: 'var(--bg-2)', border: '1px solid var(--line-2)', borderRadius: 'var(--r-lg)', boxShadow: 'var(--shadow-3)' }}>
            <span className="tiny muted" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
              <Sparkles size={12} style={{ color: 'var(--brand-400)' }} /> Improving...
            </span>
            <div style={{ width: 100, height: 3, background: 'var(--bg-3)', borderRadius: 999, overflow: 'hidden', position: 'relative' }}>
              <div style={{ position: 'absolute', top: 0, left: 0, height: '100%', width: '40%', background: 'var(--brand-500)', borderRadius: 999, animation: 'indeterminate 1.2s ease-in-out infinite' }} />
            </div>
          </div>
        </div>
      )}

      {/* AI refinement panel */}
      {pendingRefinement && (
        <div className="fixed bottom-6 right-6 z-50 w-[400px] max-w-[calc(100vw-24px)] card" style={{ maxHeight: 'calc(100vh - 48px)', display: 'flex', flexDirection: 'column', boxShadow: 'var(--shadow-3)' }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '12px 14px', borderBottom: '1px solid var(--line-1)' }}>
            <span className="tiny" style={{ fontWeight: 600, display: 'flex', alignItems: 'center', gap: 6 }}>
              <Sparkles size={12} style={{ color: 'var(--brand-400)' }} /> AI Suggestion -- compare and decide
            </span>
            <button onClick={handleRejectRefinement} className="icon-btn" style={{ width: 24, height: 24 }}><X size={14} /></button>
          </div>
          <div style={{ overflowY: 'auto', padding: 14, flex: 1 }}>
            <div style={{ marginBottom: 12 }}>
              <div className="tiny" style={{ fontWeight: 600, color: 'var(--danger-400)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 6 }}>Original</div>
              <div className="small" style={{ padding: 10, borderRadius: 'var(--r-md)', background: 'var(--danger-bg)', border: '1px solid rgba(217,83,78,0.2)', textDecoration: 'line-through', color: 'var(--text-2)', whiteSpace: 'pre-wrap' }}>
                {pendingRefinement.originalText}
              </div>
            </div>
            <div style={{ marginBottom: 12 }}>
              <div className="tiny" style={{ fontWeight: 600, color: 'var(--success-400)', textTransform: 'uppercase', letterSpacing: '0.06em', marginBottom: 6 }}>Improved</div>
              <div className="small" style={{ padding: 10, borderRadius: 'var(--r-md)', background: 'var(--success-bg)', border: '1px solid rgba(63,169,107,0.2)', color: 'var(--text-1)', whiteSpace: 'pre-wrap' }}>
                {pendingRefinement.improvedText}
              </div>
            </div>
            {pendingRefinement.reasoning && (
              <div className="tiny muted" style={{ fontStyle: 'italic' }}>{pendingRefinement.reasoning}</div>
            )}
          </div>
          <div style={{ display: 'flex', gap: 8, padding: '12px 14px', borderTop: '1px solid var(--line-1)' }}>
            <button onClick={handleAcceptRefinement} className="btn" style={{ flex: 1, justifyContent: 'center', background: 'var(--success-bg)', borderColor: 'rgba(63,169,107,0.3)', color: 'var(--success-400)' }}>
              <Check size={12} /> Accept
            </button>
            <button onClick={handleRejectRefinement} className="btn" style={{ flex: 1, justifyContent: 'center' }}>
              <X size={12} /> Discard
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

/* Extracted term row */
function KV({ k, v, missing }) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '110px 1fr', gap: 8, padding: '6px 0', borderBottom: '1px dashed var(--line-1)', alignItems: 'center' }}>
      <div className="small muted">{k}</div>
      <div className="small" style={{ fontWeight: 500, color: missing ? 'var(--danger-400)' : undefined }}>{missing ? 'Not specified' : v}</div>
    </div>
  );
}
