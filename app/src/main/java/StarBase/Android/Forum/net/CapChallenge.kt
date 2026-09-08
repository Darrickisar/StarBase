package StarBase.Android.Forum.net

import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.nodes.DataNode
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

data class CapChallenge(
    val apiEndpoint: String,
    val scriptUrl: String,
    val wasmUrl: String,
    val fieldName: String
) {
    companion object {
        fun from(form: Element): CapChallenge? {
            val widget = form.selectFirst("cap-widget") ?: return null
            val root = widget.closest("[data-cap-verification]") ?: return null
            val endpoint = trustedUrl(widget.attr("data-cap-api-endpoint")) ?: return null
            val script = trustedUrl(root.attr("data-cap-widget-script")) ?: return null
            val wasm = trustedUrl(root.attr("data-cap-wasm-url")) ?: return null
            val field = widget.attr("data-cap-hidden-field-name").ifBlank { "cap-token" }
            if (field !in setOf("cap_token", "cap-token")) return null
            return CapChallenge(endpoint, script, wasm, field)
        }

        private fun trustedUrl(raw: String): String? {
            if (raw.isBlank()) return null
            val url = Site.BASE.toHttpUrlOrNull()?.resolve(raw) ?: return null
            return url.takeIf {
                it.isHttps && it.host in setOf("linux.sb", "cap.linux.sb") && it.port == 443 &&
                    it.username.isEmpty() && it.password.isEmpty() && it.fragment == null
            }?.toString()
        }

        fun validToken(token: String): Boolean =
            token.length in 1..8192 && token.all { it.code in 33..126 }
    }
}

/** Runs the site's widget unchanged; only its public result events leave this document. */
internal fun capDocument(challenge: CapChallenge, dark: Boolean, bridgeId: String): String {
    val doc = Document.createShell(Site.LOGIN)
    doc.selectFirst("html")!!.attr("lang", "zh-CN")
    doc.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
    doc.head().appendElement("style").appendChild(DataNode("""
        html,body{margin:0;padding:0;background:transparent;color-scheme:${if (dark) "dark" else "light"}}
        body{padding:16px 0;font-family:system-ui,sans-serif}
        cap-widget{display:block;--cap-widget-width:100%;--cap-border-radius:8px;
          --cap-background:${if (dark) "#181a20" else "#ffffff"};
          --cap-color:${if (dark) "#f6f3ef" else "#1a1c21"}}
    """.trimIndent()))
    doc.body().appendElement("cap-widget").attr("id", "verification").attr("data-cap-lang", "zh-cn")
        .attr("data-cap-api-endpoint", challenge.apiEndpoint)
        .attr("data-cap-hidden-field-name", challenge.fieldName)
    doc.body().appendElement("script").appendChild(DataNode("""
        window.CAP_CUSTOM_WASM_URL = ${JsonPrimitive(challenge.wasmUrl)};
        let port = null, pending = [], serial = 0;
        const requests = new Map();
        function send(message) {
          if (port) port.postMessage(JSON.stringify(message)); else pending.push(message);
        }
        function report(type, token) {
          send({type:type, token:token || ''});
        }
        window.addEventListener('message', function(event) {
          if (event.data !== ${JsonPrimitive(bridgeId)} || !event.ports[0] || port) return;
          port = event.ports[0];
          port.onmessage = function(event) {
            let result;
            try { result = JSON.parse(event.data); } catch (_) { return; }
            const request = requests.get(result.id);
            if (!request) return;
            requests.delete(result.id);
            request.cleanup();
            if (result.error) request.reject(new Error('Verification connection failed'));
            else request.resolve(new Response(result.body, {status:result.status, headers:{'Content-Type':'application/json'}}));
          };
          pending.forEach(send); pending = [];
        });
        window.CAP_CUSTOM_FETCH = function(url, options) {
          options = options || {};
          return new Promise(function(resolve, reject) {
            const id = String(++serial), signal = options.signal;
            function abort() {
              requests.delete(id); cleanup(); send({type:'cancel', id:id});
              reject(new DOMException('Aborted', 'AbortError'));
            }
            const timer = setTimeout(abort, 45000);
            function cleanup() { clearTimeout(timer); if (signal) signal.removeEventListener('abort', abort); }
            if (signal && signal.aborted) { abort(); return; }
            requests.set(id, {resolve:resolve, reject:reject, cleanup:cleanup});
            if (signal) signal.addEventListener('abort', abort, {once:true});
            send({type:'fetch', id:id, url:String(url), method:options.method || 'GET', body:options.body || ''});
          });
        };
        const widget = document.getElementById('verification');
        widget.addEventListener('solve', function(event) { report('solved', event.detail && event.detail.token); });
        widget.addEventListener('error', function() { report('error'); });
        // Set required after connection: older WebViews reject the validation anchor during upgrade.
        window.capLoaded = function() { customElements.whenDefined('cap-widget').then(function() {
          widget.setAttribute('required', ''); report('ready');
        }); };
        window.capFailed = function() { report('error'); };
    """.trimIndent()))
    doc.body().appendElement("script").attr("src", challenge.scriptUrl)
        .attr("onload", "capLoaded()").attr("onerror", "capFailed()")
    return doc.outerHtml()
}
