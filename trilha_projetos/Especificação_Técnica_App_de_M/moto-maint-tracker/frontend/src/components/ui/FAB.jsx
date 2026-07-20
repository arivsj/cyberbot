import React from 'react';

export default function FAB({ onClick, icon = '+' }) {
  return (
    <button
      onClick={onClick}
      className="fab"
      aria-label="Ação rápida"
    >
      <span className="text-2xl font-light leading-none">{icon}</span>
    </button>
  );
}
