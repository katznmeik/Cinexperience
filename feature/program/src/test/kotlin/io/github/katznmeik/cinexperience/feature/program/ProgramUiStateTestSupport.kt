package io.github.katznmeik.cinexperience.feature.program

/** The program is shown, in the OFF mode read-only (the screen decides this per [ProgramMode] itself). */
internal val ProgramUiState.showsProgram: Boolean
    get() = mode == ProgramMode.PROGRAM || (mode == ProgramMode.OFF && hasProgram)
