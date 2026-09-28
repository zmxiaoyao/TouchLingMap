package com.touchling.mapper

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sp = getSharedPreferences("cfg", MODE_PRIVATE)
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

    private fun density() = resources.displayMetrics.density

    private fun label(s: String): TextView = TextView(this).apply {
        text = s
        textSize = 14f
        setPadding(0, (16 * density()).toInt(), 0, (6 * density()).toInt())
    }

    private fun buildUi() {
        val pad = (20 * density()).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = "触灵映射（背屏 → 主屏）"
            textSize = 20f
        })

        tvStatus = TextView(this).apply {
            textSize = 16f
            setPadding(0, (12 * density()).toInt(), 0, 0)
        }
        tvDisplay = TextView(this).apply { textSize = 13f }
        root.addView(tvStatus)
        root.addView(tvDisplay)

        // 注入通道选择
        root.addView(label("注入通道："))
        rgChannel = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rgChannel.addView(RadioButton(this).apply { text = "自动"; id = 2001 })
        rgChannel.addView(RadioButton(this).apply { text = "Shizuku"; id = 2002 })
        rgChannel.addView(RadioButton(this).apply { text = "Root"; id = 2003 })
        root.addView(rgChannel)
        rgChannel.check(
            when (sp.getString("channel", "auto")) {
                "shizuku" -> 2002
                "root" -> 2003
                else -> 2001
            }
        )

        root.addView(Button(this).apply {
            text = "授权 Shizuku"
            setOnClickListener {
                try {
                    Shizuku.requestPermission(1002)
                } catch (t: Throwable) {
                    Toast.makeText(
                        context,
                        "Shizuku 不可用：请先安装 Shizuku 并用无线调试启动它",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        })

        root.addView(Button(this).apply {
            text = "开始映射"
            setOnClickListener { startProjection() }
        })

        root.addView(Button(this).apply {
            text = "停止映射"
            setOnClickListener {
                MirrorService.stop(this@MainActivity)
                Toast.makeText(this@MainActivity, "已请求停止", Toast.LENGTH_SHORT).show()
            }
        })

        root.addView(label("模式："))
        rgMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rgMode.addView(RadioButton(this).apply { text = "灵触映射"; id = 1001 })
        rgMode.addView(RadioButton(this).apply { text = "精密触控板"; id = 1002 })
        root.addView(rgMode)
        rgMode.check(if (sp.getString("mode", "direct") == "direct") 1001 else 1002)

        root.addView(label("触控板灵敏度（0.5x ~ 3x）"))
        sbSens = SeekBar(this).apply {
            max = 250
            progress = ((sp.getFloat("sens", 1f) - 0.5f) * 100).toInt().coerceIn(0, 250)
        }
        root.addView(sbSens)

        root.addView(label("黑遮罩（防烧屏，盖在背屏画面上的半透明黑层，0 = 不盖）"))
        sbMask = SeekBar(this).apply {
            max = 80
            progress = sp.getInt("mask", 0)
        }
        root.addView(sbMask)

        root.addView(label(
            "使用说明：\n" +
                "1. Root 用户：注入通道选「Root」或「自动」，首次会弹 Root 授权，同意即可\n" +
                "2. 无 Root：装 Shizuku（无线调试启动）→ 点“授权 Shizuku”\n" +
                "3. 点“开始映射”→ 同意投屏授权（仅取主屏画面，不录制文件）\n" +
                "4. 灵触模式：背屏 = 压缩版主屏，直接点按滑动\n" +
                "5. 触控板模式：滑动 = 移光标，轻点 = 点击，停 0.4 秒拖动 = 拖拽\n" +
                "6. 修改设置后请先“停止”再“开始”"
        ))

        setContentView(root)
    }

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
            .apply()
    }

    private fun shizukuState(): String = try {
        // 反射读取，兼容不同版本的返回类型（Boolean / Int）
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
            // PERMISSION_GRANTED = 0（若返回 Boolean 则 true 为已授权）
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
            sb.append("· id=${d.displayId}  ${d.name}  ${d.refreshRate.toInt()}Hz\n")
        }
        tvDisplay.text = "显示屏：\n$sb"
    }

    private fun startProjection() {
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