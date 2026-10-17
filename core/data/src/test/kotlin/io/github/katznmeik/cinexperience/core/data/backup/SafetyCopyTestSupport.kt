package io.github.katznmeik.cinexperience.core.data.backup

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The newest safety copy, `null` if there is none (only the tests need it on its own). */
internal val BackupRepository.latestSafetyCopy: Flow<SafetyCopy?> get() = safetyCopies.map { it.firstOrNull() }
