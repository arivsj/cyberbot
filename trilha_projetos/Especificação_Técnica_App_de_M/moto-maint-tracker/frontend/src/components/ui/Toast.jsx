import React from 'react';
import useStore from '../../store';

export default function Toast() {
  const { toast, clearToast } = useStore();

  if (!toast) return null;

  const typeStyles = {
    success: 'toast-success',
    error: 'toast-error',
    info: 'toast-info',
  };

  const icons = {
    success: '✓',
    error: '✕',
    info: 'ℹ',
  };

  return (
    <div className={`toast ${typeStyles[toast.type]}`}>
      <div className="flex items-center gap-2">
        <span className="text-lg">{icons[toast.type]}</span>
        <span>{toast.message}</span>
        <button onClick={clearToast} className="ml-2 opacity-70 hover:opacity-100">
          ✕
        </button>
      </div>
    </div>
  );
}
