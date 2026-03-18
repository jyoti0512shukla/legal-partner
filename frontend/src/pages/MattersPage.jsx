import { useState, useEffect } from 'react';
import { Briefcase, Plus, X, FileText, ChevronRight, Brain } from 'lucide-react';
import { useNavigate } from 'react-router-dom';
import api from '../api/client';

const STATUSES = { ACTIVE: 'success', CLOSED: 'text-muted', ARCHIVED: 'text-muted' };
const PRACTICE_AREAS = ['CORPORATE', 'LITIGATION', 'IP', 'TAX', 'REAL_ESTATE', 'LABOR', 'BANKING', 'REGULATORY', 'OTHER'];

function StatusBadge({ status }) {
  const color = status === 'ACTIVE' ? 'text-success bg-success/10' : 'text-text-muted bg-surface-el';
  return <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${color}`}>{status}</span>;
}

function CreateMatterModal({ onClose, onCreated }) {
  const [form, setForm] = useState({ name: '', matterRef: '', clientName: '', practiceArea: 'CORPORATE', description: '' });
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true); setError('');
    try {
      await api.post('/matters', form);
      onCreated();
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to create matter');
    } finally { setSaving(false); }
  };

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div className="glass rounded-xl w-full max-w-md p-6">
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-lg font-semibold">New Matter</h2>
          <button onClick={onClose} className="text-text-muted hover:text-text-primary"><X className="w-5 h-5" /></button>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="text-xs text-text-muted mb-1 block">Matter Name *</label>
            <input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. Tata Consultancy — MSA 2025" className="input-field w-full text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Matter Ref *</label>
            <input required value={form.matterRef} onChange={e => setForm({ ...form, matterRef: e.target.value })}
              placeholder="e.g. MAT-2025-001" className="input-field w-full text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Client Name *</label>
            <input required value={form.clientName} onChange={e => setForm({ ...form, clientName: e.target.value })}
              placeholder="e.g. Tata Consultancy Services Ltd." className="input-field w-full text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Practice Area</label>
            <select value={form.practiceArea} onChange={e => setForm({ ...form, practiceArea: e.target.value })} className="input-field w-full text-sm">
              {PRACTICE_AREAS.map(p => <option key={p} value={p}>{p.replace('_', ' ')}</option>)}
            </select>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Description</label>
            <textarea value={form.description} onChange={e => setForm({ ...form, description: e.target.value })}
              rows={2} placeholder="Brief description of the matter..." className="input-field w-full text-sm resize-none" />
          </div>
          {error && <p className="text-danger text-sm">{error}</p>}
          <div className="flex justify-end gap-3 pt-1">
            <button type="button" onClick={onClose} className="btn-secondary text-sm">Cancel</button>
            <button type="submit" disabled={saving} className="btn-primary text-sm flex items-center gap-2">
              {saving && <div className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />}
              Create Matter
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function MatterDocuments({ matterId }) {
  const [docs, setDocs] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get(`/matters/${matterId}/documents`)
      .then(r => setDocs(r.data.content || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [matterId]);

  if (loading) return <p className="text-xs text-text-muted py-2">Loading documents...</p>;
  if (docs.length === 0) return <p className="text-xs text-text-muted py-2">No documents linked to this matter yet.</p>;

  return (
    <ul className="divide-y divide-border/50 mt-2">
      {docs.map(d => (
        <li key={d.id} className="flex items-center gap-2 py-2">
          <FileText className="w-3.5 h-3.5 text-text-muted shrink-0" />
          <span className="text-sm text-text-secondary">{d.fileName}</span>
          <span className="text-xs text-text-muted ml-auto">{d.documentType}</span>
        </li>
      ))}
    </ul>
  );
}

export default function MattersPage() {
  const [matters, setMatters] = useState([]);
  const [showCreate, setShowCreate] = useState(false);
  const [expanded, setExpanded] = useState(null);
  const navigate = useNavigate();

  const load = () => api.get('/matters').then(r => setMatters(r.data)).catch(() => {});
  useEffect(() => { load(); }, []);

  const handleStatusChange = async (id, status) => {
    await api.patch(`/matters/${id}/status?status=${status}`).catch(() => {});
    load();
  };

  const toggleExpand = (id) => setExpanded(prev => prev === id ? null : id);

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-2xl font-bold">Matters</h1>
        <button onClick={() => setShowCreate(true)} className="btn-primary flex items-center gap-2 text-sm">
          <Plus className="w-4 h-4" /> New Matter
        </button>
      </div>

      {showCreate && (
        <CreateMatterModal
          onClose={() => setShowCreate(false)}
          onCreated={() => { load(); setShowCreate(false); }}
        />
      )}

      {matters.length === 0 ? (
        <div className="card text-center py-16">
          <Briefcase className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="text-text-muted mb-2">No matters yet</p>
          <p className="text-xs text-text-muted mb-5">Organise your contracts and analysis by client matter</p>
          <button onClick={() => setShowCreate(true)} className="btn-primary text-sm inline-flex items-center gap-2">
            <Plus className="w-4 h-4" /> Create your first matter
          </button>
        </div>
      ) : (
        <div className="space-y-3">
          {matters.map(m => (
            <div key={m.id} className="card">
              <div
                className="flex items-center gap-4 cursor-pointer"
                onClick={() => toggleExpand(m.id)}
              >
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-0.5">
                    <span className="font-semibold text-text-primary truncate">{m.name}</span>
                    <StatusBadge status={m.status} />
                  </div>
                  <div className="flex items-center gap-3 text-xs text-text-muted">
                    <span>{m.matterRef}</span>
                    <span>·</span>
                    <span>{m.clientName}</span>
                    {m.practiceArea && <><span>·</span><span>{m.practiceArea.replace('_', ' ')}</span></>}
                    <span>·</span>
                    <span>{m.documentCount} doc{m.documentCount !== 1 ? 's' : ''}</span>
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <button
                    onClick={e => { e.stopPropagation(); navigate(`/intelligence?matterId=${m.id}`); }}
                    className="flex items-center gap-1.5 text-xs text-primary hover:underline px-2 py-1"
                    title="Analyse this matter in Intelligence"
                  >
                    <Brain className="w-3.5 h-3.5" /> Analyse
                  </button>
                  <select
                    value={m.status}
                    onClick={e => e.stopPropagation()}
                    onChange={e => handleStatusChange(m.id, e.target.value)}
                    className="text-xs bg-surface-el border border-border rounded px-2 py-1"
                  >
                    <option value="ACTIVE">Active</option>
                    <option value="CLOSED">Closed</option>
                    <option value="ARCHIVED">Archived</option>
                  </select>
                  <ChevronRight className={`w-4 h-4 text-text-muted transition-transform ${expanded === m.id ? 'rotate-90' : ''}`} />
                </div>
              </div>

              {expanded === m.id && (
                <div className="mt-4 pt-4 border-t border-border">
                  {m.description && <p className="text-sm text-text-secondary mb-3">{m.description}</p>}
                  <p className="text-xs font-medium text-text-muted mb-1">Documents</p>
                  <MatterDocuments matterId={m.id} />
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
