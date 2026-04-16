import { useState, useEffect, useRef, useCallback } from 'react';
import { FileEdit, Loader, Download, Lightbulb, Sparkles, CloudUpload, Check, X, FileText, Save, FolderOpen, Pencil, AlertTriangle, Briefcase, Clock } from 'lucide-react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import api from '../api/client';
import SaveToCloudModal from '../components/SaveToCloudModal';
import DraftsRecentStrip from '../components/DraftsRecentStrip';

function stripHtml(html) {
  const div = document.createElement('div');
  div.innerHTML = html || '';
  return div.innerText || div.textContent || '';
}

// Extract only the <body> content so the template's <style> block
// doesn't leak out and break the app layout when rendered inline.
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

export default function DraftPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const matterIdFromUrl = searchParams.get('matterId');

  const [templates, setTemplates] = useState([]);
  const [docs, setDocs] = useState([]);
  const [matters, setMatters] = useState([]); // matters user has access to
  const [refMode, setRefMode] = useState('manual'); // 'manual' | 'system'
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState(null);
  const [error, setError] = useState('');
  const [generatingStatus, setGeneratingStatus] = useState(null); // {label, index, total}
  const [refining, setRefining] = useState(false);
  const [refiningAt, setRefiningAt] = useState(null); // {x, y} — position of in-progress floating bar
  const [showSaveToCloud, setShowSaveToCloud] = useState(false);
  const [selectionInfo, setSelectionInfo] = useState(null);
  const [pendingRefinement, setPendingRefinement] = useState(null);
  const [qaWarnings, setQaWarnings] = useState({}); // clauseKey → string[]
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const [submittingAsync, setSubmittingAsync] = useState(false);
  const [submitToast, setSubmitToast] = useState('');
  // When set, the right panel shows an existing async draft (polled from the server)
  // instead of whatever `draft` held from a fresh live stream.
  const [activeAsyncId, setActiveAsyncId] = useState(null);
  const stripRef = useRef(null);
  const previewRef = useRef(null);
  const floatRef = useRef(null);

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
    // Load matters the user has access to (backend already filters by membership)
    api.get('/matters').then(r => {
      const list = r.data?.content || r.data || [];
      setMatters(Array.isArray(list) ? list : []);
    }).catch(() => setMatters([]));
  }, []);

  // If matterId came from URL, hydrate the form fields once matters are loaded
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
      setSelectionInfo({
        text,
        x: rect.left + rect.width / 2,
        y: rect.bottom + 8,
      });
      setPendingRefinement(null);
    } else {
      setSelectionInfo(null);
    }
  }, []);

  // Dismiss floaters when clicking outside preview
  useEffect(() => {
    const onMouseDown = (e) => {
      if (!previewRef.current?.contains(e.target) && !floatRef.current?.contains(e.target)) {
        setSelectionInfo(null);
      }
    };
    document.addEventListener('mousedown', onMouseDown);
    return () => document.removeEventListener('mousedown', onMouseDown);
  }, []);

  // Submit an async draft — returns immediately with an id; the strip polls progress.
  // Every "Generate" click runs async. The draft appears in the Recent Drafts
  // strip; the preview pane stays on whatever the user was looking at. User
  // clicks the strip entry to watch it (or see the finished result).
  const handleGenerateAsync = async () => {
    if (!form.templateId) return;
    if (form.templateId === 'custom' && !form.contractTypeName.trim()) return;
    setSubmittingAsync(true);
    setError('');
    try {
      await api.post('/ai/draft/async', form);
      if (stripRef.current?.refresh) await stripRef.current.refresh();
      setSubmitToast('Draft started — watch it in “Your recent drafts” above.');
      setTimeout(() => setSubmitToast(''), 6000);
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Failed to submit draft');
    } finally {
      setSubmittingAsync(false);
    }
  };

  // Poll a single async draft when the user selects one from the strip (or just
  // submitted one). Renders identically to the live-stream path — it just updates
  // `draft.draftHtml` + `generatingStatus` as each clause lands server-side.
  useEffect(() => {
    if (!activeAsyncId) return;
    let cancelled = false;

    const poll = async () => {
      try {
        const r = await api.get(`/ai/draft/async/${activeAsyncId}`);
        if (cancelled) return;
        const d = r.data;
        // Always update the preview with whatever is persisted server-side.
        setDraft({ draftHtml: d.draftHtml || '', suggestions: [] });

        if (d.status === 'PENDING') {
          setGeneratingStatus({ label: 'Queued — waiting for capacity', index: 0, total: d.totalClauses || 0 });
          setLoading(true);
        } else if (d.status === 'PROCESSING') {
          setGeneratingStatus({
            label: d.currentClauseLabel || 'Generating',
            index: d.completedClauses || 0,
            total: d.totalClauses || 9,
          });
          setLoading(true);
        } else if (d.status === 'INDEXED') {
          setGeneratingStatus(null);
          setLoading(false);
          return 'done';
        } else if (d.status === 'FAILED') {
          setGeneratingStatus(null);
          setLoading(false);
          setError(d.errorMessage || 'Draft failed');
          return 'done';
        }
      } catch (e) {
        // swallow transient polling errors; try again on next tick
      }
      return null;
    };

    // Fire once, then set up a 3s interval until status is terminal.
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
    // Stop any async-draft polling — we're taking over with a live stream.
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
            setGeneratingStatus({ label: 'Planning sections…', index: 0, total: 0 });
          } else if (event.type === 'start') {
            setGeneratingStatus({ label: 'Preparing…', index: 0, total: event.totalClauses });
            setDraft({ draftHtml: event.partialHtml, suggestions: [] });
          } else if (event.type === 'clause_start') {
            setGeneratingStatus({ label: event.label, index: event.index, total: event.totalClauses });
          } else if (event.type === 'clause_retry') {
            setGeneratingStatus(prev => ({ ...prev, label: prev?.label, retrying: `QA issues found — fixing: ${event.fixing}` }));
          } else if (event.type === 'clause_done') {
            setGeneratingStatus({ label: event.label, index: event.index, total: event.totalClauses, done: true });
            setDraft(prev => ({ ...(prev || {}), draftHtml: event.partialHtml }));
            if (event.qaWarnings?.length > 0) {
              setQaWarnings(prev => ({ ...prev, [event.clauseType]: event.qaWarnings }));
            }
          } else if (event.type === 'complete') {
            setDraft({ draftHtml: event.draftHtml, suggestions: event.suggestions });
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

  const handleDownloadHtml = () => {
    if (!draft?.draftHtml) return;
    const blob = new Blob([draft.draftHtml], { type: 'text/html' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `draft-${form.templateId}-${form.effectiveDate || 'draft'}.html`;
    a.click();
    URL.revokeObjectURL(url);
  };

  const handleDownloadPdf = () => {
    if (!draft?.draftHtml) return;
    const win = window.open('', '_blank');
    win.document.write(`<!DOCTYPE html><html><head>
      <title>Draft Contract</title>
      <style>
        body { font-family: Georgia, serif; font-size: 12pt; line-height: 1.6; margin: 2cm; color: #000; }
        h1 { font-size: 18pt; text-align: center; margin-bottom: 24pt; }
        h2 { font-size: 14pt; margin-top: 18pt; }
        h3 { font-size: 12pt; }
        p { margin-bottom: 10pt; text-align: justify; }
        ul { margin-left: 20pt; margin-bottom: 10pt; }
        @media print { body { margin: 2cm; } }
      </style>
    </head><body>${draft.draftHtml}</body></html>`);
    win.document.close();
    win.focus();
    setTimeout(() => { win.print(); }, 500);
  };

  const handleImproveSelection = async () => {
    if (!selectionInfo) return;
    const { text, x, y } = selectionInfo;
    setSelectionInfo(null);
    setRefiningAt({ x, y });
    setRefining(true);
    setError('');

    // Highlight the selected text in the draft while the LLM works
    const markedHtml = `<mark data-ai="refining">${text}</mark>`;
    setDraft(prev => ({
      ...prev,
      draftHtml: prev.draftHtml.replace(text, markedHtml),
    }));

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
      // Remove the highlight if the call failed
      setDraft(prev => ({
        ...prev,
        draftHtml: prev.draftHtml.replace(markedHtml, text),
      }));
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
      suggestions: [
        ...(prev.suggestions || []),
        { clauseRef: 'Refined selection', suggestion: improvedText, reasoning },
      ],
    }));
    // Strip the accepted highlight after 3 seconds
    setTimeout(() => {
      setDraft(prev => ({
        ...prev,
        draftHtml: prev.draftHtml.replace(acceptedMark, improvedText),
      }));
    }, 3000);
    setPendingRefinement(null);
  };

  const handleRejectRefinement = () => {
    if (pendingRefinement) {
      setDraft(prev => ({
        ...prev,
        draftHtml: prev.draftHtml.replace(pendingRefinement.markedHtml, pendingRefinement.originalText),
      }));
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

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6 flex items-center gap-2">
        <FileEdit className="w-7 h-7" />
        AI-Assisted Drafting
      </h1>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Left form panel */}
        <div className="lg:col-span-1">
          <div className="card sticky top-4">
            <h3 className="font-semibold mb-4">Create Draft</h3>
            <div className="space-y-4">
              <div>
                <label className="text-xs text-text-muted mb-1 block">Contract Type</label>
                <select
                  value={form.templateId}
                  onChange={e => setForm({ ...form, templateId: e.target.value, contractTypeName: '' })}
                  className="input-field w-full text-sm"
                >
                  <option value="">Select contract type…</option>
                  {templates.map(t => (
                    <option key={t.id} value={t.id}>{t.name}</option>
                  ))}
                </select>
                {form.templateId && templates.find(t => t.id === form.templateId)?.description && (
                  <p className="text-xs text-text-muted mt-1">
                    {templates.find(t => t.id === form.templateId).description}
                  </p>
                )}
                {form.templateId === 'custom' && (
                  <input
                    value={form.contractTypeName}
                    onChange={e => setForm({ ...form, contractTypeName: e.target.value })}
                    placeholder="e.g. Joint Venture Agreement, Franchise Agreement…"
                    className="input-field w-full text-sm mt-2"
                  />
                )}
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Party A</label>
                <input value={form.partyA} onChange={e => setForm({ ...form, partyA: e.target.value })} placeholder="Company name" className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Party B</label>
                <input value={form.partyB} onChange={e => setForm({ ...form, partyB: e.target.value })} placeholder="Company name" className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Party A Address</label>
                <input value={form.partyAAddress} onChange={e => setForm({ ...form, partyAAddress: e.target.value })} placeholder="Registered office address" className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Party B Address</label>
                <input value={form.partyBAddress} onChange={e => setForm({ ...form, partyBAddress: e.target.value })} placeholder="Registered office address" className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Party A Representative</label>
                <input value={form.partyARep} onChange={e => setForm({ ...form, partyARep: e.target.value })} placeholder="e.g. Mr. X, Director" className="input-field w-full text-sm" />
              </div>
              <div>
                <label className="text-xs text-text-muted mb-1 block">Party B Representative</label>
                <input value={form.partyBRep} onChange={e => setForm({ ...form, partyBRep: e.target.value })} placeholder="e.g. Ms. Y, Managing Director" className="input-field w-full text-sm" />
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-text-muted mb-1 block">Effective Date</label>
                  <input type="date" value={form.effectiveDate} onChange={e => setForm({ ...form, effectiveDate: e.target.value })} className="input-field w-full text-sm" />
                </div>
                <div>
                  <label className="text-xs text-text-muted mb-1 block">Jurisdiction</label>
                  <select value={form.jurisdiction} onChange={e => setForm({ ...form, jurisdiction: e.target.value })} className="input-field w-full text-sm">
                    {JURISDICTIONS.map(j => <option key={j} value={j}>{j}</option>)}
                  </select>
                </div>
              </div>
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-text-muted mb-1 block">Term (years)</label>
                  <input type="text" value={form.termYears} onChange={e => setForm({ ...form, termYears: e.target.value })} placeholder="3" className="input-field w-full text-sm" />
                </div>
                <div>
                  <label className="text-xs text-text-muted mb-1 block">Notice (days)</label>
                  <input type="text" value={form.noticeDays} onChange={e => setForm({ ...form, noticeDays: e.target.value })} placeholder="30" className="input-field w-full text-sm" />
                </div>
              </div>
              <div>
                <div className="flex items-center justify-between mb-1">
                  <label className="text-xs text-text-muted">Agreement Ref (optional)</label>
                  <div className="flex gap-1">
                    <button type="button" onClick={() => setRefMode('system')}
                      className={`flex items-center gap-1 px-2 py-0.5 rounded text-xs transition-colors ${refMode === 'system' ? 'bg-primary text-white' : 'text-text-muted hover:text-text-primary'}`}>
                      <FolderOpen className="w-3 h-3" /> From system
                    </button>
                    <button type="button" onClick={() => setRefMode('manual')}
                      className={`flex items-center gap-1 px-2 py-0.5 rounded text-xs transition-colors ${refMode === 'manual' ? 'bg-primary text-white' : 'text-text-muted hover:text-text-primary'}`}>
                      <Pencil className="w-3 h-3" /> Manual
                    </button>
                  </div>
                </div>
                {refMode === 'manual' ? (
                  <input value={form.agreementRef} onChange={e => setForm({ ...form, agreementRef: e.target.value })}
                    placeholder="e.g. NDA-2025-001" className="input-field w-full text-sm" />
                ) : (
                  <div className="flex gap-2">
                    <select value={form.agreementRef} onChange={e => setForm({ ...form, agreementRef: e.target.value })}
                      className="input-field w-full text-sm">
                      <option value="">Select a document...</option>
                      {docs.map(d => <option key={d.id} value={d.fileName}>{d.fileName}</option>)}
                    </select>
                    <button type="button" onClick={() => navigate('/documents')}
                      className="btn-secondary text-xs px-2 shrink-0" title="Upload a new document">
                      + Upload
                    </button>
                  </div>
                )}
              </div>
              {/* ── Deal Context ─────────────────────────── */}
              <div className="border-t border-border pt-4 mt-2">
                <p className="text-xs font-semibold text-text-muted uppercase tracking-wide mb-3">Negotiation Context</p>
                <div className="space-y-3">
                  <div>
                    <label className="text-xs text-text-muted mb-1 block">Deal Brief <span className="text-text-muted/60">(optional)</span></label>
                    <textarea
                      value={form.dealBrief}
                      onChange={e => setForm({ ...form, dealBrief: e.target.value })}
                      placeholder="Describe the deal in 2-4 sentences. E.g. 'SaaS vendor providing cloud payroll software to a mid-size Fintech. Vendor is a startup; client has high leverage. Key risk: data residency and liability cap.'"
                      rows={3}
                      className="input-field w-full text-sm resize-none"
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div>
                      <label className="text-xs text-text-muted mb-1 block">We represent</label>
                      <select value={form.clientPosition} onChange={e => setForm({ ...form, clientPosition: e.target.value })} className="input-field w-full text-sm">
                        <option value="NEUTRAL">Neutral</option>
                        <option value="PARTY_A">Party A</option>
                        <option value="PARTY_B">Party B</option>
                      </select>
                    </div>
                    <div>
                      <label className="text-xs text-text-muted mb-1 block">Draft stance</label>
                      <select value={form.draftStance} onChange={e => setForm({ ...form, draftStance: e.target.value })} className="input-field w-full text-sm">
                        <option value="FIRST_DRAFT">First draft</option>
                        <option value="BALANCED">Balanced</option>
                        <option value="FINAL_OFFER">Final offer</option>
                      </select>
                    </div>
                  </div>
                  <div>
                    <label className="text-xs text-text-muted mb-1 block">Industry</label>
                    <select value={form.industry} onChange={e => setForm({ ...form, industry: e.target.value })} className="input-field w-full text-sm">
                      <option value="GENERAL">General / Not specified</option>
                      <option value="IT_SERVICES">IT Services</option>
                      <option value="FINTECH">Fintech</option>
                      <option value="PHARMA">Pharma / Healthcare</option>
                      <option value="MANUFACTURING">Manufacturing</option>
                    </select>
                  </div>
                </div>
              </div>

              <div className="flex flex-col gap-2">
                <button onClick={handleGenerateAsync} disabled={submittingAsync || !form.templateId} className="btn-primary w-full flex items-center justify-center gap-2 py-3"
                        title="Runs the draft on the server. You can keep working, close the tab, or come back later — click the entry in the strip above to watch or open it.">
                  {submittingAsync ? <Loader className="w-5 h-5 animate-spin" /> : <FileEdit className="w-5 h-5" />}
                  {submittingAsync ? 'Starting…' : 'Generate Draft'}
                </button>
                {submitToast && (
                  <p className="text-xs text-success flex items-center gap-1.5 py-1">
                    <Clock className="w-3 h-3" /> {submitToast}
                  </p>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* Right preview panel */}
        <div className="lg:col-span-2 space-y-6">
          <DraftsRecentStrip
            activeId={activeAsyncId}
            onSelect={(id) => {
              setActiveAsyncId(id);
              setError('');
              setDraft(null);
              setGeneratingStatus({ label: 'Loading…', index: 0, total: 0 });
              setLoading(true);
            }}
            onReady={(api) => { stripRef.current = api; }}
          />

          {error && (
            <div className="card border-l-4 border-danger bg-danger/5">
              <p className="text-danger text-sm">{error}</p>
            </div>
          )}

          {draft && (
            <>
              <div className="card space-y-3">
                <div className="flex items-center justify-between flex-wrap gap-3">
                  <span className="text-text-secondary text-sm">Select text in the preview to improve it inline</span>
                </div>
                <div className="flex items-center gap-3 flex-wrap">
                  <Briefcase className="w-4 h-4 text-text-muted" />
                  <label className="text-sm text-text-secondary">Save to Matter:</label>
                  <select
                    value={form.matterId || ''}
                    onChange={(e) => setForm(f => ({ ...f, matterId: e.target.value }))}
                    className="input-field text-sm flex-1 min-w-[200px] max-w-xs"
                  >
                    <option value="">— No matter (standalone) —</option>
                    {matters.map(m => (
                      <option key={m.id} value={m.id}>
                        {m.name} {m.matterRef ? `(${m.matterRef})` : ''}
                      </option>
                    ))}
                  </select>
                  {matters.length === 0 && (
                    <span className="text-xs text-text-muted">No matters available</span>
                  )}
                </div>
                <div className="flex gap-2 flex-wrap">
                  <button
                    onClick={handleSaveDraft}
                    disabled={saving}
                    className="btn-primary flex items-center gap-2 text-sm"
                  >
                    {saving ? <Loader className="w-4 h-4 animate-spin" /> : saveSuccess ? <Check className="w-4 h-4" /> : <Save className="w-4 h-4" />}
                    {saving ? 'Saving...' : saveSuccess ? 'Saved!' : (form.matterId ? 'Save to Matter' : 'Save')}
                  </button>
                  <button onClick={() => setShowSaveToCloud(true)} className="btn-secondary flex items-center gap-2 text-sm">
                    <CloudUpload className="w-4 h-4" />
                    Save to Cloud
                  </button>
                  <button onClick={handleDownloadPdf} className="btn-secondary flex items-center gap-2 text-sm">
                    <FileText className="w-4 h-4" />
                    Download PDF
                  </button>
                  <button onClick={handleDownloadHtml} className="btn-secondary flex items-center gap-2 text-sm">
                    <Download className="w-4 h-4" />
                    Download HTML
                  </button>
                </div>
              </div>

              {showSaveToCloud && (
                <SaveToCloudModal
                  content={draft.draftHtml}
                  defaultFileName={`draft-${form.templateId}-${form.effectiveDate || 'draft'}.html`}
                  onClose={() => setShowSaveToCloud(false)}
                  onSaved={() => setShowSaveToCloud(false)}
                />
              )}

              {Object.keys(qaWarnings).length > 0 && (
                <div className="card border-l-4 border-warning bg-warning/5">
                  <h4 className="font-semibold mb-3 flex items-center gap-2 text-warning">
                    <AlertTriangle className="w-4 h-4" />
                    QA Warnings — Review Required
                  </h4>
                  <p className="text-xs text-text-muted mb-3">
                    The AI flagged potential issues in the clauses below. Review and fix before using this draft.
                  </p>
                  <div className="space-y-2">
                    {Object.entries(qaWarnings).map(([clauseKey, warnings]) => (
                      <div key={clauseKey} className="p-3 rounded-lg bg-warning/10 border border-warning/20">
                        <p className="text-xs font-semibold text-warning mb-1">
                          {CLAUSE_SPECS_LABELS[clauseKey] || clauseKey}
                        </p>
                        <ul className="space-y-0.5">
                          {warnings.map((w, i) => (
                            <li key={i} className="text-xs text-text-secondary flex items-start gap-1.5">
                              <span className="text-warning mt-0.5">•</span> {w}
                            </li>
                          ))}
                        </ul>
                      </div>
                    ))}
                  </div>
                </div>
              )}

              {draft.suggestions?.length > 0 && (
                <div className="card">
                  <h4 className="font-semibold mb-3 flex items-center gap-2">
                    <Lightbulb className="w-4 h-4 text-warning" />
                    AI Suggestions
                  </h4>
                  <div className="space-y-3">
                    {draft.suggestions.map((s, i) => (
                      <div key={i} className="p-3 rounded-lg bg-surface-el/50 border border-border">
                        <p className="text-xs text-text-muted font-medium">{s.clauseRef}</p>
                        <p className="text-sm mt-1">{s.suggestion}</p>
                        {s.reasoning && <p className="text-xs text-text-muted mt-2">{s.reasoning}</p>}
                      </div>
                    ))}
                  </div>
                </div>
              )}

              <div className="card overflow-hidden">
                <div
                  ref={previewRef}
                  onMouseUp={handlePreviewMouseUp}
                  className="bg-white/5 rounded-lg p-6 overflow-auto max-h-[600px] text-sm text-text-primary select-text [&_h1]:text-xl [&_h1]:font-bold [&_h1]:text-center [&_h1]:mb-4 [&_h2]:text-base [&_h2]:font-semibold [&_h2]:uppercase [&_h2]:mt-6 [&_h2]:mb-2 [&_h2]:border-b [&_h2]:border-border [&_h2]:pb-1 [&_p]:mb-2 [&_p]:leading-relaxed [&_.clause-sub]:pl-5 [&_.clause-sub]:mb-2 [&_ul]:list-disc [&_ul]:ml-6 [&_ul]:mb-3"
                  dangerouslySetInnerHTML={{ __html: extractBodyContent(draft.draftHtml) }}
                />
              </div>
            </>
          )}

          {loading && generatingStatus && (
            <div className="card border border-primary/30 px-5 py-4">
              <div className="flex items-center justify-between mb-2">
                <span className="text-sm font-medium text-text-primary flex items-center gap-2">
                  <Loader className="w-4 h-4 animate-spin text-primary" />
                  {generatingStatus.done
                    ? `✓ ${generatingStatus.label}`
                    : `Generating ${generatingStatus.label}…`}
                </span>
                <span className="text-xs text-text-muted tabular-nums">
                  {generatingStatus.index}/{generatingStatus.total}
                </span>
              </div>
              {generatingStatus.retrying && (
                <p className="text-xs text-warning mb-2 flex items-center gap-1">
                  <span>⚠ QA retry —</span>
                  <span className="truncate">{generatingStatus.retrying}</span>
                </p>
              )}
              <div className="w-full bg-surface-el rounded-full h-1.5">
                <div
                  className="bg-primary h-1.5 rounded-full transition-all duration-500"
                  style={{ width: `${(generatingStatus.index / generatingStatus.total) * 100}%` }}
                />
              </div>
            </div>
          )}

          {loading && !generatingStatus && (
            <div className="card text-center py-8">
              <Loader className="w-8 h-8 text-primary mx-auto mb-3 animate-spin" />
              <p className="text-text-muted text-sm">Preparing draft…</p>
            </div>
          )}

          {!draft && !loading && (
            <div className="card border-2 border-dashed border-border text-center py-16">
              <FileEdit className="w-12 h-12 text-text-muted mx-auto mb-4" />
              <p className="text-text-muted">Select a template and fill in the details, then click Generate Draft.</p>
              <p className="text-xs text-text-muted mt-2">The AI will fill placeholders and suggest a liability clause based on your corpus.</p>
            </div>
          )}
        </div>
      </div>

      {/* Mark animations for refining/accepted highlights */}
      <style>{`
        @keyframes ai-refining-pulse {
          0%, 100% { background: rgba(99,102,241,0.15); }
          50% { background: rgba(99,102,241,0.38); }
        }
        mark[data-ai="refining"] {
          background: rgba(99,102,241,0.2);
          border-radius: 3px;
          padding: 0 3px;
          animation: ai-refining-pulse 1.4s ease-in-out infinite;
          color: inherit;
        }
        mark[data-ai="accepted"] {
          background: rgba(34,197,94,0.28);
          border-radius: 3px;
          padding: 0 3px;
          color: inherit;
        }
        @keyframes indeterminate {
          0%   { transform: translateX(-100%); }
          100% { transform: translateX(250%); }
        }
      `}</style>

      {/* Floating improve button — appears at selection */}
      {selectionInfo && !refining && (
        <div
          ref={floatRef}
          className="fixed z-50 -translate-x-1/2"
          style={{ left: selectionInfo.x, top: selectionInfo.y }}
        >
          <button
            onClick={handleImproveSelection}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-full bg-primary text-white text-xs font-medium shadow-lg hover:bg-primary/90 transition-colors"
          >
            <Sparkles className="w-3.5 h-3.5" />
            Improve with AI
          </button>
        </div>
      )}

      {/* Floating progress bar — shown at the same position while the LLM is working */}
      {refiningAt && (
        <div
          className="fixed z-50 -translate-x-1/2"
          style={{ left: refiningAt.x, top: refiningAt.y }}
        >
          <div className="flex flex-col items-center gap-1.5 px-3 py-2 rounded-xl bg-surface border border-primary/30 shadow-xl">
            <span className="text-[10px] text-text-muted font-medium flex items-center gap-1">
              <Sparkles className="w-3 h-3 text-primary" />
              Improving…
            </span>
            <div className="w-28 h-1 bg-surface-el rounded-full overflow-hidden relative">
              <div
                className="absolute top-0 left-0 h-full w-2/5 bg-primary rounded-full"
                style={{ animation: 'indeterminate 1.2s ease-in-out infinite' }}
              />
            </div>
          </div>
        </div>
      )}

      {/* AI refinement panel — fixed right-side drawer, always fully visible */}
      {pendingRefinement && (
        <div className="fixed bottom-6 right-6 z-50 w-[400px] max-w-[calc(100vw-24px)] flex flex-col bg-surface border border-border rounded-xl shadow-2xl"
             style={{ maxHeight: 'calc(100vh - 48px)' }}>
          {/* Header */}
          <div className="flex items-center justify-between px-4 py-3 border-b border-border shrink-0">
            <p className="text-xs font-semibold text-text-primary flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-primary" />
              AI Suggestion — compare and decide
            </p>
            <button onClick={handleRejectRefinement} className="text-text-muted hover:text-text-primary">
              <X className="w-4 h-4" />
            </button>
          </div>

          {/* Scrollable content */}
          <div className="overflow-y-auto px-4 py-3 space-y-3 flex-1">
            <div>
              <p className="text-[10px] text-danger font-semibold uppercase tracking-wide mb-1.5">Original</p>
              <p className="text-xs p-2.5 rounded-lg bg-danger/10 border border-danger/20 text-text-secondary line-through leading-relaxed whitespace-pre-wrap">
                {pendingRefinement.originalText}
              </p>
            </div>
            <div>
              <p className="text-[10px] text-success font-semibold uppercase tracking-wide mb-1.5">Improved</p>
              <p className="text-xs p-2.5 rounded-lg bg-success/10 border border-success/20 leading-relaxed whitespace-pre-wrap">
                {pendingRefinement.improvedText}
              </p>
            </div>
            {pendingRefinement.reasoning && (
              <p className="text-[10px] text-text-muted italic leading-relaxed">{pendingRefinement.reasoning}</p>
            )}
          </div>

          {/* Always-visible action buttons */}
          <div className="flex gap-2 px-4 py-3 border-t border-border shrink-0">
            <button onClick={handleAcceptRefinement}
              className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg bg-success/20 hover:bg-success/30 text-success text-xs font-semibold transition-colors border border-success/30">
              <Check className="w-3.5 h-3.5" />
              Accept Change
            </button>
            <button onClick={handleRejectRefinement}
              className="flex-1 flex items-center justify-center gap-1.5 py-2 rounded-lg bg-surface-el hover:bg-danger/10 text-text-muted hover:text-danger text-xs font-semibold transition-colors border border-border">
              <X className="w-3.5 h-3.5" />
              Discard
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
