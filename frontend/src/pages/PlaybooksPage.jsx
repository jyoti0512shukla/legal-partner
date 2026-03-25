import { useState, useEffect } from 'react';
import { Shield, Plus, Trash2, Lock, Unlock, Edit2, X, ChevronDown, ChevronRight, Check } from 'lucide-react';
import api from '../api/client';

const DEAL_TYPES = [
  'SAAS_ACQUISITION', 'M_AND_A', 'NDA', 'COMMERCIAL_LEASE',
  'FINANCING', 'IP_LICENSE', 'EMPLOYMENT', 'GENERAL'
];

const CLAUSE_TYPES = [
  'LIABILITY', 'IP_RIGHTS', 'TERMINATION', 'CONFIDENTIALITY',
  'GOVERNING_LAW', 'FORCE_MAJEURE', 'INDEMNITY', 'PAYMENT',
  'DATA_PROTECTION', 'NON_COMPETE', 'REPRESENTATIONS_WARRANTIES', 'ASSIGNMENT'
];

function DealTypeBadge({ dealType }) {
  return (
    <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-primary/10 text-primary">
      {dealType?.replace(/_/g, ' ')}
    </span>
  );
}

function DefaultBadge() {
  return (
    <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-success/10 text-success flex items-center gap-1">
      <Check className="w-3 h-3" /> Default
    </span>
  );
}

