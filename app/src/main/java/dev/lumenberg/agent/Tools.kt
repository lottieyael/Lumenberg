package dev.lumenberg.agent

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The things the assistant can do without touching the screen.
 *
 * This exists because driving apps is the worst way to answer most questions: it is slow,
 * it fails whenever a layout changes, it puts windows in front of the user, and it sends
 * whatever is on screen to a model provider. Looking a fact up over HTTP does none of that.
 * Everything here runs in the background with nothing to configure and no keys, so a
 * research request needs no permissions at all.
 */
class Tools(context: Context) {
    private val context = context.applicationContext

    /** Runs a tool. Returns what the assistant should be told, or null if it is not a tool. */
    suspend fun run(verb: String, args: JSONObject): String? = when (verb) {
        "search" -> search(args.optString("q"))
        "place" -> place(args.optString("q")).let { it?.describe() ?: "Nothing found for that place." }
        "nearby" -> nearby(args.optString("what"), args.optString("near"))
        "route" -> route(args.optString("from"), args.optString("to"), args.optString("mode"))
        "weather" -> weather(args.optString("place"))
        "navigate" -> navigate(args.optString("to"))
        else -> null
    }

    // --- knowledge ---------------------------------------------------------

    /**
     * Wikipedia rather than a search engine: every general web search worth using needs an
     * account and a key, and the point of this layer is that nothing needs setting up.
     * It answers factual questions well and is honest about the rest.
     */
    private suspend fun search(query: String): String {
        if (query.isBlank()) return "No search terms given."
        val hits = json(
            "https://en.wikipedia.org/w/api.php?action=query&list=search" +
                "&srsearch=${enc(query)}&format=json&srlimit=3",
        )?.optJSONObject("query")?.optJSONArray("search") ?: return "Nothing found."

        if (hits.length() == 0) return "Nothing found for \"$query\"."
        val titles = (0 until hits.length()).mapNotNull { hits.optJSONObject(it)?.optString("title") }
        val summary = titles.firstOrNull()?.let { title ->
            json("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}")
                ?.optString("extract")
                ?.takeIf { it.isNotBlank() }
        }
        return buildString {
            append("Results: ").append(titles.joinToString("; ")).append('\n')
            summary?.let { append(titles.first()).append(": ").append(it.take(900)) }
        }
    }

    // --- places and travel -------------------------------------------------

    private data class Spot(val name: String, val lat: Double, val lon: Double) {
        fun describe() = "$name (${"%.4f".format(lat)}, ${"%.4f".format(lon)})"
    }

    private suspend fun place(query: String, bounded: String? = null): Spot? {
        if (query.isBlank()) return null
        val url = StringBuilder("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=")
            .append(enc(query))
        bounded?.let { url.append("&bounded=1&viewbox=").append(it) }
        val first = array(url.toString())?.optJSONObject(0) ?: return null
        return Spot(
            name = first.optString("display_name").substringBefore(",").ifBlank { query },
            lat = first.optDouble("lat"),
            lon = first.optDouble("lon"),
        )
    }

    private suspend fun nearby(what: String, near: String): String {
        if (what.isBlank()) return "Nothing to look for."
        val centre = place(near) ?: return "I could not find \"$near\"."
        // A box roughly a kilometre across, which is what "nearby" means on foot.
        val box = "${centre.lon - 0.010},${centre.lat + 0.007},${centre.lon + 0.010},${centre.lat - 0.007}"
        val found = array(
            "https://nominatim.openstreetmap.org/search?format=json&limit=6&bounded=1" +
                "&q=${enc(what)}&viewbox=$box",
        ) ?: return "Nothing found near ${centre.name}."
        val names = (0 until found.length()).mapNotNull {
            found.optJSONObject(it)?.optString("display_name")?.substringBefore(",")
        }.distinct()
        return if (names.isEmpty()) {
            "No $what found near ${centre.name}."
        } else {
            "$what near ${centre.name}: ${names.joinToString("; ")}"
        }
    }

    private suspend fun route(from: String, to: String, mode: String): String {
        val start = place(from) ?: return "I could not find \"$from\"."
        val end = place(to) ?: return "I could not find \"$to\"."
        val profile = when (mode.lowercase()) {
            "walk", "walking", "foot" -> "foot"
            "bike", "cycling", "bicycle" -> "bike"
            else -> "driving"
        }
        val route = json(
            "https://router.project-osrm.org/route/v1/$profile/" +
                "${start.lon},${start.lat};${end.lon},${end.lat}?overview=false",
        )?.optJSONArray("routes")?.optJSONObject(0)
            ?: return "No route from ${start.name} to ${end.name}."
        val minutes = (route.optDouble("duration") / 60).toInt()
        val km = route.optDouble("distance") / 1000
        return "${start.name} to ${end.name} by $profile: about $minutes minutes, " +
            "${"%.1f".format(km)} km."
    }

    private suspend fun weather(where: String): String {
        val spot = place(where) ?: return "I could not find \"$where\"."
        val data = json(
            "https://api.open-meteo.com/v1/forecast?latitude=${spot.lat}&longitude=${spot.lon}" +
                "&current=temperature_2m,apparent_temperature,precipitation" +
                "&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto",
        ) ?: return "No weather for ${spot.name}."
        val now = data.optJSONObject("current")
        val day = data.optJSONObject("daily")
        val high = day?.optJSONArray("temperature_2m_max")?.optDouble(0)
        val low = day?.optJSONArray("temperature_2m_min")?.optDouble(0)
        return buildString {
            append(spot.name).append(": ")
            append(now?.optDouble("temperature_2m")?.toInt() ?: "?").append("°C now")
            now?.optDouble("apparent_temperature")?.let { append(", feels like ${it.toInt()}°C") }
            if (high != null && low != null) append(", today ${low.toInt()} to ${high.toInt()}°C")
            now?.optDouble("precipitation")?.takeIf { it > 0 }?.let { append(", ${it}mm falling") }
        }
    }

    /**
     * The one tool that deliberately puts something in front of the user, because starting
     * navigation is the point rather than a side effect.
     */
    private suspend fun navigate(to: String): String {
        val spot = place(to) ?: return "I could not find \"$to\"."
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${spot.lat},${spot.lon}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val started = runCatching { context.startActivity(intent) }.isSuccess ||
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:${spot.lat},${spot.lon}?q=${enc(spot.name)}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        return if (started) "Navigation started to ${spot.name}." else "No navigation app is installed."
    }

    // --- plumbing ----------------------------------------------------------

    private suspend fun json(url: String): JSONObject? =
        fetch(url)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private suspend fun array(url: String): JSONArray? =
        fetch(url)?.let { runCatching { JSONArray(it) }.getOrNull() }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                // OpenStreetMap's terms require a real identifier, not a browser's.
                setRequestProperty("User-Agent", AGENT)
                setRequestProperty("Accept", "application/json")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private companion object {
        const val AGENT = "Lumenberg/0.6 (Android launcher; github.com/lottieyael/Lumenberg)"
    }
}
