from plugin_loader import Plugin
import httpx

class CepPlugin(Plugin):
    name = "cep"
    description = "Consulta endereço por CEP usando ViaCEP"
    commands = [("/cep", "cmd_cep")]

    async def cmd_cep(self, update, context):
        if not context.args:
            await update.message.reply_text("Use: `/cep 01310-100`", parse_mode="Markdown")
            return
        cep = context.args[0].replace("-", "")
        if len(cep) != 8 or not cep.isdigit():
            await update.message.reply_text("CEP inválido. Formato: 01310-100")
            return
        async with httpx.AsyncClient(timeout=10) as client:
            r = await client.get(f"https://viacep.com.br/ws/{cep}/json/")
        if r.status_code != 200 or r.json().get("erro"):
            await update.message.reply_text("❌ CEP não encontrado.")
            return
        d = r.json()
        await update.message.reply_text(
            f"📍 {d.get('logradouro', '')}\n"
            f"🏘️ {d.get('bairro', '')}\n"
            f"🏙️ {d.get('localidade', '')} — {d.get('uf', '')}\n"
            f"📬 {d['cep']}",
            parse_mode="Markdown",
        )
