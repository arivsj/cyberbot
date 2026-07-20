const express = require('express');
const cors = require('cors');
const helmet = require('helmet');
const morgan = require('morgan');

const bikeRoutes = require('./routes/bike');
const maintenanceRoutes = require('./routes/maintenance');
const routineRoutes = require('./routes/routine');
const alertRoutes = require('./routes/alerts');
const reportRoutes = require('./routes/report');

const app = express();

app.use(helmet());
app.use(cors({ origin: process.env.FRONTEND_URL || '*', credentials: true }));
app.use(morgan('dev'));
app.use(express.json({ limit: '10mb' }));
app.use(express.urlencoded({ extended: true }));

app.get('/api/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.use('/api/bike', bikeRoutes);
app.use('/api/maintenance', maintenanceRoutes);
app.use('/api/routine', routineRoutes);
app.use('/api/alerts', alertRoutes);
app.use('/api/reports', reportRoutes);

app.use((err, req, res, next) => {
  console.error(err.stack);
  if (err.type === 'entity.parse.failed') {
    return res.status(400).json({ error: 'JSON inválido no corpo da requisição.' });
  }
  res.status(500).json({ error: 'Erro interno do servidor.' });
});

module.exports = app;
