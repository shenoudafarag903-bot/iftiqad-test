package com.iftiqad.app

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.Query
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import java.io.File

class MainActivity : Activity() {

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.parseColor("#070B14")
        window.navigationBarColor = Color.parseColor("#070B14")

        web = WebView(this)
        web.setBackgroundColor(Color.parseColor("#070B14"))
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.addJavascriptInterface(Bridge(), "Android")
        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView, request: WebResourceRequest
            ): WebResourceResponse? = loader.shouldInterceptRequest(request.url)
        }
        setContentView(web)
        val tab = intent.getStringExtra("open_tab") ?: ""
        val hash = if (tab.isNotEmpty()) "#$tab" else ""
        web.loadUrl("file:///android_asset/index.html$hash")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = intent.getStringExtra("open_tab") ?: ""
        if (tab == "today" || tab == "bday" || tab == "alerts") {
            web.evaluateJavascript("window.goTab&&window.goTab('$tab')", null)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::web.isInitialized) {
            web.evaluateJavascript("window.onResumeApp&&window.onResumeApp()", null)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        web.evaluateJavascript(
            "(function(){return window.onBack?window.onBack():false})()"
        ) { r ->
            if (r != "true") finish()
        }
    }

    private fun shareFile(f: File, mime: String, whatsapp: Boolean) {
        val uri = FileProvider.getUriForFile(this, "com.iftiqad.app.fileprovider", f)
        val i = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (whatsapp) {
            for (pkg in arrayOf("com.whatsapp", "com.whatsapp.w4b")) {
                try {
                    i.setPackage(pkg)
                    startActivity(i)
                    return
                } catch (e: ActivityNotFoundException) {
                }
            }
            i.setPackage(null)
        }
        startActivity(Intent.createChooser(i, "مشاركة"))
    }

    inner class Bridge {
        private val adminEmails = listOf("amin@iftiqad.local", "abouna@iftiqad.local")
        private val allSections = listOf("primary", "prep", "women", "general")

        private fun toJ(v: Any?): Any? = when (v) {
            null -> JSONObject.NULL
            is Timestamp -> v.toDate().time
            is Map<*, *> -> JSONObject().also { o -> v.forEach { (k, x) -> o.put(k.toString(), toJ(x)) } }
            is List<*> -> JSONArray().also { a -> v.forEach { a.put(toJ(it)) } }
            else -> v
        }

        private fun sendJs(fn: String, payload: String) {
            runOnUiThread {
                web.evaluateJavascript("window.$fn&&window.$fn(" + JSONObject.quote(payload) + ")", null)
            }
        }

        private fun sendToJs(o: JSONObject) = sendJs("onNativeData", o.toString())

        private fun fsValue(v: Any?): Any? = when (v) {
            null, JSONObject.NULL -> null
            is JSONObject -> if (v.optBoolean("__ts", false)) FieldValue.serverTimestamp() else fsMap(v)
            is JSONArray -> (0 until v.length()).map { fsValue(v.get(it)) }
            is Boolean, is String, is Long, is Double -> v
            is Int -> v.toLong()
            else -> v.toString()
        }

        private fun fsMap(o: JSONObject): Map<String, Any> {
            val m = HashMap<String, Any>()
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val x = fsValue(o.get(k))
                if (x != null) m[k] = x
            }
            return m
        }

        @JavascriptInterface
        fun nativeWrite(json: String) {
            try {
                val db = FirebaseFirestore.getInstance()
                val ops = JSONArray(json)
                val batch = db.batch()
                for (i in 0 until ops.length()) {
                    val o = ops.getJSONObject(i)
                    val ref = db.document(o.getString("path"))
                    val data = o.optJSONObject("data") ?: JSONObject()
                    when (o.getString("op")) {
                        "set" -> batch.set(ref, fsMap(data))
                        "update" -> batch.update(ref, fsMap(data))
                        "delete" -> batch.delete(ref)
                    }
                }
                batch.commit().addOnFailureListener { e -> sendJs("onNativeWriteErr", e.message ?: "") }
            } catch (e: Exception) {
                sendJs("onNativeWriteErr", e.message ?: "")
            }
        }

        @JavascriptInterface
        fun nativeListUsers() {
            FirebaseFirestore.getInstance().collection("users").get()
                .addOnSuccessListener { snap ->
                    val arr = JSONArray()
                    for (d in snap.documents) {
                        val o = (toJ(d.data) as? JSONObject) ?: JSONObject()
                        o.put("uid", d.id)
                        arr.put(o)
                    }
                    sendJs("onNativeUsers", arr.toString())
                }
                .addOnFailureListener { sendJs("onNativeUsers", "null") }
        }

        @JavascriptInterface
        fun nativeCreateServant(email: String, pass: String, name: String, secsJson: String) {
            val secApp = try {
                FirebaseApp.getInstance("sec")
            } catch (e: IllegalStateException) {
                FirebaseApp.initializeApp(applicationContext, FirebaseApp.getInstance().options, "sec")
            }
            val secAuth = FirebaseAuth.getInstance(secApp)
            secAuth.createUserWithEmailAndPassword(email, pass)
                .addOnSuccessListener { r ->
                    val uid = r.user?.uid ?: ""
                    secAuth.signOut()
                    val secs = JSONArray(secsJson)
                    val list = (0 until secs.length()).map { secs.getString(it) }
                    val data = hashMapOf<String, Any>(
                        "name" to name, "login" to email, "sections" to list,
                        "active" to true, "admin" to false, "createdAt" to FieldValue.serverTimestamp()
                    )
                    FirebaseFirestore.getInstance().collection("users").document(uid).set(data)
                        .addOnSuccessListener { sendJs("onNativeServant", JSONObject().put("ok", true).put("code", "").toString()) }
                        .addOnFailureListener { sendJs("onNativeServant", JSONObject().put("ok", false).put("code", "auth/other").toString()) }
                }
                .addOnFailureListener { e ->
                    val c = when (e) {
                        is FirebaseAuthUserCollisionException -> "auth/email-already-in-use"
                        is FirebaseAuthWeakPasswordException -> "auth/weak-password"
                        else -> "auth/other"
                    }
                    sendJs("onNativeServant", JSONObject().put("ok", false).put("code", c).toString())
                }
        }


        private var svReg: ListenerRegistration? = null
        private val alertRegs = ArrayList<ListenerRegistration>()

        @JavascriptInterface
        fun nativeChangePass(oldP: String, newP: String) {
            val u = FirebaseAuth.getInstance().currentUser
            if (u == null) {
                sendJs("onNativePass", JSONObject().put("ok", false).put("code", "nouser").toString())
                return
            }
            u.reauthenticate(EmailAuthProvider.getCredential(u.email ?: "", oldP))
                .addOnSuccessListener {
                    u.updatePassword(newP)
                        .addOnSuccessListener { sendJs("onNativePass", JSONObject().put("ok", true).toString()) }
                        .addOnFailureListener { e -> sendJs("onNativePass", JSONObject().put("ok", false).put("code", e.message ?: "").toString()) }
                }
                .addOnFailureListener { sendJs("onNativePass", JSONObject().put("ok", false).put("code", "wrong").toString()) }
        }

        @JavascriptInterface
        fun nativeListLogs(sec: String) {
            FirebaseFirestore.getInstance().collection("sections").document(sec).collection("logs")
                .orderBy("at", Query.Direction.DESCENDING).limit(100).get()
                .addOnSuccessListener { snap ->
                    val arr = JSONArray()
                    for (d in snap.documents) arr.put(toJ(d.data) as? JSONObject ?: JSONObject())
                    sendJs("onNativeLogs", JSONObject().put("sec", sec).put("list", arr).toString())
                }
                .addOnFailureListener { sendJs("onNativeLogs", JSONObject().put("sec", sec).put("list", JSONArray()).toString()) }
        }

        @JavascriptInterface
        fun nativeListArchive(sec: String) {
            FirebaseFirestore.getInstance().collection("sections").document(sec).collection("archive")
                .orderBy("ts", Query.Direction.DESCENDING).limit(24).get()
                .addOnSuccessListener { snap ->
                    val arr = JSONArray()
                    for (d in snap.documents) {
                        val o = (toJ(d.data) as? JSONObject) ?: JSONObject()
                        o.put("id", d.id)
                        arr.put(o)
                    }
                    sendJs("onNativeArchive", JSONObject().put("sec", sec).put("list", arr).toString())
                }
                .addOnFailureListener { sendJs("onNativeArchive", JSONObject().put("sec", sec).put("list", JSONArray()).toString()) }
        }

        private fun attachAdminAlerts(secs: List<String>, uid: String) {
            alertRegs.forEach { it.remove() }
            alertRegs.clear()
            val since = System.currentTimeMillis()
            val db = FirebaseFirestore.getInstance()
            for (sec in secs) {
                val reg = db.collection("sections").document(sec).collection("logs")
                    .whereEqualTo("action", "delete")
                    .addSnapshotListener { snap, err ->
                        if (err != null || snap == null) return@addSnapshotListener
                        for (ch in snap.documentChanges) {
                            if (ch.type != DocumentChange.Type.ADDED) continue
                            val x = ch.document.data
                            val t = (x["at"] as? Timestamp)?.toDate()?.time ?: 0L
                            if (t < since || x["by"] == uid) continue
                            val byName = x["byName"]?.toString() ?: ""
                            val name = x["serveeName"]?.toString() ?: ""
                            val text = "🗑 $byName حذف $name"
                            val o = JSONObject().put("t", t).put("sec", sec).put("text", text)
                                .put("by", byName).put("servee", x["serveeId"]?.toString() ?: "")
                            sendJs("onNativeAlert", o.toString())
                            Notifier.post(applicationContext, (System.currentTimeMillis() % 1000000000L).toInt(), "🗑 تم حذف مخدوم", text, text, "alerts")
                        }
                    }
                alertRegs.add(reg)
            }
        }

        @JavascriptInterface
        fun nativeCheck() {
            try {
                if (FirebaseAuth.getInstance().currentUser == null) sendJs("onNativeNoUser", "")
                else nativeLoadData()
            } catch (e: Exception) {
                sendJs("onNativeNoUser", "")
            }
        }

        @JavascriptInterface
        fun nativeSignOut() {
            svReg?.remove()
            svReg = null
            alertRegs.forEach { it.remove() }
            alertRegs.clear()
            FirebaseAuth.getInstance().signOut()
            sendJs("onNativeNoUser", "")
        }

        fun nativeLoadData() {
            val user = FirebaseAuth.getInstance().currentUser ?: return
            val db = FirebaseFirestore.getInstance()
            val email = (user.email ?: "").lowercase()
            val isAdmin = adminEmails.contains(email)
            db.collection("users").document(user.uid).get()
                .addOnSuccessListener { ud ->
                    val active = isAdmin || ud.getBoolean("active") != false
                    val secs: List<String> = if (isAdmin) allSections
                        else ((ud.get("sections") as? List<*>)?.map { it.toString() } ?: emptyList())
                    if (!active || secs.isEmpty()) {
                        sendToJs(JSONObject().put("error", "blocked"))
                        return@addOnSuccessListener
                    }
                    val me = JSONObject()
                        .put("uid", user.uid)
                        .put("email", email)
                        .put("login", email.removeSuffix("@iftiqad.local"))
                        .put("name", ud.getString("name") ?: email)
                        .put("isAdmin", isAdmin)
                        .put("active", true)
                        .put("sections", JSONArray(secs))
                    if (isAdmin) attachAdminAlerts(secs, user.uid)
                    svReg?.remove()
                    svReg = db.collection("servees").whereIn("section", secs).addSnapshotListener { snap, err ->
                        if (err != null || snap == null) {
                            sendToJs(JSONObject().put("error", "data"))
                            return@addSnapshotListener
                        }
                        val arr = JSONArray()
                        for (d in snap.documents) {
                            val o = (toJ(d.data) as? JSONObject) ?: JSONObject()
                            o.put("id", d.id)
                            arr.put(o)
                        }
                        sendToJs(JSONObject().put("me", me).put("servees", arr))
                    }
                }
                .addOnFailureListener { sendToJs(JSONObject().put("error", "data")) }
        }

        @JavascriptInterface
        fun nativeLogin(email: String, pass: String) {
            FirebaseAuth.getInstance().signInWithEmailAndPassword(email, pass)
                .addOnSuccessListener { r ->
                    val who = r.user?.email ?: email
                    runOnUiThread {
                        web.evaluateJavascript("window.onNativeLogin&&window.onNativeLogin(true,'" + who + "','')", null)
                    }
                    nativeLoadData()
                }
                .addOnFailureListener { e ->
                    val code = (e as? com.google.firebase.auth.FirebaseAuthException)?.errorCode ?: "unknown"
                    val msg = (e.message ?: "").replace("'", "").replace("\n", " ")
                    runOnUiThread {
                        web.evaluateJavascript("window.onNativeLogin&&window.onNativeLogin(false,'" + code + "','" + msg + "')", null)
                    }
                }
        }

        @JavascriptInterface
        fun scheduleVisit(id: String, time: String, name: String, phone: String, lead: String) {
            try {
                AlarmStore.scheduleVisit(
                    applicationContext, id.toInt(), time.toLong(), name, "", phone,
                    lead.toIntOrNull() ?: 0
                )
            } catch (e: Exception) {
                toast("تعذر ضبط التنبيه")
            }
        }

        @JavascriptInterface
        fun cancelAlarm(id: String) {
            try { AlarmStore.cancelVisit(applicationContext, id.toInt()) } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun cancelAllAlarms() {
            try { AlarmStore.cancelAll(applicationContext) } catch (e: Exception) {}
        }

        @JavascriptInterface
        fun setDaily(on: String, hour: String, minute: String) {
            try {
                AlarmStore.setDaily(
                    applicationContext, on == "1", hour.toIntOrNull() ?: 8, minute.toIntOrNull() ?: 0
                )
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun setBirthdays(json: String, on: String, pre: String, hour: String, minute: String) {
            try {
                AlarmStore.setBirthdays(
                    applicationContext, json, on == "1", pre == "1",
                    hour.toIntOrNull() ?: 9, minute.toIntOrNull() ?: 0
                )
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun notifyNow(title: String, text: String) {
            try {
                Notifier.post(
                    applicationContext, (System.currentTimeMillis() % 1000000000L).toInt() + 5,
                    title, text, text, "alerts"
                )
            } catch (e: Exception) {
            }
        }

        @JavascriptInterface
        fun getStatus(): String {
            return try {
                val notif = NotificationManagerCompat.from(applicationContext).areNotificationsEnabled()
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                val bat = pm.isIgnoringBatteryOptimizations(packageName)
                "{\"notif\":$notif,\"battery\":$bat}"
            } catch (e: Exception) {
                "{\"notif\":true,\"battery\":false}"
            }
        }

        @JavascriptInterface
        fun openNotifSettings() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    )
                } catch (e: Exception) {
                    toast("افتح إعدادات الهاتف ← التطبيقات ← تطبيق افتقاد ← الإشعارات")
                }
            }
        }

        @JavascriptInterface
        fun openBatterySettings() {
            runOnUiThread {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    try {
                        startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    } catch (e2: Exception) {
                        toast("افتح إعدادات الهاتف ← البطارية ← تطبيق افتقاد ← بدون قيود")
                    }
                }
            }
        }

        @JavascriptInterface
        fun testNotify() {
            try {
                AlarmStore.scheduleTest(applicationContext)
            } catch (e: Exception) {
                toast("تعذر ضبط التجربة")
            }
        }

        @JavascriptInterface
        fun dial(phone: String) {
            try {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
            } catch (e: Exception) {
                toast("تعذر فتح الاتصال")
            }
        }

        @JavascriptInterface
        fun openUrl(url: String) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                toast("تعذر فتح الرابط")
            }
        }

        @JavascriptInterface
        fun sharePdf(json: String, fileName: String, target: String) {
            try {
                val f = PdfBuilder.build(applicationContext, json, fileName)
                runOnUiThread {
                    try {
                        shareFile(f, "application/pdf", target == "wa")
                    } catch (e: Exception) {
                        toast("تعذرت المشاركة")
                    }
                }
            } catch (e: Exception) {
                toast("تعذر إنشاء الملف: ${e.message}")
            }
        }

        private fun toast(m: String) {
            runOnUiThread { Toast.makeText(this@MainActivity, m, Toast.LENGTH_LONG).show() }
        }
    }
}
