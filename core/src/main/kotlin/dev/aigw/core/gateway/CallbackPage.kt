package dev.aigw.core.gateway

internal fun callbackPage(title: String, message: String): String = """
    <!doctype html>
    <html lang="zh-CN"><head><meta charset="utf-8">
    <meta name="viewport" content="width=device-width,initial-scale=1">
    <title>$title</title>
    <style>
      body{margin:0;height:100vh;display:flex;align-items:center;justify-content:center;
           font-family:system-ui,-apple-system,"PingFang SC",sans-serif;background:#f6f8fb;color:#1b1c1e}
      .card{max-width:22rem;padding:2rem;border-radius:1.75rem;background:#fff;text-align:center;
            box-shadow:0 8px 30px rgba(0,0,0,.06)}
      h1{font-size:1.25rem;margin:0 0 .75rem}
      p{font-size:.95rem;line-height:1.6;color:#5a5f66;margin:0}
    </style></head>
    <body><div class="card"><h1>$title</h1><p>$message</p></div></body></html>
""".trimIndent()
