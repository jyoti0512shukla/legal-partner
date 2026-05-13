import { useState, useEffect } from 'react';
import { Upload, GitBranch, Bot, Users, Loader2, Lock, GitCompare, X } from 'lucide-react';
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
  const [comparing, setComparing] = useState(null); // { v1, v2 }
  const [diffData, setDiffData] = useState(null);
  const [diffLoading, setDiffLoading] = useState(false);

  useEffect(() => {
    if (!docId) return;
    api.get(`/documents/${docId}/versions`)
      .then(r => setVersions(r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [docId]);

  const handleCompare = async (v1, v2) => {
    setComparing({ v1, v2 });
    setDiffLoading(true);
    setDiffData(null);
    try {
      const res = await api.get(`/documents/${docId}/versions/${v1}/${v2}/compare`);
      setDiffData(res.data);
    } catch { setDiffData(null); }
    finally { setDiffLoading(false); }
  };

  const closeCompare = () => { setComparing(null); setDiffData(null); };

  if (loading) return <div className="small muted" style={{ padding: 10 }}><Loader2 size={14} className="animate-spin" /> Loading versions...</div>;
  if (versions.length === 0) return <div className="small muted" style={{ padding: 10 }}>No versions tracked yet</div>;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
        <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-2)' }}>Version History</span>
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
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
      </div>

      {/* Version list */}
      {versions.map((v, i) => {
        const cfg = SOURCE_CONFIG[v.source] || SOURCE_CONFIG.UPLOAD;
        const Icon = cfg.icon;
        const prevVersion = i < versions.length - 1 ? versions[i + 1] : null;
        return (
          <div key={v.id} style={{ display: 'flex', gap: 10, position: 'relative', paddingLeft: 20, paddingBottom: i < versions.length - 1 ? 14 : 0 }}>
            {i < versions.length - 1 && (
              <div style={{ position: 'absolute', left: 8, top: 18, bottom: 0, width: 1, background: 'var(--line-1)' }} />
            )}
            <div style={{
              position: 'absolute', left: 3, top: 4,
              width: 12, height: 12, borderRadius: '50%',
              background: i === 0 ? cfg.color : 'var(--bg-3)',
              border: `2px solid ${cfg.color}`, flexShrink: 0,
            }} />
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
                {/* Compare with previous button */}
                {prevVersion && (
                  <button onClick={() => handleCompare(prevVersion.versionNumber, v.versionNumber)}
                    style={{
                      fontSize: 9, color: 'var(--brand-400)', background: 'none', border: 'none',
                      cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 3, marginLeft: 'auto',
                    }}>
                    <GitCompare size={9} /> vs v{prevVersion.versionNumber}
                  </button>
                )}
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

      {/* Inline diff view */}
      {comparing && (
        <div style={{ marginTop: 14, border: '1px solid var(--line-1)', borderRadius: 'var(--r-md)', overflow: 'hidden' }}>
          <div style={{
            display: 'flex', alignItems: 'center', justifyContent: 'space-between',
            padding: '8px 12px', background: 'var(--bg-2)', borderBottom: '1px solid var(--line-1)',
          }}>
            <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-2)' }}>
              <GitCompare size={11} style={{ verticalAlign: 'middle', marginRight: 4 }} />
              Comparing v{comparing.v1} → v{comparing.v2}
            </span>
            <button onClick={closeCompare} style={{ background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-3)' }}>
              <X size={14} />
            </button>
          </div>
          <div style={{ maxHeight: 400, overflow: 'auto', padding: 12 }}>
            {diffLoading ? (
              <div style={{ textAlign: 'center', padding: 20 }}><Loader2 size={14} className="animate-spin" style={{ color: 'var(--text-3)' }} /></div>
            ) : diffData ? (
              <VersionDiff v1={diffData.v1} v2={diffData.v2} />
            ) : (
              <div className="small muted">Failed to load comparison.</div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function VersionDiff({ v1, v2 }) {
  const lines1 = (v1.text || '').split(/[.!?]+/).filter(s => s.trim());
  const lines2 = (v2.text || '').split(/[.!?]+/).filter(s => s.trim());

  // Simple sentence-level diff
  const set1 = new Set(lines1.map(l => l.trim().toLowerCase()));
  const set2 = new Set(lines2.map(l => l.trim().toLowerCase()));

  const removed = lines1.filter(l => !set2.has(l.trim().toLowerCase()));
  const added = lines2.filter(l => !set1.has(l.trim().toLowerCase()));
  const unchanged = lines1.filter(l => set2.has(l.trim().toLowerCase()));

  const hasChanges = removed.length > 0 || added.length > 0;

  return (
    <div style={{ fontSize: 11, lineHeight: 1.6 }}>
      <div style={{ display: 'flex', gap: 12, marginBottom: 10, fontSize: 10, color: 'var(--text-3)' }}>
        <span>v{v1.versionNumber} ({v1.source}) by {v1.createdBy}</span>
        <span>→</span>
        <span>v{v2.versionNumber} ({v2.source}) by {v2.createdBy}</span>
      </div>

      {!hasChanges ? (
        <div className="small muted">No text differences detected between versions.</div>
      ) : (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {removed.length > 0 && (
            <div>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--danger-400)', marginBottom: 4 }}>
                Removed in v{v2.versionNumber} ({removed.length} sentence{removed.length > 1 ? 's' : ''})
              </div>
              {removed.slice(0, 15).map((line, i) => (
                <div key={`r-${i}`} style={{
                  padding: '3px 8px', marginBottom: 2, borderRadius: 3,
                  background: 'rgba(217,83,78,0.08)', color: 'var(--danger-400)',
                  textDecoration: 'line-through', fontSize: 11,
                }}>
                  {line.trim()}.
                </div>
              ))}
              {removed.length > 15 && <div className="small muted">...and {removed.length - 15} more</div>}
            </div>
          )}
          {added.length > 0 && (
            <div style={{ marginTop: removed.length > 0 ? 8 : 0 }}>
              <div style={{ fontSize: 10, fontWeight: 600, color: 'var(--success-400)', marginBottom: 4 }}>
                Added in v{v2.versionNumber} ({added.length} sentence{added.length > 1 ? 's' : ''})
              </div>
              {added.slice(0, 15).map((line, i) => (
                <div key={`a-${i}`} style={{
                  padding: '3px 8px', marginBottom: 2, borderRadius: 3,
                  background: 'rgba(63,185,80,0.08)', color: 'var(--success-400)',
                  fontSize: 11,
                }}>
                  + {line.trim()}.
                </div>
              ))}
              {added.length > 15 && <div className="small muted">...and {added.length - 15} more</div>}
            </div>
          )}
          <div style={{ marginTop: 8, fontSize: 10, color: 'var(--text-3)' }}>
            {unchanged.length} unchanged sentence{unchanged.length !== 1 ? 's' : ''}
          </div>
        </div>
      )}
    </div>
  );
}
