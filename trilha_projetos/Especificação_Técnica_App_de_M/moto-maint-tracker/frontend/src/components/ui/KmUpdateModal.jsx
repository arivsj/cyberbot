import React from 'react';
import Modal from './Modal';
import LoadingSpinner from './LoadingSpinner';

export default function KmUpdateModal({ open, onClose, kmValue, onKmChange, onSubmit, error, loading }) {
  if (!open) return null;

  return (
    <Modal open={open} onClose={onClose} title="Atualizar Quilometragem">
      <p className="text-sm text-gray-600 mb-4">
        Já se passaram mais de 24 horas desde sua última atualização. Registre a quilometragem atual da sua moto.
      </p>
      <form onSubmit={(e) => { e.preventDefault(); onSubmit(kmValue); }} className="space-y-4">
        <div>
          <label className="label">Quilometragem Atual (km)</label>
          <input
            type="number"
            value={kmValue}
            onChange={(e) => onKmChange(e.target.value)}
            placeholder="Ex: 15000"
            className={`input-field ${error ? 'input-error' : ''}`}
            autoFocus
            min="0"
            step="1"
          />
          {error && <p className="error-text">{error}</p>}
        </div>
        <div className="flex gap-3">
          <button type="button" onClick={onClose} className="btn-secondary flex-1">
            Agora não
          </button>
          <button type="submit" disabled={loading || !kmValue} className="btn-primary flex-1">
            {loading ? <LoadingSpinner size="sm" /> : 'Atualizar'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
