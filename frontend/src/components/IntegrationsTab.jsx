import { useState, useEffect } from 'react';
import { Loader, Check, X, ExternalLink, Search } from 'lucide-react';
import api from '../api/client';
import {
  getIntegrationConnections,
  getIntegrationAuthUrl,
  disconnectIntegration,
  configureSlackWebhook,
  configureTeamsWebhook,
} from '../api/integrations';

const INTEGRATIONS = [
  { category: 'Document Management', items: [
    { provider: 'GOOGLE_DRIVE', name: 'Google Drive', description: 'Import and export documents', apiPrefix: '/cloud-storage', icon: '📁', featured: true },
    { provider: 'ONEDRIVE', name: 'OneDrive / SharePoint', description: 'Microsoft cloud storage and SharePoint', apiPrefix: '/cloud-storage', icon: '☁️' },
    { provider: 'DROPBOX', name: 'Dropbox', description: 'Import and export documents', apiPrefix: '/cloud-storage', icon: '📦' },
    { provider: 'NETDOCUMENTS', name: 'NetDocuments', description: 'Legal document management system', apiPrefix: '/integrations', icon: '📋', featured: true },
    { provider: 'IMANAGE', name: 'iManage Work', description: 'Enterprise document and email management', apiPrefix: '/integrations', icon: '🗄️' },
  ]},
  { category: 'E-Signature', items: [
    { provider: 'DOCUSIGN', name: 'DocuSign', description: 'Send contracts for electronic signature', apiPrefix: '/integrations', icon: '✍️', featured: true },
  ]},
  { category: 'Notifications', items: [
    { provider: 'SLACK', name: 'Slack', description: 'Receive workflow notifications via Slack', apiPrefix: '/integrations', icon: '💬', isWebhook: true, featured: true },
    { provider: 'MICROSOFT_TEAMS', name: 'Microsoft Teams', description: 'Receive workflow notifications via Teams', apiPrefix: '/integrations', icon: '👥', isWebhook: true },
  ]},
];

const ALL_ITEMS = INTEGRATIONS.flatMap(cat => cat.items.map(item => ({ ...item, category: cat.category })));
const FEATURED_PROVIDERS = ['GOOGLE_DRIVE', 'NETDOCUMENTS', 'DOCUSIGN', 'SLACK'];

