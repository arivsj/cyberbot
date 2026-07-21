from plugin_loader import Plugin

class ExemploPlugin(Plugin):
    name = "exemplo"
    description = "Plugin de exemplo — responde com echo"

    commands = [
        ("/echo", "cmd_echo"),
    ]

    async def cmd_echo(self, update, context):
        text = " ".join(context.args) if context.args else "olá"
        await update.message.reply_text(f"📢 Echo: {text}")
