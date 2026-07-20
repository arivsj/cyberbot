function brDate(s) {
  if (!s) return "-";
  let d = s.split(" ")[0];
  if (d.includes("-")) {
    const p = d.split("-");
    if (p.length === 3) d = `${p[2]}/${p[1]}/${p[0]}`;
  }
  let t = s.split(" ")[1] || "";
  if (t) {
    const parts = t.split(":");
    if (parts.length >= 2) t = `${parts[0]}:${parts[1]}`;
  }
  return t ? `${d} ${t}` : d;
}

let botRunning = false;
let ollamaRunning = false;
let selectedMonth = "";
let currentDriveFolder = null;

document.querySelectorAll("nav a[data-module]").forEach((a) => {
  a.addEventListener("click", () => {
    if (a.classList.contains("disabled")) return;
    document.querySelectorAll("nav a").forEach((x) => x.classList.remove("active"));
    a.classList.add("active");
    document.querySelectorAll(".module").forEach((m) => m.classList.add("hidden"));
    document.getElementById(`module-${a.dataset.module}`).classList.remove("hidden");
  });
});

async function toggleBot() {
  const btn = document.getElementById("btnBot");
  if (!ollamaRunning) {
    btn.textContent = "⏳ AGUARDANDO OLLAMA";
    setTimeout(() => {
      btn.textContent = botRunning ? "■ PARAR BOT" : "▶ INICIAR BOT";
    }, 2000);
    return;
  }
  if (botRunning) {
    await api.post("/api/bot/stop");
    botRunning = false;
    btn.textContent = "▶ INICIAR BOT";
    btn.classList.remove("btn-stop");
    setBotStatus(false);
  } else {
    await api.post("/api/bot/start");
    botRunning = true;
    btn.textContent = "■ PARAR BOT";
    btn.classList.add("btn-stop");
    setBotStatus(true);
  }
}

function setBotStatus(online) {
  const el = document.getElementById("botStatus");
  el.textContent = online ? "ONLINE" : "OFFLINE";
  el.className = `status ${online ? "online" : "offline"}`;
  document.getElementById("statusBot").textContent = online ? "Online" : "Offline";
  document.getElementById("statusBot").style.color = online ? "var(--neon)" : "var(--danger)";
}

async function loadDashboard() {
  try {
    const { resumo, categorias } = await api.get("/api/financas/resumo");
    document.getElementById("totalTransacoes").textContent = resumo.total_transacoes;
    document.getElementById("gastoMes").textContent = `R$ ${resumo.gasto_mes.toFixed(2)}`;
    const top = categorias[0];
    document.getElementById("topCategoria").textContent = top ? `${top.categoria} (R$ ${top.total.toFixed(2)})` : "-";
    await loadStatus();
    await loadDriveStats();
    await loadLogStats();
    await loadWhitelistStats();
    await loadUsersCount();
  } catch (e) {
    console.log("API não disponível ainda");
  }
}

let logDateFilter = "";
let logDatesCache = [];

function todayStr() {
  return new Date().toISOString().split("T")[0];
}

async function loadLogStats() {
  const date = logDateFilter || todayStr();
  try {
    const [entries, dates] = await Promise.all([
      api.get(`/api/logs/date/${date}`).catch(() => []),
      api.get("/api/logs/dates").catch(() => []),
    ]);
    logDatesCache = dates;
    const blocked = entries.filter((e) => e.type === "blocked");
    document.getElementById("logDateDisplay").textContent = brDate(date);
    document.getElementById("logTotal").textContent = entries.length;
    document.getElementById("logBlocked").textContent = blocked.length;
    document.getElementById("logSize").textContent = "📂 Abrir";
    const el = document.getElementById("logChart");
    const by_hour = {};
    entries.forEach((e) => {
      try {
        const h = (e.ts || "").split(" ")[1]?.split(":")[0];
        if (h) by_hour[h] = (by_hour[h] || 0) + 1;
      } catch (_) {}
    });
    const hours = Object.keys(by_hour).sort();
    if (hours.length === 0) {
      el.innerHTML = '<p class="empty" style="padding:20px">Nenhum registro nesta data</p>';
      return;
    }
    const max = Math.max(...hours.map((h) => by_hour[h]), 1);
    el.innerHTML = hours.map(
      (h) => `
      <div class="bar-item">
        <div class="bar-label">${h}h</div>
        <div class="bar-track">
          <div class="bar-fill log" style="width:${(by_hour[h] / max) * 100}%"></div>
        </div>
        <div class="bar-val">${by_hour[h]}x</div>
      </div>`
    ).join("");
    const det = document.getElementById("logDetailContent");
    if (!document.getElementById("logDetail").classList.contains("hidden")) {
      renderLogTable(entries, det);
    }
  } catch (_) {}
}

function renderLogTable(entries, container) {
  if (!entries.length) {
    container.innerHTML = '<p class="empty" style="padding:20px">Nenhum registro</p>';
    return;
  }
  container.innerHTML = `<table><thead><tr><th>Horário</th><th>Usuário</th><th>Tipo</th><th>Resumo</th></tr></thead><tbody>${
    entries.map((e) => {
      const uname = e.username ? `@${e.username}` : "";
      const nome = e.first_name ? `${e.first_name} ${uname}`.trim() : uname || e.user_id || "-";
      return `<tr><td>${(e.ts || "").split(" ")[1] || e.ts}</td><td title="ID: ${e.user_id}">${nome}</td><td>${e.type}</td><td>${(e.summary || "").substring(0, 60)}</td></tr>`;
    }).join("")
  }</tbody></table>`;
}

