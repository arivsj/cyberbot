import React, { useState } from 'react';
import useStore from '../store';
import Modal from '../components/ui/Modal';
import { validators } from '../utils/validators';

export default function Maintenance() {
  const { createCorretiva, createRevisao, createRoutineTask, showToast } = useStore();
  const [activeTab, setActiveTab] = useState('corretiva');
  const [loading, setLoading] = useState(false);

  const tabs = [
    { id: 'corretiva', label: 'Corretiva', icon: '🔧' },
    { id: 'revisao', label: 'Revisão', icon: '🔍' },
    { id: 'rotina', label: 'Rotina', icon: '📋' },
  ];

  return (
    <div className="pb-24">
      <div className="px-4 py-4">
        <div className="flex gap-1 bg-gray-100 rounded-xl p-1 mb-6">
          {tabs.map(tab => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex-1 py-2.5 text-sm font-medium rounded-lg transition-all ${
                activeTab === tab.id ? 'bg-white text-gray-900 shadow-sm' : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              {tab.icon} {tab.label}
            </button>
          ))}
        </div>

        {activeTab === 'corretiva' && <CorretivaForm onSubmit={createCorretiva} loading={loading} setLoading={setLoading} />}
        {activeTab === 'revisao' && <RevisaoForm onSubmit={createRevisao} loading={loading} setLoading={setLoading} />}
        {activeTab === 'rotina' && <RotinaForm onSubmit={createRoutineTask} loading={loading} setLoading={setLoading} />}
      </div>
    </div>
  );
}

function CorretivaForm({ onSubmit, loading, setLoading }) {
  const [form, setForm] = useState({ nomePeca: '', kmTroca: '', vidaUtilEstimadaKm: '', descricao: '' });
  const [images, setImages] = useState([]);
  const [errors, setErrors] = useState({});

  const handleImageChange = (e) => {
    const files = Array.from(e.target.files).slice(0, 3);
    for (const file of files) {
      const error = validators.imageFile(file);
      if (error) { showToast(error, 'error'); return; }
    }
    setImages(files);
  };

  const validate = () => {
    const fields = [
      { name: 'nomePeca', validators: [(v) => validators.required(v, 'Nome da peça')] },
      { name: 'kmTroca', validators: [(v) => validators.required(v, 'Km da troca'), (v) => validators.integer(v, 'Km da troca')] },
      { name: 'vidaUtilEstimadaKm', validators: [(v) => validators.required(v, 'Vida útil estimada'), (v) => validators.positiveInteger(v, 'Vida útil estimada')] },
    ];
    const errs = validators.validateForm(fields, form);
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!validate()) return;
    setLoading(true);
    try {
      const data = {
        nomePeca: form.nomePeca.trim(),
        kmTroca: Number(form.kmTroca),
        vidaUtilEstimadaKm: Number(form.vidaUtilEstimadaKm),
        descricao: form.descricao.trim(),
      };
      await onSubmit(data, images);
      setForm({ nomePeca: '', kmTroca: '', vidaUtilEstimadaKm: '', descricao: '' });
      setImages([]);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4 animate-fade-in">
      <h2 className="text-lg font-semibold text-gray-900">Manutenção Corretiva</h2>
      <p className="text-sm text-gray-500">Registre uma troca de peça por falha ou desgaste.</p>

      <div>
        <label className="label">Nome da Peça *</label>
        <input type="text" value={form.nomePeca} onChange={(e) => setForm({ ...form, nomePeca: e.target.value })}
          placeholder="Ex: Pastilha de freio" className={`input-field ${errors.nomePeca ? 'input-error' : ''}`} />
        {errors.nomePeca && <p className="error-text">{errors.nomePeca}</p>}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="label">Km da Troca *</label>
          <input type="number" value={form.kmTroca} onChange={(e) => setForm({ ...form, kmTroca: e.target.value })}
            placeholder="Ex: 15000" className={`input-field ${errors.kmTroca ? 'input-error' : ''}`} min="0" step="1" />
          {errors.kmTroca && <p className="error-text">{errors.kmTroca}</p>}
        </div>
        <div>
          <label className="label">Vida Útil (km) *</label>
          <input type="number" value={form.vidaUtilEstimadaKm} onChange={(e) => setForm({ ...form, vidaUtilEstimadaKm: e.target.value })}
            placeholder="Ex: 10000" className={`input-field ${errors.vidaUtilEstimadaKm ? 'input-error' : ''}`} min="1" step="1" />
          {errors.vidaUtilEstimadaKm && <p className="error-text">{errors.vidaUtilEstimadaKm}</p>}
        </div>
      </div>

      <div>
        <label className="label">Descrição</label>
        <textarea value={form.descricao} onChange={(e) => setForm({ ...form, descricao: e.target.value })}
          placeholder="Descreva o serviço realizado..." className="input-field min-h-[80px] resize-none" rows={3} />
      </div>

      <div>
        <label className="label">Imagens (até 3, JPEG/PNG)</label>
        <input type="file" accept="image/jpeg,image/png" multiple onChange={handleImageChange} className="text-sm text-gray-500 file:mr-3 file:py-2 file:px-3 file:rounded-lg file:border-0 file:text-sm file:font-medium file:bg-primary-50 file:text-primary-700 hover:file:bg-primary-100" />
        {images.length > 0 && <p className="text-xs text-gray-400 mt-1">{images.length} imagem(ns) selecionada(s)</p>}
      </div>

      <button type="submit" disabled={loading} className="btn-primary w-full">
        {loading ? 'Salvando...' : 'Registrar Troca de Peça'}
      </button>
    </form>
  );
}

