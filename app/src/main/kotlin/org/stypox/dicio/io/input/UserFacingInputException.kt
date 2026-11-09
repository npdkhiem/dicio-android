package org.stypox.dicio.io.input

/**
 * An input failure whose [message] is plain language. The UI shows and speaks
 * this text as-is, instead of the generic "could not evaluate" error.
 */
class UserFacingInputException(message: String) : Exception(message)
