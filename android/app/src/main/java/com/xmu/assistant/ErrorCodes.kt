package com.xmu.assistant

/**
 * 内部错误码。
 *
 * 这些字符串不是给用户看的文案，而是模块之间传递的**协议值**：网络层 throw，
 * SectionState 转成 UI 状态，RefreshUiState / UiComponents 再按值映射成人话。
 *此前同一句字面量散落在二十处，改一处就静默失配。
 * 现在单点定义：改值只改这里，匹配点全部跟着走。
 *
 * 不包含用于匹配服务端返回文本的关键词（如 JwHttpBase 里的「请先登录」）——
 * 那是外部契约，与本地协议值语义不同，不能混在一起。
 */
internal object ErrorCodes {
    /** 连接层失败：超时、DNS、代理、非 2xx。 */
    const val NETWORK_FAILURE = "网络失败"

    /** 会话失效：CAS 跳转、401、无权访问。 */
    const val LOGIN_EXPIRED = "登录过期"

    /** 并发保护：代际 token 已换，结果属于上一个会话。 */
    const val SESSION_CHANGED = "会话已改变"

    /** 平台返回了记录，但下载地址为空或不是 http(s)。 */
    const val URL_MISSING = "平台未提供地址"
}
