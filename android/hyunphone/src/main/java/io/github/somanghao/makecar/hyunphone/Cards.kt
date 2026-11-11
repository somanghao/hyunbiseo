package io.github.somanghao.makecar.hyunphone

import android.app.Activity
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import java.util.Calendar

/**
 * [정보] 탭 카드 — 차에서 물어본 결과를 종류별로 다르게(사용자 11-11 "결과만 이쁘게"):
 *  장소(가게 줄마다 거리 · 찾은 평판 한 줄 · [전화] · [지도]) · 순위(웹툰 표지 격자, 1~3위 강조) · 추천(제목 + 한 줄 이유 + 찾은 평점·출처)
 *  · 시세(값 + 등락 빨강/파랑) · 일정(날짜·시각 KST · 대진 · 대회 — 다가오는 것 강조, 지난 건 결과) · 소개(사진 + 표) · 그 밖 목록
 * 서버 카드 형식 = server/hyun/features/cards.py(kind: "" · info · schedule · stock · picks · image_list) · 장소 = phonelink(메모리 3시간)
 */
class Cards(private val a: Activity, private val onPlace: (JSONObject) -> Unit) {
    private fun dp(v: Number) = Ui.dp(a, v)
    private fun t(s: String, sp: Float, c: Int, bold: Boolean = false) = Ui.text(a, s, sp, c, bold)

    fun kind(e: Sync.Entry): String {
        val k = e.cardx?.optString("kind").orEmpty()
        val imgs = e.cardx?.optJSONArray("images")?.let { arr -> (0 until arr.length()).any { arr.optString(it).isNotBlank() } } == true
        return when {
            e.places != null || e.placesGone -> "장소"
            k == "image_list" || (imgs && k != "info") -> "순위"
            k == "schedule" -> "일정"
            k == "stock" -> "시세"
            k == "picks" -> "추천"
            k == "info" -> if (e.card.contains("휴게소")) "휴게소" else "소개"
            e.card.contains("교통") -> "교통"
            else -> "목록"
        }
    }

