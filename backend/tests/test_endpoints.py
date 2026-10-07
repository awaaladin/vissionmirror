import json
import uuid

import pytest

from tests.conftest import JPEG, PNG, WEBP, get_token


def upload(data=JPEG, name="me.jpg", mime="image/jpeg"):
    return {"image": (name, data, mime)}


async def describe(client, auth, **form):
    return await client.post("/v1/describe", headers=auth, files=upload(), data=form)


async def test_describe_returns_full_contract(client, auth):
    r = await describe(client, auth, detail_level="detailed")
    assert r.status_code == 200
    body = r.json()
    assert uuid.UUID(body["session_id"])
    assert set(body) == {
        "session_id", "image_quality", "summary", "outfit", "hair_and_grooming",
        "accessories", "issues", "colour_harmony", "spoken_text", "confidence",
    }
    assert body["image_quality"] == {"usable": True, "issue": None, "advice": None}
    assert body["outfit"][0].keys() == {"item", "description", "color", "pattern"}


@pytest.mark.parametrize("data,mime", [(JPEG, "image/jpeg"), (PNG, "image/png"), (WEBP, "image/webp")])
async def test_describe_accepts_jpeg_png_webp(client, auth, data, mime):
    r = await client.post("/v1/describe", headers=auth, files=upload(data, "x", mime))
    assert r.status_code == 200


async def test_describe_rejects_non_image_even_with_image_content_type(client, auth):
    r = await client.post("/v1/describe", headers=auth, files=upload(b"<html>nope</html>"))
    assert r.status_code == 415
    assert r.json()["code"] == "unsupported_image"
    assert r.json()["spoken_text"]


async def test_describe_rejects_oversized_image(client, auth):
    big = JPEG + b"\x00" * (5 * 1024 * 1024)
    r = await client.post("/v1/describe", headers=auth, files=upload(big))
    assert r.status_code == 413
    assert r.json()["code"] == "image_too_large"


async def test_describe_accepts_image_right_at_limit(client, auth):
    exact = JPEG + b"\x00" * (5 * 1024 * 1024 - len(JPEG))
    r = await client.post("/v1/describe", headers=auth, files=upload(exact))
    assert r.status_code == 200


async def test_describe_validates_form_fields(client, auth):
    assert (await describe(client, auth, detail_level="extreme")).status_code == 422
    assert (await describe(client, auth, language="not a language!")).status_code == 422
    r = await client.post("/v1/describe", headers=auth)
    assert r.status_code == 422


async def test_describe_defaults_to_standard_detail(client, auth):
    default = (await client.post("/v1/describe", headers=auth, files=upload())).json()
    standard = (await describe(client, auth, detail_level="standard")).json()
    assert default["spoken_text"] == standard["spoken_text"]


async def test_unusable_photo_gives_advice_and_no_session(make_client):
    client = make_client("dark")
    auth = await get_token(client)
    r = await describe(client, auth)
    body = r.json()
    assert r.status_code == 200
    assert body["image_quality"]["usable"] is False
    assert body["image_quality"]["advice"]
    assert body["session_id"] is None
    assert body["outfit"] == [] and body["issues"] == []


async def test_stain_scenario_reports_issue_with_hedged_language(make_client):
    client = make_client("stain")
    auth = await get_token(client)
    body = (await describe(client, auth)).json()
    assert body["issues"][0]["severity"] == "medium"
    assert "can't be sure" in body["spoken_text"]


async def test_clash_scenario(make_client):
    client = make_client("clash")
    auth = await get_token(client)
    body = (await describe(client, auth)).json()
    assert body["colour_harmony"]["verdict"] == "clashing"


async def test_ask_follow_up_uses_same_photo(make_client):
    client = make_client("stain")
    auth = await get_token(client)
    sid = (await describe(client, auth)).json()["session_id"]
    r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "Is there a stain?"})
    assert r.status_code == 200
    body = r.json()
    assert set(body) == {"answer_text", "spoken_text", "confidence"}
    assert "collar" in body["answer_text"]


async def test_ask_keeps_conversation_history(client, auth, redis):
    sid = (await describe(client, auth)).json()["session_id"]
    for q in ("How is my hair?", "And the colours?"):
        r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": q})
        assert r.status_code == 200
    meta = json.loads(await redis.get(f"session:{sid}:meta"))
    assert [t["role"] for t in meta["history"]] == ["user", "assistant", "user", "assistant"]
    assert meta["history"][2]["text"] == "And the colours?"


async def test_ask_refuses_to_judge_looks(client, auth):
    sid = (await describe(client, auth)).json()["session_id"]
    r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "Am I attractive?"})
    assert "I don't judge looks" in r.json()["spoken_text"]


async def test_ask_unknown_session_is_404_with_spoken_text(client, auth):
    r = await client.post("/v1/ask", headers=auth, json={"session_id": str(uuid.uuid4()), "question": "hi"})
    assert r.status_code == 404
    assert r.json()["code"] == "session_not_found"
    assert r.json()["spoken_text"]


async def test_ask_validates_body(client, auth):
    r = await client.post("/v1/ask", headers=auth, json={"session_id": str(uuid.uuid4()), "question": ""})
    assert r.status_code == 422


async def test_delete_session_removes_it(client, auth, redis):
    sid = (await describe(client, auth)).json()["session_id"]
    assert (await client.delete(f"/v1/session/{sid}", headers=auth)).status_code == 204
    assert await redis.keys("session:*") == []
    r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "hi"})
    assert r.status_code == 404
    assert (await client.delete(f"/v1/session/{sid}", headers=auth)).status_code == 404


async def test_session_is_private_to_its_device(client, auth):
    sid = (await describe(client, auth)).json()["session_id"]
    other = await get_token(client)
    r = await client.post("/v1/ask", headers=other, json={"session_id": sid, "question": "hi"})
    assert r.status_code == 404
    assert (await client.delete(f"/v1/session/{sid}", headers=other)).status_code == 404
    # Owner still has it.
    r = await client.post("/v1/ask", headers=auth, json={"session_id": sid, "question": "hi"})
    assert r.status_code == 200
