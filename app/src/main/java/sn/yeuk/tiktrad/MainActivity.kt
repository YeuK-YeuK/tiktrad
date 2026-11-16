package sn.yeuk.tiktrad

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.ByteBuffer

class MainActivity : AppCompatActivity() {

    private lateinit var inputLink: TextInputEditText
    private lateinit var inputKey: TextInputEditText
    private lateinit var inputModel: TextInputEditText
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var result: TextView
    private lateinit var btnCopy: MaterialButton
    private var busy = false
    private var lastTranslation = ""

    private val prefs by lazy { getSharedPreferences("tiktrad", Context.MODE_PRIVATE) }

    private val pickMedia = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) runFromUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        inputLink = findViewById(R.id.inputLink)
        inputKey = findViewById(R.id.inputKey)
        inputModel = findViewById(R.id.inputModel)
        progress = findViewById(R.id.progress)
        status = findViewById(R.id.status)
        result = findViewById(R.id.result)
        btnCopy = findViewById(R.id.btnCopy)

        inputKey.setText(prefs.getString("key", ""))
        inputModel.setText(prefs.getString("model", DEFAULT_MODEL))

        findViewById<MaterialButton>(R.id.btnSave).setOnClickListener {
            saveSettings()
            toast("Réglages enregistrés")
        }
        findViewById<MaterialButton>(R.id.btnLink).setOnClickListener {
            val link = extractUrl(inputLink.text?.toString().orEmpty())
            if (link == null) toast("Collez d'abord un lien TikTok") else runFromLink(link)
        }
        findViewById<MaterialButton>(R.id.btnFile).setOnClickListener {
            pickMedia.launch("*/*")
        }
        btnCopy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Traduction", lastTranslation))
            toast("Traduction copiée")
        }

        if (prefs.getString("key", "").isNullOrBlank()) {
            status.text = "Première utilisation : entrez votre clé API Gemini en bas, puis « Enregistrer »."
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val stream: Uri? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (stream != null) { runFromUri(stream); return }
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val url = extractUrl(text)
        if (url != null) {
            inputLink.setText(url)
            runFromLink(url)
        } else toast("Aucun lien trouvé dans le partage")
    }

    // ---------- Flux principal ----------

    private fun runFromLink(link: String) = launchJob {
        setStatus("Récupération de la vidéo TikTok…")
        val video = withContext(Dispatchers.IO) { downloadTikTok(link) }
        processFile(video, "video/mp4")
    }

    private fun runFromUri(uri: Uri) = launchJob {
        setStatus("Lecture du fichier…")
        val mime = contentResolver.getType(uri) ?: "video/mp4"
        val file = withContext(Dispatchers.IO) {
            val f = File(cacheDir, "input_media")
            contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
            f
        }
        processFile(file, mime)
    }

    private suspend fun processFile(file: File, mime: String) {
        setStatus("Extraction du son…")
        var media = file
        var mediaMime = mime
        if (mime.startsWith("video")) {
            val audio = withContext(Dispatchers.IO) { runCatching { extractAudio(file) }.getOrNull() }
            if (audio != null) { media = audio; mediaMime = "audio/mp4" }
        }
        if (media.length() > MAX_INLINE_BYTES) {
            throw Exception("Fichier trop lourd (${media.length() / 1_000_000} Mo). Limite ≈ 19 Mo.")
        }
        setStatus("Transcription et traduction en cours…")
        val text = withContext(Dispatchers.IO) { askGemini(media, mediaMime) }
        showResult(text)
    }

    private fun launchJob(block: suspend () -> Unit) {
        if (busy) { toast("Traitement déjà en cours"); return }
        saveSettings()
        if (prefs.getString("key", "").isNullOrBlank()) {
            status.text = "Entrez d'abord votre clé API Gemini (en bas de l'écran)."
            return
        }
        busy = true
        progress.visibility = View.VISIBLE
        result.text = ""
        btnCopy.visibility = View.GONE
        lifecycleScope.launch {
            try {
                block()
            } catch (e: Exception) {
                setStatus("Erreur : ${e.message}")
            } finally {
                busy = false
                progress.visibility = View.GONE
            }
        }
    }

    // ---------- TikTok ----------

    /** Résout le lien TikTok en vidéo téléchargeable via le service public tikwm.com. */
    private fun downloadTikTok(link: String): File {
        val conn = (URL("https://www.tikwm.com/api/").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20000; readTimeout = 30000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            setRequestProperty("User-Agent", UA)
        }
        conn.outputStream.use { it.write("url=${URLEncoder.encode(link, "UTF-8")}&hd=0".toByteArray()) }
        val body = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(body)
        if (json.optInt("code", -1) != 0) {
            throw Exception("Impossible de récupérer cette vidéo (${json.optString("msg")}). " +
                "Astuce : dans TikTok, « Enregistrer la vidéo », puis « Choisir une vidéo » ici.")
        }
        val data = json.getJSONObject("data")
        var play = data.optString("play").ifBlank { data.optString("wmplay") }
        if (play.isBlank()) throw Exception("Aucune vidéo trouvée pour ce lien.")
        if (play.startsWith("/")) play = "https://www.tikwm.com$play"

        val out = File(cacheDir, "tiktok.mp4")
        val dl = (URL(play).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("User-Agent", UA)
        }
        dl.inputStream.use { i -> out.outputStream().use { i.copyTo(it) } }
        if (out.length() < 1000) throw Exception("Téléchargement de la vidéo échoué.")
        return out
    }

    // ---------- Audio ----------

    /** Copie la piste audio (sans réencodage) dans un fichier .m4a léger. */
    private fun extractAudio(video: File): File? {
        val extractor = MediaExtractor()
        extractor.setDataSource(video.absolutePath)
        var track = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; format = f; break }
        }
        if (track < 0 || format == null) { extractor.release(); return null }
        extractor.selectTrack(track)
        val out = File(cacheDir, "audio.m4a")
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val dst = muxer.addTrack(format)
        muxer.start()
        val buffer = ByteBuffer.allocate(1 shl 20)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val size = extractor.readSampleData(buffer, 0)
            if (size < 0) break
            info.offset = 0
            info.size = size
            info.presentationTimeUs = extractor.sampleTime
            info.flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            muxer.writeSampleData(dst, buffer, info)
            extractor.advance()
        }
        muxer.stop(); muxer.release(); extractor.release()
        return if (out.length() > 0) out else null
    }

    // ---------- Gemini ----------

    private fun askGemini(file: File, mime: String): String {
        val key = prefs.getString("key", "")!!.trim()
        val model = prefs.getString("model", DEFAULT_MODEL)!!.trim().ifBlank { DEFAULT_MODEL }
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)

        val parts = JSONArray()
            .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
            .put(JSONObject().put("text", PROMPT))
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
            .put("generationConfig", JSONObject().put("temperature", 0.2))

        val conn = (URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 30000; readTimeout = 180000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", key)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.readText().orEmpty()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(resp).getJSONObject("error").getString("message") }.getOrDefault(resp)
            throw Exception("Gemini ($code) : $msg")
        }
        val cands = JSONObject(resp).optJSONArray("candidates")
            ?: throw Exception("Réponse vide de Gemini (contenu peut-être bloqué).")
        val p = cands.getJSONObject(0).getJSONObject("content").getJSONArray("parts")
        return buildString { for (i in 0 until p.length()) append(p.getJSONObject(i).optString("text")) }.trim()
    }

    // ---------- UI helpers ----------

    private fun showResult(text: String) {
        setStatus("Terminé ✔")
        result.text = text
        lastTranslation = text.substringAfter("TRADUCTION", text).trimStart(':', ' ', '\n').trim()
        btnCopy.visibility = View.VISIBLE
    }

    private fun setStatus(s: String) { status.text = s }

    private fun saveSettings() {
        prefs.edit()
            .putString("key", inputKey.text?.toString()?.trim())
            .putString("model", inputModel.text?.toString()?.trim()?.ifBlank { DEFAULT_MODEL })
            .apply()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun extractUrl(text: String): String? =
        Regex("https?://\\S+").find(text)?.value?.trimEnd('.', ',', ')')

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-latest"
        const val MAX_INLINE_BYTES = 19_000_000L
        const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        val PROMPT = """
            Écoute attentivement les paroles prononcées dans cet enregistrement (souvent en arabe : standard, darija, hassaniya, égyptien, golfe, etc.).
            1) Indique la langue et, si possible, le dialecte.
            2) Transcris fidèlement les paroles dans la langue d'origine (écriture arabe pour l'arabe).
            3) Traduis l'intégralité en français clair et naturel, en conservant le sens, les citations religieuses et les expressions idiomatiques (explique-les brièvement entre crochets si nécessaire).
            Si l'audio ne contient que de la musique sans paroles, dis-le simplement.
            Réponds exactement dans ce format :
            LANGUE : ...

            TRANSCRIPTION :
            ...

            TRADUCTION :
            ...
        """.trimIndent()
    }
}
