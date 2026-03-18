import { useState, useEffect, useRef, useCallback } from 'react';
import { FileEdit, Loader, Download, Lightbulb, Sparkles, CloudUpload, Check, X, FileText, Save, FolderOpen, Pencil } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';
import SaveToCloudModal from '../components/SaveToCloudModal';

function stripHtml(html) {
  const div = document.createElement('div');
  div.innerHTML = html || '';
  return div.innerText || div.textContent || '';
}

const JURISDICTIONS = ['Maharashtra', 'Delhi', 'Karnataka', 'Tamil Nadu', 'Gujarat', 'Rajasthan', 'Supreme Court', 'India'];

export default function DraftPage() {
  const navigate = useNavigate();
  const [templates, setTemplates] = useState([]);
  const [docs, setDocs] = useState([]);
  const [refMode, setRefMode] = useState('manual'); // 'manual' | 'system'
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState(null);
  const [error, setError] = useState('');
  const [generatingStatus, setGeneratingStatus] = useState(null); // {label, index, total}
  const [refining, setRefining] = useState(false);
  const [showSaveToCloud, setShowSaveToCloud] = useState(false);
  const [selectionInfo, setSelectionInfo] = useState(null);
  const [pendingRefinement, setPendingRefinement] = useState(null);
  const [saving, setSaving] = useState(false);
  const [saveSuccess, setSaveSuccess] = useState(false);
  const previewRef = useRef(null);
  const floatRef = useRef(null);

  const [form, setForm] = useState({
    templateId: '',
    partyA: '',
    partyB: '',
    partyAAddress: '',
    partyBAddress: '',
    partyARep: '',
    partyBRep: '',
    effectiveDate: new Date().toISOString().slice(0, 10),
    jurisdiction: 'Maharashtra',
    agreementRef: '',
    termYears: '3',
    noticeDays: '30',
    survivalYears: '5',
    practiceArea: 'CORPORATE',
    counterpartyType: 'vendor',
  });

  useEffect(() => {
    api.get('/ai/templates').then(r => setTemplates(r.data || [])).catch(() => setTemplates([]));
    api.get('/documents?size=100&sort=uploadDate,desc').then(r => setDocs(r.data.content || [])).catch(() => setDocs([]));
  }, []);

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

  const handleGenerate = async () => {
    if (!form.templateId) return;
    setLoading(true);
    setError('');
    setDraft(null);
    setGeneratingStatus(null);
    setSelectionInfo(null);
    setPendingRefinement(null);
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
          if (event.type === 'start') {
            setGeneratingStatus({ label: 'Preparing…', index: 0, total: event.totalClauses });
            setDraft({ draftHtml: event.partialHtml, suggestions: [] });
          } else if (event.type === 'clause_start') {
            setGeneratingStatus({ label: event.label, index: event.index, total: event.totalClauses });
          } else if (event.type === 'clause_done') {
            setGeneratingStatus({ label: event.label, index: event.index, total: event.totalClauses, done: true });
            setDraft(prev => ({ ...(prev || {}), draftHtml: event.partialHtml }));
          } else if (event.type === 'complete') {
            setDraft({ draftHtml: event.draftHtml, suggestions: event.suggestions });
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
    setRefining(true);
    setError('');
    try {
      const docContext = stripHtml(draft.draftHtml).slice(0, 4000);
      const res = await api.post('/ai/refine-clause', {
        selectedText: text,
        documentContext: docContext,
        instruction: 'Improve for clarity, legal precision, and Indian law compliance.',
      });
      const improved = res.data?.improvedText || res.data?.improved_text;
      if (improved) {
        setPendingRefinement({ originalText: text, improvedText: improved, reasoning: res.data?.reasoning, x, y });
      }
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Refine failed');
    } finally {
      setRefining(false);
    }
  };

  const handleAcceptRefinement = () => {
    if (!pendingRefinement) return;
    setDraft(prev => ({
      ...prev,
      draftHtml: prev.draftHtml.replace(pendingRefinement.originalText, pendingRefinement.improvedText),
      suggestions: [
        ...(prev.suggestions || []),
        { clauseRef: 'Refined selection', suggestion: pendingRefinement.improvedText, reasoning: pendingRefinement.reasoning },
      ],
    }));
    setPendingRefinement(null);
  };

  const handleRejectRefinement = () => setPendingRefinement(null);

  const handleSaveToSystem = async () => {
    if (!draft?.draftHtml) return;
    setSaving(true);
    setSaveSuccess(false);
    try {
      const fileName = `draft-${form.templateId}-${form.effectiveDate || 'draft'}.html`;
      const blob = new Blob([draft.draftHtml], { type: 'text/html' });
      const file = new File([blob], fileName, { type: 'text/html' });
      const fd = new FormData();
      fd.append('file', file);
      fd.append('jurisdiction', form.jurisdiction || '');
      fd.append('year', form.effectiveDate ? form.effectiveDate.slice(0, 4) : new Date().getFullYear());
      fd.append('documentType', 'DRAFT');
      fd.append('practiceArea', form.practiceArea || 'CORPORATE');
      if (form.partyA) fd.append('clientName', form.partyA);
      await api.post('/documents/upload', fd, { headers: { 'Content-Type': undefined } });
      setSaveSuccess(true);
      setTimeout(() => setSaveSuccess(false), 4000);
    } catch (e) {
      setError(e.response?.data?.message || e.message || 'Save failed');
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
                <label className="text-xs text-text-muted mb-1 block">Template</label>
                <select value={form.templateId} onChange={e => setForm({ ...form, templateId: e.target.value })} className="input-field w-full text-sm">
                  <option value="">Select template...</option>
                  {templates.map(t => <option key={t.id} value={t.id}>{t.name}</option>)}
                </select>
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
              <button onClick={handleGenerate} disabled={loading || !form.templateId} className="btn-primary w-full flex items-center justify-center gap-2 py-3">
                {loading ? <Loader className="w-5 h-5 animate-spin" /> : <FileEdit className="w-5 h-5" />}
                {loading ? 'Generating...' : 'Generate Draft'}
              </button>
            </div>
          </div>
        </div>

        {/* Right preview panel */}
        <div className="lg:col-span-2 space-y-6">
          {error && (
            <div className="card border-l-4 border-danger bg-danger/5">
              <p className="text-danger text-sm">{error}</p>
            </div>
          )}

          {draft && (
            <>
              <div className="card flex items-center justify-between flex-wrap gap-3">
                <span className="text-text-secondary text-sm">
                  {refining
                    ? <span className="flex items-center gap-2"><Loader className="w-4 h-4 animate-spin" /> Improving selection...</span>
                    : 'Select text in the preview to improve it inline'}
                </span>
                <div className="flex gap-2 flex-wrap">
                  <button
                    onClick={handleSaveToSystem}
                    disabled={saving}
                    className="btn-primary flex items-center gap-2 text-sm"
                  >
                    {saving ? <Loader className="w-4 h-4 animate-spin" /> : saveSuccess ? <Check className="w-4 h-4" /> : <Save className="w-4 h-4" />}
                    {saving ? 'Saving...' : saveSuccess ? 'Saved!' : 'Save to System'}
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
                  className="bg-white/5 rounded-lg p-6 overflow-auto max-h-[600px] text-sm text-text-primary select-text [&_h1]:text-xl [&_h1]:font-bold [&_h2]:text-lg [&_h2]:font-semibold [&_h3]:text-base [&_h3]:font-medium [&_p]:mb-3 [&_ul]:list-disc [&_ul]:ml-6 [&_ul]:mb-3"
                  dangerouslySetInnerHTML={{ __html: draft.draftHtml }}
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
