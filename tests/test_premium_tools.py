from pathlib import Path

import pytest

import premium_tools


def _fake_ffmpeg(args, timeout=1800):
    output = Path(args[-1])
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_bytes(b"processed")


def test_cut_video_rejects_invalid_ranges(tmp_path):
    source = tmp_path / "clip.mp4"
    source.write_bytes(b"video")
    with pytest.raises(ValueError):
        premium_tools.cut_video(source, source.name, 10, 10)
    with pytest.raises(ValueError):
        premium_tools.cut_video(source, source.name, 0, premium_tools.MAX_TOOL_DURATION_SECONDS + 1)


def test_cut_video_uses_safe_output_and_ffmpeg(monkeypatch, tmp_path):
    source = tmp_path / "mi video (original).mp4"
    source.write_bytes(b"video")
    monkeypatch.setattr(premium_tools, "_run_ffmpeg", _fake_ffmpeg)
    output, name = premium_tools.cut_video(source, source.name, 0, 5)
    assert output.exists()
    assert name.endswith("_cortado.mp4")
    assert " " not in name


def test_batch_images_limits_and_converts(monkeypatch, tmp_path):
    source = tmp_path / "foto 1.png"
    source.write_bytes(b"image")
    monkeypatch.setattr(premium_tools, "_run_ffmpeg", _fake_ffmpeg)
    output = premium_tools.convert_images_batch([source], [source.name], tmp_path / "result")
    assert len(output) == 1
    assert output[0][0].suffix == ".jpg"
    with pytest.raises(ValueError):
        premium_tools.convert_images_batch([tmp_path / "missing.png"], ["missing.png"], tmp_path / "result")


def test_join_requires_two_compatible_videos(monkeypatch, tmp_path):
    first = tmp_path / "one.mp4"
    second = tmp_path / "two.mp4"
    first.write_bytes(b"one")
    second.write_bytes(b"two")
    monkeypatch.setattr(premium_tools, "_run_ffmpeg", _fake_ffmpeg)
    output, name = premium_tools.join_videos([first, second], [first.name, second.name], tmp_path / "joined")
    assert output.exists()
    assert name == "videos_unidos.mp4"
    with pytest.raises(ValueError):
        premium_tools.join_videos([first], [first.name], tmp_path / "joined")


def test_extract_subtitles_reports_missing_input(tmp_path):
    with pytest.raises(ValueError):
        premium_tools.extract_embedded_subtitles(tmp_path / "missing.mp4")
