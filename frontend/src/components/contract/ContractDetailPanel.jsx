import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  X, Lock, FileEdit, Shield, Key, GitCompare, Send, Download, Trash2,
  Loader2, Check, Pencil, Calendar, Clock,
} from 'lucide-react';
import api from '../../api/client';
import StatusBadge from './StatusBadge';
import VersionTimeline from './VersionTimeline';
import NotesSection from './NotesSection';
import FinalizeModal from './FinalizeModal';
import SendForSignatureModal from '../../components/SendForSignatureModal';

const TABS = [
  { id: 'overview', label: 'Overview' },
  { id: 'versions', label: 'Versions' },
  { id: 'deadlines', label: 'Deadlines' },
  { id: 'activity', label: 'Activity' },
];

function getUrgencyColor(daysUntil) {
  if (daysUntil < 0) return 'var(--danger-400)';
  if (daysUntil <= 7) return 'var(--danger-400)';
  if (daysUntil <= 30) return '#d29922';
  if (daysUntil <= 90) return 'var(--warn-400)';
  return 'var(--text-3)';
}

export default function ContractDetailPanel({ docId, onClose, onStatusChanged, onDeleted }) {
  const navigate = useNavigate();
  const [doc, setDoc] = useState(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('overview');
  const [allowed, setAllowed] = useState([]);
  const [showFinalize, setShowFinalize] = useState(false);
  const [showSign, setShowSign] = useState(false);

  // Inline editing
  const [editingField, setEditingField] = useState(null);
  const [editValue, setEditValue] = useState('');
  const [saving, setSaving] = useState(false);

  const loadDoc = () => {
    setLoading(true);
    Promise.all([
      api.get(`/documents/${docId}`),
      api.get(`/documents/${docId}/lifecycle/allowed-transitions`),
    ]).then(([docRes, transRes]) => {
      const d = docRes.data?.metadata || docRes.data;
      setDoc(d);
      setAllowed(transRes.data?.allowedTransitions || []);
    }).catch(() => {})
    .finally(() => setLoading(false));
  };

  useEffect(() => { if (docId) { setTab('overview'); loadDoc(); } }, [docId]);

  const handleTransition = async (e) => {
    const status = e.target.value;
    if (!status) return;
    try {
      await api.post(`/documents/${docId}/lifecycle/transition?status=${status}`);
      loadDoc();
      onStatusChanged?.();
    } catch (err) { alert(err.response?.data?.message || 'Transition failed'); }
  };

  const handleDelete = async () => {
    if (!confirm('Delete this document permanently?')) return;
    try { await api.delete(`/documents/${docId}`); onDeleted?.(); }
    catch { alert('Delete failed'); }
  };

  const startEdit = (field, value) => { setEditingField(field); setEditValue(value || ''); };
  const cancelEdit = () => { setEditingField(null); setEditValue(''); };
  const saveEdit = async () => {
    setSaving(true);
    try {
      await api.patch(`/documents/${docId}/metadata`, { [editingField]: editValue });
      loadDoc();
      onStatusChanged?.();
      cancelEdit();
    } catch (err) { alert(err.response?.data?.message || 'Save failed'); }
    finally { setSaving(false); }
  };

  if (loading && !doc) {
    return (
      <div style={{ width: '45%', borderLeft: '1px solid var(--line-1)', background: 'var(--bg-1)', display: 'grid', placeItems: 'center' }}>
        <Loader2 size={20} className="animate-spin" style={{ color: 'var(--text-3)' }} />
      </div>
    );
  }
  if (!doc) return null;

  const isLocked = doc.locked || doc.isLocked;

  return (
    <div style={{
      width: '45%', borderLeft: '1px solid var(--line-1)', background: 'var(--bg-1)',
      display: 'flex', flexDirection: 'column', overflow: 'hidden',
    }}>
      {/* Header (sticky) */}
      <div style={{ padding: '16px 18px', borderBottom: '1px solid var(--line-1)', flexShrink: 0 }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 8 }}>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ fontWeight: 600, fontSize: 14, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
              {doc.fileName}
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginTop: 4 }}>
              {doc.contractStatus && <StatusBadge status={doc.contractStatus} />}
              {isLocked && <Lock size={11} style={{ color: 'var(--text-3)' }} />}
              {doc.currentVersion && <span style={{ fontSize: 10, color: 'var(--text-3)' }}>v{doc.currentVersion}</span>}
            </div>
          </div>
          <button className="icon-btn" onClick={onClose} style={{ flexShrink: 0 }}><X size={16} /></button>
        </div>

        {/* Quick actions */}
        <div style={{ display: 'flex', gap: 4, flexWrap: 'wrap', marginBottom: 8 }}>
          <button className="btn sm" onClick={() => navigate(`/documents/${docId}/edit`)}>
            <FileEdit size={11} /> Editor
          </button>
          <button className="btn sm" onClick={() => navigate(`/review?docId=${docId}`)}>
            <Shield size={11} /> Review
          </button>
          <button className="btn sm" onClick={() => navigate(`/extraction?docId=${docId}`)}>
            <Key size={11} /> Extract
          </button>
          <button className="btn sm" onClick={() => navigate(`/compare?docId=${docId}`)}>
            <GitCompare size={11} /> Compare
          </button>
          {!isLocked && (
            <button className="btn sm" onClick={() => setShowFinalize(true)}>
              <Lock size={11} /> Finalize
            </button>
          )}
          {doc.contractStatus === 'PENDING_SIGNATURE' && (
            <button className="btn sm primary" onClick={() => setShowSign(true)}>
              <Send size={11} /> Sign
            </button>
          )}
        </div>

        {/* Status transition */}
        {allowed.length > 0 && (
          <select className="select" onChange={handleTransition} value="" style={{ fontSize: 11, padding: '3px 8px', width: '100%' }}>
            <option value="">Change status...</option>
            {allowed.map(s => <option key={s} value={s}>{s.replace(/_/g, ' ')}</option>)}
          </select>
        )}

        {/* Tabs */}
        <div style={{ display: 'flex', gap: 0, marginTop: 10, borderBottom: '1px solid var(--line-1)' }}>
          {TABS.map(t => (
            <button key={t.id} onClick={() => setTab(t.id)}
              style={{
                padding: '6px 12px', fontSize: 11, fontWeight: 600, cursor: 'pointer',
                background: 'none', border: 'none',
                borderBottom: tab === t.id ? '2px solid var(--brand-400)' : '2px solid transparent',
                color: tab === t.id ? 'var(--brand-400)' : 'var(--text-3)',
              }}>
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* Tab content */}
      <div style={{ flex: 1, overflow: 'auto', padding: 18 }}>
        {tab === 'overview' && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {/* Brief */}
            {(doc.userBrief || doc.summaryText) && (
              <div>
                <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: 4 }}>Brief</div>
                <div style={{ fontSize: 12, color: 'var(--text-1)', lineHeight: 1.5, padding: 10, background: 'var(--bg-2)', borderRadius: 'var(--r-sm)' }}>
                  {doc.userBrief || doc.summaryText}
                </div>
              </div>
            )}

            {/* Key Points */}
            {doc.userKeyPoints && (() => {
              try {
                const pts = JSON.parse(doc.userKeyPoints);
                if (pts.length > 0) return (
                  <div>
                    <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: 4 }}>Key Points</div>
                    <ul style={{ margin: 0, paddingLeft: 16, fontSize: 12, color: 'var(--text-2)', lineHeight: 1.6 }}>
                      {pts.map((p, i) => <li key={i}>{p}</li>)}
                    </ul>
                  </div>
                );
              } catch { return null; }
            })()}

            {/* Metadata */}
            <div>
              <div style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-3)', textTransform: 'uppercase', marginBottom: 6 }}>Details</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
                {[
                  { key: 'partyA', label: 'Party A', editable: true },
                  { key: 'partyB', label: 'Party B', editable: true },
                  { key: 'clientName', label: 'Client', editable: true },
                  { key: 'jurisdiction', label: 'Jurisdiction', editable: true },
                  { key: 'contractValue', label: 'Value', editable: true },
                  { key: 'documentType', label: 'Type', editable: false },
                  { key: 'expiryDate', label: 'Expires', editable: false },
                  { key: 'noticePeriodDays', label: 'Notice Period', editable: false, suffix: ' days' },
                  { key: 'governingLawJurisdiction', label: 'Governing Law', editable: false },
                ].map(f => {
                  const val = doc[f.key];
                  const isEditing = editingField === f.key;
                  return (
                    <div key={f.key} style={{
                      display: 'flex', alignItems: 'center', padding: '6px 0',
                      borderBottom: '1px solid var(--line-1)',
                    }}>
                      <span style={{ fontSize: 11, color: 'var(--text-3)', width: 100, flexShrink: 0 }}>{f.label}</span>
                      {isEditing ? (
                        <div style={{ display: 'flex', gap: 4, flex: 1 }}>
                          <input className="input" value={editValue} onChange={e => setEditValue(e.target.value)}
                            style={{ fontSize: 12, flex: 1, padding: '2px 6px' }} autoFocus />
                          <button className="icon-btn" onClick={saveEdit} disabled={saving} style={{ width: 22, height: 22 }}>
                            <Check size={11} />
                          </button>
                          <button className="icon-btn" onClick={cancelEdit} style={{ width: 22, height: 22 }}>
                            <X size={11} />
                          </button>
                        </div>
                      ) : (
                        <div style={{ display: 'flex', alignItems: 'center', gap: 4, flex: 1, minWidth: 0 }}>
                          <span style={{ fontSize: 12, color: val ? 'var(--text-1)' : 'var(--text-3)' }}>
                            {val ? `${val}${f.suffix || ''}` : '—'}
                          </span>
                          {f.editable && !isLocked && (
                            <button className="icon-btn" onClick={() => startEdit(f.key, val)}
                              style={{ width: 18, height: 18, opacity: 0.4, marginLeft: 'auto' }}>
                              <Pencil size={9} />
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Finalization info */}
            {doc.finalizedBy && (
              <div style={{ fontSize: 10, color: 'var(--text-3)', padding: '8px 0', borderTop: '1px solid var(--line-1)' }}>
                Finalized by {doc.finalizedBy} on {doc.finalizedAt ? new Date(doc.finalizedAt).toLocaleDateString() : '—'}
              </div>
            )}

            {/* Notes */}
            <NotesSection docId={docId} />

            {/* Delete */}
            <button className="btn sm ghost" onClick={handleDelete}
              style={{ color: 'var(--danger-400)', marginTop: 8 }}>
              <Trash2 size={11} /> Delete document
            </button>
          </div>
        )}

        {tab === 'versions' && <VersionTimeline docId={docId} locked={isLocked} />}

        {tab === 'deadlines' && <DeadlinesTab docId={docId} />}

        {tab === 'activity' && <ActivityTab docId={docId} />}
      </div>

      {/* Modals */}
      {showFinalize && (
        <FinalizeModal docId={docId} docName={doc.fileName}
          onClose={() => setShowFinalize(false)}
          onFinalized={() => { setShowFinalize(false); loadDoc(); onStatusChanged?.(); }} />
      )}
      {showSign && (
        <SendForSignatureModal docId={docId} docName={doc.fileName}
          parties={{ partyA: doc.partyA, partyB: doc.partyB }}
          onClose={() => setShowSign(false)}
          onSent={() => { setShowSign(false); loadDoc(); onStatusChanged?.(); }} />
      )}
    </div>
  );
}

// ── Sub-tabs ──

function DeadlinesTab({ docId }) {
  const [deadlines, setDeadlines] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get(`/documents/${docId}/deadlines`)
      .then(r => setDeadlines(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [docId]);

  const handleAction = async (id) => {
    await api.post(`/documents/deadlines/${id}/action`).catch(() => {});
    const r = await api.get(`/documents/${docId}/deadlines`);
    setDeadlines(r.data || []);
  };

  if (loading) return <Loader2 size={14} className="animate-spin" style={{ color: 'var(--text-3)' }} />;
  if (deadlines.length === 0) return <div className="small muted">No deadlines tracked for this contract.</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      {deadlines.map(dl => {
        const days = Math.ceil((new Date(dl.deadlineDate) - new Date()) / 86400000);
        const color = getUrgencyColor(days);
        return (
          <div key={dl.id} style={{
            padding: 10, borderRadius: 'var(--r-sm)', border: '1px solid var(--line-1)',
            background: dl.actioned ? 'var(--bg-2)' : undefined,
            opacity: dl.actioned ? 0.6 : 1,
          }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <span style={{
                  fontSize: 9, fontWeight: 600, padding: '1px 5px', borderRadius: 3,
                  background: color + '18', color, textTransform: 'uppercase', marginRight: 6,
                }}>
                  {dl.deadlineType}
                </span>
                <span style={{ fontSize: 12, color: 'var(--text-1)' }}>{dl.description}</span>
              </div>
              <div style={{ textAlign: 'right', flexShrink: 0 }}>
                <div style={{ fontSize: 12, fontWeight: 600, color }}>
                  {days < 0 ? `${Math.abs(days)}d overdue` : days === 0 ? 'Today' : `${days}d`}
                </div>
                <div style={{ fontSize: 10, color: 'var(--text-3)' }}>{dl.deadlineDate}</div>
              </div>
            </div>
            {!dl.actioned && (
              <button className="btn sm ghost" onClick={() => handleAction(dl.id)} style={{ marginTop: 6, fontSize: 10 }}>
                <Check size={10} /> Mark actioned
              </button>
            )}
          </div>
        );
      })}
    </div>
  );
}

function ActivityTab({ docId }) {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get(`/audit/logs?documentId=${docId}&size=20&sort=timestamp,desc`)
      .then(r => setLogs(r.data?.content || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [docId]);

  if (loading) return <Loader2 size={14} className="animate-spin" style={{ color: 'var(--text-3)' }} />;
  if (logs.length === 0) return <div className="small muted">No activity recorded yet.</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
      {logs.map((log, i) => (
        <div key={log.id || i} style={{
          display: 'flex', gap: 10, padding: '8px 0',
          borderBottom: i < logs.length - 1 ? '1px solid var(--line-1)' : 'none',
        }}>
          <div style={{
            width: 6, height: 6, borderRadius: '50%', marginTop: 5, flexShrink: 0,
            background: log.action?.includes('FAILED') ? 'var(--danger-400)' : 'var(--brand-400)',
          }} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 12, color: 'var(--text-1)' }}>
              {(log.action || '').replace(/_/g, ' ')}
            </div>
            {log.queryText && (
              <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 1 }}>{log.queryText}</div>
            )}
            <div style={{ fontSize: 10, color: 'var(--text-3)', marginTop: 2 }}>
              {log.username} · {new Date(log.timestamp).toLocaleString()}
            </div>
          </div>
        </div>
      ))}
    </div>
  );
}
