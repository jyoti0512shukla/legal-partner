import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate, Link } from 'react-router-dom';
import {
  ArrowLeft, FileText, FileEdit, Shield, Workflow, Users, Clock, Plus, Upload, Trash2, X,
  Loader2, CheckCircle2, XCircle, AlertTriangle, Edit2, Play,
  GitPullRequest, ChevronRight, RotateCcw, Flag, Send, MessageSquare
} from 'lucide-react';
import { useAuth } from '../hooks/useAuth';
import api from '../api/client';
import MatterFindingsPanel from '../components/MatterFindingsPanel';

const DEAL_TYPES = ['SAAS_ACQUISITION', 'M_AND_A', 'NDA', 'COMMERCIAL_LEASE', 'FINANCING', 'IP_LICENSE', 'EMPLOYMENT', 'GENERAL'];
const PRACTICE_AREAS = ['CORPORATE', 'LITIGATION', 'IP', 'TAX', 'REAL_ESTATE', 'LABOR', 'BANKING', 'REGULATORY', 'OTHER'];
const STATUS_COLORS = { ACTIVE: 'bg-success/10 text-success', CLOSED: 'bg-surface-el text-text-muted', ARCHIVED: 'bg-surface-el text-text-muted' };
const DOC_STATUS_COLORS = { INDEXED: 'text-success', PENDING: 'text-warning', PROCESSING: 'text-warning', FAILED: 'text-danger' };
const MEMBER_ROLES = ['LEAD_PARTNER', 'PARTNER', 'ASSOCIATE', 'PARALEGAL', 'CLIENT_CONTACT', 'EXTERNAL'];
const ROLE_COLORS = {
  LEAD_PARTNER: 'bg-primary/10 text-primary', PARTNER: 'bg-primary/10 text-primary',
  ASSOCIATE: 'bg-warning/10 text-warning', PARALEGAL: 'bg-surface-el text-text-muted',
  CLIENT_CONTACT: 'bg-success/10 text-success', EXTERNAL: 'bg-surface-el text-text-muted',
};

const TABS = [
  { id: 'documents', label: 'Documents', icon: FileText },
  { id: 'findings', label: 'Findings', icon: Shield },
  { id: 'workflows', label: 'AI Agents', icon: Workflow },
  { id: 'team', label: 'Team', icon: Users },
  { id: 'activity', label: 'Activity', icon: Clock },
];

