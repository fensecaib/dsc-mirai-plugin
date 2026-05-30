package top.colter.mirai.plugin.dschat.draw

import org.jetbrains.skia.Color
import org.jetbrains.skia.Image
import org.jetbrains.skia.paragraph.TextStyle
import top.colter.mirai.plugin.dschat.deepseek.DeepSeekConfig
import top.colter.skiko.*
import top.colter.skiko.data.LayoutAlignment
import top.colter.skiko.data.RichParagraphBuilder
import top.colter.skiko.layout.*

data class Dota2MatchReport(
    val matchId: Long, val duration: Int, val startTime: Long,
    val gameMode: String, val radiantScore: Int, val direScore: Int,
    val radiantWin: Boolean, val players: List<Dota2PlayerCard>,
    val mvpName: String, val mvpReason: String,
    val svpName: String, val svpReason: String,
    val criminalName: String, val criminalReason: String,
    // 双阵营分析额外字段
    val radiantMvp: String = "", val radiantMvpReason: String = "",
    val radiantCriminal: String = "", val radiantCriminalReason: String = "",
    val direMvp: String = "", val direMvpReason: String = "",
    val direCriminal: String = "", val direCriminalReason: String = ""
)

data class Dota2PlayerCard(
    val name: String, val heroName: String, val heroIcon: Image? = null,
    val isRadiant: Boolean, val kills: Int, val deaths: Int, val assists: Int,
    val kda: Double, val gpm: Int, val xpm: Int, val heroDamage: Int,
    val towerDamage: Int, val level: Int, val lastHits: Int,
    val items: List<Image?>, val isMvp: Boolean = false,
    val isSvp: Boolean = false, val isCriminal: Boolean = false,
    val denies: Int = 0, val netWorth: Int = 0, val heroHealing: Int = 0,
    val backpackItems: List<Image?> = emptyList(),
    val hasAghsScepter: Boolean = false, val hasAghsShard: Boolean = false,
    val aghsScepterIcon: Image? = null, val aghsShardIcon: Image? = null,
    val rankTier: Int = 0, val rankName: String = ""
)

val C_BG    = Color.makeRGB( 15,  23,  42)
val C_ODD   = Color.makeRGB( 30,  41,  59)
val C_EVEN  = Color.makeRGB( 21,  30,  48)
val C_HDR   = Color.makeRGB( 30,  58,  95)
val C_TXT   = Color.makeRGB(226, 232, 240)
val C_TXT2  = Color.makeRGB(148, 163, 184)
val C_DIM   = Color.makeRGB(100, 116, 139)
val C_GREEN = Color.makeRGB( 34, 197,  94)
val C_RED   = Color.makeRGB(239,  68,  68)
val C_GOLD  = Color.makeRGB(245, 158,  11)
val C_BLUE  = Color.makeRGB( 59, 130, 246)
val C_BORDER= Color.makeRGB( 51,  65,  85)
val C_AGHS  = Color.makeRGB(100, 180, 255).withAlpha(0.25f)
val C_SHARD = Color.makeRGB(180, 140, 240).withAlpha(0.25f)

private fun ff() = FontUtils.defaultFont?.familyName ?: ""
private fun fmtDur(s: Int) = "${s/60}分${s%60}秒"
private fun fmtTs(ts: Long): String {
    val d = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.CHINA)
    d.timeZone = java.util.TimeZone.getTimeZone("Asia/Shanghai")
    return d.format(java.util.Date(ts * 1000))
}
private fun kdaC(k: Double) = when { k>=3.0->C_GREEN; k>=1.5->C_GOLD; else->C_RED }
private fun fmtK(n: Int): String = when { n>=100000->"${n/1000}k"; n>=10000-> java.lang.String.format("%.1f",n/1000.0)+"k"; else->n.toString() }
private fun fmtKda(k: Double): String = java.lang.String.format("%.1f", k)
private fun rankColor(tier: Int): Int = when(tier/10) {
    8 -> Color.makeRGB(230, 180, 80); 7 -> Color.makeRGB(200, 120, 60)
    6 -> Color.makeRGB(180, 120, 210); else -> C_TXT2
}