let logDetailLoaded = false;
async function toggleLogDetail() {
  const el = document.getElementById("logDetail");
  const toggle = document.getElementById("logToggle");
  if (el.classList.contains("hidden")) {
    el.classList.remove("hidden");
    toggle.textContent = "▼";
    if (!logDetailLoaded) {
      logDetailLoaded = true;
      const date = logDateFilter || todayStr();
      const entries = await api.get(`/api/logs/date/${date}`).catch(() => []);
      renderLogTable(entries, document.getElementById("logDetailContent"));
    }
  } else {
    el.classList.add("hidden");
    toggle.textContent = "▶";
  }
}

// ─── Date picker ────────────────────────────────────────

async function buildDatePicker() {
  const picker = document.getElementById("logDatePicker");
  if (logDatesCache.length === 0) {
    logDatesCache = await api.get("/api/logs/dates").catch(() => []);
  }
  const years = [...new Set(logDatesCache.map((d) => d.split("-")[0]))].sort().reverse();
  picker.innerHTML = `<div class="dp-level" id="dpYears">${years.map((y) =>
    `<button class="dp-btn" data-year="${y}">${y}</button>`
  ).join("")}</div>`;
  picker.classList.remove("hidden");
  document.querySelectorAll("#dpYears .dp-btn").forEach((btn) => {
    btn.addEventListener("click", () => showMonths(btn.dataset.year));
  });
}

function showMonths(year) {
  const months = [...new Set(logDatesCache.filter((d) => d.startsWith(year)).map((d) => d.split("-")[1]))].sort();
  const picker = document.getElementById("logDatePicker");
  const levels = picker.querySelectorAll(".dp-level:not(#dpYears)");
  levels.forEach((l) => l.remove());
  const div = document.createElement("div");
  div.className = "dp-level dp-level-months";
  div.innerHTML = `<button class="dp-btn dp-back" data-action="back-years">◀ ANOS</button>` +
    months.map((m) => `<button class="dp-btn" data-date="${year}-${m}">${m}</button>`).join("");
  picker.appendChild(div);
  div.querySelectorAll(".dp-btn[data-date]").forEach((btn) => {
    btn.addEventListener("click", () => showDays(btn.dataset.date));
  });
  div.querySelector("[data-action=back-years]")?.addEventListener("click", buildDatePicker);
}

function showDays(ym) {
  const days = logDatesCache.filter((d) => d.startsWith(ym)).map((d) => parseInt(d.split("-")[2])).sort((a, b) => a - b);
  const picker = document.getElementById("logDatePicker");
  const existing = picker.querySelector(".dp-level.dp-level-days");
  if (existing) existing.remove();
  const div = document.createElement("div");
  div.className = "dp-level dp-level-days";
  div.innerHTML = `<button class="dp-btn dp-back" data-action="back-months">◀ MESES</button>` +
    days.map((d) => {
      const ds = `${ym}-${String(d).padStart(2, "0")}`;
      return `<button class="dp-btn${ds === todayStr() ? " dp-today" : ""}" data-date="${ds}">${d}</button>`;
    }).join("");
  picker.appendChild(div);
  div.querySelectorAll(".dp-btn[data-date]").forEach((btn) => {
    btn.addEventListener("click", () => selectDate(btn.dataset.date));
  });
  div.querySelector("[data-action=back-months]")?.addEventListener("click", () => showMonths(ym.split("-")[0]));
}

function selectDate(date) {
  logDateFilter = date;
  document.getElementById("logDatePicker").classList.add("hidden");
  logDetailLoaded = false;
  document.getElementById("logDetail").classList.add("hidden");
  document.getElementById("logToggle").textContent = "▶";
  loadLogStats();
}

document.getElementById("logDateFilter")?.addEventListener("click", async () => {
  const picker = document.getElementById("logDatePicker");
  if (picker.classList.contains("hidden")) {
    await buildDatePicker();
  } else {
    picker.classList.add("hidden");
  }
});

// ─── End date picker ────────────────────────────────────

async function loadDriveStats() {
  try {
    const s = await api.get("/api/drive/stats");
    document.getElementById("drivePastas").textContent = s.pastas;
    document.getElementById("driveArquivos").textContent = s.arquivos;
    const totalMB = (s.total_size / (1024 * 1024)).toFixed(1);
    document.getElementById("driveTamanho").textContent = `${totalMB} MB`;
  } catch (_) {}
}

async function loadStatus() {
  try {
    const s = await api.get("/api/status");
    botRunning = s.bot;
    ollamaRunning = s.ollama;
    setBotStatus(s.bot);
    const statusEl = document.getElementById("statusOllama");
    if (statusEl) {
      statusEl.textContent = s.ollama ? `Online (${s.modelo})` : "Offline";
      statusEl.style.color = s.ollama ? "var(--neon)" : "var(--danger)";
      statusEl.onclick = changeModel;
    }
    const ctxEl = document.getElementById("statusContexto");
    if (ctxEl) {
      if (s.ollama && s.contexto) {
        const maxCtx = s.contexto.max_context.toLocaleString();
        const optCtx = s.contexto.optimal_context.toLocaleString();
        ctxEl.textContent = `${optCtx}/${maxCtx}`;
        ctxEl.title = `Contexto ideal: ${optCtx} tokens | Máximo do modelo: ${maxCtx} tokens`;
        ctxEl.style.color = "var(--neon)";
      } else {
        ctxEl.textContent = "-";
        ctxEl.style.color = "var(--danger)";
      }
    }
    const btn = document.getElementById("btnBot");
    if (s.bot) {
      btn.textContent = "■ PARAR BOT";
      btn.classList.add("btn-stop");
    } else {
      btn.textContent = "▶ INICIAR BOT";
      btn.classList.remove("btn-stop");
    }
    if (!s.ollama) {
      btn.textContent = "⏳ OLLAMA OFFLINE";
    }
  } catch (_) {}
}

