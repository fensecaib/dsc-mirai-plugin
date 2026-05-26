package top.colter.mirai.plugin.dschat.client

public interface StatusCode {
    public val code: Int
    public val message: String

    public fun handleStatus()
}