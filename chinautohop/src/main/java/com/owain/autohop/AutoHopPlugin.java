package com.owain.autohop;

import com.google.inject.Provides;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Experience;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.WorldType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.PlayerSpawned;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetInfo;
import net.runelite.client.chat.ChatColorType;
import net.runelite.client.chat.ChatMessageBuilder;
import net.runelite.client.chat.ChatMessageManager;
import net.runelite.client.chat.QueuedMessage;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.WorldService;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginType;
import net.runelite.client.util.WorldUtil;
import net.runelite.http.api.worlds.World;
import net.runelite.http.api.worlds.WorldResult;
import org.pf4j.Extension;

@Extension
@PluginDescriptor(
	name = "Chin auto hop",
	description = "Hop away from people in the wilderness",
	type = PluginType.MISCELLANEOUS
)
@Slf4j
public class AutoHopPlugin extends Plugin
{
	private static final Pattern WILDERNESS_LEVEL_PATTERN = Pattern.compile("^Level: (\\d+)$");
	private static final int MIN_COMBAT_LEVEL = 3;
	private static final int DISPLAY_SWITCHER_MAX_ATTEMPTS = 3;

	@Inject
	private Client client;

	private int wildernessLevel = -1;

	@Inject
	private ChatMessageManager chatMessageManager;

	@Inject
	private WorldService worldService;

	@Inject
	private AutoHopConfig config;

	private net.runelite.api.World quickHopTargetWorld;
	private int displaySwitcherAttempts = 0;

	@Provides
	AutoHopConfig getConfig(ConfigManager configManager)
	{
		return configManager.getConfig(AutoHopConfig.class);
	}

	@Override
	protected void startUp()
	{
		wildernessLevel();
	}

