package io.github.katznmeik.cinexperience.core.model

/*
 * Editing helpers only the tests use (the editor doesn't need them). core:data's
 * tests have their own copy, since this module's tests can't use core:testing.
 */

/** Whether the session's layout differs from the one it started with (rows compared). */
internal val LayoutEditSession.hasChanges: Boolean get() = layout?.rows != original?.rows
