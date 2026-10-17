"""
U1 Slicer Server - minimalny serwis HTTP w LAN opakowujący OrcaSlicer CLI.

Kontrakt API (zgodny z pl.u1companion.slicing.SlicingApi w aplikacji Android):
  GET    /api/v1/health                -> {"status":"ok","version":..,"slicer_version":..}
  GET    /api/v1/profiles              -> {"printers":[..],"processes":[..],"filaments":[..]}
  POST   /api/v1/jobs   (multipart: model=<plik STL/3MF/OBJ/STEP>, config=<JSON SliceConfig>)
                                       -> JobDto
  GET    /api/v1/jobs/{id}             -> JobDto {id,status: queued|slicing|done|failed, progress, error, gcode_name,...}
  GET    /api/v1/jobs/{id}/gcode       -> plik G-code
  DELETE /api/v1/jobs/{id}

Autoryzacja: nagłówek "Authorization: Bearer <U1_SLICER_TOKEN>" (gdy zmienna ustawiona).

STATUS: szkielet, NIETESTOWANY z prawdziwym OrcaSlicer. Do weryfikacji:
  - dokładne flagi CLI dla wersji OrcaSlicer, którą zainstalujesz (`orca-slicer --help`),
  - nazwy plików wyjściowych (plate_1.gcode vs <nazwa>.gcode),
  - czy CLI wymaga Xvfb (xvfb-run) w danym buildzie.
"""
from __future__ import annotations

import asyncio
import json
import os
import shutil
import subprocess
import uuid
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from pydantic import BaseModel, Field

ORCA_BIN = os.environ.get("U1_ORCA_BIN", "orca-slicer")
USE_XVFB = os.environ.get("U1_USE_XVFB", "1") == "1"
PROFILE_DIR = Path(os.environ.get("U1_PROFILE_DIR", "./profiles"))  # profile JSON: machine/, process/, filament/ (patrz docs/DESIGN.md §4.1)
WORK_DIR = Path(os.environ.get("U1_WORK_DIR", "./work"))
TOKEN = os.environ.get("U1_SLICER_TOKEN", "")
MAX_UPLOAD_MB = int(os.environ.get("U1_MAX_UPLOAD_MB", "200"))
TIMEOUT_SEC = int(os.environ.get("U1_SLICE_TIMEOUT", "900"))
ALLOWED_EXT = {".stl", ".3mf", ".obj", ".step", ".stp"}

app = FastAPI(title="U1 Slicer Server", version="0.1.0")
WORK_DIR.mkdir(parents=True, exist_ok=True)


class SliceConfig(BaseModel):
    printer_profile: str = "Snapmaker U1 (0.4 nozzle)"
    process_profile: Optional[str] = None
    filament_profiles: list[str] = Field(default_factory=list)
    overrides: dict[str, str] = Field(default_factory=dict)
    plate: int = 1
    reset_project_settings: bool = True
    arrange: bool = True


@dataclass
class Job:
    id: str
    dir: Path
    status: str = "queued"
    progress: float = 0.0
    error: Optional[str] = None
    gcode: Optional[Path] = None
    log: list[str] = field(default_factory=list)

    def dto(self) -> dict:
        return {
            "id": self.id,
            "status": self.status,
            "progress": self.progress,
            "error": self.error,
            "gcode_name": self.gcode.name if self.gcode else None,
        }


JOBS: dict[str, Job] = {}
SLICE_LOCK = asyncio.Semaphore(1)  # jeden slicing naraz (RPi/PC domowy)


def auth(authorization: str = Header(default="")) -> None:
    if TOKEN and authorization != f"Bearer {TOKEN}":
        raise HTTPException(status_code=401, detail="unauthorized")


def orca_version() -> Optional[str]:
    try:
        out = subprocess.run([ORCA_BIN, "--help"], capture_output=True, text=True, timeout=30)
        first = (out.stdout or out.stderr).splitlines()[:1]
        return first[0].strip() if first else None
    except Exception:  # noqa: BLE001
        return None


# --- Profile ---------------------------------------------------------------------------

def _profiles(kind: str) -> list[str]:
    d = PROFILE_DIR / kind
    return sorted(p.stem for p in d.glob("*.json")) if d.exists() else []


def resolve_profile(kind: str, name: str) -> dict:
    """Ładuje profil i rozwiązuje łańcuch 'inherits' (profile systemowe Orca dziedziczą)."""
    path = PROFILE_DIR / kind / f"{name}.json"
    if not path.exists():
        raise HTTPException(400, f"Brak profilu {kind}/{name}")
    data = json.loads(path.read_text(encoding="utf-8"))
    parent = data.get("inherits")
    if parent:
        base = resolve_profile(kind, parent)
        base.update({k: v for k, v in data.items() if k != "inherits"})
        data = base
    data.pop("inherits", None)
    return data


def write_profile(job: Job, kind: str, name: str, overrides: dict[str, str]) -> Path:
    data = resolve_profile(kind, name)
    for k, v in overrides.items():
        # Orca trzyma wartości wieloelementowe jako listy stringów (np. nozzle_temperature per filament)
        if isinstance(data.get(k), list) and kind != "filament":
            data[k] = [v]
        else:
            data[k] = v
    out = job.dir / f"{kind}_{uuid.uuid4().hex[:6]}.json"
    out.write_text(json.dumps(data, indent=1), encoding="utf-8")
    return out


# --- Slicing ---------------------------------------------------------------------------

PROCESS_KEYS = {"layer_height", "sparse_infill_density", "enable_support", "support_type", "brim_type"}
FILAMENT_KEYS = {"nozzle_temperature", "hot_plate_temp"}


