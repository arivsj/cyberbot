import os
import sys
import re
import time
import uuid
import asyncio
import shutil
import difflib
from datetime import datetime
from collections import Counter
from dotenv import load_dotenv

load_dotenv()

import base64
import tempfile
import subprocess
import httpx
from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup, CopyTextButton
from telegram.ext import Application, CommandHandler, MessageHandler, CallbackQueryHandler, filters, ContextTypes

import db
import youtube
import trilha_rede
import log
import security
import cleanup
import ollama_utils
db.init()

def _log_update(update: Update, type_: str, summary: str = ""):
    entry = {"type": type_, "summary": summary[:200]}
    user = None
    chat = None
    if update.message and update.message.from_user:
        user = update.message.from_user
        chat = update.message.chat
    elif update.callback_query and update.callback_query.from_user:
        user = update.callback_query.from_user
        chat = update.callback_query.message.chat if update.callback_query.message else None
    if user:
        entry["user_id"] = user.id
        entry["username"] = user.username
        entry["first_name"] = user.first_name
        entry["last_name"] = user.last_name
        entry["language_code"] = user.language_code
    if chat:
        entry["chat_id"] = chat.id
        entry["chat_type"] = chat.type
    log.log_access(entry)

def _check_whitelist(update):
    user = update.effective_user
    if not user:
        return False
    allowed = db.whitelist_check(user.id, user.username)
    if not allowed:
        _log_update(update, "blocked", f"user_id={user.id}")
    return allowed

TOKEN = os.environ.get("TELEGRAM_TOKEN") or ""
OLLAMA_BASE = "http://localhost:11434"
MODEL = db.get_setting("model", "gemma4")

async def start(update: Update, context: ContextTypes.DEFAULT_TYPE):
    _log_update(update, "command", "/start")
    await update.message.reply_text(
        "Olá! Estou conectado ao Ollama.\n"
        "Envie texto, imagens ou áudios para conversarmos.\n"
        "Comandos:\n/menu - menu de funcionalidades\n/clear - limpa o histórico\n/model <nome> - troca o modelo"
    )

MAIN_MENU_TEXT = "📋 *Menu de funcionalidades*"
MAIN_MENU_BUTTONS = [
    [InlineKeyboardButton("📁 Drive", callback_data="d_main")],
    [InlineKeyboardButton("🎬 YouTube", callback_data="menu_youtube")],
    [InlineKeyboardButton("💰 Finanças", callback_data="menu_financas")],
    [InlineKeyboardButton("🧠 Trilha Rede", callback_data="tr_main")],
    [InlineKeyboardButton("📱 PC Apps", callback_data="pc_main")],
    [InlineKeyboardButton("🛡️ Security", callback_data="sec_main")],
    [InlineKeyboardButton("🧹 Limpeza", callback_data="cl_main")],
    [InlineKeyboardButton("🔄 Trocar modelo", callback_data="menu_model"),
     InlineKeyboardButton("🧹 Limpar histórico", callback_data="menu_clear")],
]
MAIN_MENU_MARKUP = InlineKeyboardMarkup(MAIN_MENU_BUTTONS)

async def menu(update: Update, context: ContextTypes.DEFAULT_TYPE):
    _log_update(update, "command", "/menu")
    await update.message.reply_text(
        MAIN_MENU_TEXT,
        reply_markup=MAIN_MENU_MARKUP,
        parse_mode="Markdown",
    )

def format_resumo(mes=None):
    rotulo = f" ({mes})" if mes else " (total)"
    r = db.resumo_mes(mes=mes)
    cats = db.gastos_por_categoria(mes=mes)
    contas = db.gastos_por_conta(mes=mes)
    recentes = db.listar(5, mes=mes)

    top_cat = f"🏆 Maior categoria: *{cats[0]['categoria']}* (R$ {cats[0]['total']:.2f})" if cats else ""
    lines = [
        f"📊 *Resumo de Finanças{rotulo}*\n",
        f"📦 Total de despesas: *{r['total_transacoes']}*",
        f"💰 Gasto no mês: *R$ {r['gasto_mes']:.2f}*",
        f"💵 Gasto total: *R$ {r['total_gasto']:.2f}*\n",
        top_cat,
        "━━━ *Por Categoria* ━━━",
    ]
    for c in cats:
        lines.append(f"▸ {c['categoria']}: R$ {c['total']:.2f} ({c['qtde']}x)")
    lines.append("")
    lines.append("━━━ *Por Conta* ━━━")
    for c in contas:
        lines.append(f"▸ {c['conta']}: R$ {c['total']:.2f} ({c['qtde']}x)")
    lines.append("")
    lines.append("━━━ *Últimas* ━━━")
    for t in recentes:
        lines.append(f"▸ {t['data']} | {t['categoria']} | R$ {t['valor']:.2f}")

    return "\n".join(lines)

async def show_resumo(query):
    texto = format_resumo()
    buttons = [[InlineKeyboardButton("🔙 Voltar", callback_data="menu_back_financa")]]
    await query.edit_message_text(
        texto,
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )

async def resumo(update: Update, context: ContextTypes.DEFAULT_TYPE):
    mes = context.args[0] if context.args else None
    if mes and not (len(mes) == 7 and mes[4] == "-"):
        await update.message.reply_text("Formato inválido. Use: `/resumo 2026-06`", parse_mode="Markdown")
        return
    texto = format_resumo(mes=mes)
    buttons = [[InlineKeyboardButton("💰 Menu Finanças", callback_data="menu_back_financa")]]
    await update.message.reply_text(
        texto,
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )

async def financa_insights(query, context):
    model = context.user_data.get("model", MODEL)
    hoje = datetime.now().strftime("%Y-%m")
    meses = db.meses_disponiveis()
    mes_atual = hoje if hoje in meses else (meses[0] if meses else None)

    await query.edit_message_text("🤖 *Analisando suas finanças...*", parse_mode="Markdown")

    def _grupo(items, chave_nome="categoria"):
        grupos = []
        usados = set()
        for i, item in enumerate(items):
            if i in usados:
                continue
            nome = item[chave_nome]
            similares = [j for j in range(i, len(items)) if j not in usados and difflib.SequenceMatcher(None, nome, items[j][chave_nome]).ratio() >= 0.8]
            soma_total = sum(items[j]["total"] for j in similares)
            soma_qtde = sum(items[j]["qtde"] for j in similares)
            usados.update(similares)
            grupos.append({chave_nome: nome, "total": soma_total, "qtde": soma_qtde})
        return sorted(grupos, key=lambda x: -x["total"])[:5]

    r = db.resumo_mes(mes=mes_atual)
    cats_raw = db.gastos_por_categoria(mes=mes_atual)
    contas_raw = db.gastos_por_conta(mes=mes_atual)
    cats = _grupo(cats_raw, "categoria") if cats_raw else []
    contas = _grupo(contas_raw, "conta") if contas_raw else []

    dados = (
        f"MÊS: {mes_atual}\n"
        f"GASTO TOTAL: R$ {r['total_gasto']:.2f}\n"
        f"QTD TRANSAÇÕES: {r['total_transacoes']}\n"
    )

    if cats:
        dados += "\nCATEGORIAS:\n" + "\n".join(
            f"- {c['categoria']}: R$ {c['total']:.2f} ({c['qtde']}x)"
            for c in cats
        )

    if contas:
        dados += "\n\nCONTAS:\n" + "\n".join(
            f"- {c['conta']}: R$ {c['total']:.2f} ({c['qtde']}x)"
            for c in contas
        )

    # ─── Consolidar descrições (com tolerância a erro de digitação) ──
    transacoes = db.listar(limite=500, mes=mes_atual)
    palavras_ignorar = {"de", "da", "do", "na", "no", "em", "para", "com", "e", "a", "o", "que", "é", "um", "uma", "pra"}
    itens_por_categoria = {}
    for t in transacoes:
        desc = (t.get("descricao") or "").strip().lower()
        if not desc:
            continue
        cat = t["categoria"]
        if cat not in itens_por_categoria:
            itens_por_categoria[cat] = []
        partes = [p.strip().rstrip(",") for p in desc.replace(",", ",").split(",")]
        for p in partes:
            if p and p not in palavras_ignorar:
                itens_por_categoria[cat].append(p)

    if itens_por_categoria:
        dados += "\n\nITENS COMPRADOS POR CATEGORIA:"
        for cat, itens in sorted(itens_por_categoria.items()):
            agrupados = []
            usados = set()
            for item in sorted(set(itens)):
                if item in usados:
                    continue
                similares = [x for x in itens if x not in usados and difflib.SequenceMatcher(None, item, x).ratio() >= 0.8]
                total = len(similares)
                usados.update(similares)
                agrupados.append((item, total))
            agrupados.sort(key=lambda x: -x[1])
            items_str = ", ".join(f"{item} ({v}x)" for item, v in agrupados[:10])
            dados += f"\n- {cat}: {items_str}"

    if len(meses) > 1:
        mes_passado = meses[1]
        rp = db.resumo_mes(mes=mes_passado)
        variacao = ((r['total_gasto'] - rp['total_gasto']) / rp['total_gasto'] * 100) if rp['total_gasto'] > 0 else 0
        dados += f"\n\nMÊS ANTERIOR ({mes_passado}): R$ {rp['total_gasto']:.2f} (variação: {variacao:+.1f}%)"

    prompt = (
        "Você é um coach financeiro. Analise estes dados do mês e responda em 3 parágrafos:\n"
        "1) Diagnóstico rápido do mês — destaque os itens que mais aparecem\n"
        "2) Sugestões de economia baseadas nos itens comprados\n"
        "3) Uma meta simples para o próximo mês\n\n"
        f"{dados}"
    )

    try:
        async with httpx.AsyncClient(timeout=60) as client:
            resp = await client.post(
                f"{OLLAMA_BASE}/api/chat",
                json={
                    "model": model,
                    "messages": [{"role": "user", "content": prompt}],
                    "options": {"num_ctx": 4096},
                    "stream": False,
                },
            )
            resp.raise_for_status()
            reply = resp.json()["message"]["content"]
    except Exception as e:
        reply = f"Erro: {e}"

    buttons = [[InlineKeyboardButton("💰 Menu Finanças", callback_data="menu_back_financa")]]
    await query.edit_message_text(
        f"🤖 *Insights — {mes_atual}*\n\n{reply[:3000]}",
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )

