const express = require('express');
const router = express.Router();
const { authenticate } = require('../middleware/auth');
const ReportService = require('../services/reportService');

router.use(authenticate);

router.get('/', async (req, res) => {
  try {
    const { tipo, startDate, endDate, minKm, maxKm } = req.query;
    const history = await ReportService.getFilteredHistory(req.user.uid, { tipo, startDate, endDate, minKm, maxKm });
    res.json(history);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.get('/pdf', async (req, res) => {
  try {
    const { tipo, startDate, endDate, minKm, maxKm } = req.query;
    const pdfBuffer = await ReportService.generateMaintenanceReport(req.user.uid, { tipo, startDate, endDate, minKm, maxKm });

    res.setHeader('Content-Type', 'application/pdf');
    res.setHeader('Content-Disposition', `attachment; filename=relatorio_manutencao_${Date.now()}.pdf`);
    res.send(pdfBuffer);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

module.exports = router;