async def run_slice(job: Job, model: Path, cfg: SliceConfig) -> None:
    async with SLICE_LOCK:
        job.status = "slicing"
        try:
            proc_over = {k: v for k, v in cfg.overrides.items() if k in PROCESS_KEYS}
            machine = write_profile(job, "machine", cfg.printer_profile, {})
            process = write_profile(job, "process", cfg.process_profile or default_process(cfg), proc_over)
            temps = cfg.overrides.get("nozzle_temperature", "").split(",")
            filaments = []
            for i, f in enumerate(cfg.filament_profiles or ["Snapmaker PLA"]):
                fo = {}
                if i < len(temps) and temps[i].strip():
                    fo["nozzle_temperature"] = [temps[i].strip()]
                    fo["nozzle_temperature_initial_layer"] = [temps[i].strip()]
                if "hot_plate_temp" in cfg.overrides:
                    fo["hot_plate_temp"] = [cfg.overrides["hot_plate_temp"]]
                    fo["hot_plate_temp_initial_layer"] = [cfg.overrides["hot_plate_temp"]]
                filaments.append(write_profile(job, "filament", f, fo))

            out_dir = job.dir / "out"
            out_dir.mkdir(exist_ok=True)
            cmd = [
                ORCA_BIN,
                "--slice", str(cfg.plate),
                "--load-settings", f"{machine};{process}",
                "--load-filaments", ";".join(str(f) for f in filaments),
                "--outputdir", str(out_dir),
            ]
            if cfg.arrange:
                cmd += ["--arrange", "1"]
            # TODO: dla 3MF z MakerWorld (profil Bambu) zweryfikować, czy --load-settings nadpisuje ustawienia projektu
            cmd.append(str(model))
            if USE_XVFB and shutil.which("xvfb-run"):
                cmd = ["xvfb-run", "-a"] + cmd

            p = await asyncio.create_subprocess_exec(*cmd, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.STDOUT)
            try:
                out, _ = await asyncio.wait_for(p.communicate(), timeout=TIMEOUT_SEC)
            except asyncio.TimeoutError:
                p.kill()
                raise RuntimeError("Przekroczono limit czasu slicingu")
            job.log = out.decode(errors="replace").splitlines()[-200:]
            gcodes = sorted(out_dir.rglob("*.gcode"))
            if p.returncode != 0 or not gcodes:
                raise RuntimeError(f"OrcaSlicer zakończył się kodem {p.returncode}: " + " | ".join(job.log[-5:]))
            final = job.dir / f"{model.stem}_U1.gcode"
            gcodes[0].rename(final)
            job.gcode = final
            job.progress = 1.0
            job.status = "done"
        except HTTPException as e:
            job.status, job.error = "failed", str(e.detail)
        except Exception as e:  # noqa: BLE001
            job.status, job.error = "failed", str(e)


def default_process(cfg: SliceConfig) -> str:
    procs = _profiles("process")
    lh = cfg.overrides.get("layer_height", "0.2")
    for p in procs:
        if p.startswith(f"{float(lh):.2f}mm") and "U1" in p:
            return p
    if not procs:
        raise HTTPException(500, "Brak profili process w U1_PROFILE_DIR")
    return procs[0]


# --- Endpointy -------------------------------------------------------------------------

@app.get("/api/v1/health", dependencies=[Depends(auth)])
def health():
    return {"status": "ok", "version": app.version, "slicer_version": orca_version()}


@app.get("/api/v1/profiles", dependencies=[Depends(auth)])
def profiles():
    return {"printers": _profiles("machine"), "processes": _profiles("process"), "filaments": _profiles("filament")}


@app.post("/api/v1/jobs", dependencies=[Depends(auth)])
async def create_job(model: UploadFile = File(...), config: str = Form(...)):
    ext = Path(model.filename or "").suffix.lower()
    if ext not in ALLOWED_EXT:
        raise HTTPException(400, f"Nieobsługiwany typ pliku {ext}")
    cfg = SliceConfig.model_validate_json(config)
    job_id = uuid.uuid4().hex[:12]
    jdir = WORK_DIR / job_id
    jdir.mkdir(parents=True)
    safe = "model" + ext
    dest = jdir / safe
    size = 0
    with dest.open("wb") as f:
        while chunk := await model.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_UPLOAD_MB * 1024 * 1024:
                shutil.rmtree(jdir, ignore_errors=True)
                raise HTTPException(413, "Plik za duży")
            f.write(chunk)
    job = Job(id=job_id, dir=jdir)
    JOBS[job_id] = job
    asyncio.create_task(run_slice(job, dest, cfg))
    return job.dto()


@app.get("/api/v1/jobs/{job_id}", dependencies=[Depends(auth)])
def get_job(job_id: str):
    job = JOBS.get(job_id) or _404()
    return job.dto()


@app.get("/api/v1/jobs/{job_id}/gcode", dependencies=[Depends(auth)])
def get_gcode(job_id: str):
    job = JOBS.get(job_id) or _404()
    if job.status != "done" or not job.gcode:
        raise HTTPException(409, "G-code jeszcze niegotowy")
    return FileResponse(job.gcode, media_type="text/x-gcode", filename=job.gcode.name)


@app.delete("/api/v1/jobs/{job_id}", dependencies=[Depends(auth)])
def delete_job(job_id: str):
    job = JOBS.pop(job_id, None) or _404()
    shutil.rmtree(job.dir, ignore_errors=True)
    return {"deleted": job_id}


def _404():
    raise HTTPException(404, "Nie ma takiego zlecenia")
