package io.github.gooddesignenjoyer.mensaqr

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val BASE = "https://akafoe.studylife.org"

private const val APP_NAME = "Mensa QR"   // shown on screen

/** Label for the login session on the server, e.g. "Mensa QR on Google Pixel 8". */
private val DEVICE_NAME: String by lazy {
    val maker = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
    val model = if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
                else "$maker ${Build.MODEL}"
    "Studylife on $model"
}

private fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

class MainActivity : Activity() {

    private lateinit var store: Store
    private lateinit var root: LinearLayout
    private var tempToken: String? = null   // held only during a login attempt

    /** Standard-format User-Agent, e.g. "MensaQR/1.0.0 (Android 16; Pixel 8)". */
    private val userAgent: String by lazy {
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }
            .getOrNull() ?: "dev"
        "MensaQR/$version (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = Store(this)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val p = dp(24)
            setPadding(p, p, p, p)
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            fitsSystemWindows = true   // keep clear of status/nav bars (edge-to-edge on API 35+)
            addView(root)
        })
        route()
    }

    /** Today's code from the plain cache, or null. No Keystore work, so it's instant. */
    private fun cachedToday(): String? =
        if (store.plain("qr_day") == today()) store.plain("qr_card") else null

    /** Decide what to show on open. */
    private fun route() {
        cachedToday()?.let { showQr(it); return }   // fast path: drawn in the first frame, works offline
        info("Loading…")
        bg {                                        // Keystore decryption off the main thread
            val token = store.get("access_token")
            val email = store.get("email")
            val pw = store.get("password")
            ui {
                when {
                    token != null -> fetchQr(token)                     // try existing session
                    email != null && pw != null -> startAuth(email, pw) // session gone, re-auth
                    else -> showLogin(null)                             // first run
                }
            }
        }
    }

    // ---------------------------------------------------------------- screens

    private fun showLogin(error: String?): Unit = ui {
        screen(APP_NAME)
        msg(
            "Unofficial app, not affiliated with Akafö or StudyLife. Sign in with your " +
            "Akafö StudyLife account. Your login is only sent to akafoe.studylife.org " +
            "and is stored encrypted on this phone, so re-login only needs the e-mail code.",
            false
        )
        error?.let { msg(it, true) }
        val email = field("E-mail", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        val pw = field("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        button("Continue") {
            val e = email.text.toString().trim()
            val p = pw.text.toString()
            if (e.isEmpty() || p.isEmpty()) showLogin("Enter e-mail and password.")
            else startAuth(e, p)
        }
    }

    /** Step 1: e-mail + password -> temp_token, server sends the e-mail code. */
    private fun startAuth(email: String, pw: String) {
        info("Signing in…")
        bg {
            val (code, body) = http(
                "POST", "/api/sanctum/token",
                JSONObject().put("email", email).put("password", pw).put("device_name", DEVICE_NAME),
                null
            )
            ui {
                when {
                    code in 200..299 -> {
                        tempToken = JSONObject(body).optStringOrNull("temp_token")
                        store.put("email", email); store.put("password", pw)
                        if (tempToken == null) showLogin("Unexpected response (no temp_token).")
                        else showCode(null)
                    }
                    code == -1 -> retry("No connection.") { startAuth(email, pw) } // keep creds
                    else -> {                                                      // rejected
                        store.del("email"); store.del("password")
                        showLogin("Sign-in failed (HTTP $code). Check your details.")
                    }
                }
            }
        }
    }

    private fun showCode(error: String?): Unit = ui {
        screen("Enter e-mail code")
        msg("A confirmation code was sent to your e-mail.", false)
        error?.let { msg(it, true) }
        val code = field("Code", InputType.TYPE_CLASS_NUMBER)
        button("Verify") {
            val c = code.text.toString().trim()
            if (c.isEmpty()) showCode("Enter the code.") else verify(c)
        }
    }

    /** Step 2: temp_token + e-mail code -> access_token. */
    private fun verify(code: String) {
        val tt = tempToken ?: return route()
        info("Verifying…")
        bg {
            val (status, body) = http(
                "POST", "/api/2fa/verify",
                JSONObject().put("code", code).put("temp_token", tt)
                    .put("remember_device", true).put("device_name", DEVICE_NAME),
                null
            )
            ui {
                when {
                    status in 200..299 -> {
                        val j = JSONObject(body)
                        val token = j.optStringOrNull("access_token")
                        if (token == null) { showCode("Unexpected response (no access_token)."); return@ui }
                        store.put("access_token", token)
                        j.optStringOrNull("refresh_token")?.let { store.put("refresh_token", it) }
                        j.optStringOrNull("remember_device_token")?.let { store.put("remember_device_token", it) }
                        j.optJSONObject("user")?.let { u ->
                            val name = listOfNotNull(u.optStringOrNull("first_name"), u.optStringOrNull("last_name"))
                                .joinToString(" ").trim()
                            if (name.isNotEmpty()) store.putPlain("name", name)
                        }
                        tempToken = null
                        fetchQr(token)
                    }
                    status == -1 -> showCode("No connection. Try again.")
                    else -> showCode("Wrong or expired code (HTTP $status).")
                }
            }
        }
    }

    /** Fetch today's QR payload with the session token. */
    private fun fetchQr(token: String) {
        info("Fetching today’s code…")
        bg {
            val (status, body) = http("GET", "/api/student-verification/qr-code", null, "Bearer $token")
            ui {
                when {
                    status in 200..299 -> {
                        val card = JSONObject(body).getJSONObject("qr_code").getString("card_number")
                        store.putPlain("qr_card", card); store.putPlain("qr_day", today())
                        showQr(card)
                    }
                    status == 401 || status == 403 -> {           // session expired
                        store.del("access_token")
                        val e = store.get("email"); val p = store.get("password")
                        if (e != null && p != null) startAuth(e, p)
                        else showLogin("Session expired — sign in again.")
                    }
                    status == -1 -> retry("No connection.") { fetchQr(token) }
                    else -> retry("Couldn’t fetch (HTTP $status).") { fetchQr(token) }
                }
            }
        }
    }

    private fun showQr(card: String): Unit = ui {
        screen(store.plain("name") ?: "")   // the account holder's name, from login
        keepBright(true)
        val bmp = qr(card, (resources.displayMetrics.widthPixels * 0.8).toInt())
        root.addView(ImageView(this).apply {
            setImageBitmap(bmp)
            layoutParams = LinearLayout.LayoutParams(bmp.width, bmp.height) // 1:1, no blurry rescale
        })
        msg("Valid ${today()}", false)
        button("Refresh") { store.delPlain("qr_day"); route() }
        button("Log out") {
            AlertDialog.Builder(this)
                .setMessage("Log out and remove your login from this phone?")
                .setPositiveButton("Log out") { _, _ -> store.clear(); route() }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun retry(text: String, action: () -> Unit): Unit = ui {
        screen(APP_NAME)
        msg(text, true)
        button("Try again", action)
        cachedToday()?.let { card ->
            button("Show today’s code") { showQr(card) }
        }
    }

    private fun info(text: String): Unit = ui { screen(APP_NAME); msg(text, false) }

    // ------------------------------------------------------------ UI helpers

    /** Reset the screen and add a title (skipped if blank). */
    private fun screen(t: String) {
        keepBright(false)
        root.removeAllViews()
        if (t.isNotBlank()) root.addView(TextView(this).apply {
            text = t; textSize = 22f; gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })
    }

    private fun msg(t: String, err: Boolean): Unit = root.addView(TextView(this).apply {
        text = t; textSize = 14f; gravity = Gravity.CENTER
        setPadding(0, dp(8), 0, dp(8))
        if (err) setTextColor(Color.RED)
    })

    private fun field(hint: String, type: Int) = EditText(this).apply {
        this.hint = hint; inputType = type
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        root.addView(this)
    }

    private fun button(t: String, onClick: () -> Unit): Unit = root.addView(Button(this).apply {
        text = t
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            .apply { topMargin = dp(16) }
    })

    private fun keepBright(on: Boolean) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (on) 1f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun ui(block: () -> Unit): Unit = runOnUiThread(block)
    private fun bg(block: () -> Unit) { Thread(block).start() }

    // ------------------------------------------------------------------- QR

    /** Error correction H, quiet zone 4 — same content your script encodes.
     *  ZXing picks the version/mask itself; a scanner decodes to the exact
     *  same card_number regardless, so it scans identically. */
    private fun qr(data: String, target: Int): Bitmap {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H,
            EncodeHintType.MARGIN to 4
        )
        // Size 0 = smallest possible matrix: exactly 1 pixel per QR square.
        val m = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = m.width
        val px = IntArray(n * n) { i -> if (m.get(i % n, i / n)) Color.BLACK else Color.WHITE }
        val small = Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
        // Whole-number scale with filtering off: every square stays sharp and equal-sized.
        val scale = maxOf(1, target / n)
        return Bitmap.createScaledBitmap(small, n * scale, n * scale, false)
    }

    // ----------------------------------------------------------------- HTTP

    /** Returns (statusCode, body). statusCode -1 means no connection. */
    private fun http(method: String, path: String, body: JSONObject?, auth: String?): Pair<Int, String> {
        val c = URL(BASE + path).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 15000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", userAgent)
            auth?.let { c.setRequestProperty("Authorization", it) }
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status in 200..299) c.inputStream else (c.errorStream ?: c.inputStream)
            status to stream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            -1 to (e.message ?: "network error")
        } finally {
            c.disconnect()
        }
    }
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key) || !has(key)) null else optString(key)

