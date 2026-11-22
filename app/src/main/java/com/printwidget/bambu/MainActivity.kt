package com.printwidget.bambu

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.Spinner
import android.widget.TextView
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var store: Store
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Set while waiting for an e-mailed code or a two-factor code. */
    private var pendingTfaKey: String? = null

    private lateinit var signInSection: View
    private lateinit var codeSection: View
    private lateinit var signedInSection: View
    private lateinit var email: EditText
    private lateinit var password: EditText
    private lateinit var code: EditText
    private lateinit var codeHint: TextView
    private lateinit var status: TextView
    private lateinit var account: TextView
    private lateinit var printerSpinner: Spinner
    private lateinit var current: TextView
    private lateinit var addWidget: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        signInSection = findViewById(R.id.sign_in_section)
        codeSection = findViewById(R.id.code_section)
        signedInSection = findViewById(R.id.signed_in_section)
        email = findViewById(R.id.email)
        password = findViewById(R.id.password)
        code = findViewById(R.id.code)
        codeHint = findViewById(R.id.code_hint)
        status = findViewById(R.id.status)
        account = findViewById(R.id.account)
        printerSpinner = findViewById(R.id.printer_spinner)
        current = findViewById(R.id.current)
        addWidget = findViewById(R.id.add_widget)

        email.setText(store.email)
        if (store.region == Region.CHINA) findViewById<RadioButton>(R.id.region_china).isChecked = true

        findViewById<Button>(R.id.sign_in).setOnClickListener { signIn() }
        findViewById<Button>(R.id.verify).setOnClickListener { verifyCode() }
        findViewById<Button>(R.id.cancel_code).setOnClickListener { pendingTfaKey = null; showSection() }
        findViewById<Button>(R.id.refresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.sign_out).setOnClickListener {
            store.signOut()
            PrintWidgetProvider.updateAll(this)
            showSection()
        }
        addWidget.setOnClickListener { requestPinWidget() }

        showSection()
        if (store.isSignedIn) refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun selectedRegion(): Region =
        if (findViewById<RadioButton>(R.id.region_china).isChecked) Region.CHINA else Region.GLOBAL

    private fun showSection(waitingForCode: Boolean = false) {
        val signedIn = store.isSignedIn
        signInSection.visibility = if (!signedIn && !waitingForCode) View.VISIBLE else View.GONE
        codeSection.visibility = if (waitingForCode) View.VISIBLE else View.GONE
        signedInSection.visibility = if (signedIn && !waitingForCode) View.VISIBLE else View.GONE
        if (signedIn) {
            account.text = getString(R.string.signed_in_as, store.email)
            bindPrinters()
        }
        if (!getSystemService(AppWidgetManager::class.java).isRequestPinAppWidgetSupported) {
            addWidget.visibility = View.GONE
        }
    }

    private fun setBusy(message: String?) {
        status.text = message.orEmpty()
        status.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun signIn() {
        val mail = email.text.toString().trim()
        val pw = password.text.toString()
        if (mail.isEmpty() || pw.isEmpty()) {
            setBusy(getString(R.string.err_missing_fields)); return
        }
        val region = selectedRegion()
        store.region = region
        store.email = mail
        setBusy(getString(R.string.signing_in))
        io.execute {
            try {
                when (val r = BambuCloud(region).login(mail, pw)) {
                    is LoginResult.Success -> finishSignIn(r.token)
                    LoginResult.NeedsEmailCode -> main.post {
                        pendingTfaKey = null
                        codeHint.text = getString(R.string.code_hint_email, mail)
                        setBusy(null); showSection(waitingForCode = true)
                    }
                    is LoginResult.NeedsTfa -> main.post {
                        pendingTfaKey = r.tfaKey
                        codeHint.text = getString(R.string.code_hint_tfa)
                        setBusy(null); showSection(waitingForCode = true)
                    }
                }
            } catch (e: Exception) {
                main.post { setBusy(e.message ?: getString(R.string.err_generic)) }
            }
        }
    }

    private fun verifyCode() {
        val c = code.text.toString().trim()
        if (c.isEmpty()) return
        val region = store.region
        val tfa = pendingTfaKey
        setBusy(getString(R.string.signing_in))
        io.execute {
            try {
                val cloud = BambuCloud(region)
                val token = if (tfa != null) cloud.loginWithTfa(tfa, c) else cloud.loginWithEmailCode(store.email, c)
                finishSignIn(token)
            } catch (e: Exception) {
                main.post { setBusy(e.message ?: getString(R.string.err_generic)) }
            }
        }
    }

    /** Runs on the background thread. */
    private fun finishSignIn(token: String) {
        val cloud = BambuCloud(store.region)
        val uid = cloud.fetchUserId(token)
        store.token = token
        store.userId = uid
        store.lastError = ""
        store.printers = cloud.fetchPrinters(token)
        main.post {
            pendingTfaKey = null
            password.setText("")
            code.setText("")
            setBusy(null)
            showSection()
            RefreshWorker.schedulePeriodic(this)
            refresh()
        }
    }

    private fun bindPrinters() {
        val printers = store.printers
        val labels = listOf(getString(R.string.auto_printer)) + printers.map { it.name }
        printerSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = printers.indexOfFirst { it.serial == store.selectedSerial }
        printerSpinner.setSelection(if (index >= 0) index + 1 else 0)
        printerSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val serial = if (position == 0) "" else printers.getOrNull(position - 1)?.serial.orEmpty()
                if (serial != store.selectedSerial) {
                    store.selectedSerial = serial
                    PrintWidgetProvider.updateAll(this@MainActivity)
                    showCurrent()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        showCurrent()
    }

    private fun showCurrent() {
        val s = store.statusToShow()
        current.text = when {
            store.lastError.isNotEmpty() -> store.lastError
            s == null -> getString(R.string.no_printers)
            s.isActive -> getString(
                R.string.current_active, s.printerName, s.jobName,
                formatMinutes(s.remainingMinutes), s.percent,
            )
            else -> "${s.printerName}: ${PrintWidgetProvider.stateLabel(this, s.state)}"
        }
    }

    /** Refreshes in the foreground so the screen shows the result straight away. */
    private fun refresh() {
        setBusy(getString(R.string.refreshing))
        io.execute {
            try {
                val cloud = BambuCloud(store.region)
                val printers = cloud.fetchPrinters(store.token)
                store.printers = printers
                store.statuses = StatusFetcher(store.region, store.userId, store.token).fetch(printers)
                store.lastError = ""
            } catch (e: AuthExpiredException) {
                store.lastError = e.message.orEmpty()
            } catch (e: BambuApiException) {
                store.lastError = e.message.orEmpty()
            } catch (e: Exception) {
                store.lastError = getString(R.string.err_generic) + " (" + e.javaClass.simpleName + ")"
            }
            main.post {
                if (isDestroyed) return@post
                setBusy(null)
                PrintWidgetProvider.updateAll(this)
                showSection()
                if (store.statuses.any { it.isActive }) RefreshWorker.refreshNow(this)
            }
        }
    }

    private fun requestPinWidget() {
        val mgr = getSystemService(AppWidgetManager::class.java)
        mgr.requestPinAppWidget(ComponentName(this, PrintWidgetProvider::class.java), null, null)
    }

    companion object {
        fun formatMinutes(m: Int): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
    }
}
