package com.myai.assistant.draft

/**
 * Deterministic draft advisor.
 *
 * Given what is *actually known* about a draft, it ranks candidates with a small,
 * readable rule set and explains every score. It is deliberately not a neural
 * model: the app cannot claim "vision AI" for ranking, and a rule set can be
 * audited, unit-tested and fixed when a patch changes the game.
 *
 * Inputs that are unknown are skipped instead of guessed, and the confidence
 * number drops accordingly (see [confidenceFor]).
 */
object DraftEngine {

    private const val BASE = 50

    fun analyze(context: DraftContext): DraftAnalysis {
        val mapInfo = MapCatalog.find(context.map)
        val effectiveMode = when {
            context.mode != GameMode.UNKNOWN -> context.mode
            mapInfo != null -> mapInfo.mode
            else -> GameMode.UNKNOWN
        }
        val normalised = if (effectiveMode != context.mode) context.copy(mode = effectiveMode) else context

        val banned = normalised.bans.mapNotNull { BrawlerCatalog.find(it)?.name }.toSet()
        val taken = (normalised.allies + normalised.enemies)
            .mapNotNull { BrawlerCatalog.find(it)?.name }
            .toSet() + listOfNotNull(normalised.myBrawler?.let { BrawlerCatalog.find(it)?.name })

        val candidates = BrawlerCatalog.all.filter { it.name !in banned && it.name !in taken }

        if (candidates.isEmpty()) {
            return DraftAnalysis(
                context = normalised,
                mapInfo = mapInfo,
                recommendations = emptyList(),
                confidence = 0,
                notes = listOf("كل الأبطال المعروفين محظورون أو مختارون — راجع المعلومات المدخلة.")
            )
        }

        val scored = candidates.map { brawler ->
            val breakdown = score(brawler, normalised, mapInfo)
            Recommendation(
                brawler = brawler,
                score = breakdown.total.coerceIn(5, 99),
                reasons = breakdown.reasons.take(3),
                caveats = breakdown.caveats.take(2)
            )
        }.sortedWith(compareByDescending<Recommendation> { it.score }.thenBy { it.brawler.name })

        val notes = buildList {
            if (mapInfo == null && context.map != null) {
                add("الخريطة «${context.map}» غير موجودة في جدول الخرائط — التوصية عامة على النمط.")
            }
            if (effectiveMode == GameMode.UNKNOWN) {
                add("نمط اللعب غير معروف: الترتيب لا يعتمد على أهداف النمط.")
            }
            if (normalised.enemies.isEmpty()) {
                add("لا توجد اختيارات خصم معروفة: الترتيب لا يعتمد على المضادات.")
            }
            if (normalised.pickSlot == null) {
                add("دور الاختيار غير معروف: لم يُرجَّح أي بطل لأنه «اختيار أول» أو «اختيار مضاد».")
            }
            if (normalised.unresolved.isNotEmpty()) {
                add("أسماء لم أتعرّف عليها ولم أخمّنها: ${normalised.unresolved.joinToString("، ")}")
            }
        }

        return DraftAnalysis(
            context = normalised,
            mapInfo = mapInfo,
            recommendations = scored.take(3),
            confidence = confidenceFor(normalised, mapInfo),
            notes = notes
        )
    }

    /** Confidence in the analysis, derived only from how much input was available. */
    fun confidenceFor(context: DraftContext, mapInfo: MapInfo?): Int {
        var score = 35
        if (mapInfo != null) score += 22
        if (context.mode != GameMode.UNKNOWN) score += 12
        score += (context.pickSlot?.let { 6 } ?: 0)
        score += (context.enemies.size * 6).coerceAtMost(18)
        score += (context.allies.size * 2).coerceAtMost(4)
        score += if (context.bans.isNotEmpty()) 3 else 0
        if (context.map != null && mapInfo == null) score -= 10
        return score.coerceIn(10, 95)
    }

