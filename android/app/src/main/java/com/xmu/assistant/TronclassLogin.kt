package com.xmu.assistant

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal class TronclassLogin(
    private val transport: LoginHttpTransport = OkHttpLoginTransport(),
    private val base64Encoder: (ByteArray) -> String = {
        Base64.encodeToString(it, Base64.NO_WRAP)
    },
) {
    private val jar = SimpleCookieJar()
    private val random = SecureRandom()

    fun login(username: String, password: String): LoginResult {
        require(username.isNotBlank() && password.isNotBlank()) { "请输入学号和密码" }

        jar.clear()
        val authUrl = "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/auth"
        val tokenUrl = "https://c-identity.xmu.edu.cn/auth/realms/xmu/protocol/openid-connect/token"
        val loginUrl = "https://lnt.xmu.edu.cn/api/login?login=access_token"
        val redirectUri = "https://c-mobile.xmu.edu.cn/identity-web-login-callback?_h5=true"
        val authParams = form(
            "scope" to "openid",
            "response_type" to "code",
            "client_id" to "TronClassH5",
            "redirect_uri" to redirectUri,
        )

        var page = openLoginPage(authUrl, authParams)
        var afterPassword = submitPassword(page, username, password)
        if (afterPassword.isLoginPageLanding()) {
            // 服务端偶发把密码提交打回登录页（流水号/会话失配，非密码问题）：换全新会话重来一次。
            // 绝不能复用当前会话重试——该会话已被标 needCaptcha，再提交必被拒，且会多记一次失败。
            Log.w(TAG, "password submit bounced back to login page, retrying once with a fresh session")
            jar.clear()
            page = openLoginPage(authUrl, authParams)
            afterPassword = submitPassword(page, username, password)
            if (afterPassword.isLoginPageLanding()) {
                Log.w(TAG, "password submit bounced twice, giving up")
                throw IllegalStateException("登录会话已失效，请重新登录")
            }
        }
        val afterIdentity = request(resolve(afterPassword.location ?: error("登录回调失败"), page.url))
            .requireNotServerError("身份确认")
        val callback = resolve(afterIdentity.location ?: error("登录回调失败"), page.url)
        val code = Regex("""[?&]code=([^&]+)""").find(callback)?.groupValues?.get(1)
            ?: error("登录回调没有返回 code")

        val tokenBody = form(
            "client_id" to "TronClassH5",
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to redirectUri,
            "scope" to "openid",
        )
        val tokenResponse = request(tokenUrl, method = "POST", body = tokenBody).requireNotServerError("令牌获取")
        val token = JSONObject(tokenResponse.body)
            .optRealString("access_token")
            .takeIf { it.isNotBlank() }
            ?: error("登录令牌获取失败")

        val finalLogin = request(
            loginUrl,
            method = "POST",
            contentType = "application/json; charset=utf-8",
            body = JSONObject().put("access_token", token).put("org_id", 1).toString(),
        ).requireNotServerError("TronClass 登录")
        if (finalLogin.code !in 200..299) error("TronClass 登录失败：${finalLogin.code}")
        return LoginResult(cookieHeader = jar.header(loginUrl))
    }

    /** 服务端 5xx 显式归为"服务繁忙"（与密码错误/网络失败区分，避免误导重试）。 */
    private fun LoginHttpResponse.requireNotServerError(step: String): LoginHttpResponse {
        if (code in 500..599) error("教务服务繁忙（$step），请稍后重试")
        return this
    }

    /** 登录页三跳（Keycloak → broker → 统一认证表单），返回可提交的表单地址与加密参数。 */
    private fun openLoginPage(authUrl: String, authParams: String): LoginPage {
        val first = request("$authUrl?$authParams").requireNotServerError("登录跳转")
        val second = request(resolve(first.location ?: error("登录跳转失败"), authUrl)).requireNotServerError("登录跳转")
        val loginPage = request(resolve(second.location ?: error("登录页面跳转失败"), authUrl)).requireNotServerError("登录页面")
        val salt = Regex("""id="pwdEncryptSalt"\s+value="([^"]+)"""").find(loginPage.body)?.groupValues?.get(1)
            ?: error("无法读取登录加密参数")
        val execution = Regex("""name="execution"\s+value="([^"]+)"""").find(loginPage.body)?.groupValues?.get(1)
            ?: error("无法读取登录流水号")
        return LoginPage(loginPage.url, salt, execution)
    }

    /**
     * 提交账密。失败分类（实测统一认证口径）：
     * - 密码错/风控拒/需验证码 → HTTP 401 + 页面 `formErrorTip2` 文案，App 侧过去一律翻译成
     *"账号或密码可能不正确"，把风控与验证码也误报成密码错，用户只会反复重试、越试越死；
     * - 流水号/会话失配 → 302 打回登录页（由调用方换新会话重试一次）；
     * - 5xx → 服务繁忙。
     */
    private fun submitPassword(page: LoginPage, username: String, password: String): LoginHttpResponse {
        val loginForm = form(
            "username" to username,
            "password" to encryptPassword(password, page.salt),
            "captcha" to "",
            "_eventId" to "submit",
            "cllt" to "userNameLogin",
            "dllt" to "generalLogin",
            "lt" to "",
            "execution" to page.execution,
        )
        val response = request(page.url, method = "POST", body = loginForm).requireNotServerError("密码提交")
        // 无重定向 + 4xx，或 200 但页面带了错误文案：都属服务端明确拒绝，交给分类器决定提示，
        // 不能再笼统地报"账号或密码可能不正确"。
        if (response.location == null &&
            (response.code in 400..499 || errorTipOf(response.body).isNotBlank())
        ) {
            classifyRejection(response)
        }
        return response
    }

    /** 401/403/429 按页面文案分流。只记状态码、needCaptcha 与文案摘要，绝不记密码或密文。 */
    private fun classifyRejection(response: LoginHttpResponse): Nothing {
        val tip = errorTipOf(response.body)
        val needCaptcha = Regex("""var needCaptcha = "([^"]*)"""").find(response.body)?.groupValues?.get(1).orEmpty()
        Log.w(TAG, "login rejected: code=${response.code} needCaptcha=$needCaptcha tip=${tip.take(60)}")
        val message = when {
            "验证码" in tip -> "登录需要验证码，请先用手机浏览器登录一次再回来重试"
            "锁定" in tip -> "账号已被锁定，请到统一身份认证站点解锁后再试"
            "非常用" in tip -> "账号被判定为非常用登录或密码有误，请先用手机浏览器登录确认密码"
            response.code == 429 -> "登录请求过于频繁，请约 30 分钟后重试"
            response.code == 403 -> "登录请求被拒绝（403），请稍后重试"
            else -> "账号或密码可能不正确"
        }
        throw IllegalStateException(message)
    }

    /** 从错误页取服务端提示文案（`formErrorTip2` 与 `showErrorTip` 两种模板）。 */
    private fun errorTipOf(body: String): String =
        Regex("""id="formErrorTip2"[^>]*>\s*<span>([^<]*)</span>""").find(body)?.groupValues?.get(1)?.trim()
            ?: Regex("""id="showErrorTip"[^>]*>\s*<span>([^<]*)</span>""").find(body)?.groupValues?.get(1)?.trim()
            ?: ""

    /** 密码提交被打回登录页（无 ticket），说明会话/流水号失配，不是密码错。 */
    private fun LoginHttpResponse.isLoginPageLanding(): Boolean =
        code in 300..399 && location?.contains("/authserver/login") == true

    private data class LoginPage(val url: String, val salt: String, val execution: String)

    private fun encryptPassword(password: String, salt: String): String {
        val plaintext = randomString(64) + password
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(salt.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(randomString(16).toByteArray(Charsets.UTF_8)),
        )
        return base64Encoder(cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHJKMNPQRSTWXYZabcdefhijkmnprstwxyz2345678"
        return (0 until length).map { chars[random.nextInt(chars.length)] }.joinToString("")
    }

    private fun request(
        url: String,
        method: String = "GET",
        contentType: String = "application/x-www-form-urlencoded; charset=utf-8",
        body: String = "",
    ): LoginHttpResponse {
        val response = transport.execute(
            LoginHttpRequest(
                url = url,
                method = method,
                contentType = contentType,
                body = body,
                cookieHeader = jar.header(url),
            ),
        )
        jar.read(response.headers, url)
        return response
    }

    private companion object {
        const val TAG = "XmuLogin"
    }

    private fun form(vararg pairs: Pair<String, String>): String =
        pairs.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
        }

    private fun resolve(location: String, base: String): String = URL(URL(base), location).toString()

}

class SimpleCookieJar {
    private val cookies = linkedMapOf<Triple<String, String, String>, Cookie>()

    @Synchronized
    fun clear() = cookies.clear()

    @Synchronized
    fun read(headers: Map<String, List<String>>, url: String = "https://lnt.xmu.edu.cn/") {
        val origin = url.toHttpUrl()
        headers.entries.filter { it.key.equals("Set-Cookie", ignoreCase = true) }
            .flatMap { it.value }.forEach { raw ->
                val cookie = Cookie.parse(origin, raw) ?: return@forEach
                val key = Triple(cookie.name, cookie.domain, cookie.path)
                if (cookie.expiresAt <= System.currentTimeMillis()) cookies.remove(key)
                else cookies[key] = cookie
            }
    }

    @Synchronized
    fun header(url: String = "https://lnt.xmu.edu.cn/"): String {
        val target = url.toHttpUrl()
        val now = System.currentTimeMillis()
        cookies.entries.removeAll { it.value.expiresAt <= now }
        return cookies.values.filter { it.matches(target) }.sortedByDescending { it.path.length }
            .joinToString("; ") { "${it.name}=${it.value}" }
    }
}
