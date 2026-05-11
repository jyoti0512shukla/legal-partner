import { useState, useEffect } from 'react';
import { Calendar, Check, AlertTriangle, Clock } from 'lucide-react';
import api from '../../api/client';

function getUrgencyStyle(daysUntil) {
  if (daysUntil < 0) return { color: 'var(--danger-400)', bg: 'var(--danger-bg, rgba(217,83,78,0.1))' };
  if (daysUntil <= 7) return { color: 'var(--danger-400)', bg: 'var(--danger-bg, rgba(217,83,78,0.08))' };
  if (daysUntil <= 30) return { color: '#d29922', bg: 'rgba(210,153,34,0.08)' };
  if (daysUntil <= 90) return { color: 'var(--warn-400)', bg: 'var(--warn-bg, rgba(216,154,58,0.06))' };
  return { color: 'var(--text-3)', bg: 'transparent' };
}

const TYPE_LABELS = {
  EXPIRY: 'Expiry',
  NOTICE: 'Notice',
  RENEWAL: 'Renewal',
  PAYMENT: 'Payment',
  CUSTOM: 'Custom',
};

export default function DeadlineWidget() {
  const [deadlines, setDeadlines] = useState([]);
  const [loading, setLoading] = useState(true);

  const load = () => {
    api.get('/documents/deadlines/upcoming?limit=8')
      .then(r => setDeadlines(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const handleAction = async (id) => {
    try {
      await api.post(`/documents/deadlines/${id}/action`);
      load();
    } catch {}
  };

  if (loading) return null;
  if (deadlines.length === 0) return null;

  return (
    <div className="card">
      <div className="card-header">
        <Calendar size={14} style={{ color: 'var(--text-2)' }} />
        <h3>Upcoming Deadlines</h3>
        <span className="badge" style={{ marginLeft: 'auto' }}>{deadlines.length}</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column' }}>
        {deadlines.map(dl => {
          const urgency = getUrgencyStyle(dl.daysUntil);
          return (
            <div key={dl.id} style={{
              display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
              borderBottom: '1px solid var(--line-1)', background: urgency.bg,
            }}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 12, fontWeight: 500, color: 'var(--text-1)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                  {dl.documentName}
                </div>
                <div style={{ display: 'flex', gap: 6, alignItems: 'center', marginTop: 2 }}>
                  <span style={{
                    fontSize: 9, fontWeight: 600, padding: '1px 5px', borderRadius: 3,
                    background: urgency.color + '18', color: urgency.color, textTransform: 'uppercase',
                  }}>
                    {TYPE_LABELS[dl.deadlineType] || dl.deadlineType}
                  </span>
                  <span style={{ fontSize: 10, color: 'var(--text-3)' }}>{dl.description}</span>
                </div>
              </div>
              <div style={{ textAlign: 'right', flexShrink: 0 }}>
                <div style={{ fontSize: 12, fontWeight: 600, color: urgency.color }}>
                  {dl.daysUntil < 0 ? `${Math.abs(dl.daysUntil)}d overdue` :
                   dl.daysUntil === 0 ? 'Today' :
                   `${dl.daysUntil}d`}
                </div>
                <div style={{ fontSize: 10, color: 'var(--text-3)' }}>{dl.deadlineDate}</div>
              </div>
              <button
                className="icon-btn"
                onClick={() => handleAction(dl.id)}
                title="Mark as actioned"
                style={{ width: 26, height: 26, flexShrink: 0 }}
              >
                <Check size={12} />
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
