const REASON_LABELS = {
  NOT_MENTIONED: { label: 'Not Found', color: 'var(--danger-400)', bg: 'var(--danger-bg)' },
  UNCLEAR: { label: 'Unclear', color: 'var(--warn-400)', bg: 'var(--warn-bg)' },
  NOT_APPLICABLE: { label: 'N/A', color: 'var(--text-4)', bg: 'var(--bg-3)' },
  AMBIGUOUS: { label: 'Ambiguous', color: 'var(--warn-400)', bg: 'var(--warn-bg)' },
  PARTIAL_SCAN: { label: 'Partial Scan', color: 'var(--text-3)', bg: 'var(--bg-3)' },
  POSSIBLE_MISSING: { label: 'Possibly Missing', color: 'var(--warn-400)', bg: 'var(--warn-bg)' },
};

export default function GapEntry({ entry }) {
  const reason = REASON_LABELS[entry.reasonCode] || REASON_LABELS.NOT_MENTIONED;
  const fieldLabel = (entry.canonicalField || entry.rawField || '').replace(/_/g, ' ');

  return (
    <div style={{
      display: 'grid', gridTemplateColumns: '20px 1fr 1.2fr 80px 70px 70px 28px',
      gap: 10, alignItems: 'center', padding: '10px 12px',
      borderBottom: '1px solid var(--line-1)',
      background: 'var(--bg-0)', opacity: 0.7,
    }}>
      <div />
      <div style={{ fontSize: 13, fontWeight: 500, textTransform: 'capitalize', color: 'var(--text-3)' }}>
        {fieldLabel}
      </div>
      <div style={{ fontSize: 12, fontStyle: 'italic', color: 'var(--text-4)' }}>
        {entry.reasonCode === 'NOT_APPLICABLE' ? 'Not relevant for this contract type' : 'Not found in document'}
      </div>
      <span style={{
        fontSize: 10, fontWeight: 600, padding: '2px 8px', borderRadius: 999,
        background: reason.bg, color: reason.color, textAlign: 'center',
      }}>
        {reason.label}
      </span>
      <div />
      <div />
      <div />
    </div>
  );
}
