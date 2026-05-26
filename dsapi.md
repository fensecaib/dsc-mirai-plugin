# ds-chat-mirai 开发文档

## 一、概述

基于 `weibo-dynamic`（mirai 微博动态解析插件）代码骨架，重构为 **ds-chat-mirai**——一个 mirai-console v2 插件，提供 DeepSeek API 对话功能，支持 QQ 群聊中的多轮上下文对话。

**产物**: `ds-chat-mirai-1.0.0.mirai2.jar`  
**插件 ID**: `top.colter.ds-chat-mirai`  
**微博解析**: 已禁用（`GroupMessageListener` 未注册）

---

## 二、项目结构

```
src/main/kotlin/top/colter/mirai/plugin/
├── dschat/
│   ├── DsChatPlugin.kt              # 插件入口
│   ├── WeiboConfig.kt               # 微博配置（保留未使用）
│   ├── deepseek/
│   │   ├── DeepSeekConfig.kt        # DeepSeek 配置（AutoSavePluginConfig）
│   │   ├── DeepSeekData.kt          # 请求/响应数据模型
│   │   └── DeepSeekClient.kt        # HTTP 客户端（Ktor OkHttp + 重试）
│   ├── lisener/
│   │   ├── DeepSeekListener.kt      # 对话监听器（核心）
│   │   └── GroupMessageListener.kt  # 微博监听（未注册）
│   ├── draw/
│   │   ├── ChatDraw.kt              # AI 回复图片渲染（RichText）
│   │   └── WeiboDraw.kt / ...       # 微博图片渲染（保留未使用）
│   ├── client/                      # HTTP 客户端基类
│   ├── data/                        # 微博数据模型（保留未使用）
│   └── tools/                       # 工具函数（json、logger、cache）
└── weibo/                           # 原项目代码（包已重命名，微博功能已禁用）
```

---

## 三、核心功能

### 3.1 触发方式

| 触发 | 示例 | 说明 |
|------|------|------|
| 前缀触发 | `/ds 今天天气怎么样` | `triggerPrefix` 配置项（默认 `/ds`） |
| @Bot 触发 | `@Bot 今天天气怎么样` | `enableAtTrigger` 配置项（默认开启） |

### 3.2 文本 vs 图片输出

| 条件 | 输出形式 |
|------|----------|
| `textReplyThreshold = 0` | 始终文本 |
| 回复字符数 ≤ 阈值（默认 400） | 文本 `subject.sendMessage()` + `QuoteReply` |
| 回复字符数 > 阈值 | Skia 渲染图片 `chatDraw()` → `subject.sendImage()` |
| 图片渲染失败 | 自动降级为文本 |

### 3.3 多轮上下文记忆

**隔离**: `SessionKey(groupId, userId)` — 按群+用户双维度隔离

**存储**: `ConcurrentHashMap<SessionKey, SessionEntry>`

```kotlin
data class SessionKey(val groupId: Long, val userId: Long)
class SessionEntry(
    val messages: ArrayDeque<ChatMessage> = ArrayDeque(),
    var lastAccessTime: Long = System.currentTimeMillis()
)
```

**拼接**: 每次 API 请求 `messages = [system] + [session历史] + [当前 user 消息]`

**双层溢出保护**:

| 层级 | 参数 | 触发条件 | 动作 |
|------|------|----------|------|
| 轮次限制 | `memoryRounds`（默认 10） | 超过 `rounds × 2` 条消息 | 成对删除最旧 user+assistant |
| Token 限制 | `maxContextTokens`（默认 32000） | 估算 token 超限 | 成对删除直到达标 |

**TTL 惰性过期**: 每次用户发消息时检查，30 分钟无活动则清除会话（无后台扫表协程）。

**手动清空**:

| 指令 | 行为 |
|------|------|
| `/ds clear` | 立即清空当前会话 |
| `/ds 重置` | 同上 |
| `/ds 新建对话` | 同上 |
| `/ds 清空上下文` | 同上 |

### 3.4 自定义提示词文件

优先级：

```
data/top.colter.ds-chat-mirai/system-prompt.txt 存在  →  读取完整内容
system-prompt.txt 不存在                              →  回退到 YAML 中的 systemPrompt
```

---

## 四、配置文件

插件首次加载后自动生成：`config/top.colter.ds-chat-mirai/DeepSeekConfig.yml`

```yaml
api:
  apiKey: ""                                          # DeepSeek API Key（必填）
  apiUrl: "https://api.deepseek.com/chat/completions"
  model: "deepseek-v4-flash"                          # 或 deepseek-v4-pro

chat:
  systemPrompt: "你是一个有帮助的AI助手。"              # system-prompt.txt 存在时失效
  maxTokens: 4096                                     # 单次回复最大 token（硬截断）
  textReplyThreshold: 400                             # 转图片的字符数阈值（0=始终文本）
  enableMemory: true                                  # 上下文记忆开关
  memoryRounds: 10                                    # 最大记忆轮数
  memoryTtlMinutes: 30                                # 无活动过期时间
  maxContextTokens: 32000                             # 上下文 token 上限

trigger:
  triggerPrefix: "/ds"
  enableAtTrigger: true

image:
  defaultColor: "#667eea;#764ba2"                     # 图片主题色（支持渐变，分号分隔）
  factor: 1.0                                         # 图片倍率
```

