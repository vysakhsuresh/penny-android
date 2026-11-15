package app.penny.core.event

import java.util.UUID

/** Who is acting and on which device; stamped on every event. */
data class Actor(val id: String, val device: String)

fun interface Clock { fun nowMillis(): Long }

fun interface IdGenerator { fun newId(): String }

object SystemClock : Clock { override fun nowMillis(): Long = System.currentTimeMillis() }

object UuidGenerator : IdGenerator { override fun newId(): String = UUID.randomUUID().toString() }
