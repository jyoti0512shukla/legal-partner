import { useState } from 'react';
import { ChevronDown, ChevronRight } from 'lucide-react';
import ExtractionEntryRow from './ExtractionEntryRow';

const BUCKET_LABELS = {
  PARTIES: 'Parties',
  TERM: 'Term & Duration',
  COMMERCIAL: 'Commercial Terms',
  LEGAL: 'Legal Protections',
  OPERATIONAL: 'Operational',
  REGULATORY: 'Regulatory & Compliance',
  OTHER: 'Other',
};

const BUCKET_ORDER = ['PARTIES', 'COMMERCIAL', 'TERM', 'LEGAL', 'OPERATIONAL', 'REGULATORY', 'OTHER'];

export default function BucketSection({ bucket, entries, onCorrect }) {
  const [open, setOpen] = useState(true);
  const label = BUCKET_LABELS[bucket] || bucket;
  const hasIssues = entries.some(e => e.consistencyStatus === 'FAILED' || e.consistencyStatus === 'CONFLICTING_DUPLICATES');
  const gapCount = entries.filter(e => e.reasonCode).length;

  return (
    <div className="card" style={{ marginBottom: 10, overflow: 'hidden' }}>
      <div
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'flex', alignItems: 'center', gap: 10, padding: '10px 14px',
          cursor: 'pointer', borderBottom: open ? '1px solid var(--line-1)' : 'none',
        }}
      >
        {open ? <ChevronDown size={14} style={{ color: 'var(--text-3)' }} /> : <ChevronRight size={14} style={{ color: 'var(--text-3)' }} />}
        <span style={{ fontWeight: 600, fontSize: 13, flex: 1 }}>{label}</span>
        <span className="tiny muted">{entries.length} field{entries.length !== 1 ? 's' : ''}</span>
        {gapCount > 0 && (
          <span style={{ fontSize: 10, padding: '1px 6px', borderRadius: 999, background: 'var(--warn-bg)', color: 'var(--warn-400)' }}>
            {gapCount} gap{gapCount !== 1 ? 's' : ''}
          </span>
        )}
        {hasIssues && (
          <span style={{ fontSize: 10, padding: '1px 6px', borderRadius: 999, background: 'var(--danger-bg)', color: 'var(--danger-400)' }}>
            issue
          </span>
        )}
      </div>
      {open && (
        <div>
          {/* Column headers */}
          <div style={{
            display: 'grid', gridTemplateColumns: '20px 1fr 1.2fr 80px 70px 70px 28px',
            gap: 10, padding: '6px 12px', fontSize: 10, fontWeight: 600,
            textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--text-4)',
            borderBottom: '1px solid var(--line-1)',
          }}>
            <div />
            <div>Field</div>
            <div>Value</div>
            <div>Importance</div>
            <div>Confidence</div>
            <div>Check</div>
            <div />
          </div>
          {entries.map((entry, i) => (
            <ExtractionEntryRow key={i} entry={entry} onCorrect={onCorrect} />
          ))}
        </div>
      )}
    </div>
  );
}

export { BUCKET_ORDER };