export default function MatterDetailPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useAuth();
  const [matter, setMatter] = useState(null);
  const [loading, setLoading] = useState(true);
  const [tab, setTab] = useState('documents');
  const [team, setTeam] = useState([]);
  const [myRole, setMyRole] = useState(null);
  const [pipelines, setPipelines] = useState([]);

  const isAdmin = user?.role === 'ROLE_ADMIN';
  const isPartner = user?.role === 'ROLE_PARTNER';
  const canManage = isAdmin || myRole === 'LEAD_PARTNER';
  const canEdit = isAdmin || isPartner || myRole === 'LEAD_PARTNER';
  const canReviewFindings = isAdmin || isPartner || myRole === 'LEAD_PARTNER' || myRole === 'PARTNER';

  const fetchMatter = useCallback(() => {
    api.get(`/matters/${id}`).then(r => setMatter(r.data)).catch(() => navigate('/matters'));
  }, [id, navigate]);

  const fetchTeam = useCallback(() => {
    api.get(`/matters/${id}/team`).then(r => {
      setTeam(r.data || []);
      const me = (r.data || []).find(m => m.email === user?.email || m.userId === user?.id);
      setMyRole(me ? me.matterRole : null);
    }).catch(() => {});
  }, [id, user]);

  useEffect(() => {
    api.get(`/matters/${id}`)
      .then(r => {
        setMatter(r.data);
        // Team fetch is separate — don't fail the whole page if team endpoint errors
        api.get(`/matters/${id}/team`)
          .then(teamRes => {
            setTeam(teamRes.data || []);
            const me = (teamRes.data || []).find(m => m.email === user?.email || m.userId === user?.id);
            setMyRole(me ? me.matterRole : null);
          })
          .catch(() => setTeam([]));
      })
      .catch(() => navigate('/matters'))
      .finally(() => setLoading(false));
    api.get('/review-pipelines').then(r => setPipelines(r.data || [])).catch(() => {});
  }, [id, user, navigate]);

  const handleStatusChange = async (status) => {
    await api.patch(`/matters/${id}/status?status=${status}`);
    fetchMatter();
  };

  const handlePipelineChange = async (pipelineId) => {
    await api.patch(`/matters/${id}/review-pipeline?${pipelineId ? `pipelineId=${pipelineId}` : ''}`);
    fetchMatter();
  };

  const findingSummary = matter ? { count: matter.findingCount || 0 } : null;

  if (loading) return <div className="flex items-center justify-center py-20"><Loader2 className="w-6 h-6 animate-spin text-text-muted" /></div>;
  if (!matter) return null;

  return (
    <div className="max-w-6xl mx-auto">
      {/* Header */}
      <div className="mb-6">
        <Link to="/matters" className="flex items-center gap-1 text-sm text-text-muted hover:text-text-primary mb-3">
          <ArrowLeft className="w-4 h-4" /> Back to Matters
        </Link>

        <div className="flex items-start justify-between gap-4">
          <div>
            <div className="flex items-center gap-3 mb-1">
              <h1 className="text-2xl font-bold text-text-primary">{matter.name}</h1>
              <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${STATUS_COLORS[matter.status] || ''}`}>
                {matter.status}
              </span>
            </div>
            <div className="flex items-center gap-2 text-sm text-text-muted flex-wrap">
              <span>{matter.matterRef}</span>
              <span>·</span>
              <span>{matter.clientName}</span>
              {matter.practiceArea && <><span>·</span><span>{matter.practiceArea.replace('_', ' ')}</span></>}
              {matter.dealType && <><span>·</span><span className="text-primary">{matter.dealType.replace(/_/g, ' ')}</span></>}
            </div>
            {matter.description && <p className="text-sm text-text-secondary mt-2">{matter.description}</p>}
          </div>

          <div className="flex items-center gap-2 shrink-0">
            <button
              onClick={() => navigate(`/draft?matterId=${id}`)}
              className="btn-primary text-xs py-1.5 px-3 flex items-center gap-1.5"
              title="Generate a new draft in the context of this matter"
            >
              <FileEdit className="w-3.5 h-3.5" /> New Draft
            </button>
            {canEdit && (
              <select value={matter.status} onChange={e => handleStatusChange(e.target.value)}
                className="input-field text-xs py-1">
                <option value="ACTIVE">Active</option>
                <option value="CLOSED">Closed</option>
                <option value="ARCHIVED">Archived</option>
              </select>
            )}
          </div>
        </div>

        {/* Stats bar */}
        <div className="flex items-center gap-3 mt-3 flex-wrap">
          <span className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-full bg-primary/10 text-primary">
            <FileText className="w-3.5 h-3.5" /> {matter.documentCount} docs
          </span>
          {findingSummary && findingSummary.count > 0 && (
            <span className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-full bg-warning/10 text-warning">
              <Shield className="w-3.5 h-3.5" /> {findingSummary.count} findings
            </span>
          )}
          <span className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-full bg-surface-el text-text-primary">
            <Users className="w-3.5 h-3.5" /> {team.length} members
          </span>
          <span className="flex items-center gap-1.5 text-xs font-medium px-2.5 py-1 rounded-full bg-surface-el text-text-primary">
            <GitPullRequest className="w-3.5 h-3.5" />
            <span className="text-text-muted">Review:</span>
            {canEdit ? (
              <select value={matter.reviewPipelineId || ''}
                onChange={e => handlePipelineChange(e.target.value || null)}
                className="bg-transparent border-none text-xs font-medium text-text-primary cursor-pointer p-0">
                <option value="">None</option>
                {pipelines.map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
              </select>
            ) : (
              <span>{matter.reviewPipelineName || 'None'}</span>
            )}
          </span>
        </div>
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

      {/* Tab content */}
      {tab === 'documents' && <DocumentsTab matterId={id} canUpload={true} onDocUploaded={fetchMatter}
        defaultPipelineId={matter.reviewPipelineId} defaultPipelineName={matter.reviewPipelineName} pipelines={pipelines} />}
      {tab === 'findings' && <MatterFindingsPanel matterId={id} readOnly={!canReviewFindings} />}
      {tab === 'workflows' && <WorkflowsTab matterId={id} matterRef={matter.matterRef} matterName={matter.name} />}
      {tab === 'team' && <TeamTab matterId={id} team={team} canManage={canManage} onTeamChanged={fetchTeam} />}
      {tab === 'activity' && <ActivityTab matterId={id} />}
    </div>
  );
}

// ── Documents Tab ──────────────────────────────────────────────────────

function DocumentsTab({ matterId, canUpload, onDocUploaded, defaultPipelineId, defaultPipelineName, pipelines }) {
  const [docs, setDocs] = useState([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [reviews, setReviews] = useState([]);
  const [reviewModal, setReviewModal] = useState(null); // doc object for modal
  const [startingReview, setStartingReview] = useState(false);
  const [actionLoading, setActionLoading] = useState(null);
  const [reviewHistory, setReviewHistory] = useState([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const navigate = useNavigate();

  const fetchDocs = () => {
    api.get(`/documents?matterId=${matterId}&page=0&size=100`)
      .then(r => setDocs(r.data?.content || r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  };

  const fetchReviews = () => {
    api.get(`/review-pipelines/reviews/matter/${matterId}`)
      .then(r => setReviews(r.data || []))
      .catch(() => {});
  };

  useEffect(() => {
    fetchDocs();
    fetchReviews();
  }, [matterId]);

  const handleUpload = async (files) => {
    if (!files?.length) return;
    setUploading(true);
    try {
      for (const file of files) {
        const formData = new FormData();
        formData.append('file', file);
        formData.append('matterId', matterId);
        await api.post('/documents/upload', formData, { headers: { 'Content-Type': 'multipart/form-data' } });
      }
      fetchDocs();
      if (onDocUploaded) onDocUploaded();
    } catch (e) {
      alert(e.response?.data?.message || 'Upload failed');
    } finally {
      setUploading(false);
    }
  };

  const handleDrop = (e) => {
    e.preventDefault();
    setDragOver(false);
    handleUpload(e.dataTransfer.files);
  };

  const handleStartReview = async (docId, pipelineId) => {
    if (!pipelineId) return;
    setStartingReview(true);
    try {
      await api.post(`/review-pipelines/reviews/start?matterId=${matterId}&documentId=${docId}&pipelineId=${pipelineId}`);
      fetchReviews();
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to start review');
    } finally {
      setStartingReview(false);
    }
  };

  const handleAction = async (reviewId, action) => {
    setActionLoading(action);
    try {
      const notes = action === 'RETURN' || action === 'FLAG'
        ? prompt(`Add a note for ${action.toLowerCase()}:`) || ''
        : '';
      await api.post(`/review-pipelines/reviews/${reviewId}/action`, { action, notes });
      fetchReviews();
      loadHistory(reviewId);
    } catch (e) {
      alert(e.response?.data?.message || 'Action failed');
    } finally {
      setActionLoading(null);
    }
  };

  const loadHistory = async (reviewId) => {
    setHistoryLoading(true);
    try {
      const res = await api.get(`/review-pipelines/reviews/${reviewId}/actions`);
      setReviewHistory(res.data || []);
    } catch { setReviewHistory([]); }
    finally { setHistoryLoading(false); }
  };

  const getDocReview = (docId) => reviews.find(r => r.documentId === docId && r.status === 'IN_PROGRESS');
  const getDocCompletedReviews = (docId) => reviews.filter(r => r.documentId === docId && r.status !== 'IN_PROGRESS');

  const openReviewModal = (doc) => {
    setReviewModal(doc);
    setReviewHistory([]);
    const active = getDocReview(doc.id);
    if (active) loadHistory(active.id);
  };

  const REVIEW_STATUS = {
    IN_PROGRESS: { color: 'text-warning', bg: 'bg-warning/10', label: 'In Review' },
    APPROVED: { color: 'text-success', bg: 'bg-success/10', label: 'Approved' },
    SENT: { color: 'text-primary', bg: 'bg-primary/10', label: 'Sent' },
  };

  const ACTION_ICONS = {
    APPROVE: { icon: CheckCircle2, color: 'text-success hover:bg-success/10 border border-success/20', label: 'Approve' },
    RETURN: { icon: RotateCcw, color: 'text-warning hover:bg-warning/10 border border-warning/20', label: 'Return' },
    FLAG: { icon: Flag, color: 'text-danger hover:bg-danger/10 border border-danger/20', label: 'Flag' },
    SEND: { icon: Send, color: 'text-primary hover:bg-primary/10 border border-primary/20', label: 'Send' },
    ADD_NOTE: { icon: MessageSquare, color: 'text-text-muted hover:bg-surface-el border border-border', label: 'Add Note' },
  };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-4"><Loader2 className="w-4 h-4 animate-spin" /> Loading documents...</div>;

  // Current modal doc review state
  const modalActiveReview = reviewModal ? getDocReview(reviewModal.id) : null;
  const modalCompletedReviews = reviewModal ? getDocCompletedReviews(reviewModal.id) : [];
  const modalActions = modalActiveReview?.availableActions?.split(',').filter(Boolean) || [];

  return (
    <div className="space-y-4">
      {/* Upload area */}
      {canUpload && (
        <div
          onDragOver={e => { e.preventDefault(); setDragOver(true); }}
          onDragLeave={() => setDragOver(false)}
          onDrop={handleDrop}
          className={`border-2 border-dashed rounded-lg p-6 text-center transition-colors ${
            dragOver ? 'border-primary bg-primary/5' : 'border-border hover:border-primary/50'
          }`}
        >
          {uploading ? (
            <div className="flex items-center justify-center gap-2 text-text-muted">
              <Loader2 className="w-5 h-5 animate-spin" /> Uploading...
            </div>
          ) : (
            <>
              <Upload className="w-8 h-8 text-text-muted mx-auto mb-2" />
              <p className="text-sm text-text-muted mb-2">Drag & drop files here, or</p>
              <label className="btn-primary text-sm cursor-pointer inline-flex items-center gap-1.5">
                <Plus className="w-4 h-4" /> Choose Files
                <input type="file" multiple className="hidden" onChange={e => handleUpload(e.target.files)} />
              </label>
              <p className="text-[10px] text-text-muted mt-2">PDF, DOCX, HTML — auto-linked to this matter</p>
            </>
          )}
        </div>
      )}

      {/* Document list */}
      {docs.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">No documents uploaded to this matter yet.</p>
      ) : (
        <div className="space-y-2">
          {docs.map(doc => {
            const activeReview = getDocReview(doc.id);
            const completedReviews = getDocCompletedReviews(doc.id);
            const reviewStatus = activeReview ? REVIEW_STATUS[activeReview.status] : null;

            return (
              <div key={doc.id}
                className="card p-3 flex items-center justify-between cursor-pointer hover:border-primary/30 transition-colors">
                <div className="flex items-center gap-3 flex-1 min-w-0"
                  onClick={() => navigate(`/documents/${doc.id}/edit?matterId=${matterId}`)}>
                  <FileText className="w-5 h-5 text-text-muted shrink-0" />
                  <div className="min-w-0">
                    <div className="text-sm font-medium text-text-primary truncate">{doc.fileName || doc.name}</div>
                    <div className="text-[10px] text-text-muted">
                      {doc.documentType && <span>{doc.documentType} · </span>}
                      {new Date(doc.uploadedAt || doc.createdAt).toLocaleDateString()}
                    </div>
                  </div>
                </div>
                <div className="flex items-center gap-2 shrink-0">
                  {/* Completed review badges */}
                  {completedReviews.map(r => {
                    const st = REVIEW_STATUS[r.status] || REVIEW_STATUS.APPROVED;
                    return (
                      <span key={r.id} className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${st.bg} ${st.color}`}>
                        {st.label}
                      </span>
                    );
                  })}
                  <span className={`text-[10px] font-medium ${DOC_STATUS_COLORS[doc.processingStatus] || 'text-text-muted'}`}>
                    {doc.processingStatus || 'PENDING'}
                  </span>
                  {/* Review button */}
                  <button onClick={(e) => { e.stopPropagation(); openReviewModal(doc); }}
                    className={`flex items-center gap-1.5 text-[11px] font-medium px-2.5 py-1.5 rounded-lg transition-colors ${
                      activeReview
                        ? `${reviewStatus?.bg} ${reviewStatus?.color}`
                        : 'bg-surface-el text-text-muted hover:text-primary hover:bg-primary/5'
                    }`}>
                    <GitPullRequest className="w-3.5 h-3.5" />
                    {activeReview
                      ? `${activeReview.currentStageName} (${activeReview.currentStageOrder}/${activeReview.totalStages})`
                      : 'Review'}
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      )}

      {/* ── Review Modal ──────────────────────────────────────────────── */}
      {reviewModal && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 p-4"
          onClick={() => setReviewModal(null)}>
          <div className="bg-surface border border-border rounded-xl w-full max-w-lg max-h-[80vh] overflow-y-auto shadow-xl"
            onClick={(e) => e.stopPropagation()}>

            {/* Modal header */}
            <div className="flex items-center justify-between px-5 py-4 border-b border-border/50">
              <div className="flex items-center gap-3 min-w-0">
                <FileText className="w-5 h-5 text-text-muted shrink-0" />
                <div className="min-w-0">
                  <h3 className="text-sm font-semibold text-text-primary truncate">
                    {reviewModal.fileName || reviewModal.name}
                  </h3>
                  <p className="text-[10px] text-text-muted">Document Review</p>
                </div>
              </div>
              <button onClick={() => setReviewModal(null)} className="text-text-muted hover:text-text-primary p-1">
                <X className="w-4 h-4" />
              </button>
            </div>

            <div className="p-5 space-y-5">

              {/* ── Active Review ───────────────────────────────────── */}
              {modalActiveReview ? (
                <>
                  {/* Pipeline + Stage info */}
                  <div>
                    <div className="flex items-center gap-2 mb-3">
                      <GitPullRequest className={`w-4 h-4 ${REVIEW_STATUS[modalActiveReview.status]?.color}`} />
                      <span className="text-sm font-semibold text-text-primary">{modalActiveReview.pipelineName}</span>
                      <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${REVIEW_STATUS[modalActiveReview.status]?.bg} ${REVIEW_STATUS[modalActiveReview.status]?.color}`}>
                        {REVIEW_STATUS[modalActiveReview.status]?.label}
                      </span>
                    </div>

                    {/* Stage progress */}
                    <div className="flex items-center gap-3 mb-2">
                      <span className="text-xs text-text-muted">Stage {modalActiveReview.currentStageOrder} of {modalActiveReview.totalStages}</span>
                      <div className="flex-1 h-1.5 bg-surface-el rounded-full">
                        <div className="h-1.5 bg-primary rounded-full transition-all"
                          style={{ width: `${(modalActiveReview.currentStageOrder / modalActiveReview.totalStages) * 100}%` }} />
                      </div>
                    </div>

                    <div className="card p-3 !bg-surface-el">
                      <p className="text-[10px] text-text-muted mb-0.5">CURRENT STAGE</p>
                      <p className="text-sm font-medium text-text-primary">{modalActiveReview.currentStageName}</p>
                      {modalActiveReview.requiredRole && (
                        <p className="text-[10px] text-text-muted mt-0.5">
                          Waiting on: {modalActiveReview.requiredRole.replace(/_/g, ' ')}
                        </p>
                      )}
                    </div>
                  </div>

                  {/* Action buttons */}
                  {modalActions.length > 0 && (
                    <div>
                      <p className="text-[10px] text-text-muted font-medium mb-2">ACTIONS</p>
                      <div className="flex flex-wrap gap-2">
                        {modalActions.map(action => {
                          const cfg = ACTION_ICONS[action];
                          if (!cfg) return null;
                          const Icon = cfg.icon;
                          return (
                            <button key={action}
                              onClick={() => handleAction(modalActiveReview.id, action)}
                              disabled={!!actionLoading}
                              className={`flex items-center gap-1.5 text-xs font-medium px-3 py-2 rounded-lg transition-colors ${cfg.color}`}>
                              {actionLoading === action
                                ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                                : <Icon className="w-3.5 h-3.5" />}
                              {cfg.label}
                            </button>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* History */}
                  <div>
                    <p className="text-[10px] text-text-muted font-medium mb-2">HISTORY</p>
                    {historyLoading ? (
                      <div className="flex items-center gap-2 text-text-muted py-2">
                        <Loader2 className="w-3.5 h-3.5 animate-spin" />
                        <span className="text-xs">Loading...</span>
                      </div>
                    ) : reviewHistory.length === 0 ? (
                      <p className="text-xs text-text-muted py-2">No actions taken yet.</p>
                    ) : (
                      <div className="space-y-2">
                        {reviewHistory.map(h => (
                          <div key={h.id} className="flex items-start gap-2.5 text-xs">
                            <div className={`w-6 h-6 rounded-full flex items-center justify-center shrink-0 mt-0.5 ${
                              h.action === 'APPROVE' ? 'bg-success/10' :
                              h.action === 'RETURN' ? 'bg-warning/10' :
                              h.action === 'FLAG' ? 'bg-danger/10' :
                              'bg-surface-el'
                            }`}>
                              {h.action === 'APPROVE' && <CheckCircle2 className="w-3 h-3 text-success" />}
                              {h.action === 'RETURN' && <RotateCcw className="w-3 h-3 text-warning" />}
                              {h.action === 'FLAG' && <Flag className="w-3 h-3 text-danger" />}
                              {h.action === 'SEND' && <Send className="w-3 h-3 text-primary" />}
                              {h.action === 'ADD_NOTE' && <MessageSquare className="w-3 h-3 text-text-muted" />}
                            </div>
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2">
                                <span className="font-medium text-text-primary">{h.actedByName}</span>
                                <span className="text-text-muted">{h.action.toLowerCase()}</span>
                                <span className="text-text-muted">at {h.stageName}</span>
                              </div>
                              {h.notes && <p className="text-text-secondary mt-0.5 italic">{h.notes}</p>}
                              <p className="text-[10px] text-text-muted mt-0.5">
                                {new Date(h.createdAt).toLocaleString()}
                              </p>
                            </div>
                          </div>
                        ))}
                      </div>
                    )}
                  </div>
                </>
              ) : (
                /* ── No Active Review — Start one ──────────────────── */
                <>
                  {/* Completed reviews */}
                  {modalCompletedReviews.length > 0 && (
                    <div>
                      <p className="text-[10px] text-text-muted font-medium mb-2">PREVIOUS REVIEWS</p>
                      <div className="space-y-1.5">
                        {modalCompletedReviews.map(r => {
                          const st = REVIEW_STATUS[r.status] || REVIEW_STATUS.APPROVED;
                          return (
                            <div key={r.id} className={`flex items-center justify-between px-3 py-2 rounded-lg ${st.bg}`}>
                              <div className="flex items-center gap-2">
                                <GitPullRequest className={`w-3.5 h-3.5 ${st.color}`} />
                                <span className="text-xs font-medium text-text-primary">{r.pipelineName}</span>
                              </div>
                              <div className="flex items-center gap-2">
                                <span className={`text-[10px] font-medium ${st.color}`}>{st.label}</span>
                                <span className="text-[10px] text-text-muted">
                                  {r.completedAt ? new Date(r.completedAt).toLocaleDateString() : ''}
                                </span>
                              </div>
                            </div>
                          );
                        })}
                      </div>
                    </div>
                  )}

                  {/* Start review */}
                  <div>
                    <p className="text-[10px] text-text-muted font-medium mb-3">START REVIEW</p>

                    {/* Default pipeline — prominent button */}
                    {defaultPipelineId && (
                      <button onClick={() => handleStartReview(reviewModal.id, defaultPipelineId)}
                        disabled={startingReview}
                        className="w-full btn-primary text-sm py-2.5 flex items-center justify-center gap-2 mb-3">
                        {startingReview
                          ? <Loader2 className="w-4 h-4 animate-spin" />
                          : <Play className="w-4 h-4" />}
                        Start Review — {defaultPipelineName}
                      </button>
                    )}

                    {/* All pipelines (or just non-default ones if default is set) */}
                    {(() => {
                      const otherPipelines = defaultPipelineId
                        ? pipelines.filter(p => p.id !== defaultPipelineId)
                        : pipelines;
                      if (otherPipelines.length === 0 && !defaultPipelineId && pipelines.length === 0) {
                        return (
                          <p className="text-xs text-text-muted text-center py-4">
                            No review pipelines configured. Create one in Settings.
                          </p>
                        );
                      }
                      if (otherPipelines.length === 0) return null;
                      return (
                        <>
                          {defaultPipelineId ? (
                            <p className="text-[10px] text-text-muted mb-2">Or use a different pipeline:</p>
                          ) : (
                            <p className="text-[10px] text-text-muted mb-2">Select a pipeline to start review:</p>
                          )}
                          <div className="space-y-1.5">
                            {otherPipelines.map(p => (
                              <button key={p.id}
                                onClick={() => handleStartReview(reviewModal.id, p.id)}
                                disabled={startingReview}
                                className="w-full flex items-center justify-between px-3 py-2.5 rounded-lg border border-border hover:border-primary/30 hover:bg-surface-el transition-colors">
                                <div className="flex items-center gap-2">
                                  <GitPullRequest className="w-3.5 h-3.5 text-text-muted" />
                                  <span className="text-xs font-medium text-text-primary">{p.name}</span>
                                </div>
                                <span className="text-[10px] text-text-muted">{p.stages?.length || 0} stages</span>
                              </button>
                            ))}
                          </div>
                        </>
                      );
                    })()}
                  </div>
                </>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

// ── Workflows Tab ──────────────────────────────────────────────────────

function WorkflowsTab({ matterId, matterRef, matterName }) {
  const [runs, setRuns] = useState([]);
  const [loading, setLoading] = useState(true);
  const navigate = useNavigate();

  useEffect(() => {
    api.get(`/workflows/runs?matter=${encodeURIComponent(matterRef)}`)
      .then(r => setRuns(r.data?.content || r.data || []))
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [matterRef]);

  const RUN_STATUS_ICONS = { COMPLETED: CheckCircle2, FAILED: XCircle, RUNNING: Loader2, PENDING: Clock, CANCELLED: XCircle };
  const RUN_STATUS_COLORS = { COMPLETED: 'text-success', FAILED: 'text-danger', RUNNING: 'text-warning', PENDING: 'text-text-muted', CANCELLED: 'text-text-muted' };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-4"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  return (
    <div className="space-y-4">
      <div className="flex justify-end">
        <button onClick={() => navigate('/workflows')} className="btn-primary text-sm flex items-center gap-1.5">
          <Play className="w-4 h-4" /> Run AI Agent
        </button>
      </div>

      {runs.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">No AI agent runs for this matter yet.</p>
      ) : (
        <div className="space-y-2">
          {runs.map(run => {
            const Icon = RUN_STATUS_ICONS[run.status] || Clock;
            return (
              <div key={run.id} onClick={() => navigate(`/workflows/run/${run.id}`)}
                className="card p-3 flex items-center justify-between cursor-pointer hover:border-primary/30 transition-colors">
                <div className="flex items-center gap-3">
                  <Icon className={`w-5 h-5 ${RUN_STATUS_COLORS[run.status] || ''} ${run.status === 'RUNNING' ? 'animate-spin' : ''}`} />
                  <div>
                    <div className="text-sm font-medium text-text-primary">{run.workflowName}</div>
                    <div className="text-[10px] text-text-muted">
                      {new Date(run.startedAt).toLocaleString()} · {run.stepCount} steps
                    </div>
                  </div>
                </div>
                <span className={`text-xs font-medium ${RUN_STATUS_COLORS[run.status] || ''}`}>{run.status}</span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

// ── Team Tab ──────────────────────────────────────────────────────────

function TeamTab({ matterId, team, canManage, onTeamChanged }) {
  const [role, setRole] = useState('ASSOCIATE');
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState(null);
  const [userSearch, setUserSearch] = useState('');
  const [searchResults, setSearchResults] = useState([]);
  const [teams, setTeams] = useState([]);
  const [selectedTeam, setSelectedTeam] = useState('');
  const [addingTeam, setAddingTeam] = useState(false);

  useEffect(() => {
    if (canManage) api.get('/teams').then(r => setTeams(r.data || [])).catch(() => {});
  }, [canManage]);

  const handleSearchUsers = async (q) => {
    setUserSearch(q);
    if (q.length < 2) { setSearchResults([]); return; }
    try {
      const res = await api.get(`/admin/users/search?q=${encodeURIComponent(q)}`);
      setSearchResults(res.data || []);
    } catch { setSearchResults([]); }
  };

  const handleAddUser = async (userResult) => {
    setAdding(true);
    try {
      await api.post(`/matters/${matterId}/team`, { email: userResult.email, matterRole: role, userId: userResult.id });
      setUserSearch('');
      setSearchResults([]);
      onTeamChanged();
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to add member');
    } finally { setAdding(false); }
  };

  const handleAddTeam = async () => {
    if (!selectedTeam) return;
    setAddingTeam(true);
    try {
      await api.post(`/matters/${matterId}/team/add-team?teamId=${selectedTeam}&matterRole=${role}`);
      setSelectedTeam('');
      onTeamChanged();
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to add team');
    } finally { setAddingTeam(false); }
  };

  const handleRemove = async (memberId) => {
    if (!confirm('Remove this team member?')) return;
    setRemoving(memberId);
    try {
      await api.delete(`/matters/${matterId}/team/${memberId}`);
      onTeamChanged();
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to remove member');
    } finally { setRemoving(null); }
  };

  return (
    <div className="space-y-4">
      {canManage && (
        <div className="card p-4 space-y-3">
          <div className="flex items-center justify-between">
            <p className="text-xs font-medium text-text-muted">Add Members</p>
            <select value={role} onChange={e => setRole(e.target.value)} className="input-field text-xs py-1">
              {MEMBER_ROLES.map(r => <option key={r} value={r}>{r.replace(/_/g, ' ')}</option>)}
            </select>
          </div>

          {/* Search users */}
          <div className="relative">
            <input type="text" placeholder="Search users by email or name..."
              value={userSearch} onChange={e => handleSearchUsers(e.target.value)}
              className="input-field w-full text-sm pl-3" />
            {searchResults.length > 0 && (
              <div className="absolute z-10 top-full left-0 right-0 mt-1 border border-border rounded-lg bg-surface divide-y divide-border max-h-40 overflow-y-auto shadow-lg">
                {searchResults.map(u => {
                  const alreadyMember = team.some(m => m.email === u.email);
                  return (
                    <div key={u.id} className="flex items-center justify-between px-3 py-2 hover:bg-surface-el">
                      <span className="text-sm text-text-primary">{u.displayName || u.email}
                        {u.displayName && <span className="text-text-muted ml-1 text-xs">{u.email}</span>}
                      </span>
                      {alreadyMember ? (
                        <span className="text-[10px] text-text-muted">Already added</span>
                      ) : (
                        <button onClick={() => handleAddUser(u)} disabled={adding}
                          className="btn-primary text-[10px] px-2 py-0.5">+ Add</button>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* Add entire team */}
          {teams.length > 0 && (
            <div className="flex gap-2 items-center">
              <select value={selectedTeam} onChange={e => setSelectedTeam(e.target.value)} className="input-field flex-1 text-sm">
                <option value="">Add entire team...</option>
                {teams.map(t => <option key={t.id} value={t.id}>{t.name} ({t.memberCount} members)</option>)}
              </select>
              <button onClick={handleAddTeam} disabled={addingTeam || !selectedTeam} className="btn-secondary text-sm flex items-center gap-1">
                {addingTeam ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Users className="w-3.5 h-3.5" />}
                Add Team
              </button>
            </div>
          )}
        </div>
      )}

      {/* Team list */}
      {team.length === 0 ? (
        <p className="text-text-muted text-sm text-center py-6">No team members added yet.</p>
      ) : (
        <div className="space-y-2">
          {team.map(member => (
            <div key={member.id} className="card p-3 flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-8 h-8 rounded-full bg-primary/10 flex items-center justify-center text-primary text-sm font-medium">
                  {(member.displayName || member.email || '?')[0].toUpperCase()}
                </div>
                <div>
                  <div className="text-sm font-medium text-text-primary">{member.displayName || member.email}</div>
                  {member.displayName && <div className="text-[10px] text-text-muted">{member.email}</div>}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-[10px] px-2 py-0.5 rounded-full font-medium ${ROLE_COLORS[member.matterRole] || ''}`}>
                  {member.matterRole.replace(/_/g, ' ')}
                </span>
                {canManage && (
                  <button onClick={() => handleRemove(member.id)} disabled={removing === member.id}
                    className="text-text-muted hover:text-danger p-1" title="Remove member">
                    <Trash2 className="w-3.5 h-3.5" />
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ── Activity Tab ──────────────────────────────────────────────────────

function ActivityTab({ matterId }) {
  const [activities, setActivities] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Combine findings + docs into a timeline
    Promise.all([
      api.get(`/matters/${matterId}/findings`).catch(() => ({ data: [] })),
      api.get(`/documents?matterId=${matterId}&page=0&size=100`).catch(() => ({ data: { content: [] } })),
    ]).then(([findingsRes, docsRes]) => {
      const items = [];

      // Findings
      for (const f of (findingsRes.data || [])) {
        items.push({
          time: f.createdAt,
          type: 'finding',
          title: `Finding: ${f.title}`,
          detail: `${f.severity} · ${f.findingType.replace(/_/g, ' ')}`,
          severity: f.severity,
        });
        if (f.reviewedAt) {
          items.push({
            time: f.reviewedAt,
            type: 'review',
            title: `Finding reviewed: ${f.title}`,
            detail: `Status: ${f.status}`,
          });
        }
      }

      // Documents
      for (const d of (docsRes.data?.content || docsRes.data || [])) {
        items.push({
          time: d.uploadedAt || d.createdAt,
          type: 'document',
          title: `Document uploaded: ${d.fileName || d.name}`,
          detail: d.processingStatus || 'PENDING',
        });
      }

      items.sort((a, b) => new Date(b.time) - new Date(a.time));
      setActivities(items);
    }).finally(() => setLoading(false));
  }, [matterId]);

  const TYPE_ICONS = { finding: AlertTriangle, review: CheckCircle2, document: FileText, workflow: Workflow };
  const TYPE_COLORS = { finding: 'text-warning', review: 'text-success', document: 'text-primary', workflow: 'text-text-muted' };

  if (loading) return <div className="flex items-center gap-2 text-text-muted py-4"><Loader2 className="w-4 h-4 animate-spin" /> Loading...</div>;

  if (activities.length === 0) return <p className="text-text-muted text-sm text-center py-6">No activity yet.</p>;

  return (
    <div className="space-y-1">
      {activities.map((a, i) => {
        const Icon = TYPE_ICONS[a.type] || Clock;
        return (
          <div key={i} className="flex items-start gap-3 py-2">
            <div className="mt-0.5">
              <Icon className={`w-4 h-4 ${TYPE_COLORS[a.type] || 'text-text-muted'}`} />
            </div>
            <div className="flex-1 min-w-0">
              <div className="text-sm text-text-primary">{a.title}</div>
              <div className="text-[10px] text-text-muted">{a.detail}</div>
            </div>
            <div className="text-[10px] text-text-muted shrink-0">
              {new Date(a.time).toLocaleString()}
            </div>
          </div>
        );
      })}
    </div>
  );
}
