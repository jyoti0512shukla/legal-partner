import { useState, useEffect } from 'react';
import { BookOpen, Plus, Star, Trash2, X, ChevronDown, ChevronUp } from 'lucide-react';
import api from '../api/client';

const CLAUSE_TYPES = [
  'DEFINITIONS', 'SERVICES', 'PAYMENT', 'CONFIDENTIALITY', 'IP_RIGHTS',
  'LIABILITY', 'TERMINATION', 'FORCE_MAJEURE', 'REPRESENTATIONS_WARRANTIES',
  'DATA_PROTECTION', 'GOVERNING_LAW', 'GENERAL_PROVISIONS',
];
const CLAUSE_LABELS = {
  DEFINITIONS: 'Definitions', SERVICES: 'Services', PAYMENT: 'Fees and Payment',
  CONFIDENTIALITY: 'Confidentiality', IP_RIGHTS: 'IP Rights', LIABILITY: 'Liability & Indemnity',
  TERMINATION: 'Termination', FORCE_MAJEURE: 'Force Majeure',
  REPRESENTATIONS_WARRANTIES: 'Representations & Warranties',
  DATA_PROTECTION: 'Data Protection', GOVERNING_LAW: 'Governing Law',
  GENERAL_PROVISIONS: 'General Provisions',
};
const INDUSTRIES = ['', 'GENERAL', 'IT_SERVICES', 'FINTECH', 'PHARMA', 'MANUFACTURING'];
const CONTRACT_TYPES = ['', 'NDA', 'MSA', 'SOW', 'EMPLOYMENT', 'VENDOR'];
const PRACTICE_AREAS = ['', 'CORPORATE', 'IP', 'TAX', 'REAL_ESTATE', 'LABOR', 'BANKING', 'REGULATORY', 'OTHER'];