    data class Breakdown(
        val total: Int,
        val reasons: List<String>,
        val caveats: List<String>
    )

    /** Public for tests: the exact contribution of every rule for one brawler. */
    fun score(brawler: Brawler, context: DraftContext, mapInfo: MapInfo?): Breakdown {
        var total = BASE
        val reasons = mutableListOf<String>()
        val caveats = mutableListOf<String>()

        // --- 1. Map fit -------------------------------------------------------
        if (mapInfo != null) {
            val tags = mapInfo.tags
            if (MapTag.OPEN in tags) {
                when (brawler.range) {
                    Range.LONG -> { total += 14; reasons += "خريطة مفتوحة تناسب مداه البعيد" }
                    Range.MID -> total += 5
                    Range.SHORT -> { total -= 12; caveats += "مساحات مفتوحة تكشف قصير المدى بسرعة" }
                }
            }
            if (MapTag.TIGHT in tags) {
                when (brawler.range) {
                    Range.SHORT -> { total += 10; reasons += "ممرات ضيقة تقرّبه من الهدف" }
                    Range.LONG -> { total -= 6; caveats += "ممرات ضيقة تقلّل فائدة المدى البعيد" }
                    Range.MID -> total += 2
                }
            }
            if (MapTag.WALLED in tags) {
                when (brawler.cls) {
                    BrawlerClass.ARTILLERY -> { total += 13; reasons += "جدران كثيرة يستغلها القاذف" }
                    BrawlerClass.MARKSMAN -> total -= 6
                    else -> Unit
                }
                if (Trait.WALL_BREAK in brawler.traits) {
                    total += 6
                    reasons += "يكسر الجدران ويفتح خطوط الرمي"
                }
            }
            if (MapTag.THROWER_FRIENDLY in tags && brawler.cls == BrawlerClass.ARTILLERY) total += 6
            if (MapTag.BUSHES in tags) {
                if (Trait.BUSH_CONTROL in brawler.traits) {
                    total += 9; reasons += "أدغال تحتاج من يكشفها ويؤمّنها"
                }
                if (Trait.INVISIBLE in brawler.traits) { total += 6 }
                if (brawler.range == Range.LONG) total -= 4
            }
            if (MapTag.WATER in tags && brawler.range == Range.SHORT) total -= 4
        }

        // --- 2. Mode fit ------------------------------------------------------
        total += modeBonus(brawler, context.mode, reasons, caveats)

        // --- 3. Counters against the enemy team -------------------------------
        val enemies = context.enemies.mapNotNull { BrawlerCatalog.find(it) }
        enemies.forEach { enemy -> total += counterBonus(brawler, enemy, reasons) }

        // --- 4. Synergy with your own team -----------------------------------
        val allies = context.allies.mapNotNull { BrawlerCatalog.find(it) }
        allies.forEach { ally -> total += synergyBonus(brawler, ally, reasons) }
        if (allies.count { it.cls == brawler.cls } >= 1) {
            total -= 4
            caveats += "لديك بالفعل بطل من نفس الدور"
        }

        // --- 5. Pick order ----------------------------------------------------
        when (context.pickSlot) {
            1 -> {
                if (brawler.generalist) { total += 8; reasons += "اختيار أول آمن لا يُستدرج بسهولة" }
                if (brawler.cls == BrawlerClass.ARTILLERY || brawler.cls == BrawlerClass.ASSASSIN) {
                    total -= 6
                    caveats += "اختيار أول بورقة متخصصة قد يُضاد فورًا"
                }
            }
            3 -> {
                if (brawler.cls == BrawlerClass.ASSASSIN || brawler.hasAntiTank) {
                    total += 8; reasons += "اختيار أخير مناسب لورقة مضادة"
                }
            }
        }

        // --- 6. Unknown mode keeps the advice class-agnostic ------------------
        if (context.mode == GameMode.UNKNOWN && allies.isEmpty() && enemies.isEmpty()) {
            if (brawler.generalist) total += 5
        }

        if (reasons.isEmpty()) {
            reasons += "لا توجد عوامل قوية في هذه المسودة؛ الترشيح بناءً على الدور والمدى فقط"
        }
        return Breakdown(total, reasons.distinct(), caveats.distinct())
    }

