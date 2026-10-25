package pt.tiago.neonline.leveltool

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class MotelServiceRangeTest {
    @Test fun `resume cutoffs select exactly the missing Motel lesson range`() {
        val recipes = MotelService.load(File("content"))

        for (lastPublished in listOf(100, 110, 120, 130)) {
            assertEquals(
                (lastPublished + 1..149).toList(),
                MotelService.missingLessons(recipes, lastPublished).map { it.ordinal },
            )
        }
    }
}
