// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.org

import com.calebc42.composer.model.AppSpec

/**
 * Composer's FORMAT-level org boundary.
 *
 * A general org parser must not depend on [AppSpec]. This interface is the
 * adapter seam between syntax libraries and Composer's Jetpacs vocabulary.
 */
interface OrgAppCodec {
    fun parse(text: String): AppSpec
    fun write(spec: AppSpec): String
}