suspend fun dota2MatchDraw(report: Dota2MatchReport): Image? {
    Dp.factor = DeepSeekConfig.image.factor
    return View(Modifier().width(1060.dp).background(C_BG)) {
        Column(Modifier().fillMaxWidth()) {
            topBar(report); sep()
            teamTable("RADIANT", report.radiantScore, report.radiantWin, report.players.filter{it.isRadiant}); sep()
            teamTable("DIRE", report.direScore, !report.radiantWin, report.players.filter{!it.isRadiant}); sep()
            analysisBlock(report); sep()
            foot(report)
        }
    }
}

fun Layout.sep() = Box(Modifier().fillMaxWidth().height(1.dp).background(C_BORDER))

fun Layout.topBar(r: Dota2MatchReport) {
    Row(Modifier().fillMaxWidth().height(44.dp).background(C_HDR), alignment=LayoutAlignment.CENTER_LEFT) {
        Text(text="  #${r.matchId}", color=C_BLUE, fontSize=20.dp, fontFamily=ff())
        Text(text="  ${r.gameMode}", color=C_TXT2, fontSize=18.dp, fontFamily=ff())
        Text(text="  ${fmtDur(r.duration)}", color=C_TXT2, fontSize=18.dp, fontFamily=ff())
        Box(Modifier().width(16.dp).height(1.dp))
        Text(text=fmtTs(r.startTime), color=C_DIM, fontSize=16.dp, fontFamily=ff())
    }
}

fun Layout.teamTable(label: String, score: Int, won: Boolean, players: List<Dota2PlayerCard>) {
    val accent = if(won) C_GREEN else C_RED
    Row(Modifier().fillMaxWidth().height(34.dp).background(accent.withAlpha(0.10f)), alignment=LayoutAlignment.CENTER_LEFT) {
        Text(text="  $label  ", color=accent, fontSize=22.dp, fontFamily=ff())
        Text(text="$score  ${if(won) "WIN" else "LOSE"}", color=accent, fontSize=22.dp, fontFamily=ff())
    }
    // 表头 — 每列宽度与数据行 playerRow 严格对齐，修改时两处同步
    Row(Modifier().fillMaxWidth().height(24.dp).background(C_ODD), alignment=LayoutAlignment.CENTER_LEFT) {
        hdr("",22.dp); hdr("英雄",68.dp); hdr("玩家",120.dp); hdr("Lv",26.dp); hdr("K",26.dp); hdr("D",26.dp); hdr("A",26.dp)
        hdr("KDA",42.dp); hdr("CS",60.dp); hdr("NET",50.dp); hdr("GPM",38.dp); hdr("XPM",38.dp)
        hdr("伤害",50.dp); hdr("治疗",34.dp); hdr("物品", 320.dp); hdr("A杖", 50.dp)
    }
    players.forEachIndexed{i,p->
        val b = when{ p.isMvp-> C_GREEN.withAlpha(0.07f); p.isSvp-> C_GOLD.withAlpha(0.07f)
            p.isCriminal->C_RED.withAlpha(0.07f); i%2==0->C_EVEN; else->C_BG }
        playerRow(i+1,p,b)
        sep()
    }
}

