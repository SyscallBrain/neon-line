package pt.tiago.neonline.leveltool

import java.io.ByteArrayInputStream
import java.io.File
import pt.tiago.neonline.core.solver.PerfectOpeningBook

/** Validates and copies the optional generated NLBK book into the packaged asset tree. */
internal fun copyPerfectBookIfPresent(root: File, output: File): File? {
    val source = File(root, "book/book-7x6.bin")
    if (!source.isFile) {
        File(output, "book/book-7x6.bin").delete()
        return null
    }
    val bytes = source.readBytes()
    PerfectOpeningBook.read(ByteArrayInputStream(bytes))
    val target = File(output, "book/book-7x6.bin")
    require(target.parentFile.isDirectory || target.parentFile.mkdirs()) { "cannot create book asset directory" }
    target.writeBytes(bytes)
    return target
}
