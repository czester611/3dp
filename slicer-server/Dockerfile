# U1 Slicer Server - szkielet (NIETESTOWANY).
# OrcaSlicer na Linuksa jest dystrybuowany jako AppImage (x86_64). Na Raspberry Pi (arm64)
# oficjalnego buildu może nie być - wtedy serwer uruchom na PC (zobacz docs/DESIGN.md).
FROM python:3.12-slim

ARG ORCA_URL="https://github.com/OrcaSlicer/OrcaSlicer/releases/download/vX.Y.Z/OrcaSlicer_Linux_AppImage_vX.Y.Z.AppImage"

RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb xauth libgl1 libegl1 libgtk-3-0 libwebkit2gtk-4.1-0 libgstreamer1.0-0 \
        libgstreamer-plugins-base1.0-0 ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*

# AppImage rozpakowany (brak FUSE w kontenerze). Ustaw ORCA_URL na konkretną wersję.
RUN curl -fsSL "$ORCA_URL" -o /tmp/orca.AppImage && chmod +x /tmp/orca.AppImage \
    && cd /opt && /tmp/orca.AppImage --appimage-extract && mv squashfs-root orca && rm /tmp/orca.AppImage

WORKDIR /srv
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .

ENV U1_ORCA_BIN=/opt/orca/AppRun \
    U1_PROFILE_DIR=/srv/profiles \
    U1_WORK_DIR=/srv/work \
    U1_USE_XVFB=1

EXPOSE 8484
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8484"]