function AddClauseModal({ onClose, onSaved }) {
  const [form, setForm] = useState({
    clauseType: 'LIABILITY', title: '', content: '',
    contractType: '', practiceArea: '', industry: '', counterpartyType: '', jurisdiction: '', golden: false,
  });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.title.trim() || !form.content.trim()) { setError('Title and content are required'); return; }
    setSaving(true); setError('');
    try {
      await api.post('/clause-library', form);
      onSaved();
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to save clause');
    } finally { setSaving(false); }
  };

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div className="glass rounded-xl w-full max-w-2xl p-6 max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-lg font-semibold">Add Clause to Library</h2>
          <button onClick={onClose} className="text-text-muted hover:text-text-primary"><X className="w-5 h-5" /></button>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-xs text-text-muted mb-1 block">Clause Type *</label>
              <select value={form.clauseType} onChange={e => setForm({ ...form, clauseType: e.target.value })} className="input-field w-full text-sm">
                {CLAUSE_TYPES.map(t => <option key={t} value={t}>{CLAUSE_LABELS[t]}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Contract Type</label>
              <select value={form.contractType} onChange={e => setForm({ ...form, contractType: e.target.value })} className="input-field w-full text-sm">
                {CONTRACT_TYPES.map(t => <option key={t} value={t}>{t || 'Any'}</option>)}
              </select>
            </div>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Title *</label>
            <input required value={form.title} onChange={e => setForm({ ...form, title: e.target.value })}
              placeholder="e.g. Mutual Liability Cap — IT Services (12-month fees)" className="input-field w-full text-sm" />
          </div>
          <div className="grid grid-cols-3 gap-3">
            <div>
              <label className="text-xs text-text-muted mb-1 block">Industry</label>
              <select value={form.industry} onChange={e => setForm({ ...form, industry: e.target.value })} className="input-field w-full text-sm">
                {INDUSTRIES.map(i => <option key={i} value={i}>{i || 'Any'}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Practice Area</label>
              <select value={form.practiceArea} onChange={e => setForm({ ...form, practiceArea: e.target.value })} className="input-field w-full text-sm">
                {PRACTICE_AREAS.map(p => <option key={p} value={p}>{p || 'Any'}</option>)}
              </select>
            </div>
            <div>
              <label className="text-xs text-text-muted mb-1 block">Jurisdiction</label>
              <input value={form.jurisdiction} onChange={e => setForm({ ...form, jurisdiction: e.target.value })}
                placeholder="e.g. California" className="input-field w-full text-sm" />
            </div>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Clause Text *</label>
            <textarea required value={form.content} onChange={e => setForm({ ...form, content: e.target.value })}
              rows={8} placeholder="Paste or type the clause text here. Use numbered sub-clauses (1. ... 2. ...)" className="input-field w-full text-sm font-mono resize-none" />
          </div>
          <label className="flex items-center gap-2 cursor-pointer select-none">
            <input type="checkbox" checked={form.golden} onChange={e => setForm({ ...form, golden: e.target.checked })}
              className="w-4 h-4 accent-primary" />
            <span className="text-sm text-text-secondary">
              <span className="text-warning font-medium">Mark as Golden Clause</span> — will always be injected first into AI drafting context
            </span>
          </label>
          {error && <p className="text-danger text-sm">{error}</p>}
          <div className="flex justify-end gap-3 pt-1">
            <button type="button" onClick={onClose} className="btn-secondary text-sm">Cancel</button>
            <button type="submit" disabled={saving} className="btn-primary text-sm flex items-center gap-2">
              {saving && <div className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />}
              Save to Library
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function ClauseCard({ entry, onToggleGolden, onDelete }) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div className={`card border ${entry.golden ? 'border-warning/40 bg-warning/5' : 'border-border'}`}>
      <div className="flex items-start gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 flex-wrap mb-1">
            <span className="text-sm font-semibold text-text-primary truncate">{entry.title}</span>
            {entry.golden && (
              <span className="inline-flex items-center gap-1 text-[10px] font-semibold px-1.5 py-0.5 rounded bg-warning/20 text-warning border border-warning/30">
                <Star className="w-2.5 h-2.5" /> GOLDEN
              </span>
            )}
            <span className="text-[10px] px-1.5 py-0.5 rounded bg-primary/10 text-primary border border-primary/20 font-medium">
              {CLAUSE_LABELS[entry.clauseType] || entry.clauseType}
            </span>
          </div>
          <div className="flex flex-wrap gap-2 text-[10px] text-text-muted">
            {entry.contractType && <span className="px-1.5 py-0.5 bg-surface-el rounded">{entry.contractType}</span>}
            {entry.industry && <span className="px-1.5 py-0.5 bg-surface-el rounded">{entry.industry}</span>}
            {entry.practiceArea && <span className="px-1.5 py-0.5 bg-surface-el rounded">{entry.practiceArea}</span>}
            {entry.jurisdiction && <span className="px-1.5 py-0.5 bg-surface-el rounded">{entry.jurisdiction}</span>}
            {entry.usageCount > 0 && <span className="px-1.5 py-0.5 bg-success/10 text-success rounded">Used {entry.usageCount}×</span>}
          </div>
        </div>
        <div className="flex items-center gap-1 shrink-0">
          <button onClick={() => setExpanded(v => !v)}
            className="p-1.5 text-text-muted hover:text-text-primary rounded transition-colors" title="Preview clause">
            {expanded ? <ChevronUp className="w-4 h-4" /> : <ChevronDown className="w-4 h-4" />}
          </button>
          <button onClick={() => onToggleGolden(entry.id)}
            className={`p-1.5 rounded transition-colors ${entry.golden ? 'text-warning hover:text-text-muted' : 'text-text-muted hover:text-warning'}`}
            title={entry.golden ? 'Remove golden status' : 'Mark as golden'}>
            <Star className="w-4 h-4" />
          </button>
          <button onClick={() => onDelete(entry.id)}
            className="p-1.5 text-text-muted hover:text-danger rounded transition-colors" title="Delete">
            <Trash2 className="w-4 h-4" />
          </button>
        </div>
      </div>
      {expanded && (
        <div className="mt-3 pt-3 border-t border-border">
          <pre className="text-xs text-text-secondary font-mono whitespace-pre-wrap leading-relaxed bg-surface-el/50 rounded-lg p-3 max-h-60 overflow-y-auto">
            {entry.content}
          </pre>
        </div>
      )}
    </div>
  );
}

export default function ClauseLibraryPage({ embedded = false }) {
  const [entries, setEntries] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showAdd, setShowAdd] = useState(false);
  const [filterType, setFilterType] = useState('');

  const load = () => {
    setLoading(true);
    api.get('/clause-library').then(r => setEntries(r.data || [])).catch(() => {}).finally(() => setLoading(false));
  };
  useEffect(() => { load(); }, []);

  const handleToggleGolden = async (id) => {
    await api.patch(`/clause-library/${id}/golden`).catch(() => {});
    load();
  };

  const handleDelete = async (id) => {
    if (!window.confirm('Delete this clause from the library?')) return;
    await api.delete(`/clause-library/${id}`).catch(() => {});
    load();
  };

  const displayed = filterType ? entries.filter(e => e.clauseType === filterType) : entries;

  // Group by clause type for display
  const grouped = displayed.reduce((acc, e) => {
    (acc[e.clauseType] = acc[e.clauseType] || []).push(e);
    return acc;
  }, {});

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div>
          {!embedded && <h1 className="text-2xl font-bold flex items-center gap-2">
            <BookOpen className="w-7 h-7" /> Clause Library
          </h1>}
          <p className="text-sm text-text-muted mt-1">
            Curated clauses injected into AI drafting context as precedent.{' '}
            <span className="text-warning">Golden clauses</span> are always used first.
          </p>
        </div>
        <button onClick={() => setShowAdd(true)} className="btn-primary flex items-center gap-2 text-sm">
          <Plus className="w-4 h-4" /> Add Clause
        </button>
      </div>

      {showAdd && (
        <AddClauseModal onClose={() => setShowAdd(false)} onSaved={() => { load(); setShowAdd(false); }} />
      )}

      <div className="flex items-center gap-3 mb-5">
        <label className="text-xs text-text-muted">Filter by type:</label>
        <select value={filterType} onChange={e => setFilterType(e.target.value)} className="input-field text-sm">
          <option value="">All types</option>
          {CLAUSE_TYPES.map(t => <option key={t} value={t}>{CLAUSE_LABELS[t]}</option>)}
        </select>
        <span className="text-xs text-text-muted ml-auto">{displayed.length} clause{displayed.length !== 1 ? 's' : ''}</span>
      </div>

      {loading && (
        <div className="card text-center py-12">
          <div className="w-6 h-6 border-2 border-primary border-t-transparent rounded-full animate-spin mx-auto" />
        </div>
      )}

      {!loading && entries.length === 0 && (
        <div className="card text-center py-16 border-2 border-dashed border-border">
          <BookOpen className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="font-semibold mb-2">No clauses in the library yet</p>
          <p className="text-sm text-text-muted mb-5">
            Add curated clauses that will be automatically injected into AI drafting as firm precedent.
            Golden clauses are always used first — great for preferred positions on key terms.
          </p>
          <button onClick={() => setShowAdd(true)} className="btn-primary text-sm inline-flex items-center gap-2">
            <Plus className="w-4 h-4" /> Add your first clause
          </button>
        </div>
      )}

      {!loading && Object.keys(grouped).length > 0 && (
        <div className="space-y-6">
          {CLAUSE_TYPES.filter(t => grouped[t]).map(type => (
            <div key={type}>
              <h3 className="text-xs font-semibold text-text-muted uppercase tracking-wide mb-2">
                {CLAUSE_LABELS[type]} ({grouped[type].length})
              </h3>
              <div className="space-y-3">
                {grouped[type].map(entry => (
                  <ClauseCard
                    key={entry.id}
                    entry={entry}
                    onToggleGolden={handleToggleGolden}
                    onDelete={handleDelete}
                  />
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
