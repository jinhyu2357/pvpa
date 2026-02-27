package org.example.jinhhyu.pvpa

import net.kyori.adventure.text.Component
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.entity.Projectile
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.player.PlayerItemDamageEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

class Pvpa : JavaPlugin(), Listener {
    private val pvpAllowed: MutableMap<UUID, MutableSet<UUID>> = mutableMapOf()
    private val pendingRequests: MutableMap<UUID, MutableSet<UUID>> = mutableMapOf()
    private val scoreboardEnabled: MutableSet<UUID> = mutableSetOf()
    private val previousScoreboards: MutableMap<UUID, Scoreboard> = mutableMapOf()
    private val pvpListScoreboards: MutableMap<UUID, Scoreboard> = mutableMapOf()

    private val durabilityExemptUntil: MutableMap<UUID, Long> = mutableMapOf()
    private val blockedMessageCooldown: MutableMap<UUID, Long> = mutableMapOf()
    private val runtimeCommandsRegistered: MutableSet<String> = mutableSetOf()

    override fun onEnable() {
        server.pluginManager.registerEvents(this, this)

        registerRuntimeCommand("pvp", "Send a PvP request to a player.", "/pvp <name>")
        registerRuntimeCommand("pvpa", "Accept a player's PvP request.", "/pvpa <name>")
        registerRuntimeCommand("pvpc", "Remove a player from your PvP-allowed list.", "/pvpc <name>")
        registerRuntimeCommand("pvplist", "Show players on your PvP-allowed list.", "/pvplist")
        registerRuntimeCommand("pvpshow", "Toggle showing your PvP list on the scoreboard.", "/pvpshow")
    }

    override fun onDisable() {
        for (playerId in scoreboardEnabled.toList()) {
            val player = server.getPlayer(playerId) ?: continue
            hidePvpListScoreboard(player)
        }

        pvpAllowed.clear()
        pendingRequests.clear()
        scoreboardEnabled.clear()
        previousScoreboards.clear()
        pvpListScoreboards.clear()
        durabilityExemptUntil.clear()
        blockedMessageCooldown.clear()
        runtimeCommandsRegistered.clear()
    }

    private fun registerRuntimeCommand(name: String, descriptionText: String, usageText: String) {
        if (!runtimeCommandsRegistered.add(name.lowercase())) {
            return
        }

        val runtimeCommand = object : Command(name, descriptionText, usageText, emptyList()) {
            override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
                handleCommandInvocation(sender, name, args)
                return true
            }

            override fun tabComplete(
                sender: CommandSender,
                alias: String,
                args: Array<out String>
            ): MutableList<String> {
                if (name.equals("pvplist", ignoreCase = true) || name.equals("pvpshow", ignoreCase = true)) {
                    return mutableListOf()
                }
                return suggestTargets(sender, args).toMutableList()
            }
        }

