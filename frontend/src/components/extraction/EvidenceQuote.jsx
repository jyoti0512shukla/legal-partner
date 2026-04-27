export default function EvidenceQuote({ spans }) {
  if (!spans || spans.length === 0) return null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 6, marginTop: 8 }}>
      {spans.filter(s => s.text).map((span, i) => (
        <div key={i} style={{
          fontFamily: 'var(--font-doc)', fontSize: 12.5, fontStyle: 'italic',
          color: 'var(--text-2)', paddingLeft: 10, borderLeft: '2px solid var(--line-2)',
          lineHeight: 1.5,
        }}>
          "{span.text}"
          {span.charStart >= 0 && (
            <span style={{ fontSize: 10, color: 'var(--text-4)', marginLeft: 8 }}>
              [pos {span.charStart}–{span.charEnd}]
            </span>
          )}
        </div>
      ))}
    </div>
  );
}
