package com.amandhakar.ledgerly.parser

/**
 * Parser-local: kept separate from `:core:database`'s `Direction` rather than shared via
 * `:core:model`, the same way `Paise` was pulled back out of Room entities — a plain enum used as
 * a Room column has not hit that KSP crash, but this module has no reason to risk finding out.
 * Task 1.7/1.9 maps this onto the Room entity's `Direction` when a rule or the review inbox
 * confirms a transaction.
 */
enum class Direction { DEBIT, CREDIT }