function mesQuery() {
  return selectedMonth ? `?mes=${selectedMonth}` : "";
}

async function loadFinancas() {
  try {
    const qs = mesQuery();
    const { categorias, contas, meses } = await api.get(`/api/financas/resumo${qs}`);

    renderChart("graficoCat", categorias, "cat");
    renderChart("graficoConta", contas, "conta");

    populateMonths(meses);

    const transacoes = await api.get(`/api/financas${qs}`);
    const tbody = document.querySelector("#tabelaFinancas tbody");
    tbody.innerHTML = transacoes
      .map(
        (t) =>
          `<tr><td>${brDate(t.data)}</td><td>${t.categoria}</td><td>${t.conta}</td><td>R$ ${t.valor.toFixed(2)}</td><td>${t.descricao}</td></tr>`
      )
      .join("");
  } catch (e) {
    console.log("Erro ao carregar finanças", e);
  }
}

function populateMonths(meses) {
  const sel = document.getElementById("mesSelect");
  if (!sel) return;
  const current = sel.value;
  sel.innerHTML = '<option value="">Todos</option>' +
    meses.map((m) => `<option value="${m}"${m === current ? " selected" : ""}>${m}</option>`).join("");
  if (current && meses.includes(current)) sel.value = current;
}

function changeMonth(mes) {
  selectedMonth = mes;
  loadFinancas();
}

function renderChart(id, data, cls) {
  const el = document.getElementById(id);
  const max = Math.max(...data.map((d) => d.total), 1);
  el.innerHTML = data
    .map(
      (d) => `
      <div class="bar-item">
        <div class="bar-label">${d.categoria || d.conta}</div>
        <div class="bar-track">
          <div class="bar-fill ${cls}" style="width:${(d.total / max) * 100}%"></div>
        </div>
        <div class="bar-val">R$ ${d.total.toFixed(2)} (${d.qtde}x)</div>
      </div>`
    )
    .join("");
}

async function loadDrive(folderId) {
  currentDriveFolder = folderId != null ? folderId : null;
  try {
    const [folders, files] = await Promise.all([
      api.get(`/api/drive/folders${folderId != null ? `?parent=${folderId}` : ""}`),
      api.get(`/api/drive/files${folderId != null ? `?folder=${folderId}` : ""}`),
    ]);

    // Breadcrumb
    const bc = document.getElementById("driveBreadcrumb");
    bc.innerHTML = '<a href="#" data-folder="">Drive</a>';
    if (folderId != null) {
      const chain = [];
      let cur = folderId;
      while (cur) {
        const f = await api.get(`/api/drive/folders/${cur}`);
        chain.unshift(f);
        cur = f.parent_id;
      }
      chain.forEach((f) => {
        const a = document.createElement("a");
        a.href = "#";
        a.dataset.folder = f.id;
        a.textContent = ` › ${f.name}`;
        bc.appendChild(a);
      });
    }

    const container = document.getElementById("driveContent");
    let html = "";

    if (folders.length === 0 && files.length === 0) {
      html = '<p class="empty">Pasta vazia. Envie arquivos pelo bot do Telegram.</p>';
    } else {
      if (folders.length > 0) {
        html += '<div class="drive-section"><h3>📁 Pastas</h3><div class="drive-grid">';
        folders.forEach((f) => {
          html += `<div class="drive-item drive-folder" data-folder="${f.id}">
            <div class="drive-icon">📁</div>
            <div class="drive-name">${f.name}</div>
          </div>`;
        });
        html += "</div></div>";
      }
      if (files.length > 0) {
        html += '<div class="drive-section"><h3>📄 Arquivos</h3><table class="drive-table"><thead><tr><th>Nome</th><th>Tamanho</th><th>Data</th></tr></thead><tbody>';
        files.forEach((f) => {
          const sz = f.file_size < 1024 * 1024
            ? `${(f.file_size / 1024).toFixed(1)} KB`
            : `${(f.file_size / (1024 * 1024)).toFixed(1)} MB`;
          html += `<tr class="drive-file" data-absolute-path="${f.absolute_path || ""}">
            <td class="drive-name">📄 ${f.name}</td>
            <td>${sz}</td>
            <td>${brDate(f.created_at)}</td>
          </tr>`;
        });
        html += "</tbody></table></div>";
      }
    }

    container.innerHTML = html;

    // Folder click handlers
    container.querySelectorAll(".drive-folder").forEach((el) => {
      el.addEventListener("click", () => loadDrive(parseInt(el.dataset.folder)));
    });

    // File click handlers — open folder in file manager
    container.querySelectorAll(".drive-file").forEach((el) => {
      el.addEventListener("click", () => {
        const p = el.dataset.absolutePath;
        if (p) api.showItemInFolder(p);
      });
    });

    // Breadcrumb click handlers
    bc.querySelectorAll("a").forEach((a) => {
      a.addEventListener("click", (e) => {
        e.preventDefault();
        loadDrive(a.dataset.folder ? parseInt(a.dataset.folder) : null);
      });
    });
  } catch (e) {
    console.log("Erro ao carregar Drive", e);
    document.getElementById("driveContent").innerHTML = '<p class="error">Erro ao carregar Drive</p>';
  }
}

// ─── Trilha Rede ──────────────────────────────────────────

