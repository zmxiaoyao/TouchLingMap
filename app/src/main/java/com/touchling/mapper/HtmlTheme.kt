package com.touchling.mapper

import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File

/**
 * v1.0.0 HTML 主题引擎（背屏用 WebView 渲染）
 *
 * 主题 = 一个 HTML 文件（CSS/JS 全支持），可来自内置库或 AI 生成。
 * 页面可通过全局对象 TouchLing 调用原生能力：
 *   TouchLing.log(msg) / TouchLing.key(code) / TouchLing.exec(cmd)
 *
 * @param themeIndex 1..N = 内置主题；4 = 我的 AI 主题（读 aiFile）
 */
class HtmlThemeView(
    ctx: Context,
    private val themeIndex: Int,
    private val aiFile: File?
) : WebView(ctx) {

    init {
        setBackgroundColor(Color.BLACK)
        // v1.1.0 性能与体验优化
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isVerticalScrollBarEnabled = false
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        keepScreenOn = true
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.defaultTextEncodingName = "UTF-8"
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
        settings.textZoom = 100
        // v1.2.0 尺寸自适应：宽视口 + 载入即缩放到合适
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // 注入自适应样式：顺手把溢出内容缩放到刚好铺满背屏
                view?.evaluateJavascript(FIT_JS, null)
            }
        }
        addJavascriptInterface(Bridge(), "TouchLing")
        loadTheme()
    }

    companion object {
        /** 注入到主题页尾：统一盒模型 + 溢出自动缩放（背屏 976x596 不再超出） */
        private val FIT_JS = """
(function(){
  try{
    var st=document.createElement('style');
    st.textContent='html,body{margin:0;padding:0;width:100%;height:100%;overflow:hidden;box-sizing:border-box}'+
      '*,*:before,*:after{box-sizing:border-box}'+
      'img,video,canvas{max-width:100%;max-height:100%}';
    if(document.head){document.head.appendChild(st);}
    var d=document.documentElement, b=document.body||d;
    var w=Math.max(d.scrollWidth,b.scrollWidth)||1;
    var h=Math.max(d.scrollHeight,b.scrollHeight)||1;
    var vw=window.innerWidth||1, vh=window.innerHeight||1;
    var sc=Math.min(vw/w, vh/h);
    if(sc<0.995){
      b.style.transformOrigin='top left';
      b.style.transform='scale('+sc+')';
      b.style.width=(100/sc)+'%';
      b.style.height=(100/sc)+'%';
    }
  }catch(e){}
})();
""".trimIndent()
    }

    inner class Bridge {
        @JavascriptInterface
        fun log(msg: String) {
            Diag.log("[theme] $msg")
        }

        @JavascriptInterface
        fun key(code: Int) {
            try {
                MirrorService.injectorInstance?.key(code)
            } catch (_: Throwable) {
            }
        }

        @JavascriptInterface
        fun exec(cmd: String): String = try {
            MirrorService.injectorInstance?.exec(cmd) ?: ""
        } catch (_: Throwable) {
            ""
        }
    }

    private fun loadTheme() {
        val html = try {
            if (themeIndex >= DefaultTheme.HTMLS.size + 1 && aiFile != null && aiFile.exists()) {
                aiFile.readText()
            } else {
                DefaultTheme.html(themeIndex)
            }
        } catch (t: Throwable) {
            Diag.log("主题读取失败: $t")
            DefaultTheme.html(1)
        }
        Diag.log("HTML主题载入 idx=$themeIndex ${html.length} 字符")
        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}

/** 内置主题库（可多个） */
object DefaultTheme {

    val NAMES = listOf("电子木鱼", "翻页时钟", "幸运转盘")

    val HTMLS: List<String>
        get() = listOf(WoodenFish, Clock, Wheel)

    fun html(index: Int): String = HTMLS.getOrElse(index - 1) { HTMLS[0] }

    /** 兼容旧代码引用 */
    val HTML: String get() = HTMLS[0]

