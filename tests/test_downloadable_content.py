from bot import (
    DOWNLOADABLE_CONTENT_CATEGORIES,
    content_listing_keyboard,
    content_menu_keyboard,
    parse_downloadable_listing,
)


def callbacks(markup):
    return [button.callback_data for row in markup.inline_keyboard for button in row]


def test_content_menu_has_requested_categories_and_other_catalog():
    markup = content_menu_keyboard()
    values = callbacks(markup)
    assert "content:open:peliculas" in values
    assert "content:open:series" in values
    assert "content:open:otros" in values
    assert set(DOWNLOADABLE_CONTENT_CATEGORIES) == {"peliculas", "series", "otros"}


def test_listing_keeps_only_authorized_links_and_separates_directories():
    html = """
    <html><body>
      <a href="../">Parent Directory</a>
      <a href="Season%201/">Season 1</a>
      <a href="movie.mp4">Movie.mp4</a>
      <a href="https://example.com/evil.mp4">External</a>
      <a href="javascript:alert(1)">Bad</a>
      <a href="movie.mp4">Duplicate</a>
    </body></html>
    """
    rows = parse_downloadable_listing(html, "https://visuales.uclv.cu/Series/")
    assert [row["label"] for row in rows] == ["Season 1", "Movie.mp4"]
    assert rows[0]["kind"] == "dir"
    assert rows[1]["kind"] == "file"
    assert all("example.com" not in row["url"] for row in rows)


def test_listing_keyboard_has_pagination_and_back_navigation():
    entries = [{"label": f"file-{index}.mp4", "url": f"https://visuales.uclv.cu/{index}.mp4", "kind": "file"} for index in range(2)]
    markup = content_listing_keyboard(entries, page=0, total_pages=2)
    values = callbacks(markup)
    assert "content:page:1" in values
    assert "content:menu" in values
    assert "home" in values
