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

    // v0.9.0 分页 + 导航
    private lateinit var pageHost: FrameLayout
    private val pageViews = mutableListOf<View>()
    private val navItems = mutableListOf<LinearLayout>()
    private val navTvs = mutableListOf<Pair<TextView, TextView>>()
    private var currentPage = -1

    // v1.1.0 优化
    private lateinit var tvSummary: TextView
    private lateinit var themeListBox: LinearLayout
    // v2.0.0 手感参数
    private lateinit var sbSmooth: SeekBar
    private lateinit var sbDead: SeekBar
    private lateinit var sbCursorDp: SeekBar
    private lateinit var swNoMirror: android.widget.Switch
    private lateinit var tvRearDev: TextView
    private lateinit var rearDevBox: LinearLayout
    // v2.2.2 开始/停止一键切换
    private var btnStart: Button? = null
    private var lastStartClick = 0L
    private val uiTick = android.os.Handler(android.os.Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            refreshStatus()
            uiTick.postDelayed(this, 1000)
        }
    }

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
        refreshRearDevices()
        updateSummary()
        refreshThemeList()
        uiTick.removeCallbacks(tick)
        uiTick.post(tick)
    }

    /** v2.2.0：选择「自动启停」白名单应用 */
    private fun pickApps() {
        try {
            val pm = packageManager
            val apps = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
                .sortedBy { it.loadLabel(pm).toString() }
            val labels = apps.map { it.loadLabel(pm).toString() }.toTypedArray()
            val sel = BooleanArray(apps.size)
            val cur = (sp.getString("autoApps", "") ?: "")
                .split(",").map { it.trim() }.filter { it.isNotEmpty() }
            apps.forEachIndexed { i, a -> sel[i] = cur.contains(a.packageName) }
            android.app.AlertDialog.Builder(this)
                .setTitle("选择自动启停的应用")
                .setMultiChoiceItems(labels, sel) { _, i, checked -> sel[i] = checked }
                .setPositiveButton("保存") { _, _ ->
                    val chosen = apps.filterIndexed { i, _ -> sel[i] }
                        .map { it.packageName }
                    sp.edit().putString("autoApps", chosen.joinToString(",")).apply()
                    Toast.makeText(this, "已保存 ${chosen.size} 个应用", Toast.LENGTH_SHORT).show()
                    refreshStatus()
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (t: Throwable) {
            Toast.makeText(this, "列表失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** v2.0.0：列出所有 display，可手动指定背屏 */
    private fun refreshRearDevices() {
        if (!::rearDevBox.isInitialized) return
        val box = rearDevBox
        box.removeAllViews()
        val dm = getSystemService(DisplayManager::class.java)
        val cur = sp.getInt("rearDisplayId", -1)
        val sb = StringBuilder()
        dm.displays.forEach { d ->
            sb.append("· #").append(d.displayId).append(' ')
                .append(d.name).append(' ')
                .append('(').append(d.mode.physicalWidth).append('×')
                .append(d.mode.physicalHeight).append(") ")
                .append(if (d.state == android.view.Display.STATE_ON) "已点亮" else "未点亮")
                .append('\n')
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4f), 0, dp(4f))
            }
            row.addView(TextView(this).apply {
                text = (if (d.displayId == cur) "✅ " else "🖥 ") +
                        "显示 ${d.displayId}（${d.mode.physicalWidth}×${d.mode.physicalHeight}）"
                textSize = 12f
                setTextColor(0xFF374151.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(smallBtn("设为背屏") {
                sp.edit().putInt("rearDisplayId", d.displayId).apply()
                persistCfg()
                refreshRearDevices()
                Toast.makeText(this, "已指定显示 ${d.displayId} 为背屏", Toast.LENGTH_SHORT).show()
            })
            box.addView(row)
        }
        box.addView(smallBtn("恢复自动识别") {
            sp.edit().putInt("rearDisplayId", -1).apply()
            refreshRearDevices()
        })
        tvRearDev.text = sb.toString().trimEnd() +
            "\n当前背屏：" + (if (cur < 0) "自动识别" else "显示 #$cur")
    }

    override fun onPause() {
        super.onPause()
        uiTick.removeCallbacks(tick)
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

    private val AI_SYS = "你是背屏主题生成器。用户会描述想要的主题，你要输出一个完整的单文件 HTML（内联 CSS/JS）。" +
            "【尺寸硬性要求】适配 976x596 的副屏：html/body 必须是 width:100%;height:100%;margin:0;padding:0;overflow:hidden；" +
            "所有尺寸用相对单位（%/vw/vh/flex），禁止写死大像素宽度（如 width:1200px）；内容必须完全在一屏内、不出现滚动条；" +
            "字体大小控制在 14~90px 之间，布局优先用 flex 居中。" +
            "深色背景、触摸交互友好、不要引用任何外部资源（图片用 emoji 或 CSS 绘制）。" +
            "只输出 HTML 源码本身，不要 markdown 代码块，不要任何解释文字。" +
            "页面中可用全局对象 TouchLing 调用原生能力：TouchLing.log(msg)、TouchLing.key(code)（3=主页 4=返回 187=多任务）、TouchLing.exec(cmd)。"

    private fun buildUi() {
        val pad = dp(20f)
        // v0.9.0：四个分页的内容容器（底部导航切换，不再是一条长滚动）
        val page1 = pageContainer(pad)
        val page2 = pageContainer(pad)
        val page3 = pageContainer(pad)
        val page4 = pageContainer(pad)
        val page5 = pageContainer(pad) // v2.0.0 手感

        // 状态卡
        page1.addView(pageTitle("控制"))
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
        page1.addView(statusCard)

        // 当前配置摘要（v1.1.0）
        val sumCard = card()
        sumCard.addView(sectionTitle("当前配置"))
        tvSummary = TextView(this).apply {
            textSize = 13f
            setTextColor(0xFF374151.toInt())
            setLineSpacing(dp(3f).toFloat(), 1f)
        }
        sumCard.addView(tvSummary)
        page1.addView(sumCard)

        // 快捷操作
        val actionCard = card()
        actionCard.addView(sectionTitle("快捷操作"))
        swNoMirror = android.widget.Switch(this).apply {
            text = "免投屏触控（背屏黑屏当触控板 · 不申请屏幕权限）"
            textSize = 14f
            isChecked = sp.getBoolean("noMirror", true)
            setOnCheckedChangeListener { _, v -> sp.edit().putBoolean("noMirror", v).apply() }
        }
        actionCard.addView(swNoMirror)
        btnStart = bigButton("▶ 开始映射", 0xFF34C759.toInt()) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastStartClick < 1200) return@bigButton
            lastStartClick = now
            if (MirrorService.running) {
                MirrorService.stop(this)
                Toast.makeText(this, "已请求停止映射", Toast.LENGTH_SHORT).show()
            } else {
                startProjection()
            }
        }
        actionCard.addView(btnStart!!)
        actionCard.addView(bigButton("停止映射", 0xFF8E8E93.toInt()) {
            MirrorService.stop(this)
            Toast.makeText(this, "已请求停止", Toast.LENGTH_SHORT).show()
        })
        actionCard.addView(bigButton("🖼 只显示主题到背屏", 0xFF0A84FF.toInt()) { startDisplayOnly() })
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
        page1.addView(actionCard)

        // 样式 / 主题卡（v0.6.0）
        val styleCard = card()
        styleCard.addView(sectionTitle("光标样式"))
        styleCard.addView(optionRow("cursorStyle", 2, listOf("圆点", "十字", "箭头", "方框")))
        styleCard.addView(sectionTitle("光标颜色"))
        val colorRow = optionRow("cursorColor", 0, listOf("蓝", "白", "红", "绿", "黄"))
        styleCard.addView(colorRow)
        styleCard.addView(TextView(this).apply {
            text = "※ 光标大小 / 方向反转 / 灵敏度已移到「🎮 手感」页"
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(8f), 0, 0)
        })
        page4.addView(pageTitle("设置"))
        page4.addView(styleCard)

        // AI 主题工坊（v0.8.0）：用 AI 生成 HTML 主题，背屏用 WebView 渲染
        page3.addView(pageTitle("AI 工坊"))
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
        aiCard.addView(sectionTitle("已保存的主题"))
        themeListBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        aiCard.addView(themeListBox)
        page3.addView(aiCard)

        // 背屏内容卡（v1.0.0）：显示什么 + 一键只上屏（不开启触摸映射）
        page2.addView(pageTitle("背屏内容"))
        val contentCard = card()
        contentCard.addView(sectionTitle("背屏显示什么（HTML 主题）"))
        contentCard.addView(
            optionRow("htmlTheme", 0, listOf("关闭", "木鱼", "时钟", "转盘", "我的AI"))
        )
        contentCard.addView(sectionTitle("内置互动玩具（原生）"))
        contentCard.addView(
            optionRow("toy", 0, listOf("关闭", "转盘", "真心话", "木鱼", "骰子"))
        )
        contentCard.addView(sectionTitle("背屏底色样式"))
        contentCard.addView(optionRow("rearBg", 0, listOf("镜像", "纯黑", "网格")))
        contentCard.addView(bigButton("🖼 只把主题/玩具显示到背屏", 0xFF0A84FF.toInt()) {
            startDisplayOnly()
        })
        contentCard.addView(TextView(this).apply {
            text = "※ 这个按钮只把内容投到背屏，不开启触摸映射（不会启动灵触/触控板）"
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(2f), 0, 0)
        })
        page2.addView(contentCard)

        // 🎮 手感页（v2.0.0，参数参考「妙妙背屏」）
        page5.addView(pageTitle("手感"))
        val handCard = card()
        val tvSensL = sectionTitle("触控板速度")
        sbSens = SeekBar(this).apply {
            max = 250
            progress = ((sp.getFloat("sens", 1f) - 0.5f) * 100).toInt().coerceIn(0, 250)
        }
        handCard.addView(tvSensL)
        handCard.addView(sbSens)
        bindLabel(tvSensL, sbSens) { "触控板速度 ${fmt1(0.5f + it / 100f)}×" }

        val tvSmoothL = sectionTitle("平滑").apply { setPadding(0, dp(12f), 0, dp(6f)) }
        sbSmooth = SeekBar(this).apply {
            max = 300
            progress = sp.getInt("smoothMs", 0).coerceIn(0, 300)
        }
        handCard.addView(tvSmoothL)
        handCard.addView(sbSmooth)
        bindLabel(tvSmoothL, sbSmooth) { "平滑（低通滤波）$it ms" }

        val tvDeadL = sectionTitle("死区").apply { setPadding(0, dp(12f), 0, dp(6f)) }
        sbDead = SeekBar(this).apply {
            max = 200
            progress = (sp.getFloat("deadZone", 0.02f) * 100).toInt().coerceIn(0, 200)
        }
        handCard.addView(tvDeadL)
        handCard.addView(sbDead)
        bindLabel(
            tvDeadL, sbDead
        ) { "陀螺仪死区 ${String.format(java.util.Locale.US, "%.2f", it / 100f)} rad/s" }

        val tvCursorL = sectionTitle("光标大小").apply { setPadding(0, dp(12f), 0, dp(6f)) }
        sbCursorDp = SeekBar(this).apply {
            max = 48
            progress = (sp.getInt("cursorDp", 26) - 16).coerceIn(0, 48)
        }
        handCard.addView(tvCursorL)
        handCard.addView(sbCursorDp)
        bindLabel(tvCursorL, sbCursorDp) { "光标大小 ${16 + it} dp" }

        handCard.addView(sectionTitle("方向反转").apply { setPadding(0, dp(12f), 0, dp(4f)) })
        handCard.addView(switchRow("光标 X 反转", "invX", true))
        handCard.addView(switchRow("光标 Y 反转", "invY"))

        handCard.addView(sectionTitle("工具").apply { setPadding(0, dp(12f), 0, dp(4f)) })
        handCard.addView(bigButton("🎯 光标回中", 0xFF374151.toInt()) {
            val dm = resources.displayMetrics
            MirrorService.mainCursor?.center(dm.widthPixels / 2f, dm.heightPixels / 2f)
            Toast.makeText(this, "光标已回中（运行中生效）", Toast.LENGTH_SHORT).show()
        })
        handCard.addView(bigButton("🧭 陀螺仪校准（平放手机）", 0xFF6B7280.toInt()) { calibrateGyro() })

        handCard.addView(sectionTitle("背屏方向").apply { setPadding(0, dp(12f), 0, dp(6f)) })
        handCard.addView(optionRow("rearRot", 0, listOf("0°", "90°", "180°", "270°")))
        handCard.addView(TextView(this).apply {
            text = "※ 平滑越大越稳但更「粘手」；死区越大越不容易漂移。改动在下次启动后生效。"
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
            setPadding(0, dp(8f), 0, 0)
        })
        page5.addView(handCard)

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
        page4.addView(channelCard)

        // 映射设置
        val setCard = card()
        setCard.addView(sectionTitle("映射模式"))
        rgMode = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        rgMode.addView(RadioButton(this).apply { text = "灵触映射"; id = 1001 })
        rgMode.addView(RadioButton(this).apply { text = "体感光标"; id = 1003 })
        rgMode.addView(RadioButton(this).apply { text = "精密触控板"; id = 1002 })
        setCard.addView(rgMode)
        rgMode.check(
            when (sp.getString("mode", "direct")) {
                "pad" -> 1002
                "gyro" -> 1003
                else -> 1001
            }
        )

        val tvMaskL = sectionTitle("黑遮罩（防烧屏）").apply {
            setPadding(0, dp(14f), 0, dp(10f))
        }
        sbMask = SeekBar(this).apply {
            max = 80
            progress = sp.getInt("mask", 0)
        }
        setCard.addView(tvMaskL)
        setCard.addView(sbMask)
        bindLabel(tvMaskL, sbMask) { "黑遮罩（防烧屏）$it%" }
        page4.addView(setCard)

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
        // v2.1.0：选「体感光标」模式时自动点亮体感开关
        rgMode.setOnCheckedChangeListener { _, id ->
            if (id == 1003) {
                swGyro.isChecked = true
                sp.edit().putBoolean("gyro", true).apply()
            }
        }
        page4.addView(featCard)

        // 背屏设备选择（v2.0.0，参考妙妙「背屏设备」）
        val devCard = card()
        devCard.addView(sectionTitle("背屏设备"))
        tvRearDev = TextView(this).apply {
            textSize = 12f
            setTextColor(0xFF374151.toInt())
        }
        devCard.addView(tvRearDev)
        rearDevBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        devCard.addView(rearDevBox)
        devCard.addView(bigButton("🔍 检测 / 刷新设备", 0xFF4B5563.toInt()) {
            refreshDisplays()
            refreshRearDevices()
            Toast.makeText(this, "已刷新", Toast.LENGTH_SHORT).show()
        })
        devCard.addView(TextView(this).apply {
            text = "※ 默认识别「非主屏」为背屏；如识别不准可在此手动指定。触摸设备：\n" +
                "/dev/input/event6 · Xiaomi_Touch_Input_1 (97599×59599)"
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
        })
        page4.addView(devCard)

        // v2.2.0 自动化与快捷
        val autoCard = card()
        autoCard.addView(sectionTitle("触摸输入源"))
        autoCard.addView(optionRow("evdev", 1, listOf("背屏视图", "evdev 直读（推荐）")))
        autoCard.addView(TextView(this).apply {
            text = "※ evdev 直读：内核层读背屏触摸；镜像 / 免投屏 都可用，" +
                "灵触映射 / 精密触控板 都支持（体感光标暂不支持）；背屏视图只做防误触"
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
        })

        autoCard.addView(sectionTitle("开机自启").apply { setPadding(0, dp(12f), 0, dp(4f)) })
        autoCard.addView(switchRow("开机自动启动（免投屏触控）", "bootAuto"))

        autoCard.addView(sectionTitle("按应用自动启停").apply { setPadding(0, dp(12f), 0, dp(4f)) })
        val swAutoApp = android.widget.Switch(this).apply {
            text = "进入白名单应用自动上屏，离开自动停止"
            textSize = 14f
            isChecked = sp.getBoolean("autoApp", false)
            setOnCheckedChangeListener { _, v ->
                sp.edit().putBoolean("autoApp", v).apply()
                if (v) {
                    try {
                        startForegroundService(
                            Intent(this@MainActivity, WatchService::class.java)
                        )
                    } catch (t: Throwable) {
                        Diag.log("Watch start: $t")
                    }
                    Toast.makeText(
                        this@MainActivity, "已开启（需授予「使用情况访问」权限）",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    try {
                        stopService(Intent(this@MainActivity, WatchService::class.java))
                    } catch (_: Throwable) {
                    }
                }
            }
        }
        autoCard.addView(swAutoApp)
        val appCount = (sp.getString("autoApps", "") ?: "")
            .split(",").filter { it.isNotBlank() }.size
        autoCard.addView(bigButton("📋 选择应用（已选 $appCount 个）", 0xFF6B7280.toInt()) {
            pickApps()
        })
        autoCard.addView(bigButton("🔑 开启「使用情况访问」权限", 0xFF9CA3AF.toInt()) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS))
            } catch (_: Throwable) {
                Toast.makeText(this, "请手动在设置里授予", Toast.LENGTH_SHORT).show()
            }
        })

        autoCard.addView(sectionTitle("悬浮开关").apply { setPadding(0, dp(12f), 0, dp(4f)) })
        val swBall = android.widget.Switch(this).apply {
            text = "可拖动的小圆点 · 点击=启动/停止"
            textSize = 14f
            isChecked = sp.getBoolean("ballOn", false)
            setOnCheckedChangeListener { _, v ->
                sp.edit().putBoolean("ballOn", v).apply()
                if (v) {
                    if (!android.provider.Settings.canDrawOverlays(this@MainActivity)) {
                        Toast.makeText(
                            this@MainActivity, "悬浮开关需要「显示在其他应用上层」权限",
                            Toast.LENGTH_LONG
                        ).show()
                        try {
                            startActivity(
                                Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:$packageName")
                                )
                            )
                        } catch (_: Throwable) {
                        }
                    }
                    FloatBall.show(this@MainActivity)
                } else {
                    FloatBall.hide()
                }
            }
        }
        autoCard.addView(swBall)
        autoCard.addView(TextView(this).apply {
            text = "💡 控制中心：下拉两次 → 编辑 → 添加「触灵映射开关」磁贴，一键启停"
            textSize = 11f
            setTextColor(0xFF9CA3AF.toInt())
        })
        page4.addView(autoCard)

        // 提示
        page1.addView(TextView(this).apply {
            text = "首次使用：装 Shizuku（无线调试启动）→ 点「授权 Shizuku」\n" +
                "点「开始映射」→ 同意投屏授权 → 自动处理背屏中心并点亮背屏\n" +
                "灵触=背屏直接操作主屏；触控板=滑动移光标、轻点点击、停 0.4s 拖动\n" +
                "改设置后请先停止再开始"
            textSize = 12f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(dp(4f), dp(4f), dp(4f), dp(20f))
        })

        // 组装：固定头部 + 分页区 + 底部液态玻璃导航（v0.9.0）
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFFF2F3F7.toInt())
        }
        shell.addView(buildHeader(pad))
        pageHost = FrameLayout(this)
        shell.addView(
            pageHost,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        )
        pageViews.clear()
        pageViews.add(makePage(page1))
        pageViews.add(makePage(page5))
        pageViews.add(makePage(page2))
        pageViews.add(makePage(page3))
        pageViews.add(makePage(page4))
        pageViews.forEach {
            pageHost.addView(
                it,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        shell.addView(buildNavBar())
        setContentView(shell)
        switchPage(0)
        // v2.2.0：悬浮开关恢复
        if (sp.getBoolean("ballOn", false) &&
            android.provider.Settings.canDrawOverlays(this)
        ) {
            FloatBall.show(applicationContext)
        }
    }

    // ---------- 分页 / 液态玻璃导航（v0.9.0） ----------

    /** v2.2.1 页面标题（排版统一） */
    /** v2.2.2：滑条标题实时显示数值 */
    private fun bindLabel(tv: TextView, bar: SeekBar, fmt: (Int) -> String) {
        tv.text = fmt(bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, u: Boolean) {
                tv.text = fmt(p)
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
    }

    private fun pageTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(0xFF111827.toInt())
        setPadding(0, dp(2f), 0, dp(10f))
    }

    private fun pageContainer(pad: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, dp(8f), pad, pad)
    }

    private fun makePage(content: LinearLayout): View = ScrollView(this).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(
            content,
            android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
    }

    private fun buildHeader(pad: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(pad, pad, pad, dp(2f))
        addView(TextView(this@MainActivity).apply {
            text = "触灵映射"
            textSize = 24f
            setTextColor(0xFF111827.toInt())
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        })
        addView(TextView(this@MainActivity).apply {
            text = "小米背屏 → 主屏 · 控制台 v" + appVersion()
            textSize = 12f
            setTextColor(0xFF8E8E93.toInt())
            setPadding(0, dp(2f), 0, dp(10f))
        })
    }

    /** 底部「液态玻璃」导航栏：半透明渐变 + 高光描边 + 悬浮阴影 */
    private fun buildNavBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0xCCFFFFFF.toInt(), 0x8CFFFFFF.toInt())
            ).apply {
                cornerRadius = dp(26f).toFloat()
                setStroke(dp(1f), 0xB3FFFFFF.toInt())
            }
            elevation = dp(10f).toFloat()
            setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.setMargins(dp(14f), dp(4f), dp(14f), dp(14f))
        bar.layoutParams = lp

        navItems.clear()
        navTvs.clear()
        val tabs = listOf(
            "🎛" to "控制",
            "🎮" to "手感",
            "🖼" to "背屏内容",
            "✨" to "AI 工坊",
            "⚙️" to "设置"
        )
        tabs.forEachIndexed { i, (icon, label) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
                setPadding(0, dp(6f), 0, dp(6f))
                setOnClickListener { switchPage(i) }
            }
            val tvIcon = TextView(this).apply {
                text = icon
                textSize = 17f
                gravity = Gravity.CENTER
            }
            val tvLabel = TextView(this).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                setPadding(0, dp(2f), 0, 0)
            }
            item.addView(tvIcon)
            item.addView(tvLabel)
            navItems.add(item)
            navTvs.add(tvIcon to tvLabel)
            bar.addView(item)
        }
        return bar
    }

    private fun switchPage(index: Int) {
        if (index == currentPage) return
        currentPage = index
        pageViews.forEachIndexed { i, v ->
            v.visibility = if (i == index) View.VISIBLE else View.GONE
        }
        navItems.forEachIndexed { i, item ->
            val on = i == index
            item.background = if (on) {
                GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xFFFFFFFF.toInt(), 0xF2FFFFFF.toInt())
                ).apply {
                    cornerRadius = dp(18f).toFloat()
                    setStroke(dp(1f), 0x66FFFFFF)
                }
            } else {
                GradientDrawable().apply { setColor(0x00000000) }
            }
            item.animate().scaleX(if (on) 1f else 0.94f)
                .scaleY(if (on) 1f else 0.94f)
                .setDuration(140).start()
            navTvs.getOrNull(i)?.let { (icon, label) ->
                icon.alpha = if (on) 1f else 0.5f
                label.setTextColor(if (on) 0xFF1F2430.toInt() else 0x8A1F2430.toInt())
            }
        }
    }

    // ---------- 逻辑 ----------

    private fun persistCfg() {
        sp.edit()
            .putString(
                "mode",
                when (rgMode.checkedRadioButtonId) {
                    1002 -> "pad"
                    1003 -> "gyro"
                    else -> "direct"
                }
            )
            .putFloat("sens", 0.5f + sbSens.progress / 100f)
            .putInt("smoothMs", sbSmooth.progress)
            .putFloat("deadZone", sbDead.progress / 100f)
            .putInt("cursorDp", 16 + sbCursorDp.progress)
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
        // v2.2.2：按钮随运行状态切换 开始/停止
        btnStart?.text = if (MirrorService.running) "⏹ 停止映射" else "▶ 开始映射"
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
            sp.edit().putInt("htmlTheme", 4).apply()
            persistCfg()
            tvAiStatus.text = "未填 API Key → 已保存「内置木鱼主题」，并把背屏内容切到「我的AI」"
            refreshThemeList()
            updateSummary()
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
                sp.edit().putInt("htmlTheme", 4).apply()
                Diag.log("AI 主题生成成功 ${html.length} 字符")
                runOnUiThread {
                    tvAiStatus.text = "✅ 已生成并保存（${html.length} 字符）→ 背屏主题已切到「我的AI主题」"
                    refreshThemeList()
                    updateSummary()
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
        val h1 = t.indexOf("<!DOCTYPE", 0, true)
        val h2 = t.indexOf("<html", 0, true)
        val h = if (h1 >= 0) h1 else h2
        if (h > 0) t = t.substring(h)
        return t.trim()
    }

    private fun saveTheme(html: String) {
        try {
            val dir = java.io.File(filesDir, "themes")
            if (!dir.exists()) dir.mkdirs()
            val stamp = java.text.SimpleDateFormat("MMdd_HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val name = "theme_$stamp.html"
            java.io.File(dir, name).writeText(html)
            sp.edit().putString("aiThemeFile", name).apply()
            Diag.log("主题已保存 $name (${html.length} 字符)")
        } catch (t: Throwable) {
            Diag.log("保存主题失败: $t")
        }
    }

    /** 刷新「已保存的主题」列表（选用 / 删除） */
    private fun refreshThemeList() {
        if (!::themeListBox.isInitialized) return
        val box = themeListBox
        box.removeAllViews()
        val dir = java.io.File(filesDir, "themes")
        val list = dir.listFiles { f -> f.isFile && f.name.endsWith(".html") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (list.isEmpty()) {
            box.addView(TextView(this).apply {
                text = "（还没有已保存的主题，用上面按钮生成一个吧）"
                textSize = 12f
                setTextColor(0xFF9CA3AF.toInt())
            })
            return
        }
        val cur = sp.getString("aiThemeFile", "") ?: ""
        list.forEach { f ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4f), 0, dp(4f))
            }
            row.addView(TextView(this).apply {
                text = (if (f.name == cur) "✅ " else "📄 ") + f.name
                textSize = 12f
                setTextColor(0xFF374151.toInt())
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                )
            })
            row.addView(smallBtn("预览") { previewTheme(f) })
            row.addView(smallBtn("选用") {
                sp.edit().putString("aiThemeFile", f.name).putInt("htmlTheme", 4).apply()
                persistCfg()
                tvAiStatus.text = "已选用 ${f.name}（背屏内容 = 我的AI）"
                refreshThemeList()
                updateSummary()
            })
            row.addView(smallBtn("删除") {
                try {
                    f.delete()
                } catch (_: Throwable) {
                }
                if (f.name == (sp.getString("aiThemeFile", "") ?: "")) {
                    sp.edit().putString("aiThemeFile", "").apply()
                }
                refreshThemeList()
            })
            box.addView(row)
        }
    }

    /** 主屏弹窗预览主题（v1.3.0） */
    private fun previewTheme(f: java.io.File) {
        try {
            val wv = android.webkit.WebView(this)
            wv.settings.javaScriptEnabled = true
            wv.settings.useWideViewPort = true
            wv.settings.loadWithOverviewMode = true
            wv.setBackgroundColor(Color.BLACK)
            wv.loadDataWithBaseURL(null, f.readText(), "text/html", "UTF-8", null)

            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10f), dp(10f), dp(10f), dp(10f))
                setBackgroundColor(0xFF0B0B0D.toInt())
            }
            box.addView(wv, LinearLayout.LayoutParams(-1, 0, 1f))
            box.addView(smallBtn("关闭预览") { })

            val dlg = android.app.Dialog(this)
            dlg.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
            dlg.setContentView(box)
            val dm = resources.displayMetrics
            dlg.window?.setLayout((dm.widthPixels * 0.92f).toInt(), (dm.heightPixels * 0.62f).toInt())
            (box.getChildAt(1) as Button).setOnClickListener { dlg.dismiss() }
            dlg.show()
        } catch (t: Throwable) {
            tvAiStatus.text = "预览失败：${t.message?.take(80)}"
        }
    }

    private fun smallBtn(text: String, onClick: () -> Unit): Button = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            cornerRadius = dp(10f).toFloat()
            setColor(0xFF4B5563.toInt())
        }
        setPadding(dp(12f), dp(2f), dp(12f), dp(2f))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(6f) }
        setOnClickListener { onClick() }
    }

    /** 控制页配置摘要 */
    private fun updateSummary() {
        if (!::tvSummary.isInitialized) return
        val theme = when (sp.getInt("htmlTheme", 0)) {
            1 -> "电子木鱼"
            2 -> "翻页时钟"
            3 -> "幸运转盘"
            4 -> "我的 AI 主题"
            else -> "关闭"
        }
        val toy = listOf("关闭", "转盘", "真心话", "木鱼", "骰子")
            .getOrElse(sp.getInt("toy", 0)) { "关闭" }
        val mode = when (sp.getString("mode", "direct")) {
            "pad" -> "精密触控板"
            "gyro" -> "体感光标"
            else -> "灵触映射"
        }
        val ch = when (sp.getString("channel", "auto")) {
            "shizuku" -> "Shizuku"
            "root" -> "Root"
            else -> "自动"
        }
        val bg = listOf("镜像", "纯黑", "网格").getOrElse(sp.getInt("rearBg", 0)) { "镜像" }
        tvSummary.text = buildString {
            append("🎨 背屏内容：").append(theme).append('\n')
            append("🧸 互动玩具：").append(toy).append('\n')
            append("🖼 背屏底色：").append(bg).append('\n')
            append("👆 触摸映射：").append(mode).append('\n')
            append("🔌 注入通道：").append(ch)
        }
    }

    private fun fmt1(v: Float): String = String.format(java.util.Locale.US, "%.2f", v)

    /** 用到光标悬浮窗的模式（触控板 / 体感光标） */
    private fun cursorModeOn(): Boolean {
        val m = sp.getString("mode", "direct") ?: "direct"
        return m == "pad" || m == "gyro" || sp.getBoolean("gyro", false)
    }

    /** v2.1.0：陀螺仪校准（平放手机采样 700ms，记录零偏） */
    private fun calibrateGyro() {
        try {
            val sm = getSystemService(android.hardware.SensorManager::class.java)
            val g = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GYROSCOPE)
            if (g == null) {
                Toast.makeText(this, "设备无陀螺仪", Toast.LENGTH_SHORT).show()
                return
            }
            val sum = floatArrayOf(0f, 0f)
            var n = 0
            val l = object : android.hardware.SensorEventListener {
                override fun onSensorChanged(e: android.hardware.SensorEvent) {
                    sum[0] += e.values[2]
                    sum[1] += e.values[0]
                    n++
                }

                override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
            }
            sm.registerListener(l, g, android.hardware.SensorManager.SENSOR_DELAY_GAME)
            Toast.makeText(this, "校准中…请平放手机不要动", Toast.LENGTH_SHORT).show()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try {
                    sm.unregisterListener(l)
                } catch (_: Throwable) {
                }
                if (n > 0) {
                    sp.edit()
                        .putFloat("gyroCalX", sum[0] / n)
                        .putFloat("gyroCalY", sum[1] / n)
                        .apply()
                    Toast.makeText(this, "校准完成（样本 $n，零偏已保存）", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "校准失败：没有采到数据", Toast.LENGTH_SHORT).show()
                }
            }, 700)
        } catch (t: Throwable) {
            Toast.makeText(this, "校准失败：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    /** v2.0.0：免投屏触控启动（背屏黑屏当触控板，不需要屏幕捕获权限） */
    private fun startNoMirror() {
        Diag.log("用户点击 免投屏触控启动")
        persistCfg()
        val mode = sp.getString("mode", "direct") ?: "direct"
        val gyroOn = sp.getBoolean("gyro", false) || mode == "gyro"
        if ((mode == "pad" || gyroOn) && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "触控板/体感需要「显示在其他应用上层」权限（用于在主屏画光标）",
                Toast.LENGTH_LONG
            ).show()
            try {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        android.net.Uri.parse("package:$packageName")
                    )
                )
            } catch (_: Throwable) {
            }
            return
        }
        if (!Injector.rootAvailable() && shizukuState() != "已授权") {
            Toast.makeText(
                this, "需要 shell 权限才能搬运到背屏：请先授权 Shizuku/Root", Toast.LENGTH_LONG
            ).show()
            return
        }
        val i = Intent(this, MirrorService::class.java).apply {
            action = "start"
            putExtra("displayOnly", false)
            putExtra("noProjection", true)
        }
        startForegroundService(i)
        Toast.makeText(this, "免投屏模式启动中…背屏黑屏即可当触控板", Toast.LENGTH_LONG).show()
    }

    /** v1.0.0：只把主题/玩具显示到背屏（不开启触摸映射，也不需要投屏授权） */
    private fun startDisplayOnly() {
        Diag.log("用户点击 只显示到背屏")
        persistCfg()
        val htmlMode = sp.getInt("htmlTheme", 0)
        val toy = sp.getInt("toy", 0)
        if (htmlMode == 0 && toy == 0) {
            Toast.makeText(
                this, "请先在「背屏内容」页选择一个主题或玩具", Toast.LENGTH_LONG
            ).show()
            return
        }
        val i = Intent(this, MirrorService::class.java).apply {
            action = "start"
            putExtra("displayOnly", true)
            putExtra("noProjection", true)
        }
        startForegroundService(i)
        val shellOk = Injector.rootAvailable() || shizukuState() == "已授权"
        if (shellOk) {
            Toast.makeText(this, "正在把内容显示到背屏…（看背屏）", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(
                this,
                "已请求上屏，但搬运任务需要 shell 权限：请先授权 Shizuku（或 Root）",
                Toast.LENGTH_LONG
            ).show()
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
        // v2.0.0：免投屏模式优先（不申请屏幕捕获权限）
        if (sp.getBoolean("noMirror", true)) {
            startNoMirror()
            return
        }
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
        if (requestCode == 1001 || requestCode == 1004) {
            val only = requestCode == 1004
            Diag.log("投屏授权结果 resultCode=$resultCode displayOnly=$only")
            if (resultCode == RESULT_OK && data != null) {
                val i = Intent(this, MirrorService::class.java).apply {
                    action = "start"
                    putExtra("resultCode", resultCode)
                    putExtra("resultData", data)
                    putExtra("displayOnly", only)
                }
                startForegroundService(i)
            } else {
                Toast.makeText(this, "未授予投屏权限", Toast.LENGTH_SHORT).show()
            }
        }
    }
}