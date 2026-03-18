import { useState, useEffect, useRef, useCallback } from 'react';
import { FileEdit, Loader, Download, Lightbulb, Sparkles, CloudUpload, Check, X, FileText } from 'lucide-react';
import api from '../api/client';
import SaveToCloudModal from '../components/SaveToCloudModal';

function stripHtml(html) {
  const div = document.createElement('div');
  div.innerHTML = html || '';
  return div.innerText || div.textContent || '';
}

const JURISDICTIONS = ['Maharashtra', 'Delhi', 'Karnataka', 'Tamil Nadu', 'Gujarat', 'Rajasthan', 'Supreme Court', 'India'];

export default function DraftPage() {
  const [templates, setTemplates] = useState([]);
  const [loading, setLoading] = useState(false);
  const [draft, setDraft] = useState(null);
  const [error, setError] = useState('');
  const [refining, setRefining] = useState(false);
  const [showSaveToCloud, setShowSaveToCloud] = useState(false);
  const [selectionInfo, setSelectionInfo] = useState(null);   // { text, x, y }
  const [pendingRefinement, setPendingRefinement] = useState(null); // { originalText, improvedText, reasoning, x, y }
  const previewRef = useRef(null);

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
      if (!previewRef.current?.contains(e.target)) {
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
    setSelectionInfo(null);
    setPendingRefinement(null);
    try {
      const res = await api.post('/ai/draft', form);
      setDraft(res.data);
    } catch (e) {
      setError(e.response?.data?.message || e.response?.data?.error || e.message || 'Draft generation failed');
    } finally {
      setLoading(false);
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
                <label className="text-xs text-text-muted mb-1 block">Agreement Ref (optional)</label>
                <input value={form.agreementRef} onChange={e => setForm({ ...form, agreementRef: e.target.value })} placeholder="e.g. NDA-2025-001" className="input-field w-full text-sm" />
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

          {loading && (
            <div className="card border-2 border-dashed border-primary/30 text-center py-16">
              <Loader className="w-12 h-12 text-primary mx-auto mb-4 animate-spin" />
              <p className="text-text-primary font-medium">Generating your draft...</p>
              <p className="text-xs text-text-muted mt-2">The AI is drafting your contract. This may take up to a minute.</p>
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

      {/* Floating comparison panel — appears after improvement */}
      {pendingRefinement && (
        <div
          className="fixed z-50 -translate-x-1/2 w-[420px] max-w-[90vw]"
          style={{ left: Math.min(pendingRefinement.x, window.innerWidth - 230), top: pendingRefinement.y }}
        >
          <div className="bg-surface border border-border rounded-xl shadow-2xl p-4">
            <p className="text-xs font-semibold text-text-muted mb-3 flex items-center gap-1.5">
              <Sparkles className="w-3.5 h-3.5 text-primary" />
              AI Suggestion — compare and decide
            </p>

            <div className="space-y-2 mb-4">
              <div>
                <p className="text-[10px] text-danger font-medium uppercase tracking-wide mb-1">Original</p>
                <p className="text-xs p-2.5 rounded-lg bg-danger/10 border border-danger/20 text-text-secondary line-through leading-relaxed">
                  {pendingRefinement.originalText}
                </p>
              </div>
              <div>
                <p className="text-[10px] text-success font-medium uppercase tracking-wide mb-1">Improved</p>
                <p className="text-xs p-2.5 rounded-lg bg-success/10 border border-success/20 leading-relaxed">
                  {pendingRefinement.improvedText}
                </p>
              </div>
              {pendingRefinement.reasoning && (
                <p className="text-[10px] text-text-muted italic px-1">{pendingRefinement.reasoning}</p>
              )}
            </div>

            <div className="flex gap-2">
              <button onClick={handleAcceptRefinement} className="flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-lg bg-success/20 hover:bg-success/30 text-success text-xs font-medium transition-colors border border-success/30">
                <Check className="w-3.5 h-3.5" />
                Accept
              </button>
              <button onClick={handleRejectRefinement} className="flex-1 flex items-center justify-center gap-1.5 py-1.5 rounded-lg bg-danger/10 hover:bg-danger/20 text-danger text-xs font-medium transition-colors border border-danger/20">
                <X className="w-3.5 h-3.5" />
                Reject
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
