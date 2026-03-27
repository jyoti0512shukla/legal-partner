import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import { Loader2, ArrowRight, CheckCircle2, Clock, AlertTriangle, Users } from 'lucide-react';
import api from '../api/client';

const STATUS_COLORS = { IN_PROGRESS: 'text-warning', APPROVED: 'text-success', SENT: 'text-primary' };

export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [dashboard, setDashboard] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.get('/review-pipelines/dashboard')
      .then(r => setDashboard(r.data))
      .catch(() => setDashboard({ needsAction: [], teamActivity: [], recentlyCompleted: [] }))
      .finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 animate-spin text-text-muted" /></div>
  );

  const { needsAction = [], teamActivity = [], recentlyCompleted = [] } = dashboard || {};

  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-8">
        <h1 className="text-2xl font-bold font-display text-text-primary">
          {getGreeting()}, {user?.displayName || user?.email?.split('@')[0]}
        </h1>
        <p className="text-text-muted text-sm mt-1">
          {needsAction.length > 0
            ? `You have ${needsAction.length} item${needsAction.length > 1 ? 's' : ''} waiting for your review.`
            : 'All caught up. No items need your attention right now.'}
        </p>
      </div>

      {/* Needs Your Action */}
      <Section title="Needs Your Action" icon={AlertTriangle} count={needsAction.length} color="text-warning">
        {needsAction.length === 0 ? (
          <EmptyState text="Nothing needs your attention right now." />
        ) : (
          needsAction.map(r => <ReviewCard key={r.id} review={r} navigate={navigate} showAction />)
        )}
      </Section>

      {/* Team Activity */}
      <Section title="Team Activity" icon={Users} count={teamActivity.length} color="text-primary">
        {teamActivity.length === 0 ? (
          <EmptyState text="No active reviews on your matters." />
        ) : (
          teamActivity.slice(0, 5).map(r => <ReviewCard key={r.id} review={r} navigate={navigate} />)
        )}
      </Section>

      {/* Recently Completed */}
      <Section title="Recently Completed" icon={CheckCircle2} count={recentlyCompleted.length} color="text-success">
        {recentlyCompleted.length === 0 ? (
          <EmptyState text="No completed reviews yet." />
        ) : (
          recentlyCompleted.slice(0, 5).map(r => <ReviewCard key={r.id} review={r} navigate={navigate} completed />)
        )}
      </Section>
    </div>
  );
}

function Section({ title, icon: Icon, count, color, children }) {
  return (
    <div className="mb-8">
      <div className="flex items-center gap-2 mb-3">
        <Icon className={`w-4 h-4 ${color}`} />
        <h2 className="text-sm font-semibold text-text-primary font-display">{title}</h2>
        {count > 0 && (
          <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium bg-surface-el ${color}`}>{count}</span>
        )}
      </div>
      <div className="space-y-2">{children}</div>
    </div>
  );
}

function ReviewCard({ review: r, navigate, showAction, completed }) {
  const progress = r.totalStages > 0 ? Math.round((r.currentStageOrder / r.totalStages) * 100) : 0;

  return (
    <div onClick={() => navigate(`/matters/${r.matterId}`)}
      className="card p-4 cursor-pointer hover:border-primary/30 transition-colors">
      <div className="flex items-start justify-between gap-3">
        <div className="flex-1 min-w-0">
          <div className="flex items-center gap-2 mb-1">
            <span className="text-sm font-medium text-text-primary">{r.matterName}</span>
            {r.documentName && <span className="text-[10px] text-text-muted">· {r.documentName}</span>}
          </div>
          <div className="flex items-center gap-2 text-xs text-text-muted">
            <span>{r.pipelineName}</span>
            <span>·</span>
            <span className={completed ? 'text-success' : STATUS_COLORS[r.status] || ''}>
              {completed ? 'Completed' : r.currentStageName}
            </span>
            {r.requiredRole && !completed && (
              <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-surface-el">{r.requiredRole}</span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2 shrink-0">
          {!completed && (
            <div className="flex items-center gap-1.5">
              <div className="w-16 h-1.5 bg-surface-el rounded-full overflow-hidden">
                <div className="h-full bg-primary rounded-full transition-all" style={{ width: `${progress}%` }} />
              </div>
              <span className="text-[10px] text-text-muted">{r.currentStageOrder}/{r.totalStages}</span>
            </div>
          )}
          {showAction && (
            <span className="btn-primary text-[10px] px-2 py-1">Review</span>
          )}
          <ArrowRight className="w-4 h-4 text-text-muted" />
        </div>
      </div>
    </div>
  );
}

function EmptyState({ text }) {
  return <p className="text-text-muted text-xs text-center py-4">{text}</p>;
}

function getGreeting() {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}
