import api from './client';

export const getIntegrationConnections = () => api.get('/integrations/connections');
export const getIntegrationAuthUrl = (provider) => api.get(`/integrations/auth-url?provider=${provider}`);
export const disconnectIntegration = (provider) => api.delete(`/integrations/disconnect?provider=${provider}`);
export const configureSlackWebhook = (webhookUrl) => api.post('/integrations/slack/configure', { webhookUrl });
