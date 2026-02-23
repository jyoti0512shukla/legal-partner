import axios from 'axios';

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 120000,
});

export function setAuthHeader(username, password) {
  const encoded = btoa(`${username}:${password}`);
  api.defaults.headers.common['Authorization'] = `Basic ${encoded}`;
}

export function clearAuthHeader() {
  delete api.defaults.headers.common['Authorization'];
}

export default api;
