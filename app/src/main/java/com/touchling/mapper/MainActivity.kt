package com.touchling.mapper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    private lateinit var sp: android.content.SharedPreferences
    private lateinit var tvStatus: TextView
    private lateinit var tvDisplay: TextView
    private lateinit var rgMode: RadioGroup
    private lateinit var rgChannel: RadioGroup
    private lateinit var sbSens: SeekBar
    private lateinit var sbMask: SeekBar
    private lateinit var swGyro: android.widget.Switch
    private lateinit var swScroll2: android.widget.Switch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences("cfg", MODE_PRIVATE)
        Diag.init(applicationContext)
        buildUi()
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1003)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        refreshDisplays()
    }

    override fun onPause() {
        super.onPause()
        persistCfg()
    }

    // ---------- UI 组件工厂（卡片化设计） ----------

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val p = dp(18f)
        setPadding(p, p, p, p)
        background = GradientDrawable().apply {
            cornerRadius = dp(20f).toFloat()
            setColor(Color.WHITE)
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = dp(14f)
        layoutParams = lp
    }

    private fun sectionTitle(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 13f
        setTextColor(0xFF8E8E93.toInt())
        setPadding(0, 0, 0, dp(10f))
    }

    private fun bigButton(text: String, bg: Int, click: () -> Unit): Button =
        Button(this).apply {
            this.text = text
            textSize = 16f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dp(14f).toFloat()
                setColor(bg)
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = dp(10f)
            layoutParams = lp
            setOnClickListener { click() }
        }

    // ---------- 界面 ----------

    private fun buildUi() {
        val pad = dp(20f)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xFFF2F3F7.toInt())
        }

        // 头部
        root.addView(TextView(this).apply {
            text = "触灵映射"
            textSize = 26f
            setTextColor(0xFF111827.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(0, dp(6f), 0, dp(2f))
        })
        root.addView(TextView(this).apply {
            text = "小米背屏 → 主屏 · 映射控制台 v0.4.0"
            textSize = 13f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, 0, 0, dp(16f))
        })

        // 状态卡
        val statusCard = card()
        tvStatus = TextView(this).apply {
            textSize = 15f
            setTextColor(0xFF111827.toInt())
        }
        tvDisplay = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(6f), 0, 0)
        }
        statusCard.addView(tvStatus)
        statusCard.addView(tvDisplay)
        root.addView(statusCard)

        // 快捷操作
        val actionCard = card()
        actionCard.addView(sectionTitle("快捷操作"))
        actionCard.addView(bigButton("开始映射", 0xFF34C759.toInt()) { startProjection() })
        actionCard.addView(bigButton("停止映射", 0xFF8E8E93.toInt()) {
            MirrorService.stop(this)
            Toast.makeText(this, "已请求停止", Toast.LENGTH_SHORT).show()
        })
        actionCard.addView(bigButton("授权 Shizuku", 0xFF007AFF.toInt()) {
            try {
                Shizuku.requestPermission(1002)
            } catch (t: Throwable) {
                Toast.makeText(
                    this,
                    "Shizuku 不可用：请先安装 Shizuku 并用无线调试启动它",
                    Toast.LENGTH_LONG
                ).show()
            }
        })
        root.addView(actionCard)

        // 注入通道
        val channelCard = card()
        channelCard.addView(sectionTitle("注入通道"))
        rgChannel = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rgChannel.addView(RadioButton(this).apply { text = "自动"; id = 2001 })
        rgChannel.addView(RadioButton(this).apply { text = "Shizuku"; id = 2002 })
        rgChannel.addView(RadioButton(this).apply { text = "Root"; id = 2003 })
        channelCard.addView(rgChannel)
        rgChannel.check(
            when (sp.getString("channel", "auto")) {
                "shizuku" -> 2002
                "root" -> 2003
                else -> 2001
            }
        )
        root.addView(channelCard)

        // 映射设置
        val setCard = card()
        setCard.addView(sectionTitle("映射模式"))
        rgMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rgMode.addView(RadioButton(this).apply { text = "灵触映射"; id = 1001 })
        rgMode.addView(RadioButton(this).apply { text = "精密触控板"; id = 1002 })
        setCard.addView(rgMode)
        rgMode.check(if (sp.getString("mode", "direct") == "direct") 1001 else 1002)

        setCard.addView(sectionTitle("触控板灵敏度").apply {
            setPadding(0, dp(14f), 0, dp(10f))
        })
        sbSens = SeekBar(this).apply {
            max = 250
            progress = ((sp.getFloat("sens", 1f) - 0.5f) * 100).toInt().coerceIn(0, 250)
        }
        setCard.addView(sbSens)

        setCard.addView(sectionTitle("黑遮罩（防烧屏）").apply {
            setPadding(0, dp(14f), 0, dp(10f))
        })
        sbMask = SeekBar(this).apply {
            max = 80
            progress = sp.getInt("mask", 0)
        }
        setCard.addView(sbMask)
        root.addView(setCard)

        // 特色功能
        val featCard = card()
        featCard.addView(sectionTitle("特色功能"))
        swGyro = android.widget.Switch(this).apply {
            text = "体感空鼠（倾斜手机移光标，轻点=点击）"
            textSize = 14f
            isChecked = sp.getBoolean("gyro", false)
        }
        featCard.addView(swGyro)
        swScroll2 = android.widget.Switch(this).apply {
            text = "双指滚动 / 双指轻点=快捷面板"
            textSize = 14f
            isChecked = sp.getBoolean("scroll2", true)
        }
        featCard.addView(swScroll2)
        root.addView(featCard)

        // 提示
        root.addView(TextView(this).apply {
            text = "首次使用：装 Shizuku（无线调试启动）→ 点「授权 Shizuku」\n" +
                "点「开始映射」→ 同意投屏授权 → 自动处理背屏中心并点亮背屏\n" +
                "灵触=背屏直接操作主屏；触控板=滑动移光标、轻点点击、停 0.4s 拖动\n" +
                "改设置后请先停止再开始"
            textSize = 12f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(dp(4f), dp(4f), dp(4f), dp(20f))
        })

        setContentView(root)
    }

    // ---------- 逻辑 ----------

    private fun persistCfg() {
        sp.edit()
            .putString("mode", if (rgMode.checkedRadioButtonId == 1001) "direct" else "pad")
            .putFloat("sens", 0.5f + sbSens.progress / 100f)
            .putInt("mask", sbMask.progress)
            .putString(
                "channel",
                when (rgChannel.checkedRadioButtonId) {
                    2002 -> "shizuku"
                    2003 -> "root"
                    else -> "auto"
                }
            )
            .putBoolean("gyro", swGyro.isChecked)
            .putBoolean("scroll2", swScroll2.isChecked)
            .apply()
    }

    private fun shizukuState(): String = try {
        // 反射读取，兼容返回类型（Boolean / Int）
        val ping = Shizuku::class.java.getMethod("pingBinder").invoke(null)
        val alive = when (ping) {
            is Boolean -> ping
            is Number -> ping.toInt() != 0
            else -> false
        }
        if (!alive) {
            "未运行/未安装"
        } else {
            val g = Shizuku::class.java.getMethod("checkSelfPermission").invoke(null)
            val granted = when (g) {
                is Boolean -> g
                is Number -> g.toInt() == 0
                else -> false
            }
            if (granted) "已授权" else "未授权"
        }
    } catch (t: Throwable) {
        "不可用"
    }

    private fun refreshStatus() {
        val root = if (Injector.rootAvailable()) "可用" else "不可用"
        tvStatus.text = "Shizuku：${shizukuState()} ｜ Root(su)：$root"
    }

    private fun refreshDisplays() {
        val dm = getSystemService(DisplayManager::class.java)
        val sb = StringBuilder()
        for (d in dm.displays) {
            sb.append("· id=${d.displayId}  ${d.name}  ${d.refreshRate.toInt()}Hz  state=${d.state}\n")
        }
        tvDisplay.text = sb.toString().trimEnd()
    }

    private fun startProjection() {
        Diag.log("用户点击 开始映射")
        persistCfg()
        val channel = sp.getString("channel", "auto") ?: "auto"
        when (channel) {
            "root" -> if (!Injector.rootAvailable()) {
                Toast.makeText(this, "未检测到 su，Root 通道不可用", Toast.LENGTH_LONG).show()
                return
            }
            "shizuku" -> if (shizukuState() != "已授权") {
                Toast.makeText(this, "Shizuku 未授权——仍可镜像，但触摸不会生效", Toast.LENGTH_LONG).show()
            }
            else -> if (shizukuState() != "已授权" && !Injector.rootAvailable()) {
                Toast.makeText(this, "Shizuku 未就绪且无 Root——仍可镜像，但触摸不会生效", Toast.LENGTH_LONG).show()
            }
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        startActivityForResult(mpm.createScreenCaptureIntent(), 1001)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001) {
            Diag.log("投屏授权结果 resultCode=$resultCode")
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, MirrorService::class.java).apply {
                    action = "start"
                    putExtra("resultCode", resultCode)
                    putExtra("resultData", data)
                }
                startForegroundService(i)
            } else {
                Toast.makeText(this, "未授予投屏权限", Toast.LENGTH_SHORT).show()
            }
        }
    }
}