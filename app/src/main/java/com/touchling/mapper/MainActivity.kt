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

    // v0.8.0 AI 主题工坊
    private lateinit var etPrompt: EditText
    private lateinit var etBase: EditText
    private lateinit var etKey: EditText
    private lateinit var etModel: EditText
    private lateinit var tvAiStatus: TextView

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

    /** 当前 App 版本号（跟随打包版本，不再手写） */
    private fun appVersion(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    /** 单选行（把 int 选项存进 prefs） */
    private fun optionRow(key: String, def: Int, labels: List<String>): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val btns = mutableListOf<Button>()
        fun paint() {
            val cur = sp.getInt(key, def)
            btns.forEachIndexed { i, b ->
                b.background = GradientDrawable().apply {
                    cornerRadius = dp(10f).toFloat()
                    setColor(if (i == cur) 0xFF111827.toInt() else 0xFFEDEEF2.toInt())
                }
                b.setTextColor(if (i == cur) Color.WHITE else 0xFF374151.toInt())
            }
        }
        labels.forEachIndexed { i, label ->
            val b = Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).apply { rightMargin = dp(6f) }
                setOnClickListener {
                    sp.edit().putInt(key, i).apply()
                    paint()
                    persistCfg()
                }
            }
            btns.add(b)
            row.addView(b)
        }
        paint()
        return row
    }

    /** 开关行（bool 选项） */
    private fun switchRow(label: String, key: String, def: Boolean = false): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4f), 0, dp(4f))
        }
        row.addView(TextView(this).apply {
            text = label
            textSize = 14f
            setTextColor(0xFF374151.toInt())
            layoutParams = LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
            )
        })
        row.addView(android.widget.Switch(this).apply {
            isChecked = sp.getBoolean(key, def)
            setOnCheckedChangeListener { _, v ->
                sp.edit().putBoolean(key, v).apply()
                persistCfg()
            }
        })
        return row
    }

    // ---------- 界面 ----------

    private val AI_SYS = "你是背屏主题生成器。用户会描述想要的主题，你要输出一个完整的单文件 HTML（内联 CSS/JS），" +
            "适配 976x596 的副屏，深色背景、触摸交互友好、不要外部资源。" +
            "只输出 HTML 源码本身，不要 markdown 代码块，不要任何解释文字。" +
            "页面中可用全局对象 TouchLing 调用原生能力：TouchLing.log(msg)、TouchLing.key(code)（3=主页 4=返回 187=多任务）、TouchLing.exec(cmd)。"

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
            text = "小米背屏 → 主屏 · 映射控制台 v${appVersion()}"
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

        // 样式 / 主题卡（v0.6.0）
        val styleCard = card()
        styleCard.addView(sectionTitle("光标样式"))
        styleCard.addView(optionRow("cursorStyle", 0, listOf("圆点", "十字", "箭头", "方框")))
        styleCard.addView(sectionTitle("光标颜色"))
        val colorRow = optionRow("cursorColor", 0, listOf("蓝", "白", "红", "绿", "黄"))
        styleCard.addView(colorRow)
        styleCard.addView(sectionTitle("光标大小"))
        styleCard.addView(optionRow("cursorSizeIdx", 1, listOf("小", "中", "大")))
        styleCard.addView(sectionTitle("背屏显示样式"))
        styleCard.addView(optionRow("rearBg", 0, listOf("镜像", "纯黑", "网格")))
        styleCard.addView(sectionTitle("背屏互动玩具"))
        styleCard.addView(optionRow("toy", 0, listOf("关闭", "转盘", "真心话", "木鱼", "骰子")))
        styleCard.addView(sectionTitle("方向反转（触控板 / 体感）"))
        styleCard.addView(switchRow("左右反转", "invX"))
        styleCard.addView(switchRow("上下反转", "invY"))
        styleCard.addView(TextView(this).apply {
            text = "※ 样式改动在下次「开始映射」时生效"
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(8f), 0, 0)
        })
        root.addView(styleCard)

        // AI 主题工坊（v0.8.0）：用 AI 生成 HTML 主题，背屏用 WebView 渲染
        val aiCard = card()
        aiCard.addView(sectionTitle("AI 主题工坊（HTML）"))
        etPrompt = EditText(this).apply {
            hint = "描述想要的主题，例如：赛博朋克风电子木鱼，带功德计数和霓虹光效"
            textSize = 13f
        }
        etBase = EditText(this).apply {
            hint = "API 地址（默认 OpenAI，可填兼容接口）"
            textSize = 13f
            setText(sp.getString("aiBase", "") ?: "")
        }
        etKey = EditText(this).apply {
            hint = "API Key（留空则用内置示例主题）"
            textSize = 13f
            setText(sp.getString("aiKey", "") ?: "")
        }
        etModel = EditText(this).apply {
            hint = "模型（默认 gpt-4o-mini）"
            textSize = 13f
            setText(sp.getString("aiModel", "") ?: "")
        }
        aiCard.addView(etPrompt)
        aiCard.addView(etBase)
        aiCard.addView(etKey)
        aiCard.addView(etModel)
        aiCard.addView(bigButton("✨ 用 AI 生成主题", 0xFF7C4DFF.toInt()) { aiGenerateTheme() })
        tvAiStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(4f), 0, dp(8f))
        }
        aiCard.addView(tvAiStatus)
        aiCard.addView(sectionTitle("背屏主题渲染（HTML）"))
        aiCard.addView(optionRow("htmlTheme", 0, listOf("关闭", "内置示例", "我的AI主题")))
        root.addView(aiCard)

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

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }
        setContentView(scroll)
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

    // ---------- AI 主题生成（v0.8.0） ----------

    private fun aiGenerateTheme() {
        val desc = etPrompt.text.toString().trim()
        val base = etBase.text.toString().trim().ifBlank { "https://api.openai.com/v1" }
        val key = etKey.text.toString().trim()
        val model = etModel.text.toString().trim().ifBlank { "gpt-4o-mini" }
        sp.edit()
            .putString("aiBase", base)
            .putString("aiKey", key)
            .putString("aiModel", model)
            .apply()
        if (desc.isEmpty()) {
            tvAiStatus.text = "请先描述你想要的主题"
            return
        }
        if (key.isEmpty()) {
            saveTheme(DefaultTheme.HTML)
            sp.edit().putInt("htmlTheme", 2).apply()
            persistCfg()
            tvAiStatus.text = "未填 API Key → 已保存「内置示例主题」，并把背屏主题切到「我的AI主题」"
            return
        }
        tvAiStatus.text = "生成中…（最长 2 分钟，请勿退出）"
        Diag.log("AI 主题生成开始 model=$model base=$base")
        Thread {
            try {
                val body = org.json.JSONObject().apply {
                    put("model", model)
                    put("temperature", 0.8)
                    put(
                        "messages", org.json.JSONArray().apply {
                            put(
                                org.json.JSONObject().apply {
                                    put("role", "system")
                                    put("content", AI_SYS)
                                }
                            )
                            put(
                                org.json.JSONObject().apply {
                                    put("role", "user")
                                    put("content", desc)
                                }
                            )
                        }
                    )
                }
                val conn = java.net.URL("$base/chat/completions")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 120000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer $key")
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val txt = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                if (code !in 200..299) error("HTTP $code ${txt.take(160)}")
                val content = org.json.JSONObject(txt)
                    .getJSONArray("choices").getJSONObject(0)
                    .getJSONObject("message").getString("content")
                val html = extractHtml(content)
                saveTheme(html)
                sp.edit().putInt("htmlTheme", 2).apply()
                Diag.log("AI 主题生成成功 ${html.length} 字符")
                runOnUiThread {
                    tvAiStatus.text = "✅ 已生成并保存（${html.length} 字符）→ 背屏主题已切到「我的AI主题」"
                }
            } catch (t: Throwable) {
                Diag.log("AI 主题生成失败: $t")
                runOnUiThread { tvAiStatus.text = "❌ 生成失败：${t.message?.take(120)}" }
            }
        }.start()
    }

    /** 从模型回复里抠出纯 HTML（去掉 markdown 代码块与多余说明） */
    private fun extractHtml(s: String): String {
        var t = s.trim()
        if (t.startsWith("```")) {
            t = t.removePrefix("```html").removePrefix("```HTML").removePrefix("```")
            val e = t.lastIndexOf("```")
            if (e >= 0) t = t.substring(0, e)
        }
        val h = t.indexOf("<!DOCTYPE", true).let { if (it >= 0) it else t.indexOf("<html", true) }
        if (h > 0) t = t.substring(h)
        return t.trim()
    }

    private fun saveTheme(html: String) {
        try {
            val dir = java.io.File(filesDir, "themes")
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, "ai.html").writeText(html)
        } catch (t: Throwable) {
            Diag.log("保存主题失败: $t")
        }
    }

    private fun startProjection() {
        Diag.log("用户点击 开始映射")
        persistCfg()
        // v0.5.0：触控板/体感模式的光标画在主屏 → 需要悬浮窗权限
        val mode = sp.getString("mode", "direct") ?: "direct"
        val gyroOn = sp.getBoolean("gyro", false)
        if ((mode == "pad" || gyroOn) && !android.provider.Settings.canDrawOverlays(this)) {
            Diag.log("缺悬浮窗权限 → 引导授权")
            Toast.makeText(
                this,
                "请授予「显示在其他应用上层」权限（光标才能显示在主屏），授权后回来再点一次开始映射",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (t: Throwable) {
                Diag.log("打开悬浮窗设置失败: $t")
            }
            return
        }
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