**参数说明**:

| 参数 | 作用域 | 解释 |
|------|--------|------|
| `maxTokens` | API 层 | 本次回复最长输出 token，超限硬截断并追加提示 |
| `maxContextTokens` | 本地 Session | 历史上下文 token 估算上限，超限成对删除旧轮次 |
| `textReplyThreshold` | 输出层 | 回复文本超过此字符数自动转图片 |

---

## 五、架构与数据流

```
QQ群消息
  ├── 命中 /ds 前缀或 @Bot → DeepSeekListener
  │     ├── /ds clear? → 清空会话
  │     ├── 检查 Session TTL → 过期则重建
  │     ├── 拼接 [system + context + user] → ChatRequest
  │     ├── DeepSeekClient.chat() → POST API（2次重试 + 指数退避）
  │     ├── onSuccess:
  │     │     ├── 检查 finish_reason → 正常/截断/过滤
  │     │     ├── 字符数 > threshold? → chatDraw() 渲染图片
  │     │     └── saveToSession() → trimSession()
  │     └── onFailure: 错误提示
  └── (微博监听已禁用)
```

### 复用能力

| 能力 | 来源 | 用途 |
|------|------|------|
| `SimpleListenerHost` + `@EventHandler` | mirai-core | 消息监听 |
| `Ktor OkHttp` + `json` | `tools/General.kt` | HTTP 请求 + JSON 序列化 |
| `AutoSavePluginConfig` | mirai-console | 配置持久化 |
| `RichParagraphBuilder` + `RichText` | skiko-layout | 长文本图片渲染 |
| `Skia Surface` | mirai-skia-plugin | 图片输出 |
| `FontUtils.loadTypeface` | skiko | 中文字体加载 |

---

## 六、DeepSeek API 集成

| 项目 | 值 |
|------|-----|
| Endpoint | `POST https://api.deepseek.com/chat/completions` |
| Auth | `Authorization: Bearer <api_key>` |
| 模型 | `deepseek-v4-flash`（可配置为 deepseek-v4-pro） |
| 思考模式 | `disabled`（flash 禁用；pro 建议 `enabled`） |
| 输出模式 | 非流式 |
| 超时 | 120s（连接 30s） |
| 重试 | 2 次，指数退避，仅对 IO/5xx 重试 |

### 请求体

```json
{
  "model": "deepseek-v4-flash",
  "messages": [
    {"role": "system", "content": "<提示词>"},
    {"role": "user", "content": "<问题>"}
  ],
  "thinking": {"type": "disabled"},
  "stream": false,
  "max_tokens": 4096
}
```

### finish_reason 处理

| 值 | 含义 | 行为 |
|----|------|------|
| `stop` | 正常结束 | 返回 content |
| `length` | 达 maxTokens 截断 | content + "(回复过长，已被截断)" |
| `content_filter` | 安全过滤 | "内容已被安全过滤..." |
| `insufficient_system_resource` | 资源不足 | "服务繁忙，请稍后重试" |

### 切换到 deepseek-v4-pro

1. 配置文件 `model: "deepseek-v4-pro"`
2. `DeepSeekData.kt` 中 `ThinkingConfig.type = "enabled"`
3. `DeepSeekClient.kt` 中超时调至 180s

---

## 七、构建与部署

### 编译

```powershell
cd D:\Projects\python\colter
.\gradlew.bat clean buildPlugin -x test
```

### 产物

```
build\mirai\ds-chat-mirai-1.0.0.mirai2.jar
```

### 部署

1. 将 jar 放入 mirai-console 的 `plugins/` 目录
2. 启动 mirai-console
3. 编辑 `config/top.colter.ds-chat-mirai/DeepSeekConfig.yml`，填写 `apiKey`
4. 可选：在 `data/top.colter.ds-chat-mirai/` 下放置 `system-prompt.txt`
5. 群内发送 `/ds <问题>` 测试

---

## 八、已知问题

### 8.1 敏感信息

`WeiboClient.kt:22` 包含原作者硬编码的微博 Cookie（`XSRF-TOKEN`, `SUB`, `SUBP`）。微博功能已禁用，此文件不会被执行，但上传公共仓库前建议将 Cookie 值替换为占位符。

### 8.2 微博代码残留

原项目包 `top.colter.mirai.plugin.weibo.*` 中的所有文件均已将 `package` 声明改为 `dschat`，但物理目录在 `src/main/kotlin/.../weibo/` 下。不影响编译运行，可后续清理。

---

## 九、配置参数速查

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `api.apiKey` | `""` | DeepSeek API Key |
| `api.model` | `deepseek-v4-flash` | 模型选择 |
| `chat.maxTokens` | 4096 | 回复最大 token（硬截断） |
| `chat.textReplyThreshold` | 400 | 转图片字符阈值（0=始终文本） |
| `chat.enableMemory` | `true` | 上下文记忆 |
| `chat.memoryRounds` | 10 | 最大记忆轮数 |
| `chat.memoryTtlMinutes` | 30 | 过期时间 |
| `chat.maxContextTokens` | 32000 | 上下文 token 上限 |
| `trigger.triggerPrefix` | `/ds` | 触发前缀 |
| `trigger.enableAtTrigger` | `true` | @Bot 触发 |
| `image.defaultColor` | `#667eea;#764ba2` | 图片主题色 |
