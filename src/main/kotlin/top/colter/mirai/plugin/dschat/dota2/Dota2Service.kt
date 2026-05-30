package top.colter.mirai.plugin.dschat.dota2

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.jetbrains.skia.Image
import top.colter.mirai.plugin.dschat.DsChatPlugin
import top.colter.mirai.plugin.dschat.deepseek.ChatMessage
import top.colter.mirai.plugin.dschat.deepseek.ChatRequest
import top.colter.mirai.plugin.dschat.deepseek.DeepSeekClient
import top.colter.mirai.plugin.dschat.deepseek.ThinkingConfig
import top.colter.mirai.plugin.dschat.draw.Dota2MatchReport
import top.colter.mirai.plugin.dschat.draw.Dota2PlayerCard
import top.colter.mirai.plugin.dschat.tools.json
import top.colter.mirai.plugin.dschat.tools.logger
import java.io.File

object Dota2Service {
    private const val API_BASE = "https://api.opendota.com/api"

    private val client = HttpClient(OkHttp) {
        install(HttpTimeout) { requestTimeoutMillis = 30_000; connectTimeoutMillis = 15_000 }
    }

    // ── 绑定 ────────────────────────────────────
    private val bindFile by lazy {
        DsChatPlugin.dataFolderPath.resolve("dota2-bindings.json").toFile()
    }
    private val bindings = mutableMapOf<Long, Long>() // qqId -> accountId

    fun init() {
        if (bindFile.exists()) {
            try {
                val raw = json.decodeFromString(JsonObject.serializer(), bindFile.readText())
                raw.forEach { (k, v) -> bindings[k.toLong()] = v.jsonPrimitive.content.toLong() }
                logger.info("加载了 ${bindings.size} 条Dota2绑定")
            } catch (e: Exception) {
                logger.warning("绑定文件损坏，已重置")
                bindFile.writeText("{}")
            }
        }
    }

    private fun saveBindings() {
        val obj = buildJsonObject { bindings.forEach { (k, v) -> put(k.toString(), v) } }
        bindFile.writeText(json.encodeToString(JsonObject.serializer(), obj))
    }

    fun getBinding(qqId: Long): Long? = bindings[qqId]

    fun setBinding(qqId: Long, accountId: Long) {
        bindings[qqId] = accountId
        saveBindings()
    }

    // ── API ─────────────────────────────────────
    suspend fun validatePlayer(accountId: Long): String? {
        return try {
            val r = client.get("$API_BASE/players/$accountId")
            if (!r.status.isSuccess()) return null
            val data = json.decodeFromString(JsonObject.serializer(), r.bodyAsText())
            data["profile"]?.jsonObject?.get("personaname")?.jsonPrimitive?.content
        } catch (e: Exception) {
            logger.warning("Validate player failed: $accountId - ${e.message}")
            null
        }
    }

    suspend fun getRecentMatches(accountId: Long, limit: Int = 1): JsonArray? {
        return try {
            val r = client.get("$API_BASE/players/$accountId/matches") {
                parameter("limit", limit); parameter("significant", 1)
            }
            if (!r.status.isSuccess()) return null
            json.decodeFromString(JsonArray.serializer(), r.bodyAsText())
        } catch (e: Exception) {
            logger.warning("Get matches failed: $accountId - ${e.message}")
            null
        }
    }

    suspend fun getMatchDetail(matchId: Long): JsonObject? {
        return try {
            val r = client.get("$API_BASE/matches/$matchId")
            if (!r.status.isSuccess()) return null
            json.decodeFromString(JsonObject.serializer(), r.bodyAsText())
        } catch (e: Exception) {
            logger.warning("Get match failed: $matchId - ${e.message}")
            null
        }
    }

    // ── 英雄映射 ────────────────────────────────
    private val heroNames = mutableMapOf<Int, String>()
    private val heroIconPaths = mutableMapOf<Int, String>()   // id → CDN相对路径
    private val itemIconPaths = mutableMapOf<Int, String>()   // id → CDN相对路径
    private var constantsLoaded = false

    private const val CDN_BASE = "https://cdn.cloudflare.steamstatic.com"

