package pt.tiago.neonline.ui.screens

import pt.tiago.neonline.R

/** Explicit resource mapping keeps content keys independent of Android resource identifiers. */
fun tutorialTextResource(key: String): Int = when (key) {
    "tut_defense_1" -> R.string.tut_defense_1
    "tut_01_step_1" -> R.string.tut_01_step_1
    "tut_101_diagonal" -> R.string.tut_101_diagonal
    "tut_151_small_board" -> R.string.tut_151_small_board
    "tut_152_pillars" -> R.string.tut_152_pillars
    "tut_153_ceiling" -> R.string.tut_153_ceiling
    "tut_201_popout" -> R.string.tut_201_popout
    "tut_301_clock" -> R.string.tut_301_clock
    "tut_351_fade" -> R.string.tut_351_fade
    "tut_401_bomb" -> R.string.tut_401_bomb
    "tut_410_wild" -> R.string.tut_410_wild
    "tut_420_lock" -> R.string.tut_420_lock
    "tut_451_five" -> R.string.tut_451_five
    "tut_02_step_1" -> R.string.tut_02_step_1
    "tut_03_step_1" -> R.string.tut_03_step_1
    "tut_04_step_1" -> R.string.tut_04_step_1
    "tut_05_step_1" -> R.string.tut_05_step_1
    "tut_06_step_1" -> R.string.tut_06_step_1
    "tut_07_step_1" -> R.string.tut_07_step_1
    "tut_08_step_1" -> R.string.tut_08_step_1
    "tut_09_step_1" -> R.string.tut_09_step_1
    "tut_10_step_1" -> R.string.tut_10_step_1
    else -> R.string.tutorial_generic
}
