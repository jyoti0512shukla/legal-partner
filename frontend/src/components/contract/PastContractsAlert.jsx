import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router-dom';
import { Info, ChevronDown, ChevronUp, ExternalLink } from 'lucide-react';
import api from '../../api/client';
import StatusBadge from './StatusBadge';

export default function PastContractsAlert({ partyName }) {
  const navigate = useNavigate();
  const [data, setData] = useState(null);
  const [expanded, setExpanded] = useState(false);
  const [dismissed, setDismissed] = useState(false);
  const debounceRef = useRef(null);

  useEffect(() => {
    setData(null);
    setDismissed(false);
    setExpanded(false);

    if (!partyName || partyName.trim().length < 3) return;

    clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      api.get(`/documents/clients/lookup?name=${encodeURIComponent(partyName.trim())}`)
        .then(r => { if (r.data?.matched) setData(r.data); })
        .catch(() => {});
    }, 500);

    return () => clearTimeout(debounceRef.current);
  }, [partyName]);

  if (!data || dismissed) return null;

  const lt = data.latestTerms || {};

  return (
    <div style={{
      border: '1px solid rgba(56,139,253,0.3)', borderRadius: 'var(--r-md)',
      background: 'rgba(56,139,253,0.05)', padding: '10px 12px', marginTop: 8,
    }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
        <Info size={14} style={{ color: 'var(--info-400)', flexShrink: 0, marginTop: 1 }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 12, fontWeight: 600, color: 'var(--text-1)' }}>
            {data.contractCount} past contract{data.contractCount > 1 ? 's' : ''} found with {data.clientName}
          </div>
          <div style={{ fontSize: 11, color: 'var(--text-2)', marginTop: 4, lineHeight: 1.5 }}>
            {lt.lastContractType && <span>Latest: {lt.lastContractType}</span>}
            {lt.lastContractValue && <span> · {lt.lastContractValue}</span>}
            {lt.lastJurisdiction && <span> · {lt.lastJurisdiction}</span>}
            {lt.lastLiabilityCap && <><br />Liability cap: {lt.lastLiabilityCap}</>}
            {lt.lastNoticePeriod && <span> · Notice: {lt.lastNoticePeriod} days</span>}
            {lt.lastStatus && <><br /><StatusBadge status={lt.lastStatus} size="sm" /></>}
            {lt.lastExpiryDate && <span> · Expires: {lt.lastExpiryDate}</span>}
          </div>

          {/* Expandable contract list */}
          {expanded && data.contracts?.length > 0 && (
            <div style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
              {data.contracts.map(c => (
                <div key={c.id} style={{
                  display: 'flex', alignItems: 'center', justifyContent: 'space-between',
                  padding: '4px 8px', background: 'var(--bg-1)', borderRadius: 'var(--r-sm)',
                  fontSize: 11,
                }}>
                  <div style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                    {c.fileName}
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexShrink: 0, marginLeft: 8 }}>
                    {c.contractStatus && <StatusBadge status={c.contractStatus} size="sm" />}
                    <span className="muted">{c.contractValue || ''}</span>
                  </div>
                </div>
              ))}
            </div>
          )}

          <div style={{ display: 'flex', gap: 10, marginTop: 6, alignItems: 'center' }}>
            {data.contractCount > 1 && (
              <button onClick={() => setExpanded(!expanded)}
                style={{ fontSize: 10, color: 'var(--info-400)', background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 3 }}>
                {expanded ? <ChevronUp size={10} /> : <ChevronDown size={10} />}
                {expanded ? 'Collapse' : `Show all ${data.contractCount}`}
              </button>
            )}
            <button onClick={() => navigate(`/documents?search=${encodeURIComponent(data.clientName)}`)}
              style={{ fontSize: 10, color: 'var(--info-400)', background: 'none', border: 'none', cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 3 }}>
              <ExternalLink size={10} /> View all contracts
            </button>
            <button onClick={() => setDismissed(true)}
              style={{ fontSize: 10, color: 'var(--text-3)', background: 'none', border: 'none', cursor: 'pointer', marginLeft: 'auto' }}>
              Dismiss
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
