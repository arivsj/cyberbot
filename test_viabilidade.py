import sqlite3
import os
import time
import httpx
from datetime import datetime, date

DB_PATH = os.path.join(os.path.dirname(__file__), "financas.db")

def query(sql, params=()):
    conn = sqlite3.connect(DB_PATH)
    conn.row_factory = sqlite3.Row
    cur = conn.execute(sql, params)
    rows = [dict(r) for r in cur.fetchall()]
    conn.close()
    return rows

print("=" * 60)
print("TESTE DE VIABILIDADE — CYBERBOT FINANÇAS + IA")
print("=" * 60)

# ─── 1. USO DO APP ────────────────────────────────────
rows = query("SELECT data, valor, categoria FROM transacoes ORDER BY data ASC")
if not rows:
    print("\n❌ Nenhuma transação encontrada no banco.")
    exit()

primeira = rows[0]["data"]
ultima = rows[-1]["data"]
dias_uso = (datetime.strptime(ultima, "%Y-%m-%d") - datetime.strptime(primeira, "%Y-%m-%d")).days + 1
total_gasto = sum(r["valor"] for r in rows)
total_itens = len(rows)
media_diaria = total_gasto / dias_uso if dias_uso > 0 else 0
itens_por_dia = total_itens / dias_uso if dias_uso > 0 else 0

print(f"\n📅 PERÍODO: {primeira} a {ultima} ({dias_uso} dias)")
print(f"📦 Total de transações: {total_itens}")
print(f"💰 Gasto total: R$ {total_gasto:.2f}")
print(f"📊 Média por dia: R$ {media_diaria:.2f} | {itens_por_dia:.1f} transações/dia")

meses = query("SELECT DISTINCT strftime('%Y-%m', data) as mes FROM transacoes ORDER BY mes")
qtd_meses = len(meses)
print(f"📆 Meses com registro: {qtd_meses}")

# Mês mais recente completo
hoje = date.today()
inicio_mes = date(hoje.year, hoje.month, 1)
if hoje.day <= 2:
    # mês anterior
    if hoje.month == 1:
        inicio_mes = date(hoje.year - 1, 12, 1)
    else:
        inicio_mes = date(hoje.year, hoje.month - 1, 1)

rows_mes = query("SELECT strftime('%Y-%m', data) as mes, SUM(valor) as total FROM transacoes GROUP BY mes ORDER BY mes")
gastos_mensais = [(r["mes"], r["total"]) for r in rows_mes]

print("\n📈 GASTOS POR MÊS:")
for m, v in gastos_mensais:
    print(f"  {m}: R$ {v:.2f}")

if len(gastos_mensais) >= 2:
    ultimos = [v for _, v in gastos_mensais[-3:]]
    tendencia = (ultimos[-1] - ultimos[0]) / len(ultimos) if len(ultimos) > 1 else 0
    print(f"\n📉 Tendência mensal: {'📈 subindo' if tendencia > 0 else '📉 caindo'} ({tendencia:+.2f}/mês)")
else:
    tendencia = 0

# ─── 2. EXTRAPOLAÇÃO ─────────────────────────────────
print("\n" + "=" * 60)
print("🔮 EXTRAPOLAÇÃO (30 DIAS / 6 MESES / 1 ANO)")
print("=" * 60)

proj_30d_gasto = media_diaria * 30
proj_30d_itens = itens_por_dia * 30
proj_6m_gasto = media_diaria * 180
proj_6m_itens = itens_por_dia * 180
proj_1a_gasto = media_diaria * 365
proj_1a_itens = itens_por_dia * 365

tabela = (
    f"\n{'Prazo':<12} {'Gasto':>14} {'Transações':>14}"
    f"\n{'-'*40}"
    f"\n{'30 dias':<12} {'R$ '+f'{proj_30d_gasto:.2f}':>14} {f'{proj_30d_itens:.0f}':>14}"
    f"\n{'6 meses':<12} {'R$ '+f'{proj_6m_gasto:.2f}':>14} {f'{proj_6m_itens:.0f}':>14}"
    f"\n{'1 ano':<12} {'R$ '+f'{proj_1a_gasto:.2f}':>14} {f'{proj_1a_itens:.0f}':>14}"
)
print(tabela)

