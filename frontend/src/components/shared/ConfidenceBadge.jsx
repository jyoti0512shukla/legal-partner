export default function ConfidenceBadge({ level }) {
  const config = {
    HIGH: { segments: 5, color: 'bg-success', text: 'text-success', label: 'High' },
    MEDIUM: { segments: 3, color: 'bg-warning', text: 'text-warning', label: 'Medium' },
    LOW: { segments: 1, color: 'bg-danger', text: 'text-danger', label: 'Low' },
  };
  const c = config[level] || config.MEDIUM;

  return (
    <div className="flex items-center gap-2">
      <div className="flex gap-0.5">
        {Array.from({ length: 5 }).map((_, i) => (
          <div key={i} className={`w-3 h-1.5 rounded-full ${i < c.segments ? c.color : 'bg-border'}`} />
        ))}
      </div>
      <span className={`text-xs font-medium ${c.text}`}>{c.label}</span>
    </div>
  );
}
