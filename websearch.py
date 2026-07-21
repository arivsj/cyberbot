import httpx
import re

USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

async def search_duckduckgo(query, max_results=5):
    try:
        url = "https://html.duckduckgo.com/html/"
        data = {"q": query}
        async with httpx.AsyncClient(timeout=10, follow_redirects=True) as client:
            r = await client.post(url, data=data, headers={"User-Agent": USER_AGENT})
            r.raise_for_status()
            links = re.findall(r'class="result__a"[^>]*href="([^"]+)"', r.text)
            snippets = re.findall(r'class="result__snippet"[^>]*>(.*?)</(?:a|span|div)', r.text, re.DOTALL)
            results = []
            for i, href in enumerate(links[:max_results]):
                clean_url = href.split("://")[-1] if "://" in href else href
                clean_snippet = ""
                if i < len(snippets):
                    clean_snippet = re.sub(r'<[^>]+>', '', snippets[i]).strip()
                results.append({"url": href, "snippet": clean_snippet})
            return results
    except Exception as e:
        return [{"url": "", "snippet": f"Erro na busca: {e}"}]

async def fetch_page_text(url):
    try:
        async with httpx.AsyncClient(timeout=15, follow_redirects=True) as client:
            r = await client.get(url, headers={"User-Agent": USER_AGENT})
            r.raise_for_status()
            text = re.sub(r'<script[^>]*>.*?</script>', '', r.text, flags=re.DOTALL)
            text = re.sub(r'<style[^>]*>.*?</style>', '', text, flags=re.DOTALL)
            text = re.sub(r'<[^>]+>', ' ', text)
            text = re.sub(r'\s+', ' ', text).strip()
            lines = [l.strip() for l in text.split("\n") if l.strip()]
            return " ".join(lines[:200])
    except Exception as e:
        return f""

async def search_and_prepare(query, max_results=3):
    results = await search_duckduckgo(query, max_results)
    context_parts = []
    sources = []
    for i, r in enumerate(results):
        url = r["url"]
        if not url:
            continue
        sources.append(url)
        body = await fetch_page_text(url)
        if body:
            context_parts.append(f"[Fonte {i+1}: {url}]\n{body[:1500]}")
        elif r["snippet"]:
            context_parts.append(f"[Fonte {i+1}: {url}]\n{r['snippet']}")
    return "\n\n".join(context_parts), sources