    private suspend fun ensureConstants() {
        if (constantsLoaded) return
        try {
            val heroResp = client.get("$API_BASE/constants/heroes")
            if (heroResp.status.isSuccess()) {
                val heroObj = json.decodeFromString(JsonObject.serializer(), heroResp.bodyAsText())
                heroObj.forEach { (_, v) ->
                    val hero = v.jsonObject
                    val id = hero["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    heroNames[id] = hero["localized_name"]?.jsonPrimitive?.content ?: "Hero_$id"
                    val img = hero["img"]?.jsonPrimitive?.content?.substringBefore("?") ?: ""
                    if (img.isNotEmpty()) heroIconPaths[id] = img
                }
                logger.info("加载了 ${heroNames.size} 个英雄映射")
            }
            val itemResp = client.get("$API_BASE/constants/items")
            if (itemResp.status.isSuccess()) {
                val itemObj = json.decodeFromString(JsonObject.serializer(), itemResp.bodyAsText())
                itemObj.forEach { (_, v) ->
                    val item = v.jsonObject
                    val id = item["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
                    val img = item["img"]?.jsonPrimitive?.content?.substringBefore("?") ?: ""
                    if (img.isNotEmpty()) itemIconPaths[id] = img
                }
                logger.info("加载了 ${itemIconPaths.size} 个物品映射")
            }
        } catch (e: Exception) {
            logger.warning("加载常量失败: ${e.message}")
        }
        constantsLoaded = true
    }

    fun heroName(id: Int) = heroNames[id] ?: "Hero_$id"

    // ── 图标下载 ─────────────────────────────────
    private val iconCacheDir by lazy {
        DsChatPlugin.dataFolderPath.resolve("dota2/icons").toFile().also { it.mkdirs() }
    }

    private suspend fun downloadIcon(cdnPath: String, cacheType: String): Image? {
        val fileName = cdnPath.split("/").last()
        val file = iconCacheDir.resolve(cacheType).also { it.mkdirs() }.resolve(fileName)
        if (file.exists() && file.length() > 1024) {
            return try { Image.makeFromEncoded(file.readBytes()) } catch (_: Exception) { null }
        }
        try {
            val bytes = client.get("$CDN_BASE$cdnPath").body<ByteArray>()
            if (bytes.size < 1024) return null
            file.writeBytes(bytes)
            return Image.makeFromEncoded(bytes)
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun loadHeroIcon(heroId: Int): Image? {
        val path = heroIconPaths[heroId] ?: return null
        return downloadIcon(path, "heroes")
    }

    private suspend fun loadItemIcon(itemId: Int): Image? {
        if (itemId == 0) return null
        val path = itemIconPaths[itemId] ?: return null
        return downloadIcon(path, "items")
    }

    // ── A杖/魔晶图标 ─────────────────────────────
    private var aghsScepterYes: Image? = null
    private var aghsScepterNo: Image? = null
    private var aghsShardYes: Image? = null
    private var aghsShardNo: Image? = null

    private suspend fun ensureAghsIcons() {
        if (aghsScepterNo != null) return
        val base = "https://www.opendota.com/assets/images/dota2"
        aghsScepterYes = downloadDirect("$base/scepter_1.png")
        aghsScepterNo  = downloadDirect("$base/scepter_0.png")
        aghsShardYes   = downloadDirect("$base/shard_1.png")
        aghsShardNo    = downloadDirect("$base/shard_0.png")
    }

    fun getAghsIcon(has: Boolean, isShard: Boolean): Image? {
        return if (isShard) (if (has) aghsShardYes else aghsShardNo)
        else (if (has) aghsScepterYes else aghsScepterNo)
    }

    private suspend fun downloadDirect(url: String): Image? {
        val fileName = url.split("/").last()
        val file = iconCacheDir.resolve("aghs").also { it.mkdirs() }.resolve(fileName)
        if (file.exists() && file.length() > 512) {
            return try { Image.makeFromEncoded(file.readBytes()) } catch (_: Exception) { null }
        }
        try {
            val bytes = client.get(url).body<ByteArray>()
            if (bytes.size < 512) return null
            file.writeBytes(bytes)
            return Image.makeFromEncoded(bytes)
        } catch (e: Exception) { return null }
    }

    // ── DS分析 ───────────────────────────────────
    private val dota2SystemPrompt = """
You are a Dota 2 post-match roast analyst. You analyze only ONE team's data. Your tone is like a drill sergeant debriefing — ruthless and direct. **Output language: Simplified Chinese only.**

=== Scoring Weights (core role priority — carry picks first) ===
GPM(25%) + CS(20%) + Tower(20%) + Kills(15%) + Death(15%) + KDA(5%)
Carry's death costs 10x more than support — a dead carry = no DPS + enemy push + lost Roshan.
If a player leads GPM+CS+Tower in all 3, they are the MVP/SVP even with mediocre KDA.

=== Percentile Fields (FOR INTERNAL JUDGMENT — DO NOT cite % in output) ===
GPM%/DMG%/TWR% show how this player ranks among peers on the same hero. Use them to distinguish "good performance" from "good hero" — never cite them directly.

=== Three Evaluation Criteria ===
(1) Core Trifecta: If a player leads the team in GPM+CS+Tower, they are the primary carry — MVP/SVP first candidate regardless of KDA.
(2) Death Weight: Every death subtracts. A carry's 15 deaths cost the team roughly the same as a support's 30 assists gain. Deaths >= 15 triggers criminal review.
(3) Kill Quality: Kills matter more than assists. 14 kills is worth more than 9 kills + 24 assists — the former means you can solo-kill, the latter means you clean up.

=== Style Rules ===
- You are a drill sergeant humiliating a squad that just got wiped. Public execution style. No mercy.
- Military/financial/medical metaphors: economic black hole, mobile ATM, reverse carry, backpack training, combat medic on life support
- Weave numbers into sentences naturally, like casual trashtalk. Never use brackets or parentheses around numbers.
- MVP: praise 3-4 lines with data, then roast 3-4 lines exposing fatal flaws. Even the MVP gets humiliated.
- SVP: highlight 2 data bright spots, then burn 4-5 lines dismantling the "I tried" illusion — expose why the SVP is a fraud
- Criminal: 6-7 lines of escalating humiliation. Start clinical, end barbaric. The last 2 lines should be pure personal attacks based on data.
- EVERY section MUST end with exactly one line break + "— " followed by a Chinese gaming slang punchline. This is NON-NEGOTIABLE.

=== Output Format (STRICT) ===
[战犯]
<HeroName>(<PlayerName>): <8-10 sentences, natural style, no number brackets required>
— <gaming slang in Chinese>

[MVP] or [SVP] — output only one based on win/loss, never both
<HeroName>(<PlayerName>): <8-10 sentences, natural style, no number brackets required>
— <gaming slang in Chinese>

=== Iron Rules ===
1. Exactly two sections. Win→[MVP], Loss→[SVP]. Never both.
2. Each section: 8-10 sentences + 1 slang line. Slang line MUST start on a new line with "— ".
3. Criminal ≠ MVP/SVP. Mutual exclusion.
4. Reuse exact hero/player names from the data table.
5. Section tags [战犯][MVP][SVP] are mandatory. Otherwise never use brackets or parentheses in sentences.
6. Chinese output only.
""".trimIndent()

    suspend fun analyzeMatch(
        myAccountId: Long,
        matchJson: JsonObject,
        dsClient: DeepSeekClient,
        model: String
    ): DsAnalysisResult? {
        ensureConstants()

        val players = matchJson["players"]?.jsonArray ?: return null
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: return null

        // 找到"我"的位置，确定我方阵营
        val me = players.find {
            it.jsonObject["account_id"]?.jsonPrimitive?.longOrNull == myAccountId
        } ?: return null
        val myTeam = if (me.jsonObject["isRadiant"]?.jsonPrimitive?.boolean == true) 0 else 1
        val weWon = (myTeam == 0) == radiantWin

        val myTeamPlayers = players.filter {
            val isR = it.jsonObject["isRadiant"]?.jsonPrimitive?.boolean ?: false
            val team = if (isR) 0 else 1
            team == myTeam
        }

        val sb = StringBuilder()
        sb.appendLine("比赛结果: ${if(weWon)"我们赢了" else "我们输了"}。请输出[战犯]和[${if(weWon)"MVP" else "SVP"}]。不要输出${if(weWon)"[SVP]" else "[MVP]"}。")
        sb.appendLine()
        sb.appendLine("玩家 | 英雄 | K/D/A | KDA | GPM | GPM% | 伤害 | 伤害% | 塔伤 | 塔伤% | CS")
        sb.appendLine("---|---|---|---|---|---|---|---|---|---|---")
        for (p in myTeamPlayers) {
            val obj = p.jsonObject
            val bm = obj["benchmarks"]?.jsonObject
            val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0
            val d = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0
            val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0
            val kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val gpmPct = bm?.get("gold_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val dmgPct = bm?.get("hero_damage_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val twrPctVal = bm?.get("tower_damage")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            sb.appendLine("${obj["personaname"]?.jsonPrimitive?.content ?: "?"} | " +
                "${heroName(obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0)} | " +
                "$k/$d/$a | " +
                java.lang.String.format("%.1f", kda) + " | " +
                "${obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${gpmPct?.let { java.lang.String.format("%.0f%%", it * 100) } ?: "-"} | " +
                "${obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${dmgPct?.let { java.lang.String.format("%.0f%%", it * 100) } ?: "-"} | " +
                "${obj["tower_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${twrPctVal?.let { java.lang.String.format("%.0f%%", it * 100) } ?: "-"} | " +
                "${obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0}")
        }

        val messages = listOf(
            ChatMessage("system", dota2SystemPrompt),
            ChatMessage("user", "分析我方队伍的比赛数据，找出MVP(如果赢了)/SVP(如果输了)和战犯：\n\n$sb")
        )

        val request = ChatRequest(
            model = model, messages = messages,
            thinking = ThinkingConfig(type = "enabled"),
            maxTokens = 16384
        )

        val result = dsClient.chat(request)
        return result.fold(
            onSuccess = { response ->
                val content = response.choices.firstOrNull()?.message?.content ?: ""
                logger.info("DS analysis raw: ${content.take(500)}")
                val parsed = parseAnalysisResult(content)
                if (parsed != null) {
                    logger.info("DS parsed: MVP/SVP=${parsed.mvpOrSvp} (${parsed.mvpOrSvpText.length}chars) criminal=${parsed.criminal} (${parsed.criminalText.length}chars)")
                }
                parsed
            },
            onFailure = {
                logger.error("DS analysis failed: ${it.message}")
                null
            }
        )
    }

    // ── 双阵营分析（不绑定账号） ─────────────────
    suspend fun analyzeFullMatch(
        matchJson: JsonObject,
        dsClient: DeepSeekClient,
        model: String
    ): DualAnalyzeResult? {
        val radiantResult = analyzeTeamSide(0, matchJson, dsClient, model)
        val direResult = analyzeTeamSide(1, matchJson, dsClient, model)
        return DualAnalyzeResult(radiantResult, direResult)
    }

    data class DualAnalyzeResult(
        val radiant: DsAnalysisResult?,
        val dire: DsAnalysisResult?
    )

    private suspend fun analyzeTeamSide(
        side: Int,
        matchJson: JsonObject,
        dsClient: DeepSeekClient,
        model: String
    ): DsAnalysisResult? {
        ensureConstants()
        val players = matchJson["players"]?.jsonArray ?: return null
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: return null
        val weWon = (side == 0) == radiantWin

        val sidePlayers = players.filter {
            val isR = it.jsonObject["isRadiant"]?.jsonPrimitive?.boolean ?: false
            val team = if (isR) 0 else 1
            team == side
        }

        val sb = StringBuilder()
        val sideName = if (side == 0) "天辉" else "夜魇"
        sb.appendLine("分析${sideName}队伍。${if(weWon)"赢了" else "输了"}。请输出[战犯]和[${if(weWon)"MVP" else "SVP"}]。不要输出${if(weWon)"[SVP]" else "[MVP]"}。")
        sb.appendLine()
        sb.appendLine("玩家 | 英雄 | K/D/A | KDA | GPM | GPM% | 伤害 | 伤害% | 塔伤 | 塔伤% | CS")
        sb.appendLine("---|---|---|---|---|---|---|---|---|---|---")
        for (p in sidePlayers) {
            val obj = p.jsonObject
            val bm = obj["benchmarks"]?.jsonObject
            val k = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0
            val d = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0
            val a = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0
            val kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0
            val gpmPct = bm?.get("gold_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val dmgPct = bm?.get("hero_damage_per_min")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            val twrPctVal = bm?.get("tower_damage")?.jsonObject?.get("pct")?.jsonPrimitive?.doubleOrNull
            sb.appendLine("${obj["personaname"]?.jsonPrimitive?.content ?: "?"} | " +
                "${heroName(obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0)} | " +
                "$k/$d/$a | " +
                java.lang.String.format("%.1f", kda) + " | " +
                "${obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${gpmPct?.let { java.lang.String.format("%.0f%%", it * 100) } ?: "-"} | " +
                "${obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${dmgPct?.let { java.lang.String.format("%.0f%%", it * 100) } ?: "-"} | " +
                "${obj["tower_damage"]?.jsonPrimitive?.intOrNull ?: 0} | " +
                "${twrPctVal?.let { java.lang.String.format("%.0f%%", it * 100) } ?: "-"} | " +
                "${obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0}")
        }

        val messages = listOf(
            ChatMessage("system", dota2SystemPrompt),
            ChatMessage("user", "$sideName:\n\n$sb")
        )
        val request = ChatRequest(model = model, messages = messages, thinking = ThinkingConfig(type = "enabled"), maxTokens = 16384)
        val result = dsClient.chat(request)
        return result.fold(
            onSuccess = { response ->
                val content = response.choices.firstOrNull()?.message?.content ?: ""
                parseAnalysisResult(content)
            },
            onFailure = { null }
        )
    }

    @Serializable
    data class DsAnalysisResult(
        val mvpOrSvp: String,
        val mvpOrSvpText: String,
        val criminal: String,
        val criminalText: String,
        val rawResponse: String
    )

    private fun parseAnalysisResult(content: String): DsAnalysisResult? {
        fun extract(tag: String): Pair<String, String>? {
            val start = content.indexOf("[$tag]")
            if (start < 0) return null
            val body = content.substring(start + tag.length + 2).trim()
            // 找下一个段落标签(行首的[xxx]开头)，避免 [我] 等内嵌标记干扰
            val end = body.indexOf("\n[")
            val text = if (end > 0) body.substring(0, end).trim() else body.trim()
            val firstLine = text.lines().firstOrNull()?.trim() ?: ""
            val name = firstLine.split(":","：").firstOrNull()?.trim()?.take(30) ?: "?"
            return name to text
        }

        val mvp = extract("MVP")
        val svp = extract("SVP")
        val criminal = extract("战犯") ?: return null

        val mvpOrSvp = (mvp ?: svp) ?: return null

        return DsAnalysisResult(
            mvpOrSvp = mvpOrSvp.first,
            mvpOrSvpText = mvpOrSvp.second,
            criminal = criminal.first,
            criminalText = criminal.second,
            rawResponse = content
        )
    }

    // ── 构建最终报告 ─────────────────────────────
    suspend fun buildReport(
        matchJson: JsonObject,
        myAccountId: Long,
        dsResult: DsAnalysisResult?,
        won: Boolean
    ): Dota2MatchReport {
        ensureConstants()
        ensureAghsIcons()

        val players = matchJson["players"]?.jsonArray ?: JsonArray(emptyList())
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: false

        val cards = players.map { p ->
            val obj = p.jsonObject
            val accId = obj["account_id"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0
            val heroId = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0

            // 装备图标
            val itemIds = (0..5).map { obj["item_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
            val bpIds = (0..2).map { obj["backpack_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
            val neutralIds = listOf(
                obj["item_neutral"]?.jsonPrimitive?.intOrNull ?: 0
            )
            val isRadiant = obj["isRadiant"]?.jsonPrimitive?.boolean ?: true

            Dota2PlayerCard(
                name = obj["personaname"]?.jsonPrimitive?.content?.ifEmpty { "?" } ?: "?",
                heroName = heroName(heroId),
                heroIcon = loadHeroIcon(heroId),
                isRadiant = isRadiant, kills = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0,
                deaths = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0,
                assists = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0,
                kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                gpm = obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0,
                xpm = obj["xp_per_min"]?.jsonPrimitive?.intOrNull ?: 0,
                heroDamage = obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0,
                towerDamage = obj["tower_damage"]?.jsonPrimitive?.intOrNull ?: 0,
                level = obj["level"]?.jsonPrimitive?.intOrNull ?: 0,
                lastHits = obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0,
                items = itemIds.map { loadItemIcon(it) } + neutralIds.mapNotNull { loadItemIcon(it) },
                denies = obj["denies"]?.jsonPrimitive?.intOrNull ?: 0,
                netWorth = obj["net_worth"]?.jsonPrimitive?.intOrNull ?: 0,
                heroHealing = obj["hero_healing"]?.jsonPrimitive?.intOrNull ?: 0,
                backpackItems = bpIds.mapNotNull { loadItemIcon(it) },
                hasAghsScepter = (obj["aghanims_scepter"]?.jsonPrimitive?.intOrNull ?: 0) > 0,
                hasAghsShard = (obj["aghanims_shard"]?.jsonPrimitive?.intOrNull ?: 0) > 0,
                aghsScepterIcon = getAghsIcon((obj["aghanims_scepter"]?.jsonPrimitive?.intOrNull ?: 0) > 0, false),
                aghsShardIcon = getAghsIcon((obj["aghanims_shard"]?.jsonPrimitive?.intOrNull ?: 0) > 0, true),
                rankTier = obj["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0,
                rankName = rankName(obj["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0),
                isMvp = won && nameContains(dsResult?.mvpOrSvp, obj),
                isSvp = !won && nameContains(dsResult?.mvpOrSvp, obj),
                isCriminal = nameContains(dsResult?.criminal, obj)
            )
        }

        return Dota2MatchReport(
            matchId = matchJson["match_id"]?.jsonPrimitive?.longOrNull ?: 0,
            duration = matchJson["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            startTime = matchJson["start_time"]?.jsonPrimitive?.longOrNull ?: 0,
            gameMode = gameModeName(matchJson["game_mode"]?.jsonPrimitive?.intOrNull ?: 0),
            radiantScore = matchJson["radiant_score"]?.jsonPrimitive?.intOrNull ?: 0,
            direScore = matchJson["dire_score"]?.jsonPrimitive?.intOrNull ?: 0,
            radiantWin = radiantWin,
            players = cards,
            mvpName = if (won) (dsResult?.mvpOrSvp ?: "?") else "N/A",
            mvpReason = if (won) (dsResult?.mvpOrSvpText ?: "分析失败") else "N/A",
            svpName = if (!won) (dsResult?.mvpOrSvp ?: "?") else "N/A",
            svpReason = if (!won) (dsResult?.mvpOrSvpText ?: "分析失败") else "N/A",
            criminalName = dsResult?.criminal ?: "?",
            criminalReason = dsResult?.criminalText ?: "分析失败"
        )
    }

    suspend fun buildFullReport(matchJson: JsonObject, dual: DualAnalyzeResult?): Dota2MatchReport {
        ensureConstants()
        ensureAghsIcons()
        val players = matchJson["players"]?.jsonArray ?: JsonArray(emptyList())
        val radiantWin = matchJson["radiant_win"]?.jsonPrimitive?.boolean ?: false
        val r = dual?.radiant; val d = dual?.dire

        val cards = players.map { p ->
            val obj = p.jsonObject
            val heroId = obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0
            val itemIds = (0..5).map { obj["item_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
            val bpIds = (0..2).map { obj["backpack_$it"]?.jsonPrimitive?.intOrNull ?: 0 }
            val isRadiant = obj["isRadiant"]?.jsonPrimitive?.boolean ?: true
            val neutralIds = listOf(
                obj["item_neutral"]?.jsonPrimitive?.intOrNull ?: 0
            )
            Dota2PlayerCard(
                name = obj["personaname"]?.jsonPrimitive?.content?.ifEmpty { "?" } ?: "?",
                heroName = heroName(heroId),
                heroIcon = loadHeroIcon(heroId),
                isRadiant = isRadiant, kills = obj["kills"]?.jsonPrimitive?.intOrNull ?: 0,
                deaths = obj["deaths"]?.jsonPrimitive?.intOrNull ?: 0,
                assists = obj["assists"]?.jsonPrimitive?.intOrNull ?: 0,
                kda = obj["kda"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                gpm = obj["gold_per_min"]?.jsonPrimitive?.intOrNull ?: 0,
                xpm = obj["xp_per_min"]?.jsonPrimitive?.intOrNull ?: 0,
                heroDamage = obj["hero_damage"]?.jsonPrimitive?.intOrNull ?: 0,
                towerDamage = obj["tower_damage"]?.jsonPrimitive?.intOrNull ?: 0,
                level = obj["level"]?.jsonPrimitive?.intOrNull ?: 0,
                lastHits = obj["last_hits"]?.jsonPrimitive?.intOrNull ?: 0,
                items = itemIds.map { loadItemIcon(it) } + neutralIds.mapNotNull { loadItemIcon(it) },
                denies = obj["denies"]?.jsonPrimitive?.intOrNull ?: 0,
                netWorth = obj["net_worth"]?.jsonPrimitive?.intOrNull ?: 0,
                heroHealing = obj["hero_healing"]?.jsonPrimitive?.intOrNull ?: 0,
                backpackItems = bpIds.mapNotNull { loadItemIcon(it) },
                hasAghsScepter = (obj["aghanims_scepter"]?.jsonPrimitive?.intOrNull ?: 0) > 0,
                hasAghsShard = (obj["aghanims_shard"]?.jsonPrimitive?.intOrNull ?: 0) > 0,
                aghsScepterIcon = getAghsIcon((obj["aghanims_scepter"]?.jsonPrimitive?.intOrNull ?: 0) > 0, false),
                aghsShardIcon = getAghsIcon((obj["aghanims_shard"]?.jsonPrimitive?.intOrNull ?: 0) > 0, true),
                rankTier = obj["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0,
                rankName = rankName(obj["rank_tier"]?.jsonPrimitive?.intOrNull ?: 0)
            )
        }

        return Dota2MatchReport(
            matchId = matchJson["match_id"]?.jsonPrimitive?.longOrNull ?: 0,
            duration = matchJson["duration"]?.jsonPrimitive?.intOrNull ?: 0,
            startTime = matchJson["start_time"]?.jsonPrimitive?.longOrNull ?: 0,
            gameMode = gameModeName(matchJson["game_mode"]?.jsonPrimitive?.intOrNull ?: 0),
            radiantScore = matchJson["radiant_score"]?.jsonPrimitive?.intOrNull ?: 0,
            direScore = matchJson["dire_score"]?.jsonPrimitive?.intOrNull ?: 0,
            radiantWin = radiantWin, players = cards,
            mvpName = "N/A", mvpReason = "N/A", svpName = "N/A", svpReason = "N/A",
            criminalName = "N/A", criminalReason = "N/A",
            radiantMvp = r?.mvpOrSvp ?: "?", radiantMvpReason = r?.mvpOrSvpText ?: "分析失败",
            radiantCriminal = r?.criminal ?: "?", radiantCriminalReason = r?.criminalText ?: "分析失败",
            direMvp = d?.mvpOrSvp ?: "?", direMvpReason = d?.mvpOrSvpText ?: "分析失败",
            direCriminal = d?.criminal ?: "?", direCriminalReason = d?.criminalText ?: "分析失败"
        )
    }

    private fun rankName(rankTier: Int): String {
        if (rankTier == 0) return ""
        val medal = rankTier / 10
        val star = rankTier % 10
        val names = listOf("","先锋","卫士","十字军","执政官","传奇","万古流芳","超凡入圣","冠绝一世")
        val name = names.getOrElse(medal) { "" }
        if (name.isEmpty()) return ""
        return if (medal == 8) name else "$name [$star]"
    }

    private fun gameModeName(mode: Int): String = when (mode) {
        1 -> "全英雄选择"; 2 -> "队长模式"; 3 -> "随机征召"; 4 -> "个别征召"
        5 -> "全阵营随机"; 16 -> "队长征召"; 22 -> "加速模式"; 23 -> "技能征召"
        else -> "模式$mode"
    }

    private fun nameContains(name: String?, obj: JsonObject): Boolean {
        if (name.isNullOrBlank()) return false
        val personaname = obj["personaname"]?.jsonPrimitive?.content ?: ""
        val heroName = heroName(obj["hero_id"]?.jsonPrimitive?.intOrNull ?: 0)
        return personaname.isNotEmpty() && personaname in name
            || heroName.isNotEmpty() && heroName in name
    }
}
