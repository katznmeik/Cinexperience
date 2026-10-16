package io.github.katznmeik.cinexperience.core.data

import io.github.katznmeik.cinexperience.core.model.LayoutEditSession

/*
 * Editing helpers only the tests use (the editor doesn't need them); the same as
 * in core:model's tests.
 */

/** Whether the session's layout differs from the one it started with (rows compared). */
internal val LayoutEditSession.hasChanges: Boolean get() = layout?.rows != original?.rows