function RevisaoForm({ onSubmit, loading, setLoading }) {
  const [form, setForm] = useState({ kmRegistro: '', descricao: '', proximaRevisaoKm: '' });
  const [pecas, setPecas] = useState([]);
  const [showAddPeca, setShowAddPeca] = useState(false);
  const [novaPeca, setNovaPeca] = useState({ nomePeca: '', vidaUtilEstimadaKm: '' });
  const [errors, setErrors] = useState({});

  const addPeca = () => {
    if (!novaPeca.nomePeca || !novaPeca.vidaUtilEstimadaKm) return;
    setPecas([...pecas, { ...novaPeca, vidaUtilEstimadaKm: Number(novaPeca.vidaUtilEstimadaKm) }]);
    setNovaPeca({ nomePeca: '', vidaUtilEstimadaKm: '' });
    setShowAddPeca(false);
  };

  const removePeca = (index) => {
    setPecas(pecas.filter((_, i) => i !== index));
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    const fields = [
      { name: 'kmRegistro', validators: [(v) => validators.required(v, 'Km da revisão'), (v) => validators.integer(v, 'Km da revisão')] },
    ];
    const errs = validators.validateForm(fields, form);
    setErrors(errs);
    if (Object.keys(errs).length > 0) return;

    setLoading(true);
    try {
      const data = {
        kmRegistro: Number(form.kmRegistro),
        descricao: form.descricao.trim() || 'Revisão preventiva',
        proximaRevisaoKm: form.proximaRevisaoKm ? Number(form.proximaRevisaoKm) : undefined,
        pecas: pecas.length > 0 ? pecas : undefined,
      };
      await onSubmit(data);
      setForm({ kmRegistro: '', descricao: '', proximaRevisaoKm: '' });
      setPecas([]);
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4 animate-fade-in">
      <h2 className="text-lg font-semibold text-gray-900">Revisão Preventiva</h2>
      <p className="text-sm text-gray-500">Registre uma revisão periódica programada.</p>

      <div>
        <label className="label">Km da Revisão *</label>
        <input type="number" value={form.kmRegistro} onChange={(e) => setForm({ ...form, kmRegistro: e.target.value })}
          placeholder="Ex: 20000" className={`input-field ${errors.kmRegistro ? 'input-error' : ''}`} min="0" step="1" />
        {errors.kmRegistro && <p className="error-text">{errors.kmRegistro}</p>}
      </div>

      <div>
        <label className="label">Descrição</label>
        <textarea value={form.descricao} onChange={(e) => setForm({ ...form, descricao: e.target.value })}
          placeholder="Descreva a revisão..." className="input-field min-h-[60px] resize-none" rows={2} />
      </div>

      <div>
        <label className="label">Km da Próxima Revisão</label>
        <input type="number" value={form.proximaRevisaoKm} onChange={(e) => setForm({ ...form, proximaRevisaoKm: e.target.value })}
          placeholder="Ex: 25000 (opcional)" className="input-field" min="0" step="1" />
        <p className="text-xs text-gray-400 mt-1">Se não informado, será usado km atual + 5000 km.</p>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <label className="label mb-0">Peças Trocadas</label>
          <button type="button" onClick={() => setShowAddPeca(!showAddPeca)} className="text-xs text-primary-600 font-medium hover:text-primary-700">
            + Adicionar peça
          </button>
        </div>

        {showAddPeca && (
          <div className="p-3 bg-gray-50 rounded-lg mb-2 space-y-2 animate-fade-in">
            <input type="text" value={novaPeca.nomePeca} onChange={(e) => setNovaPeca({ ...novaPeca, nomePeca: e.target.value })}
              placeholder="Nome da peça" className="input-field text-sm" />
            <div className="flex gap-2">
              <input type="number" value={novaPeca.vidaUtilEstimadaKm} onChange={(e) => setNovaPeca({ ...novaPeca, vidaUtilEstimadaKm: e.target.value })}
                placeholder="Vida útil (km)" className="input-field text-sm flex-1" min="1" step="1" />
              <button type="button" onClick={addPeca} className="btn-primary text-sm px-3">Adicionar</button>
            </div>
          </div>
        )}

        {pecas.map((peca, index) => (
          <div key={index} className="flex items-center justify-between p-2 bg-gray-50 rounded-lg mb-1">
            <span className="text-sm">{peca.nomePeca} · {peca.vidaUtilEstimadaKm?.toLocaleString('pt-BR')} km</span>
            <button type="button" onClick={() => removePeca(index)} className="text-danger-500 text-xs font-medium">Remover</button>
          </div>
        ))}
      </div>

      <button type="submit" disabled={loading} className="btn-primary w-full">
        {loading ? 'Salvando...' : 'Registrar Revisão'}
      </button>
    </form>
  );
}

function RotinaForm({ onSubmit, loading, setLoading }) {
  const [form, setForm] = useState({ nomeTarefa: '', intervaloKm: '', ultimaExecucaoKm: '' });
  const [errors, setErrors] = useState({});

  const handleSubmit = async (e) => {
    e.preventDefault();
    const fields = [
      { name: 'nomeTarefa', validators: [(v) => validators.required(v, 'Nome da tarefa')] },
      { name: 'intervaloKm', validators: [(v) => validators.required(v, 'Intervalo em km'), (v) => validators.positiveInteger(v, 'Intervalo em km')] },
    ];
    const errs = validators.validateForm(fields, form);
    setErrors(errs);
    if (Object.keys(errs).length > 0) return;

    setLoading(true);
    try {
      await onSubmit({
        nomeTarefa: form.nomeTarefa.trim(),
        intervaloKm: Number(form.intervaloKm),
        ultimaExecucaoKm: form.ultimaExecucaoKm ? Number(form.ultimaExecucaoKm) : 0,
      });
      setForm({ nomeTarefa: '', intervaloKm: '', ultimaExecucaoKm: '' });
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <form onSubmit={handleSubmit} className="space-y-4 animate-fade-in">
      <h2 className="text-lg font-semibold text-gray-900">Nova Tarefa de Rotina</h2>
      <p className="text-sm text-gray-500">Cadastre tarefas periódicas como lubrificação da corrente, calibragem, etc.</p>

      <div>
        <label className="label">Nome da Tarefa *</label>
        <input type="text" value={form.nomeTarefa} onChange={(e) => setForm({ ...form, nomeTarefa: e.target.value })}
          placeholder="Ex: Lubrificação da Corrente" className={`input-field ${errors.nomeTarefa ? 'input-error' : ''}`} />
        {errors.nomeTarefa && <p className="error-text">{errors.nomeTarefa}</p>}
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="label">Intervalo (km) *</label>
          <input type="number" value={form.intervaloKm} onChange={(e) => setForm({ ...form, intervaloKm: e.target.value })}
            placeholder="Ex: 500" className={`input-field ${errors.intervaloKm ? 'input-error' : ''}`} min="1" step="1" />
          {errors.intervaloKm && <p className="error-text">{errors.intervaloKm}</p>}
        </div>
        <div>
          <label className="label">Última Execução (km)</label>
          <input type="number" value={form.ultimaExecucaoKm} onChange={(e) => setForm({ ...form, ultimaExecucaoKm: e.target.value })}
            placeholder="Ex: 14500" className="input-field" min="0" step="1" />
        </div>
      </div>

      <button type="submit" disabled={loading} className="btn-primary w-full">
        {loading ? 'Salvando...' : 'Criar Tarefa de Rotina'}
      </button>
    </form>
  );
}
