package io.github.somanghao.makecar.hyunphone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** 카드 그림(웹툰 표지 · 소개 사진) — 서버 중계(/hyun/img…)는 서버 주소를 붙인다 · 메모리 캐시만(파일 저장 안 함) */
object Img {
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) { override fun sizeOf(k: String, v: Bitmap) = v.byteCount }
    private val pool = Executors.newFixedThreadPool(3)
    private val main = Handler(Looper.getMainLooper())

    fun url(u: String) = if (u.startsWith("/")) Server.base + u else u

    fun load(raw: String, iv: ImageView) {
        if (raw.isBlank()) return
        val u = url(raw)
        iv.tag = u
        cache.get(u)?.let { iv.setImageBitmap(it); return }
        pool.execute {
            val bm = runCatching {
                val c = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 8000; setRequestProperty("User-Agent", "Mozilla/5.0") }
                try {
                    val bytes = c.inputStream.use { it.readBytes() }
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                    var s = 1; while (o.outWidth / s > 480) s *= 2
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
                } finally { c.disconnect() }
            }.getOrNull() ?: return@execute
            cache.put(u, bm)
            main.post { if (iv.tag == u) iv.setImageBitmap(bm) }
        }
    }
}
