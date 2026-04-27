import { useState } from 'react';
import { ChevronRight, Pencil, Check, X } from 'lucide-react';
import EvidenceQuote from './EvidenceQuote';

const CONFIDENCE_COLORS = {
  HIGH: 'var(--success-400)',
  MEDIUM: 'var(--warn-400)',
  LOW: 'var(--danger-400)',
};

const IMPORTANCE_STYLES = {
  HIGH: { bg: 'var(--danger-bg)', color: 'var(--danger-400)', label: 'Critical' },
  MEDIUM: { bg: 'var(--warn-bg)', color: 'var(--warn-400)', label: 'Important' },
  LOW: { bg: 'var(--bg-3)', color: 'var(--text-4)', label: 'Info' },
};

export default function ExtractionEntryRow({ entry, onCorrect }) {
  const [expanded, setExpanded] = useState(false);
  const [editing, setEditing] = useState(false);
  const [editValue, setEditValue] = useState(entry.value || '');

  const conf = CONFIDENCE_COLORS[entry.extractionConfidence] || CONFIDENCE_COLORS.LOW;
  const imp = IMPORTANCE_STYLES[entry.importance] || IMPORTANCE_STYLES.LOW;
  const fieldLabel = (entry.canonicalField || entry.rawField || '').replace(/_/g, ' ');
  const hasEvidence = entry.evidence && entry.evidence.length > 0 && entry.evidence.some(e => e.text);

  const handleSave = () => {
    if (editValue.trim() && editValue !== entry.value) {
      onCorrect(entry.canonicalField || entry.rawField, editValue.trim());
    }
    setEditing(false);
  };

  return (
    <div style={{
      borderBottom: '1px solid var(--line-1)',
      transition: 'background 0.1s',
    }}>
      <div
        onClick={() => hasEvidence && setExpanded(e => !e)}
        style={{
          display: 'grid', gridTemplateColumns: '20px 1fr 1.2fr 80px 70px 70px 28px',
          gap: 10, alignItems: 'center', padding: '10px 12px',
          cursor: hasEvidence ? 'pointer' : 'default',
        }}
        onMouseEnter={e => e.currentTarget.style.background = 'var(--bg-2)'}
        onMouseLeave={e => e.currentTarget.style.background = 'transparent'}
      >
        {/* Chevron */}
        <div>
          {hasEvidence && (
            <ChevronRight size={14} style={{
              color: 'var(--text-4)', transform: expanded ? 'rotate(90deg)' : 'none',
              transition: 'transform 0.15s',
            }} />
          )}
        </div>

        {/* Field name */}
        <div style={{ fontSize: 13, fontWeight: 500, textTransform: 'capitalize', color: 'var(--text-1)' }}>
          {fieldLabel}
          {entry.sectionRef && (
            <span style={{ fontSize: 10, color: 'var(--brand-400)', marginLeft: 8, fontWeight: 400 }}>
              {entry.sectionRef}
            </span>
          )}
        </div>

        {/* Value (or inline edit) */}
        <div style={{ fontSize: 13, color: 'var(--text-2)' }}>
          {editing ? (
            <div style={{ display: 'flex', gap: 4, alignItems: 'center' }} onClick={e => e.stopPropagation()}>
              <input
                value={editValue}
                onChange={e => setEditValue(e.target.value)}
                className="input-field"
                style={{ fontSize: 12, padding: '4px 8px', flex: 1 }}
                autoFocus
                onKeyDown={e => { if (e.key === 'Enter') handleSave(); if (e.key === 'Escape') setEditing(false); }}
              />
              <button onClick={handleSave} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 2 }}>
                <Check size={14} style={{ color: 'var(--success-400)' }} />
              </button>
              <button onClick={() => setEditing(false)} style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 2 }}>
                <X size={14} style={{ color: 'var(--text-4)' }} />
              </button>
            </div>
          ) : (
            <span>
              {entry.value || <span style={{ fontStyle: 'italic', color: 'var(--text-4)' }}>—</span>}
              {entry.userCorrected && (
                <span style={{ fontSize: 9, color: 'var(--brand-400)', marginLeft: 6 }}>corrected</span>
              )}
            </span>
          )}
        </div>

        {/* Importance badge */}
        <span style={{
          fontSize: 10, fontWeight: 600, padding: '2px 8px', borderRadius: 999,
          background: imp.bg, color: imp.color, textAlign: 'center',
        }}>
          {imp.label}
        </span>

        {/* Confidence dot */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
          <div style={{ width: 8, height: 8, borderRadius: '50%', background: conf }} />
          <span style={{ fontSize: 10, color: 'var(--text-4)' }}>{entry.extractionConfidence || '—'}</span>
        </div>

        {/* Consistency */}
        <span style={{
          fontSize: 10, color: entry.consistencyStatus === 'FAILED' ? 'var(--danger-400)'
            : entry.consistencyStatus === 'CONFLICTING_DUPLICATES' ? 'var(--warn-400)'
            : 'var(--text-4)',
        }}>
          {entry.consistencyStatus === 'PASSED' ? '✓' : entry.consistencyStatus === 'FAILED' ? '✗' : entry.consistencyStatus === 'CONFLICTING_DUPLICATES' ? '⚠' : '—'}
        </span>

        {/* Edit button */}
        <button
          onClick={e => { e.stopPropagation(); setEditing(true); setEditValue(entry.value || ''); }}
          style={{ background: 'none', border: 'none', cursor: 'pointer', padding: 2, opacity: 0.4 }}
          onMouseEnter={e => e.currentTarget.style.opacity = 1}
          onMouseLeave={e => e.currentTarget.style.opacity = 0.4}
        >
          <Pencil size={12} style={{ color: 'var(--text-3)' }} />
        </button>
      </div>

      {/* Expanded evidence */}
      {expanded && hasEvidence && (
        <div style={{ padding: '0 12px 12px 42px', background: 'var(--bg-0)' }}>
          <EvidenceQuote spans={entry.evidence} />
        </div>
      )}
    </div>
  );
}
