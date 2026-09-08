# Using the phone's local model from another app

Conch runs open models **on the phone** — llama.cpp's `llama-server`, shipped
inside the APK, serving an OpenAI-compatible API on `127.0.0.1:8317`. With the
owner's permission, any other app on the same device can use it: prompts and
answers from a model that is already downloaded, with nothing leaving the
phone and no API key to buy.

There is no SDK. You ask once with an intent, you get a base URL and a key,
and from then on it is ordinary HTTP.

## 1. Ask for access

```kotlin
private val ACTION = "ai.eight24family.conch.action.REQUEST_LOCAL_MODEL"

// MUST be startActivityForResult: Android only tells Conch who the caller is
// for a for-result start, and Conch refuses to grant access to an app it
// cannot name.
val i = Intent(ACTION).setPackage("ai.eight24family.conch")
startActivityForResult(i, RC_LOCAL_MODEL)
```

The owner sees who is asking and what it will be able to do, then allows or
denies. An app that was allowed before gets its key back with no prompt; the
owner can revoke it at any time in Conch under **local models → api access**,
which invalidates that key immediately and leaves every other app's alone.

## 2. Read the result

```kotlin
override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
    if (rc != RC_LOCAL_MODEL || result != RESULT_OK || data == null) return  // denied
    val baseUrl = data.getStringExtra("base_url")   // http://127.0.0.1:8317/v1
    val apiKey  = data.getStringExtra("api_key")    // your app's own bearer token
    val model   = data.getStringExtra("model")      // loaded right now, or null
}
```

Keep the key; it stays valid until the owner revokes it. It is yours alone —
each granted app gets its own line in the engine's key file.

## 3. Use it

Standard OpenAI-compatible calls. Any client library, or plain HTTP:

```bash
curl http://127.0.0.1:8317/v1/chat/completions \
  -H "Authorization: Bearer $KEY" \
  -H "Content-Type: application/json" \
  -d '{"model":"qwen3_5-0_8b","stream":true,
       "messages":[{"role":"user","content":"name three planets"}]}'
```

- `GET /v1/models` — what is loaded.
- `POST /v1/chat/completions` — chat, streaming or not. Vision models take
  `image_url` parts with `data:` URLs when their vision pack is installed.
- `GET /health` — the only endpoint that needs no key. Use it to find out
  whether the engine is up before you build a request.

Every llama.cpp sampling parameter the server accepts works here too; the
context window is what the phone could afford (4K–16K, see `GET /props`).

## What a grant is, and is not

It is one key on the inference port. It buys prompts and answers from a model
the owner downloaded. It does **not** reach Conch's chats, files, servers or
SSH keys — none of those live behind that port — and it opens no network
connection off the device.

## The one limitation to design around

**The engine answers only while a model is loaded in Conch, and Conch frees
the weights after two minutes idle** — they are gigabytes of a phone's RAM.
So:

- Call `GET /health` first. A refused connection means no model is loaded, not
  that your key is wrong.
- Treat it as a capability that comes and goes, the way a paired accessory
  does. Ask the person to open the model in Conch, or fall back to whatever
  you do without a model.

Waking the engine on an outside request needs a resident, user-visible service
in Conch; until that ships, this limit is real and stated rather than papered
over.

## Errors

| Status | Means |
|---|---|
| `401` | No key, a wrong key, or a key the owner revoked. Ask again with the intent. |
| `503` | The engine is loading weights. Retry shortly. |
| Connection refused | No model is loaded (or Conch is not running). |
| `400` with a context message | Your prompt is longer than the window this phone could afford. Send less. |

## Testing against a debug build

Conch's debug variant is a separate package. Use
`.setPackage("ai.eight24family.conch.debug")` while developing against it.