async function loadTrilha() {
  try {
    const projetos = await api.get("/api/trilha/projects");
    const container = document.getElementById("trilhaContent");
    if (!projetos.length) {
      container.innerHTML = '<p class="empty">Nenhum projeto ainda. Crie um pelo bot do Telegram.</p>';
      return;
    }
    const icons = { pending: "⏳", running: "🔄", done: "✅", error: "❌" };
    let html = '<table><thead><tr><th>Status</th><th>Nome</th><th>Prompt</th><th>Data</th></tr></thead><tbody>';
    projetos.forEach((p) => {
      const icon = icons[p.status] || "❓";
      html += `<tr class="trilha-project" data-absolute-path="${p.absolute_path || ""}">
        <td>${icon}</td>
        <td>${p.name}</td>
        <td>${(p.prompt_melhorado || "").substring(0, 60)}...</td>
        <td>${brDate(p.created_at)}</td>
      </tr>`;
    });
    html += "</tbody></table>";
    container.innerHTML = html;

    container.querySelectorAll(".trilha-project").forEach((el) => {
      el.addEventListener("click", () => {
        const p = el.dataset.absolutePath;
        if (p) api.showItemInFolder(p);
      });
    });
  } catch (e) {
    console.log("Erro ao carregar Trilha Rede", e);
  }
}

document.querySelector('nav a[data-module="trilha"]')?.addEventListener("click", () => {
  setTimeout(loadTrilha, 50);
});

// ─── End Trilha Rede ──────────────────────────────────────

// ─── Whitelist ──────────────────────────────────────────

let whitelistLoaded = false;

async function loadWhitelistStats() {
  try {
    const list = await api.get("/api/whitelist");
    document.getElementById("whitelistCount").textContent = list.length;
    document.getElementById("whitelistStatus").textContent = list.length > 0 ? "🔒 Ativa" : "🔓 Inativa (todos OK)";
  } catch (_) {}
}

async function toggleWhitelistDetail() {
  const el = document.getElementById("whitelistDetail");
  const toggle = document.getElementById("whitelistToggle");
  if (el.classList.contains("hidden")) {
    el.classList.remove("hidden");
    toggle.textContent = "▼";
    if (!whitelistLoaded) {
      whitelistLoaded = true;
      await renderWhitelistTable();
    }
  } else {
    el.classList.add("hidden");
    toggle.textContent = "▶";
  }
}

async function renderWhitelistTable() {
  try {
    const list = await api.get("/api/whitelist");
    const wrap = document.getElementById("whitelistTableWrap");
    if (!list.length) {
      wrap.innerHTML = '<p class="empty" style="padding:20px">Nenhum usuário liberado. Adicione o primeiro acima.</p>';
      return;
    }
    wrap.innerHTML = `<table><thead><tr><th>ID</th><th>Username</th><th>Apelido</th><th>Desde</th><th></th></tr></thead><tbody>${
      list.map((w) => {
        const name = w.username ? `@${w.username}` : "-";
        return `<tr><td>${w.user_id}</td><td>${name}</td><td>${w.label || "-"}</td><td>${brDate(w.created_at)}</td>
          <td><button class="btn-wl-remove" data-id="${w.id}" style="background:none;border:1px solid var(--danger);color:var(--danger);border-radius:4px;cursor:pointer;padding:2px 8px;font-size:12px">✕</button></td></tr>`;
      }).join("")
    }</tbody></table>`;
    wrap.querySelectorAll(".btn-wl-remove").forEach((btn) => {
      btn.addEventListener("click", async () => {
        await api.post(`/api/whitelist/${btn.dataset.id}`, {});
        await renderWhitelistTable();
        await loadWhitelistStats();
      });
    });
  } catch (_) {}
}

document.getElementById("whitelistSectionTitle")?.addEventListener("click", toggleWhitelistDetail);

document.getElementById("btnWlAdd")?.addEventListener("click", async () => {
  const uid = document.getElementById("wlUserId").value.trim();
  const username = document.getElementById("wlUsername").value.trim().replace("@", "");
  const label = document.getElementById("wlLabel").value.trim();
  if (!uid) return;
  await api.post("/api/whitelist", { user_id: parseInt(uid), username, label });
  document.getElementById("wlUserId").value = "";
  document.getElementById("wlUsername").value = "";
  document.getElementById("wlLabel").value = "";
  await renderWhitelistTable();
  await loadWhitelistStats();
});

// ─── Modal ─────────────────────────────────────────────

async function openModal(current) {
  const sel = document.getElementById("modalSelect");
  sel.innerHTML = '<option value="">Carregando...</option>';
  document.getElementById("modalOverlay").classList.remove("hidden");
  const models = await api.get("/api/models").then(r => r.models || []).catch(() => []);
  sel.innerHTML = models.map(m =>
    `<option value="${m}"${m === current ? " selected" : ""}>${m}</option>`
  ).join("");
  if (models.length === 0) {
    sel.innerHTML = `<option value="${current}">${current} (sem lista)</option>`;
  }
  setTimeout(() => sel.focus(), 100);
}

function closeModal() {
  document.getElementById("modalOverlay").classList.add("hidden");
}

document.getElementById("modalConfirm")?.addEventListener("click", async () => {
  const name = document.getElementById("modalSelect").value;
  closeModal();
  if (!name) return;
  const res = await api.post("/api/model", { model: name }).catch(() => ({}));
  if (res && res.status === "ok") {
    loadStatus();
    loadDashboard();
  }
});

document.getElementById("modalSelect")?.addEventListener("keydown", (e) => {
  if (e.key === "Enter") document.getElementById("modalConfirm").click();
});

// ─── Model change ──────────────────────────────────────

