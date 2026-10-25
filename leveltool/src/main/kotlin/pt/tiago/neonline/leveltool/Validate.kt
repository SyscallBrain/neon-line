package pt.tiago.neonline.leveltool

import pt.tiago.neonline.core.content.ContentError
import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.model.PieceKind
import pt.tiago.neonline.core.model.Player
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import pt.tiago.neonline.core.content.LevelFileDto
import pt.tiago.neonline.core.content.VariantDto
import pt.tiago.neonline.core.content.CatalogDto
import pt.tiago.neonline.core.content.kindEnum
import pt.tiago.neonline.core.content.toState
import pt.tiago.neonline.core.content.forBossRound
import pt.tiago.neonline.core.content.levelJson

/** The strict content rules every published level must satisfy. */
object Validator {

    fun validateLevel(level: LevelFileDto): List<String> {
        val errors = mutableListOf<String>()
        if (level.schemaVersion != 1) errors += "unsupported schemaVersion ${level.schemaVersion}"
        if (level.engineVersion != GameEngine.ENGINE_VERSION) {
            errors += "engineVersion ${level.engineVersion} != engine ${GameEngine.ENGINE_VERSION}"
        }
        if (level.variants.keys != setOf("easy", "medium", "hard")) {
            errors += ContentError.VARIANT_COUNT.message
        }
        for ((key, variant) in level.variants) {
            errors += validateVariant(level, key, variant)
            if (variant.kind.type == "boss") {
                if (variant.kind.bestOf != 3 || variant.bossRounds.size != 3) errors += "boss requires three rounds ($key)"
                else {
                    if (variant.bossRounds.map { it.firstToMove } != listOf("P1","P2","P1")) errors += "boss starting player must alternate ($key)"
                    if (variant.bossRounds.first().seedHex != variant.seedHex || variant.bossRounds.first().pieces != variant.pieces) errors += "boss first opening differs from base ($key)"
                    if (variant.bossRounds.map { it.seedHex }.toSet().size != 3 || variant.bossRounds.map { it.pieces }.toSet().size != 3) errors += "boss rounds must be distinct ($key)"
                    for (index in 1..2) errors += validateVariant(level,key,variant.forBossRound(index))
                }
            } else if (variant.bossRounds.isNotEmpty()) errors += "non-boss has boss rounds ($key)"
        }
        if (level.isDoubleNeon && level.ordinal % 10 != 0) errors += "double neon levels must sit on ordinal 10, 20, ..."
        if (level.stopId == "stop-01" && level.ordinal > 1 && level.variants.values.any { it.pieces.isNotEmpty() }) {
            val openings = level.variants.values.map { variant -> variant.pieces.sortedWith(compareBy({ it.r }, { it.c })).map { "${it.r},${it.c},${it.owner},${it.kind}" } }
            if (openings.toSet().size != level.variants.size) errors += "difficulties repeat the same opening"
        }
        level.tutorial?.steps?.forEach { step ->
            if (step.textKey.isBlank()) errors += ContentError.TUTORIAL.message
        }
        return errors
    }

