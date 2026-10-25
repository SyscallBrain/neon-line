package pt.tiago.neonline.leveltool

import pt.tiago.neonline.core.model.Difficulty
import pt.tiago.neonline.core.rules.GameEngine
import pt.tiago.neonline.core.serialization.StateHasher
import java.io.File
import java.security.MessageDigest
import pt.tiago.neonline.core.content.TutorialDto
import pt.tiago.neonline.core.content.LevelFileDto
import pt.tiago.neonline.core.content.CertificateDto
import pt.tiago.neonline.core.content.CatalogDto
import pt.tiago.neonline.core.content.difficultyKey
import pt.tiago.neonline.core.content.toState
import pt.tiago.neonline.core.content.forBossRound
import pt.tiago.neonline.core.content.kindEnum

/**
 * Offline content pipeline for NeonLine.
 *
 * `validate` and `verify-certificates` run in the build; `generate`, `solve` and `certify` produce
 * content; `report` summarises it; `export-assets` copies the verified catalog into app assets with
 * a SHA-256 manifest.
 */
fun main(args: Array<String>) {
    val command = args.firstOrNull()
    try {
        when (command) {
            "diner-service" -> DinerService.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/diner-service"))
            "motel-service" -> MotelService.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/motel-service"))
            "bowling-service" -> BowlingService.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/bowling-service"))
            "drivein-service" -> DriveInService.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/drivein-service"))
            "drivein-followup-service" -> DriveInContinuationService.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/drivein-followup-service"))
            "drivein-epilogue-service" -> DriveInEpilogueService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/drivein-epilogue-service"),
                option(args,"--from")?.toInt() ?: 221, option(args,"--to")?.toInt() ?: 230)
            "drivein-finale-service" -> DriveInFinaleService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/drivein-finale-service"),
                option(args,"--from")?.toInt() ?: 231, option(args,"--to")?.toInt() ?: 240)
            "drivein-closing-service" -> DriveInClosingService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/drivein-closing-service"),
                option(args,"--from")?.toInt() ?: 241, option(args,"--to")?.toInt() ?: 249)
            "laundromat-service" -> LaundromatService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/laundromat-service"))
            "laundromat-followup-service" -> LaundromatContinuationService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/laundromat-followup-service"))
            "laundromat-finale-service" -> LaundromatFinaleService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/laundromat-finale-service"))
            "laundromat-closing-service" -> LaundromatClosingService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/laundromat-closing-service"))
            "laundromat-epilogue-service" -> LaundromatEpilogueService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/laundromat-epilogue-service"))
            "laundromat-boss-base" -> LaundromatBoss.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/laundromat-boss-base"))
            "disco-rink-service" -> DiscoRinkService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/disco-rink-service"))
            "karaoke-service" -> KaraokeService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/karaoke-service"))
            "pixel-arcade-service" -> PixelArcadeService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/pixel-arcade-service"))
            "night-fair-service" -> NightFairService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/night-fair-service"))
            "night-fair-followup-service" -> NightFairFollowupService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/night-fair-followup-service"))
            "night-fair-coda-service" -> NightFairCodaService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/night-fair-coda-service"))
            "night-fair-finale-service" -> NightFairFinaleService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/night-fair-finale-service"))
            "night-fair-encore-service" -> NightFairEncoreService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/night-fair-encore-service"))
            "ace-casino-service" -> AceCasinoService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/ace-casino-service"))
            "ace-casino-followup-service" -> AceCasinoFollowupService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/ace-casino-followup-service"))
            "ace-casino-closing-service" -> AceCasinoClosingService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/ace-casino-closing-service"))
            "ace-casino-finale-service" -> AceCasinoFinaleService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/ace-casino-finale-service"))
            "ace-casino-stop-finale-service" -> AceCasinoStopFinaleService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/ace-casino-stop-finale-service"))
            "boulevard-of-lights-service" -> BoulevardOfLightsService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/boulevard-of-lights-service"))
            "boulevard-of-lights-followup-service" -> BoulevardOfLightsFollowupService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/boulevard-of-lights-followup-service"))
            "boulevard-of-lights-encore-service" -> BoulevardOfLightsEncoreService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/boulevard-of-lights-encore-service"))
            "boulevard-of-lights-closing-service" -> BoulevardOfLightsClosingService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/boulevard-of-lights-closing-service"))
            "boulevard-of-lights-finale-service" -> BoulevardOfLightsFinaleService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/boulevard-of-lights-finale-service"))
            "karaoke-followup-service" -> KaraokeContinuationService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/karaoke-followup-service"))
            "karaoke-encore-service" -> KaraokeEncoreService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/karaoke-encore-service"))
            "karaoke-spotlight-service" -> KaraokeSpotlightService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/karaoke-spotlight-service"))
            "karaoke-finale-service" -> KaraokeFinaleService.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/karaoke-finale-service"))
            "karaoke-boss-base" -> KaraokeBoss.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/karaoke-boss-base"))
            "disco-rink-boss-base" -> DiscoRinkBoss.run(
                File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/disco-rink-boss-base"))
            "drivein-boss-base" -> DriveInBoss.run(File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/drivein-boss-base"))
            "bowling-boss-base" -> BowlingBoss.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/bowling-boss-base"))
            "diner-boss-base" -> DinerBoss.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/diner-boss-base"))
            "motel-boss-base" -> MotelBoss.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/motel-boss-base"))
            "audit-diner-service" -> DinerService.audit(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/diner-service-audit.json"),option(args,"--games")?.toInt() ?: 64)
            "diner-entry" -> DinerEntry.run(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/diner-entry"))
            "audit-diner" -> DinerEntry.audit(File(option(args,"--root") ?: "content"),File(option(args,"--output") ?: "build/diner-audit.json"),option(args,"--games")?.toInt() ?: 16)
            "validate" -> validate(args)
            "verify-certificates" -> verifyCertificates(args)
            "solve" -> solve(args)
            "refresh-certificates" -> ContentRefresh.run(File(option(args, "--root") ?: "content"))
            "audit-difficulty" -> DifficultyAudit.run(File(option(args, "--root") ?: "content"), option(args, "--games")?.toInt() ?: 12)
            "boss-series" -> BossContent.run(File(option(args,"--root") ?: "content"), File(option(args,"--output") ?: "build/boss-content"), option(args,"--only-level")?.toInt()?.let(::setOf))
            "rebalance" -> CampaignRebalance.run(
                File(option(args, "--root") ?: "content"),
                File(option(args, "--output") ?: "build/rebalanced-content"),
                option(args, "--from")?.toInt() ?: 1,
                option(args, "--to")?.toInt() ?: 50,
            )
            "generate" -> generate(args)
            "certify" -> certify(args)
            "report" -> report(args)
            "export-assets" -> exportAssets(args)
            "calibrate" -> calibrate(args)
            "daily-pool" -> DailyPool.generate(File(option(args, "--root") ?: "content"),
                File(option(args, "--output") ?: "content/daily/pool.json"), option(args, "--version")?.toInt() ?: 1)
            "rush-pool" -> RushPool.write(File(option(args,"--output") ?: "content/rush/pool.json"),
                option(args,"--count")?.toInt() ?: 500, option(args,"--seed")?.toLong() ?: 20261016L, option(args,"--version")?.toInt() ?: 1)
            "build-book" -> {
                val options = parseBuildBookCommandOptions(args)
                val output = File(options.output ?: "content/book/book-7x6.bin")
                val report = PerfectOpeningBookBuilder(onProgress = { ply, solved ->
                    println("build-book: ply $ply complete, $solved exact positions")
                }, onNodeProgress = { ply, nodes ->
                    println("build-book: ply $ply solving, $nodes nodes")
                }).build(options.depth, output,
                    checkpoint = options.checkpoint?.let(::File),
                    solverCheckpoint = options.solverCheckpoint?.let(::File))
                println("build-book: wrote ${report.entries} exact positions through ply ${report.maxPly} to ${output.path}")
            }
            null, "help", "--help" -> printHelp()
            else -> {
                println("Unknown command: $command")
                printHelp()
            }
        }
    } catch (e: Exception) {
        System.err.println("$command failed: ${e.message}")
        e.printStackTrace()
        kotlin.system.exitProcess(1)
    }
}

private fun printHelp() {
    println(
        """
        NeonLine leveltool - offline content pipeline

        usage: leveltool <command> [options]

        commands:
          validate --root content [--strict]      Validate the published catalog
          verify-certificates --root content      Replay every certificate with the engine
          solve --level <id> [--difficulty d]     Find a three star solution and write its certificate
          generate --stop <n>                     Generate candidate levels into content/candidates
          diner-entry --root content --output <dir>  Stage certified Diner levels51–60
          diner-service --root content --output <dir>  Stage family-driven Diner levels61–99
          motel-service --root content --output <dir>  Stage the first Flamingo Motel block 101–110
          bowling-service --root content --output <dir>  Stage the next ten certified Strike Lanes levels
          drivein-service --root content --output <dir>  Stage the first ten certified Cinema Drive-In levels
          drivein-followup-service --root content --output <dir>  Stage certified Cinema Drive-In levels 211–220
          drivein-epilogue-service --root content --output <dir> [--from 221 --to 230]  Stage certified Cinema Drive-In levels 221–230
          drivein-finale-service --root content --output <dir> [--from 231 --to 240]  Stage certified Cinema Drive-In levels 231–240
          drivein-closing-service --root content --output <dir> [--from 241 --to 249]  Stage certified Cinema Drive-In levels 241–249
          laundromat-service --root content --output <dir>  Stage certified Bubble Laundromat levels 251–260
          laundromat-followup-service --root content --output <dir>  Stage certified Bubble Laundromat levels 261–270
          laundromat-finale-service --root content --output <dir>  Stage certified Bubble Laundromat levels 271–280
          laundromat-closing-service --root content --output <dir>  Stage certified Bubble Laundromat levels 281–290
          laundromat-epilogue-service --root content --output <dir>  Stage certified Bubble Laundromat levels 291–299
          laundromat-boss-base --root content --output <dir>  Stage the certified Centrifuge boss opening at level 300
          disco-rink-service --root content --output <dir>  Stage certified Disco Rink levels 341–350
          karaoke-service --root content --output <dir>  Stage certified Moon Karaoke levels 351–360
          pixel-arcade-service --root content --output <dir>  Stage certified Pixel Arcade levels 401–450
          night-fair-service --root content --output <dir>  Stage certified Night Fair levels 451–460
          night-fair-followup-service --root content --output <dir>  Stage certified Night Fair levels 461–470
          night-fair-coda-service --root content --output <dir>  Stage certified Night Fair levels 481–490
          night-fair-finale-service --root content --output <dir>  Stage the Night Fair finale at levels 491–500
          night-fair-encore-service --root content --output <dir>  Stage certified Night Fair levels 471–480
          ace-casino-service --root content --output <dir>  Stage Ace Casino levels 501–510 with combined rules
          ace-casino-followup-service --root content --output <dir>  Stage Ace Casino levels 511–520 with combined rules
          ace-casino-closing-service --root content --output <dir>  Stage Ace Casino levels 521–530 with combined rules
          ace-casino-finale-service --root content --output <dir>  Stage Ace Casino levels 531–540 with combined rules
          ace-casino-stop-finale-service --root content --output <dir>  Stage the Ace Casino finale at levels 541–550
          boulevard-of-lights-service --root content --output <dir>    Stage Boulevard of Lights levels 551–560
          boulevard-of-lights-followup-service --root content --output <dir>    Stage Boulevard of Lights levels 561–570
          boulevard-of-lights-encore-service --root content --output <dir>    Stage Boulevard of Lights levels 571–580
          boulevard-of-lights-closing-service --root content --output <dir>    Stage Boulevard of Lights levels 581–590
          boulevard-of-lights-finale-service --root content --output <dir>    Stage the Queen boss and Boulevard finale at level 600
          karaoke-followup-service --root content --output <dir>  Stage certified Moon Karaoke levels 361–370
          karaoke-encore-service --root content --output <dir>  Stage certified Moon Karaoke levels 371–380
          karaoke-spotlight-service --root content --output <dir>  Stage certified Moon Karaoke levels 381–390
          karaoke-finale-service --root content --output <dir>  Stage certified Moon Karaoke levels 391–399
          karaoke-boss-base --root content --output <dir>  Stage Diva's boss opening at level 400
          drivein-boss-base --root content --output <dir>  Stage the certified Projectionist boss opening at level 250
          bowling-boss-base --root content --output <dir>  Stage the certified Strike boss opening at level200
          diner-boss-base --root content --output <dir>  Stage the certified Dolores boss opening at level100
          motel-boss-base --root content --output <dir>  Stage the certified Flamingo boss opening at level150
          audit-diner-service --root content --output <file> [--games 64]  Independent service sample
          audit-diner --root content --output <file> [--games 16]  Independent Diner sample
          certify --stop <n>                      generate -> solve -> promote into content/levels
          boss-series --root content --output <dir> [--only-level n]  Stage three certified boss rounds
          rebalance --root content --output <dir> [--from n --to n]  Build distinct calibrated openings
          refresh-certificates --root <dir>       Migrate certificate hashes to the current engine
          audit-difficulty --root content [--games 12]  Independent simulated-player sample
          calibrate --level <id> [--games 60]     Measure the published rival profile
          daily-pool --root content --output content/daily/pool.json [--version n]  Freeze certified daily puzzles
          report --output report.md               Content distribution report
          rush-pool [--count 500] [--seed 20261016] [--version 1] [--output content/rush/pool.json]
          build-book --depth <0..42> [--output content/book/book-7x6.bin] [--checkpoint <file>] [--solver-checkpoint <file>]  Generate/resume an exact classic opening book
          export-assets --root content --output <dir>   Copy verified content into app assets
        """.trimIndent(),
    )
}

private fun levelFiles(root: File): List<Pair<File, LevelFileDto>> {
    val levelsDir = File(root, "levels")
    if (!levelsDir.isDirectory) return emptyList()
    return levelsDir.walkTopDown()
        .filter { it.isFile && it.extension == "json" }
        .map { file -> file to Certificates.decodeLevel(file.readText()) }
        .sortedBy { it.second.ordinal }
        .toList()
}

private fun catalogFile(root: File): File = File(root, "catalog.json")

private fun validate(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val levels = levelFiles(root)
    val files = levels.associate { (file, level) -> relativePath(root, file) to level }
    val errors = mutableListOf<String>()
    val catalog = if (catalogFile(root).isFile) {
        kotlinx.serialization.json.Json.decodeFromString(CatalogDto.serializer(), catalogFile(root).readText())
    } else {
        null
    }
    if (catalog == null) {
        errors += "missing content/catalog.json"
    } else {
        errors += Validator.validateCatalog(catalog, files)
    }
    if (errors.isEmpty()) {
        println("validate: OK (${levels.size} levels, ${levels.sumOf { it.second.variants.size }} variants)")
    } else {
        errors.forEach { println("validate: $it") }
        println("validate: FAILED with ${errors.size} problem(s)")
        kotlin.system.exitProcess(1)
    }
}

private fun verifyCertificates(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val certsDir = File(root, "certificates")
    var checked = 0
    val errors = mutableListOf<String>()
    for ((file, level) in levelFiles(root)) {
        for ((key,variant) in level.variants) {
            val certificates = mutableListOf<CertificateDto>()
            val slots=if(variant.kind.type=="boss") 0..2 else 0..0
            for(slot in slots) {
                val name=if(slot==0) key else "$key-round-${slot+1}"
                val certFile=File(certsDir,"${level.id}/$name.json")
                if(!certFile.isFile) { errors += "missing ${certFile.path}"; continue }
                val certificate=Certificates.decodeCertificate(certFile.readText())
                val problems=Certificates.verify(level,certificate).toMutableList()
                if(certificate.seedHex != variant.forBossRound(slot).seedHex) problems += "round opening mismatch"
                if(problems.isEmpty()) { checked++; certificates += certificate }
                else problems.forEach { errors += "${certFile.path}: $it" }
            }
            if(variant.kind.type=="boss" && certificates.size==3) {
                val largestPair=certificates.map { it.humanMoves }.sortedDescending().take(2).sum()
                if(largestPair > variant.stars.three) errors += "${level.id}/$key cannot certify three stars across two won rounds"
            }
        }
    }
    if (errors.isEmpty()) {
        println("verify-certificates: OK ($checked certificates replayed)")
        val rushFile=File(root,"rush/pool.json")
        if(rushFile.isFile) {
            val pool=kotlinx.serialization.json.Json.decodeFromString(pt.tiago.neonline.core.content.RushPoolDto.serializer(),rushFile.readText())
            RushPool.verify(pool)
            println("verify-rush: OK (${pool.entries.size} exact minimal forced-win certificates)")
        }
    } else {
        errors.forEach { println("verify-certificates: $it") }
        println("verify-certificates: FAILED (${errors.size} problem(s) in $checked good certificates)")
        kotlin.system.exitProcess(1)
    }
}

private fun solve(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val levelId = option(args, "--level") ?: error("solve needs --level <id>")
    val levels = levelFiles(root).map { it.second }
    val level = levels.firstOrNull { it.id == levelId } ?: error("unknown level $levelId")
    val difficulty = option(args, "--difficulty")?.let { Difficulty.valueOf(it.uppercase()) }
    val targets = difficulty?.let { listOf(it) } ?: Difficulty.entries.toList()
    var written = 0
    for (d in targets) {
        val certificate = Certificates.solve(level, d)
        if (certificate == null) {
            println("solve: no three star solution for $levelId/${d.name.lowercase()}")
            continue
        }
        val out = File(root, "certificates/${level.id}/${difficultyKey(d)}.json")
        out.parentFile.mkdirs()
        out.writeText(pretty.encodeToString(CertificateDto.serializer(), certificate) + "\n")
        println("solve: wrote ${out.path} (${certificate.steps.size} plies, 3 stars)")
        written++
    }
    if (written == 0) kotlin.system.exitProcess(1)
}

private fun generate(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val stop = option(args, "--stop")?.toIntOrNull() ?: error("generate needs --stop <n>")
    val from = option(args, "--from")?.toIntOrNull() ?: ((stop - 1) * 50 + 1)
    val to = option(args, "--to")?.toIntOrNull() ?: (stop * 50)
    val generated = Generator.generate(root, stop, from, to)
    println("generate: wrote ${generated.size} candidates into content/candidates/stop-%02d".format(stop))
}

private fun certify(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val stop = option(args, "--stop")?.toIntOrNull() ?: error("certify needs --stop <n>")
    val promoted = Certifier.certify(root, stop)
    println("certify: promoted ${promoted.size} levels into content/levels")
}

private fun calibrate(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val levelId = option(args, "--level") ?: error("calibrate needs --level <id>")
    val games = option(args, "--games")?.toIntOrNull() ?: 60
    val level = Files.levels(root).first { it.id == levelId }
    val difficulty = option(args, "--difficulty")?.let { Difficulty.valueOf(it.uppercase()) }
    val targets = difficulty?.let { listOf(it) } ?: Difficulty.entries.toList()
    val agent = Calibrator
    for (d in targets) {
        val result = agent.calibrate(root, levelId, d, games)
        val verdict = if (result.inBand) "in band" else "OUT OF BAND"
        println(
            "calibrate: $levelId/${difficultyKey(d)} ${"%.0f".format(result.winRate * 100)}% over ${result.games} games " +
                "($verdict, band ${"%.0f".format(Calibrator.band(d, level.isDoubleNeon, Calibrator.isNoveltyIntroduction(level.ordinal)).start * 100)}-" +
                "${"%.0f".format(Calibrator.band(d, level.isDoubleNeon, Calibrator.isNoveltyIntroduction(level.ordinal)).endInclusive * 100)}%, " +
                "avg ${"%.1f".format(result.solverMoves)} moves)",
        )
    }
}

private fun report(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val output = option(args, "--output") ?: "report.md"
    val levels = levelFiles(root).map { it.second }
    val kinds = levels.flatMap { it.variants.values }.groupingBy { it.kindEnum().name }.eachCount()
    val families = levels.groupingBy { it.familyId }.eachCount()
    val text = buildString {
        appendLine("# NeonLine content report")
        appendLine()
        appendLine("* levels: ${levels.size}")
        appendLine("* variants: ${levels.sumOf { it.variants.size }}")
        appendLine("* families: ${families.size}")
        appendLine()
        appendLine("## Level kinds")
        appendLine()
        appendLine("| Kind | Variants |")
        appendLine("|---|---|")
        kinds.forEach { (kind, count) -> appendLine("| $kind | $count |") }
        appendLine()
        appendLine("## Families")
        appendLine()
        appendLine("| Family | Levels |")
        appendLine("|---|---|")
        families.forEach { (family, count) -> appendLine("| $family | $count |") }
        appendLine()
        appendLine("## Difficulty quality")
        appendLine()
        appendLine("| Difficulty | Immediate wins | Average certified player moves |")
        appendLine("|---|---|---|")
        Difficulty.entries.forEach { difficulty ->
            val key = difficultyKey(difficulty)
            val immediate = levels.count { level ->
                val state = level.variants.getValue(key).toState(level.id, difficulty)
                GameEngine.validMoves(state).any { move ->
                    (GameEngine.apply(state, move).state.status as? pt.tiago.neonline.core.model.Status.Won)?.player == pt.tiago.neonline.core.model.Player.P1
                }
            }
            val moves = levels.mapNotNull { level -> File(root, "certificates/${level.id}/$key.json").takeIf { it.isFile }?.let {
                val first = Certificates.decodeCertificate(it.readText()).humanMoves
                if(level.variants.getValue(key).kind.type == "boss") first + Certificates.decodeCertificate(File(root,"certificates/${level.id}/$key-round-2.json").readText()).humanMoves else first
            } }
            appendLine("| $key | $immediate / ${levels.size} | ${"%.1f".format(moves.average())} |")
        }
        appendLine()
        appendLine("The first lesson and one level introducing each new Motel shape rule deliberately have an immediate win. Other openings must not.")
        val sampleFile = File(root, "balance-sample.json")
        if (sampleFile.isFile) {
            val sample = kotlinx.serialization.json.Json.decodeFromString(DifficultyAudit.Report.serializer(), sampleFile.readText())
            appendLine()
            appendLine("## Independent simulated-player sample")
            appendLine()
            appendLine("Policy version ${sample.policyVersion}. Fixed stratified levels, separate seeds from candidate selection; no hints or tutorial guidance. Boss samples now count complete best-of-three confrontations.")
            appendLine()
            appendLine("| Difficulty | Wins | Games | Win rate |")
            appendLine("|---|---|---|---|")
            Difficulty.entries.forEach { difficulty ->
                val key = difficultyKey(difficulty)
                val rows = sample.samples.filter { it.difficulty == key }
                val wins = rows.sumOf { it.wins }
                val games = rows.sumOf { it.games }
                appendLine("| $key | $wins | $games | ${"%.1f".format(wins * 100.0 / games)}% |")
            }
            appendLine()
            appendLine("This is a limited simulation sample, not a human playtest or proof that every level meets the target bands.")
        }
    }
    val out = File(output)
    out.parentFile?.mkdirs()
    out.writeText(text)
    println("report: wrote $output")
}

private fun exportAssets(args: Array<String>) {
    val root = File(option(args, "--root") ?: "content")
    val output = File(option(args, "--output") ?: error("export-assets needs --output <dir>"))
    val levels = levelFiles(root)
    require(levels.isNotEmpty()) { "no content to export" }
    val errors = Validator.validateCatalog(readCatalog(root), levels.associate { relativePath(root, it.first) to it.second })
    require(errors.isEmpty()) { "content is not valid: ${errors.first()}" }
    output.mkdirs()
    val manifest = StringBuilder()
    manifest.appendLine("format=neonline-assets-1")
    manifest.appendLine("engineVersion=${GameEngine.ENGINE_VERSION}")
    manifest.appendLine("hashVersion=${StateHasher.HASH_VERSION}")
    for ((file, level) in levels) {
        val target = File(output, relativePath(root, file))
        target.parentFile.mkdirs()
        target.writeText(file.readText())
        val sha = sha256(target.readBytes())
        manifest.appendLine("${relativePath(root, file)}=$sha")
        if (level.tutorial != null) {
            level.variants.keys.sorted().forEach { difficulty ->
                val certificate = Certificates.decodeCertificate(File(root, "certificates/${level.id}/$difficulty.json").readText())
                val guide = certifiedTutorial(level, difficulty, certificate)!!
                val path = "tutorials/${level.id}/$difficulty.json"
                val guideFile = File(output, path)
                guideFile.parentFile.mkdirs()
                guideFile.writeText(pretty.encodeToString(TutorialDto.serializer(), guide))
                manifest.appendLine("$path=${sha256(guideFile.readBytes())}")
            }
        }
    }
    // The catalog itself travels with the content, so the app can find the levels without a network.
    val catalog = catalogFile(root)
    if (catalog.isFile) {
        val target = File(output, "levels/catalog.json")
        target.parentFile.mkdirs()
        target.writeText(catalog.readText())
        manifest.appendLine("levels/catalog.json=${sha256(target.readBytes())}")
    }
    val dailyFile = File(root, "daily/pool.json")
    if (dailyFile.isFile) {
        DailyPool.verify(root, kotlinx.serialization.json.Json.decodeFromString(pt.tiago.neonline.core.content.DailyPoolDto.serializer(), dailyFile.readText()))
        val target = File(output, "daily/pool.json")
        target.parentFile.mkdirs(); target.writeText(dailyFile.readText())
        manifest.appendLine("daily/pool.json=${sha256(target.readBytes())}")
    }
    val rushFile=File(root,"rush/pool.json")
    if(rushFile.isFile) {
        val pool=kotlinx.serialization.json.Json.decodeFromString(pt.tiago.neonline.core.content.RushPoolDto.serializer(),rushFile.readText())
        RushPool.verify(pool)
        val target=File(output,"rush/pool.json")
        target.parentFile.mkdirs();target.writeText(rushFile.readText())
        manifest.appendLine("rush/pool.json=${sha256(target.readBytes())}")
    }
    copyPerfectBookIfPresent(root, output)?.let { book ->
        manifest.appendLine("${relativePath(output, book)}=${sha256(book.readBytes())}")
    }
    File(output, "manifest.txt").writeText(manifest.toString())
    println("export-assets: ${levels.size} levels into ${output.path} with a sha256 manifest")
}

private fun readCatalog(root: File): CatalogDto {
    val file = catalogFile(root)
    require(file.isFile) { "missing content/catalog.json" }
    return kotlinx.serialization.json.Json.decodeFromString(CatalogDto.serializer(), file.readText())
}

private fun relativePath(root: File, file: File): String =
    file.relativeTo(root).path.replace(File.separatorChar, '/')

private fun option(args: Array<String>, name: String): String? {
    val index = args.indexOf(name)
    return if (index >= 0 && index + 1 < args.size) args[index + 1] else null
}

private val pretty = kotlinx.serialization.json.Json { prettyPrint = true; encodeDefaults = true }

internal fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

internal fun sha256(text: String): String = sha256(text.toByteArray())
