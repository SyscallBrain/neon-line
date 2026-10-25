package pt.tiago.neonline.leveltool

import pt.tiago.neonline.core.content.*

/** Bind guided moves to the already verified solution for this exact variant. */
internal fun certifiedTutorial(level: LevelFileDto, difficulty: String, certificate: CertificateDto): TutorialDto? {
    val tutorial = level.tutorial ?: return null
    val variant = level.variants.getValue(difficulty)
    require(certificate.levelId == level.id && certificate.difficulty == difficulty)
    require(certificate.engineVersion == level.engineVersion && certificate.revision == level.revision && certificate.seedHex == variant.seedHex)
    val humanParity = if (variant.firstToMove == "P1") 0 else 1
    val humanSteps = certificate.steps.filterIndexed { index, _ -> index % 2 == humanParity }
    require(humanSteps.size >= tutorial.steps.size) { "Tutorial exceeds certified human moves: ${level.id}/$difficulty" }
    return tutorial.copy(steps = tutorial.steps.mapIndexed { index, step ->
        if (step.forcedCol == null) step else {
            val match = Regex("Drop\\(col=(\\d+), kind=NORMAL\\)").matchEntire(humanSteps[index].move)
                ?: error("Guided action is not a normal drop: ${level.id}/$difficulty/$index")
            val col = match.groupValues[1].toInt()
            step.copy(forcedCol = col, highlightCols = listOf(col))
        }
    })
}