async function changeModel() {
  try {
    const r = await api.get("/api/model");
    openModal(r && r.model ? r.model : "gemma4");
  } catch (e) {
    openModal("gemma4");
  }
}

// ─── End Model ──────────────────────────────────────────

// Clickable dashboard cards navigation
document.querySelectorAll(".card.clickable").forEach((el) => {
  el.addEventListener("click", () => {
    const mod = el.dataset.go;
    const link = document.querySelector(`nav a[data-module="${mod}"]`);
    if (link) link.click();
  });
});

// Toggle log detail
document.getElementById("logSectionTitle")?.addEventListener("click", toggleLogDetail);

// ─── Users ─────────────────────────────────────────────

let usersCache = [];

async function loadUsersCount() {
  try {
    usersCache = await api.get("/api/logs/users");
    document.getElementById("usersTotal").textContent = usersCache.length;
  } catch (_) {}
}

document.getElementById("usersToggleBtn")?.addEventListener("click", async () => {
  const el = document.getElementById("usersDetail");
  const btn = document.getElementById("usersToggleBtn");
  if (el.classList.contains("hidden")) {
    el.classList.remove("hidden");
    btn.textContent = "📁 Recolher";
    usersCache = await api.get("/api/logs/users").catch(() => usersCache);
    document.getElementById("usersTotal").textContent = usersCache.length;
    await renderUsersTable();
  } else {
    el.classList.add("hidden");
    btn.textContent = "📋 Ver todos";
  }
});

async function renderUsersTable() {
  const container = document.getElementById("usersContent");
  if (!usersCache.length) {
    container.innerHTML = '<p class="empty">Nenhum usuário encontrado</p>';
    return;
  }
  container.innerHTML = `<table><thead><tr><th>Nome</th><th>Username</th><th>ID</th><th>Último acesso</th><th>Tipo</th><th>Resumo</th></tr></thead><tbody>${
    usersCache.map((u) => {
      const nome = u.first_name || u.username || "desconhecido";
      const uname = u.username ? `@${u.username}` : "-";
      return `<tr class="user-row" data-chat="${u.chat_id || u.user_id}" data-name="${nome}">
        <td>${nome}</td>
        <td>${uname}</td>
        <td>${u.user_id}</td>
        <td>${brDate(u.last_seen)}</td>
        <td>${u.last_type || "-"}</td>
        <td style="max-width:200px;overflow:hidden;text-overflow:ellipsis;white-space:nowrap">${u.last_summary || "-"}</td>
      </tr>`;
    }).join("")
  }</tbody></table>`;
  container.querySelectorAll(".user-row").forEach((row) => {
    row.addEventListener("click", () => {
      openSend(row.dataset.chat, row.dataset.name);
    });
  });
}

let sendChatId = null;

function openSend(chatId, name) {
  sendChatId = chatId;
  document.getElementById("sendLabel").textContent = `Enviar para ${name}`;
  document.getElementById("sendText").value = "";
  document.getElementById("sendStatus").classList.add("hidden");
  document.getElementById("sendOverlay").classList.remove("hidden");
  setTimeout(() => document.getElementById("sendText").focus(), 100);
}

function closeSend() {
  document.getElementById("sendOverlay").classList.add("hidden");
}

document.getElementById("sendConfirm")?.addEventListener("click", async () => {
  const text = document.getElementById("sendText").value.trim();
  if (!text || !sendChatId) return;
  const status = document.getElementById("sendStatus");
  status.className = "send-status sending";
  status.textContent = "⏳ Enviando...";
  status.classList.remove("hidden");
  try {
    const res = await api.post("/api/bot/send", { chat_id: parseInt(sendChatId), text });
    if (res.status === "sent") {
      status.className = "send-status sent";
      status.textContent = "✅ Mensagem enviada!";
      document.getElementById("sendText").value = "";
    } else {
      status.className = "send-status error";
      status.textContent = "❌ Erro: " + (res.error || "desconhecido");
    }
  } catch (e) {
    status.className = "send-status error";
    status.textContent = "❌ Erro ao enviar";
  }
});

document.getElementById("sendText")?.addEventListener("keydown", (e) => {
  if (e.key === "Enter" && e.ctrlKey) document.getElementById("sendConfirm").click();
});

// ─── End Users ──────────────────────────────────────────

// Blocked dialog
document.getElementById("logBlocked")?.addEventListener("click", openBlocked);
document.getElementById("logSize")?.addEventListener("click", async () => {
  const date = logDateFilter || todayStr();
  const p = await api.get(`/api/logs/path?date=${date}`).then(r => r.path).catch(() => null);
  if (p) api.showItemInFolder(p);
});

async function openBlocked() {
  const overlay = document.getElementById("blockedOverlay");
  const content = document.getElementById("blockedContent");
  content.innerHTML = '<p class="loading">Carregando...</p>';
  overlay.classList.remove("hidden");
  try {
    const entries = await api.get("/api/logs/blocked");
    if (!entries.length) {
      content.innerHTML = '<p class="empty" style="padding:20px">Nenhum bloqueio hoje</p>';
      return;
    }
    content.innerHTML = `<table><thead><tr><th>Horário</th><th>Usuário</th><th>ID</th></tr></thead><tbody>${
      entries.map((e) => {
        const nome = e.first_name || e.username || "-";
        const uname = e.username ? ` @${e.username}` : "";
        return `<tr><td>${(e.ts || "").split(" ")[1] || e.ts}</td><td>${nome}${uname}</td><td>${e.user_id || "-"}</td></tr>`;
      }).join("")
    }</tbody></table>`;
  } catch (_) {
    content.innerHTML = '<p class="error">Erro ao carregar</p>';
  }
}

