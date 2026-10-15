package com.example.wifi_led_kitchen

import android.view.View
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private val client = OkHttpClient.Builder() 
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private lateinit var prefs: SharedPreferences
    private var baseUrl = "http://192.168.0.200"

    private lateinit var btnOff: Button
    private lateinit var btnOn: Button
    private lateinit var btnAuto: Button
    private lateinit var btnTimer: Button
    private lateinit var btnRelay: Button
    private lateinit var btnRefresh: Button
    private lateinit var btnSettings: Button
    private lateinit var btnSetThreshold: Button
    private lateinit var etMinutes: EditText
    private lateinit var etThreshold: EditText
    private lateinit var tvStatus: TextView
    private lateinit var tvIpAddress: TextView
    private lateinit var tvLightValue: TextView
    private lateinit var cbLightControl: CheckBox

    private lateinit var indicatorOff: TextView
    private lateinit var indicatorOn: TextView
    private lateinit var indicatorAuto: TextView
    private lateinit var indicatorTimer: TextView
    private lateinit var indicatorRelay: TextView

    private var relayState = false

    companion object {
        private const val PREFS_NAME = "app_prefs"
        private const val KEY_ESP_IP = "esp_ip"
        private const val KEY_LIGHT_CONTROL = "light_control"
        private const val KEY_THRESHOLD = "threshold"
        private const val DEFAULT_IP = "192.168.0.200"
        private const val DEFAULT_THRESHOLD = 512
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSettings()

        initViews()
        setupListeners()
        fetchStatus()
    }

    private fun initViews() {
        btnOff = findViewById(R.id.btnOff)
        btnOn = findViewById(R.id.btnOn)
        btnAuto = findViewById(R.id.btnAuto)
        btnTimer = findViewById(R.id.btnTimer)
        btnRelay = findViewById(R.id.btnRelay)
        btnRefresh = findViewById(R.id.btnRefresh)
        btnSettings = findViewById(R.id.btnSettings)
        btnSetThreshold = findViewById(R.id.btnSetThreshold)
        etMinutes = findViewById(R.id.etMinutes)
        etThreshold = findViewById(R.id.etThreshold)
        tvStatus = findViewById(R.id.tvStatus)
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvLightValue = findViewById(R.id.tvLightValue)
        cbLightControl = findViewById(R.id.cbLightControl)

        indicatorOff = findViewById(R.id.indicatorOff)
        indicatorOn = findViewById(R.id.indicatorOn)
        indicatorAuto = findViewById(R.id.indicatorAuto)
        indicatorTimer = findViewById(R.id.indicatorTimer)
        indicatorRelay = findViewById(R.id.indicatorRelay)

        tvIpAddress.text = "IP: $baseUrl"

        // Восстановить состояние галочки и уставки
        cbLightControl.isChecked = prefs.getBoolean(KEY_LIGHT_CONTROL, true)
        etThreshold.setText(prefs.getInt(KEY_THRESHOLD, DEFAULT_THRESHOLD).toString())
    }

    private fun setupListeners() {
        btnOff.setOnClickListener { sendMode(0) }
        btnOn.setOnClickListener { sendMode(1) }
        btnAuto.setOnClickListener { sendMode(2) }

        btnTimer.setOnClickListener {
            val minutes = etMinutes.text.toString().toIntOrNull() ?: 0
            if (minutes > 0) sendMode(3, minutes)
            else tvStatus.text = "Введите минуты"
        }

        btnRelay.setOnClickListener {
            relayState = !relayState
            sendRelay(if (relayState) 1 else 0)
        }

        btnRefresh.setOnClickListener {
            tvStatus.text = "🔄 Обновление..."
            fetchStatus()
        }

        btnSettings.setOnClickListener {
            showNetworkSettingsDialog()
        }

        // === КНОПКА УСТАНОВКИ ПОРОГА ===
        btnSetThreshold.setOnClickListener {
            sendThreshold()
        }

        // === ГАЛОЧКА КОНТРОЛЯ ОСВЕЩЁННОСТИ ===
        cbLightControl.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(KEY_LIGHT_CONTROL, isChecked).apply()
            if (!isChecked) {
                // Галочка снята → отправляем 0 (движение всегда разрешено)
                sendThresholdValue(0)
            } else {
                // Галочка поставлена → отправляем сохранённую уставку
                val value = etThreshold.text.toString().toIntOrNull() ?: DEFAULT_THRESHOLD
                sendThresholdValue(value)
            }
        }
    }

    // ===== ОТПРАВКА УСТАВКИ ОСВЕЩЁННОСТИ =====
    private fun sendThreshold() {
        if (!cbLightControl.isChecked) {
            sendThresholdValue(0)
            return
        }
        val value = etThreshold.text.toString().toIntOrNull() ?: DEFAULT_THRESHOLD
        if (value in 0..1023) {
            prefs.edit().putInt(KEY_THRESHOLD, value).apply()
            sendThresholdValue(value)
        } else {
            tvStatus.text = "❌ Порог 0..1023"
        }
    }

    private fun sendThresholdValue(value: Int) {
        val request = Request.Builder()
            .url("$baseUrl/threshold?value=$value")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "❌ Ошибка: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                fetchStatus()
            }
        })
    }

    // ===== ЗАГРУЗКА / СОХРАНЕНИЕ IP =====
    private fun loadSettings() {
        val ip = prefs.getString(KEY_ESP_IP, DEFAULT_IP) ?: DEFAULT_IP
        baseUrl = "http://$ip"
    }

    private fun saveIp(ip: String) {
        prefs.edit().putString(KEY_ESP_IP, ip).apply()
        baseUrl = "http://$ip"
        tvIpAddress.text = "IP: $baseUrl"
        tvStatus.text = "✅ IP сохранён: $ip"
    }

    // ===== ОТПРАВКА КОМАНД =====
    private fun sendMode(mode: Int, minutes: Int = 0) {
        val url = if (mode == 3 && minutes > 0) {
            "$baseUrl/mode?mode=$mode&minutes=$minutes"
        } else {
            "$baseUrl/mode?mode=$mode"
        }

        val request = Request.Builder().url(url).build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "❌ Ошибка: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                fetchStatus()
            }
        })
    }

    private fun sendRelay(state: Int) {
        val request = Request.Builder()
            .url("$baseUrl/relay?state=$state")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "❌ Ошибка реле: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.close()
                fetchStatus()
            }
        })
    }

    // ===== ЗАПРОС СТАТУСА =====
    private fun fetchStatus() {
        val request = Request.Builder()
            .url("$baseUrl/status")
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread {
                    tvStatus.text = "❌ Ошибка: ${e.message}"
                }
            }

            override fun onResponse(call: Call, response: Response) {
                val json = response.body?.string()
                response.close()

                json?.let {
                    try {
                        val obj = JSONObject(it)
                        val mode = obj.getInt("mode")
                        val motion = obj.getInt("motion")
                        val remaining = obj.getInt("timer_remaining")
                        val relay = obj.getInt("relay")
                        val light = obj.optInt("light", 0)
                        val threshold = obj.optInt("threshold", DEFAULT_THRESHOLD)

                        runOnUiThread {
                            updateUI(mode, motion, remaining, relay, light, threshold)
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            tvStatus.text = "❌ Ошибка данных"
                        }
                    }
                }
            }
        })
    }

    // ===== ДИАЛОГ НАСТРОЕК СЕТИ =====
    private fun showNetworkSettingsDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle("🌐 Настройки сети ESP")

        val dialogView = layoutInflater.inflate(R.layout.dialog_network_settings, null)
        builder.setView(dialogView)

        val etSsid = dialogView.findViewById<EditText>(R.id.etSsid)
        val etPassword = dialogView.findViewById<EditText>(R.id.etPassword)
        val etIp = dialogView.findViewById<EditText>(R.id.etIp)
        val cbDhcp = dialogView.findViewById<CheckBox>(R.id.cbDhcp)
        val tvIpHint = dialogView.findViewById<TextView>(R.id.tvIpHint)

        val currentIp = baseUrl.replace("http://", "")
        etIp.setText(currentIp)
        cbDhcp.isChecked = true

        cbDhcp.setOnCheckedChangeListener { _, isChecked ->
            etIp.isEnabled = !isChecked
            tvIpHint.visibility = if (isChecked) View.GONE else View.VISIBLE
        }

        builder.setPositiveButton("Сохранить") { _, _ ->
            val ssid = etSsid.text.toString().trim()
            if (ssid.isEmpty()) {
                tvStatus.text = "❌ Введите SSID"
                return@setPositiveButton
            }

            val password = etPassword.text.toString()
            val useDhcp = cbDhcp.isChecked
            val ip = etIp.text.toString().trim()

            if (!useDhcp && ip.isNotEmpty()) {
                saveIp(ip)
            }

            sendNetworkConfig(ssid, password, useDhcp, ip)
        }

        builder.setNegativeButton("Отмена", null)
        builder.create().show()
    }

    // ===== ОТПРАВКА НОВЫХ НАСТРОЕК СЕТИ НА ESP =====
    private fun sendNetworkConfig(
        ssid: String,
        password: String,
        useDhcp: Boolean,
        ip: String
    ) {
        // TODO: реализовать POST-запрос на /save, если нужно
    }

    // ===== ОБНОВЛЕНИЕ UI =====
    private fun updateUI(
        mode: Int, motion: Int, remaining: Int, relay: Int,
        light: Int, threshold: Int
    ) {
        resetIndicators()

        when (mode) {
            0 -> {
                indicatorOff.setTextColor(Color.RED)
                tvStatus.text = "Режим: ВЫКЛ"
            }
            1 -> {
                indicatorOn.setTextColor(Color.GREEN)
                tvStatus.text = "Режим: ВКЛ ПОСТОЯННО"
            }
            2 -> {
                indicatorAuto.setTextColor(Color.BLUE)
                val motionText = if (motion == 1) "ДВИЖЕНИЕ ЕСТЬ" else "ДВИЖЕНИЯ НЕТ"
                tvStatus.text = "Режим: АВТО ($motionText)"
            }
            3 -> {
                indicatorTimer.setTextColor(Color.YELLOW)
                val remainingText = if (remaining > 0) {
                    "Осталось: ${remaining / 60} мин ${remaining % 60} сек"
                } else {
                    "Таймер завершён"
                }
                tvStatus.text = "Режим: ТАЙМЕР ($remainingText)"
            }
        }

        // Индикатор и текст для РЕЛЕ
        if (relay == 1) {
            indicatorRelay.setTextColor(Color.MAGENTA)
            btnRelay.text = "РЕЛЕ ВКЛ"
            btnRelay.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#9C27B0")
                )
            )
        } else {
            indicatorRelay.setTextColor(Color.GRAY)
            btnRelay.text = "РЕЛЕ ВЫКЛ"
            btnRelay.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(
                    Color.parseColor("#607D8B")
                )
            )
        }

        // === ОБНОВЛЕНИЕ ОСВЕЩЁННОСТИ ===
        tvLightValue.text = "☀ $light"

        // Если уставка = 0, значит контроль снят — снимаем галочку
        val isControlEnabled = threshold > 0
        if (cbLightControl.isChecked != isControlEnabled) {
            cbLightControl.setOnCheckedChangeListener(null) // временно отключаем, чтобы не срабатывало
            cbLightControl.isChecked = isControlEnabled
            cbLightControl.setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(KEY_LIGHT_CONTROL, isChecked).apply()
                if (!isChecked) sendThresholdValue(0)
                else sendThresholdValue(etThreshold.text.toString().toIntOrNull() ?: DEFAULT_THRESHOLD)
            }
        }

        // Обновляем поле уставки, если оно пустое или изменилось
        if (threshold > 0 && etThreshold.text.toString() != threshold.toString()) {
            etThreshold.setText(threshold.toString())
        }
    }

    private fun resetIndicators() {
        listOf(indicatorOff, indicatorOn, indicatorAuto, indicatorTimer, indicatorRelay).forEach {
            it.setTextColor(Color.GRAY)
        }
    }
}