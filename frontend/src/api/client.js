import axios from 'axios';

const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 180000,
  withCredentials: true,  // send httpOnly jwt cookie automatically
  headers: { 'Content-Type': 'application/json' }
});

// Transient-failure retry. Most user-visible "errors" on this app are actually
// ngrok/vLLM hiccups: the tunnel dropped, vLLM was slow to respond, etc. We
// silently retry up to 2 times with exponential backoff before surfacing.
//
// We DO NOT retry:
//  - 4xx except 408/429 (client bug, auth, not-found, etc.)
//  - Streaming requests (SSE would duplicate events)
//  - Requests that already retried twice
const RETRYABLE_STATUSES = new Set([408, 429, 502, 503, 504]);

function isRetryable(error) {
  // Network / timeout → retry
  if (!error.response) {
    const code = error.code;
    if (code === 'ECONNABORTED' || code === 'ERR_NETWORK' || code === 'ETIMEDOUT') return true;
    return false;
  }
  return RETRYABLE_STATUSES.has(error.response.status);
}

apiClient.interceptors.response.use(
  response => response,
  async error => {
    const config = error.config || {};

    // Skip retry on SSE / streaming responses
    const isStream = (config.headers && /text\/event-stream/.test(config.headers.Accept || ''))
                     || (config.url && config.url.includes('/stream'));

    if (!isStream && isRetryable(error)) {
      config.__retryCount = config.__retryCount || 0;
      if (config.__retryCount < 2) {
        config.__retryCount += 1;
        const delay = 1500 * Math.pow(2, config.__retryCount - 1); // 1.5s, 3s
        await new Promise(r => setTimeout(r, delay));
        return apiClient(config);
      }
    }

    if (error.response?.status === 401) {
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default apiClient;
