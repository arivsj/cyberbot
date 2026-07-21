from plugin_loader import Plugin
import httpx
import re
import os
import json
import tempfile

TEMP_DIR = os.path.join(tempfile.gettempdir(), "cyberbot_book")

class BookPlugin(Plugin):
    name = "book"
    description = "Busca livros na web usando IA local para analisar e ranquear resultados"
    commands = [("/book", "cmd_book")]

    async def search_duckduckgo(self, query):
        results = []
        for q in [query, f"{query} pdf", f"{query} download livro"]:
            url = "https://html.duckduckgo.com/html/"
            data = {"q": q}
            async with httpx.AsyncClient(timeout=12, follow_redirects=True) as c:
                try:
                    r = await c.post(url, data=data, headers={"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"})
                    r.raise_for_status()
                except:
                    continue
            links = re.findall(r'class="result__a"[^>]*href="([^"]+)"', r.text)
            snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</(?:a|span|div)', r.text, re.DOTALL)
            for i, href in enumerate(links):
                snippet = ""
                if i < len(snippets):
                    snippet = re.sub(r'<[^>]+>', '', snippets[i]).strip()[:150]
                results.append({"url": href, "snippet": snippet, "source": "duckduckgo"})
        seen = set()
        unique = []
        for r in results:
            if r["url"] not in seen:
                seen.add(r["url"])
                unique.append(r)
        return unique[:20]

    async def fetch_and_save(self, url, index):
        os.makedirs(TEMP_DIR, exist_ok=True)
        path = os.path.join(TEMP_DIR, f"result_{index}.json")
        data = {"url": url, "status": "pending", "content": "", "content_type": "", "error": ""}
        async with httpx.AsyncClient(timeout=10, follow_redirects=True) as c:
            try:
                r = await c.head(url, headers={"User-Agent": "Mozilla/5.0"})
                ct = r.headers.get("content-type", "")
                data["content_type"] = ct
                if "pdf" in ct:
                    data["status"] = "pdf"
                    data["content"] = f"[PDF] {url}"
                else:
                    gr = await c.get(url, headers={"User-Agent": "Mozilla/5.0"}, timeout=10)
                    text = re.sub(r'<script[^>]*>.*?</script>', '', gr.text, flags=re.DOTALL)
                    text = re.sub(r'<style[^>]*>.*?</style>', '', text, flags=re.DOTALL)
                    text = re.sub(r'<[^>]+>', ' ', text)
                    text = re.sub(r'\s+', ' ', text).strip()
                    data["status"] = "html"
                    data["content"] = text[:2000]
            except Exception as e:
                data["error"] = str(e)[:100]
                data["status"] = "error"
        with open(path, "w") as f:
            json.dump(data, f)
        return data

    def load_saved(self, index):
        path = os.path.join(TEMP_DIR, f"result_{index}.json")
        if os.path.exists(path):
            with open(path) as f:
                return json.load(f)
        return None

    def analyze_with_ollama(self, query, results_data):
        prompt = (
            "Você é um bibliotecário digital analisando resultados de busca por livros. "
            "Sua tarefa: analisar cada URL e determinar se CONTÉM ou LEVA a um livro real (PDF, ePUB, ou página de download). "
            "Ignore resultados que são apenas menções, resenhas, ou sites genéricos.\n\n"
            f"Busca original: {query}\n\n"
            "Resultados:\n"
        )
        for i, r in enumerate(results_data):
            prompt += f"\n[{i+1}] URL: {r['url']}\n"
            prompt += f"    Tipo: {r.get('content_type', r.get('status', ''))}\n"
            if r.get("snippet"):
                prompt += f"    Snippet: {r['snippet']}\n"
            if r.get("content"):
                prompt += f"    Conteúdo: {r['content'][:300]}\n"
            if r.get("error"):
                prompt += f"    Erro: {r['error']}\n"

        prompt += (
            "\n\nPara cada resultado, responda APENAS no formato JSON abaixo, sem explicações:\n"
            '{"resultados": [{"indice": 1, "relevante": true/false, "titulo": "nome do livro", "descricao": "descrição curta"}]}\n\n'
            "Se um resultado parece ser um livro PDF, ePUB, ou página de download — marque como relevante."
        )
        try:
            import httpx as hx
            r = hx.post(
                "http://localhost:11434/api/chat",
                json={
                    "model": "gemma4",
                    "messages": [{"role": "user", "content": prompt}],
                    "options": {"num_ctx": 8192},
                    "stream": False,
                },
                timeout=60,
            )
            if r.status_code == 200:
                content = r.json().get("message", {}).get("content", "")
                content = content.strip().removeprefix("```json").removeprefix("```").removesuffix("```").strip()
                return json.loads(content)
        except Exception as e:
            return {"resultados": []}
        return {"resultados": []}

    async def cmd_book(self, update, context):
        import shutil
        query = " ".join(context.args) if context.args else ""
        if not query:
            await update.message.reply_text(
                "📖 *Book Search*\n\nUse: `/book nome do livro`\n\n"
                "O agente IA busca em múltiplas fontes, analisa os resultados e retorna os melhores.",
                parse_mode="Markdown",
            )
            return

        if os.path.exists(TEMP_DIR):
            shutil.rmtree(TEMP_DIR)
        os.makedirs(TEMP_DIR, exist_ok=True)

        msg = await update.message.reply_text(f"⏳ *Fase 1/3:* Buscando {query} na web...", parse_mode="Markdown")

        results = await self.search_duckduckgo(query)

        if not results:
            await msg.edit_text("😕 Nenhum resultado encontrado.", parse_mode="Markdown")
            return

        await msg.edit_text(f"⏳ *Fase 2/3:* Analisando {len(results)} sites...\n📥 Baixando conteúdo para análise...", parse_mode="Markdown")

        fetched = []
        for i, r in enumerate(results):
            saved = self.load_saved(i)
            if saved:
                fetched.append(saved)
            else:
                data = await self.fetch_and_save(r["url"], i)
                data["snippet"] = r.get("snippet", "")
                fetched.append(data)

        await msg.edit_text(f"⏳ *Fase 3/3:* IA analisando {len(fetched)} resultados...\n🧠 Aplicando filtro inteligente...", parse_mode="Markdown")

        analysis = self.analyze_with_ollama(query, fetched)

        relevant = [r for r in analysis.get("resultados", []) if r.get("relevante")]

        if not relevant:
            relevant = [{"indice": i, "relevante": True, "titulo": "Resultado", "descricao": ""} for i in range(min(8, len(fetched)))]

        lines = [f"📖 *Busca: {query}*"]
        lines.append(f"🤖 IA analisou {len(fetched)} sites, {len(relevant)} relevantes\n")

        shown = 0
        for item in relevant[:5]:
            idx = item.get("indice", 1) - 1
            if idx < 0 or idx >= len(fetched):
                continue
            r = fetched[idx]
            titulo = item.get("titulo", "Livro")[:60]
            desc = item.get("descricao", r.get("snippet", ""))[:120]
            status_icon = "📄" if "pdf" in r.get("content_type", "").lower() else "🔗"
            lines.append(f"{status_icon} *{titulo}*")
            if desc:
                lines.append(f"   {desc}")
            lines.append(f"   `{r['url'][:80]}`")
            lines.append("")
            shown += 1

        if shown == 0:
            lines.append("😕 Nenhum resultado relevante encontrado.")

        shutil.rmtree(TEMP_DIR, ignore_errors=True)
        text = "\n".join(lines)
        await msg.edit_text(text, parse_mode="Markdown", disable_web_page_preview=True)
