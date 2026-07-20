import React from 'react';

export default function LoadingSpinner({ size = 'md', message = '' }) {
  const sizes = { sm: 'w-4 h-4', md: 'w-8 h-8', lg: 'w-12 h-12' };

  return (
    <div className="flex flex-col items-center justify-center gap-2 py-8">
      <div className={`${sizes[size]} border-2 border-gray-200 border-t-primary-600 rounded-full animate-spin`} />
      {message && <p className="text-sm text-gray-500">{message}</p>}
    </div>
  );
}
