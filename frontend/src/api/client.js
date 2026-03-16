import axios from 'axios';

const apiClient = axios.create({
  baseURL: '/api/v1',
  timeout: 180000,
  withCredentials: true,  // send httpOnly jwt cookie automatically
  headers: { 'Content-Type': 'application/json' }
});

// Response interceptor: redirect to login on 401
apiClient.interceptors.response.use(
  response => response,
  error => {
    if (error.response?.status === 401) {
      // Clear any cached user state and go to login
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

export default apiClient;