export default function IntegrationsTab() {
  const [cloudConnections, setCloudConnections] = useState([]);
  const [integrationConnections, setIntegrationConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(null);
  const [disconnecting, setDisconnecting] = useState(null);
  const [webhookUrls, setWebhookUrls] = useState({});
  const [webhookSaving, setWebhookSaving] = useState(null);
  const [webhookSuccess, setWebhookSuccess] = useState(null);
  const [search, setSearch] = useState('');

  const fetchConnections = async () => {
    try {
      const [cloudRes, intRes] = await Promise.all([
        api.get('/cloud-storage/connections').catch(() => ({ data: [] })),
        getIntegrationConnections().catch(() => ({ data: [] })),
      ]);
      setCloudConnections(cloudRes.data || []);
      setIntegrationConnections(intRes.data || []);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => { fetchConnections(); }, []);

  const isConnected = (provider, apiPrefix) => {
    if (apiPrefix === '/cloud-storage') {
      return cloudConnections.some(c => c.provider === provider && c.connected);
    }
    return integrationConnections.some(c => c.provider === provider && c.connected);
  };

  const handleConnect = async (item) => {
    setConnecting(item.provider);
    try {
      const { data } = await api.get(`${item.apiPrefix}/auth-url?provider=${item.provider}`);
      window.location.href = data.url;
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to get authorization URL');
    } finally {
      setConnecting(null);
    }
  };

  const handleDisconnect = async (item) => {
    if (!confirm(`Disconnect ${item.name}? You can reconnect later.`)) return;
    setDisconnecting(item.provider);
    try {
      if (item.apiPrefix === '/cloud-storage') {
        await api.delete(`/cloud-storage/disconnect?provider=${item.provider}`);
      } else {
        await disconnectIntegration(item.provider);
      }
      await fetchConnections();
    } catch (e) {
      alert(e.response?.data?.message || 'Failed to disconnect');
    } finally {
      setDisconnecting(null);
    }
  };

  const handleWebhookSave = async (item) => {
    const url = webhookUrls[item.provider] || '';
    setWebhookSaving(item.provider);
    setWebhookSuccess(null);
    try {
      if (item.provider === 'SLACK') {
        await configureSlackWebhook(url);
      } else if (item.provider === 'MICROSOFT_TEAMS') {
        await configureTeamsWebhook(url);
      }
      setWebhookSuccess(item.provider);
      await fetchConnections();
      setTimeout(() => setWebhookSuccess(null), 3000);
    } catch (e) {
      alert(e.response?.data?.message || 'Invalid webhook URL');
    } finally {
      setWebhookSaving(null);
    }
  };

  const filterItems = (items) => {
    if (!search.trim()) return items;
    const q = search.toLowerCase();
    return items.filter(item =>
      item.name.toLowerCase().includes(q) ||
      item.description.toLowerCase().includes(q) ||
      (item.category || '').toLowerCase().includes(q)
    );
  };

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-text-muted py-8">
        <Loader className="w-4 h-4 animate-spin" /> Loading integrations...
      </div>
    );
  }

  // Build featured items: connected ones + popular ones
  const connectedItems = ALL_ITEMS.filter(item => isConnected(item.provider, item.apiPrefix));
  const featuredDefaults = ALL_ITEMS.filter(item =>
    FEATURED_PROVIDERS.includes(item.provider) && !isConnected(item.provider, item.apiPrefix)
  );
  const featuredItems = [...connectedItems, ...featuredDefaults];
  const filteredFeatured = filterItems(featuredItems);

  // Build all-integrations grouped by category
  const filteredCategories = INTEGRATIONS.map(cat => ({
    ...cat,
    items: filterItems(cat.items.map(item => ({ ...item, category: cat.category }))),
  })).filter(cat => cat.items.length > 0);

  const noResults = search.trim() && filteredFeatured.length === 0 && filteredCategories.length === 0;

  const renderCard = (item) => {
    const connected = isConnected(item.provider, item.apiPrefix);
    const isConnecting = connecting === item.provider;
    const isDisconnecting = disconnecting === item.provider;

    return (
      <div key={item.provider} className="card p-4 flex flex-col gap-3">
        <div className="flex items-start justify-between">
          <div className="flex items-center gap-3">
            <span className="text-2xl">{item.icon}</span>
            <div>
              <div className="font-medium text-text-primary">{item.name}</div>
              <div className="text-xs text-text-muted mt-0.5">{item.description}</div>
            </div>
          </div>
          {connected && (
            <span className="flex items-center gap-1 text-xs text-success shrink-0">
              <Check className="w-3.5 h-3.5" /> Connected
            </span>
          )}
        </div>

        <div className="flex items-center gap-2">
          <span className="text-[10px] bg-surface-el text-text-muted border border-border/50 px-2 py-0.5 rounded-full">
            {item.category}
          </span>
        </div>

        {/* Webhook inline input for non-connected webhook integrations */}
        {item.isWebhook && !connected && (
          <div className="space-y-2">
            <input
              type="url"
              placeholder={item.provider === 'SLACK'
                ? 'https://hooks.slack.com/services/...'
                : 'https://outlook.office.com/webhook/...'}
              value={webhookUrls[item.provider] || ''}
              onChange={(e) => setWebhookUrls(prev => ({ ...prev, [item.provider]: e.target.value }))}
              className="input-field w-full text-xs"
            />
            <button
              onClick={() => handleWebhookSave(item)}
              disabled={
                webhookSaving === item.provider ||
                !(webhookUrls[item.provider] || '').startsWith('https://')
              }
              className="btn-primary text-xs w-full"
            >
              {webhookSaving === item.provider ? 'Saving...'
                : webhookSuccess === item.provider ? 'Saved!'
                : 'Save Webhook'}
            </button>
            <p className="text-[10px] text-text-muted">
              {item.provider === 'SLACK' ? (
                <>Paste your Slack Incoming Webhook URL. Create one at{' '}
                  <a href="https://api.slack.com/messaging/webhooks" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">
                    api.slack.com/messaging/webhooks
                  </a>
                </>
              ) : (
                <>Paste your Teams Incoming Webhook URL. Create one in your Teams channel settings.</>
              )}
            </p>
          </div>
        )}

        {/* Action buttons */}
        <div className="flex gap-2 mt-auto">
          {connected ? (
            <button
              onClick={() => handleDisconnect(item)}
              disabled={isDisconnecting}
              className="btn-secondary text-xs text-danger flex-1"
            >
              {isDisconnecting ? 'Disconnecting...' : 'Disconnect'}
            </button>
          ) : !item.isWebhook ? (
            <button
              onClick={() => handleConnect(item)}
              disabled={isConnecting}
              className="btn-primary text-xs flex-1 flex items-center justify-center gap-1"
            >
              {isConnecting ? (
                <><Loader className="w-3 h-3 animate-spin" /> Connecting...</>
              ) : (
                <><ExternalLink className="w-3 h-3" /> Connect</>
              )}
            </button>
          ) : null}
        </div>
      </div>
    );
  };

  return (
    <div className="space-y-8">
      {/* Search bar */}
      <div className="relative">
        <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-text-muted pointer-events-none" />
        <input
          type="text"
          placeholder="Search integrations..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="input-field w-full pl-10 pr-9 py-2.5 text-sm"
        />
        {search && (
          <button
            onClick={() => setSearch('')}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-text-muted hover:text-text-primary"
          >
            <X className="w-4 h-4" />
          </button>
        )}
      </div>

      {noResults && (
        <div className="card text-center py-10">
          <p className="text-text-muted text-sm">No integrations found for "{search}"</p>
          <button onClick={() => setSearch('')} className="text-primary text-sm mt-2 hover:underline">
            Clear search
          </button>
        </div>
      )}

      {/* Featured section */}
      {!search.trim() && filteredFeatured.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold text-text-primary mb-1">Featured</h2>
          <p className="text-sm text-text-muted mb-4">Connected integrations and popular services</p>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
            {filteredFeatured.map(renderCard)}
          </div>
        </section>
      )}

      {/* All Integrations grouped by category */}
      {filteredCategories.length > 0 && (
        <section>
          <h2 className="text-lg font-semibold text-text-primary mb-4">
            {search.trim() ? 'Search Results' : 'All Integrations'}
          </h2>
          {filteredCategories.map(category => (
            <div key={category.category} className="mb-6">
              <h3 className="text-sm font-semibold text-text-secondary uppercase tracking-wide mb-3">
                {category.category}
              </h3>
              <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4">
                {category.items.map(renderCard)}
              </div>
            </div>
          ))}
        </section>
      )}
    </div>
  );
}
