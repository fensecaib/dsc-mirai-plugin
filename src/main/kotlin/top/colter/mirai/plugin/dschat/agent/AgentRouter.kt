package top.colter.mirai.plugin.dschat.agent

import top.colter.mirai.plugin.dschat.deepseek.DeepSeekConfig

// 根据配置创建对应的Agent实例
object AgentRouter {
    fun create(config: DeepSeekConfig): Agent {
        return if (config.webSearch.enabled) SearchAgent(config.webSearch) else ChatAgent()
    }
}