	@Subscribe
	private void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if ("wildernessWidgetTextSet".equals(event.getEventName()))
		{
			wildernessLevel();
		}
	}

	private void wildernessLevel()
	{
		final Widget wildernessLevelWidget = client.getWidget(WidgetInfo.PVP_WILDERNESS_LEVEL);

		if (wildernessLevelWidget == null)
		{
			wildernessLevel = -1;
			return;
		}

		String wildernessLevelText = wildernessLevelWidget.getText();

		if (wildernessLevelText.contains("<br>"))
		{
			wildernessLevelText = wildernessLevelText.split("<br>")[0];
		}

		final Matcher m = WILDERNESS_LEVEL_PATTERN.matcher(wildernessLevelText);
		if (!m.matches() || WorldType.isPvpWorld(client.getWorldType()))
		{
			wildernessLevel = -1;
			return;
		}

		wildernessLevel = Integer.parseInt(m.group(1));
	}

	private static String combatAttackRange(final int combatLevel, final int wildernessLevel)
	{
		return Math.max(MIN_COMBAT_LEVEL, combatLevel - wildernessLevel) + "-" + Math.min(Experience.MAX_COMBAT_LEVEL, combatLevel + wildernessLevel);
	}

	@Subscribe
	private void onPlayerSpawned(PlayerSpawned event)
	{
		final Player local = client.getLocalPlayer();
		final Player player = event.getPlayer();

		if (wildernessLevel == -1 ||
			local == null ||
			player == null ||
			player.equals(local) ||
			(config.friends() && player.isFriend()) ||
			(config.clanmember() && player.isClanMember()))
		{
			return;
		}

		final int playerCombat = player.getCombatLevel();
		final int combatLevel = local.getCombatLevel();

		final int minimumAttackable = Math.max(MIN_COMBAT_LEVEL, combatLevel - wildernessLevel);
		final int maximumAttackable = Math.min(Experience.MAX_COMBAT_LEVEL, combatLevel + wildernessLevel);

		if (playerCombat >= minimumAttackable && playerCombat <= maximumAttackable)
		{
			hop();
		}
	}

	private void hop()
	{
		WorldResult worldResult = worldService.getWorlds();
		if (worldResult == null || client.getGameState() != GameState.LOGGED_IN)
		{
			return;
		}

		World currentWorld = worldResult.findWorld(client.getWorld());

		if (currentWorld == null)
		{
			return;
		}

		EnumSet<net.runelite.http.api.worlds.WorldType> currentWorldTypes = currentWorld.getTypes().clone();

		currentWorldTypes.remove(net.runelite.http.api.worlds.WorldType.PVP);
		currentWorldTypes.remove(net.runelite.http.api.worlds.WorldType.HIGH_RISK);
		currentWorldTypes.remove(net.runelite.http.api.worlds.WorldType.BOUNTY);
		currentWorldTypes.remove(net.runelite.http.api.worlds.WorldType.SKILL_TOTAL);
		currentWorldTypes.remove(net.runelite.http.api.worlds.WorldType.LAST_MAN_STANDING);

		List<World> worlds = worldResult.getWorlds();

		int totalLevel = client.getTotalLevel();

		World world;
		do
		{
			world = worlds.get(new Random().nextInt(worlds.size()));

			if (world.getId() == currentWorld.getId())
			{
				continue;
			}

			EnumSet<net.runelite.http.api.worlds.WorldType> types = world.getTypes().clone();

			types.remove(net.runelite.http.api.worlds.WorldType.BOUNTY);
			types.remove(net.runelite.http.api.worlds.WorldType.LAST_MAN_STANDING);

			if (types.contains(net.runelite.http.api.worlds.WorldType.SKILL_TOTAL))
			{
				try
				{
					int totalRequirement = Integer.parseInt(world.getActivity().substring(0, world.getActivity().indexOf(" ")));

					if (totalLevel >= totalRequirement)
					{
						types.remove(net.runelite.http.api.worlds.WorldType.SKILL_TOTAL);
					}
				}
				catch (NumberFormatException ex)
				{
					log.warn("Failed to parse total level requirement for target world", ex);
				}
			}

			// Break out if we've found a good world to hop to
			if (currentWorldTypes.equals(types))
			{
				if (!config.american() && !config.unitedkingdom() && !config.australia() && !config.germany())
				{
					break;
				}

				switch (world.getLocation())
				{
					case 0:
						if (config.american())
						{
							break;
						}
					case 1:
						if (config.unitedkingdom())
						{
							break;
						}
					case 3:
						if (config.australia())
						{
							break;
						}
					case 7:
						if (config.germany())
						{
							break;
						}
				}
			}
		}
		while (world != currentWorld);

		hop(world.getId());
	}

	private void hop(int worldId)
	{
		WorldResult worldResult = worldService.getWorlds();
		// Don't try to hop if the world doesn't exist
		World world = worldResult.findWorld(worldId);
		if (world == null)
		{
			return;
		}

		final net.runelite.api.World rsWorld = client.createWorld();
		rsWorld.setActivity(world.getActivity());
		rsWorld.setAddress(world.getAddress());
		rsWorld.setId(world.getId());
		rsWorld.setPlayerCount(world.getPlayers());
		rsWorld.setLocation(world.getLocation());
		rsWorld.setTypes(WorldUtil.toWorldTypes(world.getTypes()));

		if (client.getGameState() == GameState.LOGIN_SCREEN)
		{
			client.changeWorld(rsWorld);
			return;
		}

		String chatMessage = new ChatMessageBuilder()
			.append(ChatColorType.NORMAL)
			.append("Hopping away from a player in the wilderness. New world: ")
			.append(ChatColorType.HIGHLIGHT)
			.append(Integer.toString(world.getId()))
			.append(ChatColorType.NORMAL)
			.append("..")
			.build();

		chatMessageManager
			.queue(QueuedMessage.builder()
				.type(ChatMessageType.CONSOLE)
				.runeLiteFormattedMessage(chatMessage)
				.build());

		quickHopTargetWorld = rsWorld;
		displaySwitcherAttempts = 0;
	}


	@Subscribe
	private void onGameTick(GameTick event)
	{
		if (quickHopTargetWorld == null)
		{
			return;
		}

		if (client.getWidget(WidgetInfo.WORLD_SWITCHER_LIST) == null)
		{
			client.openWorldHopper();

			if (++displaySwitcherAttempts >= DISPLAY_SWITCHER_MAX_ATTEMPTS)
			{
				String chatMessage = new ChatMessageBuilder()
					.append(ChatColorType.NORMAL)
					.append("Failed to quick-hop after ")
					.append(ChatColorType.HIGHLIGHT)
					.append(Integer.toString(displaySwitcherAttempts))
					.append(ChatColorType.NORMAL)
					.append(" attempts.")
					.build();

				chatMessageManager
					.queue(QueuedMessage.builder()
						.type(ChatMessageType.CONSOLE)
						.runeLiteFormattedMessage(chatMessage)
						.build());

				resetQuickHopper();
			}
		}
		else
		{
			client.hopToWorld(quickHopTargetWorld);
			resetQuickHopper();
		}
	}

	@Subscribe
	private void onChatMessage(ChatMessage event)
	{
		if (event.getType() != ChatMessageType.GAMEMESSAGE)
		{
			return;
		}

		if (event.getMessage().equals("Please finish what you're doing before using the World Switcher."))
		{
			resetQuickHopper();
		}
	}

	private void resetQuickHopper()
	{
		displaySwitcherAttempts = 0;
		quickHopTargetWorld = null;
	}
}
