import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { FileText, Database, ShieldAlert, Brain, Upload, Search, BarChart3 } from 'lucide-react';
import api from '../api/client';
import LoadingSkeleton from '../components/shared/LoadingSkeleton';

export default function DashboardPage() {
  const navigate = useNavigate();
  const [stats, setStats] = useState(null);
  const [activity, setActivity] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get('/documents/stats').catch(() => ({ data: { totalDocuments: 0, totalSegments: 0 } })),
      api.get('/audit/recent').catch(() => ({ data: [] })),
    ]).then(([statsRes, activityRes]) => {
      setStats(statsRes.data);
      setActivity(activityRes.data);
    }).finally(() => setLoading(false));
  }, []);

  if (loading) return <LoadingSkeleton rows={6} />;

  const kpis = [
    { icon: FileText, label: 'Documents', value: stats?.totalDocuments || 0, color: 'text-primary' },
    { icon: Database, label: 'Segments', value: stats?.totalSegments || 0, color: 'text-success' },
    { icon: ShieldAlert, label: 'Avg Risk', value: '—', color: 'text-warning' },
    { icon: Brain, label: 'Queries', value: activity.filter(a => a.action === 'AI_QUERY').length, color: 'text-primary' },
  ];

  return (
    <div>
      <h1 className="text-2xl font-bold mb-6">Dashboard</h1>

      <div className="grid grid-cols-4 gap-4 mb-8">
        {kpis.map(({ icon: Icon, label, value, color }) => (
          <div key={label} className="card">
            <div className="flex items-center justify-between mb-3">
              <Icon className={`w-5 h-5 ${color}`} />
            </div>
            <p className="text-3xl font-bold text-text-primary">{value}</p>
            <p className="text-sm text-text-muted mt-1">{label}</p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-2 gap-6 mb-8">
        <div className="card">
          <h2 className="text-lg font-semibold mb-4">Recent Activity</h2>
          {activity.length === 0 ? (
            <p className="text-text-muted text-sm">No activity yet. Upload a document to get started.</p>
          ) : (
            <div className="space-y-3">
              {activity.slice(0, 8).map(a => (
                <div key={a.id} className="flex items-center gap-3 text-sm">
                  <div className={`w-2 h-2 rounded-full ${a.success ? 'bg-success' : 'bg-danger'}`} />
                  <span className="text-text-secondary flex-1">
                    <span className="text-text-primary font-medium capitalize">{a.username}</span>
                    {' '}{formatAction(a.action)}
                  </span>
                  <span className="text-text-muted text-xs">{timeAgo(a.timestamp)}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="card">
          <h2 className="text-lg font-semibold mb-4">Corpus Overview</h2>
          {stats?.documentsByJurisdiction && Object.keys(stats.documentsByJurisdiction).length > 0 ? (
            <div className="space-y-2">
              {Object.entries(stats.documentsByJurisdiction).map(([j, count]) => (
                <div key={j} className="flex justify-between text-sm">
                  <span className="text-text-secondary">{j}</span>
                  <span className="text-text-primary font-medium">{count} docs</span>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-text-muted text-sm">Upload documents to see jurisdiction breakdown.</p>
          )}
        </div>
      </div>

      <h2 className="text-lg font-semibold mb-4">Quick Actions</h2>
      <div className="grid grid-cols-3 gap-4">
        {[
          { icon: Upload, label: 'Upload Document', to: '/documents' },
          { icon: Search, label: 'New Query', to: '/intelligence' },
          { icon: BarChart3, label: 'Run Risk Report', to: '/risk' },
        ].map(({ icon: Icon, label, to }) => (
          <button key={to} onClick={() => navigate(to)}
            className="card hover:border-primary/50 transition-colors group cursor-pointer text-left">
            <Icon className="w-6 h-6 text-text-muted group-hover:text-primary mb-2 transition-colors" />
            <p className="font-medium text-text-primary">{label}</p>
          </button>
        ))}
      </div>
    </div>
  );
}

function formatAction(action) {
  const map = {
    DOCUMENT_UPLOAD: 'uploaded a document',
    AI_QUERY: 'queried contracts',
    AI_COMPARE: 'compared documents',
    RISK_ASSESSMENT: 'ran risk assessment',
    DOCUMENT_VIEW: 'viewed documents',
    AUDIT_VIEW: 'viewed audit logs',
  };
  return map[action] || action.toLowerCase().replace('_', ' ');
}

function timeAgo(ts) {
  const diff = Date.now() - new Date(ts).getTime();
  const mins = Math.floor(diff / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins}m ago`;
  const hrs = Math.floor(mins / 60);
  if (hrs < 24) return `${hrs}h ago`;
  return `${Math.floor(hrs / 24)}d ago`;
}
