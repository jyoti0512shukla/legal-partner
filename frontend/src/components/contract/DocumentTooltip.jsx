import { useState, useRef } from 'react';
import StatusBadge from './StatusBadge';

export default function DocumentTooltip({ doc, children }) {
  const [show, setShow] = useState(false);
  const timeout = useRef(null);

  const onEnter = () => { timeout.current = setTimeout(() => setShow(true), 400); };
  const onLeave = () => { clearTimeout(timeout.current); setShow(false); };

  const brief = doc.userBrief || doc.summaryText;
  let keyPoints = [];
  if (doc.userKeyPoints) {
    try { keyPoints = JSON.parse(doc.userKeyPoints); } catch {}
  }

  return (
    <div style={{ position: 'relative', display: 'inline-flex', minWidth: 0 }}
         onMouseEnter={onEnter} onMouseLeave={onLeave}>
      {children}
      {show && (brief || doc.contractStatus || doc.partyA) && (
        <div style={{
          position: 'absolute', top: '100%', left: 0, zIndex: 999, marginTop: 6,
          background: 'var(--bg-1)', border: '1px solid var(--line-1)',
          borderRadius: 'var(--r-md, 8px)', padding: 14, minWidth: 280, maxWidth: 340,
          boxShadow: '0 8px 24px rgba(0,0,0,0.15)', fontSize: 12, lineHeight: 1.5,
        }}>
          {doc.contractStatus && (
            <div style={{ marginBottom: 8 }}><StatusBadge status={doc.contractStatus} /></div>
          )}
          {brief && (
            <div style={{ color: 'var(--text-1)', marginBottom: 8 }}>
              {brief.length > 200 ? brief.slice(0, 200) + '...' : brief}
            </div>
          )}
          {keyPoints.length > 0 && (
            <ul style={{ margin: '0 0 8px', paddingLeft: 16, color: 'var(--text-2)' }}>
              {keyPoints.slice(0, 5).map((p, i) => <li key={i}>{p}</li>)}
            </ul>
          )}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, color: 'var(--text-3)', fontSize: 11 }}>
            {doc.partyA && <span>Party A: {doc.partyA}</span>}
            {doc.partyB && <span>Party B: {doc.partyB}</span>}
            {doc.expiryDate && <span>Expires: {doc.expiryDate}</span>}
          </div>
          {doc.finalizedBy && (
            <div style={{ marginTop: 6, color: 'var(--text-3)', fontSize: 10, borderTop: '1px solid var(--line-1)', paddingTop: 6 }}>
              Finalized by {doc.finalizedBy} {doc.finalizedAt && `· ${new Date(doc.finalizedAt).toLocaleDateString()}`}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
