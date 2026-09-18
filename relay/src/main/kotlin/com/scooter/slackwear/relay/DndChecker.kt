package com.scooter.slackwear.relay

interface DndChecker {
    fun isSnoozed(device: Device): Boolean
}

class WatchDndChecker : DndChecker {
    override fun isSnoozed(device: Device): Boolean = false
}
