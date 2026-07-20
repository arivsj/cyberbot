import React, { useState } from 'react';
import useStore from '../store';
import { validators } from '../utils/validators';

export default function Onboarding() {
  const { createBike, showToast } = useStore();
  const [step, setStep] = useState(1);
  const [form, setForm] = useState({ apelidoMoto: '', marcaModelo: '', anoFabricacao: '', kmAtual: '' });
  const [errors, setErrors] = useState({});
  const [loading, setLoading] = useState(false);

  const validateStep1 = () => {
    const fields = [
      { name: 'apelidoMoto', validators: [(v) => validators.required(v, 'Apelido da Moto')] },
      { name: 'marcaModelo', validators: [(v) => validators.required(v, 'Marca / Modelo')] },
    ];
    const errs = validators.validateForm(fields, form);
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const validateStep2 = () => {
    const fields = [
      { name: 'kmAtual', validators: [
        (v) => validators.required(v, 'Quilometragem atual'),
        (v) => validators.integer(v, 'Quilometragem atual'),
        (v) => v > 0 ? null : 'A quilometragem deve ser maior que zero.',
      ]},
    ];
    if (form.anoFabricacao) {
      fields.push({
        name: 'anoFabricacao',
        validators: [
          (v) => validators.integer(v, 'Ano de fabricação'),
          (v) => v >= 1900 && v <= 2030 ? null : 'Ano inválido.',
        ],
      });
    }
    const errs = validators.validateForm(fields, form);
    setErrors(errs);
    return Object.keys(errs).length === 0;
  };

  const handleNext = () => {
    if (step === 1 && validateStep1()) setStep(2);
    else if (step === 2 && validateStep2()) handleSubmit();
  };

  const handleSubmit = async () => {
    setLoading(true);
    try {
      await createBike({
        apelidoMoto: form.apelidoMoto.trim(),
        marcaModelo: form.marcaModelo.trim(),
        anoFabricacao: form.anoFabricacao ? Number(form.anoFabricacao) : null,
        kmAtual: Number(form.kmAtual),
      });
      showToast('Moto cadastrada com sucesso!', 'success');
    } catch (error) {
      showToast(error.message, 'error');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-gradient-to-b from-primary-600 to-primary-800 flex flex-col">
      <div className="flex-1 flex flex-col items-center justify-center px-6">
        <div className="w-20 h-20 bg-white/20 rounded-2xl flex items-center justify-center mb-6">
          <span className="text-4xl">🏍️</span>
        </div>
        <h1 className="text-2xl font-bold text-white mb-2">Moto-Maint Tracker</h1>
        <p className="text-primary-200 text-sm mb-8 text-center">Gerencie a manutenção da sua moto de forma inteligente</p>

        <div className="w-full max-w-sm bg-white rounded-2xl shadow-xl p-6">
          {/* Progresso */}
          <div className="flex gap-2 mb-6">
            <div className={`h-1 flex-1 rounded-full ${step >= 1 ? 'bg-primary-600' : 'bg-gray-200'}`} />
            <div className={`h-1 flex-1 rounded-full ${step >= 2 ? 'bg-primary-600' : 'bg-gray-200'}`} />
          </div>

          {step === 1 && (
            <div className="space-y-4 animate-fade-in">
              <h2 className="text-lg font-semibold text-gray-900">Identificação da Moto</h2>
              <div>
                <label className="label">Apelido da Moto *</label>
                <input
                  type="text"
                  value={form.apelidoMoto}
                  onChange={(e) => setForm({ ...form, apelidoMoto: e.target.value })}
                  placeholder="Ex: Fitinha"
                  className={`input-field ${errors.apelidoMoto ? 'input-error' : ''}`}
                />
                {errors.apelidoMoto && <p className="error-text">{errors.apelidoMoto}</p>}
              </div>
              <div>
                <label className="label">Marca / Modelo *</label>
                <input
                  type="text"
                  value={form.marcaModelo}
                  onChange={(e) => setForm({ ...form, marcaModelo: e.target.value })}
                  placeholder="Ex: Honda CG 160"
                  className={`input-field ${errors.marcaModelo ? 'input-error' : ''}`}
                />
                {errors.marcaModelo && <p className="error-text">{errors.marcaModelo}</p>}
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="space-y-4 animate-fade-in">
              <h2 className="text-lg font-semibold text-gray-900">Quilometragem Inicial</h2>
              <div>
                <label className="label">Quilometragem Atual (km) *</label>
                <input
                  type="number"
                  value={form.kmAtual}
                  onChange={(e) => setForm({ ...form, kmAtual: e.target.value })}
                  placeholder="Ex: 5000"
                  className={`input-field ${errors.kmAtual ? 'input-error' : ''}`}
                  min="0"
                  step="1"
                />
                {errors.kmAtual && <p className="error-text">{errors.kmAtual}</p>}
              </div>
              <div>
                <label className="label">Ano de Fabricação</label>
                <input
                  type="number"
                  value={form.anoFabricacao}
                  onChange={(e) => setForm({ ...form, anoFabricacao: e.target.value })}
                  placeholder="Ex: 2022"
                  className={`input-field ${errors.anoFabricacao ? 'input-error' : ''}`}
                  min="1900"
                  max="2030"
                  step="1"
                />
                {errors.anoFabricacao && <p className="error-text">{errors.anoFabricacao}</p>}
              </div>
            </div>
          )}

          <button
            onClick={handleNext}
            disabled={loading}
            className="btn-primary w-full mt-6"
          >
            {loading ? (
              <div className="flex items-center justify-center gap-2">
                <div className="w-4 h-4 border-2 border-white/30 border-t-white rounded-full animate-spin" />
                <span>Salvando...</span>
              </div>
            ) : step === 1 ? 'Próximo' : 'Começar!'}
          </button>
        </div>
      </div>
    </div>
  );
}
