package io.github.mangi.eta.agent.mcp

import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * MCP 传输层失败分类。
 *
 * 之前所有失败都被压成一句「MCP 请求失败」，设置页与工具执行器无法区分
 * 网络不可达 / 认证失败 / 端点不存在 / 协议不兼容 / 限流。分类后上层可以给出
 * 准确提示，同时保持既有异常类型仍是 [IOException]，不改变调用方的错误处理结构。
 */
internal enum class McpFailureKind {
    /** 域名解析失败、连接被拒绝、路由不可达。 */
    NETWORK_UNREACHABLE,

    /** 连接、读取或整请求超时。 */
    TIMEOUT,

    /** TLS 握手或证书校验失败。 */
    TLS_FAILURE,

    /** HTTP 401/403/407。 */
    AUTHENTICATION,

    /** HTTP 404/410，通常是端点路径写错或服务未挂载。 */
    ENDPOINT_NOT_FOUND,

    /** HTTP 429。 */
    RATE_LIMITED,

    /** 协议版本、方法或请求头不被服务端接受。 */
    PROTOCOL_INCOMPATIBLE,

    /** 重定向目标跨源或层数过多，按安全策略停止跟随。 */
    UNSAFE_REDIRECT,

    /** HTTP 5xx。 */
    SERVER_ERROR,

    /** 其余 4xx。 */
    BAD_REQUEST,

    /** 客户端已关闭。 */
    CLIENT_CLOSED,

    /** 响应不是可解析的 JSON-RPC / SSE 载荷。 */
    MALFORMED_RESPONSE,

    UNKNOWN,
}

/** 按 HTTP 状态码分类，JSON-RPC 错误码只用于区分「协议层拒绝」与「普通 4xx」。 */
internal fun classifyHttpStatus(code: Int, jsonRpcCode: Int? = null): McpFailureKind = when (code) {
    401, 403, 407 -> McpFailureKind.AUTHENTICATION
    404, 410 -> McpFailureKind.ENDPOINT_NOT_FOUND
    429 -> McpFailureKind.RATE_LIMITED
    405, 406, 415, 426 -> McpFailureKind.PROTOCOL_INCOMPATIBLE
    in 500..599 -> McpFailureKind.SERVER_ERROR
    in 300..399 -> McpFailureKind.UNSAFE_REDIRECT
    else -> if (jsonRpcCode != null) {
        McpFailureKind.PROTOCOL_INCOMPATIBLE
    } else {
        McpFailureKind.BAD_REQUEST
    }
}

/** 把任意抛出物归一到分类，供上层与测试统一断言。 */
internal fun classifyFailure(failure: Throwable): McpFailureKind = when (failure) {
    is McpTransportException -> failure.kind
    is McpHttpStatusException -> failure.kind
    is McpProtocolCompatibilityException -> McpFailureKind.PROTOCOL_INCOMPATIBLE
    is SocketTimeoutException -> McpFailureKind.TIMEOUT
    is SSLException -> McpFailureKind.TLS_FAILURE
    is UnknownHostException -> McpFailureKind.NETWORK_UNREACHABLE
    is ConnectException -> McpFailureKind.NETWORK_UNREACHABLE
    is NoRouteToHostException -> McpFailureKind.NETWORK_UNREACHABLE
    is InterruptedIOException -> McpFailureKind.TIMEOUT
    else -> McpFailureKind.UNKNOWN
}

/** 面向用户的一句话说明。 */
internal fun McpFailureKind.userHint(): String = when (this) {
    McpFailureKind.NETWORK_UNREACHABLE -> "无法连接 MCP 服务器（网络不可达或端口未监听）"
    McpFailureKind.TIMEOUT -> "MCP 请求超时"
    McpFailureKind.TLS_FAILURE -> "MCP 服务器 TLS 握手或证书校验失败"
    McpFailureKind.AUTHENTICATION -> "MCP 服务器拒绝认证（HTTP 401/403），请检查 Bearer Token"
    McpFailureKind.ENDPOINT_NOT_FOUND -> "MCP 端点不存在（HTTP 404），请检查服务器地址"
    McpFailureKind.RATE_LIMITED -> "MCP 服务器限流（HTTP 429），请稍后重试"
    McpFailureKind.PROTOCOL_INCOMPATIBLE -> "MCP 协议不兼容，服务端拒绝了当前协议版本或方法"
    McpFailureKind.UNSAFE_REDIRECT -> "MCP 端点返回了不安全的重定向"
    McpFailureKind.SERVER_ERROR -> "MCP 服务器内部错误（HTTP 5xx）"
    McpFailureKind.BAD_REQUEST -> "MCP 请求被服务器拒绝（HTTP 4xx）"
    McpFailureKind.CLIENT_CLOSED -> "MCP 客户端已关闭"
    McpFailureKind.MALFORMED_RESPONSE -> "MCP 响应格式无效"
    McpFailureKind.UNKNOWN -> "MCP 请求失败"
}

/** 工具执行器返回给模型的错误码，未知分类仍回落到既有的 MCP_CALL_FAILED。 */
internal val McpFailureKind.toolFailureCode: String
    get() = when (this) {
        McpFailureKind.AUTHENTICATION -> "MCP_AUTH_FAILED"
        McpFailureKind.ENDPOINT_NOT_FOUND -> "MCP_ENDPOINT_NOT_FOUND"
        McpFailureKind.RATE_LIMITED -> "MCP_RATE_LIMITED"
        McpFailureKind.PROTOCOL_INCOMPATIBLE, McpFailureKind.UNSAFE_REDIRECT ->
            "MCP_PROTOCOL_INCOMPATIBLE"
        McpFailureKind.NETWORK_UNREACHABLE, McpFailureKind.TLS_FAILURE ->
            "MCP_NETWORK_UNREACHABLE"
        McpFailureKind.TIMEOUT -> "MCP_TIMEOUT"
        McpFailureKind.CLIENT_CLOSED -> "MCP_EXECUTOR_CLOSED"
        McpFailureKind.MALFORMED_RESPONSE -> "MCP_INVALID_RESPONSE"
        McpFailureKind.SERVER_ERROR, McpFailureKind.BAD_REQUEST, McpFailureKind.UNKNOWN ->
            "MCP_CALL_FAILED"
    }

internal const val MCP_MAX_ERROR_CHARS = 200

/** 传输层失败：携带分类，消息保持可直接展示的中文。 */
internal class McpTransportException(
    val kind: McpFailureKind,
    safeMessage: String,
    cause: Throwable? = null,
    /**
     * 该失败是否更像「服务端不认识现代协议」而不是「服务器坏了」。
     *
     * 只有这类失败才允许 AUTO 模式回退到 legacy 握手；大小超限等失败必须原样上报，
     * 否则会把一个明显的资源问题误判成协议版本问题。
     */
    val compatibility: Boolean = false,
) : IOException(safeMessage.take(MCP_MAX_ERROR_CHARS), cause) {
    constructor(kind: McpFailureKind, cause: Throwable) : this(
        kind = kind,
        safeMessage = cause.message?.take(MCP_MAX_ERROR_CHARS)?.ifBlank { null }
            ?: kind.userHint(),
        cause = cause,
    )
}