    private fun validateVariant(level: LevelFileDto, key: String, variant: VariantDto): List<String> {
        val errors = mutableListOf<String>()
        val tag = "${level.id}/$key"
        if (variant.kind.type == "defense" && variant.kind.surviveAiMoves?.let { it in 1..(variant.width * variant.height) } != true)
            errors += "invalid surviveAiMoves ($tag)"
        if (variant.width !in 4..9 || variant.height !in 4..7) errors += "${ContentError.SIZE.message} ($tag)"
        if (variant.connect != 4 && variant.connect != 5) errors += "${ContentError.CONNECT.message} ($tag)"
        if (variant.mask.size != variant.height || variant.mask.any { it.length != variant.width }) {
            errors += "${ContentError.MASK_SHAPE.message} ($tag)"
        }
        if (variant.mask.any { row -> row.any { it != 'X' && it != '#' } }) {
            errors += "${ContentError.MASK_SHAPE.message} ($tag)"
        }
        if (variant.rules.rotateEvery != null && variant.mask.any { it != "X".repeat(variant.width) }) {
            errors += "${ContentError.MASK_ROTATION.message} ($tag)"
        }
        if (variant.stars.three > variant.stars.two) errors += "${ContentError.STARS.message} ($tag)"
        if (variant.stars.three < 1) errors += "stars.three must be at least 1 ($tag)"
        if (variant.ai.maxDepth !in 1..12 || variant.ai.nodeBudget < 1) errors += "${ContentError.AI.message} ($tag)"
        if (variant.ai.blunderRate !in 0..1000) errors += "${ContentError.AI.message} ($tag)"

        val state = variant.toState(level.id, Difficulty.valueOf(key.uppercase()))
        for (piece in variant.pieces) {
            if (piece.r !in 0 until variant.height || piece.c !in 0 until variant.width) {
                errors += "${ContentError.PIECE_OUTSIDE.message} ($tag)"
                continue
            }
            val cell = state.index(piece.r, piece.c)
            if (!state.playable[cell]) errors += "${ContentError.PIECE_BLOCKED.message} ($tag)"
        }
        // Floating pieces are a content error: nothing may hover above an empty playable cell.
        for (c in 0 until variant.width) {
            for (r in 0 until variant.height - 1) {
                val above = state.pieceAt(r, c)
                val below = state.pieceAt(r + 1, c)
                if (above != null && below == null && state.isPlayable(state.index(r + 1, c))) {
                    errors += "${ContentError.PIECE_FLOATS.message} at row $r col $c ($tag)"
                }
            }
        }
        // No line may already exist.
        for (player in Player.entries) {
            if (GameEngine.findLines(state.pieces, state.width, state.height, state.connect, player).isNotEmpty()) {
                errors += "${ContentError.LINE_ALREADY.message} for $player ($tag)"
            }
        }
        if (level.stopId == "stop-01" && level.ordinal > 1) {
            val immediate = GameEngine.validMoves(state).any { move ->
                (GameEngine.apply(state, move).state.status as? pt.tiago.neonline.core.model.Status.Won)?.player == Player.P1
            }
            if (immediate) errors += "non-introductory level has an immediate winning move ($tag)"
            if (variant.pieces.isNotEmpty() && Player.entries.any { player -> variant.pieces.count { it.owner == player.name } < 3 }) {
                errors += "opening lacks a credible opponent position ($tag)"
            }
        }
        return errors
    }

    /** Catalog-level rules: contiguity, stop sizes and the boss level of each stop. */
    fun validateCatalog(catalog: CatalogDto, files: Map<String, LevelFileDto>): List<String> {
        val errors = mutableListOf<String>()
        if (catalog.engineVersion != GameEngine.ENGINE_VERSION) {
            errors += "catalog engineVersion mismatch"
        }
        for (entry in catalog.levels) {
            val file = files[entry.path] ?: run {
                errors += "catalog points to a missing file: ${entry.path}"
                return@run null
            } ?: continue
            if (file.revision != entry.revision) errors += "revision mismatch for ${entry.path}"
            val expected = sha256(levelJson(file))
            if (expected != entry.sha256) {
                errors += "sha256 mismatch for ${entry.path} (catalog ${entry.sha256.take(8)}, file ${expected.take(8)})"
            }
        }
        val ordinals = files.values.map { it.ordinal }.sorted()
        val expectedOrdinals = (1..ordinals.size).toList()
        if (ordinals != expectedOrdinals) errors += "${ContentError.ORDINAL_GAP.message} (found ${ordinals.size} levels)"
        for (stop in catalog.stops) {
            val inStop = files.values.filter { it.stopId == stop.id }
            if (inStop.isEmpty()) errors += "stop ${stop.id} has no levels"
            // A complete stop of 50 levels ends with a boss; a partial test stop has none yet.
            if (stop.to % 50 == 0) {
                val boss = inStop.filter { it.ordinal == stop.to }
                if (boss.isEmpty() || boss.any { level -> level.variants.values.any { it.kindEnum().name != "BOSS" } }) {
                    errors += "${ContentError.BOSS_STOP.message} for stop ${stop.id}"
                }
            }
            for (level in inStop) {
                val variantErrors = validateLevel(level)
                errors += variantErrors
            }
            // Revalidate every level, not only the ones named by the catalog.
        }
        val stopsById = catalog.stops.associateBy { it.id }
        for (level in files.values) {
            val stop = stopsById[level.stopId]
            if (stop == null) {
                errors += "level ${level.id} references an unknown stop ${level.stopId}"
            } else if (level.ordinal !in stop.from..stop.to) {
                errors += "level ${level.id} ordinal ${level.ordinal} is outside its stop range ${stop.from}..${stop.to}"
            }
            if (catalog.levels.none { it.path == "levels/${level.stopId}/${level.id}.json" }) {
                errors += "level ${level.id} is missing from the catalog"
            }
        }
        return errors.distinct()
    }
}