    // ------------------------------------------------ ① 电子木鱼
    private val WoodenFish = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  html,body{margin:0;height:100%;overflow:hidden;
    background:radial-gradient(circle at 50% 18%,#2b1d12,#0a0705 70%);
    color:#f5e6c8;font-family:-apple-system,"PingFang SC",sans-serif;
    display:flex;flex-direction:column;align-items:center;justify-content:space-between;user-select:none;}
  .title{font-size:19px;opacity:.7;margin-top:12px;letter-spacing:2px}
  #merit{font-size:56px;color:#ffd54f;font-weight:700;text-shadow:0 0 22px rgba(255,213,79,.45)}
  .bowl{width:168px;height:168px;border-radius:50%;
    background:radial-gradient(circle at 35% 30%,#9c7a68,#4e342e 75%);
    box-shadow:0 0 46px rgba(255,213,79,.28),inset 0 -12px 24px rgba(0,0,0,.55);
    display:flex;align-items:center;justify-content:center;font-size:44px;
    transition:transform .07s ease-out}
  .bowl:active{transform:scale(.93)}
  .row{display:flex;gap:10px;margin-bottom:12px}
  .btn{padding:9px 16px;border-radius:20px;background:rgba(255,255,255,.10);
    border:1px solid rgba(255,255,255,.18);font-size:14px}
  .tip{opacity:.55;font-size:12px;margin-bottom:14px}
  .float{position:fixed;color:#ffeb3b;font-size:22px;pointer-events:none;
    animation:up 1s ease-out forwards}
  @keyframes up{from{opacity:1;transform:translateY(0)}to{opacity:0;transform:translateY(-60px)}}
</style></head>
<body>
  <div class="title">电子木鱼 · 内置主题 1</div>
  <div id="merit">功德 0</div>
  <div class="bowl" id="bowl">🪵</div>
  <div class="row">
    <div class="btn" id="btnHome">主页</div>
    <div class="btn" id="btnBack">返回</div>
    <div class="btn" id="btnReset">清零</div>
  </div>
  <div class="tip">轻触木鱼 · TouchLing HTML 主题引擎</div>
<script>
var n=0,merit=document.getElementById('merit'),bowl=document.getElementById('bowl');
function knock(x,y){
  n++;merit.textContent='功德 '+n;
  var f=document.createElement('div');
  f.className='float';f.textContent='+1';
  f.style.left=(x-12)+'px';f.style.top=(y-30)+'px';
  document.body.appendChild(f);setTimeout(function(){f.remove();},1000);
  if(window.TouchLing){TouchLing.log('knock '+n);}
}
bowl.addEventListener('click',function(e){
  knock(e.clientX,e.clientY);
  bowl.style.transform='scale(1.07)';
  setTimeout(function(){bowl.style.transform='';},80);
});
document.getElementById('btnHome').onclick=function(){if(window.TouchLing)TouchLing.key(3);};
document.getElementById('btnBack').onclick=function(){if(window.TouchLing)TouchLing.key(4);};
document.getElementById('btnReset').onclick=function(){n=0;merit.textContent='功德 0';};
</script>
</body></html>
""".trimIndent()

    // ------------------------------------------------ ② 翻页时钟
    private val Clock = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  html,body{margin:0;height:100%;overflow:hidden;
    background:linear-gradient(160deg,#05070d,#101828 60%,#1b1030);
    color:#eaf2ff;font-family:-apple-system,"PingFang SC",sans-serif;
    display:flex;flex-direction:column;align-items:center;justify-content:center;user-select:none}
  #t{font-size:78px;font-weight:800;letter-spacing:3px;
     font-variant-numeric:tabular-nums;text-shadow:0 0 26px rgba(90,160,255,.55)}
  #d{font-size:17px;opacity:.72;margin-top:6px;letter-spacing:2px}
  #w{font-size:14px;opacity:.45;margin-top:2px;letter-spacing:6px}
  .bar{width:64%;height:4px;border-radius:4px;margin-top:26px;
    background:rgba(255,255,255,.10);overflow:hidden}
  .bar>i{display:block;height:100%;width:0;
    background:linear-gradient(90deg,#4f8cff,#a46bff)}
  .tip{position:fixed;bottom:12px;font-size:12px;opacity:.4}
</style></head>
<body>
  <div id="t">--:--:--</div>
  <div id="d"></div>
  <div id="w"></div>
  <div class="bar"><i id="sec"></i></div>
  <div class="tip">轻触可切回 · TouchLing 内置主题 2</div>
<script>
function p(n){return (n<10?'0':'')+n;}
function tick(){
  var x=new Date();
  document.getElementById('t').textContent=p(x.getHours())+':'+p(x.getMinutes())+':'+p(x.getSeconds());
  document.getElementById('d').textContent=x.getFullYear()+' 年 '+(x.getMonth()+1)+' 月 '+x.getDate()+' 日';
  var ws=['星期日','星期一','星期二','星期三','星期四','星期五','星期六'];
  document.getElementById('w').textContent=ws[x.getDay()];
  document.getElementById('sec').style.width=(x.getSeconds()/60*100)+'%';
}
tick();setInterval(tick,1000);
</script>
</body></html>
""".trimIndent()

    // ------------------------------------------------ ③ 幸运转盘
    private val Wheel = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  html,body{margin:0;height:100%;overflow:hidden;
    background:radial-gradient(circle at 50% 30%,#151a2e,#07080f);
    color:#fff;font-family:-apple-system,"PingFang SC",sans-serif;
    display:flex;flex-direction:column;align-items:center;justify-content:center;user-select:none}
  .wrap{position:relative;width:250px;height:250px}
  .wheel{width:250px;height:250px;border-radius:50%;
    background:conic-gradient(#ef5350 0 60deg,#ab47bc 60deg 120deg,#5c6bc0 120deg 180deg,
      #26a69a 180deg 240deg,#ffa726 240deg 300deg,#66bb6a 300deg 360deg);
    box-shadow:0 0 40px rgba(255,255,255,.18),inset 0 0 0 4px rgba(255,255,255,.35);
    transition:transform 2.6s cubic-bezier(.17,.67,.16,1)}
  .pin{position:absolute;left:50%;top:-14px;margin-left:-12px;width:0;height:0;
    border-left:12px solid transparent;border-right:12px solid transparent;
    border-top:22px solid #ffeb3b;filter:drop-shadow(0 0 6px rgba(255,235,59,.8))}
  #res{margin-top:20px;font-size:26px;color:#ffeb3b;font-weight:700;min-height:34px}
  .tip{font-size:12px;opacity:.45;margin-top:8px}
</style></head>
<body>
  <div class="wrap"><div class="pin"></div><div class="wheel" id="w"></div></div>
  <div id="res">轻触转盘</div>
  <div class="tip">内置主题 3 · TouchLing</div>
<script>
var items=['吃火锅','吃烧烤','吃日料','吃快餐','吃面','随便吃'];
var spin=0,w=document.getElementById('w'),res=document.getElementById('res');
w.addEventListener('click',function(){
  var i=Math.floor(Math.random()*items.length);
  spin+=1800+Math.floor(Math.random()*360);
  w.style.transform='rotate('+spin+'deg)';
  res.textContent='...';
  setTimeout(function(){res.textContent='👉 '+items[i];
    if(window.TouchLing)TouchLing.log('wheel '+items[i]);},2650);
});
</script>
</body></html>
""".trimIndent()
}