function closeBlocked() {
  document.getElementById("blockedOverlay").classList.add("hidden");
}

// ─── Security ─────────────────────────────────────────

const SEC_CHECKS = ["connections", "ssh", "integrity", "persistence", "processes", "ports"];
const SEC_LABELS = {
  connections: "🌐 Conexões de Saída", ssh: "🔑 Logins SSH", integrity: "📁 Integridade",
  persistence: "⏱ Persistência", processes: "⚙ Processos", ports: "🚪 Portas",
};

document.querySelector('nav a[data-module="security"]')?.addEventListener("click", () => {
  setTimeout(loadSecurity, 50);
});

async function runSecurityCheck(name) {
  try {
    const res = await api.get(`/api/security/${name}`);
    console.log(`[sec] ${name}:`, res?.status, res?.alerts?.length || 0, "alertas");
    return res;
  } catch (e) {
    console.error(`[sec] ERRO ${name}:`, e);
    return null;
  }
}

let secResultsCache = {};
async function runAllSecurity() {
  setSecStatus("⏳ Rodando...", "sending");
  try {
    const all = await api.get("/api/security/run");
    if (all && all.error) throw new Error(all.error);
    for (const name of SEC_CHECKS) secResultsCache[name] = all[name] || null;
  } catch (e) {
    console.error("[sec] ERRO:", e);
    for (const name of SEC_CHECKS) secResultsCache[name] = null;
  }
  renderSecurityResults(secResultsCache);
  setSecStatus("✅ Completo", "sent");
}

function renderSecurityResults(results) {
  const grid = document.getElementById("secResults");
  const meta = results[SEC_CHECKS[0]]?.meta || {};
  let html = '<div class="sec-overview">';
  let totalAlerts = 0, totalAtten = 0;
  for (const name of SEC_CHECKS) {
    const r = results[name];
    if (r?.alerts?.length) totalAlerts += r.alerts.length;
    if (r?.attentions?.length) totalAtten += r.attentions.length;
  }
  let riskText = "🟢 Nenhum risco";
  if (totalAlerts > 0) riskText = `🔴 ${totalAlerts} risco(s) detectado(s)`;
  else if (totalAtten > 0) riskText = `🟡 ${totalAtten} atenção(ões)`;
  html += `<div class="sec-risk-badge ${totalAlerts > 0 ? "risk-high" : totalAtten > 0 ? "risk-atten" : "risk-low"}">${riskText}</div>`;
  html += '</div>';

  // Chart
  html += '<div class="sec-chart-wrap"><div class="sec-chart" id="secChart">';
  for (const name of SEC_CHECKS) {
    const r = results[name];
    const count = r?.alerts?.length || 0;
    const max = Math.max(...SEC_CHECKS.map(n => results[n]?.alerts?.length || 0), 1);
    html += `<div class="sec-chart-item"><div class="sec-chart-label">${SEC_LABELS[name].split(" ")[0]}</div><div class="sec-chart-bar"><div class="sec-chart-fill ${count > 0 ? "chart-alert" : "chart-ok"}" style="width:${(count / max) * 100}%"></div></div><div class="sec-chart-val">${count}</div></div>`;
  }
  html += '</div></div>';

  function cardStatus(r) {
    if (!r) return { cls: "sec-ok", icon: "⚠️", label: "🟢 OK", badge: "erro", badgeCls: "badge-ok" };
    const s = r.status;
    if (s === "alerta") return { cls: "sec-alert", icon: "🔴", label: "🔴 Risco", badge: (r.alerts?.length || 0), badgeCls: "badge-alert" };
    if (s === "atencao") return { cls: "sec-alert", icon: "🟡", label: "🟡 Atenção", badge: (r.attentions?.length || 0), badgeCls: "badge-alert" };
    return { cls: "sec-ok", icon: "🟢", label: "🟢 OK", badge: "OK", badgeCls: "badge-ok" };
  }
  for (const name of SEC_CHECKS) {
    const r = results[name];
    const label = SEC_LABELS[name];
    const st = cardStatus(r);
    const alerts = r?.alerts || [];
    const m = r?.meta || {};
    html += `<div class="sec-card ${st.cls}" data-check="${name}">
      <div class="sec-card-header">
        <span class="sec-icon">${st.icon}</span>
        <span class="sec-title">${label}</span>
        <span class="sec-risk-label">${st.label}</span>
        <span class="sec-badge ${st.badgeCls}">${st.badge}</span>
        <button class="sec-run-btn" data-check="${name}">▶</button>
      </div>
      <div class="sec-card-meta"><span class="sec-cmd">$ ${m.cmd || ""}</span></div>
      <div class="sec-card-desc">${m.desc || ""}</div>
      ${r?.status === "alerta" ? `<div class="sec-card-risk">⚠️ Risco: ${m.risk || ""}</div>` : ""}
      <div class="sec-card-body">${renderCheckBody(name, r)}</div>
    </div>`;
  }
  grid.innerHTML = html;

  grid.querySelectorAll(".sec-run-btn").forEach((btn) => {
    btn.addEventListener("click", async () => {
      const name = btn.dataset.check;
      setSecStatus(`⏳ Rodando ${SEC_LABELS[name]}...`, "sending");
      try {
        const all = await api.get("/api/security/run");
        if (all && all.error) throw new Error(all.error);
        for (const n of SEC_CHECKS) secResultsCache[n] = all[n] || null;
      } catch (e) {
        console.error("[sec] ERRO individual:", e);
        secResultsCache[name] = null;
      }
      renderSecurityResults(secResultsCache);
      setSecStatus("✅ OK", "sent");
    });
  });
}

