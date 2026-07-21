from plugin_loader import Plugin
import os
import sys
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))
import db
import httpx
import tempfile
import zipfile
import io
import wave

VOICES_URL = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0/pt/pt_BR/faber/medium/"
VOICE_FILES = {
    "pt_BR-faber-medium.onnx": "pt_BR-faber-medium.onnx",
    "pt_BR-faber-medium.onnx.json": "pt_BR-faber-medium.onnx.json",
}
VOICE_DIR = os.path.expanduser("~/.local/share/piper-tts-voices/pt_BR-faber-medium")
DRIVE_DIR = os.path.join(os.path.dirname(os.path.dirname(__file__)), "drive_files", "leituras")

def limpar_texto(texto):
    import re
    texto = re.sub(r'\*{1,2}(.*?)\*{1,2}', r'\1', texto)
    texto = re.sub(r'`{1,3}(.*?)`{1,3}', r'\1', texto)
    texto = re.sub(r'_{1,2}(.*?)_{1,2}', r'\1', texto)
    texto = re.sub(r'~~(.*?)~~', r'\1', texto)
    texto = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', texto)
    texto = re.sub(r'#{1,6}\s+', '', texto)
    texto = re.sub(r'>>>?\s*', '', texto)
    texto = re.sub(r'[-*+]\s+', '', texto)
    texto = re.sub(r'\n{2,}', '\n', texto)
    return texto.strip()

class LeiaPlugin(Plugin):
    name = "leia"
    description = "Lê textos em voz alta usando TTS local (Piper) e salva no Drive"
    commands = [("/leia", "cmd_leia")]

    def ensure_voice(self):
        if not os.path.isdir(VOICE_DIR):
            os.makedirs(VOICE_DIR, exist_ok=True)
            for fname, _ in VOICE_FILES.items():
                url = VOICES_URL + fname
                dest = os.path.join(VOICE_DIR, fname)
                if not os.path.exists(dest):
                    try:
                        r = httpx.get(url, timeout=120, follow_redirects=True)
                        with open(dest, "wb") as f:
                            f.write(r.content)
                    except Exception as e:
                        return f"Erro ao baixar voz: {e}"
        onnx_path = os.path.join(VOICE_DIR, "pt_BR-faber-medium.onnx")
        config_path = os.path.join(VOICE_DIR, "pt_BR-faber-medium.onnx.json")
        if os.path.exists(onnx_path) and os.path.exists(config_path):
            return onnx_path
        return "Voz não encontrada"

    def text_to_wav(self, text, onnx_path):
        from piper import PiperVoice
        text = limpar_texto(text)
        if not text:
            text = "Não entendi."
        voice = PiperVoice.load(onnx_path)
        audio_stream = io.BytesIO()
        with wave.open(audio_stream, "wb") as wav:
            wav.setnchannels(1)
            wav.setsampwidth(2)
            wav.setframerate(voice.config.sample_rate or 22050)
            for audio_chunk in voice.synthesize(text[:800].strip()):
                wav.writeframes(audio_chunk.audio_int16_bytes)
        audio_stream.seek(0)
        return audio_stream

    async def cmd_leia(self, update, context):
        text = " ".join(context.args) if context.args else ""
        if not text:
            await update.message.reply_text(
                "🗣️ *Leia*\n\nUse: `/leia texto para ler`\n\n"
                "O bot converte o texto em áudio e envia como mensagem de voz.",
                parse_mode="Markdown",
            )
            return

        folders = db.listar_pastas()
        leituras_id = None
        for f in folders:
            if f["name"] == "leituras":
                leituras_id = f["id"]
                break
        if not leituras_id:
            leituras_id = db.criar_pasta("leituras")

        msg = await update.message.reply_text("🗣️ *Gerando áudio...*", parse_mode="Markdown")

        await msg.edit_text("⬇️ *Baixando modelo de voz...*" if not os.path.isdir(VOICE_DIR) else "🧠 *Processando texto...*", parse_mode="Markdown")

        onnx_path = self.ensure_voice()
        if not onnx_path or "Erro" in str(onnx_path) or "não encontrada" in str(onnx_path):
            await msg.edit_text(f"❌ {onnx_path}")
            return

        try:
            wav_data = self.text_to_wav(text, onnx_path)
        except Exception as e:
            await msg.edit_text(f"❌ Erro ao gerar áudio: {e}")
            return

        os.makedirs(DRIVE_DIR, exist_ok=True)
        fname = f"leia_{abs(hash(text[:30]))}.wav"
        filepath = os.path.join(DRIVE_DIR, fname)
        with open(filepath, "wb") as f:
            f.write(wav_data.getvalue())

        db.inserir_arquivo(fname, leituras_id, filepath, len(wav_data.getvalue()), "audio/wav", caption=text[:100])

        await msg.delete()

        with open(filepath, "rb") as f:
            await update.message.reply_voice(f)

        await update.message.reply_text(
            f"✅ Salvo em Drive > leituras > `{fname}`",
            parse_mode="Markdown",
        )
