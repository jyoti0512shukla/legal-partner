const STATUS_CONFIG = {
  DRAFT:             { label: 'Draft',           color: 'var(--text-3)',     bg: 'var(--bg-3)' },
  INTERNAL_REVIEW:   { label: 'In Review',       color: 'var(--info-400)',   bg: 'var(--info-bg, rgba(56,139,253,0.1))' },
  APPROVED:          { label: 'Approved',        color: 'var(--success-400)',bg: 'var(--success-bg, rgba(63,185,80,0.1))' },
  NEGOTIATING:       { label: 'Negotiating',     color: 'var(--warn-400)',   bg: 'var(--warn-bg, rgba(216,154,58,0.1))' },
  PENDING_SIGNATURE: { label: 'Pending Signature',color: '#a371f7',         bg: 'rgba(163,113,247,0.1)' },
  EXECUTED:          { label: 'Executed',         color: '#6e40c9',          bg: 'rgba(110,64,201,0.1)' },
  ACTIVE:            { label: 'Active',           color: 'var(--success-500)',bg: 'var(--success-bg, rgba(63,185,80,0.15))' },
  EXPIRING:          { label: 'Expiring',         color: '#d29922',          bg: 'rgba(210,153,34,0.15)' },
  EXPIRED:           { label: 'Expired',          color: 'var(--danger-400)',bg: 'var(--danger-bg, rgba(217,83,78,0.1))' },
  RENEWED:           { label: 'Renewed',          color: '#39d2c0',          bg: 'rgba(57,210,192,0.1)' },
  TERMINATED:        { label: 'Terminated',       color: '#8b949e',          bg: 'rgba(139,148,158,0.1)' },
};

export default function StatusBadge({ status, size = 'sm' }) {
  if (!status) return null;
  const cfg = STATUS_CONFIG[status] || { label: status, color: 'var(--text-3)', bg: 'var(--bg-3)' };
  const fontSize = size === 'sm' ? 10 : 11;
  const padding = size === 'sm' ? '2px 7px' : '3px 9px';
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 4,
      fontSize, fontWeight: 600, letterSpacing: '0.03em',
      padding, borderRadius: 'var(--r-sm, 4px)',
      color: cfg.color, background: cfg.bg,
      whiteSpace: 'nowrap', textTransform: 'uppercase',
    }}>
      <span style={{ width: 6, height: 6, borderRadius: '50%', background: cfg.color, flexShrink: 0 }} />
      {cfg.label}
    </span>
  );
}
