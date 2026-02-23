export default function LoadingSkeleton({ rows = 3, className = '' }) {
  return (
    <div className={`space-y-4 ${className}`}>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="animate-pulse">
          <div className="h-4 bg-surface-el rounded w-3/4 mb-2" />
          <div className="h-3 bg-surface-el rounded w-1/2" />
        </div>
      ))}
    </div>
  );
}
