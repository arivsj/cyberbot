import React from 'react';

export default function EmptyState({ icon = '📋', title, message, action }) {
  return (
    <div className="flex flex-col items-center justify-center py-12 px-4 text-center">
      <span className="text-4xl mb-3">{icon}</span>
      <h3 className="text-lg font-medium text-gray-900 mb-1">{title}</h3>
      <p className="text-sm text-gray-500 mb-4">{message}</p>
      {action}
    </div>
  );
}
