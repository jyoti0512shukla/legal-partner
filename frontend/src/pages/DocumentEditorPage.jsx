import { useState, useEffect, useRef } from 'react';
import { useParams, Link } from 'react-router-dom';
import { ArrowLeft, Shield, AlertTriangle, CheckCircle2, Flag, Loader2, FileText, Play } from 'lucide-react';
import api from '../api/client';

export default function DocumentEditorPage() {
  const { id } = useParams();
  const editorRef = useRef(null);
  const [config, setConfig] = useState(null);
  const [doc, setDoc] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);
  const [findings, setFindings] = useState([]);
  const [analyzing, setAnalyzing] = useState(false);
  const [activePanel, setActivePanel] = useState('findings');

  // Load editor config + document info
  useEffect(() => {
    Promise.all([
      api.get(`/editor/${id}/config`),
      api.get(`/documents/${id}`),
    ]).then(([configRes, docRes]) => {
      setConfig(configRes.data);
      setDoc(docRes.data);
    }).catch(e => {
      setError(e.response?.data?.message || 'Failed to load document for editing. Original file may not be available.');
    }).finally(() => setLoading(false));
  }, [id]);

  // Initialize ONLYOFFICE editor
  useEffect(() => {
    if (!config || !editorRef.current) return;

    const script = document.createElement('script');
    script.src = config.onlyofficeUrl + '/web-apps/apps/api/documents/api.js';
    script.onload = () => {
      if (window.DocsAPI) {
        new window.DocsAPI.DocEditor('onlyoffice-editor', {
          document: config.document,
          editorConfig: config.editorConfig,
          documentType: config.documentType,
          height: '100%',
          width: '100%',
        });
      }
    };
    script.onerror = () => setError('Could not connect to document editor. Is ONLYOFFICE running?');
    document.head.appendChild(script);

    return () => { document.head.removeChild(script); };
  }, [config]);

  // Load findings if document has a matter
  useEffect(() => {
    if (!doc?.matter?.id) return;
    api.get(`/matters/${doc.matter.id}/findings`)
      .then(r => setFindings((r.data || []).filter(f => f.documentId === id)))
      .catch(() => {});
  }, [doc, id]);

  const handleAnalyze = async (task) => {
    setAnalyzing(true);
    try {
      let endpoint;
      switch (task) {
        case 'risk': endpoint = `/ai/risk-assessment/${id}`; break;
        case 'extract': endpoint = `/ai/extract/${id}`; break;
        case 'review': endpoint = `/review`; break;
        default: return;
      }
      if (task === 'review') {
        await api.post(endpoint, { documentId: id });
      } else {
        await api.post(endpoint);
      }
      // Refresh findings
      if (doc?.matter?.id) {
        const res = await api.get(`/matters/${doc.matter.id}/findings`);
        setFindings((res.data || []).filter(f => f.documentId === id));
      }
    } catch (e) {
      alert(e.response?.data?.message || 'Analysis failed');
    } finally { setAnalyzing(false); }
  };

  if (loading) return (
    <div className="flex items-center justify-center h-screen">
      <Loader2 className="w-8 h-8 animate-spin text-text-muted" />
    </div>
  );

  if (error) return (
    <div className="flex flex-col items-center justify-center h-screen gap-4">
      <AlertTriangle className="w-10 h-10 text-warning" />
      <p className="text-text-muted text-sm max-w-md text-center">{error}</p>
      <Link to="/documents" className="btn-secondary text-sm">Back to Documents</Link>
    </div>
  );

  const SEVERITY = {
    HIGH: { dot: '🔴', bg: 'bg-danger/10', border: 'border-danger/20' },
    MEDIUM: { dot: '🟡', bg: 'bg-warning/10', border: 'border-warning/20' },
    LOW: { dot: '🟢', bg: 'bg-success/10', border: 'border-success/20' },
  };

  return (
    <div className="h-screen flex flex-col">
      {/* Header */}
      <div className="flex items-center justify-between px-4 py-2 bg-surface border-b border-border/50 shrink-0">
        <div className="flex items-center gap-3">
          <Link to={doc?.matter?.id ? `/matters/${doc.matter.id}` : '/documents'}
            className="text-text-muted hover:text-text-primary">
            <ArrowLeft className="w-4 h-4" />
          </Link>
          <div>
            <h1 className="text-sm font-semibold text-text-primary font-display">{doc?.fileName}</h1>
            <p className="text-[10px] text-text-muted">{doc?.documentType} · {doc?.contentType}</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {findings.length > 0 && (
            <span className="text-[10px] bg-warning/10 text-warning px-2 py-0.5 rounded-full">
              {findings.length} findings
            </span>
          )}
        </div>
      </div>

      {/* Split view: Editor + AI Panel */}
      <div className="flex flex-1 overflow-hidden">
        {/* ONLYOFFICE Editor */}
        <div className="flex-1 bg-white" ref={editorRef}>
          <div id="onlyoffice-editor" className="h-full" />
        </div>

        {/* AI Panel */}
        <div className="w-80 bg-surface border-l border-border/50 flex flex-col shrink-0">
          {/* Panel tabs */}
          <div className="flex border-b border-border/50 shrink-0">
            {[
              { id: 'findings', label: 'Findings', icon: Shield },
              { id: 'analyze', label: 'Analyze', icon: Play },
            ].map(tab => (
              <button key={tab.id} onClick={() => setActivePanel(tab.id)}
                className={`flex-1 flex items-center justify-center gap-1.5 py-2.5 text-xs font-medium border-b-2 transition-colors ${
                  activePanel === tab.id ? 'border-primary text-primary' : 'border-transparent text-text-muted'
                }`}>
                <tab.icon className="w-3.5 h-3.5" /> {tab.label}
              </button>
            ))}
          </div>

          {/* Panel content */}
          <div className="flex-1 overflow-y-auto p-3">
            {activePanel === 'findings' && (
              <div className="space-y-2">
                {findings.length === 0 ? (
                  <p className="text-text-muted text-xs text-center py-8">
                    No findings yet. Run analysis or upload to a matter with a playbook.
                  </p>
                ) : (
                  findings.map(f => {
                    const sev = SEVERITY[f.severity] || SEVERITY.MEDIUM;
                    return (
                      <div key={f.id} className={`${sev.bg} border ${sev.border} rounded-lg p-2.5`}>
                        <div className="flex items-start gap-1.5">
                          <span className="text-xs">{sev.dot}</span>
                          <div className="flex-1 min-w-0">
                            <p className="text-xs font-medium text-text-primary">{f.title}</p>
                            <p className="text-[10px] text-text-secondary mt-0.5">{f.description}</p>
                            {f.sectionRef && <p className="text-[10px] text-text-muted mt-0.5">§ {f.sectionRef}</p>}
                          </div>
                        </div>
                      </div>
                    );
                  })
                )}
              </div>
            )}

            {activePanel === 'analyze' && (
              <div className="space-y-3">
                <p className="text-xs text-text-muted">Run AI analysis on this document.</p>
                {[
                  { key: 'risk', label: 'Risk Assessment', desc: 'Assess risk across 7 categories' },
                  { key: 'extract', label: 'Extract Key Terms', desc: 'Party names, dates, values' },
                  { key: 'review', label: 'Clause Checklist', desc: 'Check 12 standard clauses' },
                ].map(task => (
                  <button key={task.key} onClick={() => handleAnalyze(task.key)} disabled={analyzing}
                    className="w-full text-left card p-3 hover:border-primary/30 transition-colors">
                    <div className="flex items-center justify-between">
                      <div>
                        <p className="text-xs font-medium text-text-primary">{task.label}</p>
                        <p className="text-[10px] text-text-muted">{task.desc}</p>
                      </div>
                      {analyzing ? <Loader2 className="w-4 h-4 animate-spin text-text-muted" /> : <Play className="w-4 h-4 text-primary" />}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