function renderCheckBody(name, r) {
  if (!r) return '<span class="sec-empty">Aguardando...</span>';
  if (r.status === "erro") return `<span class="sec-empty">Erro ao executar</span>`;
  let html = "";
  if (name === "connections") {
    if (r.alerts?.length) html += r.alerts.map(a => `<div class="sec-line sec-line-alert">⚠️ ${a}</div>`).join("");
    html += `<div class="sec-line">Total: ${r.count} conexões</div>`;
    if (r.entries?.length) html += r.entries.slice(0, 5).map(e => `<div class="sec-line">${e.remote} | ${e.proc}</div>`).join("");
  } else if (name === "ssh") {
    html += `<div class="sec-line">Falhas: ${r.fail_count} | Sucessos: ${r.success_count}</div>`;
    if (r.fails?.length) html += r.fails.slice(0, 3).map(f => `<div class="sec-line sec-line-alert">❌ ${f}</div>`).join("");
  } else if (name === "integrity") {
    r.results?.forEach(res => {
      const ic = res.status === "modificado" ? "🔴" : "🟢";
      html += `<div class="sec-line ${res.status === "modificado" ? "sec-line-alert" : ""}">${ic} ${res.file}: ${res.detail}</div>`;
    });
  } else if (name === "persistence") {
    if (r.alerts?.length) html += r.alerts.map(a => `<div class="sec-line sec-line-alert">⚠️ ${a}</div>`).join("");
    html += `<div class="sec-line">Cron: ${r.cron_entries?.length || 0} tasks | Timers: ${r.timers?.length || 0}</div>`;
  } else if (name === "processes") {
    r.entries?.forEach(p => {
      const sus = r.alerts?.some(a => a.includes(p.name));
      html += `<div class="sec-line ${sus ? "sec-line-alert" : ""}">${p.name}: ${p.cpu}% CPU</div>`;
    });
    } else if (name === "ports") {
      if (r.alerts?.length) html += r.alerts.map(a => `<div class="sec-line sec-line-alert">🔴 ${a}</div>`).join("");
      if (r.attentions?.length) html += r.attentions.map(a => `<div class="sec-line" style="color:var(--neon3)">🟡 ${a}</div>`).join("");
      if (r.own_ports?.length) html += r.own_ports.map(a => `<div class="sec-line" style="color:var(--neon)">🟢 ${a}</div>`).join("");
      html += `<div class="sec-line">Total: ${r.count} portas abertas</div>`;
    }
  return html || '<span class="sec-empty">Nada encontrado</span>';
}

function setSecStatus(text, cls) {
  const el = document.getElementById("secStatus");
  el.textContent = text;
  el.className = "sec-status " + (cls || "");
}

document.getElementById("secRunAll")?.addEventListener("click", runAllSecurity);

async function loadSecurity() {
  setSecStatus("⏳ Carregando...", "sending");
  try {
    const all = await api.get("/api/security/run");
    console.log("[sec] run_all:", all);
    if (all && all.error) throw new Error(all.error);
    for (const name of SEC_CHECKS) secResultsCache[name] = all[name] || null;
  } catch (e) {
    console.error("[sec] ERRO run_all:", e);
    for (const name of SEC_CHECKS) secResultsCache[name] = null;
  }
  renderSecurityResults(secResultsCache);
  setSecStatus("✅ Atualizado", "sent");
}

document.getElementById("secReport")?.addEventListener("click", async () => {
  setSecStatus("⏳ Gerando relatório com IA...", "sending");
  try {
    const res = await api.get("/api/security/report");
    if (res && res.error) throw new Error(res.error);
    if (res && res.report) {
      for (const name of SEC_CHECKS) secResultsCache[name] = res.report[name] || null;
      renderSecurityResults(secResultsCache);
    }
    const alerts = Object.values(secResultsCache).flatMap(r => r?.alerts || []);
    const summary = alerts.length ? `🔴 ${alerts.length} alertas` : "🟢 OK";
    setSecStatus(summary, alerts.length ? "error" : "sent");
    openReportDialog(res?.ia_analysis || "Sem análise", secResultsCache);
  } catch (e) {
    console.error("[sec] ERRO relatório:", e);
    setSecStatus("❌ Erro ao gerar relatório", "error");
  }
});

function openReportDialog(iaText, results) {
  const overlay = document.getElementById("reportOverlay");
  if (!overlay) return;
  document.getElementById("reportIaText").textContent = iaText;
  let html = "";
  for (const name of SEC_CHECKS) {
    const r = results[name];
    const label = SEC_LABELS[name] || name;
    const isAlert = r?.status === "alerta";
    const icon = isAlert ? "🔴" : "🟢";
    const alerts = r?.alerts || [];
    html += `<div class="sec-report-line ${isAlert ? "sec-line-alert" : ""}">${icon} <strong>${label}</strong> — ${isAlert ? alerts.length + " alerta(s)" : "OK"}</div>`;
    if (isAlert) alerts.slice(0, 3).forEach(a => html += `<div class="sec-report-detail">⚠️ ${a}</div>`);
  }
  document.getElementById("reportResults").innerHTML = html;
  overlay.classList.remove("hidden");
}

function closeReport() {
  const el = document.getElementById("reportOverlay");
  if (el) el.classList.add("hidden");
}

// ─── Reports list ─────────────────────────────────────

let reportsExpanded = false;

