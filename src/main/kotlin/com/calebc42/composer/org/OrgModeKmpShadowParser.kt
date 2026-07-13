// SPDX-License-Identifier: GPL-3.0-or-later
package com.calebc42.composer.org

import xyz.lepisma.orgmode.lexer.OrgLexer
import xyz.lepisma.orgmode.lexer.inverseLex
import xyz.lepisma.orgmode.parse
import xyz.lepisma.orgmode.unparse

/** Read-only evidence produced by the non-authoritative orgmode-kmp path. */
data class OrgSyntaxInspection(
    val parsed: Boolean,
    val reconstructedText: String? = null,
) {
    fun exactlyReconstructs(source: String): Boolean = parsed && reconstructedText == source
}

/**
 * Thin containment layer around orgmode-kmp.
 *
 * It deliberately exposes no third-party AST and performs no Composer FORMAT
 * interpretation or mutation. [OrgCodec] remains authoritative while fixture
 * parity and mutation-preservation work proceeds.
 */
object OrgModeKmpShadowParser {
    fun inspect(text: String): OrgSyntaxInspection {
        val tokens = OrgLexer(text).tokenize()
        val document = parse(tokens) ?: return OrgSyntaxInspection(parsed = false)
        return OrgSyntaxInspection(
            parsed = true,
            reconstructedText = inverseLex(unparse(document)),
        )
    }
}
