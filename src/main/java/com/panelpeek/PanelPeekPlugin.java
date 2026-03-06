package com.panelpeek;

import com.google.inject.Provides;
import java.awt.Rectangle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
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
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayManager;
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

	@Inject
	private ClientToolbar clientToolbar;

	private Field overlaysField;
	private Field infoBoxPluginField;
	private EventBus.Subscriber menuOpenedSubscriber;

	// Reflection handles for side-panel mode
	private Field configPluginNavButtonField;
	private Field configPluginTopLevelConfigPanelField;
	private Method openConfigurationPanelMethod;

	@Provides
	PanelPeekConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PanelPeekConfig.class);
	}

	@Override
	protected void startUp()
	{
		try
		{
			overlaysField = OverlayManager.class.getDeclaredField("overlays");
			overlaysField.setAccessible(true);
			log.info("[PanelPeek] overlays field found OK");
		}
		catch (NoSuchFieldException e)
		{
			log.error("[PanelPeek] Could not find overlays field on OverlayManager", e);
		}

		try
		{
			infoBoxPluginField = InfoBox.class.getDeclaredField("plugin");
			infoBoxPluginField.setAccessible(true);
		}
		catch (NoSuchFieldException e)
		{
			log.error("[PanelPeek] Could not find plugin field on InfoBox", e);
		}

		// Cache reflection handles for side-panel mode (ConfigPlugin internals)
		try
		{
			Class<?> configPluginClass = Class.forName("net.runelite.client.plugins.config.ConfigPlugin");

			configPluginNavButtonField = configPluginClass.getDeclaredField("navButton");
			configPluginNavButtonField.setAccessible(true);

			configPluginTopLevelConfigPanelField = configPluginClass.getDeclaredField("topLevelConfigPanel");
			configPluginTopLevelConfigPanelField.setAccessible(true);

			Class<?> topLevelConfigPanelClass = Class.forName("net.runelite.client.plugins.config.TopLevelConfigPanel");
			openConfigurationPanelMethod = topLevelConfigPanelClass.getDeclaredMethod("openConfigurationPanel", String.class);
			openConfigurationPanelMethod.setAccessible(true);

			log.info("[PanelPeek] Side-panel reflection handles cached OK");
		}
		catch (Exception e)
		{
			log.warn("[PanelPeek] Could not set up side-panel reflection; will fall back to dialog mode", e);
		}

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
		List<Overlay> overlays = getAllOverlays();
		log.debug("[PanelPeek] MenuOpened with ctrl held. Mouse=({},{}) overlays={}", mouse.getX(), mouse.getY(), overlays.size());

		// Iterate in reverse so we hit the topmost (last-rendered) overlay first
		for (int i = overlays.size() - 1; i >= 0; i--)
		{
			Overlay overlay = overlays.get(i);
			Rectangle bounds = overlay.getBounds();

			if (bounds == null || bounds.isEmpty())
			{
				continue;
			}

			if (!bounds.contains(mouse.getX(), mouse.getY()))
			{
				continue;
			}

			// InfoBoxOverlay is a shared container — multiple plugins' info boxes live here
			if (overlay.getClass().getSimpleName().equals("InfoBoxOverlay"))
			{
				addInfoBoxMenuEntries();
				break;
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
				continue;
			}

			addMenuEntryForPlugin(ownerPlugin);
			break;
		}
	}

	@SuppressWarnings("unchecked")
	private List<Overlay> getAllOverlays()
	{
		if (overlaysField == null)
		{
			return Collections.emptyList();
		}

		try
		{
			List<Overlay> overlays = (List<Overlay>) overlaysField.get(overlayManager);
			return overlays != null ? new ArrayList<>(overlays) : Collections.emptyList();
		}
		catch (IllegalAccessException e)
		{
			log.warn("Could not access overlay list", e);
			return Collections.emptyList();
		}
	}

	private void addMenuEntryForPlugin(Plugin ownerPlugin)
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

		client.createMenuEntry(-1)
			.setOption("Open Settings")
			.setTarget("<col=00ff00>" + pluginName + "</col>")
			.setType(MenuAction.RUNELITE)
			.onClick(e -> openConfig(pluginName, cfgClass));
	}

	private void addInfoBoxMenuEntries()
	{
		if (infoBoxPluginField == null)
		{
			return;
		}

		Set<Plugin> seen = new LinkedHashSet<>();
		for (InfoBox box : infoBoxManager.getInfoBoxes())
		{
			try
			{
				Plugin p = (Plugin) infoBoxPluginField.get(box);
				if (p != null && seen.add(p))
				{
					addMenuEntryForPlugin(p);
				}
			}
			catch (IllegalAccessException e)
			{
				// skip
			}
		}
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
			for (Method method : plugin.getClass().getDeclaredMethods())
			{
				if (!method.isAnnotationPresent(Provides.class))
				{
					continue;
				}

				Class<?> returnType = method.getReturnType();
				if (returnType.isInterface()
					&& Config.class.isAssignableFrom(returnType)
					&& returnType.isAnnotationPresent(ConfigGroup.class))
				{
					return (Class<? extends Config>) returnType;
				}
			}

			for (Method method : plugin.getClass().getMethods())
			{
				if (!method.isAnnotationPresent(Provides.class))
				{
					continue;
				}

				Class<?> returnType = method.getReturnType();
				if (returnType.isInterface()
					&& Config.class.isAssignableFrom(returnType)
					&& returnType.isAnnotationPresent(ConfigGroup.class))
				{
					return (Class<? extends Config>) returnType;
				}
			}
		}
		catch (Exception e)
		{
			log.warn("Error scanning config for plugin {}", plugin.getClass().getSimpleName(), e);
		}

		return null;
	}

	private void openConfig(String pluginName, Class<? extends Config> configClass)
	{
		if (config.openMode() == OpenMode.SIDE_PANEL && openInSidePanel(pluginName))
		{
			return;
		}

		openInDialog(pluginName, configClass);
	}

	private boolean openInSidePanel(String pluginName)
	{
		if (configPluginNavButtonField == null || configPluginTopLevelConfigPanelField == null || openConfigurationPanelMethod == null)
		{
			log.debug("[PanelPeek] Side-panel reflection not available, falling back to dialog");
			return false;
		}

		try
		{
			Plugin configPlugin = null;
			for (Plugin p : pluginManager.getPlugins())
			{
				if (p.getClass().getName().equals("net.runelite.client.plugins.config.ConfigPlugin"))
				{
					configPlugin = p;
					break;
				}
			}

			if (configPlugin == null)
			{
				log.warn("[PanelPeek] ConfigPlugin not found");
				return false;
			}

			Object navButton = configPluginNavButtonField.get(configPlugin);
			Object topLevelConfigPanel = configPluginTopLevelConfigPanelField.get(configPlugin);

			if (navButton == null || topLevelConfigPanel == null)
			{
				log.warn("[PanelPeek] ConfigPlugin navButton or topLevelConfigPanel is null");
				return false;
			}

			final NavigationButton nav = (NavigationButton) navButton;
			final Object panel = topLevelConfigPanel;

			SwingUtilities.invokeLater(() ->
			{
				try
				{
					clientToolbar.openPanel(nav);
					openConfigurationPanelMethod.invoke(panel, pluginName);
				}
				catch (Exception e)
				{
					log.error("[PanelPeek] Failed to open side panel for {}", pluginName, e);
				}
			});

			return true;
		}
		catch (Exception e)
		{
			log.error("[PanelPeek] Side-panel reflection failed for {}", pluginName, e);
			return false;
		}
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
