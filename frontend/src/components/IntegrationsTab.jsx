import { useState, useEffect } from 'react';
import { Loader, Check, X, ExternalLink } from 'lucide-react';
import api from '../api/client';
import {
  getIntegrationConnections,
  getIntegrationAuthUrl,
  disconnectIntegration,
  configureSlackWebhook,
} from '../api/integrations';

const INTEGRATIONS = [
  {
    category: 'Cloud Storage',
    description: 'Import and export documents from cloud storage',
    items: [
      { provider: 'GOOGLE_DRIVE', name: 'Google Drive', description: 'Import and export documents from Google Drive', apiPrefix: '/cloud-storage', icon: '📁' },
      { provider: 'ONEDRIVE', name: 'OneDrive / SharePoint', description: 'Connect to Microsoft OneDrive or SharePoint', apiPrefix: '/cloud-storage', icon: '☁️' },
      { provider: 'DROPBOX', name: 'Dropbox', description: 'Import and export documents from Dropbox', apiPrefix: '/cloud-storage', icon: '📦' },
    ],
  },
  {
    category: 'E-Signature',
    description: 'Send contracts for electronic signature',
    items: [
      { provider: 'DOCUSIGN', name: 'DocuSign', description: 'Send contracts for e-signature through your DocuSign account', apiPrefix: '/integrations', icon: '✍️' },
    ],
  },
  {
    category: 'Notifications',
    description: 'Receive workflow notifications',
    items: [
      { provider: 'SLACK', name: 'Slack', description: 'Receive workflow notifications via Slack webhook', apiPrefix: '/integrations', icon: '💬', isWebhook: true },
    ],
  },
];

export default function IntegrationsTab() {
  const [cloudConnections, setCloudConnections] = useState([]);
  const [integrationConnections, setIntegrationConnections] = useState([]);
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(null);
  const [disconnecting, setDisconnecting] = useState(null);
  const [slackUrl, setSlackUrl] = useState('');
  const [slackSaving, setSlackSaving] = useState(false);
  const [slackSuccess, setSlackSuccess] = useState(false);

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

  const handleSlackSave = async () => {
    setSlackSaving(true);
    setSlackSuccess(false);
    try {
      await configureSlackWebhook(slackUrl);
      setSlackSuccess(true);
      await fetchConnections();
      setTimeout(() => setSlackSuccess(false), 3000);
    } catch (e) {
      alert(e.response?.data?.message || 'Invalid webhook URL');
    } finally {
      setSlackSaving(false);
    }
  };

  if (loading) {
    return (
      <div className="flex items-center gap-2 text-text-muted py-8">
        <Loader className="w-4 h-4 animate-spin" /> Loading integrations...
      </div>
    );
  }

  return (
    <div className="space-y-8">
      {INTEGRATIONS.map(category => (
        <div key={category.category}>
          <h2 className="text-lg font-semibold text-text-primary mb-1">{category.category}</h2>
          <p className="text-sm text-text-muted mb-4">{category.description}</p>

          <div className="space-y-3">
            {category.items.map(item => {
              const connected = isConnected(item.provider, item.apiPrefix);
              const isConnecting = connecting === item.provider;
              const isDisconnecting = disconnecting === item.provider;

              return (
                <div key={item.provider} className="card p-4 flex items-center justify-between">
                  <div className="flex items-center gap-4">
                    <span className="text-2xl">{item.icon}</span>
                    <div>
                      <div className="font-medium text-text-primary">{item.name}</div>
                      <div className="text-sm text-text-muted">{item.description}</div>
                    </div>
                  </div>

                  <div className="flex items-center gap-3">
                    {connected && (
                      <span className="flex items-center gap-1 text-sm text-success">
                        <Check className="w-4 h-4" /> Connected
                      </span>
                    )}

                    {item.isWebhook ? (
                      /* Slack webhook — inline config */
                      connected ? (
                        <button
                          onClick={() => handleDisconnect(item)}
                          disabled={isDisconnecting}
                          className="btn-secondary text-sm text-danger"
                        >
                          {isDisconnecting ? 'Disconnecting...' : 'Disconnect'}
                        </button>
                      ) : null
                    ) : connected ? (
                      <button
                        onClick={() => handleDisconnect(item)}
                        disabled={isDisconnecting}
                        className="btn-secondary text-sm text-danger"
                      >
                        {isDisconnecting ? 'Disconnecting...' : 'Disconnect'}
                      </button>
                    ) : (
                      <button
                        onClick={() => handleConnect(item)}
                        disabled={isConnecting}
                        className="btn-primary text-sm"
                      >
                        {isConnecting ? (
                          <span className="flex items-center gap-1">
                            <Loader className="w-3 h-3 animate-spin" /> Connecting...
                          </span>
                        ) : (
                          <span className="flex items-center gap-1">
                            <ExternalLink className="w-3 h-3" /> Connect
                          </span>
                        )}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}

            {/* Slack webhook config row */}
            {category.category === 'Notifications' && (
              <div className="card p-4">
                <div className="flex items-center gap-3">
                  <input
                    type="url"
                    placeholder="https://hooks.slack.com/services/..."
                    value={slackUrl}
                    onChange={(e) => setSlackUrl(e.target.value)}
                    className="input-field flex-1 text-sm"
                  />
                  <button
                    onClick={handleSlackSave}
                    disabled={slackSaving || !slackUrl.startsWith('https://hooks.slack.com/')}
                    className="btn-primary text-sm"
                  >
                    {slackSaving ? 'Saving...' : slackSuccess ? 'Saved!' : 'Save Webhook'}
                  </button>
                </div>
                <p className="text-xs text-text-muted mt-2">
                  Paste your Slack Incoming Webhook URL. Create one at{' '}
                  <a href="https://api.slack.com/messaging/webhooks" target="_blank" rel="noopener noreferrer" className="text-primary hover:underline">
                    api.slack.com/messaging/webhooks
                  </a>
                </p>
              </div>
            )}
          </div>
        </div>
      ))}
    </div>
  );
}
