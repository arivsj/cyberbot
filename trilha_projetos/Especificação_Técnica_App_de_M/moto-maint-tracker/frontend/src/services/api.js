const API_BASE = '/api';

let authToken = null;

export function setAuthToken(token) {
  authToken = token;
}

export function getAuthToken() {
  return authToken;
}

async function request(endpoint, options = {}) {
  const config = {
    headers: {
      'Content-Type': 'application/json',
      ...(authToken ? { Authorization: `Bearer ${authToken}` } : {}),
      ...options.headers,
    },
    ...options,
  };

  if (config.body && typeof config.body === 'object' && !(config.body instanceof FormData)) {
    config.body = JSON.stringify(config.body);
  }

  if (config.headers['Content-Type'] === 'multipart/form-data') {
    delete config.headers['Content-Type'];
  }

  const response = await fetch(`${API_BASE}${endpoint}`, config);
  const data = await response.json();

  if (!response.ok) {
    throw new Error(data.error || `Erro ${response.status}`);
  }

  return data;
}

export const api = {
  bike: {
    get: () => request('/bike'),
    create: (data) => request('/bike', { method: 'POST', body: data }),
    update: (data) => request('/bike', { method: 'PUT', body: data }),
    updateKm: (kmAtual, force = false) => request('/bike/km', { method: 'PATCH', body: { kmAtual, force } }),
    getKmHistory: () => request('/bike/km-history'),
  },
  maintenance: {
    list: (filters = {}) => {
      const params = new URLSearchParams(filters).toString();
      return request(`/maintenance?${params}`);
    },
    create: (data, images = []) => {
      if (images.length > 0) {
        const formData = new FormData();
        Object.entries(data).forEach(([key, value]) => formData.append(key, value));
        images.forEach(img => formData.append('imagens', img));
        return request('/maintenance', { method: 'POST', body: formData });
      }
      return request('/maintenance', { method: 'POST', body: data });
    },
    corretiva: (data, images = []) => {
      const formData = new FormData();
      Object.entries(data).forEach(([key, value]) => formData.append(key, value));
      images.forEach(img => formData.append('imagens', img));
      return request('/maintenance/corretiva', { method: 'POST', body: formData });
    },
    revisao: (data) => request('/maintenance/revisao', { method: 'POST', body: data }),
    addPart: (logId, data) => request(`/maintenance/${logId}/parts`, { method: 'POST', body: data }),
  },
  routine: {
    list: () => request('/routine'),
    create: (data) => request('/routine', { method: 'POST', body: data }),
    update: (taskId, data) => request(`/routine/${taskId}`, { method: 'PUT', body: data }),
    complete: (taskId, kmAtual) => request(`/routine/${taskId}/complete`, { method: 'POST', body: { kmAtual } }),
    delete: (taskId) => request(`/routine/${taskId}`, { method: 'DELETE' }),
  },
  alerts: {
    dashboard: () => request('/alerts/dashboard'),
    critical: () => request('/alerts/critical'),
  },
  reports: {
    list: (filters = {}) => {
      const params = new URLSearchParams(filters).toString();
      return request(`/reports?${params}`);
    },
    pdf: async (filters = {}) => {
      const params = new URLSearchParams(filters).toString();
      const response = await fetch(`${API_BASE}/reports/pdf?${params}`, {
        headers: authToken ? { Authorization: `Bearer ${authToken}` } : {},
      });
      if (!response.ok) throw new Error('Erro ao gerar PDF');
      return response.blob();
    },
  },
};