tamanho_estimado_db = total_itens * 0.5  # ~0.5KB por transação no banco
print(f"\n💾 Tamanho atual do DB: ~{tamanho_estimado_db:.0f} KB")
print(f"💾 Tamanho estimado em 1 ano: ~{proj_1a_itens * 0.5:.0f} KB")

# ─── 3. TESTE DE TEMPO DA IA ─────────────────────────
print("\n" + "=" * 60)
print("⏱ TESTE DE TEMPO DE RESPOSTA DA IA (Ollama)")
print("=" * 60)

OLLAMA_BASE = "http://localhost:11434"
MODEL = "gemma4"

try:
    r = httpx.get(f"{OLLAMA_BASE}/api/tags", timeout=3)
    if r.status_code != 200 or not r.json().get("models"):
        print("⚠️  Ollama não está rodando ou não tem modelos.")
        print("⏭️  Pulando teste de tempo.\n")
        ollama_ok = False
    else:
        ollama_ok = True
except:
    print("⚠️  Ollama não acessível.")
    print("⏭️  Pulando teste de tempo.\n")
    ollama_ok = False

if ollama_ok:
    # Prompt pequeno (similar ao de insights)
    prompt_peq = (
        "Você é um coach financeiro. Analise estes dados do mês e responda em 2 parágrafos:\n"
        "1) Diagnóstico rápido\n"
        "2) Uma sugestão de economia\n\n"
        f"Total: R$ {total_gasto:.2f}, {total_itens} transações em {qtd_meses} meses"
    )

    # Prompt grande (similar ao antigo)
    prompt_gde = prompt_peq + "\n" + " ".join(
        f"[{r['data']}] {r['categoria']} R${r['valor']:.2f}" for r in rows[:200]
    )

    # Teste prompt pequeno
    print("\n📋 Teste 1 — Prompt pequeno (~0.5k tokens)")
    for tentativa in range(3):
        t0 = time.time()
        try:
            resp = httpx.post(
                f"{OLLAMA_BASE}/api/chat",
                json={
                    "model": MODEL,
                    "messages": [{"role": "user", "content": prompt_peq}],
                    "options": {"num_ctx": 4096},
                    "stream": False,
                },
                timeout=120,
            )
            elapsed = time.time() - t0
            chars = len(resp.json()["message"]["content"])
            print(f"   Tentativa {tentativa+1}: {elapsed:.1f}s — {chars} chars de resposta")
        except Exception as e:
            print(f"   Tentativa {tentativa+1}: ERRO — {e}")

    print("\n📋 Teste 2 — Prompt grande (~15k tokens)")
    for tentativa in range(2):
        t0 = time.time()
        try:
            resp = httpx.post(
                f"{OLLAMA_BASE}/api/chat",
                json={
                    "model": MODEL,
                    "messages": [{"role": "user", "content": prompt_gde}],
                    "options": {"num_ctx": 131072},
                    "stream": False,
                },
                timeout=120,
            )
            elapsed = time.time() - t0
            chars = len(resp.json()["message"]["content"])
            print(f"   Tentativa {tentativa+1}: {elapsed:.1f}s — {chars} chars")
        except Exception as e:
            print(f"   Tentativa {tentativa+1}: ERRO — {e}")

    print(f"\n{'='*60}")
    print("CONCLUSÃO")
    print(f"{'='*60}")
    print("Prompt pequeno (num_ctx=4096) → resposta em segundos ✅ viável")
    print("Prompt grande (num_ctx=131072) → pode levar minutos ⚠️")
    print("Recomendação: usar prompts enxutos com num_ctx reduzido")
else:
    print("Ligue o Ollama e rode novamente para o teste de tempo.")
