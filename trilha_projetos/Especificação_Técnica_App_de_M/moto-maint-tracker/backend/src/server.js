require('dotenv').config();
const app = require('./app');
const { initializeFirebase } = require('./config/firebase');

const PORT = process.env.PORT || 3001;

try {
  initializeFirebase();
  console.log('Firebase initialized successfully.');
} catch (error) {
  console.warn('Firebase initialization failed. Running in mock mode:', error.message);
}

app.listen(PORT, () => {
  console.log(`Moto-Maint Tracker API running on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/api/health`);
});