    private fun modeBonus(
        brawler: Brawler,
        mode: GameMode,
        reasons: MutableList<String>,
        caveats: MutableList<String>
    ): Int = when (mode) {
        GameMode.GEM_GRAB -> when (brawler.cls) {
            BrawlerClass.CONTROLLER -> 13.also { reasons += "الجواهر تُحسم بالتحكّم في المنتصف" }
            BrawlerClass.SUPPORT -> 9
            BrawlerClass.TANK -> 5
            BrawlerClass.MARKSMAN -> 6
            BrawlerClass.ARTILLERY -> 7
            BrawlerClass.ASSASSIN -> 2
            BrawlerClass.DAMAGE -> 6
        }
        GameMode.BRAWL_BALL -> when (brawler.cls) {
            BrawlerClass.TANK -> 15.also { reasons += "كرة القدم تحتاج من يفتح الطريق ويتحمّل" }
            BrawlerClass.SUPPORT -> 9
            BrawlerClass.ASSASSIN -> 7
            BrawlerClass.MARKSMAN -> -5.also { caveats += "القنّاصة أضعف في الاشتباك القريب بالكرة" }
            BrawlerClass.ARTILLERY -> -6.also { caveats += "القاذف بطيء في ملاحقة الكرة" }
            BrawlerClass.DAMAGE -> 4
            BrawlerClass.CONTROLLER -> 4
        }
        GameMode.HEIST -> when (brawler.cls) {
            BrawlerClass.DAMAGE -> 12.also { reasons += "السرقة تُحسم بضرر مباشر على الخزنة" }
            BrawlerClass.ASSASSIN -> 8
            BrawlerClass.TANK -> 4
            BrawlerClass.MARKSMAN -> 2
            BrawlerClass.ARTILLERY -> -4
            BrawlerClass.SUPPORT -> 0
            BrawlerClass.CONTROLLER -> 0
        }
        GameMode.BOUNTY -> when (brawler.cls) {
            BrawlerClass.MARKSMAN -> 13.also { reasons += "المكافأة تُلعب على المدى الطويل" }
            BrawlerClass.CONTROLLER -> 7
            BrawlerClass.ARTILLERY -> 8
            BrawlerClass.SUPPORT -> 5
            BrawlerClass.TANK -> -10.also { caveats += "الدبابة تتعرّض للنجوم بسرعة في المكافأة" }
            BrawlerClass.ASSASSIN -> -2
            BrawlerClass.DAMAGE -> 5
        }
        GameMode.KNOCKOUT -> when (brawler.cls) {
            BrawlerClass.MARKSMAN -> 11.also { reasons += "الإقصاء يعاقب على أي تعرّض" }
            BrawlerClass.CONTROLLER -> 9
            BrawlerClass.SUPPORT -> 8
            BrawlerClass.ARTILLERY -> 6
            BrawlerClass.TANK -> -7
            BrawlerClass.ASSASSIN -> -3
            BrawlerClass.DAMAGE -> 2
        }
        GameMode.HOT_ZONE -> when (brawler.cls) {
            BrawlerClass.CONTROLLER -> 12.also { reasons += "المنطقة الساخنة تُحسم بالثبات داخلها" }
            BrawlerClass.SUPPORT -> 10
            BrawlerClass.TANK -> 9
            BrawlerClass.ARTILLERY -> 5
            BrawlerClass.MARKSMAN -> 1
            BrawlerClass.ASSASSIN -> -2
            BrawlerClass.DAMAGE -> 5
        }
        GameMode.DUELS -> if (brawler.generalist) 6 else 0
        GameMode.UNKNOWN -> 0
    }

