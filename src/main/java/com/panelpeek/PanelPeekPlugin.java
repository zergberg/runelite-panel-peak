/*
 * Copyright (c) 2025, zergberg
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.panelpeek;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.Provides;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.Point;
import net.runelite.api.events.MenuOpened;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigDescriptor;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.PluginManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.events.OverlayMenuClicked;
import net.runelite.client.ui.overlay.OverlayMenuEntry;
import net.runelite.client.ui.overlay.infobox.InfoBox;
import net.runelite.client.ui.overlay.infobox.InfoBoxManager;

@PluginDescriptor(
	name = "Panel Peek",
	description = "Ctrl+right-click any overlay on the canvas to peek at that plugin's settings",
	tags = {"panel", "peek", "overlay", "config", "settings", "right-click", "pp"}
)
@Slf4j
public class PanelPeekPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private EventBus eventBus;

	@Inject
	private PluginManager pluginManager;

	@Inject
	private InfoBoxManager infoBoxManager;

	@Inject
	private PanelPeekConfig config;

	private EventBus.Subscriber menuOpenedSubscriber;

	@Provides
	PanelPeekConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PanelPeekConfig.class);
	}

	@Override
	protected void startUp()
	{
		menuOpenedSubscriber = eventBus.register(MenuOpened.class, this::onMenuOpened, 0f);
		log.info("[PanelPeek] Started OK");
	}

	@Override
	protected void shutDown()
	{
		if (menuOpenedSubscriber != null)
		{
			eventBus.unregister(menuOpenedSubscriber);
			menuOpenedSubscriber = null;
		}
	}

	private void onMenuOpened(MenuOpened event)
	{
		if (!client.isKeyPressed(KeyCode.KC_CONTROL))
		{
			return;
		}

		Point mouse = client.getMouseCanvasPosition();
		int mouseX = mouse.getX();
		int mouseY = mouse.getY();

		Overlay overlay = findOverlayAtMouse(mouseX, mouseY);
		if (overlay == null)
		{
			return;
		}

		log.debug("[PanelPeek] MenuOpened with ctrl held. Mouse=({},{}) hit={}",
			mouseX, mouseY, overlay.getClass().getSimpleName());

		// InfoBoxOverlay is a shared container — multiple plugins' info boxes live here
		if (overlay.getClass().getSimpleName().equals("InfoBoxOverlay"))
		{
			addInfoBoxMenuEntries(overlay);
			return;
		}

		Plugin ownerPlugin = overlay.getPlugin();
		if (ownerPlugin == null)
		{
			ownerPlugin = findPluginByPackage(overlay);
		}
		if (ownerPlugin == null)
		{
			log.debug("[PanelPeek] Overlay {} has no plugin owner (pkg={}), skipping",
				overlay.getClass().getSimpleName(), overlay.getClass().getPackage().getName());
			return;
		}

		addMenuEntryForPlugin(ownerPlugin, overlay);
	}

	private Overlay findOverlayAtMouse(int mouseX, int mouseY)
	{
		List<Overlay> matched = new ArrayList<>();
		overlayManager.anyMatch(overlay ->
		{
			Rectangle bounds = overlay.getBounds();
			if (bounds != null && !bounds.isEmpty() && bounds.contains(mouseX, mouseY))
			{
				matched.add(overlay);
			}
			return false; // iterate all
		});
		return matched.isEmpty() ? null : matched.get(matched.size() - 1);
	}

	private void addMenuEntryForPlugin(Plugin ownerPlugin, Overlay overlay)
	{
		Class<? extends Config> configClass = findConfigClass(ownerPlugin);
		if (configClass == null)
		{
			log.debug("[PanelPeek] No config class found for plugin {}", ownerPlugin.getClass().getSimpleName());
			return;
		}

		PluginDescriptor desc = ownerPlugin.getClass().getAnnotation(PluginDescriptor.class);
		String pluginName = desc != null ? desc.name() : ownerPlugin.getClass().getSimpleName();
		final Class<? extends Config> cfgClass = configClass;
		final Plugin plugin = ownerPlugin;

		client.createMenuEntry(-1)
			.setOption("Open Settings")
			.setTarget("<col=00ff00>" + pluginName + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> openConfig(pluginName, cfgClass, overlay, plugin));
	}

	private void addInfoBoxMenuEntries(Overlay infoBoxOverlay)
	{
		Set<Plugin> seen = new LinkedHashSet<>();
		for (InfoBox box : infoBoxManager.getInfoBoxes())
		{
			String name = box.getName();
			if (name == null)
			{
				continue;
			}

			// InfoBox.getName() returns "PluginSimpleName_InfoBoxSimpleName"
			int sep = name.indexOf('_');
			String pluginSimpleName = sep > 0 ? name.substring(0, sep) : name;

			Plugin plugin = findPluginBySimpleName(pluginSimpleName);
			if (plugin != null && seen.add(plugin))
			{
				addMenuEntryForPlugin(plugin, infoBoxOverlay);
			}
		}
	}

	private Plugin findPluginBySimpleName(String simpleName)
	{
		for (Plugin p : pluginManager.getPlugins())
		{
			if (p.getClass().getSimpleName().equals(simpleName))
			{
				return p;
			}
		}
		return null;
	}

	private Plugin findPluginByPackage(Overlay overlay)
	{
		String overlayPkg = overlay.getClass().getPackage().getName();
		for (Plugin p : pluginManager.getPlugins())
		{
			if (p.getClass().getPackage().getName().equals(overlayPkg))
			{
				return p;
			}
		}
		return null;
	}

	@SuppressWarnings("unchecked")
	private Class<? extends Config> findConfigClass(Plugin plugin)
	{
		try
		{
			Injector injector = plugin.getInjector();
			if (injector == null)
			{
				return null;
			}

			for (Key<?> key : injector.getBindings().keySet())
			{
				Class<?> type = key.getTypeLiteral().getRawType();
				if (type.isInterface()
					&& type != Config.class
					&& Config.class.isAssignableFrom(type)
					&& type.isAnnotationPresent(ConfigGroup.class))
				{
					return (Class<? extends Config>) type;
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Error scanning config for plugin {}", plugin.getClass().getSimpleName(), e);
		}

		return null;
	}

	private void openConfig(String pluginName, Class<? extends Config> cfgClass, Overlay overlay, Plugin plugin)
	{
		if (config.openMode() == OpenMode.SIDE_PANEL && openInSidePanel(pluginName, overlay, plugin))
		{
			return;
		}

		openInDialog(pluginName, cfgClass);
	}

	private boolean openInSidePanel(String pluginName, Overlay overlay, Plugin plugin)
	{
		// Find an overlay owned by this plugin to use with OverlayMenuClicked
		Overlay targetOverlay = overlay;
		if (targetOverlay == null || !plugin.equals(targetOverlay.getPlugin()))
		{
			// InfoBox case or no direct match — find any overlay owned by this plugin
			List<Overlay> pluginOverlays = new ArrayList<>();
			overlayManager.anyMatch(o ->
			{
				if (plugin.equals(o.getPlugin()))
				{
					pluginOverlays.add(o);
				}
				return false;
			});

			if (pluginOverlays.isEmpty())
			{
				log.debug("[PanelPeek] No overlay found for plugin {}, falling back to dialog", pluginName);
				return false;
			}
			targetOverlay = pluginOverlays.get(0);
		}

		OverlayMenuEntry entry = new OverlayMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", pluginName);
		eventBus.post(new OverlayMenuClicked(entry, targetOverlay));
		return true;
	}

	private void openInDialog(String pluginName, Class<? extends Config> configClass)
	{
		try
		{
			Config configProxy = configManager.getConfig(configClass);
			ConfigDescriptor descriptor = configManager.getConfigDescriptor(configProxy);

			if (descriptor == null)
			{
				log.warn("No config descriptor found for {}", pluginName);
				return;
			}

			SwingUtilities.invokeLater(() ->
			{
				ConfigDialog dialog = new ConfigDialog(pluginName, descriptor, configManager);
				dialog.setVisible(true);
			});
		}
		catch (Exception e)
		{
			log.error("Failed to open config for {}", pluginName, e);
		}
	}
}