/**
 * SharedPreferences with two kinds of values:
 * - get/put/del: encrypted with an AES-256-GCM key held in the Android Keystore
 *   (e-mail, password, tokens).
 * - plain/putPlain/delPlain: unencrypted, for today's QR code only. It's a
 *   one-day code in app-private storage, and skipping the Keystore here is
 *   what makes the app open instantly.
 */
class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("qr", Context.MODE_PRIVATE)
    fun get(k: String): String? = sp.getString(k, null)?.let { runCatching { Crypto.dec(it) }.getOrNull() }
    fun put(k: String, v: String) = sp.edit().putString(k, Crypto.enc(v)).apply()
    fun del(k: String) = sp.edit().remove(k).apply()

    fun plain(k: String): String? = sp.getString("plain_$k", null)
    fun putPlain(k: String, v: String) = sp.edit().putString("plain_$k", v).apply()
    fun delPlain(k: String) = sp.edit().remove("plain_$k").apply()

    /** Log out: forget everything (login, tokens, cached code, name). */
    fun clear() = sp.edit().clear().apply()
}

private object Crypto {
    private const val KS = "AndroidKeyStore"
    private const val ALIAS = "qr_key"
    private var cached: SecretKey? = null

    /** Load the key from the Keystore once per app start, then reuse it. */
    @Synchronized
    private fun key(): SecretKey = cached ?: loadKey().also { cached = it }

    private fun loadKey(): SecretKey {
        val ks = KeyStore.getInstance(KS).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KS).apply {
            init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun enc(s: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ct = c.doFinal(s.toByteArray(Charsets.UTF_8))
        return b64(c.iv) + ":" + b64(ct)
    }

    fun dec(s: String): String {
        val parts = s.split(":")
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
        }
        return String(c.doFinal(unb64(parts[1])), Charsets.UTF_8)
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
