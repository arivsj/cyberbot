import { useState, useEffect, useCallback } from 'react';
import useStore from '../store';

export function useKmUpdate() {
  const { bike, updateKm } = useStore();
  const [showKmModal, setShowKmModal] = useState(false);
  const [newKm, setNewKm] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!bike?.ultimaAtualizacaoKm) return;
    const lastUpdate = new Date(bike.ultimaAtualizacaoKm);
    const hoursSinceUpdate = (Date.now() - lastUpdate.getTime()) / (1000 * 60 * 60);
    if (hoursSinceUpdate >= 24) {
      setShowKmModal(true);
    }
  }, [bike?.ultimaAtualizacaoKm]);

  const handleUpdateKm = useCallback(async (km) => {
    setError('');
    setLoading(true);
    try {
      const kmValue = parseInt(km, 10);
      if (isNaN(kmValue) || kmValue < 0) {
        setError('Informe um valor de quilometragem válido.');
        setLoading(false);
        return;
      }
      if (bike.kmAtual && kmValue < bike.kmAtual) {
        setError('A quilometragem não pode ser menor que a atual. Use ajuste manual se necessário.');
        setLoading(false);
        return;
      }
      await updateKm(kmValue);
      setShowKmModal(false);
      setNewKm('');
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [bike, updateKm]);

  return { showKmModal, setShowKmModal, newKm, setNewKm, error, loading, handleUpdateKm };
}

export function useOffline() {
  const { syncing, syncMessage, setSyncing } = useStore();
  const [isOnline, setIsOnline] = useState(navigator.onLine);

  useEffect(() => {
    const handleOnline = () => setIsOnline(true);
    const handleOffline = () => setIsOnline(false);
    window.addEventListener('online', handleOnline);
    window.addEventListener('offline', handleOffline);
    return () => {
      window.removeEventListener('online', handleOnline);
      window.removeEventListener('offline', handleOffline);
    };
  }, []);

  return { isOnline, syncing, syncMessage };
}
