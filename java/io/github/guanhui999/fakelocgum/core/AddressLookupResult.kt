package io.github.guanhui999.fakelocgum.core

/** Keep the failure cause all the way to the UI; retries cannot fix a missing quota/key. */
data class AddressLookupResult(val address: PickedAddress? = null, val status: Int = 0,
                               val detail: String = "", val needsSettings: Boolean = false) {
    val success: Boolean get() = address?.isResolved == true
    companion object {
        fun failure(status: Int): AddressLookupResult = when (status) {
            -3 -> AddressLookupResult(status = status, detail = "未配置腾讯地图 Key，系统地址服务也未返回结果", needsSettings = true)
            -2 -> AddressLookupResult(status = status, detail = "地址请求超时，请检查网络后重试")
            -1 -> AddressLookupResult(status = status, detail = "地址服务未响应，请检查网络和地图 SDK 配置")
            111 -> AddressLookupResult(status = status, detail = "签名校验失败（111），请检查地图 SK", needsSettings = true)
            110, 112 -> AddressLookupResult(status = status, detail = "地址接口授权失败（$status），请检查 Key 的服务授权", needsSettings = true)
            113 -> AddressLookupResult(status = status, detail = "逆地址解析额度不足（113），请在腾讯控制台配置额度", needsSettings = true)
            199 -> AddressLookupResult(status = status, detail = "未开通逆地址解析服务（199）", needsSettings = true)
            190, 301, 311 -> AddressLookupResult(status = status, detail = "地图 Key 无效或授权不匹配（$status）", needsSettings = true)
            120 -> AddressLookupResult(status = status, detail = "地址服务调用频率受限（120），稍后重试")
            0 -> AddressLookupResult(detail = "地址服务未返回可用的城市或地址，请换点或重试")
            else -> AddressLookupResult(status = status, detail = "地址服务返回错误 $status，请检查地图配置")
        }
    }
}
