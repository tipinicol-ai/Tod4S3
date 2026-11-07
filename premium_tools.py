"""Herramientas multimedia Premium aisladas del flujo normal de descargas.

Todas las operaciones se ejecutan con FFmpeg mediante argumentos separados,
sin shell, con nombres y rutas controlados. El bot puede usar este módulo sin
modificar la aplicación Android.
"""
from __future__ import annotations

import re
import shutil
import subprocess
from pathlib import Path
from typing import Iterable

VIDEO_EXTENSIONS = {".mp4", ".mkv", ".webm", ".mov", ".avi", ".m4v", ".flv", ".3gp"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp", ".tiff"}
MAX_BATCH_ITEMS = 20
MAX_TOOL_DURATION_SECONDS = 3600


def _safe_stem(name: str) -> str:
    stem = Path(name or "archivo").stem
    stem = re.sub(r"[^A-Za-z0-9._-]+", "_", stem).strip("._")
    return (stem or "archivo")[:80]


def _run_ffmpeg(args: list[str], timeout: int = 1800) -> None:
    try:
        subprocess.run(
            ["ffmpeg", "-hide_banner", "-loglevel", "error", *args],
            check=True,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=timeout,
        )
    except (OSError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        raise RuntimeError("FFmpeg no pudo completar la operación.") from exc


def _require_file(path: Path) -> Path:
    path = Path(path).resolve()
    if not path.is_file() or path.stat().st_size <= 0:
        raise ValueError("El archivo de entrada no existe o está vacío.")
    return path


def _output_dir(source: Path, label: str) -> Path:
    target = source.parent / f"premium-{label}-{_safe_stem(source.name)}"
    target.mkdir(parents=True, exist_ok=True)
    return target


def extract_embedded_subtitles(source: Path, filename: str | None = None) -> tuple[Path, str]:
    """Extrae la primera pista de subtítulos embebida a SRT."""
    source = _require_file(source)
    output = _output_dir(source, "subtitulos") / f"{_safe_stem(filename or source.name)}.srt"
    _run_ffmpeg(["-i", str(source), "-map", "0:s:0", "-c:s", "srt", "-y", str(output)])
    if not output.exists() or output.stat().st_size == 0:
        raise RuntimeError("El video no contiene una pista de subtítulos compatible.")
    return output, output.name


def cut_video(source: Path, filename: str, start_seconds: int, end_seconds: int) -> tuple[Path, str]:
    """Corta un video entre dos segundos, sin aceptar rangos ambiguos."""
    source = _require_file(source)
    if start_seconds < 0 or end_seconds <= start_seconds:
        raise ValueError("El rango de corte no es válido.")
    if end_seconds - start_seconds > MAX_TOOL_DURATION_SECONDS:
        raise ValueError("El corte no puede superar una hora.")
    output = _output_dir(source, "corte") / f"{_safe_stem(filename)}_cortado.mp4"
    _run_ffmpeg([
        "-ss", str(start_seconds), "-i", str(source), "-t", str(end_seconds - start_seconds),
        "-map", "0:v:0", "-map", "0:a?", "-c:v", "libx264", "-preset", "veryfast",
        "-c:a", "aac", "-movflags", "+faststart", "-y", str(output),
    ])
    if not output.exists() or output.stat().st_size == 0:
        raise RuntimeError("No se pudo crear el video cortado.")
    return output, output.name


def join_videos(sources: Iterable[Path], filenames: Iterable[str], output_dir: Path) -> tuple[Path, str]:
    """Une hasta 20 videos mediante concat demuxer con lista temporal segura."""
    paths = [_require_file(Path(item)) for item in sources]
    names = list(filenames)
    if len(paths) < 2:
        raise ValueError("Debes enviar al menos dos videos para unirlos.")
    if len(paths) > MAX_BATCH_ITEMS:
        raise ValueError("No puedes unir más de 20 videos en una operación.")
    if any(path.suffix.lower() not in VIDEO_EXTENSIONS for path in paths):
        raise ValueError("Todos los archivos deben ser videos compatibles.")
    target_dir = Path(output_dir).resolve()
    target_dir.mkdir(parents=True, exist_ok=True)
    list_file = target_dir / "concat-list.txt"
    output = target_dir / "videos_unidos.mp4"
    try:
        list_file.write_text("".join(f"file '{path.as_posix().replace(chr(39), chr(39) + chr(39))}'\n" for path in paths), encoding="utf-8")
        _run_ffmpeg(["-f", "concat", "-safe", "0", "-i", str(list_file), "-c", "copy", "-y", str(output)])
    finally:
        list_file.unlink(missing_ok=True)
    if not output.exists() or output.stat().st_size == 0:
        raise RuntimeError("No se pudieron unir los videos.")
    return output, output.name


def convert_images_batch(sources: Iterable[Path], filenames: Iterable[str], output_dir: Path) -> list[tuple[Path, str]]:
    """Convierte hasta 20 imágenes a JPG optimizado."""
    paths = [_require_file(Path(item)) for item in sources]
    names = list(filenames)
    if not paths or len(paths) > MAX_BATCH_ITEMS:
        raise ValueError("El lote debe contener entre 1 y 20 imágenes.")
    if any(path.suffix.lower() not in IMAGE_EXTENSIONS for path in paths):
        raise ValueError("Todos los archivos deben ser imágenes compatibles.")
    target_dir = Path(output_dir).resolve()
    target_dir.mkdir(parents=True, exist_ok=True)
    outputs: list[tuple[Path, str]] = []
    for index, path in enumerate(paths, 1):
        name = names[index - 1] if index <= len(names) else path.name
        output = target_dir / f"{index:02d}_{_safe_stem(name)}.jpg"
        _run_ffmpeg(["-i", str(path), "-frames:v", "1", "-q:v", "5", "-y", str(output)])
        if not output.exists() or output.stat().st_size == 0:
            raise RuntimeError("No se pudo convertir una de las imágenes.")
        outputs.append((output, output.name))
    return outputs


def cleanup_tool_directory(path: Path) -> None:
    """Elimina solo el directorio temporal creado para una herramienta."""
    resolved = Path(path).resolve()
    if resolved.name.startswith("premium-"):
        shutil.rmtree(resolved, ignore_errors=True)
