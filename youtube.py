import os
import re
import yt_dlp
import db

YOUTUBE_FOLDER_NAME = "YouTube"

YOUTUBE_RE = re.compile(
    r"(?:https?://)?(?:www\.)?"
    r"(?:youtube\.com/watch\?v=|youtu\.be/|youtube\.com/shorts/)"
    r"([\w-]{11})"
)

def is_youtube_url(text):
    return bool(YOUTUBE_RE.search(text))

def extract_url(text):
    m = YOUTUBE_RE.search(text)
    return m.group(0) if m else None

def get_youtube_folder_id():
    folders = db.listar_pastas()
    for f in folders:
        if f["name"] == YOUTUBE_FOLDER_NAME:
            return f["id"]
    return db.criar_pasta(YOUTUBE_FOLDER_NAME)

def download(url, fmt, output_dir):
    if fmt == "mp3":
        ydl_opts = {
            "format": "bestaudio/best",
            "outtmpl": os.path.join(output_dir, "%(title)s.%(ext)s"),
            "postprocessors": [{
                "key": "FFmpegExtractAudio",
                "preferredcodec": "mp3",
                "preferredquality": "192",
            }],
            "quiet": True,
            "no_warnings": True,
        }
    else:
        ydl_opts = {
            "format": "best[height<=1080][ext=mp4]/best[height<=1080]",
            "outtmpl": os.path.join(output_dir, "%(title)s.%(ext)s"),
            "quiet": True,
            "no_warnings": True,
        }

    with yt_dlp.YoutubeDL(ydl_opts) as ydl:
        info = ydl.extract_info(url, download=True)
        title = info.get("title", "video")
        ext = "mp3" if fmt == "mp3" else "mp4"

    for fname in os.listdir(output_dir):
        if fname.endswith(f".{ext}"):
            fpath = os.path.join(output_dir, fname)
            size = os.path.getsize(fpath)
            return fname, fpath, size, title

    return None, None, 0, title
