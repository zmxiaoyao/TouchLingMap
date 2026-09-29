package com.touchling.mapper

import android.content.Context
import android.graphics.Color
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.io.File

/**
 * v0.8.0 HTML 主题引擎（背屏用 WebView 渲染）
 *
 * - 主题就是一个 HTML 文件（可含 CSS/JS），由 AI 生成或内置
 * - 页面里可通过全局对象 TouchLing 调用原生能力：
 *     TouchLing.log(msg)        写日志
 *     TouchLing.key(code)       注入按键（如 3=Home 4=返回 187=多任务）
 *     TouchLing.exec(cmd)       以 shell 执行命令（Shizuku/Root 通道）
 * - 不建议引用外部资源（背屏经常离线），全部内联最稳
 */
class HtmlThemeView(
    ctx: Context,
    useBuiltin: Boolean,
    private val aiFile: File?
) : WebView(ctx) {

    init {
        setBackgroundColor(Color.BLACK)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.defaultTextEncodingName = "UTF-8"
        addJavascriptInterface(Bridge(), "TouchLing")
        load(useBuiltin)
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
        fun exec(cmd: String): String {
            return try {
                MirrorService.injectorInstance?.exec(cmd) ?: ""
            } catch (_: Throwable) {
                ""
            }
        }
    }

    private fun load(useBuiltin: Boolean) {
        val html = try {
            if (!useBuiltin && aiFile != null && aiFile.exists()) {
                aiFile.readText()
            } else {
                DefaultTheme.HTML
            }
        } catch (t: Throwable) {
            Diag.log("主题读取失败: $t")
            DefaultTheme.HTML
        }
        Diag.log("HTML主题载入 ${html.length} 字符")
        loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
    }
}

/** 内置示例主题（无 API Key 时也能玩） */
object DefaultTheme {
    val HTML: String = """
<!DOCTYPE html>
<html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<style>
  html,body{margin:0;height:100%;overflow:hidden;
    background:radial-gradient(circle at 50% 18%,#2b1d12,#0a0705 70%);
    color:#f5e6c8;font-family:-apple-system,"PingFang SC",sans-serif;
    display:flex;flex-direction:column;align-items:center;justify-content:space-between;user-select:none;}
  .title{font-size:20px;opacity:.75;margin-top:12px;letter-spacing:2px}
  #merit{font-size:58px;color:#ffd54f;font-weight:700;text-shadow:0 0 22px rgba(255,213,79,.45)}
  .bowl{width:170px;height:170px;border-radius:50%;
    background:radial-gradient(circle at 35% 30%,#9c7a68,#4e342e 75%);
    box-shadow:0 0 46px rgba(255,213,79,.28),inset 0 -12px 24px rgba(0,0,0,.55);
    display:flex;align-items:center;justify-content:center;font-size:46px;
    transition:transform .07s ease-out;cursor:pointer}
  .bowl:active{transform:scale(.93)}
  .row{display:flex;gap:10px;margin-bottom:14px}
  .btn{padding:9px 16px;border-radius:20px;background:rgba(255,255,255,.10);
    border:1px solid rgba(255,255,255,.18);font-size:14px}
  .tip{opacity:.55;font-size:13px;margin-bottom:16px}
  .float{position:fixed;color:#ffeb3b;font-size:22px;pointer-events:none;
    animation:up 1s ease-out forwards}
  @keyframes up{from{opacity:1;transform:translateY(0)}to{opacity:0;transform:translateY(-60px)}}
</style></head>
<body>
  <div class="title">电子木鱼 · 内置示例主题</div>
  <div id="merit">功德 0</div>
  <div class="bowl" id="bowl">🪵</div>
  <div class="row">
    <div class="btn" id="btnHome">主页</div>
    <div class="btn" id="btnBack">返回</div>
    <div class="btn" id="btnReset">清零</div>
  </div>
  <div class="tip">轻触木鱼 · 本页由 TouchLing HTML 主题引擎渲染</div>
<script>
var n=0;
var merit=document.getElementById('merit');
var bowl=document.getElementById('bowl');
function knock(x,y){
  n++;merit.textContent='功德 '+n;
  var f=document.createElement('div');
  f.className='float';f.textContent='+1';
  f.style.left=(x-12)+'px';f.style.top=(y-30)+'px';
  document.body.appendChild(f);
  setTimeout(function(){f.remove();},1000);
  if(window.TouchLing){TouchLing.log('knock '+n);}
}
bowl.addEventListener('click',function(e){
  var r=bowl.getBoundingClientRect();
  knock(e.clientX,e.clientY);
  bowl.style.transform='scale(1.07)';
  setTimeout(function(){bowl.style.transform='';},80);
  if(n%10===0){var s=document.createElement('div');
    s.className='float';s.textContent='功德圆满 x'+n;s.style.left='24px';s.style.top='120px';
    document.body.appendChild(s);setTimeout(function(){s.remove();},1000);}
});
document.getElementById('btnHome').addEventListener('click',function(){
  if(window.TouchLing){TouchLing.key(3);}
});
document.getElementById('btnBack').addEventListener('click',function(){
  if(window.TouchLing){TouchLing.key(4);}
});
document.getElementById('btnReset').addEventListener('click',function(){
  n=0;merit.textContent='功德 0';
});
</script>
</body></html>
""".trimIndent()
}