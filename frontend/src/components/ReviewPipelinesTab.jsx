import { useState, useEffect } from 'react';
import { Loader2, Plus, Trash2, ArrowDown, X } from 'lucide-react';
import api from '../api/client';

const AVAILABLE_ACTIONS = [
  { id: 'APPROVE', label: 'Approve', desc: 'Advance to next stage', icon: '✓' },
  { id: 'RETURN', label: 'Return', desc: 'Send back to previous stage', icon: '↩' },
  { id: 'FLAG', label: 'Flag', desc: 'Flag for attention', icon: '⚑' },
  { id: 'SEND', label: 'Send', desc: 'Final send (e.g. to client)', icon: '→' },
  { id: 'ADD_NOTE', label: 'Add Note', desc: 'Comment without status change', icon: '✎' },
];
const ROLES = ['', 'ADMIN', 'PARTNER', 'ASSOCIATE', 'PARALEGAL'];

export default function ReviewPipelinesTab() {
  const [pipelines, setPipelines] = useState([]);
  const [loading, setLoading] = useState(true);
  const [editing, setEditing] = useState(null);
  const [form, setForm] = useState(null);
  const [saving, setSaving] = useState(false);

  const fetchPipelines = () => {
    api.get('/review-pipelines').then(r => setPipelines(r.data || [])).catch(() => {}).finally(() => setLoading(false));
  };
  useEffect(fetchPipelines, []);

  const startCreate = () => {
    setForm({ name: '', description: '', isDefault: false, stages: [
      { stageOrder: 1, name: 'Review', requiredRole: '', actions: 'APPROVE,RETURN', autoNotify: true },
    ]});
    setEditing('new');
  };

  const startEdit = (p) => {
    setForm({ name: p.name, description: p.description, isDefault: p.isDefault, stages: p.stages || [] });
    setEditing(p.id);
  };

  const addStage = () => {
    setForm(f => ({ ...f, stages: [...f.stages, {
      stageOrder: f.stages.length + 1, name: '', requiredRole: '', actions: 'APPROVE,RETURN', autoNotify: true
    }]}));
  };

  const updateStage = (idx, field, value) => {
    setForm(f => ({
      ...f, stages: f.stages.map((s, i) => i === idx ? { ...s, [field]: value } : s)
    }));
  };

  const removeStage = (idx) => {
    setForm(f => ({
      ...f, stages: f.stages.filter((_, i) => i !== idx).map((s, i) => ({ ...s, stageOrder: i + 1 }))
    }));
  };

  const handleSave = async () => {
    if (!form.name.trim() || form.stages.length === 0) return;
    setSaving(true);
    try {
      if (editing === 'new') {
        await api.post('/review-pipelines', form);
      } else {
        await api.put(`/review-pipelines/${editing}`, form);
      }
      setEditing(null); setForm(null); fetchPipelines();
    } catch (e) { alert(e.response?.data?.message || 'Failed to save'); }
    finally { setSaving(false); }
  };

  const handleDelete = async (id, name) => {
    if (!confirm(`Delete pipeline "${name}"?`)) return;
    try { await api.delete(`/review-pipelines/${id}`); fetchPipelines(); }
    catch (e) { alert(e.response?.data?.message || 'Failed'); }
  };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-8"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  if (form) {
    return (
      <div className="space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-lg font-semibold font-display text-text-primary">
            {editing === 'new' ? 'New Pipeline' : 'Edit Pipeline'}
          </h2>
          <button onClick={() => { setEditing(null); setForm(null); }} className="text-text-muted hover:text-text-primary">
            <X className="w-5 h-5" />
          </button>
        </div>

        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="text-xs text-text-muted mb-1 block">Pipeline Name *</label>
            <input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. M&A Due Diligence Review" className="input-field w-full text-sm" />
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Description</label>
            <input value={form.description || ''} onChange={e => setForm({ ...form, description: e.target.value })}
              placeholder="Optional description" className="input-field w-full text-sm" />
          </div>
        </div>

        {/* Stages */}
        <div>
          <p className="text-xs font-medium text-text-muted mb-2">STAGES ({form.stages.length})</p>
          <div className="space-y-2">
            {form.stages.map((stage, idx) => (
              <div key={idx} className="card p-3 !bg-surface-el">
                <div className="flex items-start gap-3">
                  <div className="w-6 h-6 rounded-full bg-primary/10 text-primary flex items-center justify-center text-xs font-bold shrink-0 mt-1">
                    {idx + 1}
                  </div>
                  <div className="flex-1 space-y-2">
                    <div className="grid grid-cols-2 gap-2">
                      <div>
                        <label className="text-[10px] text-text-muted">Stage Name *</label>
                        <input value={stage.name} onChange={e => updateStage(idx, 'name', e.target.value)}
                          placeholder="e.g. Partner Review" className="input-field w-full text-xs py-1.5" />
                      </div>
                      <div>
                        <label className="text-[10px] text-text-muted">Required Role</label>
                        <select value={stage.requiredRole || ''} onChange={e => updateStage(idx, 'requiredRole', e.target.value)}
                          className="input-field w-full text-xs py-1.5">
                          <option value="">Anyone</option>
                          {ROLES.filter(Boolean).map(r => <option key={r} value={r}>{r}</option>)}
                        </select>
                      </div>
                    </div>
                    <div>
                      <label className="text-[10px] text-text-muted mb-1 block">Available Actions</label>
                      <div className="flex flex-wrap gap-1.5">
                        {AVAILABLE_ACTIONS.map(action => {
                          const selected = (stage.actions || '').split(',').map(a => a.trim()).includes(action.id);
                          return (
                            <button key={action.id} type="button"
                              onClick={() => {
                                const current = (stage.actions || '').split(',').map(a => a.trim()).filter(Boolean);
                                const updated = selected
                                  ? current.filter(a => a !== action.id)
                                  : [...current, action.id];
                                updateStage(idx, 'actions', updated.join(','));
                              }}
                              className={`flex items-center gap-1 text-[10px] px-2 py-1 rounded-lg border transition-all ${
                                selected
                                  ? 'bg-primary/10 border-primary/30 text-primary'
                                  : 'bg-surface border-border/50 text-text-muted hover:border-primary/20'
                              }`}
                              title={action.desc}
                            >
                              <span>{action.icon}</span>
                              <span>{action.label}</span>
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  </div>
                  <button onClick={() => removeStage(idx)} className="text-text-muted hover:text-danger shrink-0 mt-1">
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                </div>
                {idx < form.stages.length - 1 && (
                  <div className="flex justify-center py-1 ml-6"><ArrowDown className="w-3 h-3 text-text-muted" /></div>
                )}
              </div>
            ))}
          </div>
          <button onClick={addStage} className="btn-secondary text-xs mt-2 flex items-center gap-1">
            <Plus className="w-3.5 h-3.5" /> Add Stage
          </button>
        </div>

        <div className="flex gap-2 pt-2">
          <button onClick={handleSave} disabled={saving || !form.name.trim() || form.stages.length === 0}
            className="btn-primary text-sm">
            {saving ? 'Saving...' : editing === 'new' ? 'Create Pipeline' : 'Save Changes'}
          </button>
          <button onClick={() => { setEditing(null); setForm(null); }} className="btn-secondary text-sm">Cancel</button>
        </div>
      </div>
    );
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <div>
          <h2 className="text-lg font-semibold font-display text-text-primary">Review Pipelines</h2>
          <p className="text-sm text-text-muted">Define how documents flow through review stages in your firm.</p>
        </div>
        <button onClick={startCreate} className="btn-primary text-sm flex items-center gap-1.5">
          <Plus className="w-4 h-4" /> New Pipeline
        </button>
      </div>

      {pipelines.length === 0 ? (
        <div className="text-center py-8">
          <p className="text-text-muted text-sm mb-3">No review pipelines created yet.</p>
          <button onClick={startCreate} className="btn-primary text-sm">Create your first pipeline</button>
        </div>
      ) : (
        <div className="space-y-3">
          {pipelines.map(p => (
            <div key={p.id} className="card p-4 cursor-pointer hover:border-primary/30 transition-colors"
              onClick={() => startEdit(p)}>
              <div className="flex items-center justify-between">
                <div>
                  <div className="flex items-center gap-2">
                    <span className="font-medium text-text-primary">{p.name}</span>
                    {p.isDefault && <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-primary/10 text-primary">Default</span>}
                  </div>
                  {p.description && <p className="text-xs text-text-muted mt-0.5">{p.description}</p>}
                  <div className="flex items-center gap-1 mt-2">
                    {(p.stages || []).map((s, i) => (
                      <span key={i} className="flex items-center gap-1">
                        <span className="text-[10px] px-1.5 py-0.5 rounded bg-surface-el text-text-secondary">{s.name}</span>
                        {i < (p.stages || []).length - 1 && <span className="text-text-muted text-[10px]">→</span>}
                      </span>
                    ))}
                  </div>
                </div>
                <div className="flex items-center gap-2">
                  <span className="text-xs text-text-muted">{(p.stages || []).length} stages</span>
                  <button onClick={e => { e.stopPropagation(); handleDelete(p.id, p.name); }}
                    className="text-text-muted hover:text-danger p-1"><Trash2 className="w-3.5 h-3.5" /></button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
