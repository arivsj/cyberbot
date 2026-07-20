export const validators = {
  required: (value, fieldName) => {
    if (!value || (typeof value === 'string' && !value.trim())) {
      return `${fieldName} é obrigatório.`;
    }
    return null;
  },

  integer: (value, fieldName) => {
    const num = Number(value);
    if (!Number.isInteger(num)) {
      return `${fieldName} deve ser um número inteiro.`;
    }
    return null;
  },

  positiveInteger: (value, fieldName) => {
    const num = Number(value);
    if (!Number.isInteger(num) || num <= 0) {
      return `${fieldName} deve ser um número inteiro positivo.`;
    }
    return null;
  },

  kmNotRetroceder: (newKm, currentKm, fieldName) => {
    if (Number(newKm) < Number(currentKm)) {
      return `${fieldName} não pode retroceder. Valor atual: ${currentKm} km.`;
    }
    return null;
  },

  imageFile: (file) => {
    if (!file) return null;
    const allowedTypes = ['image/jpeg', 'image/png'];
    if (!allowedTypes.includes(file.type)) {
      return 'Apenas imagens JPEG e PNG são permitidas.';
    }
    if (file.size > 5 * 1024 * 1024) {
      return 'A imagem deve ter no máximo 5MB.';
    }
    return null;
  },

  validateForm: (fields, values) => {
    const errors = {};
    for (const { name, validators: fieldValidators } of fields) {
      for (const validator of fieldValidators) {
        const error = validator(values[name]);
        if (error) {
          errors[name] = error;
          break;
        }
      }
    }
    return errors;
  },
};

export const formatKm = (km) => {
  if (km === null || km === undefined) return '-';
  return Number(km).toLocaleString('pt-BR') + ' km';
};

export const formatDate = (date) => {
  if (!date) return '-';
  const d = new Date(date);
  return d.toLocaleDateString('pt-BR', { day: '2-digit', month: 'short', year: 'numeric' });
};

export const getSeverityColor = (status) => {
  const colors = {
    overdue: 'text-danger-600 bg-danger-50 border-danger-200',
    critical: 'text-danger-500 bg-danger-50 border-danger-200',
    warning: 'text-warning-500 bg-warning-50 border-warning-200',
    ok: 'text-success-600 bg-success-50 border-success-200',
  };
  return colors[status] || 'text-gray-600 bg-gray-50 border-gray-200';
};

export const getSeverityIcon = (status) => {
  const icons = { overdue: '🔴', critical: '🟠', warning: '🟡', ok: '🟢' };
  return icons[status] || '⚪';
};
