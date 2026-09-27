package com.kylins.amapnav.phone

import com.kylins.amapnav.core.*
import java.net.HttpURLConnection
import java.net.URI

/** Follows only first-party HTTPS redirects; never renders remote HTML or reads browser cookies. */
object ShareResolver {
    fun resolve(input: String): Journey {
        require(input.length<=8192) { "链接太长" }
        ShareRoute.parse(input)?.let { return it }
        var address=URI(ShareRoute.urlIn(input) ?: throw IllegalArgumentException("请粘贴高德的路线链接"))
        repeat(5) {
            require(address.toString().length<=8192) { "跳转链接太长" }
            require(ShareRoute.allowed(address)) { "仅支持高德 HTTPS 分享链接" }
            ShareRoute.parse(address.toString())?.let { return it }
            val connection=address.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects=false; connection.connectTimeout=7000;connection.readTimeout=7000
            connection.setRequestProperty("User-Agent","KylinsAmapNav/0.3 Android")
            try {
                val code=connection.responseCode
                if(code in listOf(301,302,303,307,308)) {
                    address=address.resolve(connection.getHeaderField("Location") ?: throw IllegalArgumentException("分享链接缺少跳转地址"))
                } else throw IllegalArgumentException("这条链接未包含可识别的步行路线；请使用高德路线分享或手动设置")
            } finally { connection.disconnect() }
        }
        return ShareRoute.parse(address.toString()) ?: throw IllegalArgumentException("分享跳转过多或不支持此路线")
    }
}