        val registeredPrimaryLabel = server.commandMap.register(this.name.lowercase(), runtimeCommand)
        if (!registeredPrimaryLabel) {
            logger.warning("Registered /${this.name.lowercase()}:$name because /$name is already taken.")
        }
    }

    private fun handleCommandInvocation(
        sender: CommandSender,
        commandName: String,
        args: Array<out String>
    ) {
        if (sender !is Player) {
            sender.sendMessage("Only players can use this command.")
            return
        }

        when (commandName.lowercase()) {
            "pvplist" -> {
                if (args.isNotEmpty()) {
                    sender.sendMessage("Usage: /pvplist")
                    return
                }
                handlePvpList(sender)
                return
            }
            "pvpshow" -> {
                if (args.isNotEmpty()) {
                    sender.sendMessage("Usage: /pvpshow")
                    return
                }
                handlePvpShow(sender)
                return
            }
        }

        if (args.size != 1) {
            sender.sendMessage("Usage: /${commandName.lowercase()} <name>")
            return
        }

        val target = findOnlinePlayer(args[0])
        if (target == null) {
            sender.sendMessage("Player '${args[0]}' isn't online.")
            return
        }

        if (target.uniqueId == sender.uniqueId) {
            sender.sendMessage("u cannot target urself.")
            return
        }

        when (commandName.lowercase()) {
            "pvp" -> handlePvpRequest(sender, target)
            "pvpa" -> handlePvpAccept(sender, target)
            "pvpc" -> handlePvpCancel(sender, target)
            else -> Unit
        }
    }

    private fun handlePvpList(sender: Player): Boolean {
        val allowed = pvpAllowed[sender.uniqueId]
        if (allowed.isNullOrEmpty()) {
            sender.sendMessage("PvPlist is empty.")
            return true
        }

        val names = allowed
            .asSequence()
            .map { resolvePlayerName(it) }
            .sortedBy { it.lowercase() }
            .toList()

        sender.sendMessage("ur PvPlist (${names.size}):")
        for (name in names) {
            sender.sendMessage("- $name")
        }
        return true
    }

    private fun handlePvpShow(sender: Player): Boolean {
        val senderId = sender.uniqueId
        if (scoreboardEnabled.remove(senderId)) {
            hidePvpListScoreboard(sender)
            sender.sendMessage("PvPlist scoreboard is now hidden.")
            return true
        }

        scoreboardEnabled.add(senderId)
        previousScoreboards[senderId] = sender.scoreboard
        updatePvpListScoreboard(sender)
        sender.sendMessage("PvPlist scoreboard is now visible.")
        return true
    }

    private fun suggestTargets(sender: CommandSender, args: Array<out String>): Collection<String> {
        if (args.size != 1) {
            return emptyList()
        }

        val prefix = args[0].lowercase()
        val senderId = (sender as? Player)?.uniqueId

        return server.onlinePlayers
            .asSequence()
            .filter { senderId == null || it.uniqueId != senderId }
            .map { it.name }
            .filter { it.lowercase().startsWith(prefix) }
            .sorted()
            .toList()
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onEntityDamageByEntity(event: EntityDamageByEntityEvent) {
        val victim = event.entity as? Player ?: return
        val attacker = resolvePlayerDamager(event.damager) ?: return
        if (attacker.uniqueId == victim.uniqueId) {
            return
        }

        val attackerId = attacker.uniqueId
        val victimId = victim.uniqueId

        if (!isMutuallyAllowed(attackerId, victimId)) {
            event.isCancelled = true
            maybeSendBlockedMessage(attacker, victim)
            return
        }

        markDurabilityExempt(attackerId)
        markDurabilityExempt(victimId)
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onPlayerItemDamage(event: PlayerItemDamageEvent) {
        if (isDurabilityExempt(event.player.uniqueId)) {
            event.isCancelled = true
        }
    }

    @EventHandler
    fun onPlayerQuit(event: PlayerQuitEvent) {
        val playerId = event.player.uniqueId
        durabilityExemptUntil.remove(playerId)
        blockedMessageCooldown.remove(playerId)
        scoreboardEnabled.remove(playerId)
        previousScoreboards.remove(playerId)
        pvpListScoreboards.remove(playerId)
    }

    private fun handlePvpRequest(sender: Player, target: Player): Boolean {
        val senderId = sender.uniqueId
        val targetId = target.uniqueId

        // Rule #6: requesting PvP revokes your entry from the target's allowed list.
        removeAllowedOpponent(targetId, senderId)

        pendingRequests.getOrPut(targetId) { mutableSetOf() }.add(senderId)

        updatePvpListScoreboards(senderId, targetId)

        sender.sendMessage("PvP request sent to ${target.name}.")
        target.sendMessage("${sender.name} sent you a PvP request.")
        return true
    }

    private fun handlePvpAccept(sender: Player, target: Player): Boolean {
        val incoming = pendingRequests[sender.uniqueId]
        if (incoming == null || !incoming.remove(target.uniqueId)) {
            sender.sendMessage("No pending PvP request from ${target.name}.")
            return true
        }

        if (incoming.isEmpty()) {
            pendingRequests.remove(sender.uniqueId)
        }

        // Ensure acceptance creates (or restores) mutual PvP allowance for both players.
        addAllowedOpponent(sender.uniqueId, target.uniqueId)
        addAllowedOpponent(target.uniqueId, sender.uniqueId)
        updatePvpListScoreboards(sender.uniqueId, target.uniqueId)

        sender.sendMessage("Accepted ${target.name}'s PvP request.")
        target.sendMessage("${sender.name} accepted ur PvP request.")
        return true
    }

    private fun handlePvpCancel(sender: Player, target: Player): Boolean {
        val senderId = sender.uniqueId
        val targetId = target.uniqueId

        val removedFromSenderAllowed = removeAllowedOpponent(senderId, targetId)
        val removedFromTargetAllowed = removeAllowedOpponent(targetId, senderId)
        val removedIncomingPending = removePendingRequest(senderId, targetId)
        val removedOutgoingPending = removePendingRequest(targetId, senderId)

        if (
            removedFromSenderAllowed ||
            removedFromTargetAllowed ||
            removedIncomingPending ||
            removedOutgoingPending
        ) {
            updatePvpListScoreboards(senderId, targetId)
            sender.sendMessage("${target.name} was removed in ur PvPlist.")
        } else {
            sender.sendMessage("${target.name} isn't on ur PvPlist.")
        }
        return true
    }

    private fun findOnlinePlayer(name: String): Player? {
        return server.onlinePlayers.firstOrNull { it.name.equals(name, ignoreCase = true) }
    }

    private fun resolvePlayerName(playerId: UUID): String {
        val online = server.getPlayer(playerId)
        if (online != null) {
            return online.name
        }

        val offlineName = server.getOfflinePlayer(playerId).name
        return offlineName ?: playerId.toString()
    }

    private fun resolvePlayerDamager(damager: Entity): Player? {
        return when (damager) {
            is Player -> damager
            is Projectile -> damager.shooter as? Player
            else -> null
        }
    }

    private fun isMutuallyAllowed(attackerId: UUID, victimId: UUID): Boolean {
        return isOpponentAllowed(attackerId, victimId) && isOpponentAllowed(victimId, attackerId)
    }

    private fun isOpponentAllowed(playerId: UUID, opponentId: UUID): Boolean {
        return pvpAllowed[playerId]?.contains(opponentId) == true
    }

    private fun addAllowedOpponent(playerId: UUID, opponentId: UUID) {
        pvpAllowed.getOrPut(playerId) { mutableSetOf() }.add(opponentId)
    }

    private fun removeAllowedOpponent(playerId: UUID, opponentId: UUID): Boolean {
        val list = pvpAllowed[playerId] ?: return false
        val removed = list.remove(opponentId)
        if (list.isEmpty()) {
            pvpAllowed.remove(playerId)
        }
        return removed
    }

    private fun removePendingRequest(targetId: UUID, requesterId: UUID): Boolean {
        val incoming = pendingRequests[targetId] ?: return false
        val removed = incoming.remove(requesterId)
        if (incoming.isEmpty()) {
            pendingRequests.remove(targetId)
        }
        return removed
    }

    private fun markDurabilityExempt(playerId: UUID) {
        durabilityExemptUntil[playerId] = System.currentTimeMillis() + DURABILITY_EXEMPT_WINDOW_MS
    }

    private fun isDurabilityExempt(playerId: UUID): Boolean {
        val expiresAt = durabilityExemptUntil[playerId] ?: return false
        if (System.currentTimeMillis() > expiresAt) {
            durabilityExemptUntil.remove(playerId)
            return false
        }
        return true
    }

    private fun maybeSendBlockedMessage(attacker: Player, victim: Player) {
        val now = System.currentTimeMillis()
        val lastSentAt = blockedMessageCooldown[attacker.uniqueId] ?: 0L
        if (now - lastSentAt < BLOCKED_MESSAGE_COOLDOWN_MS) {
            return
        }

        blockedMessageCooldown[attacker.uniqueId] = now
    }

    private fun updatePvpListScoreboards(vararg playerIds: UUID) {
        for (playerId in playerIds.distinct()) {
            if (!scoreboardEnabled.contains(playerId)) {
                continue
            }
            val player = server.getPlayer(playerId) ?: continue
            updatePvpListScoreboard(player)
        }
    }

    private fun updatePvpListScoreboard(player: Player) {
        val manager = server.scoreboardManager
        val playerId = player.uniqueId
        val board = pvpListScoreboards.getOrPut(playerId) { manager.newScoreboard }
        val objective = board.getObjective(PVP_LIST_OBJECTIVE_NAME)
            ?: board.registerNewObjective(PVP_LIST_OBJECTIVE_NAME, Criteria.DUMMY, Component.text(PVP_LIST_TITLE))

        objective.displaySlot = DisplaySlot.SIDEBAR
        board.entries.toList().forEach { board.resetScores(it) }

        val names = pvpAllowed[playerId]
            .orEmpty()
            .asSequence()
            .map { resolvePlayerName(it) }
            .sortedBy { it.lowercase() }
            .toList()

        val lines = mutableListOf<String>()
        lines.add("Total: ${names.size}")

        if (names.isEmpty()) {
            lines.add("(empty)")
        } else {
            val shownNames = names.take(MAX_SCOREBOARD_LISTED_PLAYERS)
            lines.addAll(shownNames)

            val remainingCount = names.size - shownNames.size
            if (remainingCount > 0) {
                lines.add("+$remainingCount more")
            }
        }

        var score = lines.size
        for (line in lines) {
            objective.getScore(line).score = score--
        }

        player.scoreboard = board
    }

    private fun hidePvpListScoreboard(player: Player) {
        val playerId = player.uniqueId
        pvpListScoreboards.remove(playerId)?.getObjective(PVP_LIST_OBJECTIVE_NAME)?.unregister()
        val previous = previousScoreboards.remove(playerId)
        player.scoreboard = previous ?: server.scoreboardManager.mainScoreboard
    }

    companion object {
        private const val DURABILITY_EXEMPT_WINDOW_MS = 300L
        private const val BLOCKED_MESSAGE_COOLDOWN_MS = 1500L
        private const val MAX_SCOREBOARD_LISTED_PLAYERS = 13
        private const val PVP_LIST_OBJECTIVE_NAME = "pvpa_list"
        private const val PVP_LIST_TITLE = "PvPlist"
    }
}
