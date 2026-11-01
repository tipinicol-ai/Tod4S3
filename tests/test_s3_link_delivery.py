import asyncio
from types import SimpleNamespace

from bot import result_keyboard, send_task_links, transfer_complete_text


SAMPLE_URL = (
    "https://s3.todus.cu/stream/users/df/5721542653/"
    "%C2%A1LO%20PROBAMOS%20Y%20S%C3%8D%20PAGA%21%20%F0%9F%98%B1%20"
    "%EF%BD%9C%20Source%20Hunter%20%28Juego%20NFT%20Gratis%20M%C3%B3vil%20y%20PC%29%20"
    "%5B2bMdfR7vilk%5D.mp4"
)


def test_s3_completion_message_displays_exact_percent_encoded_url():
    message = transfer_complete_text(
        filename="¡LO PROBAMOS Y SÍ PAGA! 😱 ｜ Source Hunter.mp4",
        size=1024,
        link_url=SAMPLE_URL,
    )

    assert f"<code>{SAMPLE_URL}</code>" in message
    assert "%C2%A1" in message
    assert "%20" in message
    assert "%C3%8D" in message


def test_s3_download_and_copy_buttons_keep_the_same_url():
    keyboard = result_keyboard(SAMPLE_URL, "task-id").inline_keyboard

    assert keyboard[0][0].url == SAMPLE_URL
    assert keyboard[1][0].copy_text.text == SAMPLE_URL


def test_multipart_link_message_keeps_url_out_of_decorative_small_caps():
    class RecordingBot:
        def __init__(self):
            self.messages = []

        async def send_message(self, **kwargs):
            self.messages.append(kwargs)

    bot = RecordingBot()
    job = SimpleNamespace(chat_id=1, part_urls={0: SAMPLE_URL}, filename="archivo.mp4")
    asyncio.run(send_task_links(job, bot))

    message = bot.messages[0]
    assert f"<code>{SAMPLE_URL}</code>" in message["text"]
    assert message["reply_markup"].inline_keyboard[0][0].url == SAMPLE_URL
