import { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../hooks/useAuth';
import {
  Loader2, ArrowRight, CheckCircle2, Clock, AlertTriangle, Users,
  Brain, Shield, GitPullRequest, FileText
} from 'lucide-react';
import api from '../api/client';

const STATUS_COLORS = { IN_PROGRESS: 'text-warning', APPROVED: 'text-success', SENT: 'text-primary' };
const TABS = [
  { id: 'ai', label: 'AI Insights', icon: Brain },
  { id: 'reviews', label: 'Reviews', icon: GitPullRequest },
];

export default function DashboardPage() {
  const { user } = useAuth();
  const navigate = useNavigate();
  const [tab, setTab] = useState('ai');
  const [reviewData, setReviewData] = useState(null);
  const [aiData, setAiData] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    Promise.all([
      api.get('/review-pipelines/dashboard').catch(() => ({ data: { needsAction: [], teamActivity: [], recentlyCompleted: [] } })),
      api.get('/findings/dashboard').catch(() => ({ data: { highCount: 0, mediumCount: 0, lowCount: 0, unreviewedCount: 0, totalCount: 0, recentFindings: [], matterRiskSummary: [] } })),
    ]).then(([reviewRes, aiRes]) => {
      setReviewData(reviewRes.data);
      setAiData(aiRes.data);
    }).finally(() => setLoading(false));
  }, []);

  if (loading) return (
    <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 animate-spin text-text-muted" /></div>
  );

  const { needsAction = [], teamActivity = [], recentlyCompleted = [] } = reviewData || {};
  const { highCount = 0, mediumCount = 0, lowCount = 0, unreviewedCount = 0, totalCount = 0, recentFindings = [], matterRiskSummary = [] } = aiData || {};

  // Summary line
  const actionItems = [];
  if (unreviewedCount > 0) actionItems.push(`${unreviewedCount} unreviewed finding${unreviewedCount > 1 ? 's' : ''}`);
  if (needsAction.length > 0) actionItems.push(`${needsAction.length} review${needsAction.length > 1 ? 's' : ''} awaiting action`);

  return (
    <div className="max-w-5xl mx-auto">
      {/* Greeting */}
      <div className="mb-6">
        <h1 className="text-2xl font-bold font-display text-text-primary">
          {getGreeting()}, {user?.displayName || user?.email?.split('@')[0]}
        </h1>
        <p className="text-text-muted text-sm mt-1">
          {actionItems.length > 0
            ? actionItems.join(' and ') + ' need your attention.'
            : 'All caught up. No items need your attention right now.'}
        </p>
      </div>

      {/* Summary cards */}
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mb-6">
        <SummaryCard label="HIGH Risk" value={highCount} color="text-danger" bg="bg-danger/10" icon={AlertTriangle} />
        <SummaryCard label="MEDIUM Risk" value={mediumCount} color="text-warning" bg="bg-warning/10" icon={Shield} />
        <SummaryCard label="Unreviewed" value={unreviewedCount} color="text-primary" bg="bg-primary/10" icon={Clock} />
        <SummaryCard label="Pending Reviews" value={needsAction.length} color="text-warning" bg="bg-warning/10" icon={GitPullRequest} />
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-border mb-6">
        {TABS.map(t => {
          const Icon = t.icon;
          return (
            <button key={t.id} onClick={() => setTab(t.id)}
              className={`flex items-center gap-1.5 px-4 py-2.5 text-sm font-medium border-b-2 transition-colors ${
                tab === t.id ? 'border-primary text-primary' : 'border-transparent text-text-muted hover:text-text-primary'
              }`}>
              <Icon className="w-4 h-4" /> {t.label}
            </button>
          );
        })}
      </div>

      {/* ── AI Insights Tab ──────────────────────────────────────── */}
      {tab === 'ai' && (
        <div className="space-y-6">
          {/* Matters at risk */}
          {matterRiskSummary.length > 0 && (
            <Section title="Matters Needing Attention" icon={AlertTriangle} color="text-warning">
              <div className="space-y-2">
                {matterRiskSummary.map(m => (
                  <div key={m.matterId} onClick={() => navigate(`/matters/${m.matterId}`)}
                    className="card p-3 flex items-center justify-between cursor-pointer hover:border-primary/30 transition-colors">
                    <div className="flex items-center gap-3">
                      <FileText className="w-4 h-4 text-text-muted" />
                      <span className="text-sm font-medium text-text-primary">{m.matterName}</span>
                    </div>
                    <div className="flex items-center gap-3">
                      {m.high > 0 && <SeverityPill severity="HIGH" count={m.high} />}
                      {m.medium > 0 && <SeverityPill severity="MEDIUM" count={m.medium} />}
                      {m.low > 0 && <SeverityPill severity="LOW" count={m.low} />}
                      <ArrowRight className="w-4 h-4 text-text-muted" />
                    </div>
                  </div>
                ))}
              </div>
            </Section>
          )}

          {/* Recent findings */}
          <Section title="Recent Findings" icon={Brain} color="text-primary" count={recentFindings.length}>
            {recentFindings.length === 0 ? (
              <EmptyState text="No AI findings yet. Upload documents and run analysis to see insights." />
            ) : (
              <div className="space-y-2">
                {recentFindings.map(f => (
                  <div key={f.id} onClick={() => navigate(`/matters/${f.matterId}`)}
                    className="card p-3 cursor-pointer hover:border-primary/30 transition-colors">
                    <div className="flex items-start justify-between gap-3">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1">
                          <SeverityDot severity={f.severity} />
                          <span className="text-sm font-medium text-text-primary">{f.title}</span>
                        </div>
                        <p className="text-xs text-text-secondary line-clamp-2">{f.description}</p>
                        <div className="flex items-center gap-2 mt-1.5 text-[10px] text-text-muted">
                          {f.documentName && <span>{f.documentName}</span>}
                          {f.clauseType && <><span>·</span><span>{f.clauseType.replace(/_/g, ' ')}</span></>}
                          <span>·</span>
                          <span>{new Date(f.createdAt).toLocaleDateString()}</span>
                        </div>
                      </div>
                      <div className="flex items-center gap-2 shrink-0">
                        <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${
                          f.status === 'NEW' ? 'bg-primary/10 text-primary' :
                          f.status === 'ACCEPTED' ? 'bg-success/10 text-success' :
                          f.status === 'FLAGGED' ? 'bg-danger/10 text-danger' :
                          'bg-surface-el text-text-muted'
                        }`}>{f.status}</span>
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </Section>

          {/* Overall stats */}
          {totalCount > 0 && (
            <div className="card p-4">
              <p className="text-[10px] text-text-muted font-medium mb-3">ALL-TIME FINDINGS</p>
              <div className="flex items-center gap-6">
                <div className="flex items-center gap-4">
                  <StatItem label="High" value={highCount} color="text-danger" />
                  <StatItem label="Medium" value={mediumCount} color="text-warning" />
                  <StatItem label="Low" value={lowCount} color="text-success" />
                </div>
                <div className="h-8 w-px bg-border" />
                <StatItem label="Total" value={totalCount} color="text-text-primary" />
                <StatItem label="Unreviewed" value={unreviewedCount} color="text-primary" />
              </div>
            </div>
          )}
        </div>
      )}

      {/* ── Reviews Tab ──────────────────────────────────────────── */}
      {tab === 'reviews' && (
        <div className="space-y-6">
          <Section title="Needs Your Action" icon={AlertTriangle} count={needsAction.length} color="text-warning">
            {needsAction.length === 0 ? (
              <EmptyState text="Nothing needs your attention right now." />
            ) : (
              needsAction.map(r => <ReviewCard key={r.id} review={r} navigate={navigate} showAction />)
            )}
          </Section>

          <Section title="Team Activity" icon={Users} count={teamActivity.length} color="text-primary">
            {teamActivity.length === 0 ? (
              <EmptyState text="No active reviews on your matters." />
            ) : (
              teamActivity.slice(0, 5).map(r => <ReviewCard key={r.id} review={r} navigate={navigate} />)
            )}
          </Section>

          <Section title="Recently Completed" icon={CheckCircle2} count={recentlyCompleted.length} color="text-success">
            {recentlyCompleted.length === 0 ? (
              <EmptyState text="No completed reviews yet." />
            ) : (
              recentlyCompleted.slice(0, 5).map(r => <ReviewCard key={r.id} review={r} navigate={navigate} completed />)
            )}
          </Section>
        </div>
      )}

    </div>
  );
}

// ── Shared Components ─────────────────────────────────────────────────

function SummaryCard({ label, value, color, bg, icon: Icon }) {
  return (
    <div className={`${bg} rounded-xl p-4 flex items-center gap-3`}>
      <Icon className={`w-5 h-5 ${color} shrink-0`} />
      <div>
        <p className={`text-xl font-bold font-display ${color}`}>{value}</p>
        <p className="text-[10px] text-text-muted font-medium">{label}</p>
      </div>
    </div>
  );
}

function Section({ title, icon: Icon, count, color, children }) {
  return (
    <div>
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
          {showAction && <span className="btn-primary text-[10px] px-2 py-1">Review</span>}
          <ArrowRight className="w-4 h-4 text-text-muted" />
        </div>
      </div>
    </div>
  );
}

function SeverityDot({ severity }) {
  const color = severity === 'HIGH' ? 'text-danger' : severity === 'MEDIUM' ? 'text-warning' : 'text-success';
  return <span className={`text-sm ${color}`}>{severity === 'HIGH' ? '🔴' : severity === 'MEDIUM' ? '🟡' : '🟢'}</span>;
}

function SeverityPill({ severity, count }) {
  const styles = {
    HIGH: 'bg-danger/10 text-danger',
    MEDIUM: 'bg-warning/10 text-warning',
    LOW: 'bg-success/10 text-success',
  };
  return (
    <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${styles[severity] || ''}`}>
      {count} {severity}
    </span>
  );
}

function StatItem({ label, value, color }) {
  return (
    <div className="text-center">
      <p className={`text-lg font-bold font-display ${color}`}>{value}</p>
      <p className="text-[10px] text-text-muted">{label}</p>
    </div>
  );
}

function EmptyState({ text }) {
  return <p className="text-text-muted text-xs text-center py-6">{text}</p>;
}


function getGreeting() {
  const h = new Date().getHours();
  if (h < 12) return 'Good morning';
  if (h < 17) return 'Good afternoon';
  return 'Good evening';
}
