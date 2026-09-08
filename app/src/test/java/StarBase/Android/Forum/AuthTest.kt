package StarBase.Android.Forum

import StarBase.Android.Forum.net.Api
import StarBase.Android.Forum.net.SiteException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import StarBase.Android.Forum.net.CapChallenge
import StarBase.Android.Forum.net.capDocument
import org.junit.Test
import org.jsoup.Jsoup

class AuthTest {
    @Test
    fun capWidgetCannotBeSubmittedAsAFormWithoutVerification() {
        val form = Api.readLoginForm(fixture("login-cap.html"))
        assertThrows(SiteException::class.java) {
            Api.authenticationBody(form, mapOf("username" to "member", "password" to "secret"), "", "")
        }
    }

    @Test
    fun capUsesTheLiveWidgetConfigurationAndSubmitsItsGeneratedHiddenField() {
        val form = Api.readLoginForm(fixture("login-cap.html"))
        val cap = assertNotNull(form.capChallenge).let { form.capChallenge!! }
        assertFalse(form.captchaRequired)
        assertFalse(form.powRequired)
        assertEquals("https://cap.linux.sb/60c41af707/", cap.apiEndpoint)
        assertEquals("https://cap.linux.sb/assets/widget.js", cap.scriptUrl)
        assertEquals("https://cap.linux.sb/assets/cap_wasm_bg.wasm", cap.wasmUrl)
        assertEquals("cap_token", cap.fieldName)
        assertTrue("cap_token" in form.fields)
        val body = Api.authenticationBody(form, mapOf("username" to "member", "password" to "secret"), "", "", "verified-token")
        val values = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals(mapOf("_csrf" to "fixture-csrf", "username" to "member", "password" to "secret", "cap_token" to "verified-token"), values)
    }

    @Test
    fun capRegistrationAlsoRequiresItsOwnFreshResult() {
        val html = Jsoup.parse(fixture("login-cap.html"))
        html.selectFirst("form")!!.append("<input name='password2'><input name='email'>")
        val form = Api.readLoginForm(html.outerHtml())
        val values = mapOf("username" to "member", "password" to "secret", "password2" to "secret", "email" to "member@example.org")
        for (token in listOf("", " ", "bad\nvalue", "x".repeat(8193))) {
            assertThrows(SiteException::class.java) { Api.authenticationBody(form, values, "", "", token) }
        }
        val body = Api.authenticationBody(form, values, "", "", "registration-token")
        assertEquals("registration-token", (0 until body.size).associate { body.name(it) to body.value(it) }["cap_token"])
    }

    @Test
    fun incompleteOrUntrustedCapCannotSilentlyDowngradeToNoCaptcha() {
        val source = fixture("login-cap.html")
        for (attribute in listOf("data-cap-api-endpoint", "data-cap-widget-script", "data-cap-wasm-url")) {
            val html = Jsoup.parse(source)
            html.selectFirst("[$attribute]")!!.removeAttr(attribute)
            assertThrows(SiteException::class.java) { Api.readLoginForm(html.outerHtml()) }
        }
        for (url in listOf("http://cap.linux.sb/a", "https://cap.linux.sb.evil.example/a", "https://user:secret@cap.linux.sb/a", "https://cap.linux.sb:444/a", "javascript:alert(1)")) {
            val html = Jsoup.parse(source)
            html.selectFirst("[data-cap-widget-script]")!!.attr("data-cap-widget-script", url)
            assertThrows(SiteException::class.java) { Api.readLoginForm(html.outerHtml()) }
        }
        val hiddenOnly = Jsoup.parse(fixture("login-simple.html"))
        hiddenOnly.selectFirst("form")!!.append("<input name='cap_token'>")
        assertThrows(SiteException::class.java) { Api.readLoginForm(hiddenOnly.outerHtml()) }
        assertThrows(SiteException::class.java) { Api.readLoginForm(source.replace("cap_token", "password")) }
    }

    @Test
    fun verificationDocumentUsesTheOfficialWidgetAndContainsNoLoginFields() {
        val cap = Api.readLoginForm(fixture("login-cap.html")).capChallenge!!
        val html = capDocument(cap, false, "test-bridge")
        val doc = Jsoup.parse(html)
        assertEquals(cap.scriptUrl, doc.selectFirst("script[src]")!!.attr("src"))
        assertEquals(cap.apiEndpoint, doc.selectFirst("cap-widget")!!.attr("data-cap-api-endpoint"))
        assertTrue(doc.select("input[name=username], input[name=password], input[name=_csrf]").isEmpty())
        assertTrue(html.contains("test-bridge"))
        assertFalse(html.contains("widget.solve("))
        assertTrue(CapChallenge.validToken("verified-token"))
    }

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/$name")).bufferedReader().use { it.readText() }

    private val member = """
        <aside class="sidebar-card user-card">
          <a href="/user/42">Profile</a><span class="user-name">Member</span>
        </aside>
    """.trimIndent()