fun Layout.playerRow(idx: Int, p: Dota2PlayerCard, bg: Int) {
    val tag = when{ p.isMvp->"MVP"; p.isSvp->"SVP"; p.isCriminal->"CW"; else->null }
    val tb  = when{ p.isMvp->C_GREEN; p.isSvp->C_GOLD; p.isCriminal->C_RED; else->C_BG }
    val rowH = 50.dp  // 行高，增大可容纳更大图标
    val displayName = when { p.name.isEmpty() || p.name == "?" -> p.heroName; else -> p.name }

    // 各列宽度必须与 teamTable 表头一一对齐，总宽不可超过 View 宽度(1060dp)
    Row(Modifier().fillMaxWidth().height(rowH).background(bg), alignment=LayoutAlignment.CENTER_LEFT) {
        // #tag
        Box(Modifier().width(22.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if(tag!=null) {
                Box(Modifier().width(20.dp).height(14.dp).background(tb).border(0.dp,3.dp), alignment=LayoutAlignment.CENTER) {
                    Text(text=tag, color=Color.WHITE, fontSize=8.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
                }
            } else Text(text="$idx", color=C_DIM, fontSize=13.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
        }
        // 英雄图标（独立于玩家名字列，避免嵌套Row冲突）
        Box(Modifier().width(68.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if (p.heroIcon != null) {
                Image(image=p.heroIcon, modifier=Modifier().width(64.dp).height(36.dp))
            } else {
                Box(Modifier().width(64.dp).height(36.dp).background(C_BORDER.withAlpha(0.2f)).border(1.dp,3.dp,C_DIM), alignment=LayoutAlignment.CENTER) {
                    Text(text=p.heroName.take(3), color=C_DIM.withAlpha(0.35f), fontSize=9.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
                }
            }
        }
        // 玩家名称 + 段位
        Box(Modifier().width(120.dp).height(rowH).margin(left=2.dp), alignment=LayoutAlignment.CENTER_LEFT) {
            Column {
                Text(text=displayName, color=C_TXT, fontSize=14.dp, fontFamily=ff())
                if (p.rankName.isNotEmpty()) {
                    Text(text=p.rankName, color=rankColor(p.rankTier), fontSize=11.dp, fontFamily=ff())
                } else {
                    Text(text=p.heroName, color=C_DIM, fontSize=11.dp, fontFamily=ff())
                }
            }
        }
        cel(p.level.toString(),    26.dp, C_TXT)
        cel(p.kills.toString(),    26.dp, C_GREEN)
        cel(p.deaths.toString(),   26.dp, C_RED)
        cel(p.assists.toString(),  26.dp, C_TXT)
        cel(fmtKda(p.kda),         42.dp, kdaC(p.kda))
        Box(Modifier().width(60.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            Text(text="${p.lastHits}/${p.denies}", color=C_TXT, fontSize=13.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
        }
        cel(fmtK(p.netWorth),      50.dp, C_TXT2)
        cel(p.gpm.toString(),      38.dp, C_GOLD)
        cel(p.xpm.toString(),      38.dp, C_TXT2)
        cel(fmtK(p.heroDamage),    50.dp, C_TXT)
        cel(if(p.heroHealing>0) fmtK(p.heroHealing) else "-", 34.dp, if(p.heroHealing>0) C_GREEN else C_DIM)
        // 物品+背包列 — 主装备6格(28dp)+中立(28dp金色边框)+分隔线+背包3格(24dp)
        Row(modifier = Modifier().width(320.dp).height(rowH), alignment=LayoutAlignment.CENTER_LEFT) {
            Box(Modifier().margin(left=1.dp).width(1.dp).height(1.dp))
            p.items.take(6).forEach { img -> itemSlot(img, 28.dp) }
            repeat(6 - p.items.take(6).size) { itemSlot(null, 28.dp) }
            itemSlot(p.items.getOrNull(6), 28.dp, neutral=true)
            Box(Modifier().margin(left=3.dp, right=3.dp).width(1.dp).height(20.dp).background(C_DIM.withAlpha(0.2f)))
            p.backpackItems.take(3).forEach { img -> itemSlot(img, 24.dp) }
            repeat(3 - p.backpackItems.take(3).size) { itemSlot(null, 24.dp) }
        }
        // A杖/魔晶 — 左右排列，22dp方形图标，有图用Image无图用文字方块兜底
        Row(modifier = Modifier().width(50.dp).height(rowH), alignment=LayoutAlignment.CENTER) {
            if (p.aghsScepterIcon != null) {
                Image(image=p.aghsScepterIcon, modifier=Modifier().width(22.dp).height(22.dp))
            } else {
                aghsBox("A", p.hasAghsScepter, C_BLUE, C_AGHS)
            }
            Box(Modifier().width(4.dp).height(1.dp))
            if (p.aghsShardIcon != null) {
                Image(image=p.aghsShardIcon, modifier=Modifier().width(22.dp).height(22.dp))
            } else {
                aghsBox("S", p.hasAghsShard, Color.makeRGB(160,120,230), C_SHARD)
            }
        }
    }
}

// aghsBox — A杖/魔晶文字方块兜底。has=true亮色，false暗色。20dp方形
fun Layout.aghsBox(label: String, has: Boolean, onColor: Int, bgColor: Int) {
    Box(Modifier().width(20.dp).height(20.dp).background(if(has) bgColor else C_DIM.withAlpha(0.08f))
        .border(1.dp, 2.dp, if(has) onColor.withAlpha(0.4f) else C_DIM.withAlpha(0.15f)),
        alignment=LayoutAlignment.CENTER) {
        Text(text=label, color=if(has) onColor else C_DIM.withAlpha(0.2f), fontSize=10.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
    }
}

fun Layout.cel(txt: String, w: Dp, c: Int) {
    Box(Modifier().width(w).height(50.dp), alignment=LayoutAlignment.CENTER) {
        Text(text=txt, color=c, fontSize=13.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
    }
}

fun Layout.hdr(txt: String, w: Dp) {
    Box(Modifier().width(w).height(24.dp), alignment=LayoutAlignment.CENTER) {
        Text(text=txt, color=C_DIM, fontSize=12.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
    }
}

// itemSlot — 单个物品格子。s=槽尺寸(主28/背包24)，neutral=中立物品(金色边框)，icon=null时显示占位"·"
fun Layout.itemSlot(icon: Image? = null, s: Dp = 28.dp, neutral: Boolean = false) {
    val bc = when { neutral -> C_GOLD.withAlpha(0.3f); s < 28.dp -> C_DIM.withAlpha(0.1f); else -> C_BORDER }
    val pad = (50.dp - s) / 2f
    Box(Modifier().width(s).height(s).background(C_ODD.withAlpha(0.3f))
        .border(1.dp, 4.dp, bc).margin(left=2.dp,right=2.dp,top=pad,bottom=pad),
        alignment=LayoutAlignment.CENTER) {
        if (icon != null) Image(image=icon, modifier=Modifier().width(s-1.dp).height(s-1.dp))
        else Text(text="·", color=C_DIM.withAlpha(0.15f), fontSize=8.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
    }
}

fun Layout.analysisBlock(r: Dota2MatchReport) {
    Row(Modifier().fillMaxWidth().height(28.dp).background(C_HDR), alignment=LayoutAlignment.CENTER_LEFT) {
        Text(text="  ANALYZE", color=C_BLUE, fontSize=16.dp, fontFamily=ff())
    }
    if (r.mvpReason != "N/A") {
        val icon = r.players.find { it.isMvp }?.heroIcon
        aLine(C_GREEN, "MVP", r.mvpName, r.mvpReason, icon)
    }
    if (r.svpReason != "N/A") {
        val icon = r.players.find { it.isSvp }?.heroIcon
        aLine(C_GOLD, "SVP", r.svpName, r.svpReason, icon)
    }
    val criminalIcon = r.players.find { it.isCriminal }?.heroIcon
    aLine(C_RED, "战犯", r.criminalName, r.criminalReason, criminalIcon)
}

fun Layout.aLine(accent: Int, tag: String, name: String, reason: String, heroIcon: Image? = null) {
    Column(Modifier().fillMaxWidth().padding(top=4.dp,bottom=4.dp,left=12.dp,right=12.dp)) {
        Row(Modifier().fillMaxWidth().margin(bottom=3.dp), alignment=LayoutAlignment.CENTER_LEFT) {
            if (heroIcon != null) {
                Image(image=heroIcon, modifier=Modifier().width(42.dp).height(24.dp).margin(right=5.dp))
            }
            Box(Modifier().padding(top=1.dp,bottom=1.dp,left=8.dp,right=8.dp).background(accent).border(0.dp,3.dp)) {
                Text(text=tag, color=Color.WHITE, fontSize=13.dp, fontFamily=ff())
            }
            Text(text="  $name", color=C_TXT, fontSize=15.dp, fontFamily=ff())
        }
        val style = TextStyle().setColor(C_TXT2).setFontSize(13.px).setFontFamily(ff())
        val paragraph = RichParagraphBuilder(style)
        paragraph.addText(reason)
        RichText(paragraph=paragraph.build(), modifier=Modifier().fillMaxWidth())
    }
}

fun Layout.foot(r: Dota2MatchReport) {
    Row(Modifier().fillMaxWidth().height(26.dp).background(C_ODD), alignment=LayoutAlignment.CENTER) {
        Text(text="OpenDota · ${fmtTs(r.startTime)} · #${r.matchId} · 仅供参考", color=C_DIM, fontSize=12.dp, fontFamily=ff(), alignment=LayoutAlignment.CENTER)
    }
}

suspend fun dota2FullAnalyzeDraw(report: Dota2MatchReport): Image? {
    Dp.factor = DeepSeekConfig.image.factor
    return View(Modifier().width(1060.dp).background(C_BG)) {
        Column(Modifier().fillMaxWidth()) {
            topBar(report); sep()
            teamTable("RADIANT", report.radiantScore, report.radiantWin, report.players.filter{it.isRadiant}); sep()
            teamTable("DIRE", report.direScore, !report.radiantWin, report.players.filter{!it.isRadiant}); sep()
            fullAnalysisBlock(report); sep()
            foot(report)
        }
    }
}

fun Layout.fullAnalysisBlock(r: Dota2MatchReport) {
    Row(Modifier().fillMaxWidth().height(28.dp).background(C_HDR), alignment=LayoutAlignment.CENTER_LEFT) {
        Text(text="  ANALYZE · 双阵营", color=C_BLUE, fontSize=16.dp, fontFamily=ff())
    }
    // 天辉
    if (r.radiantMvpReason.isNotBlank() && r.radiantMvpReason != "N/A" && r.radiantMvpReason != "分析失败") {
        val icon = r.players.find { it.isRadiant && it.heroName in r.radiantMvp }?.heroIcon
        aLine(C_GREEN, "天辉MVP/SVP", r.radiantMvp, r.radiantMvpReason, icon)
    }
    if (r.radiantCriminalReason.isNotBlank() && r.radiantCriminalReason != "分析失败") {
        val icon = r.players.find { it.isRadiant && it.heroName in r.radiantCriminal }?.heroIcon
        aLine(C_RED, "天辉战犯", r.radiantCriminal, r.radiantCriminalReason, icon)
    }
    // 夜魇
    if (r.direMvpReason.isNotBlank() && r.direMvpReason != "N/A" && r.direMvpReason != "分析失败") {
        val icon = r.players.find { !it.isRadiant && it.heroName in r.direMvp }?.heroIcon
        aLine(C_GOLD, "夜魇MVP/SVP", r.direMvp, r.direMvpReason, icon)
    }
    if (r.direCriminalReason.isNotBlank() && r.direCriminalReason != "分析失败") {
        val icon = r.players.find { !it.isRadiant && it.heroName in r.direCriminal }?.heroIcon
        aLine(C_RED, "夜魇战犯", r.direCriminal, r.direCriminalReason, icon)
    }
}