    fun card(e: Sync.Entry, focusMid: Long, focusName: String): View {
        val kind = kind(e)
        val c = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL
            background = Ui.round(Ui.CARD, 18f, a, if (e.mid == focusMid || e.live) Ui.ACCENT else null)
            setPadding(dp(16), dp(12), dp(14), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } }
        val top = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(t(kind, 11.5f, 0xFF06302F.toInt(), bold = true).apply { background = Ui.round(chipColor(kind), 10f, a); setPadding(dp(8), dp(2), dp(8), dp(2)) })
        top.addView(t("  ${e.t}" + (if (e.live) " · 방금 동기화" else "") + (if (e.who == "운전자") " · \"${e.q}\"" else " · 현비서가 먼저"), 11.5f, Ui.CARD_SUB).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        c.addView(top)
        c.addView(t(e.card.ifBlank { "찾은 것" }, 17.5f, Ui.CARD_FG, bold = true).apply { setPadding(0, dp(8), 0, 0) })
        if (kind != "순위" && kind != "시세") c.addView(t(e.a, 13.5f, Ui.CARD_SUB).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, dp(2)) })
        val items = e.cardx?.optJSONArray("items")?.let { arr -> (0 until minOf(arr.length(), 10)).map { arr.optString(it) } }.orEmpty()
        val imgs = e.cardx?.optJSONArray("images")?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }.orEmpty()
        when (kind) {
            "장소" -> if (e.places != null) e.places.take(5).forEach { c.addView(placeRow(it, focusName)) } else c.addView(gone())
            "순위" -> c.addView(coverGrid(items, imgs))
            "추천" -> items.forEach { c.addView(pickRow(it)) }
            "시세" -> items.forEachIndexed { i, s -> c.addView(stockRow(s, i)) }
            "일정" -> { val next = items.indexOfFirst { !ended(it) }; items.forEachIndexed { i, s -> c.addView(scheduleRow(s, i == next)) } }
            "소개", "휴게소" -> { imgs.firstOrNull { it.isNotBlank() }?.let { c.addView(photo(it)) }; items.forEach { c.addView(infoRow(it)) } }
            else -> items.forEachIndexed { i, s -> c.addView(listRow(s, i)) }
        }
        e.cardx?.optString("source")?.takeIf { it.isNotBlank() }?.let { c.addView(t(it, 11.5f, Ui.CARD_SUB).apply { setPadding(0, dp(10), 0, 0) }) }
        return c
    }

    private fun chipColor(k: String) = when (k) { "장소" -> 0xFFBFEDEC; "순위" -> 0xFFFFE2B8; "추천" -> 0xFFE3D7FF; "시세" -> 0xFFD8E6FF; "일정" -> 0xFFC9F0D6; else -> 0xFFE1E8EF }.toInt()

    private fun gone() = t("장소 정보는 시간이 지나 지웠어요(카카오 약관 — 저장하지 않아요). 차에서 다시 찾아볼까요?", 12.5f, Ui.CARD_SUB).apply {
        background = Ui.round(0xFFE6ECF2.toInt(), 12f, a); setPadding(dp(12), dp(8), dp(12), dp(8))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) } }

    /** 장소 — 이름(누르면 상세) · 거리 · 찾은 평판 한 줄 · 바로 [전화] [지도] */
    private fun placeRow(p: JSONObject, focusName: String): View {
        val focus = focusName.isNotBlank() && p.optString("name") == focusName
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (focus) 8 else 0), dp(8), 0, dp(6)); if (focus) background = Ui.round(0xFFD6F3F2.toInt(), 12f, a)
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) } }
        val r = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); setOnClickListener { onPlace(p) } }
        r.addView(t(p.optString("name") + "  ›", 15f, Ui.CARD_FG, bold = true))
        r.addView(t(listOfNotNull(p.optString("cat").takeIf { it.isNotBlank() },
            p.optString("near").takeIf { it.isNotBlank() }?.let { "$it ${p.optInt("m")}m" } ?: "${p.optInt("m")}m",
            p.optInt("walk").takeIf { it > 0 }?.let { "걸어서 ${it}분" }).joinToString(" · "), 12.5f, Ui.CARD_SUB))
        p.optString("note").takeIf { it.isNotBlank() }?.let { r.addView(t("★ $it", 12.5f, 0xFF0E7C7B.toInt()).apply { setPadding(0, dp(2), 0, 0) }) }
        line.addView(r)
        if (p.optString("tel").isNotBlank()) line.addView(pill("전화", true) { Place.dial(a, p) })
        line.addView(pill("지도", false) { Place.map(a, p) })
        return line
    }

    fun pill(s: String, primary: Boolean, onClick: () -> Unit) = TextView(a).apply {
        text = s; gravity = Gravity.CENTER; textSize = 13.5f; typeface = Ui.font(a, true)
        setTextColor(if (primary) 0xFF06302F.toInt() else Ui.CARD_FG)
        background = Ui.round(if (primary) Ui.ACCENT else 0xFFE1E8EF.toInt(), 20f, a)
        layoutParams = LinearLayout.LayoutParams(dp(56), dp(40)).apply { marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    /** 순위 — 표지 3열 격자(1~3위 배지 강조) */
    private fun coverGrid(items: List<String>, imgs: List<String>): View {
        val g = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(10), 0, 0) }
        items.take(9).chunked(3).forEachIndexed { r, row ->
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            row.forEachIndexed { k, s ->
                val i = r * 3 + k
                val cell = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = if (k < 2) dp(8) else 0; bottomMargin = dp(10) } }
                val frame = FrameLayout(a).apply { background = Ui.round(0xFFDDE4EC.toInt(), 10f, a); clipToOutline = true }
                val iv = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
                frame.addView(iv, FrameLayout.LayoutParams(-1, dp(132)))
                imgs.getOrNull(i)?.let { Img.load(it, iv) }
                frame.addView(t("${i + 1}", 13f, if (i < 3) 0xFF06302F.toInt() else Ui.FG, bold = true).apply { gravity = Gravity.CENTER
                    background = Ui.round(if (i < 3) Ui.ACCENT else 0xCC14202E.toInt(), 8f, a) }, FrameLayout.LayoutParams(dp(26), dp(22)).apply { leftMargin = dp(6); topMargin = dp(6) })
                cell.addView(frame)
                val parts = s.split(" — ", limit = 2)
                cell.addView(t(parts[0], 13f, Ui.CARD_FG, bold = i < 3).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(5), 0, 0) })
                if (parts.size > 1) cell.addView(t(parts[1], 11f, Ui.CARD_SUB).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                line.addView(cell)
            }
            repeat(3 - row.size) { line.addView(View(a), LinearLayout.LayoutParams(0, 1, 1f)) }
            g.addView(line)
        }
        if (items.size > 9) g.addView(t("10 ${items[9].substringBefore(" — ")}", 12.5f, Ui.CARD_SUB))
        return g
    }

    /** 추천 — 제목 + 찾은 평점 칩 + 한 줄 이유 */
    private fun pickRow(s: String): View {
        val parts = s.split(" — ", limit = 2)
        val segs = parts.getOrNull(1)?.split(" · ").orEmpty()
        val rating = segs.firstOrNull { Regex("평점|★|별점|\\d\\.\\d|%").containsMatchIn(it) }
        val reason = segs.filter { it != rating }.joinToString(" · ")
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; background = Ui.round(0xFFFFFFFF.toInt(), 12f, a)
            setPadding(dp(12), dp(10), dp(12), dp(10)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) } }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(t(parts[0], 16f, Ui.CARD_FG, bold = true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        rating?.let { head.addView(t(it.trim(), 12f, 0xFF6B4E00.toInt(), bold = true).apply { background = Ui.round(0xFFFFE7A6.toInt(), 10f, a); setPadding(dp(8), dp(3), dp(8), dp(3)) }) }
        box.addView(head)
        if (reason.isNotBlank()) box.addView(t(reason, 13.5f, Ui.CARD_SUB).apply { setPadding(0, dp(3), 0, 0) })
        return box
    }

    /** 시세 — 이름 · 값 · 등락(오르면 빨강 ▲, 내리면 파랑 ▼) */
    private fun stockRow(s: String, i: Int): View {
        val parts = s.split(" — ", limit = 2)
        val right = parts.getOrNull(1).orEmpty()
        val m = Regex("([▲▼△▽+\\-])\\s*([\\d.,]+%?)").find(right)
        val value = (if (m != null) right.substring(0, m.range.first) else right).trim()
        val up = m?.groupValues?.get(1)?.let { it == "▲" || it == "△" || it == "+" }
        val color = when (up) { true -> 0xFFE5484D.toInt(); false -> 0xFF3B82F6.toInt(); null -> Ui.CARD_SUB }
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(9), 0, dp(9)) }
        line.addView(t(parts[0], 15f, Ui.CARD_FG, bold = i < 2).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        line.addView(t(value, 15f, Ui.CARD_FG, bold = true))
        line.addView(t(m?.let { "${if (up == true) "▲" else if (up == false) "▼" else ""}${it.groupValues[2]}" } ?: "", 13.5f, color, bold = true).apply {
            gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(dp(78), -2) })
        return line
    }

    private fun ended(s: String) = Regex("끝남|결과|종료").containsMatchIn(s.substringBefore(" — ")) || pastDate(s)

    /** "11-08(토)"가 오늘보다 앞이면 지난 것 */
    private fun pastDate(s: String): Boolean {
        val m = Regex("(\\d{1,2})-(\\d{1,2})").find(s.substringBefore(" — ")) ?: return false
        val now = Calendar.getInstance()
        val mo = m.groupValues[1].toInt(); val d = m.groupValues[2].toInt()
        val cur = (now.get(Calendar.MONTH) + 1) * 100 + now.get(Calendar.DAY_OF_MONTH)
        val v = mo * 100 + d
        return v < cur && cur - v < 600   // 연말→연초는 다음 해로 본다
    }

    /** 일정 — 왼쪽 날짜·시각 칸 · 오른쪽 대진·대회 · 다가오는 첫 일정 강조 · 지난 건 흐리게 + 결과 */
    private fun scheduleRow(s: String, next: Boolean): View {
        val parts = s.split(" — ", limit = 2)
        val left = parts[0]; val right = parts.getOrNull(1).orEmpty()
        val past = ended(s)
        val date = Regex("\\d{1,2}-\\d{1,2}(\\(.\\))?").find(left)?.value.orEmpty()
        val time = Regex("\\d{1,2}:\\d{2}").find(left)?.value ?: if (past) "끝남" else left.replace(date, "").replace("KST", "").trim().ifBlank { "시각 미정" }
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10)); layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
            background = Ui.round(if (next) 0xFFD9F5F4.toInt() else 0xFFFFFFFF.toInt(), 12f, a, if (next) Ui.ACCENT else null); alpha = if (past) 0.6f else 1f }
        val dcol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(dp(78), -2) }
        dcol.addView(t(date.ifBlank { "날짜 미정" }, 12f, Ui.CARD_SUB).apply { gravity = Gravity.CENTER })
        dcol.addView(t(time, if (time.length <= 5) 19f else 14f, if (past) Ui.CARD_SUB else Ui.CARD_FG, bold = true).apply { gravity = Gravity.CENTER })
        line.addView(dcol)
        val rcol = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0); layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
        val segs = right.split(" · ")
        if (next) rcol.addView(t("다음 일정 · KST", 11f, 0xFF0E7C7B.toInt(), bold = true))
        rcol.addView(t(segs.first(), 15f, Ui.CARD_FG, bold = true))
        if (segs.size > 1) rcol.addView(t(segs.drop(1).joinToString(" · "), 12.5f, Ui.CARD_SUB))
        line.addView(rcol)
        return line
    }

    /** 소개 — 사진 */
    private fun photo(u: String) = ImageView(a).apply { scaleType = ImageView.ScaleType.CENTER_CROP; background = Ui.round(0xFFDDE4EC.toInt(), 12f, a); clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(-1, dp(170)).apply { topMargin = dp(10) }; Img.load(u, this) }

    /** 소개 — "라벨 — 값" 표 */
    private fun infoRow(s: String): View {
        val parts = s.split(" — ", limit = 2)
        val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        line.addView(t(parts[0], 13f, Ui.CARD_SUB).apply { layoutParams = LinearLayout.LayoutParams(dp(78), -2) })
        line.addView(t(parts.getOrNull(1).orEmpty(), 14.5f, Ui.CARD_FG).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        return line
    }

    /** 그 밖 목록(교통 표 등) */
    private fun listRow(s: String, i: Int): View {
        val parts = s.split(" — ", limit = 2)
        val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(7), 0, 0) }
        row.addView(t("${i + 1}", 14f, if (i < 3) Ui.ACCENT else Ui.CARD_SUB, bold = true).apply { layoutParams = LinearLayout.LayoutParams(dp(22), -2) })
        row.addView(t(parts[0], 14.5f, Ui.CARD_FG, bold = i < 3).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        if (parts.size > 1) row.addView(t(parts[1], 12.5f, Ui.CARD_SUB).apply { setPadding(dp(6), dp(1), 0, 0) })
        return row
    }
}
