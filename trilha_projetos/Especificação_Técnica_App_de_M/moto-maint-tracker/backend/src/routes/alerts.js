const express = require('express');
const router = express.Router();
const { authenticate } = require('../middleware/auth');
const AlertService = require('../services/alertService');

router.use(authenticate);

router.get('/dashboard', async (req, res) => {
  try {
    const data = await AlertService.getDashboardAlerts(req.user.uid);
    res.json(data);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

router.get('/critical', async (req, res) => {
  try {
    const alerts = await AlertService.getCriticalAlerts(req.user.uid);
    res.json(alerts);
  } catch (error) {
    res.status(500).json({ error: error.message });
  }
});

module.exports = router;
