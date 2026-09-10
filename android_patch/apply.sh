#!/usr/bin/env bash
# Aplica no app Android: leitor de QR Code, botão de desparear e transporte Iroh resiliente.
# Uso: bash android_patch/apply.sh [caminho-do-projeto-android]
set -euo pipefail

DEST="${1:-/home/aridev/AndroidStudioProjects/DoGCyberAgent}"
SRC="$(cd "$(dirname "$0")" && pwd)/files"

if [ ! -d "$DEST/app/src/main" ]; then
  echo "Projeto Android não encontrado em: $DEST" >&2
  exit 1
fi
if [ ! -d "$SRC" ]; then
  echo "Pasta de arquivos não encontrada: $SRC" >&2
  exit 1
fi

STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP="$DEST/.backup-cyberbot-$STAMP"
mkdir -p "$BACKUP"

cd "$SRC"
while IFS= read -r rel; do
  mkdir -p "$DEST/$(dirname "$rel")"
  if [ -f "$DEST/$rel" ]; then
    mkdir -p "$BACKUP/$(dirname "$rel")"
    cp -p "$DEST/$rel" "$BACKUP/$rel"
    echo "substituído: $rel"
  else
    echo "novo       : $rel"
  fi
  cp "$SRC/$rel" "$DEST/$rel"
done < <(find . -type f -printf '%P\n' | sort)

echo
echo "Backup dos originais: $BACKUP"
echo "Compile com: cd '$DEST' && JAVA_HOME=/home/aridev/programs/android-studio/jbr ./gradlew assembleDebug"
