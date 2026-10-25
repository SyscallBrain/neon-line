package pt.tiago.neonline.leveltool

import java.io.File

/** Public build-book arguments; solver budgets remain an internal test seam. */
internal data class BuildBookCommandOptions(
    val depth: Int,
    val output: String?,
    val checkpoint: String?,
    val solverCheckpoint: String? = null,
)

internal fun parseBuildBookCommandOptions(args: Array<String>): BuildBookCommandOptions {
    require(args.firstOrNull() == "build-book") { "expected build-book command" }
    val values = mutableMapOf<String, String>()
    var index = 1
    while (index < args.size) {
        val name = args[index]
        require(name == "--depth" || name == "--output" || name == "--checkpoint" || name == "--solver-checkpoint") {
            "unsupported build-book option: $name"
        }
        require(name !in values) { "duplicate build-book option: $name" }
        val value = args.getOrNull(index + 1)
        require(value != null && !value.startsWith("--")) { "$name requires a value" }
        values[name] = value
        index += 2
    }

    val depth = values["--depth"]?.toIntOrNull()
        ?: throw IllegalArgumentException("build-book requires --depth <0..42>")
    require(depth in 0..42) { "build-book depth must be in 0..42" }
    val output = values["--output"]
    val checkpoint = values["--checkpoint"]
    val solverCheckpoint = values["--solver-checkpoint"]
    val paths = listOfNotNull(output, checkpoint, solverCheckpoint)
        .map { File(it).toPath().toAbsolutePath().normalize() }
    require(paths.distinct().size == paths.size) {
        "book output and checkpoint paths must differ"
    }
    return BuildBookCommandOptions(depth = depth, output = output, checkpoint = checkpoint,
        solverCheckpoint = solverCheckpoint)
}
