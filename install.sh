#!/data/data/com.termux/files/usr/bin/bash
# Khmer Drama Dubber - installer for Termux (Android)
# Run inside Termux:   bash install.sh
set -e

HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$HOME/khmer-dubber"
WORK="/sdcard/KhmerDubber"

if [ ! -f "$HERE/khmer_dubber.py" ]; then
    echo "khmer_dubber.py must be in the same folder as install.sh"
    exit 1
fi

echo "=========================================="
echo "   Khmer Drama Dubber - installing ..."
echo "=========================================="

echo
echo "[1/5] Updating Termux and installing Python + ffmpeg"
echo "      (first time can take 5-10 minutes)"
yes | pkg upgrade -y
pkg install -y python ffmpeg

echo
echo "[2/5] Installing edge-tts"
# build the pure-Python versions of aiohttp & friends (no compiler needed)
export AIOHTTP_NO_EXTENSIONS=1 MULTIDICT_NO_EXTENSIONS=1 YARL_NO_EXTENSIONS=1 \
       FROZENLIST_NO_EXTENSIONS=1 PROPCACHE_NO_EXTENSIONS=1
if ! pip install --upgrade edge-tts; then
    echo "      retrying with a compiler ..."
    pkg install -y clang
    pip install --upgrade edge-tts
fi

echo
echo "[3/5] Storage permission"
if ! touch /sdcard/.khmerdub_test 2>/dev/null; then
    echo "      Tap ALLOW on the permission pop-up."
    termux-setup-storage
    for _ in $(seq 1 60); do
        touch /sdcard/.khmerdub_test 2>/dev/null && break
        sleep 1
    done
fi
rm -f /sdcard/.khmerdub_test
if [ ! -w /sdcard ]; then
    echo "      Storage permission not given. Run 'termux-setup-storage', allow it,"
    echo "      then run this installer again."
    exit 1
fi

echo
echo "[4/5] Installing the dubber"
mkdir -p "$APP"
if [ "$HERE" != "$APP" ]; then
    cp "$HERE/khmer_dubber.py" "$APP/"
fi
cat > "$PREFIX/bin/khmerdub" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
exec python "$APP/khmer_dubber.py" "\$@"
EOF
chmod +x "$PREFIX/bin/khmerdub"

# one-tap home screen button (needs the optional Termux:Widget app)
mkdir -p "$HOME/.shortcuts"
cat > "$HOME/.shortcuts/Khmer Dub" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
"$PREFIX/bin/khmerdub"
echo
read -r -p "Press Enter to close"
EOF
chmod +x "$HOME/.shortcuts/Khmer Dub"

echo
echo "[5/5] Creating folders"
mkdir -p "$WORK/input" "$WORK/output"
python "$APP/khmer_dubber.py" > /dev/null   # writes default config.json

echo
echo "=========================================="
echo "  DONE!"
echo
echo "  1. Put video + .srt (same name) in:"
echo "       Internal storage/KhmerDubber/input"
echo "  2. In Termux type:   khmerdub"
echo "  3. Dubbed videos appear in:"
echo "       Internal storage/KhmerDubber/output"
echo
echo "  Settings: Internal storage/KhmerDubber/config.json"
echo "=========================================="
