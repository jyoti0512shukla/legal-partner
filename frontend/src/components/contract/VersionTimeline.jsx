import { useState, useEffect } from 'react';
import { Upload, GitBranch, Bot, Users, Loader2, Lock } from 'lucide-react';
import api from '../../api/client';

const SOURCE_CONFIG = {
  AI_GENERATED: { label: 'AI Generated', icon: Bot, color: 'var(--info-400)' },
  UPLOAD:       { label: 'Uploaded',      icon: Upload, color: 'var(--success-400)' },
  COUNTERPARTY: { label: 'Counterparty',  icon: Users, color: 'var(--warn-400)' },
  EDIT:         { label: 'Edited',         icon: GitBranch, color: 'var(--text-2)' },
};

export default function VersionTimeline({ docId, locked, onUploadVersion }) {
  const [versions, setVersions] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!docId) return;
    api.get(`/documents/${docId}/versions`)
      .then(r => setVersions(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [docId]);

  if (loading) return <div className="small muted" style={{ padding: 10 }}><Loader2 size={14} className="animate-spin" /> Loading versions...</div>;
  if (versions.length === 0) return <div className="small muted" style={{ padding: 10 }}>No versions tracked yet</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-2)' }}>Version History</span>
        {!locked && onUploadVersion && (
          <button className="btn sm" onClick={onUploadVersion}>
            <Upload size={12} /> Upload Version
          </button>
        )}
        {locked && (
          <span style={{ fontSize: 10, color: 'var(--text-3)', display: 'flex', alignItems: 'center', gap: 4 }}>
            <Lock size={10} /> Locked
          </span>
        )}
      </div>
      {versions.map((v, i) => {
        const cfg = SOURCE_CONFIG[v.source] || SOURCE_CONFIG.UPLOAD;
        const Icon = cfg.icon;
        return (
          <div key={v.id} style={{ display: 'flex', gap: 10, position: 'relative', paddingLeft: 20, paddingBottom: i < versions.length - 1 ? 14 : 0 }}>
            {/* Timeline line */}
            {i < versions.length - 1 && (
              <div style={{ position: 'absolute', left: 8, top: 18, bottom: 0, width: 1, background: 'var(--line-1)' }} />
            )}
            {/* Dot */}
            <div style={{
              position: 'absolute', left: 3, top: 4,
              width: 12, height: 12, borderRadius: '50%',
              background: i === 0 ? cfg.color : 'var(--bg-3)',
              border: `2px solid ${cfg.color}`, flexShrink: 0,
            }} />
            {/* Content */}
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                <span style={{ fontWeight: 600, fontSize: 12 }}>v{v.versionNumber}</span>
                <span style={{
                  fontSize: 9, fontWeight: 600, padding: '1px 5px', borderRadius: 3,
                  background: cfg.color + '18', color: cfg.color, textTransform: 'uppercase',
                  display: 'flex', alignItems: 'center', gap: 3,
                }}>
                  <Icon size={9} /> {cfg.label}
                </span>
              </div>
              {v.changeSummary && (
                <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 2 }}>{v.changeSummary}</div>
              )}
              <div style={{ fontSize: 10, color: 'var(--text-3)', marginTop: 2 }}>
                {v.createdBy} · {new Date(v.createdAt).toLocaleDateString()}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}