document.getElementById("secReportsBtn")?.addEventListener("click", async () => {
  const el = document.getElementById("secReportsList");
  if (reportsExpanded) {
    el.classList.add("hidden");
    reportsExpanded = false;
    return;
  }
  el.classList.remove("hidden");
  reportsExpanded = true;
  const content = document.getElementById("secReportsContent");
  content.innerHTML = '<p class="loading">Carregando...</p>';
  try {
    const list = await api.get("/api/reports");
    if (!list.length) {
      content.innerHTML = '<p class="empty" style="padding:20px">Nenhum relatório salvo ainda.</p>';
      return;
    }
        content.innerHTML = `<table><thead><tr><th>Data</th><th>Riscos</th><th>Alertas</th><th>Origem</th><th>Análise IA</th><th></th></tr></thead><tbody>${
      list.map((r) => {
        const riskIcon = r.has_risk ? "🔴" : "🟢";
        const riskText = r.has_risk ? "Sim" : "Não";
        const src = r.source === "bot" ? "🤖 Bot" : r.source === "desktop" ? "🖥 Desktop" : r.source || "-";
        return `<tr data-id="${r.id}">
          <td>${brDate(r.date)}</td>
          <td>${riskIcon} ${riskText}</td>
          <td>${r.alert_count}</td>
          <td>${src}</td>
          <td>${(r.ia_preview || "").substring(0, 50)}...</td>
          <td>
            <button class="btn-start" style="font-size:10px;padding:2px 8px" data-action="view" data-id="${r.id}">📂</button>
            <button class="btn-start btn-stop" style="font-size:10px;padding:2px 8px;margin-left:4px" data-action="del" data-id="${r.id}">✕</button>
          </td>
        </tr>`;
      }).join("")
    }</tbody></table>`;
    content.querySelectorAll("[data-action=view]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.dataset.id;
        const rep = await api.get(`/api/reports/${id}`);
        if (rep) showSavedReport(rep);
      });
    });
    content.querySelectorAll("[data-action=del]").forEach((btn) => {
      btn.addEventListener("click", async () => {
        const id = btn.dataset.id;
        await api.post(`/api/reports/${id}`, {});
        reportsExpanded = false;
        document.getElementById("secReportsBtn").click();
      });
    });
  } catch (_) {
    content.innerHTML = '<p class="error">Erro ao carregar</p>';
  }
});

async function showSavedReport(rep) {
  document.getElementById("reportIaText").textContent = rep.ia_analysis || "Sem análise";
  const lines = (rep.summary || "").split("\n").filter(l => l.trim());
  document.getElementById("reportResults").innerHTML = lines.length
    ? lines.map(l => `<div class="sec-report-line">${l}</div>`).join("")
    : '<div class="sec-report-line">Nenhum alerta</div>';
  document.getElementById("reportOverlay").classList.remove("hidden");
}

// ─── End Security ──────────────────────────────────────

// Navigate to Drive module - load its content on first visit
document.querySelector('nav a[data-module="drive"]')?.addEventListener("click", () => {
  setTimeout(() => loadDrive(currentDriveFolder), 50);
});

// ─── Sysmon ───────────────────────────────────────────

function sysmonColor(val, max, isTemp) {
  const pct = isTemp ? (val / max) : (val / max);
  if (pct < 0.5) return "#00fff7";
  if (pct < 0.75) return "#ff6b00";
  return "#ff0044";
}

async function loadSysmon() {
  try {
    const s = await api.get("/api/sysmon");
    const cpu = Math.min(s.cpu ?? 0, 100);
    const temp = s.cpu_temp ?? 0;
    const ram = s.ram || {};
    const ramPct = ram.pct ?? 0;
    const gpu = s.gpu || {};
    const gpuPct = gpu.vram_pct ?? 0;
    const gpuTemp = gpu.temp ?? 0;
    const hasGpu = gpu.present;

    document.getElementById("smCpu").textContent = `${cpu}%`;
    document.getElementById("smCpu").style.color = sysmonColor(cpu, 100, false);

    document.getElementById("smTemp").textContent = `${temp}°C`;
    document.getElementById("smTemp").style.color = sysmonColor(temp, 80, true);

    document.getElementById("smRam").textContent = `${ramPct}%`;
    document.getElementById("smRam").style.color = sysmonColor(ramPct, 100, false);

    const gpuRow = document.getElementById("smGpuRow");
    const vramRow = document.getElementById("smVramRow");
    const vramUsed = gpu.vram_used ?? 0;
    const vramTotal = gpu.vram_total ?? 0;
    if (hasGpu) {
      gpuRow.style.display = "flex";
      vramRow.style.display = "flex";
      document.getElementById("smGpuPct").textContent = `${gpuPct}%`;
      document.getElementById("smGpuPct").style.color = sysmonColor(gpuPct, 100, false);
      document.getElementById("smGpuTemp").textContent = `${gpuTemp}°C`;
      document.getElementById("smGpuTemp").style.color = sysmonColor(gpuTemp, 80, true);
      document.getElementById("smVram").textContent = `${vramUsed}/${vramTotal}MB`;
      document.getElementById("smVram").style.color = sysmonColor(gpuPct, 100, false);
    } else {
      gpuRow.style.display = "none";
      vramRow.style.display = "none";
    }
  } catch (_) {}
}

document.getElementById("sysmonCard")?.addEventListener("click", loadSysmon);
setInterval(loadSysmon, 600000);
setInterval(loadStatus, 5000);
setInterval(loadDashboard, 10000);
setInterval(loadFinancas, 10000);

document.addEventListener("DOMContentLoaded", () => {
  loadDashboard();
  loadFinancas();
  loadSysmon();
  document.getElementById("statusCache").textContent = "A cada 30 min (automático)";
});