    @Test
    fun loginPageAfterSubmitIsRejectedBeforeVerifyingTheSession() {
        var fetched = false
        val error = assertThrows(SiteException::class.java) {
            Api.confirmLogin(fixture("login.html")) { fetched = true; member }
        }
        assertEquals(SiteException.Kind.AUTH, error.kind)
        assertFalse(fetched)
    }

    @Test
    fun anonymousSessionCannotConfirmLoginEvenWhenSubmitShowsAMember() {
        val error = assertThrows(SiteException::class.java) {
            Api.confirmLogin(member) { fixture("home.html") }
        }
        assertEquals(SiteException.Kind.AUTH, error.kind)
    }

    @Test
    fun aFreshAuthenticatedPageConfirmsTheSession() {
        var fetches = 0
        val me = Api.confirmLogin(member) { fetches++; member }
        assertEquals(42, me.id)
        assertEquals("Member", me.name)
        assertEquals(1, fetches)
    }

    @Test
    fun aSessionRedirectedToLoginIsRejected() {
        val error = assertThrows(SiteException::class.java) {
            Api.confirmLogin(member) { fixture("login.html") }
        }
        assertEquals(SiteException.Kind.AUTH, error.kind)
    }

    @Test
    fun anUnidentifiedUserCardCannotConfirmLogin() {
        val error = assertThrows(SiteException::class.java) {
            Api.confirmLogin(member) { member.replace("/user/42", "/profile") }
        }
        assertEquals(SiteException.Kind.AUTH, error.kind)
    }

    @Test
    fun refusalKeepsTheSitesMessageAndDoesNotFetchAgain() {
        val error = assertThrows(SiteException::class.java) {
            Api.confirmLogin("<div class='form-error-panel'><p>Captcha rejected</p></div>") {
                error("Refused submissions must stop here")
            }
        }
        assertEquals("Captcha rejected", error.message)
    }

    @Test
    fun sessionVerificationFailureIsPropagatedWithoutAnotherSubmit() {
        val error = assertThrows(SiteException::class.java) {
            Api.confirmLogin(member) { throw SiteException("Offline", SiteException.Kind.NETWORK) }
        }
        assertEquals(SiteException.Kind.NETWORK, error.kind)
    }

    @Test
    fun loginFormRecognizesMembersAndRejectsUnrecognizedGuestPages() {
        val signedIn = assertThrows(SiteException::class.java) { Api.readLoginForm(member) }
        assertEquals(SiteException.Kind.AUTH, signedIn.kind)
        val guest = assertThrows(SiteException::class.java) { Api.readLoginForm(fixture("home.html")) }
        assertEquals(SiteException.Kind.PARSE, guest.kind)
        assertFalse(Api.readLoginForm(fixture("login.html")).csrf.isBlank())
    }

    @Test
    fun registrationRequiresTheLoginFormInsteadOfAnArbitrarySuccessfulPage() {
        Api.confirmRegistration(fixture("login.html"))
        listOf("", fixture("home.html"), member, "<html>Verification required</html>").forEach { html ->
            assertThrows(SiteException::class.java) { Api.confirmRegistration(html) }
        }
    }

    @Test
    fun aReturnedRegistrationFormDoesNotClaimSuccess() {
        val register = Jsoup.parse(fixture("login.html"))
        register.selectFirst("input[name=password]")!!.after("<input type='password' name='password2'>")
        assertThrows(SiteException::class.java) { Api.confirmRegistration(register.outerHtml()) }
    }

    @Test
    fun loginWorksWhenTheSiteDoesNotRequireACaptcha() {
        val form = Api.readLoginForm(fixture("login-simple.html"))
        assertFalse(form.captchaRequired)
        assertFalse(form.powRequired)
        val body = Api.authenticationBody(form, mapOf("username" to "member", "password" to "secret"), "", "")
        assertEquals(listOf("_csrf", "username", "password"), (0 until body.size).map(body::name))
        Api.confirmRegistration(fixture("login-simple.html"))
    }

    @Test
    fun nativeCaptchaFieldsArePreservedOnlyWhenTheFormRequiresThem() {
        val form = Api.readLoginForm(fixture("login.html"))
        val body = Api.authenticationBody(form, mapOf("username" to "member", "password" to "secret"), "17", "abcd")
        val values = (0 until body.size).associate { body.name(it) to body.value(it) }
        assertEquals(form.fields.toSet(), values.keys)
        assertEquals(form.token, values["native_captcha_token"])
        assertEquals("17", values["native_captcha_answer"])
        assertEquals("abcd", values["native_captcha_pow"])
        assertEquals("", values["native_captcha_company"])
    }

    @Test
    fun missingCaptchaMarkupOrAnUnsupportedChallengeIsNotTreatedAsOptional() {
        val broken = Jsoup.parse(fixture("login.html"))
        broken.select("input[name=native_captcha_token]").remove()
        assertThrows(SiteException::class.java) { Api.readLoginForm(broken.outerHtml()) }
        val turnstile = Jsoup.parse(fixture("login-simple.html"))
        turnstile.selectFirst("form")!!.append("<div class='cf-turnstile' data-sitekey='placeholder'></div>")
        assertThrows(SiteException::class.java) { Api.readLoginForm(turnstile.outerHtml()) }
    }
}