    private fun counterBonus(brawler: Brawler, enemy: Brawler, reasons: MutableList<String>): Int {
        var bonus = 0
        when (enemy.cls) {
            BrawlerClass.TANK -> {
                if (brawler.hasAntiTank) {
                    bonus += 15
                    reasons += "يضاد ${enemy.name} (دبابة) بضرر يتجاهل الصحة العالية"
                } else if (brawler.hasAntiTank.not() && brawler.cls == BrawlerClass.ASSASSIN) bonus -= 7
                if (brawler.cls == BrawlerClass.MARKSMAN) bonus -= 3
            }
            BrawlerClass.ASSASSIN -> {
                if (brawler.cls == BrawlerClass.TANK) {
                    bonus += 11
                    reasons += "الدبابة تصد ${enemy.name} عند الاقتراب"
                }
                if (brawler.cls == BrawlerClass.CONTROLLER) {
                    bonus += 8
                    reasons += "التحكّم يوقف تسلّل ${enemy.name}"
                }
                if (brawler.cls == BrawlerClass.MARKSMAN) bonus -= 8
            }
            BrawlerClass.MARKSMAN -> {
                if (brawler.cls == BrawlerClass.ASSASSIN) {
                    bonus += 12
                    reasons += "القاتل يغلق المسافة على ${enemy.name} القنّاص"
                }
                if (brawler.cls == BrawlerClass.TANK) bonus += 7
                if (brawler.cls == BrawlerClass.ARTILLERY) bonus -= 5
            }
            BrawlerClass.ARTILLERY -> {
                if (brawler.cls == BrawlerClass.ASSASSIN) {
                    bonus += 10
                    reasons += "يمكن الوصول إلى قاذف الخصم ${enemy.name} بسرعة"
                }
                if (brawler.cls == BrawlerClass.MARKSMAN) bonus += 5
                if (brawler.cls == BrawlerClass.TANK) bonus -= 4
            }
            BrawlerClass.CONTROLLER -> {
                if (brawler.range == Range.LONG) bonus += 6
                if (brawler.cls == BrawlerClass.ASSASSIN) bonus += 4
            }
            BrawlerClass.SUPPORT -> {
                if (brawler.cls == BrawlerClass.ASSASSIN) {
                    bonus += 8
                    reasons += "الضغط على دعم الخصم ${enemy.name} يقلب الاشتباك"
                }
            }
            BrawlerClass.DAMAGE -> {
                if (brawler.range == Range.LONG && enemy.range == Range.SHORT) {
                    bonus += 6
                    reasons += "المدى يتفوق على ${enemy.name} القصير"
                }
            }
        }
        if (Trait.SUMMON in enemy.traits && Trait.AREA in brawler.traits) bonus += 5
        if (Trait.INVISIBLE in enemy.traits && Trait.BUSH_CONTROL in brawler.traits) bonus += 5
        return bonus
    }

    private fun synergyBonus(brawler: Brawler, ally: Brawler, reasons: MutableList<String>): Int {
        var bonus = 0
        if (ally.cls == BrawlerClass.TANK && (brawler.cls == BrawlerClass.SUPPORT || Trait.HEAL in brawler.traits)) {
            bonus += 8
            reasons += "دعم ${ally.name} يجعل الدبابة تبقى أطول"
        }
        if (ally.cls == BrawlerClass.SUPPORT && brawler.cls == BrawlerClass.TANK) bonus += 5
        if (ally.cls == BrawlerClass.ARTILLERY && brawler.cls == BrawlerClass.CONTROLLER) bonus += 4
        if (ally.cls == BrawlerClass.MARKSMAN && brawler.cls == BrawlerClass.CONTROLLER) bonus += 3
        if (brawler.cls == BrawlerClass.ARTILLERY && ally.cls == BrawlerClass.ARTILLERY) bonus -= 5
        return bonus
    }
}