function PlaybookModal({ playbook, onClose, onSaved }) {
  const isEdit = !!playbook;
  const [form, setForm] = useState({
    name: playbook?.name || '',
    dealType: playbook?.dealType || 'GENERAL',
    description: playbook?.description || '',
    isDefault: playbook?.isDefault || false,
  });
  const [positions, setPositions] = useState([]);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState('');
  const [loadingPositions, setLoadingPositions] = useState(false);

  useEffect(() => {
    if (isEdit && playbook?.id) {
      setLoadingPositions(true);
      api.get(`/playbooks/${playbook.id}/positions`)
        .then(r => setPositions(r.data || []))
        .catch(() => {})
        .finally(() => setLoadingPositions(false));
    }
  }, [isEdit, playbook?.id]);

  const addPosition = () => {
    setPositions([...positions, {
      clauseType: 'LIABILITY',
      standardPosition: '',
      minimumAcceptable: '',
      nonNegotiable: false,
      notes: '',
    }]);
  };

  const updatePosition = (index, field, value) => {
    setPositions(positions.map((p, i) => i === index ? { ...p, [field]: value } : p));
  };

  const removePosition = (index) => {
    setPositions(positions.filter((_, i) => i !== index));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSaving(true);
    setError('');
    try {
      const payload = { ...form, positions };
      if (isEdit) {
        await api.put(`/playbooks/${playbook.id}`, payload);
      } else {
        await api.post('/playbooks', payload);
      }
      onSaved();
    } catch (err) {
      setError(err.response?.data?.message || 'Failed to save playbook');
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div className="glass rounded-xl w-full max-w-3xl p-6 max-h-[90vh] overflow-y-auto">
        <div className="flex items-center justify-between mb-5">
          <h2 className="text-lg font-semibold">{isEdit ? 'Edit Playbook' : 'New Playbook'}</h2>
          <button onClick={onClose} className="text-text-muted hover:text-text-primary"><X className="w-5 h-5" /></button>
        </div>
        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="text-xs text-text-muted mb-1 block">Playbook Name *</label>
            <input required value={form.name} onChange={e => setForm({ ...form, name: e.target.value })}
              placeholder="e.g. Standard SaaS Acquisition" className="input-field w-full text-sm" />
          </div>
          <div className="grid grid-cols-2 gap-4">
            <div>
              <label className="text-xs text-text-muted mb-1 block">Deal Type</label>
              <select value={form.dealType} onChange={e => setForm({ ...form, dealType: e.target.value })} className="input-field w-full text-sm">
                {DEAL_TYPES.map(d => <option key={d} value={d}>{d.replace(/_/g, ' ')}</option>)}
              </select>
            </div>
            <div className="flex items-end">
              <label className="flex items-center gap-2 cursor-pointer">
                <input type="checkbox" checked={form.isDefault} onChange={e => setForm({ ...form, isDefault: e.target.checked })}
                  className="w-4 h-4 rounded border-border text-primary focus:ring-primary" />
                <span className="text-sm text-text-secondary">Set as default for this deal type</span>
              </label>
            </div>
          </div>
          <div>
            <label className="text-xs text-text-muted mb-1 block">Description</label>
            <textarea value={form.description} onChange={e => setForm({ ...form, description: e.target.value })}
              rows={2} placeholder="Describe the playbook's purpose..." className="input-field w-full text-sm resize-none" />
          </div>

          {/* Positions Table */}
          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="text-xs font-medium text-text-muted">Positions</label>
              <button type="button" onClick={addPosition}
                className="btn-secondary text-xs flex items-center gap-1">
                <Plus className="w-3 h-3" /> Add Position
              </button>
            </div>

            {loadingPositions ? (
              <p className="text-xs text-text-muted py-4">Loading positions...</p>
            ) : positions.length === 0 ? (
              <p className="text-xs text-text-muted py-4 text-center">No positions added yet. Click "Add Position" to define negotiation stances.</p>
            ) : (
              <div className="space-y-3">
                {positions.map((pos, idx) => (
                  <div key={idx} className="border border-border rounded-lg p-3 bg-surface-el/50">
                    <div className="flex items-center gap-2 mb-2">
                      <select value={pos.clauseType} onChange={e => updatePosition(idx, 'clauseType', e.target.value)}
                        className="input-field text-xs flex-1">
                        {CLAUSE_TYPES.map(c => <option key={c} value={c}>{c.replace(/_/g, ' ')}</option>)}
                      </select>
                      <button type="button" onClick={() => updatePosition(idx, 'nonNegotiable', !pos.nonNegotiable)}
                        className={`p-1.5 rounded ${pos.nonNegotiable ? 'text-danger bg-danger/10' : 'text-text-muted hover:text-text-primary'}`}
                        title={pos.nonNegotiable ? 'Non-negotiable' : 'Negotiable'}>
                        {pos.nonNegotiable ? <Lock className="w-3.5 h-3.5" /> : <Unlock className="w-3.5 h-3.5" />}
                      </button>
                      <button type="button" onClick={() => removePosition(idx)}
                        className="p-1.5 text-text-muted hover:text-danger rounded">
                        <Trash2 className="w-3.5 h-3.5" />
                      </button>
                    </div>
                    <div className="grid grid-cols-2 gap-2 mb-2">
                      <div>
                        <label className="text-[10px] text-text-muted mb-0.5 block">Standard Position</label>
                        <textarea value={pos.standardPosition} onChange={e => updatePosition(idx, 'standardPosition', e.target.value)}
                          rows={2} placeholder="Our preferred position..." className="input-field w-full text-xs resize-none" />
                      </div>
                      <div>
                        <label className="text-[10px] text-text-muted mb-0.5 block">Minimum Acceptable</label>
                        <textarea value={pos.minimumAcceptable} onChange={e => updatePosition(idx, 'minimumAcceptable', e.target.value)}
                          rows={2} placeholder="The least we'd accept..." className="input-field w-full text-xs resize-none" />
                      </div>
                    </div>
                    <div>
                      <label className="text-[10px] text-text-muted mb-0.5 block">Notes</label>
                      <input value={pos.notes || ''} onChange={e => updatePosition(idx, 'notes', e.target.value)}
                        placeholder="Additional notes..." className="input-field w-full text-xs" />
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>

          {error && <p className="text-danger text-sm">{error}</p>}
          <div className="flex justify-end gap-3 pt-1">
            <button type="button" onClick={onClose} className="btn-secondary text-sm">Cancel</button>
            <button type="submit" disabled={saving} className="btn-primary text-sm flex items-center gap-2">
              {saving && <div className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />}
              {isEdit ? 'Save Changes' : 'Create Playbook'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

function DeleteConfirmModal({ playbook, onClose, onDeleted }) {
  const [deleting, setDeleting] = useState(false);

  const handleDelete = async () => {
    setDeleting(true);
    try {
      await api.delete(`/playbooks/${playbook.id}`);
      onDeleted();
    } catch {
      alert('Failed to delete playbook');
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="fixed inset-0 bg-black/60 z-50 flex items-center justify-center p-4">
      <div className="glass rounded-xl w-full max-w-sm p-6">
        <h2 className="text-lg font-semibold mb-2">Delete Playbook</h2>
        <p className="text-sm text-text-muted mb-5">
          Are you sure you want to delete <strong>{playbook.name}</strong>? This action cannot be undone.
        </p>
        <div className="flex justify-end gap-3">
          <button onClick={onClose} className="btn-secondary text-sm">Cancel</button>
          <button onClick={handleDelete} disabled={deleting}
            className="bg-danger text-white px-4 py-2 rounded-lg text-sm font-medium hover:bg-danger/90 transition-colors flex items-center gap-2">
            {deleting && <div className="w-3 h-3 border-2 border-white/30 border-t-white rounded-full animate-spin" />}
            Delete
          </button>
        </div>
      </div>
    </div>
  );
}

export default function PlaybooksPage() {
  const [playbooks, setPlaybooks] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [editPlaybook, setEditPlaybook] = useState(null);
  const [deletePlaybook, setDeletePlaybook] = useState(null);
  const [expanded, setExpanded] = useState(null);

  const load = () => {
    setLoading(true);
    api.get('/playbooks')
      .then(r => setPlaybooks(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const toggleExpand = (id) => setExpanded(prev => prev === id ? null : id);

  return (
    <div>
      <div className="flex items-center justify-between mb-6">
        <div className="flex items-center gap-3">
          <Shield className="w-6 h-6 text-primary" />
          <h1 className="text-2xl font-bold">Playbooks</h1>
        </div>
        <button onClick={() => setShowCreate(true)} className="btn-primary flex items-center gap-2 text-sm">
          <Plus className="w-4 h-4" /> New Playbook
        </button>
      </div>

      {showCreate && (
        <PlaybookModal
          onClose={() => setShowCreate(false)}
          onSaved={() => { load(); setShowCreate(false); }}
        />
      )}

      {editPlaybook && (
        <PlaybookModal
          playbook={editPlaybook}
          onClose={() => setEditPlaybook(null)}
          onSaved={() => { load(); setEditPlaybook(null); }}
        />
      )}

      {deletePlaybook && (
        <DeleteConfirmModal
          playbook={deletePlaybook}
          onClose={() => setDeletePlaybook(null)}
          onDeleted={() => { load(); setDeletePlaybook(null); }}
        />
      )}

      {loading ? (
        <div className="card text-center py-16">
          <div className="w-6 h-6 border-2 border-primary/30 border-t-primary rounded-full animate-spin mx-auto mb-3" />
          <p className="text-text-muted text-sm">Loading playbooks...</p>
        </div>
      ) : playbooks.length === 0 ? (
        <div className="card text-center py-16">
          <Shield className="w-12 h-12 text-text-muted mx-auto mb-4" />
          <p className="text-text-muted mb-2">No playbooks yet</p>
          <p className="text-xs text-text-muted mb-5">Create negotiation playbooks to define your firm's positions on key clauses</p>
          <button onClick={() => setShowCreate(true)} className="btn-primary text-sm inline-flex items-center gap-2">
            <Plus className="w-4 h-4" /> Create your first playbook
          </button>
        </div>
      ) : (
        <div className="space-y-3">
          {playbooks.map(pb => (
            <div key={pb.id} className="card">
              <div className="flex items-center gap-4 cursor-pointer" onClick={() => toggleExpand(pb.id)}>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                    <span className="font-semibold text-text-primary truncate">{pb.name}</span>
                    <DealTypeBadge dealType={pb.dealType} />
                    {pb.isDefault && <DefaultBadge />}
                  </div>
                  <div className="flex items-center gap-3 text-xs text-text-muted">
                    {pb.description && <span className="truncate max-w-md">{pb.description}</span>}
                    <span>{pb.positionCount ?? 0} position{(pb.positionCount ?? 0) !== 1 ? 's' : ''}</span>
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  <button onClick={e => { e.stopPropagation(); setEditPlaybook(pb); }}
                    className="p-1.5 text-text-muted hover:text-primary rounded" title="Edit">
                    <Edit2 className="w-4 h-4" />
                  </button>
                  <button onClick={e => { e.stopPropagation(); setDeletePlaybook(pb); }}
                    className="p-1.5 text-text-muted hover:text-danger rounded" title="Delete">
                    <Trash2 className="w-4 h-4" />
                  </button>
                  {expanded === pb.id ? <ChevronDown className="w-4 h-4 text-text-muted" /> : <ChevronRight className="w-4 h-4 text-text-muted" />}
                </div>
              </div>

              {expanded === pb.id && (
                <PlaybookPositions playbookId={pb.id} />
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function PlaybookPositions({ playbookId }) {
  const [positions, setPositions] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get(`/playbooks/${playbookId}/positions`)
      .then(r => setPositions(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [playbookId]);

  if (loading) return <p className="text-xs text-text-muted py-4 mt-3">Loading positions...</p>;
  if (positions.length === 0) return <p className="text-xs text-text-muted py-4 mt-3">No positions defined for this playbook.</p>;

  return (
    <div className="mt-4 pt-4 border-t border-border">
      <div className="space-y-2">
        {positions.map((pos, idx) => (
          <div key={idx} className="flex items-start gap-3 p-3 bg-surface-el/50 rounded-lg text-sm">
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 mb-1">
                <span className="text-xs font-medium px-2 py-0.5 rounded-full bg-surface-el border border-border text-text-secondary">
                  {pos.clauseType?.replace(/_/g, ' ')}
                </span>
                {pos.nonNegotiable && (
                  <span className="flex items-center gap-1 text-xs text-danger">
                    <Lock className="w-3 h-3" /> Non-negotiable
                  </span>
                )}
              </div>
              {pos.standardPosition && (
                <p className="text-xs text-text-secondary mb-0.5"><strong>Standard:</strong> {pos.standardPosition}</p>
              )}
              {pos.minimumAcceptable && (
                <p className="text-xs text-text-muted"><strong>Minimum:</strong> {pos.minimumAcceptable}</p>
              )}
              {pos.notes && (
                <p className="text-xs text-text-muted mt-1 italic">{pos.notes}</p>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
