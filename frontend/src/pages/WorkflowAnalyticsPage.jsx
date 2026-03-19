import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { BarChart3, CheckCircle2, XCircle, Loader2, Clock, TrendingUp, Workflow, ArrowLeft } from 'lucide-react';
import api from '../api/client';

function StatCard({ label, value, sub, icon: Icon, color = 'text-primary' }) {
  return (
    <div className="card">
      <div className="flex items-start justify-between mb-3">
        <p className="text-xs text-text-muted">{label}</p>
        <div className={`w-8 h-8 rounded-lg bg-primary/10 flex items-center justify-center`}>
          <Icon className={`w-4 h-4 ${color}`} />
        </div>
      </div>
      <p className={`text-2xl font-bold ${color}`}>{value}</p>
      {sub && <p className="text-xs text-text-muted mt-1">{sub}</p>}
    </div>
  );
}

function MiniBarChart({ data, maxVal }) {
  if (!data || data.length === 0) {
    return <p className="text-xs text-text-muted text-center py-4">No data for this period</p>;
  }
  const max = maxVal || Math.max(...data.map(d => d.count), 1);
  return (
    <div className="flex items-end gap-1 h-24">
      {data.map((d, i) => (
        <div key={i} className="flex-1 flex flex-col items-center gap-1">
          <div
            className="w-full bg-primary/20 rounded-t-sm transition-all duration-500 hover:bg-primary/40"
            style={{ height: `${Math.max(2, (d.count / max) * 80)}px` }}
            title={`${d.date}: ${d.count} run${d.count !== 1 ? 's' : ''}`}
          />
          <span className="text-[9px] text-text-muted truncate w-full text-center">
            {d.date?.slice(5)}
          </span>
        </div>
      ))}
    </div>
  );
}

function formatDuration(ms) {
  if (!ms || ms === 0) return '—';
  if (ms < 60000) return `${Math.round(ms / 1000)}s`;
  return `${Math.round(ms / 60000)}m ${Math.round((ms % 60000) / 1000)}s`;
}

export default function WorkflowAnalyticsPage() {
  const navigate = useNavigate();
  const [analytics, setAnalytics] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    api.get('/workflows/analytics')
      .then(r => setAnalytics(r.data))
      .catch(e => setError(e.response?.data?.message || 'Failed to load analytics'))
      .finally(() => setLoading(false));
  }, []);

  if (loading) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Workflow Analytics</h1>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
          {[1,2,3,4].map(i => <div key={i} className="card h-24 animate-pulse bg-surface-el" />)}
        </div>
      </div>
    );
  }

  if (error) {
    return (
      <div>
        <h1 className="text-2xl font-bold mb-6">Workflow Analytics</h1>
        <div className="card border-l-4 border-danger bg-danger/5">
          <p className="text-danger text-sm">{error}</p>
        </div>
      </div>
    );
  }

  const a = analytics;

  return (
    <div>
      <div className="flex items-center gap-3 mb-6">
        <button onClick={() => navigate('/workflows')} className="text-text-muted hover:text-text-primary">
          <ArrowLeft className="w-5 h-5" />
        </button>
        <h1 className="text-2xl font-bold">Workflow Analytics</h1>
      </div>

      {/* Stat cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4 mb-6">
        <StatCard label="Total Runs" value={a.totalRuns} icon={Workflow} />
        <StatCard
          label="Completed"
          value={a.completedRuns}
          sub={`${a.completionRate}% success rate`}
          icon={CheckCircle2}
          color="text-success"
        />
        <StatCard
          label="Failed"
          value={a.failedRuns}
          icon={XCircle}
          color="text-danger"
        />
        <StatCard
          label="Avg Duration"
          value={formatDuration(a.avgDurationMs)}
          sub="per completed run"
          icon={Clock}
          color="text-warning"
        />
      </div>

      <div className="grid grid-cols-2 gap-6 mb-6">
        {/* Activity chart */}
        <div className="card">
          <div className="flex items-center gap-2 mb-4">
            <BarChart3 className="w-4 h-4 text-primary" />
            <h3 className="text-sm font-semibold">Activity (Last 7 Days)</h3>
          </div>
          <MiniBarChart data={a.byDay} />
        </div>

        {/* In-progress */}
        <div className="card">
          <div className="flex items-center gap-2 mb-4">
            <TrendingUp className="w-4 h-4 text-primary" />
            <h3 className="text-sm font-semibold">Live Status</h3>
          </div>
          <div className="space-y-3">
            {[
              { label: 'Completed', value: a.completedRuns, color: 'bg-success', total: a.totalRuns },
              { label: 'Failed', value: a.failedRuns, color: 'bg-danger', total: a.totalRuns },
              { label: 'Running', value: a.runningRuns, color: 'bg-warning', total: a.totalRuns },
            ].map(({ label, value, color, total }) => (
              <div key={label}>
                <div className="flex justify-between text-xs mb-1">
                  <span className="text-text-muted">{label}</span>
                  <span className="text-text-primary font-medium">{value}</span>
                </div>
                <div className="h-1.5 bg-surface-el rounded-full overflow-hidden">
                  <div
                    className={`h-full ${color} rounded-full transition-all duration-700`}
                    style={{ width: `${total > 0 ? (value / total) * 100 : 0}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Usage by workflow */}
      {a.byWorkflow?.length > 0 && (
        <div className="card">
          <div className="flex items-center gap-2 mb-4">
            <Workflow className="w-4 h-4 text-primary" />
            <h3 className="text-sm font-semibold">Usage by Workflow</h3>
          </div>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-border text-xs text-text-muted">
                <th className="text-left pb-2 font-medium">Workflow</th>
                <th className="text-right pb-2 font-medium">Total</th>
                <th className="text-right pb-2 font-medium">Completed</th>
                <th className="text-right pb-2 font-medium">Success Rate</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border">
              {a.byWorkflow.map((w, i) => {
                const rate = w.totalRuns > 0 ? Math.round(w.completedRuns / w.totalRuns * 100) : 0;
                return (
                  <tr key={i} className="hover:bg-surface-el/30">
                    <td className="py-2.5 font-medium text-text-primary">{w.name}</td>
                    <td className="py-2.5 text-right text-text-muted">{w.totalRuns}</td>
                    <td className="py-2.5 text-right text-success">{w.completedRuns}</td>
                    <td className="py-2.5 text-right">
                      <span className={`text-xs font-medium ${rate >= 80 ? 'text-success' : rate >= 50 ? 'text-warning' : 'text-danger'}`}>
                        {rate}%
                      </span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      )}

      {a.totalRuns === 0 && (
        <div className="card text-center py-10">
          <BarChart3 className="w-10 h-10 text-text-muted mx-auto mb-3" />
          <p className="text-text-muted text-sm">No runs yet. Run a workflow to see analytics.</p>
          <button onClick={() => navigate('/workflows')} className="btn-primary text-sm mt-4">
            Go to Workflows
          </button>
        </div>
      )}
    </div>
  );
}
