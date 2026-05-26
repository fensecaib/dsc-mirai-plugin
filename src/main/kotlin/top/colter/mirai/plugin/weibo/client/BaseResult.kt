package top.colter.mirai.plugin.dschat.client

import kotlinx.serialization.json.JsonElement

public interface BaseResult: StatusCode {
    override val code: Int
    override val message: String
    public val data: JsonElement?
}