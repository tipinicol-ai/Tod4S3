from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.parse import unquote, urlsplit

import pytest

import todus_s3_storage as storage


S3_BASE = "https://s3.todus.cu/stream"


class FakeSession:
    def __init__(self, head_status=404):
        self.head_status = head_status
        self.head_urls = []
        self.put_url = None
        self.put_headers = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def head(self, url, **kwargs):
        self.head_urls.append(url)
        return SimpleNamespace(status_code=self.head_status)

    def put(self, url, **kwargs):
        self.put_url = url
        self.put_headers = kwargs.get("headers")
        return SimpleNamespace(status_code=200)


def test_custom_object_key_uses_user_folder_and_original_unicode_filename():
    filename = "¡LO PROBAMOS Y SÍ PAGA! 😱 ｜ Source Hunter (Juego NFT).mp4"
    key = storage.build_object_key(
        5721542653,
        filename=filename,
        object_prefix="users/df",
    )

    assert key == f"users/df/5721542653/{filename}"


def test_default_object_key_keeps_legacy_namespace():
    key = storage.build_object_key(73421, filename="archivo de prueba.zip")

    assert key.startswith("users/tg_73421/telegram/")
    assert key.endswith("_archivo_de_prueba.zip")


def test_build_object_key_rejects_path_traversal_in_custom_prefix():
    with pytest.raises(ValueError):
        storage.build_object_key(73421, filename="archivo.zip", object_prefix="users/../other")


def test_upload_uses_users_df_user_id_and_original_name(tmp_path: Path):
    filename = "¡LO PROBAMOS Y SÍ PAGA! 😱 ｜ Source Hunter (Juego NFT Gratis Móvil y PC) [2bMdfR7vilk].mp4"
    local_file = tmp_path / "video.mp4"
    local_file.write_bytes(b"datos de prueba")
    fake_session = FakeSession(head_status=404)

    with patch.object(storage.requests, "Session", return_value=fake_session):
        url, size = storage.upload_to_todus_s3(
            local_file,
            filename,
            5721542653,
            object_prefix="users/df",
        )

    assert url == fake_session.put_url
    assert url == (
        "https://s3.todus.cu/stream/users/df/5721542653/"
        "%C2%A1LO%20PROBAMOS%20Y%20S%C3%8D%20PAGA%21%20%F0%9F%98%B1%20"
        "%EF%BD%9C%20Source%20Hunter%20%28Juego%20NFT%20Gratis%20M%C3%B3vil%20y%20PC%29%20"
        "%5B2bMdfR7vilk%5D.mp4"
    )
    assert unquote(urlsplit(url).path) == f"/stream/users/df/5721542653/{filename}"
    assert fake_session.head_urls == [url]
    assert size == len(b"datos de prueba")


def test_upload_adds_unique_suffix_in_user_folder_when_name_exists(tmp_path: Path):
    local_file = tmp_path / "archivo.bin"
    local_file.write_bytes(b"datos")
    fake_session = FakeSession(head_status=200)

    with patch.object(storage.requests, "Session", return_value=fake_session):
        url, _ = storage.upload_to_todus_s3(
            local_file,
            "archivo.bin",
            73421,
            object_prefix="users/df",
        )

    decoded_path = unquote(urlsplit(url).path)
    assert decoded_path.startswith("/stream/users/df/73421/archivo_")
    assert decoded_path.endswith(".bin")
    assert decoded_path != "/stream/users/df/73421/archivo.bin"