async def menu_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()

    if query.data == "menu_back":
        await query.edit_message_text(
            MAIN_MENU_TEXT,
            reply_markup=MAIN_MENU_MARKUP,
            parse_mode="Markdown",
        )
        return

    if query.data == "menu_clear":
        context.user_data.pop("history", None)
        await query.edit_message_text(
            "🧹 *Histórico limpo!*\n\nA conversa foi resetada.",
            parse_mode="Markdown",
        )
        return

    if query.data == "menu_financas":
        buttons = [
            [InlineKeyboardButton("➕ Add despesa", callback_data="menu_financa_add")],
            [InlineKeyboardButton("📊 Resumo", callback_data="menu_financa_resumo")],
            [InlineKeyboardButton("🤖 Insights IA", callback_data="menu_financa_insights")],
            [InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")],
        ]
        await query.edit_message_text(
            "💰 *Finanças*\n\nEscolha uma opção:",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if query.data == "menu_financa_add":
        context.user_data["finance"] = {"step": 0, "data": {}}
        await query.edit_message_text(
            "💰 *Nova despesa* (1/4)\n\n"
            "Qual a *categoria*?\n"
            "Ex: Alimentação, Transporte, Lazer, Moradia, Saúde, Educação...",
            parse_mode="Markdown",
        )
        return

    if query.data == "menu_financa_resumo":
        await show_resumo(query)
        return

    if query.data == "menu_back_financa":
        buttons = [
            [InlineKeyboardButton("➕ Add despesa", callback_data="menu_financa_add")],
            [InlineKeyboardButton("📊 Resumo", callback_data="menu_financa_resumo")],
            [InlineKeyboardButton("🤖 Insights IA", callback_data="menu_financa_insights")],
            [InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")],
        ]
        await query.edit_message_text(
            "💰 *Finanças*\n\nEscolha uma opção:",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if query.data == "menu_finance_cancel":
        context.user_data.pop("finance", None)
        await query.edit_message_text("❌ Registro cancelado.")
        return

    if query.data == "menu_financa_insights":
        await financa_insights(query, context)
        return

    if query.data == "menu_youtube":
        buttons = [[InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")]]
        await query.edit_message_text(
            "🎬 *YouTube*\n\n"
            "Envie um link do YouTube e eu baixo o vídeo para você!\n\n"
            "📌 *Como usar:*\n"
            "1. Cole qualquer link do YouTube (vídeo, shorts, etc.)\n"
            "2. Escolha o formato: 🎬 MP4 (vídeo) ou 🎵 MP3 (áudio)\n"
            "3. O arquivo é salvo na pasta *YouTube* dentro do *Drive*\n\n"
            "📂 Depois é só acessar:\n"
            "Menu > 📁 Drive > 📂 Ver arquivos > 📁 YouTube",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if query.data == "menu_model":
        try:
            r = httpx.get("http://localhost:11434/api/tags", timeout=5)
            models = [m["name"] for m in r.json().get("models", [])] if r.status_code == 200 else []
        except:
            models = []
        if not models:
            await query.edit_message_text(
                "❌ Nenhum modelo encontrado no Ollama.",
                reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")]]),
            )
            return
        current = db.get_setting("model", MODEL)
        buttons = []
        for m in models:
            label = f"🤖 {m}"
            if m == current:
                label = f"✅ {m}"
            buttons.append([InlineKeyboardButton(label, callback_data=f"mdl_{m}")])
        buttons.append([InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")])
        await query.edit_message_text(
            f"🔄 *Trocar modelo*\n\nAtual: `{current}`\n\nEscolha um modelo abaixo:",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    await query.edit_message_text("?", parse_mode="Markdown")

async def clear(update: Update, context: ContextTypes.DEFAULT_TYPE):
    _log_update(update, "command", "/clear")
    context.user_data.pop("history", None)
    await update.message.reply_text("Histórico limpo!")

async def set_model(update: Update, context: ContextTypes.DEFAULT_TYPE):
    _log_update(update, "command", "/model " + " ".join(context.args or []))
    if context.args:
        context.user_data["model"] = context.args[0]
        await update.message.reply_text(f"Modelo alterado para: {context.args[0]}")
    else:
        current = context.user_data.get("model", MODEL)
        await update.message.reply_text(f"Modelo atual: {current}")

async def handle_drive_file(update, context, file_type):
    """Handle any file type for Drive upload."""
    if file_type == "photo":
        photo = update.message.photo[-1]
        file = await photo.get_file()
        name = f"photo_{file.file_id[:8]}.jpg"
    elif file_type == "voice":
        file = await update.message.voice.get_file()
        name = f"voice_{file.file_id[:8]}.ogg"
    else:
        return

    file_bytes = await file.download_as_bytearray()
    temp_name = f"__upload_{uuid.uuid4().hex}_{name}"
    temp_path = os.path.join(db.DRIVE_DIR, temp_name)
    with open(temp_path, "wb") as f:
        f.write(file_bytes)

    context.user_data["drive"] = {
        "step": "select_folder",
        "temp_path": temp_path,
        "file_name": name,
        "file_size": len(file_bytes),
        "mime_type": "image/jpeg" if file_type == "photo" else "audio/ogg",
        "telegram_file_id": file.file_id,
        "caption": update.message.caption or "",
    }

    buttons = []
    for f in db.listar_pastas():
        buttons.append([InlineKeyboardButton(f"📁 {f['name']}", callback_data=f"d_uf_{f['id']}")])
    buttons.append([InlineKeyboardButton("📁 Raiz (sem pasta)", callback_data="d_ur")])
    buttons.append([InlineKeyboardButton("📁 + Nova pasta", callback_data="d_nf")])
    buttons.append([InlineKeyboardButton("❌ Cancelar", callback_data="d_cancel")])

    await update.message.reply_text(
        f"📂 *Escolha a pasta* para salvar:\n\n📄 `{name}`",
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )

async def handle_voice(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not _check_whitelist(update):
        await update.message.reply_text("❌ Acesso negado.")
        return
    _log_update(update, "voice")
    drive = context.user_data.get("drive")
    if drive and drive.get("step") == "awaiting_file":
        await handle_drive_file(update, context, "voice")
        return
    model = context.user_data.get("model", MODEL)
    await update.message.reply_text("🎤 Processando áudio...")

    voice = update.message.voice
    file = await voice.get_file()
    voice_bytes = await file.download_as_bytearray()

    with tempfile.NamedTemporaryFile(suffix=".ogg", delete=False) as f_ogg:
        f_ogg.write(voice_bytes)
        ogg_path = f_ogg.name

    wav_path = ogg_path.replace(".ogg", ".wav")
    subprocess.run(
        ["ffmpeg", "-y", "-i", ogg_path, "-ar", "16000", "-ac", "1", wav_path],
        capture_output=True, check=True,
    )

    with open(wav_path, "rb") as f:
        b64 = base64.b64encode(f.read()).decode()

    os.unlink(ogg_path)
    os.unlink(wav_path)

    caption = update.message.caption or "Transcreva e responda este áudio"

    if "history" not in context.user_data:
        context.user_data["history"] = []
    context.user_data["history"].append({
        "role": "user",
        "content": caption,
        "images": [b64],
    })

    thinking = await update.message.reply_text("🧠 *IA pensando...*", parse_mode="Markdown")

    try:
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(
                f"{OLLAMA_BASE}/api/chat",
                json={
                    "model": model,
                    "messages": context.user_data["history"],
                    "options": ollama_utils.get_chat_options(model),
                    "stream": False,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            reply = data["message"]["content"]
    except Exception as e:
        reply = f"Erro: {e}"

    context.user_data["history"].append({"role": "assistant", "content": reply})
    await thinking.delete()
    await send_with_code_blocks(update, reply)

async def handle_photo(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not _check_whitelist(update):
        await update.message.reply_text("❌ Acesso negado.")
        return
    _log_update(update, "photo")
    drive = context.user_data.get("drive")
    if drive and drive.get("step") == "awaiting_file":
        await handle_drive_file(update, context, "photo")
        return
    model = context.user_data.get("model", MODEL)
    caption = update.message.caption or "Descreva esta imagem"

    photo = update.message.photo[-1]
    file = await photo.get_file()
    photo_bytes = await file.download_as_bytearray()
    b64 = base64.b64encode(photo_bytes).decode()

    if "history" not in context.user_data:
        context.user_data["history"] = []
    context.user_data["history"].append({
        "role": "user",
        "content": caption,
        "images": [b64],
    })

    thinking = await update.message.reply_text("🧠 *IA pensando...*", parse_mode="Markdown")

    try:
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(
                f"{OLLAMA_BASE}/api/chat",
                json={
                    "model": model,
                    "messages": context.user_data["history"],
                    "options": ollama_utils.get_chat_options(model),
                    "stream": False,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            reply = data["message"]["content"]
    except Exception as e:
        reply = f"Erro: {e}"

    context.user_data["history"].append({"role": "assistant", "content": reply})
    await thinking.delete()
    await send_with_code_blocks(update, reply)

def split_code_blocks(text):
    segments = []
    pattern = r'```\s*(\w*)\n(.*?)```'
    last_end = 0
    for match in re.finditer(pattern, text, re.DOTALL):
        if match.start() > last_end:
            before = text[last_end:match.start()].strip()
            if before:
                segments.append(("text", before))
        lang = match.group(1) or ""
        code = match.group(2).strip()
        if code:
            segments.append(("code", code, lang))
        last_end = match.end()
    remaining = text[last_end:].strip()
    if remaining:
        segments.append(("text", remaining))
    return segments if segments else [("text", text)]

async def _send_segment(update: Update, text: str, parse_mode=None, reply_markup=None):
    while text:
        part, text = text[:4096], text[4096:]
        kwargs = {}
        if parse_mode:
            kwargs["parse_mode"] = parse_mode
            if reply_markup and not text:
                kwargs["reply_markup"] = reply_markup
        await update.message.reply_text(part, **kwargs)

async def send_with_code_blocks(update: Update, text: str):
    segments = split_code_blocks(text)
    for seg in segments:
        if seg[0] == "text":
            await _send_segment(update, seg[1])
        elif seg[0] == "code":
            code = seg[1]
            lang = seg[2]
            code_display = f"```{lang}\n{code}```" if lang else f"```\n{code}```"
            try:
                has_copy = len(code) <= 256
                if has_copy:
                    kb = InlineKeyboardMarkup([[
                        InlineKeyboardButton("📋 Copiar código", copy_text=CopyTextButton(code))
                    ]])
                    await _send_segment(update, code_display, parse_mode="Markdown", reply_markup=kb)
                else:
                    await _send_segment(update, code_display, parse_mode="Markdown")
            except Exception:
                try:
                    await _send_segment(update, code_display)
                except Exception:
                    await _send_segment(update, f"[{lang}]\n{code}")

async def handle_finance_step(update: Update, context: ContextTypes.DEFAULT_TYPE):
    f = context.user_data.get("finance")
    if not f:
        return False

    step = f["step"]
    data = f["data"]
    texto = update.message.text.strip()

    if step == 0:
        data["categoria"] = texto
        f["step"] = 1
        await update.message.reply_text(
            "💰 *Nova despesa* (2/4)\n\n"
            "Qual a *conta* utilizada?\n"
            "Ex: Crédito, Débito, Pix, Dinheiro",
            parse_mode="Markdown",
        )
        return True

    if step == 1:
        data["conta"] = texto
        f["step"] = 2
        await update.message.reply_text(
            "💰 *Nova despesa* (3/4)\n\n"
            "Qual o *valor*?\n"
            "Ex: 45,90",
            parse_mode="Markdown",
        )
        return True

    if step == 2:
        try:
            valor = float(texto.replace(",", "."))
            data["valor"] = valor
        except ValueError:
            await update.message.reply_text("Valor inválido. Digite apenas números (ex: 45,90).")
            return True
        f["step"] = 3
        await update.message.reply_text(
            "💰 *Nova despesa* (4/4)\n\n"
            "Qual a *descrição*? (opcional)\n"
            "Envie `-` para pular.",
            parse_mode="Markdown",
        )
        return True

    if step == 3:
        data["descricao"] = "" if texto == "-" else texto
        db.inserir(data["categoria"], data["conta"], data["valor"], data.get("descricao", ""))
        context.user_data.pop("finance", None)
        await update.message.reply_text(
            "✅ *Despesa registrada!*\n\n"
            f"📂 {data['categoria']}\n"
            f"💳 {data['conta']}\n"
            f"💰 R$ {data['valor']:.2f}\n"
            f"📝 {data.get('descricao', '')}",
            parse_mode="Markdown",
        )
        return True

    return False

# ─── Drive ────────────────────────────────────────────────

def drive_cleanup(context):
    drive = context.user_data.pop("drive", None)
    if drive and drive.get("temp_path") and os.path.exists(drive["temp_path"]):
        os.remove(drive["temp_path"])

async def drive_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()
    data = query.data.split("_", 2)
    cb = data[1]

    if cb == "main":
        buttons = [
            [InlineKeyboardButton("📂 Ver arquivos", callback_data="d_list")],
            [InlineKeyboardButton("📤 Enviar arquivo", callback_data="d_up")],
            [InlineKeyboardButton("📁 Nova pasta", callback_data="d_nf")],
            [InlineKeyboardButton("🔙 Voltar", callback_data="d_back")],
        ]
        await query.edit_message_text(
            "📁 *Drive*\n\nGerencie seus arquivos e pastas.",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if cb == "back":
        await query.edit_message_text(
            MAIN_MENU_TEXT,
            reply_markup=MAIN_MENU_MARKUP,
            parse_mode="Markdown",
        )
        return

    if cb == "list":
        await drive_show_folder(query, context, None)
        return

    if cb == "f":
        folder_id = int(data[2])
        await drive_show_folder(query, context, folder_id)
        return

    if cb == "p":
        folder_id = int(data[2])
        folder = db.get_pasta(folder_id)
        if folder and folder["parent_id"]:
            await drive_show_folder(query, context, folder["parent_id"])
        else:
            await drive_show_folder(query, context, None)
        return

    if cb == "fl":
        file_id = int(data[2])
        file_info = db.get_arquivo(file_id)
        if not file_info or not os.path.exists(file_info["file_path"]):
            await query.edit_message_text("❌ Arquivo não encontrado no disco.")
            return
        await query.message.reply_document(
            document=open(file_info["file_path"], "rb"),
            filename=file_info["name"],
        )
        await query.edit_message_text(
            f"📤 *Arquivo enviado:* `{file_info['name']}`",
            parse_mode="Markdown",
        )
        return

    if cb == "up":
        context.user_data["drive"] = {"step": "awaiting_file"}
        await query.edit_message_text(
            "📤 *Envie um arquivo* para salvar no Drive.\n\n"
            "Envie qualquer documento, foto ou vídeo.",
            parse_mode="Markdown",
        )
        return

    if cb in ("uf", "ur"):
        drive = context.user_data.get("drive", {})
        temp_path = drive.get("temp_path")
        if not temp_path or not os.path.exists(temp_path):
            await query.edit_message_text("❌ Arquivo não encontrado. Tente novamente.")
            return
        folder_id = int(data[2]) if cb == "uf" else None
        fid = db.inserir_arquivo(
            name=drive["file_name"],
            folder_id=folder_id,
            file_path="",
            file_size=drive["file_size"],
            mime_type=drive.get("mime_type", ""),
            telegram_file_id=drive.get("telegram_file_id", ""),
            caption=drive.get("caption", ""),
        )
        final_path = os.path.join(db.DRIVE_DIR, f"{fid}_{drive['file_name']}")
        os.rename(temp_path, final_path)
        db.update_arquivo_path(fid, final_path)
        drive_cleanup(context)
        await query.edit_message_text(
            f"✅ *Arquivo salvo!*\n\n📄 `{drive['file_name']}`",
            parse_mode="Markdown",
        )
        return

    if cb == "nf":
        drive = context.user_data.get("drive", {})
        if drive.get("temp_path"):
            drive["step"] = "new_folder"
            context.user_data["drive"] = drive
        else:
            context.user_data["drive"] = {"step": "new_folder", "parent_id": None}
        await query.edit_message_text(
            "📁 *Digite o nome da nova pasta:*",
            parse_mode="Markdown",
        )
        return

    if cb == "cancel":
        drive_cleanup(context)
        await query.edit_message_text("❌ Operação cancelada.")
        return

async def drive_show_folder(query, context, folder_id):
    folders = db.listar_pastas(parent_id=folder_id)
    files = db.listar_arquivos(folder_id=folder_id)
    buttons = []
    for f in folders:
        buttons.append([InlineKeyboardButton(f"📁 {f['name']}", callback_data=f"d_f_{f['id']}")])
    for f in files:
        sz = f"{f['file_size'] / 1024:.1f} KB" if f['file_size'] < 1024 * 1024 else f"{f['file_size'] / (1024*1024):.1f} MB"
        buttons.append([InlineKeyboardButton(f"📄 {f['name']} ({sz})", callback_data=f"d_fl_{f['id']}")])
    if folder_id is not None:
        buttons.append([InlineKeyboardButton("🔙 Voltar", callback_data=f"d_p_{folder_id}")])
    else:
        buttons.append([InlineKeyboardButton("🔙 Voltar", callback_data="d_main")])
    label = "📂 *Drive*" if folder_id is None else f"📂 *{db.get_pasta(folder_id)['name']}*"
    await query.edit_message_text(
        f"{label}\n\nSelecione uma pasta ou arquivo:",
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )

async def handle_document(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not _check_whitelist(update):
        await update.message.reply_text("❌ Acesso negado.")
        return
    _log_update(update, "document")
    drive = context.user_data.get("drive")
    if not drive or drive.get("step") != "awaiting_file":
        await update.message.reply_text(
            "Use /menu > 📁 Drive > 📤 Enviar arquivo para salvar arquivos."
        )
        return

    doc = update.message.document
    file = await doc.get_file()
    file_bytes = await file.download_as_bytearray()

    temp_name = f"__upload_{uuid.uuid4().hex}_{doc.file_name or 'file'}"
    temp_path = os.path.join(db.DRIVE_DIR, temp_name)
    with open(temp_path, "wb") as f:
        f.write(file_bytes)

    context.user_data["drive"] = {
        "step": "select_folder",
        "temp_path": temp_path,
        "file_name": doc.file_name or f"file_{doc.file_id[:8]}",
        "file_size": doc.file_size or 0,
        "mime_type": doc.mime_type or "",
        "telegram_file_id": doc.file_id,
        "caption": update.message.caption or "",
    }

    buttons = []
    for f in db.listar_pastas():
        buttons.append([InlineKeyboardButton(f"📁 {f['name']}", callback_data=f"d_uf_{f['id']}")])
    buttons.append([InlineKeyboardButton("📁 Raiz (sem pasta)", callback_data="d_ur")])
    buttons.append([InlineKeyboardButton("📁 + Nova pasta", callback_data="d_nf")])
    buttons.append([InlineKeyboardButton("❌ Cancelar", callback_data="d_cancel")])

    await update.message.reply_text(
        f"📂 *Escolha a pasta* para salvar:\n\n📄 `{doc.file_name}`",
        reply_markup=InlineKeyboardMarkup(buttons),
        parse_mode="Markdown",
    )

async def handle_drive_text(update: Update, context: ContextTypes.DEFAULT_TYPE):
    drive = context.user_data.get("drive")
    if not drive:
        return False

    if drive.get("step") != "new_folder":
        return False

    name = update.message.text.strip()
    if not name:
        await update.message.reply_text("O nome não pode estar vazio.")
        return True

    parent_id = drive.get("parent_id")
    folder_id = db.criar_pasta(name, parent_id)

    if drive.get("temp_path"):
        fid = db.inserir_arquivo(
            name=drive["file_name"],
            folder_id=folder_id,
            file_path="",
            file_size=drive["file_size"],
            mime_type=drive.get("mime_type", ""),
            telegram_file_id=drive.get("telegram_file_id", ""),
            caption=drive.get("caption", ""),
        )
        final_path = os.path.join(db.DRIVE_DIR, f"{fid}_{drive['file_name']}")
        os.rename(drive["temp_path"], final_path)
        db.update_arquivo_path(fid, final_path)
        drive_cleanup(context)
        await update.message.reply_text(
            f"✅ *Arquivo salvo!*\n\n📄 `{drive['file_name']}`\n📁 `{name}`",
            parse_mode="Markdown",
        )
    else:
        context.user_data.pop("drive", None)
        await update.message.reply_text(
            f"✅ *Pasta criada!*\n\n📁 `{name}`",
            parse_mode="Markdown",
        )
    return True

# ─── End Drive ────────────────────────────────────────────

# ─── YouTube ──────────────────────────────────────────────

async def youtube_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()

    if query.data == "yt_cancel":
        context.user_data.pop("youtube", None)
        await query.edit_message_text("❌ Download cancelado.")
        return

    yt_data = context.user_data.get("youtube")
    if not yt_data or not yt_data.get("url"):
        await query.edit_message_text("❌ Link não encontrado. Envie novamente.")
        return

    fmt = "mp3" if query.data == "yt_mp3" else "mp4"
    url = yt_data["url"]
    await query.edit_message_text(f"⏳ Baixando {fmt.upper()}...")

    tmp_dir = tempfile.mkdtemp()
    try:
        loop = asyncio.get_event_loop()
        fname, fpath, fsize, title = await loop.run_in_executor(
            None, youtube.download, url, fmt, tmp_dir,
        )
    except Exception as e:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        context.user_data.pop("youtube", None)
        await query.edit_message_text(f"❌ Erro ao baixar: {e}")
        return

    if not fpath:
        shutil.rmtree(tmp_dir, ignore_errors=True)
        context.user_data.pop("youtube", None)
        await query.edit_message_text("❌ Não foi possível encontrar o arquivo baixado.")
        return

    folder_id = youtube.get_youtube_folder_id()
    fid = db.inserir_arquivo(
        name=os.path.basename(fpath),
        folder_id=folder_id,
        file_path="",
        file_size=fsize,
        mime_type=f"video/{fmt}" if fmt == "mp4" else "audio/mpeg",
    )
    final_name = f"{fid}_{os.path.basename(fpath)}"
    final_path = os.path.join(db.DRIVE_DIR, final_name)
    shutil.move(fpath, final_path)
    db.update_arquivo_path(fid, final_path)
    shutil.rmtree(tmp_dir, ignore_errors=True)
    context.user_data.pop("youtube", None)

    await query.edit_message_text(
        f"✅ *Download concluído!*\n\n"
        f"🎬 `{title}`\n"
        f"📁 Salvo em *YouTube*\n"
        f"📦 {fsize / 1024 / 1024:.1f} MB",
        parse_mode="Markdown",
    )

async def sysmon_cmd(update: Update, context: ContextTypes.DEFAULT_TYPE):
    _log_update(update, "command", "/sysmon")
    try:
        import sysmon
        data = sysmon.collect()
    except:
        await update.message.reply_text("❌ Erro ao coletar dados do sistema.")
        return
    cpu = data.get("cpu", 0)
    temp = data.get("cpu_temp", 0)
    ram = data.get("ram", {})
    gpu = data.get("gpu", {})
    lines = [
        "🖥 *Monitor do Sistema*",
        f"─" * 30,
        f"",
        f"⚙ *CPU*: {cpu}%",
        f"🌡 *Temperatura*: {temp}°C",
        f"",
        f"💾 *RAM*: {ram.get('used', 0)}MB / {ram.get('total', 0)}MB ({ram.get('pct', 0)}%)",
    ]
    if gpu.get("present"):
        lines += [
            f"",
            f"🎮 *GPU VRAM*: {gpu.get('vram_used', 0)}MB / {gpu.get('vram_total', 0)}MB ({gpu.get('vram_pct', 0)}%)",
            f"🌡 *GPU Temp*: {gpu.get('temp', 0)}°C",
        ]
    else:
        lines.append(f"\n🎮 GPU: Não detectada (sem NVIDIA-SMI)")
    await update.message.reply_text("\n".join(lines), parse_mode="Markdown")

async def codigo(update: Update, context: ContextTypes.DEFAULT_TYPE):
    _log_update(update, "command", "/codigo " + " ".join(context.args or [])[:100])
    if not context.args:
        await update.message.reply_text(
            "Use: `/codigo <descrição>`\n\nEx: `/codigo um site de papelaria`",
            parse_mode="Markdown",
        )
        return
    idea = " ".join(context.args)
    safe = "".join(c for c in idea if c.isalnum() or c in " _-").strip().replace(" ", "_")[:30]
    if not safe:
        safe = f"projeto_{int(time.time())}"

    msg = await update.message.reply_text("🧠 1/3 Melhorando o prompt...")
    loop = asyncio.get_event_loop()
    try:
        improved = await loop.run_in_executor(None, trilha_rede.improve_prompt, idea)
    except Exception as e:
        await msg.edit_text(f"❌ Erro ao melhorar prompt: {e}")
        return
    if improved.startswith("Erro"):
        await msg.edit_text(f"❌ {improved}")
        return

    project_path = os.path.join(db.TRILHA_DIR, safe)
    proj_id = db.criar_projeto(safe, project_path, idea, improved)

    await msg.edit_text(f"🧠 2/3 Prompt melhorado! (√ {len(improved)} chars)\n🚀 3/3 Gerando código com opencode...")
    try:
        status = await loop.run_in_executor(None, lambda: trilha_rede.generate(project_path, improved))
    except Exception as e:
        status = "error"
    db.update_projeto_status(proj_id, status)

    if status == "done":
        all_files = []
        for root, dirs, fnames in os.walk(project_path):
            for f in fnames:
                if not f.startswith("_"):
                    rel = os.path.relpath(os.path.join(root, f), project_path)
                    all_files.append(rel)
        file_list = "\n".join(f"  📄 {f}" for f in all_files[:10])
        if len(all_files) > 10:
            file_list += f"\n  ... e mais {len(all_files) - 10} arquivos"
        msg_text = (
            f"✅ *Projeto criado!* `{safe}`\n"
            f"📁 `{project_path}`\n"
            f"📄 {len(all_files)} arquivos\n\n{file_list}"
        )
    else:
        logs = trilha_rede.get_logs(project_path)
        msg_text = f"❌ *Erro* em `{safe}`\n📋 Últimos logs:\n```\n{''.join(logs[-5:])[:1500]}```"
    await msg.edit_text(msg_text, parse_mode="Markdown")

# ─── Trilha Rede ──────────────────────────────────────────

async def trilha_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()
    data = query.data.split("_", 2)
    cb = data[1]

    if cb == "main":
        buttons = [
            [InlineKeyboardButton("📋 Meus projetos", callback_data="tr_projects")],
            [InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")],
        ]
        await query.edit_message_text(
            "🧠 *Trilha Rede*\n\n"
            "Gere projetos completos com IA!\n\n"
            "📌 *Como usar:*\n"
            "```\n/codigo <descrição do projeto>\n```\n"
            "Ex: `/codigo um site de papelaria`\n\n"
            "O bot vai:\n"
            "1️⃣ Melhorar seu prompt com IA 🧠\n"
            "2️⃣ Gerar o código com opencode 🚀\n"
            "3️⃣ Salvar na pasta `trilha_projetos/` 📁",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if cb == "projects":
        projetos = db.listar_projetos()
        if not projetos:
            buttons = [[InlineKeyboardButton("🔙 Voltar", callback_data="tr_main")]]
            await query.edit_message_text(
                "📋 *Nenhum projeto ainda.*\n\nCrie um em 🚀 Novo projeto",
                reply_markup=InlineKeyboardMarkup(buttons),
                parse_mode="Markdown",
            )
            return
        lines = ["📋 *Meus Projetos*\n"]
        buttons = []
        for p in projetos[:10]:
            icons = {"pending": "⏳", "running": "🔄", "done": "✅", "error": "❌"}
            icon = icons.get(p["status"], "❓")
            lines.append(f"{icon} *{p['name']}* — {p['status']}")
            buttons.append([InlineKeyboardButton(
                f"{icon} {p['name']}",
                callback_data=f"tr_status_{p['id']}",
            )])
        buttons.append([InlineKeyboardButton("🔙 Voltar", callback_data="tr_main")])
        await query.edit_message_text(
            "\n".join(lines),
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if cb == "status":
        proj_id = int(data[2])
        proj = db.get_projeto(proj_id)
        if not proj:
            await query.edit_message_text("❌ Projeto não encontrado.")
            return
        icons = {"pending": "⏳", "running": "🔄", "done": "✅", "error": "❌"}
        icon = icons.get(proj["status"], "❓")
        logs = trilha_rede.get_logs(proj["project_path"], 10)
        log_text = "\n".join(logs) if logs else "Sem logs"
        buttons = [[InlineKeyboardButton("🔙 Meus projetos", callback_data="tr_projects")]]
        await query.edit_message_text(
            f"{icon} *{proj['name']}*\n\n"
            f"📌 Status: *{proj['status']}*\n"
            f"📁 Pasta: `{proj['project_path']}`\n\n"
            f"📝 *Prompt:*\n{proj['prompt_melhorado'][:200]}...\n\n"
            f"📋 *Logs:*\n```\n{log_text[:1500]}```",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

# ─── Model callback ─────────────────────────────────────

async def model_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()
    model_name = query.data.split("_", 1)[1]
    global MODEL
    MODEL = model_name
    db.set_setting("model", model_name)
    await query.edit_message_text(
        f"✅ *Modelo alterado para:* `{model_name}`\n\n🔄 O novo modelo será usado nas próximas interações.",
        parse_mode="Markdown",
        reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")]]),
    )

# ─── End Model callback ─────────────────────────────────

# ─── Cleanup ──────────────────────────────────────────────

async def cleanup_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()
    data = query.data.split("_", 2)
    cb = data[1]

    if cb == "main":
        buttons = [
            [InlineKeyboardButton("🧹 Limpeza segura", callback_data="cl_safe")],
            [InlineKeyboardButton("⚠️ Limpeza completa", callback_data="cl_all")],
            [InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")],
        ]
        await query.edit_message_text(
            "🧹 *Limpeza do Sistema*\n\n"
            "Escolha o tipo de limpeza:\n\n"
            "✅ *Segura* — mantém servidor rodando\n"
            "   Cache apt, logs, cache do usuário,\n"
            "   lixeira, pip, npm, snap, kernels antigos\n\n"
            "⚠️ *Completa* — tudo acima + /tmp e cache RAM\n"
            "   Pode afetar processos temporários\n\n"
            "📌 *Comandos individuais* disponíveis abaixo:",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if cb == "safe":
        await query.edit_message_text("🧹 *Limpando sistema (modo seguro)...*\n⏳ Aguarde...", parse_mode="Markdown")
        results = cleanup.run_safe()
        lines = ["✅ *Limpeza segura concluída!*\n"]
        for r in results:
            icon = "✅" if r["ok"] else "❌"
            task_name = next((t["name"] for t in cleanup.TASKS if t["id"] == r["id"]), r["id"])
            lines.append(f"{icon} *{task_name}*")
            if r["stdout"]:
                lines.append(f"   `{r['stdout'][:100]}`")
        buttons = [[InlineKeyboardButton("🔙 Limpeza", callback_data="cl_main")]]
        await query.edit_message_text(
            "\n".join(lines)[:4000],
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if cb == "all":
        await query.edit_message_text("⚠️ *Limpando sistema (completo)...*\n⏳ Aguarde...", parse_mode="Markdown")
        results = cleanup.run_all()
        lines = ["⚠️ *Limpeza completa concluída!*\n"]
        for r in results:
            icon = "✅" if r["ok"] else "❌"
            task_name = next((t["name"] for t in cleanup.TASKS if t["id"] == r["id"]), r["id"])
            lines.append(f"{icon} *{task_name}*")
        buttons = [[InlineKeyboardButton("🔙 Limpeza", callback_data="cl_main")]]
        await query.edit_message_text(
            "\n".join(lines)[:4000],
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    if cb == "task":
        task_id = data[2]
        await query.edit_message_text(f"⏳ Executando *{task_id}*...", parse_mode="Markdown")
        r = cleanup.run_task(task_id)
        task_name = next((t["name"] for t in cleanup.TASKS if t["id"] == r["id"]), r["id"])
        icon = "✅" if r["ok"] else "❌"
        lines = [f"{icon} *{task_name}*"]
        if r["stdout"]: lines.append(f"```\n{r['stdout'][:300]}\n```")
        if r["stderr"]: lines.append(f"⚠️ `{r['stderr'][:200]}`")
        buttons = [[InlineKeyboardButton("🔙 Limpeza", callback_data="cl_main")]]
        await query.edit_message_text(
            "\n".join(lines)[:4000],
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )

# ─── PC Apps ────────────────────────────────────────────

PC_APPS = [
    ("▶ YouTube", "xdg-open https://youtube.com"),
    ("💻 VS Code", "code"),
    ("🎬 Max", "xdg-open https://play.max.com"),
    ("☕ Caffeine", "caffeine"),
    ("💤 Sleep", "sleep"),
    ("🔒 Bloqueio", "lock"),
]

async def pc_apps_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()
    raw = query.data
    # Sleep controls
    if raw.startswith("pc_sl_"):
        action = raw.replace("pc_sl_", "")
        if action == "ask_h":
            context.user_data["sleep_h"] = 0
            context.user_data["sleep_m"] = 0
            await query.edit_message_text(
                "💤 *Quantas horas?*\n\nEscolha as horas até o desligamento:",
                parse_mode="Markdown",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton(str(h), callback_data=f"pc_sl_h_{h}") for h in range(0, 6, 2)],
                    [InlineKeyboardButton(str(h), callback_data=f"pc_sl_h_{h}") for h in range(1, 6, 2)],
                    [InlineKeyboardButton("❌ Cancelar", callback_data="pc_main")],
                ]),
            )
            return
        if action.startswith("h_"):
            context.user_data["sleep_h"] = int(action.split("_")[1])
            await query.edit_message_text(
                "💤 *Quantos minutos?* (além das horas)",
                parse_mode="Markdown",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton(str(m), callback_data=f"pc_sl_m_{m}") for m in range(0, 60, 10)],
                    [InlineKeyboardButton(str(m), callback_data=f"pc_sl_m_{m}") for m in range(5, 60, 10)],
                    [InlineKeyboardButton("❌ Cancelar", callback_data="pc_main")],
                ]),
            )
            return
        if action.startswith("m_"):
            context.user_data["sleep_m"] = int(action.split("_")[1])
            h = context.user_data.get("sleep_h", 0)
            m = context.user_data.get("sleep_m", 0)
            total_min = h * 60 + m
            if total_min < 1:
                await query.edit_message_text("⏳ Tempo mínimo: 1 minuto.", reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Voltar", callback_data="pc_main")]]))
                return
            cmd = f"shutdown -h +{total_min}"
            label = f"{h}h{m:02d}min" if h else f"{m}min"
            try:
                subprocess.Popen(
                    f"x-terminal-emulator -e bash -c '{cmd}'",
                    shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
                await query.edit_message_text(
                    f"✅ *Terminal aberto com:*\n`{cmd}`\n\n⏱ Desligamento em *{label}*\n\n📌 Se pedir permissão, digite a senha no terminal.",
                    parse_mode="Markdown",
                    reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Apps", callback_data="pc_main")]]),
                )
            except Exception as e:
                await query.edit_message_text(
                    f"❌ Erro: `{e}`",
                    parse_mode="Markdown",
                    reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Apps", callback_data="pc_main")]]),
                )
            return
    data = query.data.split("_", 2)
    cb = data[1]
    if cb == "main":
        buttons = []
        for i, (label, cmd) in enumerate(PC_APPS):
            col = i % 2
            if col == 0:
                buttons.append([InlineKeyboardButton(label, callback_data=f"pc_run_{i}")])
            else:
                buttons[-1].append(InlineKeyboardButton(label, callback_data=f"pc_run_{i}"))
        buttons.append([InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")])
        await query.edit_message_text(
            "📱 *PC Apps*\n\n"
            "Toque em um app para abrir no computador:",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return
    if cb == "run":
        idx = int(data[2])
        if idx < 0 or idx >= len(PC_APPS):
            await query.edit_message_text("❌ App inválido.")
            return
        label, cmd = PC_APPS[idx]
        if cmd == "sleep":
            context.user_data["sleep_h"] = 0
            context.user_data["sleep_m"] = 0
            await query.edit_message_text(
                "💤 *Quantas horas?*\n\nEscolha as horas até o desligamento:",
                parse_mode="Markdown",
                reply_markup=InlineKeyboardMarkup([
                    [InlineKeyboardButton(str(h), callback_data=f"pc_sl_h_{h}") for h in range(0, 6, 2)],
                    [InlineKeyboardButton(str(h), callback_data=f"pc_sl_h_{h}") for h in range(1, 6, 2)],
                    [InlineKeyboardButton("❌ Cancelar", callback_data="pc_main")],
                ]),
            )
            return
        if cmd == "lock":
            try:
                subprocess.run(["xdg-screensaver", "lock"], timeout=5)
                await query.edit_message_text(
                    "🔒 *Tela bloqueada!*",
                    parse_mode="Markdown",
                    reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Apps", callback_data="pc_main")]]),
                )
            except Exception as e:
                await query.edit_message_text(
                    f"❌ Erro ao bloquear: `{e}`",
                    parse_mode="Markdown",
                    reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Apps", callback_data="pc_main")]]),
                )
            return
        try:
            subprocess.Popen(cmd, shell=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            await query.edit_message_text(
                f"✅ *Abrindo:* `{label}`\n\n"
                f"Comando: `{cmd}`",
                parse_mode="Markdown",
                reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Apps", callback_data="pc_main")]]),
            )
        except Exception as e:
            await query.edit_message_text(
                f"❌ Erro ao abrir `{label}`:\n`{e}`",
                parse_mode="Markdown",
                reply_markup=InlineKeyboardMarkup([[InlineKeyboardButton("🔙 Apps", callback_data="pc_main")]]),
            )



# ─── End PC Apps ────────────────────────────────────────

# ─── Security ──────────────────────────────────────────

SEC_CHECKS_BOT = ["connections", "ssh", "integrity", "persistence", "processes", "ports", "firewall", "fail2ban", "sudo", "updates", "services", "users"]

async def security_callback(update: Update, context: ContextTypes.DEFAULT_TYPE):
    query = update.callback_query
    if not _check_whitelist(update):
        await query.answer("❌ Acesso negado", show_alert=True)
        return
    _log_update(update, "callback", query.data)
    await query.answer()
    data = query.data.split("_", 2)
    cb = data[1]
    if cb == "main":
        buttons = [
            [InlineKeyboardButton("▶ Executar varredura", callback_data="sec_scan")],
            [InlineKeyboardButton("📄 Relatório com IA", callback_data="sec_report")],
            [InlineKeyboardButton("🖥 Monitor Sistema", callback_data="sec_sysmon")],
            [InlineKeyboardButton("🔙 Voltar", callback_data="menu_back")],
        ]
        await query.edit_message_text(
            "🛡️ *Segurança do Servidor*\n\n"
            "Escolha uma opção:",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return
    if cb == "sysmon":
        try:
            import sysmon
            data = sysmon.collect()
            history = db.list_sysmon_history(5)
        except:
            await query.edit_message_text("❌ Erro ao coletar dados.")
            return
        cpu = data.get("cpu", 0)
        temp = data.get("cpu_temp", 0)
        ram = data.get("ram", {})
        gpu = data.get("gpu", {})
        lines = [
            "🖥 *Monitor do Sistema*",
            f"─" * 30,
            f"",
            f"⚙ *CPU*: {cpu}%",
            f"🌡 *Temp CPU*: {temp}°C",
            f"",
            f"💾 *RAM*: {ram.get('used', 0)}MB / {ram.get('total', 0)}MB ({ram.get('pct', 0)}%)",
        ]
        if gpu.get("present"):
            lines += [
                f"",
                f"🎮 *GPU*: {gpu.get('vram_pct', 0)}%",
                f"💾 *VRAM*: {gpu.get('vram_used', 0)}MB / {gpu.get('vram_total', 0)}MB",
                f"🌡 *Temp GPU*: {gpu.get('temp', 0)}°C",
            ]
        else:
            lines.append(f"\n🎮 GPU: N/A")
        if history:
            lines += ["", f"📊 *Últimas leituras:*"]
            for h in history[:5]:
                gpu_info = f" GPU {h['gpu_pct']}% VRAM {h['gpu_vram_used']}MB" if h.get('gpu_vram_total', 0) > 0 else ""
                lines.append(f"• {h.get('created_at','')[:16]} — CPU {h['cpu']}% 🌡{h['cpu_temp']}°C RAM {h['ram_pct']}%{gpu_info}")
        buttons = [[InlineKeyboardButton("🔙 Menu", callback_data="sec_main")]]
        await query.edit_message_text("\n".join(lines), reply_markup=InlineKeyboardMarkup(buttons), parse_mode="Markdown")
        return
    if cb == "scan":
        await query.edit_message_text("🛡️ *Executando varredura...*\n⏳ Aguarde...", parse_mode="Markdown")
        results = security.run_all()
        lines = [f"🛡️ *SEGURANÇA DO SERVIDOR*"]
        icons = {"connections": "🌐", "ssh": "🔑", "integrity": "📁", "persistence": "⏱", "processes": "⚙", "ports": "🚪", "firewall": "🔥", "fail2ban": "🛡", "sudo": "👤", "updates": "📦", "services": "⚙", "users": "👥"}
        total_risk = 0
        for name in SEC_CHECKS_BOT:
            r = results.get(name, {})
            st = r.get("status", "erro")
            meta = r.get("meta", {})
            is_alert = st == "alerta"
            is_atten = st == "atencao"
            if is_alert: total_risk += 1
            icon = "🔴" if is_alert else "🟡" if is_atten else "🟢" if st == "ok" else "⚠️"
            label = icons.get(name, name)
            alerts = r.get("alerts", [])
            attentions = r.get("attentions", [])
            cmd = meta.get("cmd", "")
            desc = meta.get("desc", "").split("—")[0].strip()
            status_label = "🔴 RISCO" if is_alert else "🟡 ATENÇÃO" if is_atten else "🟢 OK"
            lines.append(f"\n{icon} *{label}* {status_label}")
            lines.append(f"   `$ {cmd}`")
            lines.append(f"   {desc}")
            if alerts:
                lines.append(f"   ⚠️ *{len(alerts)} alerta(s)*:")
                for a in alerts[:2]:
                    lines.append(f"   • {a}")
            elif r.get("own_ports"):
                lines.append(f"   🟢 *{len(r['own_ports'])} porta(s) do próprio app*")
                for a in r["own_ports"][:2]:
                    lines.append(f"   • {a}")
            elif attentions:
                lines.append(f"   🟡 *{len(attentions)} atenção(ões)*:")
                for a in attentions[:2]:
                    lines.append(f"   • {a}")
            else:
                lines.append(f"   ✅ Nenhum alerta")
        buttons = [
            [InlineKeyboardButton("📄 Relatório com IA", callback_data="sec_report")],
            [InlineKeyboardButton("🔙 Menu", callback_data="sec_main")],
        ]
        await query.edit_message_text(
            "\n".join(lines),
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return
    if cb == "report":
        await query.edit_message_text("🧠 *Gerando relatório com IA...*\n⏳ Aguarde...", parse_mode="Markdown")
        results = security.run_all()
        alerts = []
        for name in SEC_CHECKS_BOT:
            r = results.get(name, {})
            meta = r.get("meta", {})
            for a in r.get("alerts", []):
                alerts.append(f"[{meta.get('cmd','')}] {a}")
        lines_prompt = []
        for name in SEC_CHECKS_BOT:
            r2 = results.get(name, {})
            st = r2.get("status", "ok")
            al = r2.get("alerts", [])
            at = r2.get("attentions", [])
            lines_prompt.append(f"{name}: status={st}, alerts={len(al)}, attentions={len(at)}")
            for a in al[:3]: lines_prompt.append(f"  alerta: {a}")
            for a in at[:3]: lines_prompt.append(f"  atencao: {a}")
            if not al and not at: lines_prompt.append(f"  tudo ok")
        full_report = "\n".join(lines_prompt)
        model = context.user_data.get("model", MODEL)
        prompt = (
            "Você é um analista de segurança Linux. Analise o relatório abaixo verificando cada item "
            "individualmente (conexões de saída, SSH, integridade de arquivos, persistência, processos, portas). "
            "Explique o resultado de cada teste de forma concisa em português. "
            "Se houver portas como 631 em localhost, cite como atenção de baixo risco, não como alerta grave. "
            "Portas do próprio app (Python, servidor web) são normais e não representam risco. "
            "Responda em até 4 parágrafos no máximo.\n\n"
            f"RELATÓRIO:\n{full_report}"
        )
        try:
            async with httpx.AsyncClient(timeout=30) as client:
                resp = await client.post(
                    f"{OLLAMA_BASE}/api/chat",
                    json={"model": model, "messages": [{"role": "user", "content": prompt}], "options": ollama_utils.get_chat_options(model), "stream": False},
                )
                ia_analysis = resp.json().get("message", {}).get("content", "Erro na análise.")
        except Exception as e:
            ia_analysis = f"Erro ao consultar IA: {e}"
        try:
            db.save_report(len(alerts) > 0, len(alerts), ia_analysis, summary, source="bot")
        except:
            pass
        lines = [
            f"🤖 *Análise da IA:*",
            ia_analysis[:1500],
            "",
            f"🛡️ *RESUMO*",
        ]
        icons = {"connections": "🌐", "ssh": "🔑", "integrity": "📁", "persistence": "⏱", "processes": "⚙", "ports": "🚪", "firewall": "🔥", "fail2ban": "🛡", "sudo": "👤", "updates": "📦", "services": "⚙", "users": "👥"}
        for name in SEC_CHECKS_BOT:
            r = results.get(name, {})
            st = r.get("status", "erro")
            meta = r.get("meta", {})
            is_alert = st == "alerta"
            is_atten = st == "atencao"
            icon = "🔴" if is_alert else "🟡" if is_atten else "🟢" if st == "ok" else "⚠️"
            label = icons.get(name, name)
            alerts2 = r.get("alerts", [])
            attentions2 = r.get("attentions", [])
            status_label = "🔴 RISCO" if is_alert else "🟡 ATENÇÃO" if is_atten else "🟢 OK"
            lines.append(f"\n{icon} *{label}* {status_label}")
            lines.append(f"   `$ {meta.get('cmd','')}`")
            if alerts2:
                lines.append(f"   ⚠️ {len(alerts2)} alerta(s)")
                for a in alerts2[:1]:
                    lines.append(f"   • {a}")
            elif r.get("own_ports"):
                lines.append(f"   🟢 {len(r['own_ports'])} porta(s) do próprio app")
            elif attentions2:
                lines.append(f"   🟡 {len(attentions2)} atenção(ões)")
                for a in attentions2[:1]:
                    lines.append(f"   • {a}")
            else:
                lines.append(f"   ✅ Nenhum alerta")
        buttons = [[InlineKeyboardButton("🔙 Menu", callback_data="sec_main")]]
        await query.edit_message_text(
            "\n".join(lines),
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

# ─── End Security ──────────────────────────────────────

# ─── End Trilha Rede ──────────────────────────────────────

async def cancel(update: Update, context: ContextTypes.DEFAULT_TYPE):
    _log_update(update, "command", "/cancel")
    if context.user_data.get("finance"):
        context.user_data.pop("finance", None)
        await update.message.reply_text("❌ Registro cancelado.")
    elif context.user_data.get("drive"):
        drive_cleanup(context)
        await update.message.reply_text("❌ Operação no Drive cancelada.")
    elif context.user_data.get("youtube"):
        context.user_data.pop("youtube", None)
        await update.message.reply_text("❌ Download cancelado.")
    else:
        await update.message.reply_text("Nada para cancelar.")

async def handle_message(update: Update, context: ContextTypes.DEFAULT_TYPE):
    if not _check_whitelist(update):
        await update.message.reply_text("❌ Acesso negado. Você não está na lista de permissão.")
        return
    user_message = update.message.text
    _log_update(update, "text", user_message[:100])
    if context.user_data.get("finance"):
        await handle_finance_step(update, context)
        return
    if context.user_data.get("drive"):
        if await handle_drive_text(update, context):
            return

    yt_url = youtube.extract_url(user_message)
    if yt_url:
        context.user_data["youtube"] = {"url": yt_url}
        buttons = [
            [InlineKeyboardButton("🎬 MP4 (vídeo)", callback_data="yt_mp4")],
            [InlineKeyboardButton("🎵 MP3 (áudio)", callback_data="yt_mp3")],
            [InlineKeyboardButton("❌ Cancelar", callback_data="yt_cancel")],
        ]
        await update.message.reply_text(
            "🎬 *Link do YouTube detectado!*\n\nEscolha o formato:",
            reply_markup=InlineKeyboardMarkup(buttons),
            parse_mode="Markdown",
        )
        return

    model = context.user_data.get("model", MODEL)

    if "history" not in context.user_data:
        context.user_data["history"] = []
    context.user_data["history"].append({"role": "user", "content": user_message})

    thinking = await update.message.reply_text("🧠 *IA pensando...*", parse_mode="Markdown")

    try:
        async with httpx.AsyncClient(timeout=120) as client:
            resp = await client.post(
                f"{OLLAMA_BASE}/api/chat",
                json={
                    "model": model,
                    "messages": context.user_data["history"],
                    "options": ollama_utils.get_chat_options(model),
                    "stream": False,
                },
            )
            resp.raise_for_status()
            data = resp.json()
            reply = data["message"]["content"]
    except Exception as e:
        reply = f"Erro: {e}"

    context.user_data["history"].append({"role": "assistant", "content": reply})
    await thinking.delete()
    await send_with_code_blocks(update, reply)

def main():
    print("=" * 50)
    print("Bot do Ollama para Telegram")
    print("=" * 50)

    if not TOKEN or TOKEN == "SEU_TOKEN_AQUI":
        print("ERRO: Token do Telegram não configurado!")
        print()
        print("Defina a variável TELEGRAM_TOKEN ou edite a linha 7 em bot.py")
        print()
        print("Como criar um bot e obter o token:")
        print("1. Abra o Telegram e procure @BotFather")
        print("2. Envie /newbot e escolha um nome e username")
        print("3. Copie o token que ele enviar")
        print("4. Execute: TELEGRAM_TOKEN='seu_token' python3 bot.py")
        sys.exit(1)

    print(f"Token: {TOKEN[:8]}...{TOKEN[-4:]}")
    print(f"Ollama: {OLLAMA_BASE}")
    print(f"Modelo padrão: {MODEL}")
    print()
    print("Verificando conexão com Ollama...")

    try:
        r = httpx.get(f"{OLLAMA_BASE}/api/tags", timeout=5)
        r.raise_for_status()
        models = r.json().get("models", [])
        if models:
            print(f"Modelos disponíveis: {[m['name'] for m in models]}")
        else:
            print("Nenhum modelo encontrado. Baixe um com: ollama pull gemma4")

    except Exception as e:
        print(f"Aviso: não foi possível conectar ao Ollama: {e}")
        print("Certifique-se de que o Ollama está rodando.")

    print()
    print("Iniciando bot... (pressione Ctrl+C para parar)")
    print()

    app = Application.builder().token(TOKEN).build()
    app.add_handler(CommandHandler("start", start))
    app.add_handler(CommandHandler("menu", menu))
    app.add_handler(CommandHandler("resumo", resumo))
    app.add_handler(CommandHandler("cancel", cancel))
    app.add_handler(CommandHandler("clear", clear))
    app.add_handler(CommandHandler("model", set_model))
    app.add_handler(CommandHandler("codigo", codigo))
    app.add_handler(CommandHandler("sysmon", sysmon_cmd))
    app.add_handler(CallbackQueryHandler(menu_callback, pattern="^menu_"))
    app.add_handler(CallbackQueryHandler(drive_callback, pattern="^d_"))
    app.add_handler(CallbackQueryHandler(youtube_callback, pattern="^yt_"))
    app.add_handler(CallbackQueryHandler(trilha_callback, pattern="^tr_"))
    app.add_handler(CallbackQueryHandler(model_callback, pattern="^mdl_"))
    app.add_handler(CallbackQueryHandler(pc_apps_callback, pattern="^pc_"))
    app.add_handler(CallbackQueryHandler(security_callback, pattern="^sec_"))
    app.add_handler(CallbackQueryHandler(cleanup_callback, pattern="^cl_"))
    app.add_handler(MessageHandler(filters.Document.ALL, handle_document))
    app.add_handler(MessageHandler(filters.VOICE, handle_voice))
    app.add_handler(MessageHandler(filters.PHOTO, handle_photo))
    app.add_handler(MessageHandler(filters.TEXT & ~filters.COMMAND, handle_message))
    app.run_polling()

if __name__ == "__main__":
    main()
