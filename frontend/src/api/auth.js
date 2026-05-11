import apiClient from './client';

export async function register(email, password, displayName) {
  const { data } = await apiClient.post('/auth/register', { email, password, displayName });
  return data;
}

export async function login(email, password, rememberMe = false) {
  const { data } = await apiClient.post('/auth/login', { email, password, rememberMe });
  return data;
}

export async function validateMfa(token, code) {
  const { data } = await apiClient.post('/auth/mfa/validate', { token, code });
  return data;
}

export async function changePassword(currentPassword, newPassword, token = null) {
  const body = { currentPassword, newPassword };
  if (token) body.token = token;
  await apiClient.post('/auth/change-password', body);
}

export async function getMe() {
  const { data } = await apiClient.get('/auth/me');
  return data;
}

export async function setupMfa() {
  const { data } = await apiClient.post('/auth/mfa/setup');
  return data;
}

export async function verifyMfa(code) {
  await apiClient.post('/auth/mfa/verify', { code });
}

export async function disableMfa() {
  await apiClient.post('/auth/mfa/disable');
}

export async function enableEmailMfa() {
  await apiClient.post('/auth/mfa/enable-email');
}

export async function getBackupCodesRemaining() {
  const { data } = await apiClient.get('/auth/mfa/backup-codes/remaining');
  return data;
}

export async function regenerateBackupCodes() {
  const { data } = await apiClient.post('/auth/mfa/backup-codes/regenerate');
  return data;
}

export async function getTrustedDevices() {
  const { data } = await apiClient.get('/auth/mfa/trusted-devices');
  return data;
}

export async function revokeAllDevices() {
  await apiClient.post('/auth/mfa/trusted-devices/revoke');
}
